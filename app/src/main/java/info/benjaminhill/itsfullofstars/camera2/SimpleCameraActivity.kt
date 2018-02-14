package info.benjaminhill.itsfullofstars.camera2


import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.SurfaceHolder
import info.benjaminhill.itsfullofstars.EZPermissionActivity
import info.benjaminhill.itsfullofstars.R
import kotlinx.android.synthetic.main.activity_camera.*
import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.experimental.suspendCoroutine

class SimpleCameraActivity : EZPermissionActivity() {
    private lateinit var manager: CameraManager
    private lateinit var mode: CameraMode
    private lateinit var imageReader: ImageReader
    private lateinit var cameraClickSaver: SendChannel<CamActorMessage>

    private val backgroundHandler: Handler = {
        Log.i(TAG, "Creating: backgroundHandler")
        val backgroundThread = HandlerThread("CameraBackground")
        backgroundThread.start()
        Handler(backgroundThread.looper)
    }()

    private val cameraDevice: CameraDevice by lazy {
        Log.i(TAG, "Creating: cameraDevice")
        runBlocking { aCameraOpen(mode.cameraId) }
    }

    private val cameraCaptureSession: CameraCaptureSession by lazy {
        Log.i(TAG, "Creating: cameraCaptureSession")
        runBlocking { aCameraCaptureSession() }
    }

