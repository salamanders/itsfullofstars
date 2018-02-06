package info.benjaminhill.itsfullofstars

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import kotlin.coroutines.experimental.suspendCoroutine


/**
 * Attempt at the simplest possible implementation of Android camera2
 * Caller has to await click results before closing the camera.
 * Every click responds with a CompletableDeferred<String> of the save location
 * <code>
 * SimpleCamera(this).use { c2s ->
 *   runBlocking { println(c2s.click().await()) }
 * }
 * </code>
 */
class SimpleCamera(context: Context) : AutoCloseable {

    val mode: Mode // Sorted by best resolution, rear-facing
    private val manager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val imageReader: ImageReader
    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private val cameraSetup: Deferred<Unit> // in case focusing takes a few seconds.

    interface CamActorMessage
    class CamActorClick(val fileLocationResponse: CompletableDeferred<String>) : CamActorMessage
    class CamActorImage(val image: Image) : CamActorMessage
    class CamActorCaptureResult(val captureResult: TotalCaptureResult) : CamActorMessage

    /**
     * Fan-in Actor that kicks off when a new image number comes along, and waits for all the supporting info
     * TODO bad smell of wrapping everything into the same inbound channel then parsing it back out to queues
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
                    Log.fine("saving jpg image:$number")
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val file = File("$fileWithoutExtension.jpg")
                    file.writeBytes(bytes)
                    openClicks[number]?.complete(file.canonicalPath)
                    file
                }
                ImageFormat.RAW_SENSOR -> {
                    Log.fine("saving dmg image:$number")
                    val dngCreator = DngCreator(manager.getCameraCharacteristics(cameraDevice.id), captureResult)
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
            Log.info("Wrote image:${file.canonicalPath} ${file.length() / 1024}k")
            image.close() // necessary when taking a few shots
            return number
        }

        for (msg in channel) {
            when (msg) {
                is CamActorClick -> {
                    val nextImageNumber = imageCount.getAndIncrement()
                    currentNumberChannel.send(nextImageNumber)
                    openClicks[nextImageNumber] = msg.fileLocationResponse
                    Log.fine("cameraClickSaver new click for image:$nextImageNumber")
                }
                is CamActorImage -> {
                    currentImageChannel.send(msg.image)
                    Log.fine("cameraClickSaver got image")
                }
                is CamActorCaptureResult -> {
                    currentCaptureResultChannel.send(msg.captureResult)
                    Log.fine("cameraClickSaver got captureResult")
                }
                else -> TODO("Unknown CamActorMessage")
            }
            Log.fine("cameraClickSaver currentNumber:${currentNumberChannel.isEmpty} ; currentImage:${currentImageChannel.isEmpty} ; currentCaptureResult:${currentCaptureResultChannel.isEmpty}")
            if (!currentNumberChannel.isEmpty && !currentImageChannel.isEmpty && !currentCaptureResultChannel.isEmpty) {
                Log.fine("cameraClickSaver has all three elements (yay!)")
                openClicks.remove(save(currentNumberChannel.receive(), currentImageChannel.receive(), currentCaptureResultChannel.receive()))
            }
        }
    }

    /** Run everything possible in the background */
    private val backgroundHandler: Handler by lazy {
        val backgroundThread = HandlerThread("CameraBackground")
        backgroundThread.start()
        Handler(backgroundThread.looper)
    }

    init {
        mode = manager.cameraIdList.map { cameraId ->
            // TODO: return to using RAW
            Mode(cameraId, manager, rawIsBest = false)
        }.sortedDescending().first()

        imageReader = ImageReader.newInstance(
                mode.size.width,
                mode.size.height,
                mode.imageFormat,
                CAM_CAPACITY
        )
        imageReader.setOnImageAvailableListener({ imageReader ->
            runBlocking {
                Log.fine("setOnImageAvailableListener about to add image data to actor")
                cameraClickSaver.send(CamActorImage(imageReader.acquireLatestImage()))
            }
        }, backgroundHandler)

        cameraSetup = async {
            cameraDevice = aOpen(mode.cameraId)
            Log.fine("Camera device is open.")
            cameraCaptureSession = aCameraCaptureSession()
            Log.fine("cameraCaptureSession is ready")
            delay(1000) // Wait for camera to stabilize.
            // TODO: better callback https://github.com/googlesamples/android-Camera2Basic/blob/master/kotlinApp/Application/src/main/java/com/example/android/camera2basic/Camera2BasicFragment.kt#L229
            Log.fine("Waited 1 second for stable camera.")
        }
    }

