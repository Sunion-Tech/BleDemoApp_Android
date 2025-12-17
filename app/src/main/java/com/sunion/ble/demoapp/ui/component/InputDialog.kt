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

@Composable
fun InputDialog(
    isShown: Boolean,
    title: String? = null,
    message: String? = null,
    initialText: String = "",
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    if (!isShown) return

    var input by remember { mutableStateOf(initialText) }

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
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Enter text") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input) }) {
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
    InputDialog(
        isShown = true,
        title = "標題",
        message = "訊息",
        initialText = "初始文字",
        onConfirm = {},
        onCancel = {}
    )
}
