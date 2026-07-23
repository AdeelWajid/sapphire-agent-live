package com.sapphire.agent.floatbubble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sapphire.agent.MainActivity
import com.sapphire.agent.R
import com.sapphire.agent.live.CallState
import kotlin.math.abs
import kotlin.math.min

/**
 * Always-available floating PA bubble (idle or in-call).
 * Tap to expand controls; drag and release to snap to the nearest screen edge.
 */
class FloatingCallService : Service() {

    companion object {
        private const val TAG = "FloatingCallService"
        const val CHANNEL_ID = "plano_call_ongoing"
        const val NOTIFICATION_ID = 1001

        const val ACTION_ENSURE = "com.sapphire.agent.float.ENSURE"
        const val ACTION_SHOW_BUBBLE = "com.sapphire.agent.float.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.sapphire.agent.float.HIDE_BUBBLE"
        const val ACTION_IN_CALL = "com.sapphire.agent.float.IN_CALL"
        const val ACTION_IDLE = "com.sapphire.agent.float.IDLE"
        const val ACTION_STOP = "com.sapphire.agent.float.STOP"
        const val ACTION_DISMISS = "com.sapphire.agent.float.DISMISS"
        const val EXTRA_STATUS = "status"

        private const val PREFS = "float_bubble"
        private const val KEY_DISMISSED = "dismissed_by_user"

        fun ensure(context: Context) = start(context, ACTION_ENSURE)
        fun showBubble(context: Context) = start(context, ACTION_SHOW_BUBBLE)
        fun hideBubble(context: Context) = start(context, ACTION_HIDE_BUBBLE)
        fun enterInCall(context: Context, status: String) =
            start(context, ACTION_IN_CALL, status)
        fun enterIdle(context: Context) = start(context, ACTION_IDLE)
        fun stop(context: Context) = start(context, ACTION_STOP)
        fun dismiss(context: Context) = start(context, ACTION_DISMISS)

        fun isDismissedByUser(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DISMISSED, false)

        fun setDismissedByUser(context: Context, dismissed: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DISMISSED, dismissed)
                .apply()
        }

