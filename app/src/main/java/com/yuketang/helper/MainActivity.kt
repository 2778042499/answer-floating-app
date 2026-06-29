package com.yuketang.helper

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_OVERLAY = 1001
        private const val REQUEST_CODE_MEDIA_PROJECTION = 1002
        private const val PREFS_NAME = "answer_helper_prefs"
        private const val KEY_API_URL = "api_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_PROMPT = "custom_prompt"

        // Singleton for sharing capture result across activities
        var pendingMediaProjectionIntent: Intent? = null

        // Callback for when a screenshot+AI analysis is requested
        var onTriggerCapture: (() -> Unit)? = null
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var uiHandler: Handler

    // UI elements
    private lateinit var btnOverlay: MaterialButton
    private lateinit var btnNotif: MaterialButton
    private lateinit var btnA11y: MaterialButton
    private lateinit var btnStartFloating: MaterialButton
    private lateinit var btnSaveApi: MaterialButton
    private lateinit var overlayStatus: android.widget.TextView
    private lateinit var notifStatus: android.widget.TextView
    private lateinit var a11yStatus: android.widget.TextView
    private lateinit var statusText: android.widget.TextView
    private lateinit var apiUrlInput: TextInputEditText
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var modelInput: TextInputEditText
    private lateinit var promptInput: TextInputEditText

    private var statusChecker: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        uiHandler = Handler(Looper.getMainLooper())

        bindViews()
        loadSavedConfig()
        updateAllStatus()

        // If coming back from media projection permission
        if (savedInstanceState == null) {
            handleMediaProjectionIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateAllStatus()
        startStatusChecker()
    }

    override fun onPause() {
        super.onPause()
        statusChecker?.cancel()
    }

    private fun bindViews() {
        overlayStatus = findViewById(R.id.overlayStatus)
        notifStatus = findViewById(R.id.notifStatus)
        a11yStatus = findViewById(R.id.a11yStatus)
        statusText = findViewById(R.id.statusText)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnNotif = findViewById(R.id.btnNotif)
        btnA11y = findViewById(R.id.btnA11y)
        btnStartFloating = findViewById(R.id.btnStartFloating)
        btnSaveApi = findViewById(R.id.btnSaveApi)
        apiUrlInput = findViewById(R.id.apiUrl)
        apiKeyInput = findViewById(R.id.apiKey)
        modelInput = findViewById(R.id.modelName)
        promptInput = findViewById(R.id.customPrompt)

        btnOverlay.setOnClickListener { requestOverlayPermission() }
        btnNotif.setOnClickListener { requestNotificationPermission() }
        btnA11y.setOnClickListener { openAccessibilitySettings() }
        btnSaveApi.setOnClickListener { saveApiConfig() }

        btnStartFloating.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
                Toast.makeText(this, "请先授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startFloatingService()
        }
    }

    // ===== Permission Requests =====
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_CODE_OVERLAY)
            } else {
                Toast.makeText(this, "悬浮窗权限已授权", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "通知权限已授权", Toast.LENGTH_SHORT).show()
            }
        } else {
            // On older Android, notifications are on by default
            Toast.makeText(this, "通知权限已授权", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "请在无障碍列表中找到「答题悬浮助手」并开启", Toast.LENGTH_LONG).show()
    }

    // ===== Media Projection =====
    private fun handleMediaProjectionIntent(intent: Intent?) {
        if (intent?.action == "REQUEST_MEDIA_PROJECTION") {
            val mediaProjectionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("intent", Intent::class.java)
            } else {
                intent.getParcelableExtra<Intent>("intent")
            }
            if (mediaProjectionIntent != null) {
                pendingMediaProjectionIntent = mediaProjectionIntent
                Toast.makeText(this, "截屏权限已获取", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "悬浮窗权限已授权", Toast.LENGTH_SHORT).show()
                }
                updateAllStatus()
            }
            REQUEST_CODE_MEDIA_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    pendingMediaProjectionIntent = data
                    Toast.makeText(this, "截屏权限已获取", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ===== Floating Service =====
    private fun startFloatingService() {
        val intent = Intent(this, FloatingTriggerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "悬浮按钮已启动", Toast.LENGTH_SHORT).show()
        statusText.text = "运行中 - 悬浮按钮已显示"
        statusText.setTextColor(0xFF4CAF50.toInt())
    }

    // ===== API Config =====
    private fun saveApiConfig() {
        prefs.edit().apply {
            putString(KEY_API_URL, apiUrlInput.text?.toString()?.trim() ?: "")
            putString(KEY_API_KEY, apiKeyInput.text?.toString()?.trim() ?: "")
            putString(KEY_MODEL, modelInput.text?.toString()?.trim() ?: "gpt-4o")
            putString(KEY_PROMPT, promptInput.text?.toString()?.trim() ?: "")
            apply()
        }
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun loadSavedConfig() {
        apiUrlInput.setText(prefs.getString(KEY_API_URL, "https://api.openai.com/v1/chat/completions"))
        apiKeyInput.setText(prefs.getString(KEY_API_KEY, ""))
        modelInput.setText(prefs.getString(KEY_MODEL, "gpt-4o"))
        promptInput.setText(prefs.getString(KEY_PROMPT, ""))
    }

    // ===== Status Checks =====
    private fun startStatusChecker() {
        statusChecker?.cancel()
        statusChecker = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                updateAllStatus()
                delay(2000)
            }
        }
    }

    private fun updateAllStatus() {
        updateOverlayStatus()
        updateNotifStatus()
        updateA11yStatus()
    }

    private fun updateOverlayStatus() {
        if (Settings.canDrawOverlays(this)) {
            overlayStatus.text = "已授权"
            overlayStatus.setTextColor(0xFF4CAF50.toInt())
            btnOverlay.text = "正常"
        } else {
            overlayStatus.text = "未授权"
            overlayStatus.setTextColor(0xFFF44336.toInt())
            btnOverlay.text = "授权"
        }
    }

    private fun updateNotifStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                notifStatus.text = "已授权"
                notifStatus.setTextColor(0xFF4CAF50.toInt())
                btnNotif.text = "正常"
            } else {
                notifStatus.text = "未授权"
                notifStatus.setTextColor(0xFFF44336.toInt())
                btnNotif.text = "授权"
            }
        } else {
            notifStatus.text = "已授权"
            notifStatus.setTextColor(0xFF4CAF50.toInt())
            btnNotif.text = "正常"
        }
    }

    private fun updateA11yStatus() {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        val ourService = enabledServices.any { service ->
            service.resolveInfo?.serviceInfo?.packageName == packageName
        }
        if (ourService) {
            a11yStatus.text = "已开启"
            a11yStatus.setTextColor(0xFF4CAF50.toInt())
            btnA11y.text = "正常"
        } else {
            a11yStatus.text = "未开启"
            a11yStatus.setTextColor(0xFFF44336.toInt())
            btnA11y.text = "开启"
        }
    }
}
