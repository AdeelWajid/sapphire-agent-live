package com.sapphire.agent.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Base64
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Captures PCM16 mono @ 16 kHz for uplink and plays PCM16 mono @ 24 kHz downlink.
 *
 * Playback runs on a dedicated thread so UI toggles stay responsive while the agent speaks.
 */
class AudioEngine(
    private val onPcmChunk: (base64: String) -> Unit,
    private val onSpeechLevel: ((rms: Double, frameMs: Double) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AudioEngine"
        const val INPUT_RATE = 16_000
        const val OUTPUT_RATE = 24_000
        private const val MAX_QUEUE_CHUNKS = 48 // drop oldest if agent bursts ahead
    }

    private enum class PlayCmd { CHUNK, FLUSH, SHUTDOWN }

    private data class PlayItem(val cmd: PlayCmd, val data: ByteArray? = null)

    private val capturing = AtomicBoolean(false)
    private val sending = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)

    @Volatile private var recordThread: Thread? = null
    @Volatile private var playThread: Thread? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null

    private val playQueue = LinkedBlockingQueue<PlayItem>(MAX_QUEUE_CHUNKS)

    fun startCapture(sendImmediately: Boolean) {
        if (capturing.getAndSet(true)) {
            setSending(sendImmediately)
            return
        }
        sending.set(sendImmediately)

        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBuf * 2).coerceAtLeast(INPUT_RATE / 10 * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            capturing.set(false)
            recorder.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }
        audioRecord = recorder
        recorder.startRecording()

        recordThread = thread(name = "pcm-capture", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buf = ByteArray(bufferSize)
            while (capturing.get()) {
                val n = try {
                    recorder.read(buf, 0, buf.size)
                } catch (e: Exception) {
                    Log.e(TAG, "read failed", e)
                    break
                }
                if (n <= 0) continue

                val sampleCount = n / 2
                if (sampleCount > 0 && onSpeechLevel != null) {
                    var sum = 0.0
                    var i = 0
                    while (i + 1 < n) {
                        val sample = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
                        val s = sample.toShort().toDouble() / Short.MAX_VALUE
                        sum += s * s
                        i += 2
                    }
                    val rms = sqrt(sum / sampleCount)
                    val frameMs = sampleCount * 1000.0 / INPUT_RATE
                    onSpeechLevel.invoke(rms, frameMs)
                }

                if (sending.get()) {
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    onPcmChunk(Base64.encodeToString(chunk, Base64.NO_WRAP))
                }
            }
        }
    }

    fun setSending(enabled: Boolean) {
        sending.set(enabled)
    }

    fun stopCapture() {
        capturing.set(false)
        sending.set(false)
        try {
            recordThread?.join(300)
        } catch (_: InterruptedException) {
        }
        recordThread = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    /** Non-blocking: decode + write happen on the playback thread. */
    fun playPcmBase64(base64: String) {
        ensurePlayThread()
        val bytes = try {
            Base64.decode(base64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "bad audio base64", e)
            return
        }
        if (bytes.isEmpty()) return

        // Keep UI snappy: if queue is full, drop oldest queued speech.
        while (!playQueue.offer(PlayItem(PlayCmd.CHUNK, bytes))) {
            playQueue.poll()
        }
    }

    /** Instant from UI: clears pending audio and flushes the track on the play thread. */
    fun stopPlayback() {
        playQueue.clear()
        playQueue.offer(PlayItem(PlayCmd.FLUSH))
    }

    private fun ensurePlayThread() {
        if (playing.get()) return
        synchronized(this) {
            if (playing.get()) return
            playing.set(true)
            playThread = thread(name = "pcm-playback", isDaemon = true) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                playbackLoop()
            }
        }
    }

    private fun playbackLoop() {
        try {
            while (playing.get()) {
                val item = try {
                    playQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                } catch (_: InterruptedException) {
                    break
                }

                when (item.cmd) {
                    PlayCmd.SHUTDOWN -> break
                    PlayCmd.FLUSH -> {
                        flushTrack()
                    }
                    PlayCmd.CHUNK -> {
                        val data = item.data ?: continue
                        val track = ensureTrack() ?: continue
                        var offset = 0
                        while (offset < data.size && playing.get()) {
                            // Abort mid-write if a flush was requested (toggle / barge-in).
                            if (playQueue.peek()?.cmd == PlayCmd.FLUSH) break
                            val written = try {
                                track.write(data, offset, data.size - offset)
                            } catch (e: Exception) {
                                Log.e(TAG, "playback write failed", e)
                                break
                            }
                            if (written <= 0) break
                            offset += written
                        }
                    }
                }
            }
        } finally {
            releaseTrack()
            playing.set(false)
        }
    }

    private fun ensureTrack(): AudioTrack? {
        audioTrack?.let { return it }
        return try {
            val minBuf = AudioTrack.getMinBufferSize(
                OUTPUT_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // Smaller buffer = less latency when stopping / toggling mid-speech.
            val buf = minBuf.coerceAtLeast(OUTPUT_RATE / 20 * 2) // ~50ms
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUTPUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track.play()
            audioTrack = track
            track
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack init failed", e)
            null
        }
    }

    private fun flushTrack() {
        try {
            val track = audioTrack ?: return
            track.pause()
            track.flush()
            track.play()
        } catch (_: Exception) {
        }
    }

    private fun releaseTrack() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
    }

    fun release() {
        stopCapture()
        playing.set(false)
        playQueue.clear()
        playQueue.offer(PlayItem(PlayCmd.SHUTDOWN))
        try {
            playThread?.join(400)
        } catch (_: InterruptedException) {
        }
        playThread = null
        releaseTrack()
    }
}
