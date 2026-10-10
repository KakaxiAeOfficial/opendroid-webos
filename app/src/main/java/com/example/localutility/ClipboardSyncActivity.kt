package com.example.localutility

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

class ClipboardSyncActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var isHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.setBackgroundDrawableResource(android.R.color.transparent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setTranslucent(true)
            }
        } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isHandled) {
            handler.postDelayed({
                readAndSyncClipboard()
                finishWithNoAnim()
            }, 30)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed({
            if (!isHandled) {
                readAndSyncClipboard()
                finishWithNoAnim()
            }
        }, 120)
    }

    private fun readAndSyncClipboard() {
        if (isHandled) return
        isHandled = true
        try {
            val isFetch = intent.getBooleanExtra("IS_FETCH", true)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            val text = clip?.getItemAt(0)?.text?.toString() ?: ""

            if (text.isNotBlank()) {
                val teleManager = TelephonyAndLocationManager(applicationContext)
                teleManager.saveToClipboardHistory(text, "Phone")

                val service = LocalFileServerService.instance
                if (service != null) {
                    if (isFetch) {
                        service.broadcastClipboardData(text)
                    } else {
                        service.broadcastPhoneCopied(text)
                    }
                }
                Log.d("ClipboardSyncActivity", "Clipboard read success: ${text.take(30)}...")
            } else {
                Log.d("ClipboardSyncActivity", "Clipboard is empty")
                if (isFetch) {
                    LocalFileServerService.instance?.broadcastClipboardData("")
                }
            }
        } catch (e: Exception) {
            Log.e("ClipboardSyncActivity", "Error reading clipboard", e)
        }
    }

    private fun finishWithNoAnim() {
        finish()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        } catch (_: Exception) {}
    }
}

