package com.sunion.ble.demoapp.ui.component

import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource

@Composable
fun EnableBluetoothDialog(
    onDismissRequest: () -> Unit,
    onConfirmButtonClick: () -> Unit,
    onDismissButtonClick: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = "Enable Bluetooth") },
        text = { Text(text = "Please turn on Bluetooth on your device.") },
        backgroundColor = Color.White,
        confirmButton = {
            TextButton(onClick = onConfirmButtonClick) {
                Text(text = "Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissButtonClick) {
                Text(text = "Cancel")
            }
        }
    )
}