package com.example.localutility

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

class DirectScreenStreamer private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: DirectScreenStreamer? = null

        fun getInstance(context: Context): DirectScreenStreamer {
            return instance ?: synchronized(this) {
                instance ?: DirectScreenStreamer(context.applicationContext).also { instance = it }
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    var lastProjectionIntent: Intent? = null
    var isStreaming = false
    var onFrameCaptured: ((String) -> Unit)? = null

    private var lastFrameTime = 0L

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("DirectScreenThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e("DirectScreen", "Error stopping thread", e)
        }
    }

    @SuppressLint("WrongConstant")
    fun startStreaming(intent: Intent, onFrame: (String) -> Unit) {
        lastProjectionIntent = intent
        startStreaming(onFrame)
    }

    @SuppressLint("WrongConstant")
    fun startStreaming(onFrame: (String) -> Unit) {
        val intent = lastProjectionIntent ?: return
        stopStreaming()

        isStreaming = true
        onFrameCaptured = onFrame
        startBackgroundThread()

        try {
            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(android.app.Activity.RESULT_OK, intent.clone() as Intent)

            // Android 14 Mandatory Callback Registration
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d("DirectScreen", "MediaProjection stopped by system")
                    stopStreaming()
                }
            }, backgroundHandler)

            val metrics = context.resources.displayMetrics
            // Proportional 540p streaming resolution
            val targetWidth = 540
            val targetHeight = (540f * (metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat())).toInt()
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val now = System.currentTimeMillis()
                        // 12 FPS throttle for smooth 5G data flow
                        if (now - lastFrameTime >= 80) {
                            lastFrameTime = now

                            val plane = image.planes[0]
                            val buffer = plane.buffer
                            val pixelStride = plane.pixelStride
                            val rowStride = plane.rowStride
                            val rowPadding = rowStride - pixelStride * targetWidth

                            val bitmap = Bitmap.createBitmap(
                                targetWidth + rowPadding / pixelStride,
                                targetHeight,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)

                            val finalBitmap = if (rowPadding == 0) {
                                bitmap
                            } else {
                                val cropped = Bitmap.createBitmap(bitmap, 0, 0, targetWidth, targetHeight)
                                bitmap.recycle()
                                cropped
                            }

                            val out = ByteArrayOutputStream()
                            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 45, out)
                            finalBitmap.recycle()

                            val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            onFrameCaptured?.invoke(base64)
                        }
                    } catch (e: Exception) {
                        Log.e("DirectScreen", "Frame processing error", e)
                    } finally {
                        image.close()
                    }
                }, backgroundHandler)
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "OpenDroidScreenMirror",
                targetWidth,
                targetHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                backgroundHandler
            )

            Log.d("DirectScreen", "Direct screen streaming session active at ${targetWidth}x$targetHeight!")

        } catch (e: Exception) {
            Log.e("DirectScreen", "Failed to start direct screen stream", e)
        }
    }

    fun stopStreaming() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e("DirectScreen", "Error stopping screen capture", e)
        } finally {
            stopBackgroundThread()
            isStreaming = false
        }
    }
}