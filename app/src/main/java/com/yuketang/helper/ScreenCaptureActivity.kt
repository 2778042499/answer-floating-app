package com.yuketang.helper

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Transparent activity that requests MediaProjection screen capture permission.
 * Passes the result intent back to MainActivity.
 */
class ScreenCaptureActivity : Activity() {

    companion object {
        private const val REQUEST_CODE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if we already have overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        }

        // Request media projection permission
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mpm.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            // Store the media projection intent
            MainActivity.pendingMediaProjectionIntent = data
            Toast.makeText(this, "截屏权限已获取", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "截屏权限获取失败", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
