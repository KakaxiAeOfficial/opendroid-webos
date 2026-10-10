package com.example.localutility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.util.Size
import org.json.JSONObject
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class DirectCameraStreamer private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: DirectCameraStreamer? = null

        fun getInstance(context: Context): DirectCameraStreamer {
            return instance ?: synchronized(this) {
                instance ?: DirectCameraStreamer(context.applicationContext).also { instance = it }
            }
        }
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var currentCharacteristics: CameraCharacteristics? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    var isStreaming = false
        private set
    var isFrontCamera = false
        private set
    var isTorchOn = false
        private set

    // Phase 22: Quality Presets, Digital Zoom & Snapshot Pipeline
    var currentQuality: String = "medium" // low (360p Data-Saver), medium (480p), high (720p)
        private set
    var currentZoom: Float = 1.0f
        private set
    private var currentWidth = 640
    private var currentHeight = 480
    private var targetFpsMs: Long = 66L // ~15 FPS default
    private var lastFrameTime = 0L

    private val cameraOpenCloseLock = Semaphore(1)
    private var onFrameCaptured: ((String) -> Unit)? = null
    private var pendingSnapshotCallback: ((String) -> Unit)? = null

    fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("DirectCameraBackground").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join(300)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e("DirectCamera", "Error stopping thread", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startStreaming(
        frontFacing: Boolean,
        quality: String = "medium",
        onFrame: (String) -> Unit
    ) {
        stopStreaming()
        isStreaming = true
        isFrontCamera = frontFacing
        isTorchOn = false
        currentQuality = quality
        currentZoom = 1.0f
        onFrameCaptured = onFrame
        startBackgroundThread()

        configureQualityParameters(quality)

        try {
            val cameraId = getCameraId(frontFacing) ?: return
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            currentCharacteristics = characteristics
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val outputSize = chooseResolutionForQuality(map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray(), quality)
            currentWidth = outputSize.width
            currentHeight = outputSize.height

            imageReader = ImageReader.newInstance(currentWidth, currentHeight, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

                        // Check if a high-res snapshot was requested
                        if (pendingSnapshotCallback != null) {
                            val cb = pendingSnapshotCallback
                            pendingSnapshotCallback = null
                            cb?.invoke(base64)
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastFrameTime >= targetFpsMs) {
                            lastFrameTime = now
                            onFrameCaptured?.invoke(base64)
                        }
                    } catch (e: Exception) {
                        Log.e("DirectCamera", "Frame processing error", e)
                    } finally {
                        image.close()
                    }
                }, backgroundHandler)
            }

            if (!cameraOpenCloseLock.tryAcquire(1500, TimeUnit.MILLISECONDS)) {
                Log.w("DirectCamera", "Timeout waiting for camera lock, forcing open")
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try { cameraOpenCloseLock.release() } catch (_: Exception) {}
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { cameraOpenCloseLock.release() } catch (_: Exception) {}
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    try { cameraOpenCloseLock.release() } catch (_: Exception) {}
                    camera.close()
                    cameraDevice = null
                    Log.e("DirectCamera", "Camera error code: $error")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e("DirectCamera", "Failed to start camera streaming", e)
        }
    }

    private fun configureQualityParameters(quality: String) {
        when (quality.lowercase()) {
            "low" -> {
                targetFpsMs = 100L // 10 FPS for ultra data-saving
            }
            "high" -> {
                targetFpsMs = 50L  // ~20 FPS
            }
            else -> {
                targetFpsMs = 66L  // ~15 FPS
            }
        }
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val readerSurface = imageReader?.surface ?: return

        try {
            val sensorOrientation = currentCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val jpegQuality = when (currentQuality.lowercase()) {
                "low" -> 35.toByte()  // ultra data-saver compression
                "high" -> 70.toByte()
                else -> 50.toByte()
            }

            val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                set(CaptureRequest.JPEG_QUALITY, jpegQuality)

                if (!isFrontCamera && isTorchOn) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }

                applyZoomToRequest(this, currentZoom)
            }

            device.createCaptureSession(listOf(readerSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                        Log.d("DirectCamera", "Live stream session active! [${currentWidth}x${currentHeight} @ $currentQuality]")
                    } catch (e: Exception) {
                        Log.e("DirectCamera", "setRepeatingRequest error", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("DirectCamera", "Capture session configuration failed")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e("DirectCamera", "createCaptureSession error", e)
        }
    }

    fun switchCamera(onFrame: ((String) -> Unit)? = null) {
        val targetFront = !isFrontCamera
        val callback = onFrame ?: onFrameCaptured ?: {}
        startStreaming(targetFront, currentQuality, callback)
    }

    fun setQuality(quality: String) {
        if (!isStreaming || quality.equals(currentQuality, ignoreCase = true)) return
        currentQuality = quality
        startStreaming(isFrontCamera, quality, onFrameCaptured ?: {})
    }

    fun setZoom(zoomRatio: Float) {
        if (!isStreaming || cameraDevice == null || captureSession == null || imageReader == null) return
        currentZoom = zoomRatio.coerceIn(1.0f, getMaxZoom())
        updateCaptureRequest()
    }

    fun toggleTorch(): Boolean {
        if (isFrontCamera || cameraDevice == null || captureSession == null || imageReader == null) return false
        isTorchOn = !isTorchOn
        updateCaptureRequest()
        return isTorchOn
    }

    fun captureSnapshot(callback: (String) -> Unit) {
        if (!isStreaming) {
            callback.invoke("")
            return
        }
        pendingSnapshotCallback = callback
    }

    private fun updateCaptureRequest() {
        try {
            val device = cameraDevice ?: return
            val session = captureSession ?: return
            val reader = imageReader ?: return
            val sensorOrientation = currentCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val jpegQuality = when (currentQuality.lowercase()) {
                "low" -> 35.toByte()
                "high" -> 70.toByte()
                else -> 50.toByte()
            }

            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                set(CaptureRequest.JPEG_QUALITY, jpegQuality)

                if (isTorchOn && !isFrontCamera) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }

                applyZoomToRequest(this, currentZoom)
            }

            session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e("DirectCamera", "Error updating capture request", e)
        }
    }

    private fun applyZoomToRequest(builder: CaptureRequest.Builder, zoom: Float) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            } else {
                val sensorRect = currentCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
                val cropWidth = (sensorRect.width() / zoom).toInt()
                val cropHeight = (sensorRect.height() / zoom).toInt()
                val cropX = (sensorRect.width() - cropWidth) / 2
                val cropY = (sensorRect.height() - cropHeight) / 2
                val cropRect = Rect(cropX, cropY, cropX + cropWidth, cropY + cropHeight)
                builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
            }
        } catch (_: Exception) {}
    }

    fun getMaxZoom(): Float {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val zoomRange = currentCharacteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                zoomRange?.upper ?: 4.0f
            } else {
                val maxZoom = currentCharacteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                maxZoom ?: 4.0f
            }
        } catch (_: Exception) {
            4.0f
        }
    }

    fun getCameraTelemetry(): JSONObject {
        return JSONObject().apply {
            put("isStreaming", isStreaming)
            put("isFrontCamera", isFrontCamera)
            put("isTorchOn", isTorchOn)
            put("quality", currentQuality)
            put("width", currentWidth)
            put("height", currentHeight)
            put("zoom", currentZoom)
            put("maxZoom", getMaxZoom())
            put("hasFlash", hasFlash())
        }
    }

    fun hasFlash(): Boolean {
        return try {
            currentCharacteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Instant hardware release:
     * 1. Abort repeating requests & in-flight captures immediately so Camera2 stops sending frames.
     * 2. Nullify ImageReader callback to drop pending frames immediately.
     * 3. Close capture session and device without thread blocking.
     */
    fun stopStreaming() {
        val acquired = try {
            cameraOpenCloseLock.tryAcquire(400, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            false
        }

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            try {
                captureSession?.stopRepeating()
                captureSession?.abortCaptures()
            } catch (_: Exception) {}
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e("DirectCamera", "Error closing camera", e)
        } finally {
            if (acquired) {
                try { cameraOpenCloseLock.release() } catch (_: Exception) {}
            }
            stopBackgroundThread()
            isStreaming = false
            isTorchOn = false
            pendingSnapshotCallback = null
        }
    }

    private fun getCameraId(frontFacing: Boolean): String? {
        val targetFacing = if (frontFacing) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun chooseResolutionForQuality(choices: Array, quality: String): Size {
        return when (quality.lowercase()) {
            "low" -> {
                // Low Data-Saver: prioritize ~352x288 or 320x240 for ultra-lightweight frames (5-8 KB)
                choices.firstOrNull { it.width in 320..384 && it.height in 240..288 }
                    ?: choices.firstOrNull { it.width in 320..480 && it.height in 240..360 }
                    ?: choices.firstOrNull { it.width <= 480 }
                    ?: Size(352, 288)
            }
            "high" -> {
                choices.firstOrNull { it.width in 960..1280 && it.height in 720..960 }
                    ?: choices.firstOrNull { it.width <= 1280 }
                    ?: Size(1280, 720)
            }
            else -> {
                choices.firstOrNull { it.width in 480..640 && it.height in 360..480 }
                    ?: choices.firstOrNull { it.width <= 640 }
                    ?: Size(640, 480)
            }
        }
    }
}

