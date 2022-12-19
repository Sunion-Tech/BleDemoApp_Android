package com.sunion.ble.demoapp.ui.screen

import android.Manifest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.sunion.ble.demoapp.R
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.sunion.ble.demoapp.HomeRoute
import com.sunion.ble.demoapp.HomeViewModel
import com.sunion.ble.demoapp.UiEvent
import com.sunion.ble.demoapp.ui.component.BarcodeScan
import com.sunion.ble.demoapp.ui.component.CameraDeniedContent

@Composable
fun ScanQRCodeScreen(viewModel: HomeViewModel, navController: NavController) {
    LaunchedEffect(key1 = Unit) {
        viewModel.uiEvent.collect {
            when (it) {
                is UiEvent.Complete -> navController.navigate(HomeRoute.Home.route)
            }
        }
    }

    val uiState = viewModel.uiState.collectAsState().value
    ScanQRCodeScreen(
        onNaviUpClick = { navController.navigate(HomeRoute.Home.route) },
        onScanResult = viewModel::setQRCodeContent,
        modifier = Modifier
    )

    if (uiState.message.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(onClick = {
                    viewModel.closeMessageDialog()
                    navController.navigate(HomeRoute.Home.route)
                }) {
                    Text(text = "OK")
                }
            },
            title = {},
            text = { Text(text = uiState.message) },
            backgroundColor = Color.White,
            dismissButton = null
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ScanQRCodeScreen(
    onNaviUpClick: () -> Unit,
    onScanResult: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    when (cameraPermissionState.status) {
        PermissionStatus.Granted -> {
            BarcodeScanContent(
                onScanResult = onScanResult,
                modifier = modifier
            )
        }
        is PermissionStatus.Denied -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
            ) {
                CameraDeniedContent(
                    shouldShowRationale = cameraPermissionState.status.shouldShowRationale,
                    onClick = cameraPermissionState::launchPermissionRequest,
                    modifier = Modifier
                )
            }
        }
    }

    TopAppBar(
        navigationIcon = {
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(onClick = onNaviUpClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_back),
                    contentDescription = "back",
                    modifier = Modifier.size(24.dp),
                    tint = Color.White
                )
            }
        },
        title = {},
        backgroundColor = Color.Transparent,
        modifier = modifier
    )
}

@Composable
fun BarcodeScanContent(
    onScanResult: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        BarcodeScan(
            onScanResult = onScanResult,
            modifier = Modifier.fillMaxSize()
        )
    }
}