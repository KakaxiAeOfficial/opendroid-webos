package com.example.localutility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.util.Size
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

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val cameraOpenCloseLock = Semaphore(1)
    var isFrontCamera = false
    var isTorchOn = false
    var isStreaming = false
    var onFrameCaptured: ((String) -> Unit)? = null

    private var lastFrameTime = 0L

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("DirectCameraThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e("DirectCamera", "Error stopping thread", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startStreaming(frontFacing: Boolean, onFrame: (String) -> Unit) {
        stopStreaming()
        isStreaming = true
        isFrontCamera = frontFacing
        onFrameCaptured = onFrame
        startBackgroundThread()

        try {
            val cameraId = getCameraId(frontFacing) ?: return
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            // Choose optimal streaming resolution (~640x480 or closest)
            val outputSize = chooseOptimalSize(map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray())

            imageReader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val now = System.currentTimeMillis()
                        // Throttle to ~12 FPS to maintain smooth stream without overloading network
                        if (now - lastFrameTime >= 80) {
                            lastFrameTime = now
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            onFrameCaptured?.invoke(base64)
                        }
                    } catch (e: Exception) {
                        Log.e("DirectCamera", "Frame processing error", e)
                    } finally {
                        image.close()
                    }
                }, backgroundHandler)
            }

            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    Log.e("DirectCamera", "Camera error code: $error")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e("DirectCamera", "Failed to start camera streaming", e)
        }
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val readerSurface = imageReader?.surface ?: return

        try {
            val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                // Hardware JPEG compression quality at 50% (~12 KB per frame)
                set(CaptureRequest.JPEG_QUALITY, 50.toByte())
                if (!isFrontCamera && isTorchOn) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
            }

            device.createCaptureSession(listOf(readerSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                        Log.d("DirectCamera", "Live frame stream session active!")
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

    fun switchCamera() {
        if (!isStreaming) return
        startStreaming(!isFrontCamera, onFrameCaptured ?: {})
    }

    fun toggleTorch() {
        if (isFrontCamera) return
        isTorchOn = !isTorchOn
        createCaptureSession()
    }

    fun stopStreaming() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e("DirectCamera", "Error closing camera", e)
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
            isStreaming = false
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

    private fun chooseOptimalSize(choices: Array<Size>): Size {
        return choices.firstOrNull { it.width in 480..640 && it.height in 360..480 }
            ?: choices.firstOrNull { it.width <= 640 }
            ?: choices.firstOrNull()
            ?: Size(640, 480)
    }
}