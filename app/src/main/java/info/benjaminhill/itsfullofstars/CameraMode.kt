package info.benjaminhill.itsfullofstars

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size

/** Everything necessary to pick between modes: cameraId, size, format */
class CameraMode(val cameraId: String, manager: CameraManager, preferRaw: Boolean = true) : Comparable<CameraMode> {
    private val characteristics = manager.getCameraCharacteristics(cameraId)
    private val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val imageFormat = if (preferRaw && CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)) {
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
    override fun compareTo(other: CameraMode): Int = when {
        size != other.size -> (size.height * size.width) - (other.size.height * other.size.width)
        imageFormat != other.imageFormat -> if (imageFormat == ImageFormat.RAW_SENSOR) 1 else -1
        lensFacing != other.lensFacing -> if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) 1 else -1
        else -> cameraId.compareTo(other.cameraId)
    }

    override fun toString(): String =
            "{cameraId:$cameraId, imageFormat:$imageFormat, lensFacing:$lensFacing, resolution:$size}"
}