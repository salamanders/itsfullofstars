package info.benjaminhill.itsfullofstars.camera2

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size

/** Everything necessary to pick between modes: cameraId, sizeMax, format */
class CameraMode private constructor(val cameraId: String, manager: CameraManager, preferRaw: Boolean = true) : Comparable<CameraMode> {
    val characteristics = manager.getCameraCharacteristics(cameraId)!!
    private val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val imageFormat = if (preferRaw && CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)) {
        ImageFormat.RAW_SENSOR
    } else {
        ImageFormat.JPEG
    }
    private val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_FRONT
    val sizeMax: Size = configMap.getOutputSizes(imageFormat).maxBy { it.width * it.height }
            ?: Size(0, 0)

    fun sizeAtLeast(area: Int = 640 * 480) = configMap.getOutputSizes(imageFormat).filter {
        it.width * it.height > area
    }.minBy { it.width * it.height }
            ?: configMap.getOutputSizes(imageFormat).maxBy { it.width * it.height }!!

    /**
     * prioritizes (in order) by: higher resolution, RAW_SENSOR over JPEG, lens back over front
     */
    override fun compareTo(other: CameraMode): Int = when {
        sizeMax != other.sizeMax -> (sizeMax.height * sizeMax.width) - (other.sizeMax.height * other.sizeMax.width)
        imageFormat != other.imageFormat -> if (imageFormat == ImageFormat.RAW_SENSOR) 1 else -1
        lensFacing != other.lensFacing -> if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) 1 else -1
        else -> cameraId.compareTo(other.cameraId)
    }

    override fun toString(): String =
            "{cameraId:$cameraId, imageFormat:$imageFormat, lensFacing:$lensFacing, sizeMax:$sizeMax}"

    companion object {
        fun getBestCamera(manager: CameraManager): CameraMode = manager.cameraIdList.map { cameraId ->
            CameraMode(cameraId, manager)
        }.max()!!
    }
}