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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import com.sunion.ble.demoapp.ui.component.LoadingScreen
import com.sunion.ble.demoapp.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, navController: NavController) {
    val context = LocalContext.current
    val uiState = viewModel.uiState.collectAsState().value
    val logList = viewModel.logList.collectAsState().value
    val taskList: Array<Pair<Int, String>> = arrayOf(
        TaskCode.Connect to "Connect",
        TaskCode.GetDeviceStatus to "Get DeviceStatus",
        TaskCode.ToggleLockState to "Toggle lock state",
        TaskCode.AutoUnlockToggleLockState to "Auto unlock toggle lock state",
        TaskCode.GetLockTime to "Get lock time",
        TaskCode.SetLockTime to "Set lock time",
        TaskCode.GetLockTimeZone to "Get lock timeZone",
        TaskCode.SetLockTimeZone to "Set lock timeZone",
        TaskCode.GetLockName to "Get lock name",
        TaskCode.SetLockName to "Set lock name",
        TaskCode.IsAdminCodeExists to "Is Admin code exists",
        TaskCode.CreateAdminCode to "Create Admin code",
        TaskCode.UpdateAdminCode to "Update Admin code",
        TaskCode.DetermineLockDirection to "Determine lock direction",
        TaskCode.GetLockConfig to "Get lock config",
        TaskCode.ToggleKeyPressBeep to "Toggle key press beep",
        TaskCode.ToggleVacationMode to "Toggle vacation mode",
        TaskCode.ToggleGuidingCode to "Toggle guiding code",
        TaskCode.ToggleAutoLock to "Toggle auto lock",
        TaskCode.SetLockLocation to "Set lock location",
        TaskCode.ToggleSecurityBolt to "Toggle security bolt",
        TaskCode.AutoUnlockToggleSecurityBolt to "Auto unlock toggle security bolt",
        TaskCode.ToggleVirtualCode to "Toggle virtual code",
        TaskCode.ToggleTwoFA to "Toggle twoFA",
        TaskCode.ToggleOperatingSound to "Toggle operating sound",
        TaskCode.ToggleShowFastTrackMode to "Toggle show fast track mode",
        TaskCode.ToggleSabbathMode to "Toggle Sabbath mode",
        TaskCode.QueryTokenArray to "Query token array",
        TaskCode.QueryToken to "Query token",
        TaskCode.AddOneTimeToken to "Add one time token",
        TaskCode.EditToken to "Edit token",
        TaskCode.DeleteToken to "Delete token",
        TaskCode.GetAccessCodeArray to "Get access code array",
        TaskCode.QueryAccessCode to "Query access code",
        TaskCode.AddAccessCode to "Add access code",
        TaskCode.EditAccessCode to "Edit access code",
        TaskCode.DeleteAccessCode to "Delete access code",
        TaskCode.GetAccessCardArray to "Get access card array",
        TaskCode.QueryAccessCard to "Query access card",
        TaskCode.AddAccessCard to "Add access card",
        TaskCode.EditAccessCard to "Edit access card",
        TaskCode.DeleteAccessCard to "Delete access card",
        TaskCode.DeviceGetAccessCard to "Device get access card",
        TaskCode.GetFingerprintArray to "Get fingerprint array",
        TaskCode.QueryFingerprint to "Query fingerprint",
        TaskCode.AddFingerprint to "Add fingerprint",
        TaskCode.EditFingerprint to "Edit fingerprint",
        TaskCode.DeleteFingerprint to "Delete fingerprint",
        TaskCode.DeviceGetFingerprint to "Device get fingerprint",
        TaskCode.GetFaceArray to "Get face array",
        TaskCode.QueryFace to "Query face",
        TaskCode.AddFace to "Add face",
        TaskCode.EditFace to "Edit face",
        TaskCode.DeleteFace to "Delete face",
        TaskCode.DeviceGetFace to "Device get face",
        TaskCode.AddCredentialFingerVein to "Add credential finger vein",
        TaskCode.EditCredentialFingerVein to "Edit credential finger vein",
        TaskCode.DeleteCredentialFingerVein to "Delete credential finger vein",
        TaskCode.DeviceGetCredentialFingerVein to "Device get credential finger vein",
        TaskCode.GetEventQuantity to "Get event quantity",
        TaskCode.GetEvent to "Get event",
        TaskCode.GetEventByAddress to "Get event by address",
        TaskCode.DeleteEvent to "Delete event",
        TaskCode.GetLockSupportedUnlockTypes to "Get lock supported unlock types",
        TaskCode.QueryUserAbility to "Query user ability",
        TaskCode.QueryUserCount to "Query user count",
        TaskCode.IsMatterDevice to "Is matter device",
        TaskCode.GetUserArray to "Get user array",
        TaskCode.GetUser to "Get user",
        TaskCode.AddUser to "Add user",
        TaskCode.EditUser to "Edit user",
        TaskCode.DeleteUser to "Delete user",
        TaskCode.GetCredentialArray to "Get credential array",
        TaskCode.GetCredentialByCredential to "Get credential by credential",
        TaskCode.GetCredentialByUser to "Get credential by user",
        TaskCode.GetCredentialHash to "Get credential hash",
        TaskCode.GetUserHash to "Get user hash",
        TaskCode.HasUnsyncedData to "Has unsynced data",
        TaskCode.GetUnsyncedData to "Get unsynced data",
        TaskCode.SetCredentialUnsyncedData to "Set credential unsynced data",
        TaskCode.SetUserUnsyncedData to "Set user unsynced data",
        TaskCode.SetLogUnsyncedData to "Set log unsynced data",
        TaskCode.SetTokenUnsyncedData to "Set token unsynced data",
        TaskCode.SetSettingUnsyncedData to "Set setting unsynced data",
        TaskCode.SetAllDataSynced to "Set all data synced",
        TaskCode.ScanWifi to "Scan wifi",
        TaskCode.ConnectToWifi to "Connect to wifi",
        TaskCode.SetOTAUpdate to "OTA update",
        TaskCode.SetOTACancel to "OTA cancel",
        TaskCode.TogglePlugState to "Toggle plug state",
        TaskCode.GetFwVersion to "Get firmware version",
        TaskCode.GetRfVersion to "Get RF version",
        TaskCode.GetMcuVersion to "Get MCU version",
        TaskCode.FactoryReset to "Factory reset",
        TaskCode.FactoryResetNoAdmin to "Factory reset no admin",
        TaskCode.Restart to "Restart",
        TaskCode.Disconnect to "Disconnect"
    )

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
        taskList,
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
}

@Composable
fun HomeScreen(
    state: UiState,
    logList: MutableList<String>,
    taskList: Array<Pair<Int, String>>,
    onExecuteClicked: () -> Unit,
    onScanClicked: () -> Unit,
    shouldShowTaskList: (Boolean) -> Unit,
    onTaskItemClicked: (Int) -> Unit,
    onChooseFileClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dropDownWidth by remember { mutableStateOf(0) }

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
                        painter = painterResource(id = com.sunion.ble.demoapp.R.drawable.ic_scan_qrcode),
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
                if (logList.size>0) {
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
                    if (taskList.none { it.first == state.taskCode }) "Please select task" else {
                        taskList.findLast { it.first == state.taskCode }
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
                        .height(500.dp)
                        .width(with(LocalDensity.current) { dropDownWidth.toDp() })
                        .background(Color.White)
                ) {
                    taskList.forEach {
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