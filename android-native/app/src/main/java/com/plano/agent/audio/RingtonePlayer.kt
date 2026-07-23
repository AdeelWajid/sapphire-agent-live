package com.plano.agent.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Loud dual-tone calling ringtone on the media/speaker path.
 * Plays before MODE_IN_COMMUNICATION so it is not duckled into the earpiece.
 */
class RingtonePlayer(context: Context) {
    companion object {
        private const val TAG = "RingtonePlayer"
        private const val SAMPLE_RATE = 24_000
    }

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var track: AudioTrack? = null
    @Volatile private var playing = false
    private var savedMusicVolume: Int? = null
    private var savedMode: Int? = null
    private var savedSpeaker: Boolean? = null

    fun play(durationMs: Long = 2200L, onDone: (() -> Unit)? = null) {
        stop()
        playing = true

        // Use media stream + speaker so the ring is loud (not voice-call / earpiece).
        try {
            savedMode = audioManager.mode
            @Suppress("DEPRECATION")
            savedSpeaker = audioManager.isSpeakerphoneOn
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true

            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            // Raise to near-max for the ring; restore on stop.
            val target = (maxVol * 0.95f).toInt().coerceAtLeast(1)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not boost media volume", e)
        }

        val pcm = buildRingtonePcm(durationMs)
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "Invalid AudioTrack buffer size: $minBuf")
            restoreAudio()
            playing = false
            onDone?.invoke()
            return
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val bufferSize = minBuf.coerceAtLeast(pcm.size).coerceAtLeast(SAMPLE_RATE / 5 * 2)

        val t = try {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            builder.build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack create failed", e)
            restoreAudio()
            playing = false
            onDone?.invoke()
            return
        }

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized")
            t.release()
            restoreAudio()
            playing = false
            onDone?.invoke()
            return
        }

        track = t
        t.play()

        thread(name = "ringtone-play", isDaemon = true) {
            try {
                var offset = 0
                while (playing && offset < pcm.size) {
                    val n = t.write(pcm, offset, min(pcm.size - offset, bufferSize))
                    if (n < 0) {
                        Log.e(TAG, "AudioTrack write error: $n")
                        break
                    }
                    if (n == 0) {
                        Thread.sleep(5)
                        continue
                    }
                    offset += n
                }
                // Let the last buffer drain.
                if (playing) Thread.sleep(80)
            } catch (e: Exception) {
                Log.e(TAG, "Ringtone playback failed", e)
            } finally {
                if (playing) {
                    stop()
                    onDone?.invoke()
                }
            }
        }
    }

    fun stop() {
        playing = false
        try {
            track?.pause()
            track?.flush()
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        restoreAudio()
    }

    private fun restoreAudio() {
        try {
            savedMusicVolume?.let {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
            }
        } catch (_: Exception) {
        }
        savedMusicVolume = null
        try {
            savedMode?.let { audioManager.mode = it }
            savedSpeaker?.let {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = it
            }
        } catch (_: Exception) {
        }
        savedMode = null
        savedSpeaker = null
    }

    /** Classic dual-tone ring bursts at near-full amplitude. */
    private fun buildRingtonePcm(durationMs: Long): ByteArray {
        val totalSamples = ((durationMs / 1000.0) * SAMPLE_RATE).toInt().coerceAtLeast(SAMPLE_RATE)
        val pcm = ShortArray(totalSamples)
        val amp = 0.92 // loud

        fun writeBurst(startSample: Int, lengthSamples: Int) {
            for (i in 0 until lengthSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val env = when {
                    i < SAMPLE_RATE * 0.02 -> i / (SAMPLE_RATE * 0.02)
                    i > lengthSamples - SAMPLE_RATE * 0.04 ->
                        ((lengthSamples - i) / (SAMPLE_RATE * 0.04)).coerceAtLeast(0.0)
                    else -> 1.0
                }
                val sample =
                    (amp * 0.55 * sin(2 * PI * 440 * t) +
                        amp * 0.55 * sin(2 * PI * 480 * t)) * env
                val idx = startSample + i
                if (idx in pcm.indices) {
                    val clipped = sample.coerceIn(-1.0, 1.0)
                    pcm[idx] = (clipped * Short.MAX_VALUE).toInt().toShort()
                }
            }
        }

        val burst = (0.42 * SAMPLE_RATE).toInt()
        // ring-ring … pause … ring-ring (covers ~2.2s)
        writeBurst((0.04 * SAMPLE_RATE).toInt(), burst)
        writeBurst((0.52 * SAMPLE_RATE).toInt(), burst)
        writeBurst((1.20 * SAMPLE_RATE).toInt(), burst)
        writeBurst((1.68 * SAMPLE_RATE).toInt(), burst)

        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            bytes[i * 2] = (v and 0xff).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return bytes
    }
}
