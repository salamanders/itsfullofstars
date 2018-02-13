package info.benjaminhill.itsfullofstars


import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.SurfaceHolder
import kotlinx.android.synthetic.main.activity_camera.*
import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.experimental.suspendCoroutine


class SimpleCameraActivity : EZPermissionActivity() {
    private val manager: CameraManager by lazy {
        getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    private val imageReader: ImageReader by lazy {
        ImageReader.newInstance(
                mode.size.width,
                mode.size.height,
                mode.imageFormat,
                CAM_CAPACITY
        )
    }

    private val backgroundHandler: Handler by lazy {
        val backgroundThread = HandlerThread("CameraBackground")
        backgroundThread.start()
        Handler(backgroundThread.looper)
    }


    // Sorted by best resolution, rear-facing
    private val mode: CameraMode by lazy {
        manager.cameraIdList.map { cameraId ->
            // TODO: return to using RAW
            CameraMode(cameraId, manager, preferRaw = false)
        }.sortedDescending().first()
    }

    private val cameraDevice: CameraDevice by lazy {
        runBlocking { aCameraOpen(mode.cameraId) }
    }

    private val cameraCaptureSession: CameraCaptureSession by lazy {
        runBlocking { aCameraCaptureSession() }
    }

    private val surfaceHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.w(TAG, "TODO Surface changed.")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            holder.removeCallback(this)
            Log.i(TAG, "SurfaceHolder.Callback removed, must be reattached through onResume")
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.i(TAG, "surfaceView returned to the foreground")
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cargo cult
        System.setProperty("kotlinx.coroutines.debug", "")

        setContentView(R.layout.activity_camera)
    }


