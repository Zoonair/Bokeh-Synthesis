package com.example.bokehsynthesis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.math.RoundingMode


@Composable
fun SensorListScreen (
    logicalList: List<LogicalCameraSensor>,
    physicalList: List<PhysicalCameraSensor>,
    modifier: Modifier,
    onLogicalButtonClick: (LogicalCameraSensor) -> Unit,
    onPhysicalButtonClick: (PhysicalCameraSensor) -> Unit
) {
    if (logicalList.isEmpty()) {
        Text(text = "Searching for cameras...", modifier = modifier.padding(16.dp))
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp)

        ) {
            items(logicalList) { logicalSensor ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 4.dp),
                    shape = RoundedCornerShape(36.dp),
                    onClick = {
                        onLogicalButtonClick(logicalSensor)
                    }
                ) { Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ){

                    Text(text = "Camera ID: ${logicalSensor.logicalId}", style = MaterialTheme.typography.titleLarge)
                    Text(text = "Facing: ${logicalSensor.facing}")
                    Text(text = "Has Flash: ${logicalSensor.isFlashSupported}")
                }
                }

                physicalList.forEach { physicalSensor ->
                    if (logicalSensor.logicalId == physicalSensor.logicalId){
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
                            shape = RoundedCornerShape(32.dp),
                            onClick = {
                                onPhysicalButtonClick(physicalSensor)
                            }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {

                                val focalLength = if (physicalSensor.cropFactor == null || physicalSensor.focalLength == null) {
                                    null
                                } else {
                                    (physicalSensor.cropFactor * physicalSensor.focalLength).toBigDecimal().setScale(1, RoundingMode.HALF_UP).toFloat()
                                }

                                val focalText = if (focalLength != null) {
                                    "${(focalLength / 24).toBigDecimal().setScale(1, RoundingMode.HALF_UP).toFloat()}x"
                                } else {
                                    "Unknown"
                                }

                                Text(text = "$focalText Lens", style = MaterialTheme.typography.titleMedium)
                                Text(text = physicalSensor.facingString)
                                Text(text = "Has Flash: ${physicalSensor.isFlashSupported}")

                                val focusText = if (physicalSensor.minimumFocusDistance == null || physicalSensor.minimumFocusDistance == 0f) {
                                    "Fixed Focus"
                                } else {
                                    "%.2f meters".format(1f / physicalSensor.minimumFocusDistance)
                                }
                                Text(text = "Min Focus: $focusText")

                                val focusCalibration = when (physicalSensor.focusCalibration) {
                                    2 -> { "Calibrated" }
                                    1 -> { "Approximate" }
                                    else -> { "Uncalibrated. Please calibrate before use (in progress)." }
                                }
                                Text(text = "Focus Calibration Accuracy: $focusCalibration")
                                Text(text = "Sensor Size (Diagonal): ${physicalSensor.sensorDiagonal?.toBigDecimal()?.setScale(1, RoundingMode.HALF_UP)?.toFloat()}")


                                Text(text = "Lens Focal Length (Full-Frame Equivalent): ${
                                    focalLength ?: "Unknown"
                                }mm")

                                val megapixels = physicalSensor.megapixels?.toBigDecimal()?.setScale(1, RoundingMode.HALF_UP)?.toFloat() ?: "Unknown"

                                Text(text = "Sensor Megapixels: $megapixels mp")

                                Text(text = "Has Image Stabilization: ${physicalSensor.isStabilizationSupported}")
                            }
                        }
                    }
                }
            }
        }
    }
}