        private fun start(context: Context, action: String, status: String? = null) {
            val intent = Intent(context, FloatingCallService::class.java).apply {
                this.action = action
                if (status != null) putExtra(EXTRA_STATUS, status)
            }
            try {
                if (action == ACTION_ENSURE || action == ACTION_IN_CALL) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "start $action failed", e)
            }
        }

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var trayExpanded = false
    private var inCallMode = false
    private var foregroundStarted = false
    /** True only while the app is minimized and the bubble should be visible. */
    private var bubbleDesired = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CallBridge.setStateListener { state ->
            mainHandler.post { applyState(state) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                CallBridge.setStateListener(null)
                bubbleDesired = false
                removeOverlay()
                stopForegroundSafely()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISMISS -> {
                setDismissedByUser(this, true)
                bubbleDesired = false
                removeOverlay()
                if (!inCallMode) {
                    CallBridge.setStateListener(null)
                    stopForegroundSafely()
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            ACTION_HIDE_BUBBLE -> {
                bubbleDesired = false
                removeOverlay()
                return START_STICKY
            }
            ACTION_SHOW_BUBBLE -> {
                if (isDismissedByUser(this)) {
                    return START_STICKY
                }
                bubbleDesired = true
                if (!foregroundStarted) {
                    startAsForeground(
                        if (inCallMode) getString(R.string.call_ongoing_status)
                        else getString(R.string.float_ready_status),
                        microphone = inCallMode
                    )
                }
                attachOverlay()
                applyState(CallBridge.state())
                return START_STICKY
            }
            ACTION_ENSURE -> {
                // Keep service/notification alive — do NOT show the bubble while app may be open.
                startAsForeground(
                    intent.getStringExtra(EXTRA_STATUS)
                        ?: if (inCallMode) getString(R.string.call_ongoing_status)
                        else getString(R.string.float_ready_status),
                    microphone = inCallMode
                )
                if (bubbleDesired) {
                    attachOverlay()
                    applyState(CallBridge.state())
                } else {
                    removeOverlay()
                }
                return START_STICKY
            }
            ACTION_IN_CALL -> {
                inCallMode = true
                startAsForeground(
                    intent.getStringExtra(EXTRA_STATUS)
                        ?: getString(R.string.call_ongoing_status),
                    microphone = true
                )
                // Don't force-show bubble; only if user already minimized (bubbleDesired).
                if (bubbleDesired) {
                    attachOverlay()
                    applyState(CallBridge.state())
                } else {
                    removeOverlay()
                }
                return START_STICKY
            }
            ACTION_IDLE -> {
                inCallMode = false
                startAsForeground(getString(R.string.float_ready_status), microphone = false)
                if (bubbleDesired) {
                    attachOverlay()
                    applyState(CallBridge.state())
                } else {
                    removeOverlay()
                }
                return START_STICKY
            }
            else -> {
                startAsForeground(getString(R.string.float_ready_status), microphone = false)
                removeOverlay()
                return START_STICKY
            }
        }
    }

    private fun startAsForeground(status: String, microphone: Boolean) {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_phone)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            val hasMic = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (microphone && hasMic &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } catch (e: SecurityException) {
            Log.e(TAG, "mic FGS blocked, plain FGS", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
                foregroundStarted = true
            } catch (e2: Exception) {
                Log.e(TAG, "FGS failed", e2)
            }
        }
    }

    private fun stopForegroundSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        foregroundStarted = false
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.call_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.call_notification_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun attachOverlay() {
        if (!bubbleDesired) {
            removeOverlay()
            return
        }
        if (rootView != null) return
        if (!canDrawOverlays(this)) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.view_float_bubble, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - (76 * density)).toInt()
                .coerceAtLeast((8 * density).toInt())
            y = (180 * density).toInt()
        }

        wireInteractions(view, lp)

        try {
            windowManager?.addView(view, lp)
            rootView = view
            params = lp
            applyState(CallBridge.state())
        } catch (e: Exception) {
            Log.e(TAG, "overlay add failed", e)
        }
    }

    private fun wireInteractions(view: View, lp: WindowManager.LayoutParams) {
        val bubble = view.findViewById<View>(R.id.bubble)
        val tray = view.findViewById<LinearLayout>(R.id.actionTray)

        view.findViewById<ImageButton>(R.id.btnFloatCall).setOnClickListener {
            val active = CallBridge.state().callState == CallState.CALLING ||
                CallBridge.state().callState == CallState.CONNECTED
            if (active) CallBridge.endCall() else {
                CallBridge.openApp()
                mainHandler.postDelayed({ CallBridge.startCall() }, 350)
            }
            collapseTray(tray)
        }
        view.findViewById<ImageButton>(R.id.btnFloatMute).setOnClickListener {
            CallBridge.toggleMute()
        }
        view.findViewById<ImageButton>(R.id.btnFloatSpeaker).setOnClickListener {
            CallBridge.toggleSpeaker()
        }
        view.findViewById<ImageButton>(R.id.btnFloatProducts).setOnClickListener {
            CallBridge.openApp()
            mainHandler.postDelayed({ CallBridge.openProducts() }, 350)
            collapseTray(tray)
        }
        view.findViewById<ImageButton>(R.id.btnFloatMicMode).setOnClickListener {
            CallBridge.toggleMicMode()
        }
        view.findViewById<ImageButton>(R.id.btnFloatOpen).setOnClickListener {
            CallBridge.openApp()
            collapseTray(tray)
        }
        view.findViewById<ImageButton>(R.id.btnFloatDismiss).setOnClickListener {
            collapseTray(tray)
            dismiss(this)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        moved = true
                        if (trayExpanded) collapseTray(tray)
                    }
                    lp.x = startX + dx
                    lp.y = startY + dy
                    try {
                        windowManager?.updateViewLayout(view, lp)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) {
                        toggleTray(tray, view, lp)
                    } else {
                        snapToNearestEdge(view, lp)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleTray(tray: View, root: View, lp: WindowManager.LayoutParams) {
        if (trayExpanded) collapseTray(tray) else expandTray(tray, root, lp)
    }

    private fun expandTray(tray: View, root: View, lp: WindowManager.LayoutParams) {
        // Never remove/re-add children (crashes mid-touch). Just show the tray.
        tray.visibility = View.VISIBLE
        trayExpanded = true
        val dm = resources.displayMetrics
        val margin = (8 * dm.density).toInt()
        root.post {
            val w = root.width.coerceAtLeast(1)
            val h = root.height.coerceAtLeast(1)
            // If menu would clip off the top, shift the whole window down.
            if (lp.y < margin) lp.y = margin
            if (lp.y + h > dm.heightPixels - margin) {
                lp.y = (dm.heightPixels - h - margin).coerceAtLeast(margin)
            }
            if (lp.x < margin) lp.x = margin
            if (lp.x + w > dm.widthPixels - margin) {
                lp.x = (dm.widthPixels - w - margin).coerceAtLeast(margin)
            }
            try {
                windowManager?.updateViewLayout(root, lp)
            } catch (_: Exception) {
            }
        }
    }

    private fun collapseTray(tray: View) {
        tray.visibility = View.GONE
        trayExpanded = false
    }

    private fun snapToNearestEdge(root: View, lp: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val margin = (8 * dm.density).toInt()
        val bubbleSize = (64 * dm.density).toInt()
        // Snap using bubble size so collapsed position stays consistent.
        val w = if (trayExpanded) root.width.coerceAtLeast(bubbleSize) else bubbleSize
        val h = if (trayExpanded) root.height.coerceAtLeast(bubbleSize) else bubbleSize
        val cx = lp.x + w / 2f
        val cy = lp.y + h / 2f

        val distLeft = cx
        val distRight = dm.widthPixels - cx
        val distTop = cy
        val distBottom = dm.heightPixels - cy

        val minDist = min(min(distLeft, distRight), min(distTop, distBottom))
        when (minDist) {
            distLeft -> {
                lp.x = margin
                lp.y = lp.y.coerceIn(margin, dm.heightPixels - h - margin)
            }
            distRight -> {
                lp.x = dm.widthPixels - w - margin
                lp.y = lp.y.coerceIn(margin, dm.heightPixels - h - margin)
            }
            distTop -> {
                lp.y = margin
                lp.x = lp.x.coerceIn(margin, dm.widthPixels - w - margin)
            }
            else -> {
                lp.y = dm.heightPixels - h - margin
                lp.x = lp.x.coerceIn(margin, dm.widthPixels - w - margin)
            }
        }
        try {
            windowManager?.updateViewLayout(root, lp)
        } catch (_: Exception) {
        }
    }

    private fun applyState(state: FloatUiState) {
        val root = rootView ?: return
        val pulse = root.findViewById<View>(R.id.pulseDot)
        val callBtn = root.findViewById<ImageButton>(R.id.btnFloatCall)
        val callLabel = root.findViewById<TextView>(R.id.labelFloatCall)
        val muteBtn = root.findViewById<ImageButton>(R.id.btnFloatMute)
        val speakerBtn = root.findViewById<ImageButton>(R.id.btnFloatSpeaker)
        val micModeLabel = root.findViewById<TextView>(R.id.labelFloatMicMode)
        val active = state.callState == CallState.CALLING || state.callState == CallState.CONNECTED

        pulse.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            callBtn.setBackgroundResource(R.drawable.bg_float_action_end)
            callBtn.setImageResource(R.drawable.ic_phone_down)
            callLabel.setText(R.string.end)
        } else {
            callBtn.setBackgroundResource(R.drawable.bg_float_action_call)
            callBtn.setImageResource(R.drawable.ic_phone)
            callLabel.setText(R.string.call)
        }
        callBtn.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)

        muteBtn.setImageResource(if (state.muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        muteBtn.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.plano_blue)
        )
        muteBtn.alpha = if (active) 1f else 0.35f
        muteBtn.isEnabled = active

        speakerBtn.setImageResource(
            if (state.speakerOn) R.drawable.ic_speaker else R.drawable.ic_earpiece
        )
        speakerBtn.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.plano_blue)
        )
        speakerBtn.alpha = if (active) 1f else 0.35f
        speakerBtn.isEnabled = active

        micModeLabel.text =
            if (state.usePtt) getString(R.string.push_to_talk) else getString(R.string.open_mic)

        root.findViewById<ImageButton>(R.id.btnFloatProducts).alpha = if (active) 1f else 0.35f
        root.findViewById<ImageButton>(R.id.btnFloatProducts).isEnabled = active
        root.findViewById<ImageButton>(R.id.btnFloatMicMode).alpha = if (active) 1f else 0.35f
        root.findViewById<ImageButton>(R.id.btnFloatMicMode).isEnabled = active

        listOf(
            R.id.btnFloatMute, R.id.btnFloatSpeaker, R.id.btnFloatProducts,
            R.id.btnFloatMicMode, R.id.btnFloatOpen, R.id.btnFloatDismiss
        ).forEach { id ->
            root.findViewById<ImageButton>(id).imageTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.plano_blue)
                )
        }
    }

    private fun removeOverlay() {
        try {
            rootView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        rootView = null
        params = null
        trayExpanded = false
    }

    override fun onDestroy() {
        CallBridge.setStateListener(null)
        removeOverlay()
        foregroundStarted = false
        super.onDestroy()
    }
}
