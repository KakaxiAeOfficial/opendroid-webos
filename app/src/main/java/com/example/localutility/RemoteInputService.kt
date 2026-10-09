package com.example.localutility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import java.util.Locale

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
        var isAutoForceStopArmed: Boolean = false
        var targetForceStopPkg: String = ""
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Phase 18: Real-Time Parental Control & App Limits Enforcer
    private val teleManager: TelephonyAndLocationManager by lazy {
        TelephonyAndLocationManager(applicationContext)
    }
    private var currentForegroundPackage: String = ""
    private var lastBlockedPkg: String = ""
    private var lastBlockedTime: Long = 0L

    fun isLauncherOrSystem(packageName: String): Boolean {
        if (packageName.isEmpty()) return true
        if (packageName == applicationContext.packageName) return true

        val lower = packageName.lowercase(Locale.US)
        if (lower.contains("launcher") || lower.contains("home") ||
            lower.contains("systemui") || lower.contains("inputmethod") ||
            lower.contains("keyguard") || lower.contains("dialer") ||
            lower.contains("telecom") || lower.contains("settings") ||
            lower.contains("permissioncontroller") || lower.contains("packageinstaller") ||
            lower.contains("google.android.gms")
        ) {
            return true
        }

        // Dynamically resolve device's active Home Launcher
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val currentLauncherPkg = resolveInfo?.activityInfo?.packageName
            if (currentLauncherPkg != null && packageName == currentLauncherPkg) {
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    private val activeWatchdogRunnable = object : Runnable {
        override fun run() {
            try {
                if (currentForegroundPackage.isNotEmpty() && !isLauncherOrSystem(currentForegroundPackage)) {
                    checkAndEnforcePolicy(currentForegroundPackage)
                }
            } catch (e: Exception) {
                Log.e("RemoteInputService", "Watchdog check error", e)
            }
            mainHandler.postDelayed(this, 3000L)
        }
    }

    fun triggerImmediatePolicyCheck() {
        mainHandler.post {
            try {
                if (currentForegroundPackage.isNotEmpty() && !isLauncherOrSystem(currentForegroundPackage)) {
                    checkAndEnforcePolicy(currentForegroundPackage)
                }
            } catch (e: Exception) {
                Log.e("RemoteInputService", "Immediate policy check error", e)
            }
        }
    }

    fun forceStopPackage(packageName: String) {
        if (packageName.isEmpty() || isLauncherOrSystem(packageName)) {
            Log.w("RemoteInputService", "Cannot force stop protected/system package: $packageName")
            return
        }

        // Inactive App Silent Gatekeeper: If app is not in foreground, do NOT open App info UI
        if (currentForegroundPackage != packageName) {
            Log.d("RemoteInputService", "Package $packageName is not in foreground, silent background kill without opening UI")
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                am?.killBackgroundProcesses(packageName)
            } catch (_: Exception) {}
            return
        }

        isAutoForceStopArmed = true
        targetForceStopPkg = packageName

        mainHandler.post {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                startActivity(intent)

                // Safety timeout: disarm force stop after 4 seconds if not triggered
                mainHandler.postDelayed({
                    if (isAutoForceStopArmed) {
                        isAutoForceStopArmed = false
                        targetForceStopPkg = ""
                    }
                }, 4000L)
            } catch (e: Exception) {
                Log.e("RemoteInputService", "Error launching details for force stop: $packageName", e)
                isAutoForceStopArmed = false
                targetForceStopPkg = ""
            }
        }
    }

    private fun checkAndEnforcePolicy(pkg: String) {
        if (isLauncherOrSystem(pkg)) return

        try {
            val blockedResult = teleManager.isPackageCurrentlyBlocked(pkg)
            if (blockedResult.first) {
                val reason = blockedResult.second

                // Anti-PiP & Clean Exit: Press BACK first to exit video/media playback (preventing floating PiP window), then press HOME
                performGlobalAction(GLOBAL_ACTION_BACK)
                mainHandler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }, 150L)

                // Debounce toast alert so child isn't spammed
                val now = System.currentTimeMillis()
                if (pkg != lastBlockedPkg || (now - lastBlockedTime) > 3500L) {
                    lastBlockedPkg = pkg
                    lastBlockedTime = now
                    mainHandler.post {
                        Toast.makeText(
                            applicationContext,
                            reason,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RemoteInputService", "Error enforcing policy for $pkg", e)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("RemoteInputService", "Accessibility Service Connected")
        mainHandler.postDelayed(activeWatchdogRunnable, 3000L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. Phase 18: Real-Time App Limits, Focus Mode & Bedtime Enforcement
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val eventPkg = event.packageName?.toString() ?: ""
            if (eventPkg.isNotEmpty() && !isLauncherOrSystem(eventPkg)) {
                currentForegroundPackage = eventPkg
                checkAndEnforcePolicy(eventPkg)
            }
        }

        // 2. Automated Remote Force Stop Engine (Dual-OS MIUI SecurityCenter + AOSP Settings)
        if (isAutoForceStopArmed) {
            val eventPkg = event.packageName?.toString() ?: ""
            if (eventPkg.contains("securitycenter", ignoreCase = true) || eventPkg.contains("settings", ignoreCase = true)) {
                mainHandler.postDelayed({
                    if (isAutoForceStopArmed) {
                        tryAutoForceStop()
                    }
                }, 150L)

                // 350ms Strict Fail-Safe: Force Home launcher so screen is NEVER stuck on App info
                mainHandler.postDelayed({
                    if (isAutoForceStopArmed) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        isAutoForceStopArmed = false
                        targetForceStopPkg = ""
                    }
                }, 350L)
            }
        }

        // 3. Auto-Uninstall Safety Gate
        if (isAutoUninstallArmed) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg.contains("packageinstaller", ignoreCase = true) ||
                pkg.contains("permissioncontroller", ignoreCase = true)
            ) {
                mainHandler.postDelayed({
                    if (isAutoUninstallArmed) {
                        tryAutoConfirmUninstall()
                    }
                }, 350L)
            }
        }
    }

    private fun tryAutoForceStop() {
        try {
            val activeRoot = rootInActiveWindow ?: return
            
            // Search for Force Stop button (MIUI SecurityCenter + AOSP Settings IDs)
            val stopButtonIds = listOf(
                "com.miui.securitycenter:id/force_stop",
                "com.miui.securitycenter:id/btn_force_stop",
                "com.miui.securitycenter:id/right_button",
                "com.android.settings:id/force_stop_button",
                "com.android.settings:id/button_stop",
                "com.android.settings:id/right_button",
                "android:id/button1"
            )

            var clickedStop = false
            for (id in stopButtonIds) {
                val list = activeRoot.findAccessibilityNodeInfosByViewId(id)
                for (node in list) {
                    if (node.isClickable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.d("RemoteInputService", "Clicked Force Stop by ID: $id")
                        clickedStop = true
                        break
                    }
                }
                if (clickedStop) break
            }

            if (!clickedStop) {
                val stopTexts = listOf("Force stop", "Force Stop", "फ़ोर्स स्टॉप", "रोकें", "Force close", "OK", "ठीक है")
                for (text in stopTexts) {
                    val nodes = activeRoot.findAccessibilityNodeInfosByText(text)
                    for (node in nodes) {
                        if (node.isClickable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.d("RemoteInputService", "Clicked Force Stop by text: $text")
                            clickedStop = true
                            break
                        } else if (node.parent?.isClickable == true && node.parent.isEnabled && node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.d("RemoteInputService", "Clicked Force Stop by parent text: $text")
                            clickedStop = true
                            break
                        }
                    }
                    if (clickedStop) break
                }
            }

            // After clicking Force stop, handle the confirmation dialog if it pops up, then immediately press HOME
            mainHandler.postDelayed({
                try {
                    val dialogRoot = rootInActiveWindow
                    if (dialogRoot != null) {
                        val confirmTexts = listOf("OK", "Force stop", "Force Stop", "ठीक है", "रोकें")
                        for (txt in confirmTexts) {
                            val confirmNodes = dialogRoot.findAccessibilityNodeInfosByText(txt)
                            for (cNode in confirmNodes) {
                                if (cNode.isClickable && cNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                    Log.d("RemoteInputService", "Confirmed Force Stop dialog: $txt")
                                    break
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}

                // Immediately return to Home screen and notify user
                performGlobalAction(GLOBAL_ACTION_HOME)
                isAutoForceStopArmed = false
                val stopped = targetForceStopPkg
                targetForceStopPkg = ""
                mainHandler.post {
                    Toast.makeText(applicationContext, "🛑 $stopped force stopped", Toast.LENGTH_SHORT).show()
                }
            }, 300L)

        } catch (e: Exception) {
            Log.e("RemoteInputService", "Error during tryAutoForceStop", e)
            isAutoForceStopArmed = false
            targetForceStopPkg = ""
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
        mainHandler.removeCallbacks(activeWatchdogRunnable)
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

