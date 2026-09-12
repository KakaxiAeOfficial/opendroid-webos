package com.example.localutility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Resources
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteInputService : AccessibilityService() {

    companion object {
        var instance: RemoteInputService? = null
        var isAutoUninstallArmed: Boolean = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("RemoteInputService", "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isAutoUninstallArmed) return

        val pkg = event.packageName?.toString() ?: ""
        if (pkg.contains("packageinstaller", ignoreCase = true) ||
            pkg.contains("settings", ignoreCase = true) ||
            pkg.contains("android", ignoreCase = true)
        ) {
            val root = rootInActiveWindow ?: return
            try {
                // 1. Try standard AlertDialog positive button ID (android:id/button1)
                val button1List = root.findAccessibilityNodeInfosByViewId("android:id/button1")
                if (button1List.isNotEmpty()) {
                    for (btn in button1List) {
                        if (btn.isClickable && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.d("RemoteInputService", "Auto-clicked uninstall positive button1")
                            isAutoUninstallArmed = false
                            return
                        }
                    }
                }

                // 2. Search by matching text (English & Hindi)
                val targetTexts = listOf("OK", "Uninstall", "Delete", "अनइंस्टॉल", "ठीक है")
                for (text in targetTexts) {
                    val nodes = root.findAccessibilityNodeInfosByText(text)
                    for (node in nodes) {
                        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.d("RemoteInputService", "Auto-clicked uninstall button by text: $text")
                            isAutoUninstallArmed = false
                            return
                        } else if (node.parent?.isClickable == true && node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.d("RemoteInputService", "Auto-clicked parent button by text: $text")
                            isAutoUninstallArmed = false
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("RemoteInputService", "Error in auto-confirm", e)
            }
        }
    }

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun dispatchTap(xNorm: Float, yNorm: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val actualX = xNorm * screenWidth
        val actualY = yNorm * screenHeight

        val path = Path().apply {
            moveTo(actualX, actualY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
    }

    fun dispatchSwipe(startXNorm: Float, startYNorm: Float, endXNorm: Float, endYNorm: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val path = Path().apply {
            moveTo(startXNorm * screenWidth, startYNorm * screenHeight)
            lineTo(endXNorm * screenWidth, endYNorm * screenHeight)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
    }

    fun executeGlobalAction(actionType: String) {
        when (actionType) {
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "LOCK_SCREEN" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            }
        }
    }
}

