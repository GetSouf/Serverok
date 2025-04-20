package com.example.serverstreamscreen

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.*
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI

class TouchService : Service() {
    private val TAG = "TouchService"
    private val WS_URI = "ws://62.152.55.56:8081"
    private var webSocketClient: WebSocketClient? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        initWebSocket()
    }

    private fun initWebSocket() {
        webSocketClient = object : WebSocketClient(URI(WS_URI)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "WebSocket подключён для касаний")
            }

            override fun onMessage(message: String?) {
                message ?: return
                try {
                    val touch = JSONObject(message)
                    val x = touch.getDouble("x").toFloat()
                    val y = touch.getDouble("y").toFloat()
                    val action = touch.getInt("action") // 0 - down, 1 - move, 2 - up

                    injectTouch(x, y, action)
                } catch (e: JSONException) {
                    Log.e(TAG, "Ошибка разбора касания: ${e.message}")
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WebSocket закрыт: $reason")
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "Ошибка WebSocket касаний: ${ex?.message}", ex)
            }
        }
        webSocketClient?.connect()
    }

//    fun injectTouch(x: Float, y: Float, action: Int) {
//        val eventTime = SystemClock.uptimeMillis()
//        val downEvent = MotionEvent.obtain(
//            eventTime, eventTime, action, x, y, 0
//        )
//
//        val instrumentation = Instrumentation()
//        instrumentation.sendPointerSync(downEvent)
//    }

    private fun injectTouch(x: Float, y: Float, action: Int) {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        val event = MotionEvent.obtain(
            downTime, eventTime, action, x, y, 0
        )
        event.source = InputDevice.SOURCE_TOUCHSCREEN

        handler.post {
            try {
                injectTouchEvent(event)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка инъекции касания: ${e.message}")
            }
        }
    }

    fun injectTouchEvent(event: MotionEvent) {
        try {
            val inputManager = getSystemService(Context.INPUT_SERVICE)
            val clazz = Class.forName("android.hardware.input.InputManager")
            val injectInputEventMethod = clazz.getMethod(
                "injectInputEvent",
                MotionEvent::class.java,
                Int::class.javaPrimitiveType
            )
            injectInputEventMethod.invoke(inputManager, event, 0)
        } catch (e: Exception) {
            Log.e("TouchService", "Ошибка injectInputEvent: ${e.message}", e)
        }
    }


    override fun onDestroy() {
        webSocketClient?.close()
        super.onDestroy()
    }
}
