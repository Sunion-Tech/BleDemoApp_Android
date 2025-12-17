package com.sunion.ble.demoapp.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sunion.ble.demoapp.data.api.InputAccessCodeData

@Composable
fun InputAccessCodeDialog(
    isShown: Boolean,
    title: String? = null,
    message: String? = null,
    initialIndex: String = "",
    initialCode: String = "",
    onConfirm: (InputAccessCodeData) -> Unit,
    onCancel: () -> Unit
) {
    if (!isShown) return

    var indexInput by remember { mutableStateOf(initialIndex) }
    var codeInput by remember { mutableStateOf(initialCode) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            if (!title.isNullOrEmpty()) {
                Text(text = title, style = MaterialTheme.typography.body1)
            }
        },
        text = {
            Column {
                if (!message.isNullOrEmpty()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                OutlinedTextField(
                    value = indexInput,
                    onValueChange = { indexInput = it },
                    singleLine = true,
                    placeholder = { Text("Enter index") },
                    label = { Text("index") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it },
                    singleLine = true,
                    placeholder = { Text("Enter code") },
                    label = { Text("code") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(InputAccessCodeData(indexInput, codeInput)) }) {
                Text("確定")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
        }
    )
}


@Composable
@Preview
private fun Preview() {
    InputAccessCodeDialog(
        isShown = true,
        title = "標題",
        message = "訊息",
        initialIndex = "123",
        initialCode = "1234",
        onConfirm = {},
        onCancel = {}
    )
}
