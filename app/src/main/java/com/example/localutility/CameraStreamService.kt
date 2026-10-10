package com.example.localutility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class CameraStreamService : Service() {

    private lateinit var cameraManager: CameraManager
    private var isTorchOn = false

    companion object {
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "CameraStreamChannel"
        var instance: CameraStreamService? = null

        /**
         * Safely stops the CameraStreamService, immediately clears the foreground camera status,
         * cancels the notification, and drops the Android OS green camera privacy indicator.
         */
        fun stopServiceSafely(context: Context) {
            try {
                instance?.let { service ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        service.stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        service.stopForeground(true)
                    }
                    val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.cancel(NOTIFICATION_ID)
                    service.stopSelf()
                }
                val intent = Intent(context, CameraStreamService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e("CameraStreamService", "stopServiceSafely error: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Camera background sync running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("CameraStreamService", "FGS start safe fallback: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e("CameraStreamService", "Fatal FGS fallback error", e2)
            }
        }

        return START_NOT_STICKY
    }

    fun toggleFlashlight() {
        try {
            val backCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            backCameraId?.let {
                isTorchOn = !isTorchOn
                cameraManager.setTorchMode(it, isTorchOn)
            }
        } catch (e: Exception) {
            Log.e("CameraStreamService", "Failed to toggle torch", e)
        }
    }

    fun turnOffFlashlight() {
        if (!isTorchOn) return
        try {
            val backCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            backCameraId?.let {
                cameraManager.setTorchMode(it, false)
                isTorchOn = false
            }
        } catch (e: Exception) {
            Log.e("CameraStreamService", "Failed to turn off torch", e)
        }
    }

    override fun onDestroy() {
        try {
            if (isTorchOn) {
                turnOffFlashlight()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e("CameraStreamService", "onDestroy cleanup error: ${e.message}")
        }
        super.onDestroy()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Camera Stream", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}


