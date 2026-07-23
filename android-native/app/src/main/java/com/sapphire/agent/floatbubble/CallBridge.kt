package com.sapphire.agent.floatbubble

import com.sapphire.agent.live.CallState
import java.lang.ref.WeakReference

data class FloatUiState(
    val callState: CallState = CallState.IDLE,
    val muted: Boolean = false,
    val speakerOn: Boolean = true,
    val usePtt: Boolean = false
)

/**
 * Bridge between [FloatingCallService] UI and [com.sapphire.agent.MainActivity] call logic.
 */
object CallBridge {
    interface Host {
        fun floatStartCall()
        fun floatEndCall()
        fun floatToggleMute()
        fun floatToggleSpeaker()
        fun floatOpenProducts()
        fun floatOpenApp()
        fun floatToggleMicMode()
        fun currentFloatState(): FloatUiState
    }

    @Volatile private var hostRef: WeakReference<Host>? = null
    @Volatile private var listener: ((FloatUiState) -> Unit)? = null

    fun register(host: Host) {
        hostRef = WeakReference(host)
        publish(host.currentFloatState())
    }

    fun unregister(host: Host) {
        if (hostRef?.get() === host) hostRef = null
    }

    fun setStateListener(l: ((FloatUiState) -> Unit)?) {
        listener = l
        hostRef?.get()?.currentFloatState()?.let { publish(it) }
    }

    fun publish(state: FloatUiState) {
        listener?.invoke(state)
    }

    fun startCall() = hostRef?.get()?.floatStartCall()
    fun endCall() = hostRef?.get()?.floatEndCall()
    fun toggleMute() = hostRef?.get()?.floatToggleMute()
    fun toggleSpeaker() = hostRef?.get()?.floatToggleSpeaker()
    fun openProducts() = hostRef?.get()?.floatOpenProducts()
    fun openApp() = hostRef?.get()?.floatOpenApp()
    fun toggleMicMode() = hostRef?.get()?.floatToggleMicMode()
    fun state(): FloatUiState = hostRef?.get()?.currentFloatState() ?: FloatUiState()
}
