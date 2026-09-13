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
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("RemoteInputService", "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: ""
        // Check if package is PackageInstaller or Android system dialog
        if (pkg.contains("packageinstaller", ignoreCase = true) ||
            pkg.contains("android", ignoreCase = true) ||
            pkg.contains("settings", ignoreCase = true)
        ) {
            // Delay to allow dialog animation to complete and window to become interactive
            mainHandler.postDelayed({
                tryAutoConfirmUninstall()
            }, 350)

            mainHandler.postDelayed({
                tryAutoConfirmUninstall()
            }, 750)
        }
    }

    private fun tryAutoConfirmUninstall() {
        try {
            // 1. Try active root window
            val activeRoot = rootInActiveWindow
            if (activeRoot != null && processWindowForUninstall(activeRoot)) {
                return
            }

            // 2. Iterate through all windows if active root didn't catch it
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val currentWindows = windows
                for (win in currentWindows) {
                    val root = win.root ?: continue
                    if (processWindowForUninstall(root)) {
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RemoteInputService", "Error during tryAutoConfirmUninstall", e)
        }
    }

    private fun processWindowForUninstall(root: AccessibilityNodeInfo): Boolean {
        // Look for Positive Button IDs across standard Android and OEMs
        val buttonIds = listOf(
            "android:id/button1",
            "com.android.packageinstaller:id/ok_button",
            "com.google.android.packageinstaller:id/ok_button",
            "com.android.packageinstaller:id/btn_ok",
            "com.android.permissioncontroller:id/permission_allow_button"
        )

        for (id in buttonIds) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            for (node in list) {
                if (triggerClickOnNode(node)) {
                    Log.d("RemoteInputService", "Auto-confirmed uninstall via id: $id")
                    return true
                }
            }
        }

        // Look for Positive Button Texts (English, Hindi, Bengali)
        val buttonTexts = listOf("OK", "Uninstall", "Delete", "अनइंस्टॉल", "ठीक है", "আনইনস্টল", "ঠিক আছে")
        for (text in buttonTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    if (triggerClickOnNode(node)) {
                        Log.d("RemoteInputService", "Auto-confirmed uninstall via text: $text")
                        return true
                    }
                } else if (node.parent?.isClickable == true) {
                    if (triggerClickOnNode(node.parent)) {
                        Log.d("RemoteInputService", "Auto-confirmed uninstall via parent of: $text")
                        return true
                    }
                }
            }
        }

        return false
    }

    private fun triggerClickOnNode(node: AccessibilityNodeInfo): Boolean {
        // 1. Physical Touch Gesture Tap at exact screen center of the button
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            val cx = rect.centerX().toFloat()
            val cy = rect.centerY().toFloat()
            dispatchTapPixels(cx, cy)
            Log.d("RemoteInputService", "Dispatched physical touch tap at ($cx, $cy)")
        }

        // 2. Programmatic Accessibility Action Click
        val actionDone = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        return actionDone || (rect.width() > 0 && rect.height() > 0)
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

