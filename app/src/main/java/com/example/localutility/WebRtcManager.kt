package com.example.localutility

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.CameraVideoCapturer.CameraSwitchHandler

class WebRtcManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: WebRtcManager? = null

        fun getInstance(context: Context): WebRtcManager {
            return instance ?: synchronized(this) {
                instance ?: WebRtcManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val rootEglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    var peerConnection: PeerConnection? = null

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var currentVideoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    var onSendMessage: ((String) -> Unit)? = null
    var isFrontCamera = false
    var lastMediaProjectionIntent: Intent? = null

    init {
        initWebRtc()
    }

    private fun initWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    // --- Dedicated Camera Video Engine ---
    @Synchronized
    fun startCameraSession(frontFacing: Boolean, onBound: () -> Unit) {
        try {
            stopCapture()

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("turn:staticauth.openrelay.metered.ca:80")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayprojectsecret")
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:staticauth.openrelay.metered.ca:443")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayprojectsecret")
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:staticauth.openrelay.metered.ca:443?transport=tcp")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayprojectsecret")
                    .createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    val json = JSONObject().apply {
                        put("type", "candidate")
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("sdpMid", candidate.sdpMid)
                        put("candidate", candidate.sdp)
                    }
                    onSendMessage?.invoke(json.toString())
                }
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d("WebRTC", "IceConnectionState: $state")
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dataChannel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
            })

            // Camera2 Capturer on dedicated thread
            surfaceTextureHelper = SurfaceTextureHelper.create("CameraCaptureThread", rootEglBase.eglBaseContext)
            videoSource = peerConnectionFactory?.createVideoSource(false)

            isFrontCamera = frontFacing
            val enumerator = Camera2Enumerator(context)
            var targetDevice: String? = null
            for (name in enumerator.deviceNames) {
                if (enumerator.isFrontFacing(name) == frontFacing) {
                    targetDevice = name
                    break
                }
            }
            if (targetDevice == null) targetDevice = enumerator.deviceNames.firstOrNull()

            targetDevice?.let {
                currentVideoCapturer = enumerator.createCapturer(it, null)
                currentVideoCapturer?.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
                currentVideoCapturer?.startCapture(1280, 720, 30)
            }

            val newTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)
            newTrack?.setEnabled(true)
            videoTrack = newTrack

            peerConnection?.addTrack(newTrack, listOf("ARDAMS"))

            Log.d("WebRTC", "Camera hardware and track successfully bound!")
            onBound()
        } catch (e: Exception) {
            Log.e("WebRTC", "Error in startCameraSession", e)
        }
    }

    // --- Screen Sharing Compatibility Wrappers ---
    fun startScreenCapture(mediaProjectionIntent: Intent) {
        lastMediaProjectionIntent = mediaProjectionIntent
    }

    fun startCameraCapture(frontFacing: Boolean) {
        startCameraSession(frontFacing) {}
    }

    fun handleRemoteOffer(sdp: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.d("WebRTC", "Remote Offer set! Generating answer for camera...")
                peerConnection?.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        desc?.let {
                            peerConnection?.setLocalDescription(SimpleSdpObserver(), it)
                            val json = JSONObject().apply {
                                put("type", "answer")
                                put("sdp", it.description)
                            }
                            Log.d("WebRTC", "Dispatching Answer to Web Browser")
                            onSendMessage?.invoke(json.toString())
                        }
                    }
                    override fun onCreateFailure(error: String?) {
                        Log.e("WebRTC", "createAnswer error: $error")
                    }
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String?) {
                Log.e("WebRTC", "setRemoteDescription error: $error")
            }
        }, sessionDescription)
    }

    fun handleRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        try {
            if (sdp.isNotEmpty()) {
                val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                peerConnection?.addIceCandidate(candidate)
            }
        } catch (e: Exception) {
            Log.e("WebRTC", "addIceCandidate error", e)
        }
    }

    fun switchCamera() {
        if (currentVideoCapturer is CameraVideoCapturer) {
            (currentVideoCapturer as CameraVideoCapturer).switchCamera(object : CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) { isFrontCamera = isFront }
                override fun onCameraSwitchError(error: String?) { Log.e("WebRTC", "Switch error: $error") }
            })
        }
    }

    @Synchronized
    fun stopCapture() {
        try {
            currentVideoCapturer?.stopCapture()
            currentVideoCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            peerConnection?.close()
            peerConnection = null
            currentVideoCapturer = null
            surfaceTextureHelper = null
            videoSource = null
            videoTrack = null
        } catch (e: Exception) {
            Log.e("WebRTC", "stopCapture error", e)
        }
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}