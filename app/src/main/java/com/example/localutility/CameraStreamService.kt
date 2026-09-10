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
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Active")
            .setContentText("Streaming live camera to Web Controller")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
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

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Camera Stream", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}