    private val surfaceHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.i(TAG, "surfaceChanged w:$width,h:$height")
            Log.i(TAG, "cameraSurfaceView w:${cameraSurfaceView.width}, h:${cameraSurfaceView.height}")
            Log.i(TAG, "holder.surfaceFrame w:${holder.surfaceFrame.width()}, h:${holder.surfaceFrame.height()}")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            holder.removeCallback(this)
            Log.i(TAG, "SurfaceHolder.Callback removed, must be reattached through onResume")
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.i(TAG, "SurfaceHolder.Callback surfaceCreated")
            val previewSize = mode.sizeAtLeast(cameraSurfaceView.width * cameraSurfaceView.height)
            Log.i(TAG, "Found preview size $previewSize")
            holder.setFixedSize(previewSize.width, previewSize.height)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "SimpleCameraActivity.onCreate")
        System.setProperty("kotlinx.coroutines.debug", "") // Cargo cult
        setContentView(R.layout.activity_camera)

        // System services not available to Activities before onCreate()
        manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        mode = CameraMode.getBestCamera(manager)
        imageReader = {
            Log.i(TAG, "Creating: imageReader")
            val imageReader = ImageReader.newInstance(
                    mode.sizeMax.width,
                    mode.sizeMax.height,
                    mode.imageFormat,
                    CAM_CAPACITY
            )
            imageReader.setOnImageAvailableListener({ imageAvailableImageReader ->
                runBlocking {
                    Log.d(TAG, "setOnImageAvailableListener about to add image data to actor")
                    cameraClickSaver.send(CamActorImage(imageAvailableImageReader.acquireLatestImage()))
                }
            }, backgroundHandler)
            imageReader
        }()

        cameraClickSaver = CameraClickSaver.build(mode)

        cameraSurfaceView.holder.addCallback(surfaceHolderCallback)
    }

    private val isDemoRun = AtomicBoolean(false)
    private suspend fun runDemoOnlyOnce() {
        if (!isDemoRun.getAndSet(true)) {
            Log.i(TAG, "runDemoOnlyOnce")
            val clicks = mutableSetOf<Deferred<String>>()
            for (i in 0..3) {
                clicks.add(click())
            }
            Log.w(TAG, "CLICKED A BUNCH OF TIMES")

            Log.w(TAG, "WAITING FOR FIRST 2")
            runBlocking {
                clicks.take(2).forEach {
                    Log.w(TAG, it.await())
                }
            }
            Log.w(TAG, "SAVED FIRST 2")
            clicks.add(click())

            runBlocking {
                Log.w(TAG, "SAVING THE REST")
                clicks.forEach {
                    it.await()
                }
            }

            Log.w(TAG, "THROWAWAY CLICK")
            clicks.add(click())
        }
    }


    /**
     * We just started OR came back from being bumped to the background
     */
    override fun onResume() {
        super.onResume()
        Log.i(TAG, "SimpleCameraActivity.onResume")
        // @see EZPermissionActivity so we don't have to worry
        runAfterAllPermissionsGranted {
            // TODO: better callback https://github.com/googlesamples/android-Camera2Basic/blob/master/kotlinApp/Application/src/main/java/com/example/android/camera2basic/Camera2BasicFragment.kt#L229
            Log.i(TAG, "Permissions granted, ready to take a shot in camera Mode: $mode")
            launch {
                runDemoOnlyOnce()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "SimpleCameraActivity.onDestroy")
        cameraDevice.close()
        if (!cameraClickSaver.isClosedForSend) {
            cameraClickSaver.close()
            Log.d(TAG, "cameraClickSaver closed")
        }
    }

    /** @return deferred path to saved image */
    private fun click(): CompletableDeferred<String> {
        Log.d(TAG, "SimpleCameraActivity.click()")
        val saveLocation = CompletableDeferred<String>()
        val nextClick = CamActorClick(saveLocation)
        if (!cameraClickSaver.offer(nextClick)) {
            throw Exception("Unable to click camera.")
        }
        val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureRequestBuilder.addTarget(imageReader.surface)

        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) // infinity
        captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY, 99.toByte()) // because 100 is silly

        /*
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 400) // should at least get an image
        captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 100) // ms
        */
        val captureRequest = captureRequestBuilder.build()
        cameraCaptureSession.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, tcr: TotalCaptureResult) {
                if (!cameraClickSaver.offer(CamActorCaptureResult(tcr))) {
                    Log.e(TAG, "Unable to offer CamActorCaptureResult to cameraClickSaver")
                }
            }
        }, backgroundHandler)
        // After this, hops to imageReader.setOnImageAvailableListener
        return saveLocation
    }


    /**
     * Blocking camera open and setup
     * TODO: Wait until it stabilizes
     */
    @SuppressLint("MissingPermission")
    private suspend fun aCameraOpen(cameraId: String): CameraDevice = suspendCoroutine { cont ->
        Log.w(TAG, "SimpleCameraActivity.aCameraOpen")
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {

            override fun onOpened(cameraDevice: CameraDevice) {
                try {
                    cont.resume(cameraDevice)
                } catch (e: Exception) {
                    Log.w(TAG, "Error when opening camera")
                    cont.resumeWithException(e)
                }
            }

            override fun onDisconnected(cameraDevice: CameraDevice) {
                try {
                    cameraDevice.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error when closing disconnected camera")
                } finally {
                    cont.resumeWithException(Exception("Camera onDisconnected $cameraDevice"))
                }
            }

            override fun onError(cameraDevice: CameraDevice, error: Int) {
                try {
                    cameraDevice.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error when closing error camera")
                } finally {
                    cont.resumeWithException(Exception("Camera onError $cameraDevice $error"))
                }
            }

        }, backgroundHandler)
    }

    /** Blocking session creator spins up the repeating preview request */
    private suspend fun aCameraCaptureSession(): CameraCaptureSession = suspendCoroutine { cont ->
        Log.w(TAG, "SimpleCameraActivity.aCameraCaptureSession")
        cameraDevice.createCaptureSession(listOf(imageReader.surface, cameraSurfaceView.holder.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) =
                    cont.resumeWithException(IllegalArgumentException("Unable to configure cameraCaptureSession"))

            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                // Build and run a repeating request for preview footage
                val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                requestBuilder.addTarget(cameraSurfaceView.holder.surface)
                val previewRequest = requestBuilder.build()
                cameraCaptureSession.setRepeatingRequest(previewRequest, null, null)

                cont.resume(cameraCaptureSession)
            }

        }, backgroundHandler)
    }

    companion object {
        const val CAM_CAPACITY = 5
        const val TAG = "ifos"
    }
}



