package com.example.trackpulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.example.trackpulse.data.permissions.PermissionHelper
import com.example.trackpulse.ui.navigation.TrackPulseApp
import com.example.trackpulse.ui.theme.TrackPulseTheme
import com.example.trackpulse.ui.viewmodel.TrackPulseViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: TrackPulseViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            viewModel.onPermissionsGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        permissionLauncher.launch(PermissionHelper.requiredCorePermissions())

        setContent {
            TrackPulseTheme {
                TrackPulseApp(viewModel = viewModel)
            }
        }
    }
}
