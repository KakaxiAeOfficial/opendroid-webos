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

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

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

    // --- Fresh Synchronized Video & Audio Room Engine ---
    fun prepareVideoRoom(isScreen: Boolean, frontCamera: Boolean, onBound: () -> Unit) {
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

            // 1. Hardware Video Capturer & Track
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
            videoSource = peerConnectionFactory?.createVideoSource(isScreen)

            if (isScreen && lastMediaProjectionIntent != null) {
                currentVideoCapturer = ScreenCapturerAndroid(lastMediaProjectionIntent, object : MediaProjection.Callback() {})
                currentVideoCapturer?.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
                currentVideoCapturer?.startCapture(1280, 720, 30)
            } else {
                isFrontCamera = frontCamera
                val enumerator = Camera2Enumerator(context)
                var targetDevice: String? = null
                for (name in enumerator.deviceNames) {
                    if (enumerator.isFrontFacing(name) == frontCamera) {
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
            }

            val newTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)
            newTrack?.setEnabled(true)
            videoTrack = newTrack
            peerConnection?.addTrack(newTrack, listOf("ARDAMS"))

            // 2. Hardware Microphone Audio Track (Activated & Unmuted)
            try {
                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                }
                audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
                audioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", audioSource)
                audioTrack?.setEnabled(true)
                audioTrack?.setVolume(1.0)
                peerConnection?.addTrack(audioTrack, listOf("ARDAMS"))
                Log.d("WebRTC", "Microphone audio track created, enabled and bound successfully!")
            } catch (e: Exception) {
                Log.e("WebRTC", "Audio bind error", e)
            }

            Log.d("WebRTC", "Video and Audio hardware bound in room!")
            onBound()
        } catch (e: Exception) {
            Log.e("WebRTC", "Error preparing video room", e)
        }
    }

    fun startScreenCapture(mediaProjectionIntent: Intent) {
        lastMediaProjectionIntent = mediaProjectionIntent
        prepareVideoRoom(isScreen = true, frontCamera = false) {}
    }

    fun startCameraCapture(frontFacing: Boolean) {
        prepareVideoRoom(isScreen = false, frontCamera = frontFacing) {}
    }

    fun handleRemoteOffer(sdp: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.d("WebRTC", "Remote Offer set! Generating answer with Video & Audio...")
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
            audioTrack = null
            audioSource = null
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