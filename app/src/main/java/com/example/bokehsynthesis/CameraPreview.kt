package com.example.bokehsynthesis

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceRequest
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds


@Composable
fun CameraPreview(
    surfaceRequest: SurfaceRequest,
    modifier: Modifier = Modifier,
    currentPhysicalCamera: PhysicalCameraSensor?,
    isSweeping: Boolean,
    onTapToFocus: (Offset) -> Unit = {},
    onShutterPress: (Unit) -> Unit = {}
) {
    val resolution = surfaceRequest.resolution
    val aspectRatio = remember(resolution) {
        resolution.height.toFloat() / resolution.width.toFloat()
    }
    val coordinateTransformer = remember { MutableCoordinateTransformer() }

    var autofocusTrigger by remember { mutableIntStateOf(0) }
    var autofocusPosition by remember { mutableStateOf(Offset.Unspecified) }
    var isFocusCircleVisible by remember { mutableStateOf(false) }

    val shutterColor = if (isSweeping) {
        Color.Gray
    } else {
        MaterialTheme.colorScheme.secondary
    }

    if (isFocusCircleVisible) {
        LaunchedEffect(autofocusTrigger) {
            delay(1000.milliseconds)
            isFocusCircleVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .pointerInput(coordinateTransformer) {
                detectTapGestures { tapOffset ->
                    if (currentPhysicalCamera?.minimumFocusDistance != null && currentPhysicalCamera.minimumFocusDistance != 0f) {
                        with(coordinateTransformer) {
                            onTapToFocus(tapOffset.transform())
                        }
                        autofocusPosition = tapOffset
                        isFocusCircleVisible = true
                        autofocusTrigger++
                    }
                }
            }
    ) {
        CameraXViewfinder(
            surfaceRequest = surfaceRequest,
            coordinateTransformer = coordinateTransformer,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        AnimatedVisibility(
            visible = isFocusCircleVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.offset {
                if (autofocusPosition.isSpecified) {
                    val indicatorSizePx = 48.dp.roundToPx()
                    IntOffset(
                        x = (autofocusPosition.x.toInt() - indicatorSizePx / 2),
                        y = (autofocusPosition.y.toInt() - indicatorSizePx / 2)
                    )
                } else {
                    IntOffset.Zero
                }
            }
        ) {
            Spacer(
                Modifier
                    .border(
                        2.dp,
                        Color.White,
                        CircleShape
                    )
                    .size(48.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(28.dp))

    Button(
        modifier =  Modifier.size(78.dp),
        onClick = { onShutterPress(Unit) },
        colors = ButtonDefaults.buttonColors(containerColor = shutterColor)
    ) { }
}