    /**
     * We just came back from being bumped to the background
     */
    override fun onResume() {
        super.onResume()
        runWithPermissions {
            imageReader.setOnImageAvailableListener({ imageReader ->
                runBlocking {
                    Log.d(TAG, "setOnImageAvailableListener about to add image data to actor")
                    cameraClickSaver.send(CamActorImage(imageReader.acquireLatestImage()))
                }
            }, backgroundHandler)

            cameraSurfaceView.holder.addCallback(surfaceHolderCallback)

            // TODO: better callback https://github.com/googlesamples/android-Camera2Basic/blob/master/kotlinApp/Application/src/main/java/com/example/android/camera2basic/Camera2BasicFragment.kt#L229
            Log.i(TAG, "Ready to take a shot in camera Mode: $mode")
            try {
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
            } catch (e: Throwable) {
                Log.w(TAG, "Died during click: $e")
                Log.w(TAG, e.stackTrace.joinToString("\n"))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runBlocking {
            try {
                // stop accepting new clicks
                cameraDevice.close()
            } catch (e: Exception) {
                Log.w(TAG, "Exception while closing SimpleCamera cameraDevice: $e")
            }
            if (!cameraClickSaver.isClosedForSend) {
                cameraClickSaver.close()
                Log.d(TAG, "cameraClickSaver closed")
            }
        }
    }

    private fun click(): CompletableDeferred<String> {
        val saveLocation = CompletableDeferred<String>()
        val nextClick = CamActorClick(saveLocation)
        if (!cameraClickSaver.offer(nextClick)) {
            throw Exception("Unable to click camera.")
        }
        val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureRequestBuilder.addTarget(imageReader.surface)

        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        /*
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

        captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) // infinity
        captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 400) // should at least get an image
        captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 100) // ms
        captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY, 99.toByte()) // because 100 is silly
        */
        val captureRequest = captureRequestBuilder.build()
        cameraCaptureSession.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, tcr: TotalCaptureResult) {
                // Blocking? Async?
                runBlocking {
                    cameraClickSaver.send(CamActorCaptureResult(tcr))
                }
            }
        }, backgroundHandler)
        // After this, hops to imageReader.setOnImageAvailableListener
        return saveLocation
    }


    interface CamActorMessage
    class CamActorClick(val fileLocationResponse: CompletableDeferred<String>) : CamActorMessage
    class CamActorImage(val image: Image) : CamActorMessage
    class CamActorCaptureResult(val captureResult: TotalCaptureResult) : CamActorMessage

    /**
     * Fan-in Actor that kicks off when a new image number comes along, and waits for all the supporting info
     * TODO smell of wrapping everything into the same inbound channel then parsing it back out to queues
     */
    private val cameraClickSaver = actor<CamActorMessage>(capacity = CAM_CAPACITY * 3) {
        val imageCount = AtomicLong(0)
        val openClicks = mutableMapOf<Long, CompletableDeferred<String>>()
        val currentNumberChannel = Channel<Long>(CAM_CAPACITY)
        val currentImageChannel = Channel<Image>(CAM_CAPACITY)
        val currentCaptureResultChannel = Channel<TotalCaptureResult>(CAM_CAPACITY)

        fun save(number: Long, image: Image, captureResult: TotalCaptureResult): Long {
            val fileWithoutExtension = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "stars_%05d_%d".format(number, System.currentTimeMillis() / 1000)).toString()
            val file: File = when (image.format) {
                ImageFormat.JPEG -> {
                    Log.d(TAG, "saving jpg image:$number")
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val file = File("$fileWithoutExtension.jpg")
                    file.writeBytes(bytes)
                    openClicks[number]?.complete(file.canonicalPath)
                    file
                }
                ImageFormat.RAW_SENSOR -> {
                    Log.d(TAG, "saving dmg image:$number")
                    val dngCreator = DngCreator(manager.getCameraCharacteristics(mode.cameraId), captureResult)
                    val file = File("$fileWithoutExtension.dmg")
                    FileOutputStream(file).use { os ->
                        dngCreator.writeImage(os, image)
                    }
                    openClicks[number]?.complete(file.canonicalPath)
                    file
                }
                else -> {
                    TODO("image:$number Unsupported image format: ${image.format}")
                }
            }
            Log.i(TAG, "Wrote image:${file.canonicalPath} ${file.length() / 1024}k")
            image.close() // necessary when taking a few shots
            return number
        }

        for (msg in channel) {
            when (msg) {
                is CamActorClick -> {
                    val nextImageNumber = imageCount.getAndIncrement()
                    currentNumberChannel.send(nextImageNumber)
                    openClicks[nextImageNumber] = msg.fileLocationResponse
                    Log.d(TAG, "cameraClickSaver new click for image:$nextImageNumber")
                }
                is CamActorImage -> {
                    currentImageChannel.send(msg.image)
                    Log.d(TAG, "cameraClickSaver got image")
                }
                is CamActorCaptureResult -> {
                    currentCaptureResultChannel.send(msg.captureResult)
                    Log.d(TAG, "cameraClickSaver got captureResult")
                }
                else -> TODO("Unknown CamActorMessage")
            }
            Log.d(TAG, "cameraClickSaver currentNumber:${currentNumberChannel.isEmpty} ; currentImage:${currentImageChannel.isEmpty} ; currentCaptureResult:${currentCaptureResultChannel.isEmpty}")
            if (!currentNumberChannel.isEmpty && !currentImageChannel.isEmpty && !currentCaptureResultChannel.isEmpty) {
                Log.d(TAG, "cameraClickSaver has all three elements (yay!)")
                openClicks.remove(save(currentNumberChannel.receive(), currentImageChannel.receive(), currentCaptureResultChannel.receive()))
            }
        }
    }


    companion object {
        private const val CAM_CAPACITY = 5
        const val TAG = "ifos"


    }


    /**
     * Blocking camera open and setup
     * TODO: Wait until it stabilizes
     */
    @SuppressLint("MissingPermission")
    private suspend fun aCameraOpen(cameraId: String): CameraDevice = suspendCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {

            override fun onOpened(cameraDevice: CameraDevice) {
                try {
                    cont.resume(cameraDevice)
                } catch (e: Exception) {
                    Log.w(TAG, "Error when opening camera")
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

    /** Blocking session creator */
    private suspend fun aCameraCaptureSession(): CameraCaptureSession = suspendCoroutine { cont ->
        cameraDevice.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) =
                    cont.resumeWithException(IllegalArgumentException("Unable to configure cameraCaptureSession"))

            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) =
                    cont.resume(cameraCaptureSession)
        }, backgroundHandler)
    }

}



