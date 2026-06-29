package com.yuketang.helper

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class FloatingTriggerService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_service"
        private const val OVERLAY_TAG = "answer_overlay"
    }

    private lateinit var windowManager: WindowManager
    private var floatingButton: View? = null
    private var answerCard: View? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs get() = getSharedPreferences("answer_helper_prefs", MODE_PRIVATE)

    private var isCapturing = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        showFloatingButton()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ===== Notification Channel =====
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "悬浮答题服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮触发按钮在后台运行"
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("答题悬浮助手")
            .setContentText("点击悬浮按钮或按音量键截屏答题")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ===== Floating Button =====
    private fun showFloatingButton() {
        if (floatingButton != null) return

        val layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = dpToPx(52)
            height = dpToPx(52)
            gravity = Gravity.START or Gravity.TOP
            x = dpToPx(16)
            y = dpToPx(200)
        }

        floatingButton = LayoutInflater.from(this).inflate(R.layout.floating_button, null).apply {
            setOnClickListener {
                if (!isCapturing) {
                    triggerCapture()
                } else {
                    Toast.makeText(this@FloatingTriggerService, "正在处理中...", Toast.LENGTH_SHORT).show()
                }
            }

            // Drag support
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_MOVE) {
                    layoutParams.x = event.rawX.toInt() - v.width / 2
                    layoutParams.y = event.rawY.toInt() - v.height / 2
                    windowManager.updateViewLayout(v, layoutParams)
                }
                false
            }
        }

        windowManager.addView(floatingButton, layoutParams)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // ===== Capture Flow =====
    private fun triggerCapture() {
        if (isCapturing) return
        isCapturing = true
        floatingButton?.alpha = 0.5f

        scope.launch {
            try {
                // Step 1: Initialize MediaProjection if needed
                if (mediaProjection == null) {
                    withContext(Dispatchers.Main) {
                        initMediaProjection()
                    }
                }

                if (mediaProjection == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FloatingTriggerService, "截屏权限未获取，请在设置页授权", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Step 2: Capture screenshot
                val bitmap = captureScreen()
                if (bitmap == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FloatingTriggerService, "截图失败", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Step 3: Show "analyzing" state
                withContext(Dispatchers.Main) {
                    showAnalyzingOverlay(bitmap)
                }

                // Step 4: Call AI API
                val answer = analyzeImage(bitmap)

                // Step 5: Show result
                withContext(Dispatchers.Main) {
                    showAnswerOverlay(answer)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showAnswerOverlay("发生错误：" + e.message)
                }
            } finally {
                isCapturing = false
                floatingButton?.alpha = 1.0f
            }
        }
    }

    private fun initMediaProjection() {
        // Check if we already have a pending intent from MainActivity
        val pendingIntent = MainActivity.pendingMediaProjectionIntent
        if (pendingIntent != null) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(Activity.RESULT_OK, pendingIntent)
        }
    }

    private fun captureScreen(): Bitmap? {
        try {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val densityDpi = metrics.densityDpi
            val rotation = windowManager.defaultDisplay.rotation

            if (mediaProjection == null) return null

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "capture",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            // Wait for the image
            val latch = java.util.concurrent.CountDownLatch(1)
            var resultBitmap: Bitmap? = null

            val handler = Handler(Looper.getMainLooper())

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    // Crop to actual width
                    if (rowPadding > 0) {
                        resultBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        bitmap.recycle()
                    } else {
                        resultBitmap = bitmap
                    }

                    image.close()
                }
                latch.countDown()
            }, handler)

            // Wait up to 2 seconds for screenshot
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)

            return resultBitmap

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        }
    }

    private fun analyzeImage(bitmap: Bitmap): String {
        val apiUrl = prefs.getString("api_url", "https://api.openai.com/v1/chat/completions") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("model", "gpt-4o") ?: ""
        val customPrompt = prefs.getString("custom_prompt", "") ?: ""

        if (apiKey.isEmpty()) {
            return "请先在设置页配置 API Key"
        }

        return AiApiClient.analyzeImage(
            apiUrl = apiUrl,
            apiKey = apiKey,
            model = model,
            imageBitmap = bitmap,
            customPrompt = customPrompt
        )
    }

    // ===== Answer Card Overlay =====
    private fun showAnalyzingOverlay(screenshot: Bitmap) {
        // Show a minimal "analyzing" indicator
        showAnswerOverlay("正在分析题目...\n请稍候")
    }

    private fun showAnswerOverlay(answer: String) {
        removeAnswerOverlay()

        val display = windowManager.defaultDisplay
        val metrics = DisplayMetrics()
        display.getMetrics(metrics)
        val screenWidth = metrics.widthPixels

        val layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = (screenWidth * 0.85).toInt()
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER
        }

        answerCard = LayoutInflater.from(this).inflate(R.layout.overlay_answer, null).apply {
            val titleView = findViewById<android.widget.TextView>(R.id.answerTitle)
            val contentView = findViewById<android.widget.TextView>(R.id.answerContent)
            val timeView = findViewById<android.widget.TextView>(R.id.answerTime)
            val closeBtn = findViewById<android.widget.TextView>(R.id.btnCloseOverlay)

            if (answer.startsWith("正在分析") || answer.startsWith("发生错误")) {
                titleView.text = if (answer.startsWith("正在分析")) "分析中" else "出错了"
            } else {
                titleView.text = "AI 答题结果"
            }

            contentView.text = answer
            timeView.text = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())

            closeBtn.setOnClickListener {
                removeAnswerOverlay()
            }

            // Auto dismiss after 30 seconds
            this.postDelayed({
                removeAnswerOverlay()
            }, 30000)
        }

        windowManager.addView(answerCard, layoutParams)
    }

    private fun removeAnswerOverlay() {
        answerCard?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        answerCard = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        floatingButton?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        floatingButton = null
        removeAnswerOverlay()
        mediaProjection?.stop()
        mediaProjection = null
    }
}
