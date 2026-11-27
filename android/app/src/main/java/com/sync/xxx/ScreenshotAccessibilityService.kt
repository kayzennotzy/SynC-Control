package com.sync.xxx

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class ScreenshotAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ScreenshotAccessibilityService? = null
        private const val TAG = "ScreenshotA11y"

        fun isRunning(): Boolean = instance != null

        fun requestScreenshot(onDone: (String?) -> Unit) {
            val svc = instance
            if (svc == null) {
                Log.e(TAG, "service not running")
                onDone(null)
                return
            }
            svc.takeScreenshotInternal(onDone)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "AccessibilityService destroyed")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshotInternal(onDone: (String?) -> Unit) {
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hw: android.hardware.HardwareBuffer = screenshot.hardwareBuffer
                            val bmp: Bitmap? = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                            try { hw.close() } catch (_: Exception) {}
                            if (bmp == null) {
                                onDone(null)
                                return
                            }
                            val soft = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            bmp.recycle()
                            if (soft == null) {
                                onDone(null)
                                return
                            }
                            val bos = ByteArrayOutputStream()
                            soft.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                            soft.recycle()
                            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                            onDone(b64)
                        } catch (e: Exception) {
                            Log.e(TAG, "convert fail: ${e.message}")
                            onDone(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "takeScreenshot fail code=$errorCode")
                        onDone(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshotInternal: ${e.message}")
            onDone(null)
        }
    }
}