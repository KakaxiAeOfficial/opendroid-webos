package com.example.localutility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteInputService : AccessibilityService() {

    companion object {
        var instance: RemoteInputService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("RemoteInputService", "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        // Check if this window belongs to PackageInstaller, System Dialog, or Settings
        val isInstaller = pkg.contains("packageinstaller", ignoreCase = true) ||
                pkg.contains("android", ignoreCase = true) ||
                className.contains("alert", ignoreCase = true) ||
                className.contains("dialog", ignoreCase = true)

        if (isInstaller) {
            val root = rootInActiveWindow ?: return
            try {
                // Verify this dialog is an Uninstall prompt
                val isUninstallDialog = isWindowUninstallPrompt(root)
                if (isUninstallDialog) {
                    confirmUninstallDialog(root)
                }
            } catch (e: Exception) {
                Log.e("RemoteInputService", "Auto-confirm error", e)
            }
        }
    }

    private fun isWindowUninstallPrompt(root: AccessibilityNodeInfo): Boolean {
        val keywords = listOf(
            "uninstall", "delete", "do you want to uninstall",
            "अनइंस्टॉल", "हटाएं", "ঠিক আছে", "আনইনস্টল", "মুছুন"
        )
        for (kw in keywords) {
            val list = root.findAccessibilityNodeInfosByText(kw)
            if (list.isNotEmpty()) return true
        }
        return false
    }

    private fun confirmUninstallDialog(root: AccessibilityNodeInfo) {
        // 1. Try standard Android AlertDialog positive button ID (android:id/button1)
        val button1List = root.findAccessibilityNodeInfosByViewId("android:id/button1")
        if (button1List.isNotEmpty()) {
            for (btn in button1List) {
                if (clickNodeWithGestureFallback(btn)) {
                    Log.d("RemoteInputService", "Confirmed uninstall via android:id/button1")
                    return
                }
            }
        }

        // 2. PackageInstaller specific IDs
        val installerBtnIds = listOf(
            "com.android.packageinstaller:id/ok_button",
            "com.google.android.packageinstaller:id/ok_button",
            "com.android.permissioncontroller:id/permission_allow_button"
        )
        for (id in installerBtnIds) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            for (btn in list) {
                if (clickNodeWithGestureFallback(btn)) {
                    Log.d("RemoteInputService", "Confirmed uninstall via viewId: $id")
                    return
                }
            }
        }

        // 3. Match button texts
        val targetTexts = listOf("OK", "Uninstall", "Delete", "अनइंस्टॉल", "ठीक है", "আনইনস্টল", "ঠিক আছে")
        for (text in targetTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    if (clickNodeWithGestureFallback(node)) {
                        Log.d("RemoteInputService", "Confirmed uninstall via text: $text")
                        return
                    }
                } else if (node.parent?.isClickable == true) {
                    if (clickNodeWithGestureFallback(node.parent)) {
                        Log.d("RemoteInputService", "Confirmed uninstall via parent of: $text")
                        return
                    }
                }
            }
        }
    }

    private fun clickNodeWithGestureFallback(node: AccessibilityNodeInfo): Boolean {
        // Method A: Exact Physical Screen Coordinates Tap Gesture (bypasses performAction security blocks)
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            val centerX = rect.centerX().toFloat()
            val centerY = rect.centerY().toFloat()
            dispatchTapPixels(centerX, centerY)
            Log.d("RemoteInputService", "Dispatched physical touch gesture at ($centerX, $centerY)")
        }

        // Method B: Accessibility Action Click
        val actionSuccess = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return actionSuccess || (rect.width() > 0 && rect.height() > 0)
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


