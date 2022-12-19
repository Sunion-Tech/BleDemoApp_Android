package com.sunion.ble.demoapp.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun CameraDeniedContent(
    shouldShowRationale: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.fillMaxWidth()) {
        val message = "This app needs access your camera to Scan QR Code"

        Text(message)
        Button(onClick = onClick) {
            Text("OK")
        }
    }
}
