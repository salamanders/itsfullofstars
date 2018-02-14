package info.benjaminhill.itsfullofstars.camera2

import android.graphics.ImageFormat
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.actor
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

sealed class CamActorMessage
class CamActorClick(val fileLocationResponse: CompletableDeferred<String>) : CamActorMessage()
class CamActorImage(val image: Image) : CamActorMessage()
class CamActorCaptureResult(val captureResult: TotalCaptureResult) : CamActorMessage()

/**
 * Fan-in Actor that kicks off when a new image number comes along, and waits for all the supporting info
 */
class CameraClickSaver private constructor(private val mode: CameraMode) {

    private val imageCount = AtomicLong(0)
    private val openClicks = mutableMapOf<Long, CompletableDeferred<String>>()
    private val currentNumberChannel = Channel<Long>(SimpleCameraActivity.CAM_CAPACITY)
    private val currentImageChannel = Channel<Image>(SimpleCameraActivity.CAM_CAPACITY)
    private val currentCaptureResultChannel = Channel<TotalCaptureResult>(SimpleCameraActivity.CAM_CAPACITY)

    suspend fun onReceive(msg: CamActorMessage) {
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
        }
        Log.d(TAG, "cameraClickSaver currentNumber:${currentNumberChannel.isEmpty} ; currentImage:${currentImageChannel.isEmpty} ; currentCaptureResult:${currentCaptureResultChannel.isEmpty}")
        if (!currentNumberChannel.isEmpty && !currentImageChannel.isEmpty && !currentCaptureResultChannel.isEmpty) {
            Log.d(TAG, "cameraClickSaver has all three elements (yay!)")
            openClicks.remove(save(currentNumberChannel.receive(), currentImageChannel.receive(), currentCaptureResultChannel.receive()))
        }
    }

    private fun save(number: Long, image: Image, captureResult: TotalCaptureResult): Long {
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
                val dngCreator = DngCreator(mode.characteristics, captureResult)
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

    companion object {
        fun build(mode: CameraMode): SendChannel<CamActorMessage> = actor(capacity = SimpleCameraActivity.CAM_CAPACITY * 3) {
            with(CameraClickSaver(mode)) {
                for (msg in channel) onReceive(msg)
            }
        }

        const val TAG = "ifos.ccs"
    }
}
