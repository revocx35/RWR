package io.github.revocx35.rwr.server

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Streams camera frames to the RWR server over a pinned wss:// connection and receives alerts.
 * Reconnects with exponential backoff. Frames are dropped (not queued) when the network can't keep up, so
 * latency stays low and memory bounded. Callbacks arrive on OkHttp threads.
 */
class StreamClient(private val account: ServerAccount, private val listener: Listener) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onAlert(alert: JSONObject)
        /** Per-frame decisions; the server sends them only while the settings ask for "debug". */
        fun onDebug(msg: JSONObject) = Unit
        /** The server refused this device (revoked/unpaired): retrying won't help. */
        fun onRejected(reason: String)
    }

    private val client = PinnedTls.client(account.pin, readTimeoutS = 0).newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ws: WebSocket? = null
    @Volatile var isConnected = false
        private set
    @Volatile private var settings = JSONObject()
    @Volatile private var stopped = true
    private var attempt = 0

    fun start(settings: JSONObject) {
        this.settings = settings
        if (!stopped) return
        stopped = false
        attempt = 0
        connect()
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        ws?.close(1000, "stopped")
        ws = null
        isConnected = false
    }

    fun updateSettings(settings: JSONObject) {
        this.settings = settings
        if (isConnected) ws?.send(JSONObject().put("type", "settings").put("settings", settings).toString())
    }

    /** Returns false if the frame was dropped (not connected, or the send queue is backed up). */
    fun sendFrame(seq: Int, tsMs: Long, rotationQuarters: Int, jpeg: ByteArray): Boolean {
        val socket = ws ?: return false
        if (!isConnected || socket.queueSize() > MAX_QUEUE_BYTES) return false
        val buf = ByteBuffer.allocate(HEADER_BYTES + jpeg.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(1).put((rotationQuarters and 3).toByte()).put(FLAG_MIRROR).put(0)
        buf.putInt(seq).putLong(tsMs).put(jpeg)
        return socket.send(buf.array().toByteString())
    }

    private fun connect() {
        if (stopped) return
        val url = account.baseUrl.replaceFirst("https://", "wss://") + "/api/v1/stream"
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${account.token}").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "hello").put("settings", settings).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "ready" -> {
                        isConnected = true
                        attempt = 0
                        listener.onConnected()
                    }
                    "alert" -> listener.onAlert(msg)
                    "debug" -> listener.onDebug(msg)
                    "stats" -> Log.d(TAG, "server stats: $text")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                closed(webSocket, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // A refused handshake (bad/revoked token) arrives here as HTTP 403.
                closed(webSocket, if (response?.code == 403) CODE_REVOKED else 0, t.message ?: t.javaClass.simpleName)
            }
        })
    }

    private fun closed(socket: WebSocket, code: Int, reason: String) {
        if (socket !== ws) return
        isConnected = false
        ws = null
        if (stopped) return
        if (code == CODE_REVOKED) {
            stopped = true
            listener.onRejected("the server no longer accepts this phone (removed or unpaired)")
            return
        }
        listener.onDisconnected(reason)
        val delay = minOf(MAX_BACKOFF_MS, 2000L shl minOf(attempt, 5))
        attempt++
        Log.i(TAG, "stream closed ($code $reason), retrying in ${delay / 1000}s")
        main.postDelayed(::connect, delay)
    }

    companion object {
        private const val TAG = "StreamClient"
        private const val HEADER_BYTES = 16
        private const val FLAG_MIRROR: Byte = 1
        private const val MAX_QUEUE_BYTES = 512 * 1024L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val CODE_REVOKED = 4403
    }
}
