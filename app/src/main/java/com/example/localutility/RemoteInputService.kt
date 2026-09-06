package com.example.localutility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class RemoteInputService : AccessibilityService() {

    companion object {
        var instance: RemoteInputService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun dispatchTap(normX: Float, normY: Float) {
        val metrics = resources.displayMetrics
        val x = normX * metrics.widthPixels
        val y = normY * metrics.heightPixels

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        dispatchGesture(gesture, null, null)
    }

    fun dispatchSwipe(normStartX: Float, normStartY: Float, normEndX: Float, normEndY: Float) {
        val metrics = resources.displayMetrics
        val startX = normStartX * metrics.widthPixels
        val startY = normStartY * metrics.heightPixels
        val endX = normEndX * metrics.widthPixels
        val endY = normEndY * metrics.heightPixels

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
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
        }
    }
}