package com.plano.agent.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log

/**
 * Routes call audio between loudspeaker and earpiece.
 * When the proximity sensor detects the phone near the ear, forces earpiece
 * (and can blank the screen) like a native phone call.
 */
class CallAudioRouter(
    context: Context,
    private val onRouteChanged: (loudspeaker: Boolean, proximityNear: Boolean) -> Unit = { _, _ -> }
) : SensorEventListener {

    companion object {
        private const val TAG = "CallAudioRouter"
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    /** User preference from the Audio button (true = loudspeaker). */
    var userWantsLoudspeaker: Boolean = true
        private set

    /** True when something is close to the proximity sensor (near ear). */
    var proximityNear: Boolean = false
        private set

    private var active = false
    private var proximityWakeLock: PowerManager.WakeLock? = null

    val hasProximitySensor: Boolean get() = proximitySensor != null

    /** Effective loudspeaker state after proximity override. */
    val isLoudspeakerActive: Boolean
        get() = userWantsLoudspeaker && !proximityNear

    fun startCallAudio(defaultLoudspeaker: Boolean = true) {
        active = true
        userWantsLoudspeaker = defaultLoudspeaker
        proximityNear = false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        applyRoute()
        startProximity()
    }

    fun stopCallAudio() {
        active = false
        stopProximity()
        releaseProximityWakeLock()
        proximityNear = false
        try {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        } catch (_: Exception) {
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun setUserLoudspeaker(enabled: Boolean) {
        userWantsLoudspeaker = enabled
        applyRoute()
    }

    fun toggleUserLoudspeaker(): Boolean {
        setUserLoudspeaker(!userWantsLoudspeaker)
        return userWantsLoudspeaker
    }

    private fun startProximity() {
        val sensor = proximitySensor
        if (sensor == null) {
            Log.i(TAG, "No proximity sensor — loudspeaker/earpiece only via Audio button")
            return
        }
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun stopProximity() {
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }
    }

    private fun applyRoute() {
        if (!active) return
        val loud = isLoudspeakerActive
        try {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = loud
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set speakerphone", e)
        }

        // Blank screen only when held to ear (earpiece path), like Phone app.
        if (proximityNear && !loud) {
            acquireProximityWakeLock()
        } else {
            releaseProximityWakeLock()
        }

        onRouteChanged(loud, proximityNear)
    }

    private fun acquireProximityWakeLock() {
        if (proximityWakeLock?.isHeld == true) return
        try {
            @Suppress("DEPRECATION")
            val lock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "planoagent:proximity"
            )
            lock.setReferenceCounted(false)
            lock.acquire(60 * 60 * 1000L) // max 1h call
            proximityWakeLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "Proximity wake lock unavailable", e)
        }
    }

    private fun releaseProximityWakeLock() {
        try {
            proximityWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        proximityWakeLock = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!active || event?.sensor?.type != Sensor.TYPE_PROXIMITY) return
        val distance = event.values.firstOrNull() ?: return
        val max = event.sensor.maximumRange
        // Near when reading is well below max range (typically 0 vs ~5cm).
        val near = distance < max.coerceAtLeast(0.1f)
        if (near == proximityNear) return
        proximityNear = near
        Log.d(TAG, "Proximity near=$near distance=$distance max=$max")
        applyRoute()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun release() {
        stopCallAudio()
    }
}
