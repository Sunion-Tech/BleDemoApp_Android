package com.sunion.ble.demoapp.ui.component

import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sunion.ble.demoapp.ui.theme.AppTheme

@Composable
fun IKeyDivider(modifier: Modifier = Modifier) {
    Divider(
        modifier = modifier,
        color = AppTheme.colors.divider
    )
}

@Composable
fun IKeyPrimaryDivider(modifier: Modifier = Modifier) {
    Divider(
        modifier = modifier,
        color = AppTheme.colors.primary
    )
}