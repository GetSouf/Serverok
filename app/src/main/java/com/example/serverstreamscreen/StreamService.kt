package com.example.serverstreamscreen

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat

class StreamService : Service() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("StreamService", "StreamService создан")

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        startForeground(1, createNotification())
        Log.d("StreamService", "Foreground notification запущен")
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "stream_channel")
            .setContentTitle("Streaming screen")
            .setContentText("Sending screen frames...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("StreamService", "onStartCommand получен")

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY

        Log.d("StreamService", "Получены resultCode и data. Настраиваем mediaProjection")

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        // Регистрация коллбэка
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.d("StreamService", "MediaProjection остановлена")
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        imageReader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "StreamDisplay",
            720, 1280, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        Log.d("StreamService", "VirtualDisplay инициализирован")

        imageReader?.setOnImageAvailableListener({
            val image = it.acquireLatestImage() ?: return@setOnImageAvailableListener
            Log.d("StreamService", "🎥 Кадр получен: ${image.width}x${image.height}")
            image.close()
        }, Handler(Looper.getMainLooper()))

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d("StreamService", "StreamService уничтожается")

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        super.onDestroy()
    }
}