    override fun close() = runBlocking {
        try {
            // stop accepting new clicks
            cameraDevice.close()
        } catch (e: Exception) {
            Log.severe("Exception while closing SimpleCamera cameraDevice: $e")
        }
        if (!cameraClickSaver.isClosedForSend) {
            cameraClickSaver.close()
            Log.fine("cameraClickSaver closed")
        }
    }

    fun click(): CompletableDeferred<String> {
        if (!cameraSetup.isCompleted) {
            runBlocking {
                Log.warning("Camera wasn't ready yet, why so eager?")
                cameraSetup.await()
                Log.info("Ok, now things are ready, you may continue.")
            }
        }

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

    /** Blocking session creator */
    private suspend fun aCameraCaptureSession(): CameraCaptureSession = suspendCoroutine { cont ->
        cameraDevice.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) =
                    cont.resumeWithException(IllegalArgumentException("Unable to configure cameraCaptureSession"))

            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) =
                    cont.resume(cameraCaptureSession)
        }, backgroundHandler)
    }

    /**
     * Blocking camera open and setup
     * TODO: Wait until it stabilizes
     */
    @SuppressLint("MissingPermission")
    private suspend fun aOpen(cameraId: String): CameraDevice = suspendCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {

            override fun onOpened(cameraDevice: CameraDevice) = try {
                cont.resume(cameraDevice)
            } catch (e: Exception) {
                Log.severe("Error when opening camera")
            }

            override fun onDisconnected(cameraDevice: CameraDevice) = try {
                cameraDevice.close()
            } catch (e: Exception) {
                Log.severe("Error when closing disconnected camera")
            } finally {
                cont.resumeWithException(Exception("Camera onDisconnected $cameraDevice"))
            }

            override fun onError(cameraDevice: CameraDevice, error: Int) = try {
                cameraDevice.close()
            } catch (e: Exception) {
                Log.severe("Error when closing error camera")
            } finally {
                cont.resumeWithException(Exception("Camera onError $cameraDevice $error"))
            }

        }, backgroundHandler)
    }


    /** Everything necessary to pick between modes */
    data class Mode(val cameraId: String, private val manager: CameraManager, private val rawIsBest: Boolean = true) : Comparable<Mode> {
        private val characteristics = manager.getCameraCharacteristics(cameraId)
        private val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val imageFormat = if (rawIsBest && CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)) {
            ImageFormat.RAW_SENSOR
        } else {
            ImageFormat.JPEG
        }
        private val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_FRONT
        val size: Size = configMap.getOutputSizes(imageFormat).maxBy { it.width * it.height }
                ?: Size(0, 0)

        /**
         * prioritizes higher resolution, lens back over front, RAW_SENSOR over JPEG
         */
        override fun compareTo(other: Mode): Int = when {
            size != other.size -> (size.height * size.width) - (other.size.height * other.size.width)
            imageFormat != other.imageFormat -> if (imageFormat == ImageFormat.RAW_SENSOR) 1 else -1
            lensFacing != other.lensFacing -> if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) 1 else -1
            else -> cameraId.compareTo(other.cameraId)
        }

        override fun toString(): String =
                "{cameraId:$cameraId, imageFormat:$imageFormat, lensFacing:$lensFacing, resolution:$size}"
    }

    companion object {
        private val Log = Logger.getLogger(SimpleCamera::class.java.simpleName)
        private const val CAM_CAPACITY = 5
    }

}






