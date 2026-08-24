package com.example.bokehsynthesis

import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.view.WindowManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.lifecycleScope
import com.example.bokehsynthesis.ui.theme.BokehSynthesisTheme
import kotlinx.coroutines.launch


@ExperimentalCamera2Interop
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.fetchLogicalCameraSensors()
        } else {
            Toast.makeText(this, "Camera access is required.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BokehSynthesisTheme {
                val logicalSensors by viewModel.availableSensors.collectAsState()
                val physicalSensors by viewModel.selectedPhysicalCamera.collectAsState()
                val isCameraActive by viewModel.isCameraActive.collectAsState()
                val surfaceRequest by viewModel.surfaceRequest.collectAsState()
                val message by viewModel.message.collectAsState()
                val isSweeping by viewModel.isSweeping.collectAsState()
                val currentPhysicalCamera = viewModel.currentPhysicalCamera
                val view = LocalView.current
                val context = LocalContext.current
                val haptics = LocalHapticFeedback.current

                if (message != null) {
                    Toast.makeText(
                        this@MainActivity,
                        message,
                        Toast.LENGTH_SHORT
                    ).show()
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    viewModel.clearMessage()
                }

                DisposableEffect(isCameraActive) {
                    if (isCameraActive) {
                        val activity = context as? MainActivity
                        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        onDispose {
                            activity?.requestedOrientation = originalOrientation
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    } else {
                        onDispose { }
                    }
                }

                Scaffold { paddingValues ->

                    if (isCameraActive) {
                        val currentRotation = view.display?.rotation ?: Surface.ROTATION_0

                        LaunchedEffect(currentRotation) {
                            viewModel.updateTargetRotation(currentRotation)
                        }

                        BackHandler {
                            viewModel.stopCamera()
                        }
                        surfaceRequest?.let { request ->
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(paddingValues),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CameraPreview(
                                    surfaceRequest = request,
                                    modifier = Modifier.fillMaxWidth(),
                                    currentPhysicalCamera = currentPhysicalCamera,
                                    isSweeping = isSweeping,
                                    onTapToFocus = { point ->
                                        viewModel.tapToFocus(point)
                                    },
                                    onShutterPress =  { _ ->
                                        lifecycleScope.launch {
                                            viewModel.onShutterPress(haptics)
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        SensorListScreen(
                            logicalList = logicalSensors,
                            physicalList = physicalSensors,
                            modifier = Modifier.padding(paddingValues),
                            onLogicalButtonClick = { logicalSensor ->
                                lifecycleScope.launch {
                                    viewModel.fetchPhysicalCameraSensors(logicalSensor)
                                }
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onPhysicalButtonClick = { physicalSensor ->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.initCamera(
                                    this@MainActivity,
                                    physicalSensor,
                                    Surface.ROTATION_0
                                )
                            }
                        )
                    }
                }
            }
        }

        permissionLauncher.launch(Manifest.permission.CAMERA)
    }
}