package com.example.bokehsynthesis

import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import androidx.camera.core.CameraInfo

data class LogicalCameraSensor(
    val cameraInfo: CameraInfo,
    val characteristic: CameraCharacteristics,
    val logicalId: String,
    val facing: String,
    val isFlashSupported: Boolean,
    val minimumFocusDistance: Float?,
)

data class PhysicalCameraSensor(
    val logicalId: String,
    val physicalId: String,
    val facingId: Int?,
    val facingString: String,
    val isFlashSupported: Boolean,
    val minimumFocusDistance: Float?,
    val focusCalibration: Int?,
    val sensorDiagonal: Float?,
    val cropFactor: Double?,
    val focalLength: Float?,
    val megapixels: Float?,
    val sensorOrientation: Int?,
    val previewSize: Size?,
    val isStabilizationSupported: Boolean,
    val availableApertures: FloatArray? = null,
    val sensorPhysicalWidthMm: Float? = null,
    val sensorPhysicalHeightMm: Float? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhysicalCameraSensor) return false
        return logicalId == other.logicalId &&
                physicalId == other.physicalId &&
                facingId == other.facingId &&
                facingString == other.facingString &&
                isFlashSupported == other.isFlashSupported &&
                minimumFocusDistance == other.minimumFocusDistance &&
                focusCalibration == other.focusCalibration &&
                sensorDiagonal == other.sensorDiagonal &&
                cropFactor == other.cropFactor &&
                focalLength == other.focalLength &&
                megapixels == other.megapixels &&
                sensorOrientation == other.sensorOrientation &&
                previewSize == other.previewSize &&
                isStabilizationSupported == other.isStabilizationSupported &&
                (availableApertures?.contentEquals(other.availableApertures) ?: (other.availableApertures == null)) &&
                sensorPhysicalWidthMm == other.sensorPhysicalWidthMm &&
                sensorPhysicalHeightMm == other.sensorPhysicalHeightMm
    }

    override fun hashCode(): Int {
        var result = logicalId.hashCode()
        result = 31 * result + physicalId.hashCode()
        result = 31 * result + (facingId ?: 0)
        result = 31 * result + facingString.hashCode()
        result = 31 * result + isFlashSupported.hashCode()
        result = 31 * result + (minimumFocusDistance?.hashCode() ?: 0)
        result = 31 * result + (focusCalibration ?: 0)
        result = 31 * result + (sensorDiagonal?.hashCode() ?: 0)
        result = 31 * result + (cropFactor?.hashCode() ?: 0)
        result = 31 * result + (focalLength?.hashCode() ?: 0)
        result = 31 * result + (megapixels?.hashCode() ?: 0)
        result = 31 * result + (sensorOrientation ?: 0)
        result = 31 * result + (previewSize?.hashCode() ?: 0)
        result = 31 * result + isStabilizationSupported.hashCode()
        result = 31 * result + (availableApertures?.contentHashCode() ?: 0)
        result = 31 * result + (sensorPhysicalWidthMm?.hashCode() ?: 0)
        result = 31 * result + (sensorPhysicalHeightMm?.hashCode() ?: 0)
        return result
    }
}