package com.sunion.ble.demoapp.ui.screen

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.rememberPermissionState
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.shouldShowRationale
import com.sunion.ble.demoapp.*
import com.sunion.ble.demoapp.R
import com.sunion.ble.demoapp.ui.component.EnableBluetoothDialog
import com.sunion.ble.demoapp.ui.component.IKeyDivider
import com.sunion.ble.demoapp.ui.component.InputAccessCodeDialog
import com.sunion.ble.demoapp.ui.component.InputDialog
import com.sunion.ble.demoapp.ui.component.LoadingScreen
import com.sunion.ble.demoapp.ui.theme.AppTheme
import com.sunion.core.ble.entity.BleDeviceFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, navController: NavController) {
    val uiState = viewModel.uiState.collectAsState().value
    val logList = viewModel.logList.collectAsState().value

    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)
    var isScanButtonClicked by remember { mutableStateOf(false) }

    LaunchedEffect(key1 = cameraPermissionState.status.isGranted) {
        if (isScanButtonClicked && cameraPermissionState.status.isGranted) {
            navController.navigate(HomeRoute.Scan.route)
        }
    }

    val fileLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) {
                result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                val fileUri = data?.data
                fileUri?.let { viewModel.handleFileSelection(it) }
            }
        }

    HomeScreen(
        uiState,
        logList,
        onExecuteClicked = { viewModel.executeTask() },
        onScanClicked = {
            isScanButtonClicked = true
            when {
                cameraPermissionState.status.isGranted -> {
                    navController.navigate(HomeRoute.Scan.route)
                }
                cameraPermissionState.status.shouldShowRationale ->
                    Unit
                else ->
                    cameraPermissionState.launchPermissionRequest()
            }
        },
        shouldShowTaskList = viewModel::shouldShowTaskList,
        onTaskItemClicked = viewModel::setTaskCode,
        onChooseFileClicked = {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            fileLauncher.launch(intent)
        },
    )

    if (uiState.isLoading)
        LoadingScreen()

    if (cameraPermissionState.status.shouldShowRationale) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                    Text(text = "OK")
                }
            },
            title = {},
            text = { Text(text = "This app needs access your camera to Scan QR Code") },
            backgroundColor = Color.White,
            dismissButton = null
        )
    }

    val bluetoothEnableLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) {
            viewModel.checkIsBluetoothEnable()
        }
    if (uiState.shouldShowBluetoothEnableDialog)
        EnableBluetoothDialog(
            onDismissRequest = viewModel::closeBluetoothEnableDialog,
            onConfirmButtonClick = {
                viewModel.closeBluetoothEnableDialog()
                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            },
            onDismissButtonClick = viewModel::closeBluetoothEnableDialog,
        )

    if(uiState.isShowInputDialog) {
        InputDialog(
            isShown = true,
            title = uiState.inputDialogTitle,
            message = uiState.inputDialogMessage,
            initialText = uiState.inputDialogInitialText,
            onConfirm = { text ->
                viewModel.setInputDialogContent(text)
                viewModel.closeInputDialog()
            },
            onCancel = { viewModel.closeInputDialog() }
        )
    }

    if(uiState.isShowInputAccessCodeDialog) {
        InputAccessCodeDialog(
            isShown = true,
            title = uiState.inputDialogTitle,
            message = uiState.inputDialogMessage,
            initialIndex = uiState.inputDialogInitialIndex,
            initialCode = uiState.inputDialogAccessCode,
            onConfirm = { inputAccessCodeData ->
                viewModel.setInputAccessCodeData(inputAccessCodeData)
                viewModel.closeAccessCodeInputDialog()
            },
            onCancel = { viewModel.closeAccessCodeInputDialog() }
        )
    }

    if (uiState.showReport) {
        // 使用 Dialog 顯示報告，點擊外面或按關閉可消失
        AlertDialog(
            onDismissRequest = { viewModel.closeReport() },
            title = { Text("自動化測試報告") },
            text = {
                // 呼叫我們之前寫好的 Report Screen
                TestReportScreen(uiState = uiState)
            },
            confirmButton = {
                TextButton(onClick = { viewModel.closeReport() }) {
                    Text("關閉")
                }
            }
        )
    }
}

