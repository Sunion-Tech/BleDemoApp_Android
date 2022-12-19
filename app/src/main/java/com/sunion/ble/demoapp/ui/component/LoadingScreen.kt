package com.sunion.ble.demoapp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.sunion.ble.demoapp.ui.theme.AppTheme
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoadingScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = AppTheme.colors.primary)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LoadingScreenDialog(msg: String) {
    Dialog(onDismissRequest = { }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LoadingScreen()
        Text(
            text = msg,
            fontSize = 16.sp,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 600.dp),
            textAlign = TextAlign.Center,
            style = TextStyle(color = AppTheme.colors.primary),
        )
    }
}