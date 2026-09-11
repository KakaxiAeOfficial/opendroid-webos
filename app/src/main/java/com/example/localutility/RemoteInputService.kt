package com.example.localutility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class RemoteInputService : AccessibilityService() {

    companion object {
        var instance: RemoteInputService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("RemoteInput", "Accessibility Service Connected & Active!")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.d("RemoteInput", "Accessibility Service Unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // Get real physical screen dimensions (including punch-hole and system insets)
    private fun getRealScreenBounds(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    fun dispatchTap(normX: Float, normY: Float) {
        try {
            val (screenWidth, screenHeight) = getRealScreenBounds()
            val x = (normX * screenWidth).coerceIn(0f, screenWidth.toFloat())
            val y = (normY * screenHeight).coerceIn(0f, screenHeight.toFloat())

            val path = Path().apply { moveTo(x, y) }
            // 80ms duration guarantees recognition across all Android OEM touch filters
            val stroke = GestureDescription.StrokeDescription(path, 0, 80)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d("RemoteInput", "Tap executed at: $x, $y")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w("RemoteInput", "Tap cancelled at: $x, $y")
                }
            }, null)
        } catch (e: Exception) {
            Log.e("RemoteInput", "Error dispatching tap", e)
        }
    }

    fun dispatchSwipe(normStartX: Float, normStartY: Float, normEndX: Float, normEndY: Float) {
        try {
            val (screenWidth, screenHeight) = getRealScreenBounds()
            val startX = (normStartX * screenWidth).coerceIn(0f, screenWidth.toFloat())
            val startY = (normStartY * screenHeight).coerceIn(0f, screenHeight.toFloat())
            val endX = (normEndX * screenWidth).coerceIn(0f, screenWidth.toFloat())
            val endY = (normEndY * screenHeight).coerceIn(0f, screenHeight.toFloat())

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            // 250ms smooth scroll stroke
            val stroke = GestureDescription.StrokeDescription(path, 0, 250)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d("RemoteInput", "Swipe executed from ($startX, $startY) to ($endX, $endY)")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w("RemoteInput", "Swipe cancelled")
                }
            }, null)
        } catch (e: Exception) {
            Log.e("RemoteInput", "Error dispatching swipe", e)
        }
    }

    fun executeGlobalAction(actionType: String) {
        when (actionType) {
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }
}