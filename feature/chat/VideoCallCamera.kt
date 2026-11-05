package com.xingshi.ai.feature.chat

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class VideoCallCamera internal constructor(
    private val runtime: CameraRuntime,
    private val captureTimeoutMs: Long = CAPTURE_TIMEOUT_MS
) {

    constructor(context: Context) : this(Camera2Runtime(context))

    sealed class LensSwitch {
        object Switched : LensSwitch()

        object Unavailable : LensSwitch()

        object Busy : LensSwitch()

        data class Failed(val reason: String) : LensSwitch()
    }

    private companion object {
        const val SWITCH_TIMEOUT_MS = 8_000L

        const val CAPTURE_TIMEOUT_MS = 5_000L
    }

    private var surfaceView: SurfaceView? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null
    private var callbacks: Callbacks? = null
    private var sessionReady: ((Boolean) -> Unit)? = null

    @Volatile
    var isSwitching: Boolean = false
        private set

    @Volatile
    private var generation = 0

    private val captureLock = Any()
    private var pendingCapture: CancellableContinuation<File?>? = null

    val isOpen: Boolean get() = runtime.isOpen

    val lensFacing: Int
        get() = if (runtime.frontFacing) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

    val canSwitchLens: Boolean
        get() = runtime.cameraIdFor(CameraCharacteristics.LENS_FACING_FRONT) != null &&
            runtime.cameraIdFor(CameraCharacteristics.LENS_FACING_BACK) != null

    fun open(surfaceView: SurfaceView, onReady: (Boolean) -> Unit, onError: (String) -> Unit = {}) {
        stop()
        callbacks = Callbacks(onReady, onError)
        this.surfaceView = surfaceView
        try {
            val id = runtime.cameraIdFor(CameraCharacteristics.LENS_FACING_FRONT) ?: runtime.firstCameraId()
            if (id == null) {
                onReady(false)
                onError("没有可用摄像头")
                return
            }
            attachSurface(surfaceView)
            sessionReady = { ready -> callbacks?.onReady?.invoke(ready) }
            openCamera(id)
        } catch (e: Exception) {
            onReady(false)
            onError("摄像头打开失败：${e.message?.take(40) ?: e.javaClass.simpleName}")
        }
    }

    suspend fun switchLens(): LensSwitch = withContext(Dispatchers.Main) {
        if (!canSwitchLens) return@withContext LensSwitch.Unavailable
        if (isSwitching) return@withContext LensSwitch.Busy
        val target = runtime.cameraIdFor(
            if (runtime.frontFacing) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        ) ?: return@withContext LensSwitch.Unavailable
        val view = surfaceView ?: return@withContext LensSwitch.Failed("预览未就绪")
        isSwitching = true
        try {
            releaseCamera()
            attachSurface(view)
            val answered = AtomicBoolean(false)
            val switched = withTimeoutOrNull(SWITCH_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    sessionReady = { ready ->
                        if (answered.compareAndSet(false, true) && continuation.isActive) continuation.resume(ready)
                    }
                    openCamera(target)
                    continuation.invokeOnCancellation { sessionReady = null }
                }
            } ?: false
            if (switched) LensSwitch.Switched else LensSwitch.Failed("新镜头没有就绪")
        } catch (e: Exception) {
            LensSwitch.Failed("切换失败：${e.message?.take(40) ?: e.javaClass.simpleName}")
        } finally {
            isSwitching = false
            sessionReady = { ready -> callbacks?.onReady?.invoke(ready) }
        }
    }

    private fun attachSurface(view: SurfaceView) {
        surfaceCallback?.let { old -> runCatching { view.holder.removeCallback(old) } }
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                runtime.setPreviewSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        }
        surfaceCallback = callback
        view.holder.addCallback(callback)
        val surface = view.holder.surface
        if (surface != null && surface.isValid) runtime.setPreviewSurface(surface)
    }

    private fun openCamera(id: String) {
        runtime.openCamera(id, ++generation, runtimeListener)
    }

    private val runtimeListener = object : CameraRuntime.Listener {
        override fun onReady(token: Int, ready: Boolean) {
            if (token != generation) return
            sessionReady?.invoke(ready)
        }

        override fun onError(token: Int, message: String) {
            if (token != generation) return
            sessionReady?.invoke(false)
            if (!isSwitching) callbacks?.onError?.invoke(message)
        }
    }

    suspend fun captureFrame(): File? = withContext(Dispatchers.IO) {
        if (isSwitching) return@withContext null
        if (!runtime.isOpen) return@withContext null
        val token = generation
        withTimeoutOrNull(captureTimeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val answered = AtomicBoolean(false)
                synchronized(captureLock) { pendingCapture = continuation }
                var cancel: () -> Unit = {}
                try {
                    cancel = runtime.capture(token) { file ->
                        if (answered.compareAndSet(false, true)) {
                            finishCapture(continuation, if (token == generation) file else null)
                        }
                    }
                } catch (_: Exception) {
                    finishCapture(continuation, null)
                }
                continuation.invokeOnCancellation {
                    answered.set(true)
                    runCatching { cancel() }
                    clearPending(continuation)
                }
            }
        }
    }

    private fun finishCapture(continuation: CancellableContinuation<File?>, file: File?) {
        clearPending(continuation)
        if (continuation.isActive) continuation.resume(file)
    }

    private fun clearPending(continuation: CancellableContinuation<File?>) {
        synchronized(captureLock) {
            if (pendingCapture === continuation) pendingCapture = null
        }
    }

    private fun releaseCamera() {
        generation++
        runtime.release()
        val pending = synchronized(captureLock) { pendingCapture.also { pendingCapture = null } }
        if (pending?.isActive == true) pending.resume(null)
    }

    fun stop() {
        releaseCamera()
        val view = surfaceView
        val callback = surfaceCallback
        if (view != null && callback != null) {
            runCatching { view.holder.removeCallback(callback) }
        }
        surfaceView = null
        surfaceCallback = null
        sessionReady = null
        callbacks = null
        isSwitching = false
        runtime.shutdown()
    }

    internal data class Callbacks(val onReady: (Boolean) -> Unit, val onError: (String) -> Unit)
}