@Composable
fun HomeScreen(
    state: UiState,
    logList: MutableList<String>,
    onExecuteClicked: () -> Unit,
    onScanClicked: () -> Unit,
    shouldShowTaskList: (Boolean) -> Unit,
    onTaskItemClicked: (BleDeviceFeature.TaskCode) -> Unit,
    onChooseFileClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dropDownWidth by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        TopAppBar(
            navigationIcon = null,
            title = {
                Text(text = "Demo APP", style = AppTheme.typography.title, color = AppTheme.colors.primary)
            },
            backgroundColor = AppTheme.colors.background,
            actions = {
                Button(
                    onClick = {
                        onChooseFileClicked()
                    }
                ) {
                    Text("選擇OTA檔案")
                }
                Spacer(modifier = Modifier.width(12.dp))
                if (state.isConnectedWithLock) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_baseline_bluetooth_connected_24),
                        contentDescription = null,
                        tint = AppTheme.colors.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                else {
                    Icon(
                        painter = painterResource(id = if (state.isBlueToothAvailable) R.drawable.ic_baseline_bluetooth_24 else R.drawable.ic_baseline_bluetooth_disabled_24),
                        contentDescription = null,
                        tint = AppTheme.colors.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    Modifier
                        .size(32.dp)
                        .clickable(onClick = { onScanClicked.invoke() }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_scan_qrcode),
                        contentDescription = "scan",
                        modifier = Modifier.size(24.dp),
                        tint = AppTheme.colors.primary,
                    )
                }
                Spacer(modifier = Modifier.width(20.dp))
            }
        )
        IKeyDivider()
        val listState = rememberLazyListState()
        LazyColumn(state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .weight(1f)) {
            items(logList) {
                Text(text = it, style = AppTheme.typography.body)
            }
            CoroutineScope(Dispatchers.Main).launch {
                if (logList.isNotEmpty()) {
                    listState.scrollToItem(logList.size - 1)
                }
            }
        }

        IKeyDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {

            Box(modifier = modifier
                .padding(12.dp)
                .weight(1f)) {
                val typeText =
                    if (state.taskList.none { it.first == state.taskCode }) "Please select task" else {
                        state.taskList.findLast { it.first == state.taskCode }
                            .let { it!!.second }
                    }
                Text(
                    text = typeText,
                    style = AppTheme.typography.body,
                    color = AppTheme.colors.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged {
                            dropDownWidth = it.width
                        },
                )
                Spacer(
                    modifier = Modifier
                        .height(26.dp)
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .clickable { shouldShowTaskList.invoke(true) },
                )

                Icon(
                    painter = painterResource(id = R.drawable.ic_drop_indicator),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .align(Alignment.CenterEnd)
                        .requiredSize(24.dp)
                )
                DropdownMenu(
                    expanded = state.shouldShowTaskList,
                    onDismissRequest = { shouldShowTaskList.invoke(false) },
                    modifier = Modifier
                        .height(if(state.taskList.size > 11) 635.dp else (state.taskList.size * 56).dp)
                        .width(with(LocalDensity.current) { dropDownWidth.toDp() })
                        .background(Color.White)
                ) {
                    state.taskList.forEach {
                        DropdownMenuItem(onClick = { onTaskItemClicked(it.first) }) {
                            Text(
                                text = it.second,
                                style = AppTheme.typography.body,
                                color = AppTheme.colors.primary,
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { onExecuteClicked.invoke() },
                enabled = state.btnEnabled,
                colors = ButtonDefaults.buttonColors(backgroundColor = AppTheme.colors.primary, disabledBackgroundColor = AppTheme.colors.buttonDisable)
            ) {
                Text("Execute", style = AppTheme.typography.button)
            }
        }
    }
}

@Composable
fun TestReportScreen(uiState: UiState) {
    // 計算成功與失敗的數量
    val total = uiState.testResults.size
    val successCount = uiState.testResults.values.count { it == TestStatus.SUCCESS }
    val failedCount = uiState.testResults.values.count { it == TestStatus.FAILED }
    val unsupportedCount = uiState.testResults.values.count { it == TestStatus.NOT_SUPPORTED }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 總結區域
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("總計: $total", style = MaterialTheme.typography.caption)
            Text("成功: $successCount", color = Color(0xFF4CAF50), style = MaterialTheme.typography.caption)
            Text("失敗: $failedCount", color = Color.Red, style = MaterialTheme.typography.caption)
            Text("不支援: $unsupportedCount", color = Color.Gray, style = MaterialTheme.typography.caption)
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // 測試細項列表
        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) { // 限制最大高度
            items(uiState.testTaskList) { task ->
                val status = uiState.testResults[task.first] ?: TestStatus.IDLE

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth()
                ) {
                    val (icon, color, label) = when (status) {
                        TestStatus.SUCCESS -> Triple(Icons.Default.CheckCircle, Color(0xFF4CAF50), "成功")
                        TestStatus.FAILED -> Triple(Icons.Default.Close, Color.Red, "失敗")
                        TestStatus.RUNNING -> Triple(Icons.Default.Refresh, Color.Blue, "執行中")
                        TestStatus.NOT_SUPPORTED -> Triple(Icons.Default.Close, Color.Gray, "未實作測試")
                        TestStatus.IDLE -> Triple(Icons.Default.Refresh, Color.LightGray, "待執行")
                    }

                    Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = task.second,
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.weight(1f),
                        color = if (status == TestStatus.NOT_SUPPORTED) Color.Gray else Color.Unspecified
                    )

                    Text(
                        text = label,
                        style = MaterialTheme.typography.caption,
                        color = color
                    )
                }
            }
        }
    }
}