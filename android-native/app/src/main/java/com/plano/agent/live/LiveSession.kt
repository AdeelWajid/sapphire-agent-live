package com.plano.agent.live

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.plano.agent.BuildConfig
import com.plano.agent.products.PickerProduct
import com.plano.agent.products.ProductCatalogApi
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class CallState {
    IDLE,
    CALLING,
    CONNECTED,
    ERROR
}

interface LiveSessionListener {
    fun onState(state: CallState, message: String? = null)
    fun onAudio(base64Pcm24k: String)
    fun onInterrupted()
    fun onFarewellHangup(text: String)
    fun onEndCall(reason: String)
    fun onToolPending(pending: Boolean)
    fun onUserText(text: String)
    fun onModelText(text: String)
    fun onShowProducts(products: List<PickerProduct>)
}

/**
 * Native WebSocket client for Sapphire Agent `/api/live`.
 */
class LiveSession(
    private val listener: LiveSessionListener,
    private val voice: String = "Charon",
    private val languageMode: String = "urdu",
    private val extraRules: String = "",
) {
    companion object {
        private const val TAG = "LiveSession"
    }

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    private val open = AtomicBoolean(false)
    private val activityOpen = AtomicBoolean(false)
    private val toolPending = AtomicBoolean(false)
    private var configSent = false

    fun connect() {
        disconnect()
        configSent = false
        toolPending.set(false)
        main.post { listener.onState(CallState.CALLING) }

        val url = "${BuildConfig.LIVE_WS_URL}?voice=${java.net.URLEncoder.encode(voice, "UTF-8")}"
        Log.i(TAG, "Connecting $url")
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open.set(true)
                sendSessionConfig(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open.set(false)
                activityOpen.set(false)
                toolPending.set(false)
                main.post { listener.onState(CallState.IDLE) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                open.set(false)
                activityOpen.set(false)
                toolPending.set(false)
                main.post {
                    listener.onState(CallState.ERROR, t.message ?: "Connection failed")
                }
            }
        })
    }

    private fun sendSessionConfig(webSocket: WebSocket) {
        if (configSent) return
        configSent = true
        val json = JSONObject()
            .put("type", "session_config")
            .put("voice", voice)
            .put("extraRules", extraRules)
            .put("languageMode", languageMode)
            .put("source", "Mobile Agent")
        webSocket.send(json.toString())
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        try {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "status" -> {
                    when (msg.optString("status")) {
                        "awaiting_config" -> {
                            configSent = false
                            sendSessionConfig(webSocket)
                        }
                        "established" -> {
                            main.post { listener.onState(CallState.CONNECTED) }
                        }
                        "closed" -> {
                            main.post { listener.onState(CallState.IDLE) }
                        }
                    }
                }
                "audio" -> {
                    val data = msg.optString("data")
                    if (data.isNotEmpty()) {
                        listener.onAudio(data)
                    }
                }
                "interrupted" -> {
                    listener.onInterrupted()
                }
                "tool_pending" -> {
                    val pending = msg.optBoolean("pending", false)
                    toolPending.set(pending)
                    main.post { listener.onToolPending(pending) }
                }
                "end_call" -> {
                    val reason = msg.optString("reason", "customer_confirmed")
                    main.post { listener.onEndCall(reason) }
                }
                "farewell_hangup" -> {
                    val farewell = msg.optString("text", "bye")
                    main.post { listener.onFarewellHangup(farewell) }
                }
                "user-text" -> {
                    val t = msg.optString("text")
                    if (t.isNotEmpty()) main.post { listener.onUserText(t) }
                }
                "model-text" -> {
                    val t = msg.optString("text")
                    if (t.isNotEmpty()) main.post { listener.onModelText(t) }
                }
                "show_products" -> {
                    val products = ProductCatalogApi.parseProducts(msg.optJSONArray("products"))
                    main.post { listener.onShowProducts(products) }
                }
                "error" -> {
                    val m = msg.optString("message", "Unknown error")
                    main.post { listener.onState(CallState.ERROR, m) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bad message: $text", e)
        }
    }

    fun sendAudioBase64(base64: String) {
        if (toolPending.get()) return
        val ws = socket ?: return
        if (!open.get()) return
        val json = JSONObject().put("audio", base64)
        ws.send(json.toString())
    }

    fun signalActivityStart() {
        if (toolPending.get()) return
        if (!activityOpen.compareAndSet(false, true)) return
        sendJson(JSONObject().put("type", "activity_start"))
    }

    fun signalActivityEnd() {
        if (!activityOpen.compareAndSet(true, false)) return
        if (toolPending.get()) return
        sendJson(JSONObject().put("type", "activity_end"))
    }

    fun sendUserText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        sendJson(
            JSONObject()
                .put("type", "user_text")
                .put("text", trimmed)
        )
    }

    private fun sendJson(obj: JSONObject) {
        val ws = socket ?: return
        if (!open.get()) return
        ws.send(obj.toString())
    }

    fun disconnect() {
        activityOpen.set(false)
        open.set(false)
        toolPending.set(false)
        try {
            socket?.close(1000, "client hangup")
        } catch (_: Exception) {
        }
        socket = null
        configSent = false
    }
}
