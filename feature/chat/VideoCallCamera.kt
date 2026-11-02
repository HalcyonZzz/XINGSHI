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
import android.view.SurfaceView
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 通话内摄像头：Camera2 预览 + 只在话段结束时取一帧 JPEG。
 * 不录制整段视频、不截屏；预览与会话资源在挂断/离开时释放。
 */
class VideoCallCamera(private val context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var imageSurface: Surface? = null
    private var cameraId: String? = null
    private var sensorOrientation = 0
    private var frontFacing = false

    val isOpen: Boolean get() = device != null

    fun open(surfaceView: SurfaceView, onReady: (Boolean) -> Unit, onError: (String) -> Unit = {}) {
        callbacks = Callbacks(onReady, onError)
        val thread = HandlerThread("video-call-camera").also { it.start() }
        this.thread = thread
        val handler = Handler(thread.looper)
        this.handler = handler
        try {
            cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull()
            val id = cameraId ?: run { onReady(false); onError("没有可用摄像头"); return }
            val characteristics = manager.getCameraCharacteristics(id)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            frontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
            val reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
            imageReader = reader
            surfaceView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                    previewSurface = holder.surface
                    // camera-first 时 onOpened 已先到，Surface 到达后必须补建会话
                    tryCreateSession()
                }

                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = Unit
            })
            @Suppress("DEPRECATION")
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    tryCreateSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                    session = null
                    onReady(false)
                    onError("摄像头连接已断开")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    session = null
                    onReady(false)
                    onError("摄像头打开失败（$error）")
                }
            }, handler)
        } catch (e: Exception) {
            onReady(false)
            onError("摄像头打开失败：${e.message?.take(40) ?: e.javaClass.simpleName}")
        }
    }

    private var sessionRequested = false

    private fun tryCreateSession() {
        if (sessionRequested) return
        val device = device ?: return
        val preview = previewSurface ?: return
        val image = imageReader?.surface ?: return
        sessionRequested = true
        createSession { ready ->
            if (!ready) sessionRequested = false
            callbacks?.onReady?.invoke(ready)
        }
    }

    private var callbacks: Callbacks? = null

    internal data class Callbacks(val onReady: (Boolean) -> Unit, val onError: (String) -> Unit)

    private fun createSession(onReady: (Boolean) -> Unit) {
        val device = device ?: return
        val preview = previewSurface ?: return
        val image = imageReader?.surface ?: return
        imageSurface = image
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(preview, image), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    this@VideoCallCamera.session = session
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build()
                        session.setRepeatingRequest(request, null, handler)
                        onReady(true)
                    } catch (_: Exception) {
                        onReady(false)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onReady(false)
                }
            }, handler)
        } catch (e: Exception) {
            onReady(false)
            callbacks?.onError?.invoke("摄像头打开失败：${e.message?.take(40) ?: e.javaClass.simpleName}")
        }
    }

    /** 立即抓取当前相机画面；失败返回 null，不拿旧帧代替。 */
    suspend fun captureFrame(): File? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val device = device
            val session = session
            val reader = imageReader
            if (device == null || session == null || reader == null) {
                if (continuation.isActive) continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val answered = java.util.concurrent.atomic.AtomicBoolean(false)
            reader.setOnImageAvailableListener({ available ->
                if (!answered.compareAndSet(false, true)) {
                    available.acquireLatestImage()?.close()
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
                if (continuation.isActive) continuation.resume(file)
            }, handler)
            try {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    imageSurface?.let { addTarget(it) }
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                }.build()
                session.capture(request, null, handler)
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
            continuation.invokeOnCancellation {
                answered.set(true)
                reader.setOnImageAvailableListener(null, null)
            }
        }
    }

    /** 与预览一致的前置镜像/方向处理：JPEG 方向由传感器与显示旋转推导。 */
    private fun jpegOrientation(): Int {
        val rotation = context.getSystemService(android.view.WindowManager::class.java)
            ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
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

    fun stop() {
        sessionRequested = false
        callbacks = null
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { imageReader?.close() }
        imageReader = null
        previewSurface = null
        imageSurface = null
        thread?.quitSafely()
        thread = null
        handler = null
    }
}
