package com.yuketang.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * AccessibilityService that detects Volume Up + Volume Down simultaneous press
 * to trigger screen capture and AI answer analysis.
 */
class VolumeKeyService : AccessibilityService() {

    companion object {
        private const val TRIGGER_WINDOW_MS = 500L
    }

    private var volUpTime = 0L
    private var volDownTime = 0L
    private var lastTriggerTime = 0L
    private var isProcessing = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for key detection, only for window changes
    }

    override fun onInterrupt() {
        // Service interrupted
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()

            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    volUpTime = now
                    // Check if volume down was pressed recently
                    if (now - volDownTime < TRIGGER_WINDOW_MS) {
                        triggerCapture()
                        return true // Consume both keys
                    }
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    volDownTime = now
                    // Check if volume up was pressed recently
                    if (now - volUpTime < TRIGGER_WINDOW_MS) {
                        triggerCapture()
                        return true
                    }
                }
            }
        }
        return super.onKeyEvent(event)
    }

    private fun triggerCapture() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < 2000) return // Debounce: 2 seconds
        if (isProcessing) return

        lastTriggerTime = now
        isProcessing = true

        // Vibrate to confirm trigger
        vibrate()

        // Show toast feedback
        Toast.makeText(this, "? 正在截屏答题...", Toast.LENGTH_SHORT).show()

        // Start the floating service to handle capture
        val intent = Intent(this, FloatingTriggerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Give the service some time to register the trigger
        android.os.Handler(mainLooper).postDelayed({
            // The FloatingTriggerService will handle the actual capture
            // We notify it via the static callback
            MainActivity.onTriggerCapture?.invoke()
            isProcessing = false
        }, 300)

        // Reset after processing
        android.os.Handler(mainLooper).postDelayed({
            isProcessing = false
        }, 3000)
    }

    private fun vibrate() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                vibrator.vibrate(100)
            }
        } catch (e: Exception) {
            // Ignore if no vibrator
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Toast.makeText(this, "音量键触发已就绪（同时按音量上下键）", Toast.LENGTH_LONG).show()
    }
}
