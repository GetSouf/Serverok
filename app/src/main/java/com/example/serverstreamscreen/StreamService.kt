package com.example.serverstreamscreen

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException

class StreamService : Service() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var webSocketClient: WebSocketClient? = null
    private val WS_URI = "ws://62.152.55.56:8081"
    private val TAG = "StreamService"
    private var connectionAttempts = 0
    private val maxConnectionAttempts = 3
    private lateinit var imageHandlerThread: HandlerThread
    private lateinit var imageHandler: Handler

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "StreamService создан")

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startForeground(1, createNotification())
        Log.d(TAG, "Foreground notification запущен")

        imageHandlerThread = HandlerThread("ImageReaderThread")
        imageHandlerThread.start()
        imageHandler = Handler(imageHandlerThread.looper)

        initWebSocket()
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "stream_channel")
            .setContentTitle("Streaming screen")
            .setContentText("Sending screen frames...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    private fun initWebSocket() {
        try {
            Log.d(TAG, "Инициализация WebSocket с URI: $WS_URI")
            webSocketClient = object : WebSocketClient(URI(WS_URI)) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    connectionAttempts = 0
                    Log.d(TAG, "WebSocket соединение установлено: ${handshakedata?.httpStatus} ${handshakedata?.httpStatusMessage}")
                }

                override fun onMessage(message: String?) {
                    Log.d(TAG, "Получено сообщение: $message")
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.w(TAG, "WebSocket закрыт: $code, причина: $reason")
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket ошибка: ${ex?.message}", ex)
                }
            }
            webSocketClient?.connect()
            connectionAttempts++
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Неверный URI: $WS_URI", e)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка WebSocket: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.d(TAG, "MediaProjection остановлена")
                stopSelf()
            }
        }, imageHandler)

        imageReader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 4)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "StreamDisplay",
            720, 1280, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, imageHandler
        )

        imageReader?.setOnImageAvailableListener({
            val image = it.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bitmap = imageToBitmap(image)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 10, stream)
            val byteArray = stream.toByteArray()

            if (webSocketClient?.isOpen == true) {
                webSocketClient?.send(byteArray)
            }

            image.close()
            bitmap.recycle()
        }, imageHandler)

        return START_STICKY
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketClient?.close()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        imageHandlerThread.quitSafely()
        Log.d(TAG, "StreamService уничтожен")
    }
}
