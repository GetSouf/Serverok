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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException

class StreamService : Service() {
    companion object {
        private const val TAG = "StreamService"
        private const val CHANNEL_ID = "stream_channel"
        private const val NOTIF_ID = 1
        private const val WS_URI = "ws://62.152.55.56:8081"
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var encoder: MediaCodec? = null
    private var codecSurface: Surface? = null
    private var drainThread: Thread? = null
    @Volatile private var isEncoding = false

    // Для логирования FPS
    private var frameCount = 0
    private var lastTimestamp = System.currentTimeMillis()

    private var webSocketClient: WebSocketClient? = null
    private var connectionAttempts = 0
    private val maxConnectionAttempts = 3

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification())

        handlerThread = HandlerThread("StreamHandlerThread").apply { start() }
        handler = Handler(handlerThread.looper)

        initWebSocket()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Screen Stream"
            val desc = "Channel for screen streaming service"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = desc
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, StreamService::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Streaming")
            .setContentText("Streaming screen via WebSocket...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pending)
            .build()
    }

    private fun initWebSocket() {
        try {
            webSocketClient = object : WebSocketClient(URI(WS_URI)) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    connectionAttempts = 0
                    Log.d(TAG, "WebSocket opened: ${handshakedata?.httpStatus}")
                }

                override fun onMessage(message: String?) {
                    Log.d(TAG, "Получено сообщение: $message")
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.w(TAG, "WebSocket closed: $code / $reason")
                    if (connectionAttempts < maxConnectionAttempts) {
                        connectionAttempts++
                        connectWebSocketDelayed()
                    }
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket error", ex)
                }
            }
            webSocketClient!!.connect()
            connectionAttempts++
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Invalid WebSocket URI", e)
        }
    }

    private fun connectWebSocketDelayed() {
        handler.postDelayed({ webSocketClient?.connect() }, 2000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data")
            ?: return START_NOT_STICKY

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stopSelf()
            }
        }, handler)

        setupEncoderAndStream()
        return START_STICKY
    }

    private fun setupEncoderAndStream() {
        // Configure video encoder
        val width = 720
        val height = 1280
        val dpi = resources.displayMetrics.densityDpi
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000) // Increased bitrate
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
            // For Android 23+ you can set priority
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 1) // real-time priority
                setInteger(MediaFormat.KEY_OPERATING_RATE, 60) // target operating rate
            }
        }
        encoder = MediaCodec.createEncoderByType("video/avc").apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codecSurface = createInputSurface()
            start()
        }

        // Create VirtualDisplay targeting encoder surface
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "StreamDisplay", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            codecSurface, null, handler
        )

        // Start draining encoder output
        isEncoding = true
        drainThread = Thread { drainEncoder() }.apply { start() }
    }

    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isEncoding) {
            val idx = encoder?.dequeueOutputBuffer(bufferInfo, 10_000L) ?: break
            when {
                idx >= 0 -> {
                    val outBuffer = encoder!!.getOutputBuffer(idx)
                    outBuffer?.let {
                        val data = ByteArray(bufferInfo.size)
                        it.get(data)
                        if (webSocketClient?.isOpen == true) {
                            webSocketClient!!.send(data)
                        }

                        // Логирование FPS
                        frameCount++
                        val now = System.currentTimeMillis()
                        if (now - lastTimestamp >= 1000) {
                            Log.d(TAG, "FPS: $frameCount")
                            frameCount = 0
                            lastTimestamp = now
                        }

                    }
                    encoder!!.releaseOutputBuffer(idx, false)
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // New format; handle if needed
                }
                else -> { /* ignore */ }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop encoding loop
        isEncoding = false
        drainThread?.join(100)

        webSocketClient?.close()
        virtualDisplay?.release()
        encoder?.stop()
        encoder?.release()
        mediaProjection?.stop()
        handlerThread.quitSafely()
        Log.d(TAG, "Service destroyed")
    }
}

