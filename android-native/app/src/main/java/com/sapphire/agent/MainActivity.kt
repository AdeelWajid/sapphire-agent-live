package com.sapphire.agent

import android.view.animation.AnimationUtils
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sapphire.agent.audio.AudioEngine
import com.sapphire.agent.audio.CallAudioRouter
import com.sapphire.agent.audio.RingtonePlayer
import com.sapphire.agent.databinding.ActivityMainBinding
import com.sapphire.agent.floatbubble.CallBridge
import com.sapphire.agent.floatbubble.FloatUiState
import com.sapphire.agent.floatbubble.FloatingCallService
import com.sapphire.agent.floatbubble.OverlayPermissionHelper
import com.sapphire.agent.live.CallState
import com.sapphire.agent.live.FarewellDetector
import com.sapphire.agent.live.LiveSession
import com.sapphire.agent.live.LiveSessionListener
import com.sapphire.agent.products.PickerProduct
import com.sapphire.agent.products.ProductCatalogApi
import com.sapphire.agent.products.ProductPickerDialog
import java.util.Calendar
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), LiveSessionListener, CallBridge.Host {

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private var session: LiveSession? = null
    private var audioEngine: AudioEngine? = null
    private var ringtone: RingtonePlayer? = null
    private lateinit var audioRouter: CallAudioRouter
    private var productDialog: ProductPickerDialog? = null
    private var connectRunnable: Runnable? = null
    private var voiceSessionStarted = false

    private var callState: CallState = CallState.IDLE
    private var usePushToTalk = true
    private var micMuted = false
    private var callStartedAt = 0L
    private var agentSpeaking = false
    private var farewellPending = false
    private var farewellTriggered = false
    private var awaitingEndConfirm = false
    private var toolPending = false
    private var recentModelSpeech = ""
    private var recentUserSpeech = ""
    private var keypadDigits = StringBuilder()

    private var speechHoldFrames = 0
    private var openMicSilenceMs = 0.0
    private var appInForeground = true

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional for ongoing-call notification */ }

    private val timerTick = object : Runnable {
        override fun run() {
            if (callState == CallState.CONNECTED && callStartedAt > 0L) {
                val elapsed = (SystemClock.elapsedRealtime() - callStartedAt) / 1000L
                binding.timerText.text = "%d:%02d".format(elapsed / 60, elapsed % 60)
                if (!micMuted && !usePushToTalk) {
                    binding.statusText.text = binding.timerText.text
                }
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val farewellFallback = Runnable {
        if (farewellPending) endCall()
    }

    private val speakingIdle = Runnable {
        agentSpeaking = false
        if (callState == CallState.CONNECTED && !micMuted && usePushToTalk) {
            binding.statusText.setText(R.string.status_hold)
        }
        if (farewellPending) {
            // Agent finished the farewell — short grace so the last audio isn't cut.
            mainHandler.postDelayed({
                if (farewellPending) endCall()
            }, 500L)
        }
    }

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCall()
        else {
            Toast.makeText(this, R.string.mic_permission_required, Toast.LENGTH_LONG).show()
            if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.RECORD_AUDIO
                )
            ) {
                Toast.makeText(this, R.string.mic_permission_settings, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ringtone = RingtonePlayer(this)
        audioRouter = CallAudioRouter(this) { loudspeaker, proximityNear ->
            mainHandler.post {
                updateSpeakerUi(loudspeaker, proximityNear)
                publishFloatState()
            }
        }
        CallBridge.register(this)

        setupKeypad()
        setupHomeScreen()
        renderIdleUi()
        maybeAskNotificationPermission()
        OverlayPermissionHelper.requestIfNeeded(this) {
            // Prepare service only — bubble stays hidden while the app is open.
            FloatingCallService.ensure(this)
            FloatingCallService.hideBubble(this)
        }

        binding.callButton.setOnClickListener {
            if (callState == CallState.IDLE || callState == CallState.ERROR) {
                ensurePermissionsThenCall()
            } else {
                endCall()
            }
        }

        binding.btnMute.setOnClickListener { toggleMute() }
        binding.btnSpeaker.setOnClickListener { toggleSpeaker() }
        binding.btnKeypad.setOnClickListener { showKeypad(true) }
        binding.keypadHide.setOnClickListener { showKeypad(false) }
        binding.btnMicMode.setOnClickListener { toggleMicMode() }
        binding.btnContacts.setOnClickListener { openProductPickerFromApi() }

        binding.btnPtt.setOnTouchListener { _, event ->
            if (!usePushToTalk || callState != CallState.CONNECTED || micMuted) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Update UI first; flush agent audio asynchronously.
                    setControlActive(binding.btnPtt, true)
                    binding.statusText.setText(R.string.status_listening)
                    audioEngine?.stopPlayback()
                    session?.signalActivityStart()
                    audioEngine?.setSending(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    setControlActive(binding.btnPtt, false)
                    audioEngine?.setSending(false)
                    mainHandler.postDelayed({ session?.signalActivityEnd() }, 120L)
                    binding.statusText.setText(R.string.status_hold)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupHomeScreen() {
        val home = binding.homeScreen
        bindRetailerHome()
        startHomeAnimations()

        val startCall: (View) -> Unit = {
            if (callState == CallState.IDLE || callState == CallState.ERROR) {
                val anim = AnimationUtils.loadAnimation(this, R.anim.btn_ripple_out)
                home.btnStartConversation.startAnimation(anim)
                ensurePermissionsThenCall()
            }
        }

        home.btnStartConversation.setOnClickListener(startCall)
        home.cardPlaceOrder.setOnClickListener(startCall)
        home.cardReportIssue.setOnClickListener(startCall)
        home.cardCatalogue.setOnClickListener {
            if (callState == CallState.CONNECTED) {
                openProductPickerFromApi()
            } else {
                startCall(it)
            }
        }
        home.btnLogout.setOnClickListener { confirmLogout() }
    }

    private fun bindRetailerHome() {
        val home = binding.homeScreen
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when {
            hour < 12 -> getString(R.string.greeting_morning)
            hour < 17 -> getString(R.string.greeting_afternoon)
            else -> getString(R.string.greeting_evening)
        }
        home.tvGreeting.text =
            getString(R.string.greeting_format, part, getString(R.string.guest_name))
        home.tvAgentIntroduction.text = getString(R.string.agent_introduction)
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.logout_confirm_title)
            .setMessage(R.string.logout_confirm_message)
            .setPositiveButton(R.string.logout) { _, _ ->
                endCall()
                finishAffinity()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startHomeAnimations() {
        val home = binding.homeScreen
        val outerAnim = AnimationUtils.loadAnimation(this, R.anim.pulse_ring)
        val innerAnim = AnimationUtils.loadAnimation(this, R.anim.pulse_ring).apply {
            startOffset = 600L
        }
        home.vOuterRing.startAnimation(outerAnim)
        home.vInnerRing.startAnimation(innerAnim)
    }

    private fun showHomeScreen(show: Boolean) {
        binding.homeScreen.root.visibility = if (show) View.VISIBLE else View.GONE
        binding.callScreen.visibility = if (show) View.GONE else View.VISIBLE
        if (show) startHomeAnimations()
    }

    private fun brandBlue(): Int = ContextCompat.getColor(this, R.color.plano_blue)
    private fun brandWhite(): Int = ContextCompat.getColor(this, R.color.plano_white)

    private fun setupKeypad() {
        val keys = listOf(
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "*", "0", "#"
        )
        val size = (72 * resources.displayMetrics.density).toInt()
        val margin = (10 * resources.displayMetrics.density).toInt()
        keys.forEach { key ->
            val tv = TextView(this).apply {
                text = key
                gravity = Gravity.CENTER
                setTextColor(brandBlue())
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_control)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(margin, margin, margin, margin)
                }
                setOnClickListener {
                    if (keypadDigits.length < 24) {
                        keypadDigits.append(key)
                        binding.keypadDisplay.text = keypadDigits.toString()
                    }
                }
            }
            binding.keypadGrid.addView(tv)
        }
    }

    private fun showKeypad(show: Boolean) {
        binding.keypadOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            keypadDigits.clear()
            binding.keypadDisplay.text = ""
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionsThenCall() {
        when {
            hasMicPermission() -> startCall()
            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.RECORD_AUDIO
            ) -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.mic_permission_title)
                    .setMessage(R.string.mic_permission_rationale)
                    .setPositiveButton(R.string.allow) { _, _ ->
                        requestMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    .setNegativeButton(R.string.not_now, null)
                    .show()
            }
            else -> requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startCall() {
        if (callState == CallState.CALLING || callState == CallState.CONNECTED) return
        if (!hasMicPermission()) {
            ensurePermissionsThenCall()
            return
        }

        farewellPending = false
        farewellTriggered = false
        awaitingEndConfirm = false
        toolPending = false
        recentModelSpeech = ""
        recentUserSpeech = ""
        agentSpeaking = false
        micMuted = false
        speechHoldFrames = 0
        openMicSilenceMs = 0.0
        voiceSessionStarted = false
        mainHandler.removeCallbacks(farewellFallback)
        connectRunnable?.let { mainHandler.removeCallbacks(it) }
        connectRunnable = null

        callState = CallState.CALLING
        renderCallingUi()
        publishFloatState()
        OverlayPermissionHelper.requestIfNeeded(this)

        val engine = AudioEngine(
            onPcmChunk = { base64 ->
                if (!micMuted && !toolPending) session?.sendAudioBase64(base64)
            },
            onSpeechLevel = { rms, frameMs -> onSpeechLevel(rms, frameMs) }
        )
        audioEngine = engine

        val live = LiveSession(
            listener = this,
            languageMode = "urdu",
        )
        session = live

        // Loud ring on speaker first; only then enter voice-call audio mode + connect.
        volumeControlStream = AudioManager.STREAM_MUSIC
        ringtone?.play(2200L) {
            mainHandler.post { beginVoiceSession(live) }
        }
        // Safety: still connect if ring callback is missed.
        connectRunnable = Runnable { beginVoiceSession(live) }
        mainHandler.postDelayed(connectRunnable!!, 2500L)
    }

    private fun beginVoiceSession(live: LiveSession) {
        if (session !== live || callState != CallState.CALLING || voiceSessionStarted) return
        voiceSessionStarted = true
        connectRunnable?.let { mainHandler.removeCallbacks(it) }
        connectRunnable = null
        ringtone?.stop()
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        // Default loudspeaker; proximity switches to earpiece when near the ear.
        audioRouter.startCallAudio(defaultLoudspeaker = true)
        // Start FGS now (still foreground) so minimize later won't crash.
        startCallForegroundService()
        live.connect()
    }

    private fun onSpeechLevel(rms: Double, frameMs: Double) {
        if (usePushToTalk || micMuted || callState != CallState.CONNECTED) return
        if (rms >= 0.01) {
            speechHoldFrames += 1
            openMicSilenceMs = 0.0
            if (speechHoldFrames >= 2) session?.signalActivityStart()
        } else {
            speechHoldFrames = 0
            openMicSilenceMs += frameMs
            if (openMicSilenceMs >= 1000.0) {
                session?.signalActivityEnd()
                openMicSilenceMs = 0.0
            }
        }
    }

    private fun endCall() {
        farewellPending = false
        farewellTriggered = false
        recentModelSpeech = ""
        recentUserSpeech = ""
        mainHandler.removeCallbacks(farewellFallback)
        mainHandler.removeCallbacks(timerTick)
        mainHandler.removeCallbacks(speakingIdle)
        connectRunnable?.let { mainHandler.removeCallbacks(it) }
        connectRunnable = null
        voiceSessionStarted = false
        ringtone?.stop()
        showKeypad(false)
        dismissProductPicker()
        val live = session
        session = null
        live?.disconnect()
        audioEngine?.release()
        audioEngine = null
        callState = CallState.IDLE
        callStartedAt = 0L
        agentSpeaking = false
        micMuted = false
        speechHoldFrames = 0
        openMicSilenceMs = 0.0
        audioRouter.stopCallAudio()
        volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        // Keep floating bubble alive when idle — only drop mic FGS.
        FloatingCallService.enterIdle(this)
        publishFloatState()
        renderIdleUi()
    }

    private fun isCallActive(): Boolean =
        callState == CallState.CALLING || callState == CallState.CONNECTED

    private fun publishFloatState() {
        CallBridge.publish(
            FloatUiState(
                callState = callState,
                muted = micMuted,
                speakerOn = audioRouter.userWantsLoudspeaker,
                usePtt = usePushToTalk
            )
        )
    }

    private fun showFloatingBubble() {
        FloatingCallService.showBubble(this)
    }

    private fun hideFloatingBubble() {
        FloatingCallService.hideBubble(this)
    }

    private fun startCallForegroundService() {
        if (!isCallActive()) return
        val status = if (callState == CallState.CALLING) {
            getString(R.string.calling_status_short)
        } else {
            getString(R.string.call_ongoing_status)
        }
        FloatingCallService.enterInCall(this, status)
        publishFloatState()
    }

    private fun stopCallForegroundService() {
        // Kept for compatibility — idle mode replaces full stop so bubble remains.
        try {
            FloatingCallService.enterIdle(this)
        } catch (_: Exception) {
        }
    }

    // --- CallBridge.Host (floating controls) ---

    override fun floatStartCall() {
        mainHandler.post {
            if (!isCallActive()) ensurePermissionsThenCall()
        }
    }

    override fun floatEndCall() {
        mainHandler.post { endCall() }
    }

    override fun floatToggleMute() {
        mainHandler.post { toggleMute() }
    }

    override fun floatToggleSpeaker() {
        mainHandler.post { toggleSpeaker() }
    }

    override fun floatOpenProducts() {
        mainHandler.post { openProductPickerFromApi() }
    }

    override fun floatOpenApp() {
        mainHandler.post {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
        }
    }

    override fun floatToggleMicMode() {
        mainHandler.post { toggleMicMode() }
    }

    override fun currentFloatState(): FloatUiState =
        FloatUiState(
            callState = callState,
            muted = micMuted,
            speakerOn = if (::audioRouter.isInitialized) audioRouter.userWantsLoudspeaker else true,
            usePtt = usePushToTalk
        )

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        appInForeground = true
        CallBridge.register(this)
        // Always hide floating UI while the full app is visible.
        FloatingCallService.hideBubble(this)
        publishFloatState()
    }

    override fun onStart() {
        super.onStart()
        FloatingCallService.hideBubble(this)
    }

    override fun onStop() {
        super.onStop()
        appInForeground = false
        if (!isChangingConfigurations) {
            FloatingCallService.ensure(this)
            FloatingCallService.showBubble(this)
            publishFloatState()
        }
    }

    private fun toggleMute() {
        if (callState != CallState.CONNECTED) return
        micMuted = !micMuted
        setControlActive(binding.btnMute, micMuted)
        binding.btnMute.setImageResource(if (micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        if (micMuted) {
            audioEngine?.setSending(false)
            session?.signalActivityEnd()
            binding.statusText.setText(R.string.status_muted)
        } else {
            applyMicMode()
        }
        publishFloatState()
    }

    private fun toggleSpeaker() {
        if (callState != CallState.CALLING && callState != CallState.CONNECTED) return
        audioRouter.toggleUserLoudspeaker()
        publishFloatState()
    }

    private fun updateSpeakerUi(loudspeaker: Boolean, proximityNear: Boolean) {
        // Button reflects user preference; icon reflects effective route.
        setControlActive(binding.btnSpeaker, audioRouter.userWantsLoudspeaker)
        binding.btnSpeaker.setImageResource(
            if (loudspeaker) R.drawable.ic_speaker else R.drawable.ic_earpiece
        )
        binding.btnSpeaker.contentDescription = when {
            proximityNear -> getString(R.string.audio_earpiece_proximity)
            loudspeaker -> getString(R.string.audio)
            else -> getString(R.string.audio_earpiece)
        }
    }

    private fun toggleMicMode() {
        usePushToTalk = !usePushToTalk
        updateMicModeControl()
        publishFloatState()
        if (callState == CallState.CONNECTED) {
            session?.signalActivityEnd()
            speechHoldFrames = 0
            openMicSilenceMs = 0.0
            applyMicMode()
        }
    }

    private fun updateMicModeControl() {
        setControlActive(binding.btnMicMode, usePushToTalk)
        binding.labelMicMode.text =
            if (usePushToTalk) getString(R.string.push_to_talk) else getString(R.string.open_mic)
        binding.btnPtt.alpha = if (usePushToTalk) 1f else 0.35f
        binding.btnPtt.isEnabled = usePushToTalk
    }

    private fun applyMicMode() {
        val engine = audioEngine ?: return
        if (micMuted) return
        if (usePushToTalk) {
            engine.setSending(false)
            binding.statusText.setText(R.string.status_hold)
        } else {
            engine.setSending(true)
            if (callStartedAt > 0L) {
                val elapsed = (SystemClock.elapsedRealtime() - callStartedAt) / 1000L
                binding.statusText.text = "%d:%02d".format(elapsed / 60, elapsed % 60)
            }
        }
    }

    private fun setControlActive(button: View, active: Boolean) {
        button.setBackgroundResource(
            if (active) R.drawable.bg_control_active else R.drawable.bg_control
        )
        if (button is android.widget.ImageButton) {
            button.imageTintList = android.content.res.ColorStateList.valueOf(
                if (active) brandWhite() else brandBlue()
            )
        }
    }

    private fun renderIdleUi() {
        showHomeScreen(true)
        binding.statusText.setText(R.string.status_ready)
        binding.timerText.visibility = View.GONE
        binding.controlsPanel.visibility = View.INVISIBLE
        binding.callButton.setBackgroundResource(R.drawable.bg_call_button)
        binding.callButton.setImageResource(R.drawable.ic_phone)
        binding.callButton.imageTintList =
            android.content.res.ColorStateList.valueOf(brandWhite())
        binding.callButtonLabel.setText(R.string.call)
        resetControlVisuals()
        updateMicModeControl()
    }

    private fun renderCallingUi() {
        showHomeScreen(false)
        binding.statusText.setText(R.string.status_calling)
        binding.timerText.visibility = View.GONE
        binding.controlsPanel.visibility = View.INVISIBLE
        binding.callButton.setBackgroundResource(R.drawable.bg_end_button)
        binding.callButton.setImageResource(R.drawable.ic_phone_down)
        binding.callButton.imageTintList =
            android.content.res.ColorStateList.valueOf(brandWhite())
        binding.callButtonLabel.setText(R.string.end)
    }

    private fun renderConnectedUi() {
        showHomeScreen(false)
        binding.controlsPanel.visibility = View.VISIBLE
        binding.timerText.visibility = View.GONE
        binding.callButton.setBackgroundResource(R.drawable.bg_end_button)
        binding.callButton.setImageResource(R.drawable.ic_phone_down)
        binding.callButton.imageTintList =
            android.content.res.ColorStateList.valueOf(brandWhite())
        binding.callButtonLabel.setText(R.string.end)
        updateSpeakerUi(audioRouter.isLoudspeakerActive, audioRouter.proximityNear)
        setControlActive(binding.btnMute, micMuted)
        updateMicModeControl()
    }

    private fun resetControlVisuals() {
        listOf(binding.btnMute, binding.btnKeypad, binding.btnSpeaker, binding.btnMicMode, binding.btnPtt)
            .forEach {
                setControlActive(it, false)
                it.imageTintList = android.content.res.ColorStateList.valueOf(brandBlue())
            }
        binding.btnMute.setImageResource(R.drawable.ic_mic)
        binding.btnSpeaker.setImageResource(R.drawable.ic_speaker)
    }

    override fun onState(state: CallState, message: String?) {
        when (state) {
            CallState.CALLING -> {
                callState = CallState.CALLING
                renderCallingUi()
                publishFloatState()
            }
            CallState.CONNECTED -> {
                callState = CallState.CONNECTED
                ringtone?.stop()
                callStartedAt = SystemClock.elapsedRealtime()
                renderConnectedUi()
                startCallForegroundService()
                publishFloatState()
                mainHandler.removeCallbacks(timerTick)
                mainHandler.post(timerTick)
                try {
                    audioEngine?.startCapture(sendImmediately = !usePushToTalk && !micMuted)
                    applyMicMode()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "Mic error", Toast.LENGTH_LONG).show()
                    endCall()
                }
            }
            CallState.IDLE -> {
                if (session != null || callState == CallState.CONNECTED || callState == CallState.CALLING) {
                    endCall()
                }
            }
            CallState.ERROR -> {
                Toast.makeText(this, message ?: getString(R.string.status_error), Toast.LENGTH_LONG).show()
                endCall()
            }
        }
    }

    override fun onAudio(base64Pcm24k: String) {
        // Enqueue on WS thread — never block the UI with AudioTrack.write.
        audioEngine?.playPcmBase64(base64Pcm24k)
        agentSpeaking = true
        mainHandler.post {
            mainHandler.removeCallbacks(speakingIdle)
            mainHandler.postDelayed(speakingIdle, 600L)
            if (farewellPending) {
                // Keep extending hangup until speech actually stops.
                mainHandler.removeCallbacks(farewellFallback)
                mainHandler.postDelayed(farewellFallback, 12_000L)
            }
        }
    }

    override fun onInterrupted() {
        audioEngine?.stopPlayback()
        agentSpeaking = false
        if (farewellPending) {
            mainHandler.postDelayed({
                if (farewellPending) endCall()
            }, 500L)
        }
    }

    override fun onFarewellHangup(text: String) {
        scheduleFarewellHangup(text)
    }

    override fun onEndCall(reason: String) {
        scheduleFarewellHangup("end_call:$reason")
    }

    override fun onToolPending(pending: Boolean) {
        toolPending = pending
        if (pending) {
            audioEngine?.setSending(false)
            session?.signalActivityEnd()
        }
    }

    override fun onUserText(text: String) {
        recentUserSpeech = "$recentUserSpeech $text".replace(Regex("""\s+"""), " ").trim().takeLast(240)
        if (awaitingEndConfirm && FarewellDetector.isAffirmative(text)) {
            scheduleFarewellHangup("user_confirmed_end")
            return
        }
        if (FarewellDetector.isFarewell(text) || FarewellDetector.isFarewell(recentUserSpeech)) {
            scheduleFarewellHangup(text)
        }
    }

    override fun onModelText(text: String) {
        recentModelSpeech = "$recentModelSpeech $text".replace(Regex("""\s+"""), " ").trim().takeLast(240)
        if (FarewellDetector.isEndCallOffer(text) || FarewellDetector.isEndCallOffer(recentModelSpeech)) {
            awaitingEndConfirm = true
        }
    }

    private fun scheduleFarewellHangup(source: String) {
        if (farewellTriggered) return
        if (callState != CallState.CONNECTED && callState != CallState.CALLING) return
        farewellTriggered = true
        farewellPending = true
        awaitingEndConfirm = false
        android.util.Log.i("PlanoAgent", "Hang up after agent finishes: $source")
        mainHandler.removeCallbacks(farewellFallback)
        // Absolute safety net if speaking detection never settles.
        mainHandler.postDelayed(farewellFallback, 12_000L)
        if (!agentSpeaking) {
            // Wait briefly for farewell audio, then end.
            mainHandler.postDelayed({
                if (farewellPending && !agentSpeaking) endCall()
            }, 1_800L)
        }
    }

    override fun onShowProducts(products: List<PickerProduct>) {
        if (products.isEmpty()) {
            openProductPickerFromApi()
            return
        }
        showProductPicker(products)
    }

    private fun openProductPickerFromApi() {
        if (callState != CallState.CONNECTED) {
            Toast.makeText(this, R.string.call_first_for_products, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.product_loading, Toast.LENGTH_SHORT).show()
        thread(name = "products-fetch", isDaemon = true) {
            try {
                val products = ProductCatalogApi.fetchProducts()
                mainHandler.post {
                    if (callState == CallState.CONNECTED) showProductPicker(products)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(
                        this,
                        e.message ?: getString(R.string.product_empty),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showProductPicker(products: List<PickerProduct>) {
        if (isFinishing) return
        dismissProductPicker()
        productDialog = ProductPickerDialog(this, products) { selected ->
            val message = ProductCatalogApi.buildSelectionMessage(selected)
            session?.sendUserText(message)
        }.also { it.show() }
    }

    private fun dismissProductPicker() {
        try {
            productDialog?.dismiss()
        } catch (_: Exception) {
        }
        productDialog = null
    }

    override fun onDestroy() {
        CallBridge.unregister(this)
        endCall()
        if (::audioRouter.isInitialized) audioRouter.release()
        super.onDestroy()
    }
}