internal interface CameraRuntime {

    val isOpen: Boolean

    val frontFacing: Boolean

    fun cameraIdFor(facing: Int): String?

    fun firstCameraId(): String?

    fun openCamera(cameraId: String, token: Int, listener: Listener)

    fun setPreviewSurface(surface: Surface?)

    fun release()

    fun shutdown()

    fun capture(token: Int, onFrame: (File?) -> Unit): () -> Unit

    interface Listener {
        fun onReady(token: Int, ready: Boolean)

        fun onError(token: Int, message: String)
    }
}

internal class Camera2Runtime(private val context: Context) : CameraRuntime {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var imageSurface: Surface? = null
    private var sensorOrientation = 0
    private var sessionRequested = false
    private var captureGeneration = 0

    private var currentToken = -1
    private var listener: CameraRuntime.Listener? = null

    override var frontFacing = false
        private set

    override val isOpen: Boolean get() = device != null

    override fun cameraIdFor(facing: Int): String? = runCatching {
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        }
    }.getOrNull()

    override fun firstCameraId(): String? =
        runCatching { manager.cameraIdList.firstOrNull() }.getOrNull()

    override fun openCamera(cameraId: String, token: Int, listener: CameraRuntime.Listener) {
        this.listener = listener
        currentToken = token
        sessionRequested = false
        val handler = ensureThread()
        runCatching {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            frontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
            runCatching { imageReader?.close() }
            imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
            @Suppress("DEPRECATION")
            manager.openCamera(cameraId, stateCallback(token), handler)
        }.onFailure { error ->
            if (token == currentToken) {
                listener.onError(token, "摄像头打开失败：${error.message?.take(40) ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun ensureThread(): Handler {
        handler?.let { return it }
        val created = HandlerThread("video-call-camera").also { it.start() }
        thread = created
        return Handler(created.looper).also { handler = it }
    }

    private fun stateCallback(token: Int) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (token != currentToken) {
                runCatching { camera.close() }
                return
            }
            device = camera
            tryCreateSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            runCatching { camera.close() }
            if (token != currentToken) return
            device = null
            session = null
            listener?.onError(token, "摄像头连接已断开")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            runCatching { camera.close() }
            if (token != currentToken) return
            device = null
            session = null
            listener?.onError(token, "摄像头打开失败（$error）")
        }
    }

    override fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        if (surface != null) tryCreateSession()
    }

    private fun tryCreateSession() {
        if (sessionRequested) return
        val device = device ?: return
        val preview = previewSurface ?: return
        val image = imageReader?.surface ?: return
        val listener = listener ?: return
        val token = currentToken
        sessionRequested = true
        imageSurface = image
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(preview, image), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (token != currentToken) {
                        runCatching { session.close() }
                        return
                    }
                    this@Camera2Runtime.session = session
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build()
                        session.setRepeatingRequest(request, null, handler)
                        listener.onReady(token, true)
                    } catch (_: Exception) {
                        listener.onReady(token, false)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (token != currentToken) return
                    sessionRequested = false
                    listener.onReady(token, false)
                }
            }, handler)
        } catch (e: Exception) {
            sessionRequested = false
            listener.onError(token, "摄像头打开失败：${e.message?.take(40) ?: e.javaClass.simpleName}")
        }
    }

    override fun capture(token: Int, onFrame: (File?) -> Unit): () -> Unit {
        if (token != currentToken) {
            onFrame(null)
            return {}
        }
        val device = device
        val session = session
        val reader = imageReader
        if (device == null || session == null || reader == null) {
            onFrame(null)
            return {}
        }
        val answered = AtomicBoolean(false)
        val capture = ++captureGeneration
        reader.setOnImageAvailableListener({ available ->
            if (!answered.compareAndSet(false, true)) {
                available.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            if (token != currentToken || capture != captureGeneration) {
                available.acquireLatestImage()?.close()
                onFrame(null)
                return@setOnImageAvailableListener
            }
            val image = available.acquireLatestImage()
            val file = try {
                if (image == null) null else {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val output = File(context.applicationContext.cacheDir, "video_calls").apply { mkdirs() }
                        .resolve("frame_${System.currentTimeMillis()}.jpg")
                    output.outputStream().use { it.write(bytes) }
                    output
                }
            } catch (_: Exception) {
                null
            } finally {
                image?.close()
            }
            onFrame(file)
        }, handler)
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                imageSurface?.let { addTarget(it) }
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            }.build()
            session.capture(request, null, handler)
        } catch (_: Exception) {
            if (answered.compareAndSet(false, true)) onFrame(null)
        }
        return {
            if (capture == captureGeneration) {
                runCatching { reader.setOnImageAvailableListener(null, null) }
            }
        }
    }

    override fun release() {
        currentToken = -1
        sessionRequested = false
        listener = null
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { imageReader?.close() }
        imageReader = null
        imageSurface = null
        previewSurface = null
    }

    override fun shutdown() {
        release()
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun jpegOrientation(): Int {
        val rotation = context.getSystemService(android.view.WindowManager::class.java)
            ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        return jpegOrientationFor(frontFacing, sensorOrientation, rotation)
    }
}

internal fun jpegOrientationFor(frontFacing: Boolean, sensorOrientation: Int, rotation: Int): Int {
    val degrees = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    return if (frontFacing) {
        (sensorOrientation - degrees + 360) % 360
    } else {
        (sensorOrientation + degrees) % 360
    }
}
