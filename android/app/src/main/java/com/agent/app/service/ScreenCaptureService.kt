package com.agent.app.service

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.agent.app.AgentApp
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 截图服务 — Agent 的"眼睛"
 * 用 MediaProjection API 截取屏幕
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var captureHandler: Handler? = null
    private var lastScreenshot: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        val thread = HandlerThread("ScreenCapture")
        thread.start()
        captureHandler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.getParcelableExtra<Intent>("result_data") ?: return START_NOT_STICKY
        val code = intent.getIntExtra("result_code", -1)

        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(code, data)

        setupImageReader()
        return START_STICKY
    }

    private fun setupImageReader() {
        val display = resources.displayMetrics
        val width = display.widthPixels
        val height = display.heightPixels
        val density = display.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            lastScreenshot = imageToBitmap(image)
            image.close()
        }, captureHandler)
    }

    /**
     * 获取当前截图（Base64 JPEG 压缩）
     */
    fun captureScreen(): String {
        val bitmap = lastScreenshot ?: return ""
        // 缩放至 540px 宽
        val scale = 540f / bitmap.width
        val scaled = Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(), true)

        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        return bitmap
    }

    private fun buildNotification() = NotificationCompat.Builder(this, AgentApp.CHANNEL_ID)
        .setContentTitle("手机助手运行中")
        .setContentText("可随时执行自动化任务")
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        imageReader?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
    }
}
