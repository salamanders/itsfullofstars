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
import kotlinx.coroutines.experimental.CoroutineName
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.util.logging.Logger
import kotlin.coroutines.experimental.suspendCoroutine


/**
 * Simplest possible implementation of Android camera2
 */
class SimpleCamera(context: Context) : AutoCloseable {

    val mode: Mode
    private val manager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val imageReader: ImageReader
    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private val totalCaptureResultChannel = Channel<TotalCaptureResult>(2)
    private val latestImageChannel = Channel<Image>(2)

    private val backgroundHandler: Handler by lazy {
        val backgroundThread = HandlerThread("CameraBackground")
        backgroundThread.start()
        Handler(backgroundThread.looper)
    }

    init {

        mode = manager.cameraIdList.map { cameraId ->
            Mode(cameraId, manager)
        }.sortedDescending().first()

        imageReader = ImageReader.newInstance(
                mode.size.width,
                mode.size.height,
                mode.imageFormat,
                2
        )
        imageReader.setOnImageAvailableListener({ imageReader ->
            launch {
                latestImageChannel.send(imageReader.acquireLatestImage())
            }
        }, backgroundHandler)

        runBlocking(CoroutineName("setupDeviceAndSession")) {
            cameraDevice = aOpen(mode.cameraId)
            Log.info("Camera device is open.")
            cameraCaptureSession = cameraCaptureSession()
            Log.info("cameraCaptureSession is ready")
        }

        launch(CoroutineName("channelToImageSavingLoop")) {
            Log.info("Launching image saving loop")
            while (!latestImageChannel.isClosedForSend || !latestImageChannel.isEmpty) {
                Log.info("Waiting for image channels...")
                save(latestImageChannel.receive(), totalCaptureResultChannel.receive())
            }
            Log.warning("Channels closed and empty")
        }

        Log.info("init finished")
    }

    override fun close() {
        cameraDevice.close()
        totalCaptureResultChannel.close()
        latestImageChannel.close()
    }

    fun click() {
        Log.info("click - start")

        val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureRequestBuilder.addTarget(imageReader.surface)

        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)

        captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
        captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
        captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 100) // ms
        captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY, 99.toByte())
        // After this, hops to imageReader.setOnImageAvailableListener

        val captureRequest = captureRequestBuilder.build()
        Log.info("Built captureRequest: ${captureRequest.describeContents()}")

        cameraCaptureSession.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (!totalCaptureResultChannel.offer(result)) {
                    Log.warning("Problem while sending captureResult to the channel")
                }
            }
        }, backgroundHandler)
        Log.info("click - end")
    }

    /**
     * Generic image saver (lossy or lossless)
     * will use lastCaptureResult for raw images
     */
    private fun save(image: Image, captureResult: CaptureResult) = when (image.format) {
        ImageFormat.JPEG -> {
            Log.info("saving jpg")
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "stars_${System.currentTimeMillis() / 1000}.jpg")
            file.writeBytes(bytes)
            Log.info("Wrote ${file.canonicalPath} ${file.length() / (1024 * 1024)}m")

        }
        ImageFormat.RAW_SENSOR -> {
            val dngCreator = DngCreator(manager.getCameraCharacteristics(cameraDevice.id), captureResult)
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "stars_${System.currentTimeMillis()}.dmg")
            FileOutputStream(file).use { os ->
                dngCreator.writeImage(os, image)
            }
            Log.info("Wrote ${file.canonicalPath} ${file.length() / (1024 * 1024)}m")
        }
        else -> {
            TODO("Unsupported image format: ${image.format}")
        }
    }


    /** Blocking session creator */
    private suspend fun cameraCaptureSession(): CameraCaptureSession = suspendCoroutine { cont ->
        cameraDevice.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) =
                    cont.resumeWithException(IllegalArgumentException("Unable to configure cameraCaptureSession"))

            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) =
                    cont.resume(cameraCaptureSession)
        }, backgroundHandler)
    }


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
    data class Mode(val cameraId: String, private val manager: CameraManager) : Comparable<Mode> {
        private val characteristics = manager.getCameraCharacteristics(cameraId)
        private val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val imageFormat = if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)) {
            ImageFormat.RAW_SENSOR
        } else {
            ImageFormat.JPEG
        }
        private val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_FRONT
        val size: Size = configMap.getOutputSizes(imageFormat).maxBy { it.width * it.height }
                ?: Size(0, 0)

        /**
         * prioritizes higher resolution, lens back over front, JPEG over RAW_SENSOR
         * TODO: return to using RAW
         */
        override fun compareTo(other: Mode): Int = when {
            size != other.size -> (size.height * size.width) - (other.size.height * other.size.width)
            imageFormat != other.imageFormat -> if (imageFormat == ImageFormat.JPEG) 1 else -1
            lensFacing != other.lensFacing -> if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) 1 else -1
            else -> cameraId.compareTo(other.cameraId)
        }

        override fun toString(): String =
                "{cameraId:$cameraId, imageFormat:$imageFormat, lensFacing:$lensFacing, resolution:$size}"
    }

    companion object {
        private val Log = Logger.getLogger(SimpleCamera::class.java.simpleName)
    }

}






