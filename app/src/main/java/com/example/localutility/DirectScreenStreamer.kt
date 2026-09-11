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
    @Volatile
    var isStreaming = false
    var onFrameCaptured: ((String) -> Unit)? = null

    private var lastFrameTime = 0L

    fun hasProjection(): Boolean {
        return mediaProjection != null || lastProjectionIntent != null
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("DirectScreenThread").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e("DirectScreen", "Error stopping thread", e)
        }
    }

    @SuppressLint("WrongConstant")
    fun startStreaming(intent: Intent, onFrame: (String) -> Unit) {
        lastProjectionIntent = intent
        onFrameCaptured = onFrame
        startStreaming(onFrame)
    }

    // Clean Pause: Keeps MediaProjection session alive so PC can reopen window anytime
    fun pauseStreaming() {
        isStreaming = false
        Log.d("DirectScreen", "Screen streaming paused (session preserved)")
    }

    // Instant Resume: Resumes sending frames with 0 delay and zero token recreation
    fun resumeStreaming() {
        if (mediaProjection != null && virtualDisplay != null) {
            isStreaming = true
            Log.d("DirectScreen", "Screen streaming resumed instantly!")
        } else {
            onFrameCaptured?.let { startStreaming(it) }
        }
    }

    @SuppressLint("WrongConstant")
    fun startStreaming(onFrame: (String) -> Unit) {
        val intent = lastProjectionIntent ?: return
        onFrameCaptured = onFrame
        startBackgroundThread()

        try {
            if (mediaProjection == null) {
                val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(android.app.Activity.RESULT_OK, intent.clone() as Intent)

                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d("DirectScreen", "MediaProjection stopped by system")
                        stopStreaming()
                    }
                }, backgroundHandler)
            }

            if (virtualDisplay == null || imageReader == null) {
                val metrics = context.resources.displayMetrics
                val targetWidth = 540
                val targetHeight = (540f * (metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat())).toInt()
                val density = metrics.densityDpi

                imageReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        try {
                            // If paused, drop frame immediately with 0 CPU load
                            if (!isStreaming) {
                                image.close()
                                return@setOnImageAvailableListener
                            }

                            val now = System.currentTimeMillis()
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
                            Log.e("DirectScreen", "Frame error", e)
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
            }

            isStreaming = true
            Log.d("DirectScreen", "Screen streaming session running cleanly!")

        } catch (e: Exception) {
            Log.e("DirectScreen", "Failed to start direct screen stream", e)
            isStreaming = false
        }
    }

    fun stopStreaming() {
        try {
            isStreaming = false
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
        }
    }
}