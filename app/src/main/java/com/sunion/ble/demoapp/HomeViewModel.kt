package com.sunion.ble.demoapp

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import com.sunion.ble.demoapp.data.api.DeviceApiRepository
import com.sunion.ble.demoapp.data.api.InputAccessCodeData
import com.sunion.core.ble.ReactiveStatefulConnection
import com.sunion.core.ble.accessByteArrayToString
import com.sunion.core.ble.entity.*
import com.sunion.core.ble.exception.ConnectionTokenException
import com.sunion.core.ble.exception.LockStatusException
import com.sunion.core.ble.exception.NotConnectedException
import com.sunion.core.ble.isDeviceUuid
import com.sunion.core.ble.isNotSupport
import com.sunion.core.ble.isNotSupport2Byte
import com.sunion.core.ble.isSupport
import com.sunion.core.ble.isSupport2Byte
import com.sunion.core.ble.toHexString
import com.sunion.core.ble.toSupportPhoneticLanguageList
import com.sunion.core.ble.unless
import com.sunion.core.ble.usecase.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import kotlin.text.isNotBlank
import kotlin.text.toIntOrNull
import kotlin.text.toLongOrNull

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val isBlueToothEnabledUseCase: IsBlueToothEnabledUseCase,
    private val bluetoothAvailableStateCollector: BluetoothAvailableStateCollector,
    private val statefulConnection: ReactiveStatefulConnection,
    private val lockQRCodeUseCase: LockQRCodeUseCase,
    private val incomingSunionBleNotificationUseCase: IncomingSunionBleNotificationUseCase,
    private val deviceStatusD6UseCase: DeviceStatusD6UseCase,
    private val lockTimeUseCase: LockTimeUseCase,
    private val adminCodeUseCase: AdminCodeUseCase,
    private val lockNameUseCase: LockNameUseCase,
    private val lockDirectionUseCase: LockDirectionUseCase,
    private val lockConfigD4UseCase: LockConfigD4UseCase,
    private val lockUtilityUseCase: LockUtilityUseCase,
    private val lockTokenUseCase: LockTokenUseCase,
    private val lockAccessCodeUseCase: LockAccessCodeUseCase,
    private val lockAccessUseCase: LockAccessUseCase,
    private val lockEventLogUseCase: LockEventLogUseCase,
    private val deviceStatusA2UseCase: DeviceStatusA2UseCase,
    private val lockConfigA0UseCase: LockConfigA0UseCase,
    private val lockWifiUseCase: LockWifiUseCase,
    private val lockOTAUseCase: LockOTAUseCase,
    private val plugConfigUseCase: PlugConfigUseCase,
    private val deviceStatus82UseCase: DeviceStatus82UseCase,
    private val lockConfig80UseCase: LockConfig80UseCase,
    private val lockUserUseCase: LockUserUseCase,
    private val lockCredentialUseCase: LockCredentialUseCase,
    private val lockDataUseCase: LockDataUseCase,
    private val lockBleUserUseCase: LockBleUserUseCase,
    private val deviceApiRepository: DeviceApiRepository,
    private val bleScanUseCase: BleScanUseCase,
    private val application: Application,
): ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent

    private val _logList: MutableStateFlow<MutableList<String>> = MutableStateFlow(mutableListOf())
    val logList: MutableStateFlow<MutableList<String>>
        get() = _logList

    private var _lockConnectionInfo: LockConnectionInfo? = null
    val lockConnectionInfo: LockConnectionInfo?
        get() = _lockConnectionInfo

    private var _bleConnectionStateListener: Job? = null

    private var _bleSunionBleNotificationListener: Job? = null

    private var _currentDeviceStatus: SunionBleNotification = DeviceStatus.UNKNOWN

    private var _currentSunionBleNotification: SunionBleNotification = SunionBleNotification.UNKNOWN

    private var isCollectingConnectToWifiState = false
    private var isConnectingToWifi = false
    private var isWifiConnected = false

    private var scanWifiJob: Job? = null
    private var collectWifiListJob: Job? = null
    private var connectToWifiJob: Job? = null

    private var currentFileUri: Uri? = null
    private var fileSize: Int = 0
    private val currentTarget = 0 // 0:mcu 1:rf
    private val ivString = "AD4EF44433DD78A9B4955B9D635894DC"
    private val signatureV005 = "304502210087756DECC4D3F9F524AE6FE15C6B14064C4F444281407302924E1672341660D402204DFAF4DE3185923BA9C271969125A730FD6575311AAB7E624711FD4DD7FFC1FD"
    private val hash256V005 = "20620A6E461BD1B41A1564493064FFE0B2FA427B80A9472454CB1D3CD022B554"

    private var adminCode = "0000"

    private var currentQrCodeContent: String? = null
    private var currentProductionGetResponse: ProductionGetResponse? = null
    private var currentConnectMacAddress: String? = null
    private var userAbility: BleV3Lock.UserAbility? = null
    private var lastCodeCardIndex = 0
    private var lastCodeIndex = 0
    private var lastCardIndex = 0
    private var lastFingerprintIndex = 0
    private var lastFaceIndex = 0
    private val _currentAccessA9Data = MutableStateFlow(Access.A9(-1,-1,-1,false, byteArrayOf()))
    private val currentAccessA9Data: StateFlow<Access.A9> = _currentAccessA9Data
    private val _currentCredential97Data = MutableStateFlow(Credential.NinetySeven(-1,-1,-1,0, byteArrayOf()))
    private val currentCredential97Data: StateFlow<Credential.NinetySeven> = _currentCredential97Data
    private var lastTokenIndex = 0
    private var lastEventLogIndex = 0
    private var lastUserIndex = 0
    private var lastCredentialIndex = 0
    private var isCheckDeviceStatus = false
    private var isCheckLockConfig = false
    private var isCheckUnLockType = false
    private val identity: String = ""
    private var model: String = ""
    private var isBackgroundOTA = false

    @Volatile
    var connectBleLockCompletion: CompletableDeferred<Unit>? = null

    fun init() {
        Timber.d("init")
        collectBluetoothAvailableState()
        showLog(msg = "Please scan QR code to get lock connection information.", isClear = true)
    }

    private fun collectBluetoothAvailableState() {
        bluetoothAvailableStateCollector
            .collectState()
            .flowOn(Dispatchers.IO)
            .onEach { state ->
                if (state == BluetoothAvailableState.LOCATION_PERMISSION_NOT_GRANTED) {
                    _uiState.update { it.copy(isBlueToothAvailable = isBlueToothEnabledUseCase()) }
                }
                _uiState.update {
                    it.copy(
                        isBlueToothAvailable = !(state == BluetoothAvailableState.BLUETOOTH_NOT_AVAILABLE
                                || state == BluetoothAvailableState.BLUETOOTH_NOT_ENABLED)
                    )
                }
            }
            .catch { Timber.e(it) }
            .launchIn(viewModelScope)
    }

    fun checkIsBluetoothEnable(): Boolean {
        val isBlueToothEnabled = isBlueToothEnabledUseCase()
        _uiState.update {
            it.copy(
                shouldShowBluetoothEnableDialog = !isBlueToothEnabled,
                isBlueToothAvailable = isBlueToothEnabled
            )
        }
        return isBlueToothEnabled
    }

    fun shouldShowTaskList(isShow: Boolean) {
        _uiState.update { it.copy(shouldShowTaskList = isShow) }
    }

    fun setTaskCode(code: BleDeviceFeature.TaskCode) {
        _uiState.update { it.copy(taskCode = code, shouldShowTaskList = false) }
    }

    fun executeTask() {
        if (!checkIsBluetoothEnable()) return
        val functionName = "executeTask"
        viewModelScope.launch {
            _uiState.update { it.copy(btnEnabled = false) }
            runWithLoading(functionName) {
                when (uiState.value.taskCode) {
                    BleDeviceFeature.TaskCode.Connect -> {
                        if (currentQrCodeContent != null && currentQrCodeContent!!.isDeviceUuid() && currentConnectMacAddress == null) {
                            startBleScan(
                                currentQrCodeContent!!,
                                currentProductionGetResponse!!,
                                true
                            )
                        } else {
                            connect()
                        }
                    }
                    BleDeviceFeature.TaskCode.AutoTest -> {
                        executeAutoTest()
                    }
                    // Get lock time
                    BleDeviceFeature.TaskCode.GetLockTime -> {
                        getLockTime()
                    }
                    // Set lock time
                    BleDeviceFeature.TaskCode.SetLockTime -> {
                        showInputDialog()
                    }
                    // Get lock time zone
                    BleDeviceFeature.TaskCode.GetLockTimeZone -> {
                        getLockTimeZone()
                    }
                    // Set lock timezone
                    BleDeviceFeature.TaskCode.SetLockTimeZone -> {
                        showInputDialog()
                    }
                    // Get lock name
                    BleDeviceFeature.TaskCode.GetLockName -> {
                        getLockName()
                    }
                    // Set lock name
                    BleDeviceFeature.TaskCode.SetLockName -> {
                        showInputDialog()
                    }
                    // Get DeviceStatus
                    BleDeviceFeature.TaskCode.GetDeviceStatus -> {
                        getDeviceStatus()
                    }
                    // Get lock config
                    BleDeviceFeature.TaskCode.GetLockConfig -> {
                        getLockConfig()
                    }
                    // Toggle lock state
                    BleDeviceFeature.TaskCode.ToggleLockState -> {
                        toggleLockState()
                    }
                    // Auto unlock toggle lock state
                    BleDeviceFeature.TaskCode.AutoUnlockToggleLockState -> {
                        autoUnlockToggleLockState()
                    }
                    // Toggle security bolt
                    BleDeviceFeature.TaskCode.ToggleSecurityBolt -> {
                        toggleSecurityBolt()
                    }
                    // Toggle key press beep
                    BleDeviceFeature.TaskCode.ToggleKeyPressBeep -> {
                        toggleKeyPressBeep()
                    }
                    // Toggle vacation mode
                    BleDeviceFeature.TaskCode.ToggleVacationMode -> {
                        toggleVacationMode()
                    }
                    // Toggle guiding code
                    BleDeviceFeature.TaskCode.ToggleGuidingCode -> {
                        toggleGuidingCode()
                    }
                    // Toggle auto lock
                    BleDeviceFeature.TaskCode.ToggleAutoLock -> {
                        showInputDialog()
                    }
                    // Set lock location
                    BleDeviceFeature.TaskCode.SetLockLocation -> {
                        setLockLocation()
                    }
                    // Toggle virtual code
                    BleDeviceFeature.TaskCode.ToggleVirtualCode -> {
                        toggleVirtualCode()
                    }
                    // Toggle twoFA
                    BleDeviceFeature.TaskCode.ToggleTwoFA -> {
                        toggleTwoFA()
                    }
                    // Toggle operating sound
                    BleDeviceFeature.TaskCode.ToggleOperatingSound -> {
                        toggleOperatingSound()
                    }
                    // Toggle show fast track mode
                    BleDeviceFeature.TaskCode.ToggleShowFastTrackMode -> {
                        toggleShowFastTrackMode()
                    }
                    // Toggle sabbath mode
                    BleDeviceFeature.TaskCode.ToggleSabbathMode -> {
                        toggleSabbathMode()
                    }
                    // Toggle phonetic language
                    BleDeviceFeature.TaskCode.TogglePhoneticLanguage -> {
                        togglePhoneticLanguage()
                    }
                    // Determine lock direction
                    BleDeviceFeature.TaskCode.DetermineLockDirection -> {
                        determineLockDirection()
                    }
                    // Is admin code exists
                    BleDeviceFeature.TaskCode.IsAdminCodeExists -> {
                        isAdminCodeExists()
                    }
                    // Create admin code
                    BleDeviceFeature.TaskCode.CreateAdminCode -> {
                        showInputDialog()
                    }
                    // Update admin code
                    BleDeviceFeature.TaskCode.UpdateAdminCode -> {
                        showInputDialog()
                    }
                    // Get admin code position
                    BleDeviceFeature.TaskCode.GetAdminCodePosition -> {
                        getAdminCodePosition()
                    }
                    // Plug on
                    BleDeviceFeature.TaskCode.TogglePlugState -> {
                        togglePlugState()
                    }
                    // Get firmware version
                    BleDeviceFeature.TaskCode.GetFwVersion -> {
                        getFirmwareVersion()
                    }
                    BleDeviceFeature.TaskCode.GetFwModel -> {
                        getFirmwareModel()
                    }
                    // Get RF version
                    BleDeviceFeature.TaskCode.GetRfVersion -> {
                        getRfVersion()
                    }
                    // Get MCU version
                    BleDeviceFeature.TaskCode.GetMcuVersion -> {
                        getMcuVersion()
                    }
                    // Factory reset
                    BleDeviceFeature.TaskCode.FactoryReset -> {
                        factoryReset()
                    }
                    // Factory reset
                    BleDeviceFeature.TaskCode.FactoryResetNoAdmin -> {
                        factoryResetNoAdmin()
                    }
                    // Restart
                    BleDeviceFeature.TaskCode.Restart -> {
                        restart()
                    }
                    // Get TokenArray
                    BleDeviceFeature.TaskCode.GetTokenArray -> {
                        getTokenArray()
                    }
                    // Get Token
                    BleDeviceFeature.TaskCode.GetToken -> {
                        getToken()
                    }
                    // Add OneTime Token
                    BleDeviceFeature.TaskCode.AddOneTimeToken -> {
                        addOneTimeToken()
                    }
                    // Edit Token
                    BleDeviceFeature.TaskCode.EditToken -> {
                        showInputDialog()
                    }
                    // Delete Token
                    BleDeviceFeature.TaskCode.DeleteToken -> {
                        showInputDialog()
                    }
                    // Get Access Code Array
                    BleDeviceFeature.TaskCode.GetAccessCodeArray -> {
                        getAccessCodeArray()
                    }
                    // Get Access Code
                    BleDeviceFeature.TaskCode.GetAccessCode -> {
                        getAccessCode()
                    }
                    // Add Access Code
                    BleDeviceFeature.TaskCode.AddAccessCode -> {
                        showAccessCodeInputDialog()
                    }
                    // Edit Access Code
                    BleDeviceFeature.TaskCode.EditAccessCode -> {
                        showAccessCodeInputDialog()
                    }
                    // Delete Access Code
                    BleDeviceFeature.TaskCode.DeleteAccessCode -> {
                        showInputDialog()
                    }
                    // Get Access Card Array
                    BleDeviceFeature.TaskCode.GetAccessCardArray -> {
                        getAccessCardArray()
                    }
                    // Get Access Card
                    BleDeviceFeature.TaskCode.GetAccessCard -> {
                        getAccessCard()
                    }
                    // Add Access Card
                    BleDeviceFeature.TaskCode.AddAccessCard -> {
                        showInputDialog()
                    }
                    // Edit Access Card
                    BleDeviceFeature.TaskCode.EditAccessCard -> {
                        showInputDialog()
                    }
                    // Delete Access Card
                    BleDeviceFeature.TaskCode.DeleteAccessCard -> {
                        showInputDialog()
                    }
                    // Device Get Access Card
                    BleDeviceFeature.TaskCode.DeviceGetAccessCard -> {
                        deviceGetAccessCard()
                    }
                    // Get Fingerprint Array
                    BleDeviceFeature.TaskCode.GetFingerprintArray -> {
                        getFingerprintArray()
                    }
                    // Get Fingerprint
                    BleDeviceFeature.TaskCode.GetFingerprint -> {
                        getFingerprint()
                    }
                    // Add Fingerprint
                    BleDeviceFeature.TaskCode.AddFingerprint -> {
                        showInputDialog()
                    }
                    // Edit Fingerprint
                    BleDeviceFeature.TaskCode.EditFingerprint -> {
                        showInputDialog()
                    }
                    // Delete Fingerprint
                    BleDeviceFeature.TaskCode.DeleteFingerprint -> {
                        showInputDialog()
                    }
                    // Device Get Fingerprint
                    BleDeviceFeature.TaskCode.DeviceGetFingerprint -> {
                        deviceGetFingerprint()
                    }
                    // Get FaceArray
                    BleDeviceFeature.TaskCode.GetFaceArray -> {
                        getFaceArray()
                    }
                    // Get Face
                    BleDeviceFeature.TaskCode.GetFace -> {
                        getFace()
                    }
                    // Add Face
                    BleDeviceFeature.TaskCode.AddFace -> {
                        showInputDialog()
                    }
                    // Edit Face
                    BleDeviceFeature.TaskCode.EditFace -> {
                        showInputDialog()
                    }
                    // Delete Face
                    BleDeviceFeature.TaskCode.DeleteFace -> {
                        showInputDialog()
                    }
                    // Device Get Face
                    BleDeviceFeature.TaskCode.DeviceGetFace -> {
                        deviceGetFace()
                    }
                    // Get Event Quantity
                    BleDeviceFeature.TaskCode.GetEventQuantity -> {
                        getEventQuantity()
                    }
                    // Get Event
                    BleDeviceFeature.TaskCode.GetEvent -> {
                        getEvent()
                    }
                    // Delete Event
                    BleDeviceFeature.TaskCode.DeleteEvent -> {
                        showInputDialog()
                    }
                    // Get Lock Supported Unlock Types
                    BleDeviceFeature.TaskCode.GetLockSupportedUnlockTypes -> {
                        getLockSupportedUnlockTypes()
                    }
                    // Get User Ability
                    BleDeviceFeature.TaskCode.GetUserAbility -> {
                        getUserAbility()
                    }
                    // Get User Count
                    BleDeviceFeature.TaskCode.GetUserCount -> {
                        getUserCount()
                    }
                    // Is Matter Device
                    BleDeviceFeature.TaskCode.IsMatterDevice -> {
                        isMatterDevice()
                    }
                    // Get User Array
                    BleDeviceFeature.TaskCode.GetUserArray -> {
                        getUserArray()
                    }
                    // Get User
                    BleDeviceFeature.TaskCode.GetUser -> {
                        getUser()
                    }
                    // Add User
                    BleDeviceFeature.TaskCode.AddUser -> {
                        showInputDialog()
                    }
                    // Edit User
                    BleDeviceFeature.TaskCode.EditUser -> {
                        showInputDialog()
                    }
                    // Delete User
                    BleDeviceFeature.TaskCode.DeleteUser -> {
                        showInputDialog()
                    }
                    // Get Credential Array
                    BleDeviceFeature.TaskCode.GetCredentialArray -> {
                        getCredentialArray()
                    }
                    // Get Credential
                    BleDeviceFeature.TaskCode.GetCredential -> {
                        getCredential()
                    }
                    // Get Credential By Credential
                    BleDeviceFeature.TaskCode.GetCredentialByCredential -> {
                        getCredentialByCredential()
                    }
                    // Get Credential By User
                    BleDeviceFeature.TaskCode.GetCredentialByUser -> {
                        getCredentialByUser()
                    }
                    // Get Credential Hash
                    BleDeviceFeature.TaskCode.GetUserCredentialHash -> {
                        getUserCredentialHash()
                    }
                    // Get User Hash
                    BleDeviceFeature.TaskCode.GetBleUserHash -> {
                        getBleUserHash()
                    }
                    // Set All Data Synced
                    BleDeviceFeature.TaskCode.SetAllDataSynced -> {
                        setAllDataSynced()
                    }
                    // Scan Wifi
                    BleDeviceFeature.TaskCode.ScanWifi -> {
                        when (_currentDeviceStatus) {
                            is DeviceStatus.EightTwo -> {
                                collectWifiList3()
                                scanWifi3()
                            }

                            is DeviceStatus.B0 -> {
                                collectWifiList3()
                                scanWifi3()
                            }

                            else -> {
                                collectWifiList()
                                scanWifi()
                            }
                        }
                    }
                    // Connect To Wifi
                    BleDeviceFeature.TaskCode.ConnectToWifi -> {
                        when (_currentDeviceStatus) {
                            is DeviceStatus.EightTwo -> {
                                connectToWifi3("Sunion-SW", "S-device_W")
                            }

                            is DeviceStatus.B0 -> {
                                connectToWifi3("Sunion-SW", "S-device_W")
                            }

                            else -> {
                                connectToWifi("Sunion-SW", "S-device_W")
                            }
                        }
                    }
                    // Set OTA Status
                    BleDeviceFeature.TaskCode.SetOTAUpdate -> {
                        if (!isBackgroundOTA) {
                            // Foreground OTA Update
                            otaUpdate(currentTarget, signatureV005)
                        } else {
                            // Background OTA Update
                            backGroundOTAUpdate()
                        }
                    }
                    // Set OTA Cancel
                    BleDeviceFeature.TaskCode.SetOTACancel -> {
                        setOTACancel(currentTarget)
                    }
                    // Disconnect
                    BleDeviceFeature.TaskCode.Disconnect -> {
                        disconnect()
                    }

                    else -> {}
                }
            }
            _uiState.update { it.copy(btnEnabled = true) }
        }
    }

    private suspend fun connect() {
        val workInfos = WorkManager.getInstance(application).getWorkInfosForUniqueWork(WorkerNames.OTA_WORKER).get()

        val isRunningOrEnqueued = workInfos.any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }

        if (isRunningOrEnqueued) {
            showLog("OTA還在背景執行中，無法連線")
            return
        }

        val functionName = ::connect.name
        if(lockConnectionInfo == null) {
            showLog("Please scan QR code to get lock connection information.")
            return
        }
        //建立一個信號，用來等待連線結果
        val connectionResult = CompletableDeferred<Unit>()

        // Setup BLE connection state observer
        _bleConnectionStateListener?.cancel()
        _bleConnectionStateListener = statefulConnection.connState
            .onEach { event ->
                when (event.status) {
                    // something wrong
                    EventState.ERROR -> {
                        _uiState.update { it.copy(isConnectedWithLock = false) }
                        // 發生錯誤，通知等待結束
                        connectionResult.completeExceptionally(Exception(event.message ?: "Connect Error"))

                        when (event.message) {
                            TimeoutException::class.java.simpleName -> {
                                showLog("$functionName to lock timeout")
                            }
                            ConnectionTokenException.IllegalTokenException::class.java.simpleName -> {
                                showLog("$functionName to lock failed with illegal token")
                            }
                            else -> {
                                unless(
                                    event.data != null
                                ) {
                                    showLog("$functionName to lock failed: ${event.message}")
                                    disconnect()
                                }
                            }
                        }
                    }
                    // RxBleConnection is ready.
                    EventState.READY -> {
                        // Setup incoming device status observer
                        _bleSunionBleNotificationListener?.cancel()
                        _bleSunionBleNotificationListener = incomingSunionBleNotificationUseCase()
                            .map { sunionBleNotification ->
                                Timber.d("Incoming SunionBleNotification: $sunionBleNotification")
                                when (sunionBleNotification) {
                                    is DeviceStatus -> {
                                        _currentDeviceStatus = sunionBleNotification
                                        showLog("Incoming ${sunionBleNotification::class.simpleName} arrived.")
                                        setSupportTaskList(sunionBleNotification)
                                        connectBleLockCompletion?.complete(Unit)
                                    }
                                    is Alert -> {
                                        _currentSunionBleNotification = sunionBleNotification
                                        showLog("Incoming ${sunionBleNotification::class.simpleName} arrived.")
                                    }
                                    is Access -> {
                                        _currentSunionBleNotification = sunionBleNotification
                                        showLog("Incoming ${sunionBleNotification::class.simpleName} arrived.")
                                    }
                                    is Credential -> {
                                        _currentSunionBleNotification = sunionBleNotification
                                        showLog("Incoming ${sunionBleNotification::class.simpleName} arrived.")
                                    }
                                    else -> {
                                        _currentSunionBleNotification = SunionBleNotification.UNKNOWN
                                    }
                                }
                                updateCurrentDeviceStatusOrNotification(sunionBleNotification)
                            }
                            .catch { e -> showLog("Incoming SunionBleNotification exception $e") }
                            .flowOn(Dispatchers.IO)
                            .launchIn(viewModelScope)
                    }
                    // connected
                    EventState.SUCCESS -> {
                        if (event.status == EventState.SUCCESS && event.data?.first == true) {
                            model = lockConnectionInfo!!.model
                            _uiState.update { it.copy(isConnectedWithLock = true) }
                            _lockConnectionInfo = lockConnectionInfo!!.copy(
                                permission = statefulConnection.lockConnectionInfo.permission,
                                keyTwo = statefulConnection.lockConnectionInfo.keyTwo,
                                permanentToken = statefulConnection.lockConnectionInfo.permanentToken
                            )
                            // filter model not support function
                            setModelSupportTaskList(lockConnectionInfo!!.model)
                            showLog("$functionName to lock succeed.")
                            // connect with oneTimeToken
                            if (lockConnectionInfo!!.permanentToken.isNullOrEmpty()) {
                                showLog("After pairing with lock, you can get lock connection information from statefulConnection.lockConnectionInfo and save permanent token for later use.")
                            }
                            showLog("Lock connection information:")
                            showLog("$lockConnectionInfo")
                            connectBleLockCompletion = CompletableDeferred()
                            connectBleLockCompletion?.await()
                            initLock()

                            // 連線成功，通知等待結束
                            connectionResult.complete(Unit)
                        }
                    }
                    EventState.LOADING -> {}
                    else -> {}
                }
            }
            .catch { Timber.e(it) }
            .flowOn(Dispatchers.Default)
            .launchIn(viewModelScope)
        // connect to device
        coroutineScope {
            launch {
                statefulConnection.establishConnection(
                    macAddress = lockConnectionInfo!!.macAddress,
                    keyOne = lockConnectionInfo!!.keyOne,
                    oneTimeToken = lockConnectionInfo!!.oneTimeToken,
                    permanentToken = lockConnectionInfo!!.permanentToken,
                    model = lockConnectionInfo!!.model,
                    isSilentlyFail = false
                )
            }

            try {
                // 關鍵：這裡會掛起，直到 connectionResult.complete() 被呼叫
                // 或是 30 秒後超時，避免永遠卡死
                withTimeout(30000) {
                    connectionResult.await()
                }
            } catch (e: TimeoutException) {
                showLog("連線超時: ${e.message}")
            }
        }
    }

    private suspend fun initLock(){
        val functionName = "initLock"
        val isAdminCodeExists = isAdminCodeExists()
        if(!isAdminCodeExists) {
            createAdminCode()
        }
        getLockConfig()
        val containsGetLockSupportedUnlockTypesTask = uiState.value.taskList.any { it.first == BleDeviceFeature.TaskCode.GetLockSupportedUnlockTypes }
        if(containsGetLockSupportedUnlockTypesTask) {
            getLockSupportedUnlockTypes()
        }
        val containsGetUserAbilityTask = uiState.value.taskList.any { it.first == BleDeviceFeature.TaskCode.GetUserAbility }
        if(containsGetUserAbilityTask) {
            getUserAbility()
        }
        val containsDetermineLockTask = uiState.value.taskList.any { it.first == BleDeviceFeature.TaskCode.DetermineLockDirection }
        if(containsDetermineLockTask) {
            determineLockDirection()
            showLog("$functionName: success")
        } else {
            showLog("$functionName: success")
        }
    }

    private suspend fun getLockTime(): Int {
        val functionName = "getLockTime"
        val result = lockTimeUseCase.getTime()
        showLog("$functionName: $result")
        return result
    }

    private suspend fun setLockTime(time: Long = Instant.now().atZone(ZoneId.systemDefault()).toEpochSecond()): Boolean {
        val functionName = "setLockTime"
        val result = lockTimeUseCase.setTime(time)
        showLog("$functionName result: $result")
        return result
    }

    private suspend fun getLockTimeZone(): String {
        val functionName = "getLockTimeZone"
        val result = when(model) {
            "KDW01", "TDW01", "TLRW01" -> {
                lockTimeUseCase.getWiFiTimeZone()
            }
            else -> {
                lockTimeUseCase.getTimeZone()
            }
        }
        showLog("$functionName result: $result")
        return result
    }

    private suspend fun setLockTimeZone(timeZone: String = ZoneId.systemDefault().id): Boolean {
        val functionName = "setLockTimeZone"
        val result = when(model) {
            "KDW01", "TDW01", "TLRW01" -> {
                lockTimeUseCase.setWiFiTimeZone(timeZone)
            }
            else -> {
                lockTimeUseCase.setTimeZone(timeZone)
            }
        }
        showLog("$functionName to $timeZone result: $result")
        return result
    }

    private suspend fun getDeviceStatus() {
        val functionName = "getDeviceStatus"
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val deviceStatus = deviceStatusD6UseCase()
                _currentDeviceStatus = DeviceStatus.D6(
                    deviceStatus.config,
                    deviceStatus.lockState,
                    deviceStatus.battery,
                    deviceStatus.batteryState,
                    deviceStatus.timestamp
                )
                updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
            }
            is DeviceStatus.A2 -> {
                val deviceStatus = deviceStatusA2UseCase()
                _currentDeviceStatus = DeviceStatus.A2(
                    deviceStatus.direction,
                    deviceStatus.vacationMode,
                    deviceStatus.deadBolt,
                    deviceStatus.doorState,
                    deviceStatus.lockState,
                    deviceStatus.securityBolt,
                    deviceStatus.battery,
                    deviceStatus.batteryState
                )
                updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
            }
            is DeviceStatus.B0 -> {
                val deviceStatus = plugConfigUseCase()
                _currentDeviceStatus = DeviceStatus.B0(
                    deviceStatus.mainVersion,
                    deviceStatus.subVersion,
                    deviceStatus.setWifi,
                    deviceStatus.connectWifi,
                    deviceStatus.plugState
                )
                updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
            }
            is DeviceStatus.EightTwo -> {
                val deviceStatus = deviceStatus82UseCase()
                _currentDeviceStatus = DeviceStatus.EightTwo(
                    deviceStatus.mainVersion,
                    deviceStatus.subVersion,
                    deviceStatus.direction,
                    deviceStatus.vacationMode,
                    deviceStatus.deadBolt,
                    deviceStatus.doorState,
                    deviceStatus.lockState,
                    deviceStatus.securityBolt,
                    deviceStatus.battery,
                    deviceStatus.batteryState
                )
                updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private suspend fun toggleLockState() {
        val functionName = "toggleLockState"
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.D6
                val desiredState = when (deviceStatusD6.lockState) {
                    LockState.LOCKED -> { LockState.UNLOCKED }
                    LockState.UNLOCKED -> { LockState.LOCKED }
                    else -> {
                        showLog("Unknown lock state.")
                        return
                    }
                }
                if (deviceStatusD6.config.direction is LockDirection.NotDetermined) {
                    showLog("Lock direction is not determined. Please set lock direction before toggle lock state.")
                    return
                }
                val deviceStatus = deviceStatusD6UseCase.setLockState(desiredState)
                showLog("$functionName to $desiredState")
                _currentDeviceStatus = DeviceStatus.D6(
                    deviceStatus.config,
                    deviceStatus.lockState,
                    deviceStatus.battery,
                    deviceStatus.batteryState,
                    deviceStatus.timestamp
                )
                updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
            }
            is DeviceStatus.A2 -> {
                val deviceStatusA2 = _currentDeviceStatus as DeviceStatus.A2
                if (deviceStatusA2.direction == BleV2Lock.Direction.UNKNOWN.value) {
                    showLog("Lock direction is not determined. Please set lock direction before toggle lock state.")
                    return
                }
                val desiredState: Int = when (deviceStatusA2.lockState) {
                    BleV2Lock.LockState.LOCKED.value -> { BleV2Lock.LockState.UNLOCKED.value }
                    BleV2Lock.LockState.UNLOCKED.value -> { BleV2Lock.LockState.LOCKED.value }
                    else -> {
                        showLog("Unknown lock state.")
                        return
                    }
                }
                val deviceStatus =deviceStatusA2UseCase.setLockState(desiredState)
                showLog("$functionName to $desiredState")
                _currentDeviceStatus = DeviceStatus.A2(
                    deviceStatus.direction,
                    deviceStatus.vacationMode,
                    deviceStatus.deadBolt,
                    deviceStatus.doorState,
                    deviceStatus.lockState,
                    deviceStatus.securityBolt,
                    deviceStatus.battery,
                    deviceStatus.batteryState
                )
                updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
            }
            is DeviceStatus.EightTwo -> {
                val deviceStatus82 = _currentDeviceStatus as DeviceStatus.EightTwo
                if (deviceStatus82.direction == BleV3Lock.Direction.UNKNOWN.value) {
                    showLog("Lock direction is not determined. Please set lock direction before toggle lock state.")
                    return
                }
                val desiredState: Int = when (deviceStatus82.lockState) {
                    BleV3Lock.LockState.LOCKED.value -> { BleV3Lock.LockState.UNLOCKED.value }
                    BleV3Lock.LockState.UNLOCKED.value -> { BleV3Lock.LockState.LOCKED.value }
                    else -> {
                        showLog("Unknown lock state.")
                        return
                    }
                }
                val deviceStatus = deviceStatus82UseCase.setLockState(desiredState)
                showLog("$functionName to $desiredState")
                _currentDeviceStatus = DeviceStatus.EightTwo(
                    deviceStatus.mainVersion,
                    deviceStatus.subVersion,
                    deviceStatus.direction,
                    deviceStatus.vacationMode,
                    deviceStatus.deadBolt,
                    deviceStatus.doorState,
                    deviceStatus.lockState,
                    deviceStatus.securityBolt,
                    deviceStatus.battery,
                    deviceStatus.batteryState
                )
                updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private suspend fun autoUnlockToggleLockState() {
        val functionName = "autoUnlockToggleLockState"
        when (_currentDeviceStatus) {
            is DeviceStatus.EightTwo -> {
                val deviceStatus82 = _currentDeviceStatus as DeviceStatus.EightTwo
                if (deviceStatus82.direction == BleV3Lock.Direction.UNKNOWN.value) {
                    showLog("Lock direction is not determined. Please set lock direction before toggle lock state.")
                    return
                }
                val desiredState: Int = when (deviceStatus82.lockState) {
                    BleV3Lock.LockState.LOCKED.value -> { BleV3Lock.LockState.UNLOCKED.value }
                    BleV3Lock.LockState.UNLOCKED.value -> {
                        showLog("Already unlocked.")
                        return
                    }
                    else -> {
                        showLog("Unknown lock state.")
                        return
                    }
                }
                val result = deviceStatus82UseCase.setAutoUnlockLockState(desiredState)
                showLog("$functionName to $desiredState result: $result")
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private suspend fun toggleSecurityBolt() {
        val functionName = "toggleSecurityBolt"
        when (_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val deviceStatusA2 = _currentDeviceStatus as DeviceStatus.A2
                if (deviceStatusA2.securityBolt.isNotSupport()) {
                    showLog("$functionName not support.")
                    return
                }
                val state: Int = when (deviceStatusA2.securityBolt) {
                    BleV2Lock.SecurityBolt.PROTRUDE.value -> { BleV2Lock.SecurityBolt.NOT_PROTRUDE.value }
                    BleV2Lock.SecurityBolt.NOT_PROTRUDE.value -> { BleV2Lock.SecurityBolt.PROTRUDE.value }
                    else -> {
                        showLog("Unknown security bolt.")
                        return
                    }
                }
                val deviceStatus = deviceStatusA2UseCase.setSecurityBolt(state)
                showLog("$functionName to $state")
                _currentDeviceStatus = DeviceStatus.A2(
                    deviceStatus.direction,
                    deviceStatus.vacationMode,
                    deviceStatus.deadBolt,
                    deviceStatus.doorState,
                    deviceStatus.lockState,
                    deviceStatus.securityBolt,
                    deviceStatus.battery,
                    deviceStatus.batteryState
                )
                updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
            }
            is DeviceStatus.EightTwo -> {
                val deviceStatus82 = _currentDeviceStatus as DeviceStatus.EightTwo
                if (deviceStatus82.securityBolt.isNotSupport()) {
                    showLog("$functionName not support.")
                    return
                }
                val state: Int = when (deviceStatus82.securityBolt) {
                    BleV3Lock.SecurityBolt.PROTRUDE.value -> { BleV3Lock.SecurityBolt.NOT_PROTRUDE.value }
                    BleV3Lock.SecurityBolt.NOT_PROTRUDE.value -> { BleV3Lock.SecurityBolt.PROTRUDE.value }
                    else -> {
                        showLog("Unknown security bolt.")
                        return
                    }
                }
                val deviceStatus = deviceStatus82UseCase.setSecurityBolt(state)
                showLog("$functionName to $state")
                _currentDeviceStatus = DeviceStatus.EightTwo(
                    deviceStatus.mainVersion,
                    deviceStatus.subVersion,
                    deviceStatus.direction,
                    deviceStatus.vacationMode,
                    deviceStatus.deadBolt,
                    deviceStatus.doorState,
                    deviceStatus.lockState,
                    deviceStatus.securityBolt,
                    deviceStatus.battery,
                    deviceStatus.batteryState
                )
                updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private suspend fun getLockConfig() {
        val functionName = "getLockConfig"
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val lockConfig = lockConfigD4UseCase.get()
                showLog("$functionName.D4: $lockConfig")
            }
            is DeviceStatus.A2 -> {
                val lockConfig = lockConfigA0UseCase.get()
                showLog("$functionName.A0: $lockConfig")
                // filter not support function
                setSupportTaskList(lockConfig = lockConfig)
            }
            is DeviceStatus.EightTwo -> {
                val lockConfig = lockConfig80UseCase.get()
                showLog("$functionName.80: $lockConfig")
                // filter not support function
                setSupportTaskList(lockConfig = lockConfig)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun toggleKeyPressBeep(soundValue:Int = 0) {
        val functionName = "toggleKeyPressBeep"
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.D6
                val isSoundOn = when (deviceStatusD6.config.isSoundOn) {
                    true -> { false }
                    false -> { true }
                }
                val result = lockConfigD4UseCase.setKeyPressBeep(isSoundOn)
                showLog("$functionName to $isSoundOn result: $result")
            }
            is DeviceStatus.A2 -> {
                val lockConfig = lockConfigA0UseCase.get()
                val value = when (lockConfig.soundType) {
                    0x01 -> if(lockConfig.soundValue == 100) 0 else 100
                    0x02 -> if(lockConfig.soundValue == 100) 50 else if(lockConfig.soundValue == 50) 0 else 100
                    else -> soundValue
                }
                val result = lockConfigA0UseCase.setSoundValue(value != 0, value)
                showLog("$functionName at type ${lockConfig.soundType} result: $result")
            }
            is DeviceStatus.EightTwo -> {
                val lockConfig = lockConfig80UseCase.get()
                val value = when (lockConfig.soundType) {
                    0x01 -> if(lockConfig.soundValue == 100) 0 else 100
                    0x02 -> if(lockConfig.soundValue == 100) 50 else if(lockConfig.soundValue == 50) 0 else 100
                    else -> soundValue
                }
                val result = lockConfig80UseCase.setSoundValue(value != 0, value)
                showLog("$functionName at type ${lockConfig.soundType} value $value result: $result")
            }
            else -> { showLog("Device status not support.") }
        }
    }

    private suspend fun toggleVirtualCode() {
        val functionName = "toggleVirtualCode"
        when (_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val lockConfig = lockConfigA0UseCase.get()
                val isVirtualCodeOn = lockConfig.virtualCode == BleV2Lock.VirtualCode.CLOSE.value
                val result = lockConfigA0UseCase.setVirtualCode(isVirtualCodeOn)
                showLog("$functionName to $isVirtualCodeOn result: $result")
            }
            is DeviceStatus.EightTwo -> {
                val lockConfig = lockConfig80UseCase.get()
                val isVirtualCodeOn = lockConfig.virtualCode == BleV3Lock.VirtualCode.CLOSE.value
                val result = lockConfig80UseCase.setVirtualCode(isVirtualCodeOn)
                showLog("$functionName to $isVirtualCodeOn result: $result")
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private suspend fun toggleTwoFA() {
        val functionName = "toggleTwoFA"
        when (_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val lockConfig = lockConfigA0UseCase.get()
                val isTwoFAOn = lockConfig.twoFA == BleV2Lock.TwoFA.CLOSE.value
                val result = lockConfigA0UseCase.setTwoFA(isTwoFAOn)
                showLog("$functionName to $isTwoFAOn result: $result")
            }
            is DeviceStatus.EightTwo -> {
                val lockConfig = lockConfig80UseCase.get()
                val isTwoFAOn = lockConfig.twoFA == BleV3Lock.TwoFA.CLOSE.value
                val result = lockConfig80UseCase.setTwoFA(isTwoFAOn)
                showLog("$functionName to $isTwoFAOn result: $result")
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private suspend fun toggleVacationMode() {
        val functionName = "toggleVacationMode"
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.D6
                val isVacationModeOn = when (deviceStatusD6.config.isVacationModeOn) {
                    true -> { false }
                    false -> { true }
                }
                val result = lockConfigD4UseCase.setVacationMode(isVacationModeOn)
                showLog("$functionName to $isVacationModeOn result: $result")
            }
            is DeviceStatus.A2 -> {
                val lockConfig = lockConfigA0UseCase.get()
                val isVacationModeOn = lockConfig.vacationMode == BleV2Lock.VacationMode.CLOSE.value
                val result = lockConfigA0UseCase.setVacationMode(isVacationModeOn)
                showLog("$functionName to $isVacationModeOn result: $result")
            }
            is DeviceStatus.EightTwo -> {
                val lockConfig = lockConfig80UseCase.get()
                val isVacationModeOn = lockConfig.vacationMode == BleV3Lock.VacationMode.CLOSE.value
                val result = lockConfig80UseCase.setVacationMode(isVacationModeOn)
                showLog("$functionName to $isVacationModeOn result: $result")
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private suspend fun toggleGuidingCode() {
        val functionName = ::toggleGuidingCode.name
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.D6
                val isGuidingCodeOn = when (deviceStatusD6.config.isGuidingCodeOn) {
                    true -> { false }
                    false -> { true }
                }
                val result = lockConfigD4UseCase.setGuidingCode(isGuidingCodeOn)
                showLog("$functionName to $isGuidingCodeOn result: $result")
            }
            is DeviceStatus.A2 -> {
                val lockConfig = lockConfigA0UseCase.get()
                val isGuidingCodeOn = lockConfig.guidingCode == BleV2Lock.GuidingCode.CLOSE.value
                val result = lockConfigA0UseCase.setGuidingCode(isGuidingCodeOn)
                showLog("$functionName to $isGuidingCodeOn result: $result")
            }
            is DeviceStatus.EightTwo -> {
                val lockConfig = lockConfig80UseCase.get()
                val isGuidingCodeOn = lockConfig.guidingCode == BleV3Lock.GuidingCode.CLOSE.value
                val result = lockConfig80UseCase.setGuidingCode(isGuidingCodeOn)
                showLog("$functionName to $isGuidingCodeOn result: $result")
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private suspend fun toggleAutoLock(autoLockTime: Int = 10) {
        val functionName = "toggleAutoLock"
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.D6
                val isAutoLock = when (deviceStatusD6.config.isAutoLock) {
                    true -> { false }
                    false -> { true }
                }
                val result = lockConfigD4UseCase.setAutoLock(isAutoLock, autoLockTime)
                if (isAutoLock) {
                    showLog("$functionName to true and auto lock time to $autoLockTime result: $result")
                } else {
                    showLog("$functionName to false result: $result")
                }
            }
            is DeviceStatus.A2 -> {
                val lockConfig = lockConfigA0UseCase.get()
                val isAutoLock = lockConfig.autoLock == BleV2Lock.AutoLock.CLOSE.value
                val result = lockConfigA0UseCase.setAutoLock(isAutoLock, autoLockTime)
                showLog("$functionName to $isAutoLock and auto lock time to $autoLockTime result: $result")
            }
            is DeviceStatus.EightTwo -> {
                val lockConfig = lockConfig80UseCase.get()
                val isAutoLock = lockConfig.autoLock == BleV3Lock.AutoLock.CLOSE.value
                val result = lockConfig80UseCase.setAutoLock(isAutoLock, autoLockTime)
                showLog("$functionName to $isAutoLock and auto lock time to $autoLockTime result: $result")
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private suspend fun setLockLocation(latitude: Double = 25.03369, longitude: Double = 121.564128) {
        val functionName = "setLockLocation"
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val result = lockConfigD4UseCase.setLocation(latitude = latitude, longitude = longitude)
                showLog("$functionName to (${latitude}, ${longitude}) result: $result")
            }
            is DeviceStatus.A2 -> {
                val result = lockConfigA0UseCase.setLocation(latitude = latitude, longitude = longitude)
                showLog("$functionName to (${latitude}, ${longitude}) result: $result")
            }
            is DeviceStatus.EightTwo -> {
                val result = lockConfig80UseCase.setLocation(latitude = latitude, longitude = longitude)
                showLog("$functionName to (${latitude}, ${longitude}) result: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun toggleOperatingSound() {
        val functionName = "toggleOperatingSound"
        when (_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val lockConfig = lockConfigA0UseCase.get()
                val isOperatingSoundOn = lockConfig.operatingSound == BleV2Lock.OperatingSound.CLOSE.value
                val result = lockConfigA0UseCase.setOperatingSound(isOperatingSoundOn)
                showLog("$functionName to $isOperatingSoundOn result: $result")
            }
            is DeviceStatus.EightTwo -> {
                val lockConfig = lockConfig80UseCase.get()
                val isOperatingSoundOn = lockConfig.operatingSound == BleV3Lock.OperatingSound.CLOSE.value
                val result = lockConfig80UseCase.setOperatingSound(isOperatingSoundOn)
                showLog("$functionName to $isOperatingSoundOn result: $result")
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private suspend fun toggleShowFastTrackMode() {
        val functionName = "toggleShowFastTrackMode"
        when (_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val lockConfig = lockConfigA0UseCase.get()
                val isShowFastTrackModeOn = lockConfig.showFastTrackMode == BleV2Lock.ShowFastTrackMode.CLOSE.value
                val result = lockConfigA0UseCase.setShowFastTrackMode(isShowFastTrackModeOn)
                showLog("$functionName to $isShowFastTrackModeOn result: $result")
            }
            is DeviceStatus.EightTwo -> {
                val lockConfig = lockConfig80UseCase.get()
                val isShowFastTrackModeOn = lockConfig.showFastTrackMode == BleV3Lock.ShowFastTrackMode.CLOSE.value
                val result = lockConfig80UseCase.setShowFastTrackMode(isShowFastTrackModeOn)
                showLog("$functionName to $isShowFastTrackModeOn result: $result")
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private suspend fun toggleSabbathMode() {
        val functionName = "toggleSabbathMode"
        when (_currentDeviceStatus) {
            is DeviceStatus.EightTwo -> {
                val lockConfig = lockConfig80UseCase.get()
                val isSabbathMode = lockConfig.sabbathMode == BleV3Lock.SabbathMode.CLOSE.value
                val result = lockConfig80UseCase.setSabbathMode(isSabbathMode)
                showLog("$functionName to $isSabbathMode result: $result")
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private suspend fun togglePhoneticLanguage() {
        val functionName = "togglePhoneticLanguage"
        when (_currentDeviceStatus) {
            is DeviceStatus.EightTwo -> {
                val lockConfig =lockConfig80UseCase.get()
                val phoneticLanguage = lockConfig.phoneticLanguage
                if(phoneticLanguage.isNotSupport()){
                    throw LockStatusException.LockFunctionNotSupportException()
                } else {
                    val supportPhoneticLanguageList = lockConfig.supportPhoneticLanguage.toSupportPhoneticLanguageList()
                    var nextPhoneticLanguage = -1
                    for (i in supportPhoneticLanguageList.indices) {
                        val nextIndex = if (i == supportPhoneticLanguageList.lastIndex) 0 else i + 1
                        nextPhoneticLanguage = supportPhoneticLanguageList[nextIndex]
                    }
                    if(phoneticLanguage != nextPhoneticLanguage){
                        val result = lockConfig80UseCase.setPhoneticLanguage(nextPhoneticLanguage)
                        val languageName = BleV3Lock.PhoneticLanguage.entries.firstOrNull { it.value == nextPhoneticLanguage } ?: BleV3Lock.PhoneticLanguage.NOT_SUPPORT
                        showLog("$functionName to $languageName result: $result")
                    } else {
                        val languageName = BleV3Lock.PhoneticLanguage.entries.firstOrNull { it.value == phoneticLanguage } ?: BleV3Lock.PhoneticLanguage.NOT_SUPPORT
                        showLog("Already set $languageName language.")
                    }
                }
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private suspend fun determineLockDirection() {
        val functionName = "determineLockDirection"
        showLog(functionName)
        when (val deviceStatus = lockDirectionUseCase()) {
            is DeviceStatus.D6 -> {
                _currentDeviceStatus = DeviceStatus.D6(
                    deviceStatus.config,
                    deviceStatus.lockState,
                    deviceStatus.battery,
                    deviceStatus.batteryState,
                    deviceStatus.timestamp
                )
                updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
            }
            is DeviceStatus.A2 -> {
                if(deviceStatus.direction.isNotSupport()) {
                    showLog("$functionName not support.")
                } else {
                    _currentDeviceStatus = DeviceStatus.A2(
                        deviceStatus.direction,
                        deviceStatus.vacationMode,
                        deviceStatus.deadBolt,
                        deviceStatus.doorState,
                        deviceStatus.lockState,
                        deviceStatus.securityBolt,
                        deviceStatus.battery,
                        deviceStatus.batteryState
                    )
                    updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
                }
            }
            is DeviceStatus.EightTwo -> {
                if(deviceStatus.direction.isNotSupport()) {
                    showLog("$functionName not support.")
                } else {
                    _currentDeviceStatus = DeviceStatus.EightTwo(
                        deviceStatus.mainVersion,
                        deviceStatus.subVersion,
                        deviceStatus.direction,
                        deviceStatus.vacationMode,
                        deviceStatus.deadBolt,
                        deviceStatus.doorState,
                        deviceStatus.lockState,
                        deviceStatus.securityBolt,
                        deviceStatus.battery,
                        deviceStatus.batteryState
                    )
                    updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getLockName() {
        val functionName = "getLockName"
        val name = lockNameUseCase.getName()
        showLog("$functionName: $name")
    }

    private suspend fun setLockName(name: String = "New_Lock") {
        val functionName = "setLockName"
        val result = lockNameUseCase.setName(name)
        showLog("$functionName to $name result: $result")
    }

    private suspend fun isAdminCodeExists(): Boolean {
        val functionName = "isAdminCodeExists"
        val result = adminCodeUseCase.isAdminCodeExists()
        showLog("$functionName: $result")
        return result
    }

    private suspend fun createAdminCode(code: String = "0000") {
        val functionName = "createAdminCode"
        val result = adminCodeUseCase.createAdminCode(code)
        if(result) {
            adminCode = code
        }
        showLog("$functionName $code result: $result")
    }

    private suspend fun updateAdminCode(oldCode: String = adminCode, newCode: String = "1234") {
        val functionName = "updateAdminCode"
        val result = adminCodeUseCase.updateAdminCode(oldCode, newCode)
        if(result) {
            adminCode = newCode
        }
        showLog("$functionName from $oldCode to $newCode result: $result")
    }

    private suspend fun getAdminCodePosition() {
        val functionName = "getAdminCodePosition"
        val result = adminCodeUseCase.getAdminCodePosition()
        val adminPosition = BleV3Lock.AdminPosition(result.userIndex, result.credentialIndex)
        showLog("$functionName adminPosition $adminPosition")
    }

    private suspend fun togglePlugState() {
        val functionName = "togglePlugState"
        when (_currentDeviceStatus) {
            is DeviceStatus.B0 -> {
                val deviceStatusB0 = _currentDeviceStatus as DeviceStatus.B0
                val plugState =
                    if (deviceStatusB0.plugState == BleV2Lock.PlugState.POWER_ON.value) {
                        BleV2Lock.PlugState.POWER_OFF.value
                    } else {
                        BleV2Lock.PlugState.POWER_ON.value
                    }
                showLog("$functionName $plugState")
                plugConfigUseCase.setPlugState(plugState)
            }
            else -> {}
        }
    }

    private suspend fun updateCurrentDeviceStatusOrNotification(sunionBleNotification: SunionBleNotification) {
        when (sunionBleNotification) {
            is DeviceStatus -> {
                showLog("Current is ${sunionBleNotification::class.simpleName}: $_currentDeviceStatus")
            }
            is Alert -> {
                showLog("Current is ${sunionBleNotification::class.simpleName}: $_currentSunionBleNotification")
            }
            is Access -> {
                showLog("Current is ${sunionBleNotification::class.simpleName}: $_currentSunionBleNotification")
                when(sunionBleNotification){
                    is Access.A9 -> {
                        when(sunionBleNotification.type){
                            Access.Type.CARD.value -> {
                                //AccessCard
                                if (sunionBleNotification.data.accessByteArrayToString().isNotBlank()) {
                                    _currentAccessA9Data.value = sunionBleNotification
                                    deviceExitAccess(currentAccessA9Data.value.type, currentAccessA9Data.value.index)
                                }
                            }
                            Access.Type.FINGERPRINT.value -> {
                                //Fingerprint
                                if (sunionBleNotification.data.accessByteArrayToString().isNotBlank()) {
                                    _currentAccessA9Data.value = sunionBleNotification
                                    if(sunionBleNotification.data.accessByteArrayToString() == "100") {
                                        deviceExitAccess(currentAccessA9Data.value.type, currentAccessA9Data.value.index)
                                    }
                                }
                            }
                            Access.Type.FACE.value -> {
                                //Face
                                if (sunionBleNotification.data.accessByteArrayToString().isNotBlank()) {
                                    _currentAccessA9Data.value = sunionBleNotification
                                    if(sunionBleNotification.data.accessByteArrayToString() == "100") {
                                        deviceExitAccess(currentAccessA9Data.value.type, currentAccessA9Data.value.index)
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
            is Credential -> {
                showLog("Current is ${sunionBleNotification::class.simpleName}: $_currentSunionBleNotification")
                when(sunionBleNotification){
                    is Credential.NinetySeven -> {
                        when(sunionBleNotification.type){
                            BleV3Lock.CredentialType.RFID.value -> {
                                //AccessCard
                                if (sunionBleNotification.data.accessByteArrayToString().isNotBlank()) {
                                    _currentCredential97Data.value = sunionBleNotification
                                    deviceExitCredential(currentCredential97Data.value.type, currentCredential97Data.value.index)
                                }
                            }
                            BleV3Lock.CredentialType.FINGERPRINT.value -> {
                                //Fingerprint
                                if (sunionBleNotification.data.accessByteArrayToString().isNotBlank()) {
                                    _currentCredential97Data.value = sunionBleNotification
                                    if(currentCredential97Data.value.data.accessByteArrayToString() == "100") {
                                        deviceExitCredential(currentCredential97Data.value.type, currentCredential97Data.value.index)
                                    }
                                }
                            }
                            BleV3Lock.CredentialType.FACE.value -> {
                                //Face
                                if (sunionBleNotification.data.accessByteArrayToString().isNotBlank()) {
                                    _currentCredential97Data.value = sunionBleNotification
                                    if(currentCredential97Data.value.data.accessByteArrayToString() == "100") {
                                        deviceExitCredential(currentCredential97Data.value.type, currentCredential97Data.value.index)
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
            else -> {
                showLog("Unknown device status or alert!!")
            }
        }
    }

    private suspend fun getFirmwareVersion() {
        val functionName = "getFirmwareVersion"
        val version = lockUtilityUseCase.getFirmwareVersion()
        showLog("$functionName: $version")
    }

    private suspend fun getFirmwareModel(): String {
        val functionName = "getFirmwareModel"
        val model = lockUtilityUseCase.getFirmwareModel()
        showLog("$functionName: $model")
        Timber.d("$functionName: $model")
        return model
    }

    private suspend fun getRfVersion() {
        val functionName = ::getRfVersion.name
        val version = lockUtilityUseCase.getRfVersion()
        showLog("$functionName: $version")
    }

    private suspend fun getMcuVersion() {
        val functionName = ::getMcuVersion.name
        val version = lockUtilityUseCase.getMcuVersion()
        showLog("$functionName: $version")
    }

    private suspend fun factoryReset(code: String = adminCode) {
        val functionName = "factoryReset"
        val result = lockUtilityUseCase.factoryReset(code)
        showLog("$functionName: $result")
        if(result){
            _lockConnectionInfo = null
            userAbility = null
            _currentDeviceStatus = DeviceStatus.UNKNOWN
            _currentSunionBleNotification = SunionBleNotification.UNKNOWN
            _uiState.update { it.copy(btnEnabled = false) }
        }
    }

    private suspend fun factoryResetNoAdmin() {
        val functionName = "factoryResetNoAdmin"
        val result = lockUtilityUseCase.factoryReset()
        showLog("$functionName: $result")
        if(result){
            _lockConnectionInfo = null
            userAbility = null
            _currentDeviceStatus = DeviceStatus.UNKNOWN
            _currentSunionBleNotification = SunionBleNotification.UNKNOWN
            _uiState.update { it.copy(btnEnabled = false) }
        }
    }

    private suspend fun restart() {
        val functionName = "restart"
        val result = lockUtilityUseCase.restart()
        showLog("$functionName: $result")
    }

    private suspend fun getTokenArray(){
        val functionName = "getTokenArray"
        when(_currentDeviceStatus) {
            is DeviceStatus.EightTwo -> {
                val tokenArray = lockBleUserUseCase.getBleUserArray()
                tokenArray.forEach { index ->
                    lastTokenIndex = index
                }
                showLog("$functionName: $tokenArray")
            }
            else -> {
                val tokenArray = lockTokenUseCase.getTokenArray()
                tokenArray.forEach { index ->
                    lastTokenIndex = index
                }
                showLog("$functionName: $tokenArray")
            }
        }
    }

    private suspend fun getToken(){
        val functionName = "getToken"
        when(_currentDeviceStatus) {
            is DeviceStatus.EightTwo -> {
                val tokenArray = lockBleUserUseCase.getBleUserArray()
                tokenArray.forEach { index ->
                    val deviceToken = lockBleUserUseCase.getBleUser(index)
                    if(deviceToken.isPermanent){
                        showLog("$functionName[$index] is permanent token: $deviceToken")
                    } else {
                        showLog("$functionName[$index] is one time token: ${deviceToken.token} name: ${deviceToken.name} permission: ${deviceToken.permission}")
                    }
                }
            }
            else -> {
                val tokenArray = lockTokenUseCase.getTokenArray()
                tokenArray.forEach { index ->
                    val deviceToken = lockTokenUseCase.getToken(index)
                    if(deviceToken.isPermanent){
                        showLog("$functionName[$index] is permanent token: $deviceToken")
                    } else {
                        showLog("$functionName[$index] is one time token: ${deviceToken.token} name: ${deviceToken.name} permission: ${deviceToken.permission}")
                    }
                }
            }
        }
    }

    private suspend fun addOneTimeToken(permission: String = "L", name: String = "User ${lastTokenIndex + 1}") {
        val functionName = "addOneTimeToken"
        when(_currentDeviceStatus) {
            is DeviceStatus.EightTwo -> {
                val result = lockBleUserUseCase.addOneTimeBleUser(permission, name, identity)
                showLog("$functionName permission: $permission name: $name\nresult: $result")
                if(result.isSuccessful){
                    lastTokenIndex += 1
                }
            }
            else -> {
                val result = lockTokenUseCase.addOneTimeToken(permission, name)
                showLog("$functionName permission: $permission name: $name\nresult: $result")
                if(result.isSuccessful){
                    lastTokenIndex += 1
                }
            }
        }
    }

    private suspend fun editToken(index:Int = lastTokenIndex, permission: String = "A", name: String = "User $lastTokenIndex ed") {
        val functionName = "editToken"
        when(_currentDeviceStatus) {
            is DeviceStatus.EightTwo -> {
                val result = lockBleUserUseCase.editBleUser(index, permission, name, identity)
                showLog("$functionName[$index] permission: $permission name: $name\nresult: $result")
            }
            else -> {
                val result = lockTokenUseCase.editToken(index, permission, name)
                showLog("$functionName[$index] permission: $permission name: $name\nresult: $result")
            }
        }
    }

    private suspend fun deleteToken(index: Int = lastTokenIndex, code: String = "") {
        val functionName = "deleteToken"
        when(_currentDeviceStatus) {
            is DeviceStatus.EightTwo -> {
                val result = lockBleUserUseCase.deleteBleUser(index, code)
                showLog("$functionName[$index] code: $code\nresult: $result")
                if(result) {
                    lastTokenIndex -= 1
                }
            }
            else -> {
                val result = lockTokenUseCase.deleteToken(index, code)
                showLog("$functionName[$index] code: $code\nresult: $result")
                if(result) {
                    lastTokenIndex -= 1
                }
            }
        }
    }

    private suspend fun getAccessCodeArray(){
        val functionName = "getAccessCodeArray"
        when(_currentDeviceStatus){
            is DeviceStatus.D6 -> {
                val accessCodeArray = lockAccessCodeUseCase.getAccessCodeArray()
                accessCodeArray.forEachIndexed { index, value ->
                    if (value) {
                        lastCodeCardIndex = index
                    }
                }
                showLog("$functionName: $accessCodeArray")
            }
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.accessCodeQuantity.isSupport2Byte()) {
                    val accessCodeArray = lockAccessUseCase.getAccessCodeArray()
                    accessCodeArray.forEachIndexed { index, value ->
                        if (value) {
                            lastCodeCardIndex = index
                        }
                    }
                    showLog("$functionName: $accessCodeArray")
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getAccessCode(){
        val functionName = "getAccessCode"
        when(_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val list = lockAccessCodeUseCase.getAccessCodeArray()
                val indexIterable = list.mapIndexedNotNull { index, boolean -> if (boolean && index != 0) index else null }
                indexIterable.forEach { index ->
                    val accessCode = lockAccessCodeUseCase.getAccessCode(index)
                    lastCodeIndex = index
                    showLog("$functionName[$index] is access code: $accessCode")
                }
            }
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if (result.accessCodeQuantity.isSupport2Byte()) {
                    val list = lockAccessUseCase.getAccessCodeArray()
                    val indexIterable = list.mapIndexedNotNull { index, boolean -> if (boolean) index else null }
                    Timber.d("indexIterable: $indexIterable")
                    indexIterable.forEach { index ->
                        val accessCode = lockAccessUseCase.getAccessCode(index)
                        if(accessCode.type == 0) {
                            lastCodeIndex = index
                            showLog("$functionName[$index] is access code: $accessCode")
                        }
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun addAccessCode(code: String = getRandomCode(), index:Int = lastCodeCardIndex + 1) {
        val functionName = "addAccessCode"
        val isEnabled = true
        val name = "User $index"
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val result = lockAccessCodeUseCase.addAccessCode(index, isEnabled, name, code, scheduleType)
                showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nresult: $result")
                if(result){
                    lastCodeIndex = index
                    lastCodeCardIndex = index
                }
            }
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.accessCodeQuantity.isSupport2Byte()) {
                    val isSuccess = lockAccessUseCase.addAccessCode(index, isEnabled, scheduleType, name, code)
                    showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nisSuccess: $isSuccess")
                    if(isSuccess){
                        lastCodeIndex = index
                        lastCodeCardIndex = index
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                var userIndex = lastUserIndex
                if (userAbility == null){
                    showLog("$functionName need getUserAbility first, try again.")
                    getUserAbility()
                    return
                }
                val credentialResult = lockCredentialUseCase.getCredentialByUser(lastUserIndex)
                if (userAbility!!.codeCredentialCount.isSupport()) {
                    if (userAbility!!.codeCredentialCount == (credentialResult.credentialDetail?.filter { it.type == BleV3Lock.CredentialType.PIN.value && it.status != BleV3Lock.UserStatus.AVAILABLE.value }?.size ?: 0)) {
                        userIndex += 1
                    }
                    val result = lockCredentialUseCase.addCredentialCode(index, userIndex, code)
                    showLog("$functionName index: $index userIndex: $userIndex code: $code\nresult: $result")
                    if(result){
                        lastUserIndex = userIndex
                        lastCodeIndex = index
                        lastCodeCardIndex = index
                        lastCredentialIndex = index
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun editAccessCode(code: String = getRandomCode(), index:Int = lastCodeCardIndex) {
        val functionName = "editAccessCode"
        val isEnabled = true
        val name = "User $lastCodeCardIndex ed"
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val result = lockAccessCodeUseCase.editAccessCode(index, isEnabled, name, code, scheduleType)
                showLog("$functionName: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nresult: $result")
            }
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.accessCodeQuantity.isSupport2Byte()) {
                    val isSuccess = lockAccessUseCase.editAccessCode(index, isEnabled, scheduleType, name, code)
                    showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nisSuccess: $isSuccess")
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                val userIndex = lastUserIndex
                val result = lockCredentialUseCase.editCredentialCode(index, userIndex, code)
                showLog("$functionName index: $index userIndex: $userIndex code: $code\nresult: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun deleteAccessCode(index: Int = lastCodeCardIndex) {
        val functionName = "deleteAccessCode"
        when(_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val result = lockAccessCodeUseCase.deleteAccessCode(index)
                showLog("$functionName[$index]\nresult: $result")
                if(result){
                    lastCodeIndex -= 1
                    lastCodeCardIndex -= 1
                }
            }
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.accessCodeQuantity.isSupport2Byte()) {
                    val isSuccess = lockAccessUseCase.deleteAccessCode(index)
                    showLog("$functionName[$index]\nisSuccess: $isSuccess")
                    if(isSuccess){
                        lastCodeIndex -= 1
                        lastCodeCardIndex -= 1
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                val result = lockCredentialUseCase.deleteCredential(index)
                showLog("$functionName index: $index result: $result")
                if(result){
                    lastCodeIndex -= 1
                    lastCodeCardIndex -= 1
                    lastCredentialIndex -= 1
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getAccessCardArray(){
        val functionName = "getAccessCardArray"
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.accessCardQuantity.isSupport2Byte()) {
                    val accessCardArray = lockAccessUseCase.getAccessCardArray()
                    accessCardArray.forEachIndexed { index, value ->
                        if (value) {
                            lastCodeCardIndex = index
                        }
                    }
                    showLog("$functionName: $accessCardArray")
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getAccessCard(){
        val functionName = "getAccessCard"
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if (result.accessCardQuantity.isSupport2Byte()) {
                    val list = lockAccessUseCase.getAccessCardArray()
                    val indexIterable = list.mapIndexedNotNull { index, boolean -> if (boolean) index else null }
                    Timber.d("indexIterable: $indexIterable")
                    indexIterable.forEach { index ->
                        val accessCard = lockAccessUseCase.getAccessCard(index)
                        if(accessCard.type == 1) {
                            lastCardIndex = index
                            showLog("$functionName[$index] is access card: $accessCard")
                        }
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun addAccessCard(index:Int = lastCodeCardIndex + 1) {
        val functionName = "addAccessCard"
        val isEnabled = true
        val name = "User $index"
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.accessCardQuantity.isSupport2Byte()) {
                    val code = currentAccessA9Data.value.data
                    val isSuccess = lockAccessUseCase.addAccessCard(index, isEnabled, scheduleType, name, code)
                    showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: ${code.accessByteArrayToString()} scheduleType: $scheduleType\nisSuccess: $isSuccess")
                    if(isSuccess){
                        lastCodeCardIndex = index
                        lastCardIndex = index
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                var userIndex = lastUserIndex
                if (userAbility == null){
                    showLog("$functionName need getUserAbility first, try again.")
                    getUserAbility()
                    return
                }
                val credentialResult = lockCredentialUseCase.getCredentialByUser(lastUserIndex)
                if (userAbility!!.cardCredentialCount.isSupport()) {
                    val code = currentCredential97Data.value.data
                    if (userAbility!!.cardCredentialCount == (credentialResult.credentialDetail?.filter { it.type == BleV3Lock.CredentialType.RFID.value && it.status != BleV3Lock.UserStatus.AVAILABLE.value }?.size ?: 0)) {
                        userIndex += 1
                    }
                    val result = lockCredentialUseCase.addCredentialCard(index, userIndex, code)
                    showLog("$functionName index: $index userIndex: $userIndex code: ${currentCredential97Data.value.data.accessByteArrayToString()}\nresult: $result")
                    if(result){
                        lastUserIndex = userIndex
                        lastCodeCardIndex = index
                        lastCredentialIndex = index
                        lastCardIndex = index
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun editAccessCard(index: Int = lastCodeCardIndex) {
        val functionName = "editAccessCard"
        val isEnabled = true
        val name = "User $lastCodeCardIndex ed"
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.accessCardQuantity.isSupport2Byte()) {
                    val code = currentAccessA9Data.value.data
                    val isSuccess = lockAccessUseCase.editAccessCard(index, isEnabled, scheduleType, name, code)
                    showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: ${code.accessByteArrayToString()} scheduleType: $scheduleType\nisSuccess: $isSuccess")
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                val userIndex = lastUserIndex
                val code = currentCredential97Data.value.data
                val result = lockCredentialUseCase.editCredentialCard(index, userIndex, code)
                showLog("$functionName index: $index userIndex: $userIndex code: ${code.accessByteArrayToString()}\nresult: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun deleteAccessCard(index: Int = lastCodeCardIndex) {
        val functionName = "deleteAccessCard"
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.accessCardQuantity.isSupport2Byte()) {
                    val isSuccess = lockAccessUseCase.deleteAccessCard(index)
                    showLog("$functionName[$index]\nisSuccess: $isSuccess")
                    if(isSuccess){
                        lastCodeCardIndex -= 1
                        lastCardIndex -= 1
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                val result = lockCredentialUseCase.deleteCredential(index)
                showLog("$functionName index: $index result: $result")
                if(result){
                    lastCodeCardIndex -= 1
                    lastCredentialIndex -= 1
                    lastCardIndex -= 1
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun deviceGetAccessCard(){
        val functionName = ::deviceGetAccessCard.name
        val index = lastCodeCardIndex + 2
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.accessCardQuantity.isSupport2Byte()) {
                    val isSuccess = lockAccessUseCase.deviceGetAccessCard(index)
                    showLog("$functionName: $isSuccess")
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                val result = lockCredentialUseCase.deviceGetCredentialCard(index)
                showLog("$functionName: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getFingerprintArray(){
        val functionName = "getFingerprintArray"
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.fingerprintQuantity.isSupport2Byte()) {
                    val fingerprintArray = lockAccessUseCase.getFingerprintArray()
                    fingerprintArray.forEachIndexed { index, value ->
                        if (value) {
                            lastFingerprintIndex = index
                        }
                    }
                    showLog("$functionName: $fingerprintArray")
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getFingerprint(){
        val functionName = "getFingerprint"
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.fingerprintQuantity.isSupport2Byte()) {
                    val list = lockAccessUseCase.getFingerprintArray()
                    val indexIterable = list.mapIndexedNotNull { index, boolean -> if (boolean) index else null }
                    Timber.d("indexIterable: $indexIterable")
                    indexIterable.forEach { index ->
                        val fingerprint = lockAccessUseCase.getFingerprint(index)
                        if(fingerprint.type == 2) {
                            showLog("$functionName[$index] is Fingerprint: $fingerprint")
                        }
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun addFingerprint(index:Int = lastFingerprintIndex + 1) {
        val functionName = "addFingerprint"
        val isEnabled = true
        val name = "User $index"
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.fingerprintQuantity.isSupport2Byte()) {
                    val isSuccess = lockAccessUseCase.addFingerprint(index, isEnabled, scheduleType, name)
                    showLog("$functionName index: $index isEnabled: $isEnabled name: $name scheduleType: $scheduleType\nisSuccess: $isSuccess")
                    if(isSuccess){
                        lastFingerprintIndex += 1
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                var userIndex = lastUserIndex
                if (userAbility == null){
                    showLog("$functionName need getUserAbility first, try again.")
                    getUserAbility()
                    return
                }
                val credentialResult = lockCredentialUseCase.getCredentialByUser(lastUserIndex)
                if (userAbility!!.fpCredentialCount.isSupport()) {
                    if (userAbility!!.fpCredentialCount == (credentialResult.credentialDetail?.filter { it.type == BleV3Lock.CredentialType.FINGERPRINT.value && it.status != BleV3Lock.UserStatus.AVAILABLE.value }?.size ?: 0)) {
                        userIndex += 1
                    }
                    val result = lockCredentialUseCase.addCredentialFingerPrint(index, userIndex, currentCredential97Data.value.index)
                    showLog("$functionName index: $index userIndex: $userIndex\nresult: $result")
                    if(result){
                        lastFingerprintIndex += 1
                        lastCredentialIndex += 1
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun editFingerprint(index:Int = lastFingerprintIndex) {
        val functionName = "editFingerprint"
        val isEnabled = true
        val name = "User $index ed"
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.fingerprintQuantity.isSupport2Byte()) {
                    val isSuccess = lockAccessUseCase.editFingerprint(index, isEnabled, scheduleType, name)
                    showLog("$functionName index: $index isEnabled: $isEnabled name: $name scheduleType: $scheduleType\nisSuccess: $isSuccess")
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                val userIndex = lastUserIndex
                val result = lockCredentialUseCase.editCredentialFingerPrint(index, userIndex, currentCredential97Data.value.index)
                showLog("$functionName index: $index userIndex: $userIndex\nresult: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun deleteFingerprint(index: Int = lastFingerprintIndex) {
        val functionName = "deleteFingerprint"
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.fingerprintQuantity.isSupport2Byte()) {
                    val isSuccess = lockAccessUseCase.deleteFingerprint(index)
                    showLog("$functionName[$index]\nisSuccess: $isSuccess")
                    if(isSuccess){
                        lastFingerprintIndex -= 1
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                val result = lockCredentialUseCase.deleteCredential(index)
                if(result){
                    lastFingerprintIndex -= 1
                    lastCredentialIndex -= 1
                }
                showLog("$functionName index: $index result: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun deviceGetFingerprint(){
        val functionName = "deviceGetFingerprint"
        val index = lastFingerprintIndex + 2
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.fingerprintQuantity.isSupport2Byte()) {
                    val isSuccess = lockAccessUseCase.deviceGetFingerprint(index)
                    showLog("$functionName: $isSuccess")
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                val result = lockCredentialUseCase.deviceGetCredentialFingerprint(index)
                showLog("$functionName: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getFaceArray(){
        val functionName = "getFaceArray"
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.faceQuantity.isSupport2Byte()) {
                    val faceArray = lockAccessUseCase.getFaceArray()
                    faceArray.forEachIndexed { index, value ->
                        if (value) {
                            lastFaceIndex = index
                        }
                    }
                    showLog("$functionName: $faceArray")
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getFace(){
        val functionName = "getFace"
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if (result.faceQuantity.isSupport2Byte()) {
                    val list = lockAccessUseCase.getFaceArray()
                    val indexIterable = list.mapIndexedNotNull { index, boolean -> if (boolean) index else null }
                    Timber.d("indexIterable: $indexIterable")
                    indexIterable.forEach { index ->
                        val getFace = lockAccessUseCase.getFace(index)
                        if (getFace.type == 3) {
                            showLog("$functionName[$index] is face: $getFace")
                        }
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun addFace(index:Int = lastFaceIndex + 1) {
        val functionName = "addFace"
        val isEnabled = true
        val name = "User $index"
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.faceQuantity.isSupport2Byte()) {
                    val isSuccess = lockAccessUseCase.addFace(index, isEnabled, scheduleType, name)
                    showLog("$functionName index: $index isEnabled: $isEnabled name: $name scheduleType: $scheduleType\nisSuccess: $isSuccess")
                    if(isSuccess) {
                        lastFaceIndex += 1
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                var userIndex = lastUserIndex
                if (userAbility == null){
                    showLog("$functionName need getUserAbility first, try again.")
                    getUserAbility()
                    return
                }
                val credentialResult = lockCredentialUseCase.getCredentialByUser(lastUserIndex)
                if (userAbility!!.faceCredentialCount.isSupport()) {
                    if (userAbility!!.faceCredentialCount == (credentialResult.credentialDetail?.filter { it.type == BleV3Lock.CredentialType.FACE.value && it.status != BleV3Lock.UserStatus.AVAILABLE.value }?.size ?: 0)) {
                        userIndex += 1
                    }
                    val result = lockCredentialUseCase.addCredentialFace(index, userIndex, currentCredential97Data.value.index)
                    showLog("$functionName index: $index userIndex: $userIndex\nresult: $result")
                    if(result){
                        lastFaceIndex += 1
                        lastCredentialIndex += 1
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun editFace(index:Int = lastFaceIndex) {
        val functionName = "editFace"
        val isEnabled = true
        val name = "User $index ed"
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.faceQuantity.isSupport2Byte()) {
                    val isSuccess = lockAccessUseCase.editFace(index, isEnabled, scheduleType, name)
                    showLog("$functionName index: $index isEnabled: $isEnabled name: $name scheduleType: $scheduleType\nisSuccess: $isSuccess")
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                val userIndex = lastUserIndex
                val result = lockCredentialUseCase.editCredentialFace(index, userIndex, currentCredential97Data.value.index)
                showLog("$functionName index: $index userIndex: $userIndex\nresult: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun deleteFace(index: Int = lastFaceIndex) {
        val functionName = "deleteFace"
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.faceQuantity.isSupport2Byte()) {
                    val isSuccess = lockAccessUseCase.deleteFace(index)
                    showLog("$functionName[$index]\nisSuccess: $isSuccess")
                    if(isSuccess){
                        lastFaceIndex -= 1
                    }
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                val result = lockCredentialUseCase.deleteCredential(index)
                showLog("$functionName index: $index result: $result")
                if(result){
                    lastFaceIndex -= 1
                    lastCredentialIndex -= 1
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun deviceGetFace(){
        val functionName = "deviceGetFace"
        val index = lastFaceIndex + 2
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                if(result.faceQuantity.isSupport2Byte()) {
                    val isSuccess = lockAccessUseCase.deviceGetFace(index)
                    showLog("$functionName: $isSuccess")
                } else {
                    throw LockStatusException.LockFunctionNotSupportException()
                }
            }
            is DeviceStatus.EightTwo -> {
                val result = lockCredentialUseCase.deviceGetCredentialFace(index)
                showLog("$functionName: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun deviceExitAccess(accessType: Int, index:Int) {
        val functionName = "deviceExitAccess"
        when(accessType){
            Access.Type.CARD.value -> {
                val result = lockAccessUseCase.deviceExitAccessCard(index)
                Timber.d("$functionName: $result\n")
            }
            Access.Type.FINGERPRINT.value -> {
                val result = lockAccessUseCase.deviceExitFingerprint(index)
                Timber.d("$functionName: $result\n")
            }
            Access.Type.FINGERPRINT.value -> {
                val result = lockAccessUseCase.deviceExitFace(index)
                Timber.d("$functionName: $result\n")
            }
        }
    }

    private suspend fun deviceExitCredential(accessType: Int, index:Int) {
        val functionName = "deviceExitCredential"
        when(accessType){
            BleV3Lock.CredentialType.RFID.value -> {
                val result = lockCredentialUseCase.deviceExitCredentialCard(index)
                Timber.d("$functionName: $result\n")
            }
            BleV3Lock.CredentialType.FINGERPRINT.value-> {
                val result = lockCredentialUseCase.deviceExitCredentialFingerprint(index)
                Timber.d("$functionName: $result\n")
            }
            BleV3Lock.CredentialType.FACE.value -> {
                val result = lockCredentialUseCase.deviceExitCredentialFace(index)
                Timber.d("$functionName: $result\n")
            }
        }
    }

    private suspend fun getEventQuantity(){
        val functionName = "getEventQuantity"
        val result = lockEventLogUseCase.getEventQuantity()
        showLog("$functionName result: $result")
    }

    private suspend fun getEvent(){
        val functionName = "getEvent"
        val result = lockEventLogUseCase.getEventQuantity()
        for(index in 0 until result){
            val eventLog = lockEventLogUseCase.getEvent(index)
            lastEventLogIndex = index
            showLog("$functionName index[$index]\neventLog: $eventLog")
        }
    }

    private suspend fun deleteEvent(index: Int = lastEventLogIndex){
        val functionName = "deleteEvent"
        val result = lockEventLogUseCase.deleteEvent(index)
        if(result){
            lastEventLogIndex -= 1
        }
        showLog("$functionName index[$index]\nresult: $result")
    }

    private suspend fun getLockSupportedUnlockTypes() {
        val functionName = "getLockSupportedUnlockTypes"
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                val result = lockUtilityUseCase.getLockSupportedUnlockTypes()
                showLog("$functionName result: $result")
                // filter not support function
                setSupportTaskList(supportedUnlockType = result)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getUserAbility() {
        val functionName = "getUserAbility"
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                val result = lockUserUseCase.getUserAbility()
                showLog("$functionName result: $result")
                userAbility = result
                // filter not support function
                setSupportTaskList(userAbility = result)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getUserCount() {
        val functionName = "getUserCount"
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                val result = lockUserUseCase.getUserCount()
                showLog("$functionName result: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun isMatterDevice() {
        val functionName = "isMatterDevice"
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                val result = lockUserUseCase.isMatterDevice()
                showLog("$functionName result: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getUserArray() {
        val functionName = "getUserArray"
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                val result = lockUserUseCase.getUserArray()
                result.forEachIndexed { index, value ->
                    if (value) {
                        lastUserIndex = index
                    }
                }
                showLog("$functionName result: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getUser() {
        val functionName = "getUser"
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                val list = lockUserUseCase.getUserArray()
                val indexIterable = list.mapIndexedNotNull { index, boolean -> if (boolean && index != 0) index else null }
                Timber.d("indexIterable: $indexIterable")
                indexIterable.forEach { index ->
                    val user = lockUserUseCase.getUser(index)
                    showLog("$functionName[$index] result: $user")
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun addUser(index:Int = lastUserIndex + 1) {
        val functionName = "addUser"
        val name = "User $index"
        val userStatus = BleV3Lock.UserStatus.OCCUPIED_ENABLED.value
        val userType = BleV3Lock.UserType.UNRESTRICTED.value
        val credentialRule = BleV3Lock.CredentialRule.SINGLE.value
        val weekDaySchedule = mutableListOf<BleV3Lock.WeekDaySchedule>()
        val yearDaySchedule = mutableListOf<BleV3Lock.YearDaySchedule>()
        if (userAbility == null){
            showLog("$functionName need getUserAbility first, try again.")
            getUserAbility()
            return
        }
        val timestamp = System.currentTimeMillis()
        if(userAbility!!.isMatter) {
            for (i in 0 until userAbility!!.weekDayScheduleCount){
                weekDaySchedule.add(BleV3Lock.WeekDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, enumValues<BleV3Lock.DaysMaskMap>()[i].value, 8, 0, 18, 0))
            }
            for (i in 0 until userAbility!!.yearDayScheduleCount){
                yearDaySchedule.add(BleV3Lock.YearDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, timestamp, timestamp))
            }
        } else {
            for (i in 0 until userAbility!!.weekDayScheduleCount){
                weekDaySchedule.add(BleV3Lock.WeekDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, enumValues<BleV3Lock.DaysMaskMap>()[i].value, 8, 0, 18, 0))
            }
            for (i in 0 until userAbility!!.yearDayScheduleCount){
                yearDaySchedule.add(BleV3Lock.YearDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, timestamp, timestamp))
            }
        }
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                val result = lockUserUseCase.addUser(index, name, userStatus, userType, credentialRule, weekDaySchedule, yearDaySchedule)
                showLog("$functionName name: $name index: $index userStatus: $userStatus userType: $userType credentialRule: $credentialRule weekDaySchedule: $weekDaySchedule yearDaySchedule: $yearDaySchedule\nresult: $result")
                if(result){
                    lastUserIndex = index
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun editUser(index:Int = lastUserIndex) {
        val functionName = "editUser"
        val name = "User $index ed"
        val userStatus = BleV3Lock.UserStatus.OCCUPIED_ENABLED.value
        val userType = BleV3Lock.UserType.DISPOSABLE.value
        val credentialRule = BleV3Lock.CredentialRule.SINGLE.value
        val weekDaySchedule = mutableListOf<BleV3Lock.WeekDaySchedule>()
        val yearDaySchedule = mutableListOf<BleV3Lock.YearDaySchedule>()
        if (userAbility == null){
            showLog("$functionName need getUserAbility first, try again.")
            getUserAbility()
            return
        }
        val timestamp = System.currentTimeMillis()
        if(userAbility!!.isMatter) {
            for (i in 0 until userAbility!!.weekDayScheduleCount){
                weekDaySchedule.add(BleV3Lock.WeekDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, enumValues<BleV3Lock.DaysMaskMap>()[i].value, 8, 0, 18, 0))
            }
            for (i in 0 until userAbility!!.yearDayScheduleCount){
                yearDaySchedule.add(BleV3Lock.YearDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, timestamp, timestamp))
            }
        } else {
            for (i in 0 until userAbility!!.weekDayScheduleCount){
                weekDaySchedule.add(BleV3Lock.WeekDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, enumValues<BleV3Lock.DaysMaskMap>()[i].value, 8, 0, 18, 0))
            }
            for (i in 0 until userAbility!!.yearDayScheduleCount){
                yearDaySchedule.add(BleV3Lock.YearDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, timestamp, timestamp))
            }
        }

        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                val result = lockUserUseCase.editUser(index, name, userStatus, userType, credentialRule, weekDaySchedule, yearDaySchedule)
                showLog("$functionName name: $name index: $index userStatus: $userStatus userType: $userType credentialRule: $credentialRule weekDaySchedule: $weekDaySchedule yearDaySchedule: $yearDaySchedule\nresult: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun deleteUser(index: Int = lastUserIndex){
        val functionName = "deleteUser"
        when(_currentDeviceStatus) {
            is DeviceStatus.EightTwo -> {
                val result = lockUserUseCase.deleteUser(index)
                showLog("$functionName result: $result")
                if(result){
                    lastUserIndex -= 1
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getCredentialArray() {
        val functionName = "getCredentialArray"
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                val result = lockCredentialUseCase.getCredentialArray()
                result.forEachIndexed { index, value ->
                    if (value) {
                        lastCredentialIndex = index
                    }
                }
                showLog("$functionName result: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getCredential() {
        val functionName = "getCredential"
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                val list = lockCredentialUseCase.getCredentialArray()
                val indexIterable = list.mapIndexedNotNull { index, boolean -> if (boolean && index != 0) index else null }
                Timber.d("indexIterable: $indexIterable")
                indexIterable.forEach { index ->
                    lastCredentialIndex = index
                    val credential = lockCredentialUseCase.getCredentialByCredential(index)
                    showLog("$functionName credential[$index]: $credential")
                    if(credential.type == BleV3Lock.CredentialType.PIN.value){
                        lastCodeCardIndex = index
                    }
                    if(credential.type == BleV3Lock.CredentialType.RFID.value){
                        lastCardIndex = index
                        lastCodeCardIndex = index
                    }
                    if(credential.type == BleV3Lock.CredentialType.FINGERPRINT.value){
                        lastFingerprintIndex = index
                    }
                    if(credential.type == BleV3Lock.CredentialType.FACE.value){
                        lastFaceIndex = index
                    }
                }
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getCredentialByCredential() {
        val functionName = "getCredentialByCredential"
        val index = lastCredentialIndex
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                val result = lockCredentialUseCase.getCredentialByCredential(index)
                showLog("$functionName credential[$index] result: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getCredentialByUser() {
        val functionName = "getCredentialByUser"
        val index = lastUserIndex
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                val result = lockCredentialUseCase.getCredentialByUser(index)
                showLog("$functionName user[$index] result: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getUserCredentialHash() {
        val functionName = "getUserCredentialHash"
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                val result = lockDataUseCase.getUserCredentialHash()
                showLog("$functionName result: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun getBleUserHash() {
        val functionName = "getBleUserHash"
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                val result = lockDataUseCase.getBleUserHash()
                showLog("$functionName result: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private suspend fun setAllDataSynced() {
        val functionName = "setAllDataSynced"
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                val result = lockDataUseCase.setAllDataSynced()
                showLog("$functionName result: $result")
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun collectWifiList(){
        val functionName = ::collectWifiList.name
        collectWifiListJob?.cancel()
        collectWifiListJob = lockWifiUseCase.collectWifiList()
            .catch { e -> showLog("$functionName exception $e") }
            .onEach { wifi ->
                when (wifi) {
                    WifiList.End -> {
                        _uiState.update { it.copy(isLoading = false) }
                        collectWifiListJob?.cancel()
                        showLog("$functionName end")
                        if(scanWifiJob != null){
                            scanWifiJob?.cancel()
                            scanWifiJob = null
                        }
                    }
                    is WifiList.Wifi -> {
                        showLog("$functionName result:\nssid: ${wifi.ssid} needPassword: ${wifi.needPassword}")
                    }
                }
            }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun scanWifi(): Job{
        val functionName = ::scanWifi.name
        if (scanWifiJob != null) return Job()
        val job = flow { emit(lockWifiUseCase.scanWifi()) }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion {
                delay(10000)
                if(uiState.value.isLoading){
                    _uiState.update { it.copy(isLoading = false) }
                }
                if(scanWifiJob != null){
                    scanWifiJob?.cancel()
                    scanWifiJob = null
                }
            }
            .catch { e -> showLog("$functionName exception $e") }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
        scanWifiJob = job
        return job
    }

    private fun connectToWifi(ssid: String, password: String): Job{
        val functionName = ::connectToWifi.name
        return viewModelScope.launch {
            if (isCollectingConnectToWifiState) return@launch

            connectToWifiJob?.cancel()
            val listenerJob = lockWifiUseCase
                .collectConnectToWifiState()
                .flowOn(Dispatchers.IO)
                .onStart { isCollectingConnectToWifiState = true }
                .onCompletion { isCollectingConnectToWifiState = false }
                .onEach { wifiConnectState ->
                    val progressMessage =
                        when (wifiConnectState) {
                            WifiConnectState.ConnectWifiSuccess -> "Wifi connected, connecting to cloud service..."
                            WifiConnectState.ConnectWifiFail -> "Connect to Wi-Fi failed."
                            WifiConnectState.ConnectAwsSuccess -> "Cloud service connected, syncing data..."
                            WifiConnectState.ConnectCloudSuccess -> "Data sync completed, bluetooth connection disconnected."
                            WifiConnectState.Failed -> "Unknown error."
                        }
                    showLog(progressMessage)

                    if (wifiConnectState == WifiConnectState.ConnectCloudSuccess) {
                        isConnectingToWifi = false
                        isWifiConnected = true
                        connectToWifiJob?.cancel()
                        _uiState.update { it.copy(isLoading = false) }
                    } else if (wifiConnectState == WifiConnectState.ConnectWifiFail) {
                        isConnectingToWifi = false
                        isWifiConnected = false
                        connectToWifiJob?.cancel()
                        Timber.e("CWifiFail")
                        showLog("$functionName failed, because not had provisionTicket.")
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
                .catch {
                    Timber.e(it)
                    if (it.message?.contains("Disconnected") == false)
                        Timber.e("CWifi listener exception $it")
                    showLog("$functionName failed. $it")
                    isConnectingToWifi = false
                    isWifiConnected = false
                    connectToWifiJob?.cancel()
                    _uiState.update { state -> state.copy(isLoading = false) }
                }
                .launchIn(this)

            connectToWifiJob = listenerJob

            launch(Dispatchers.IO) {
                flow { emit(lockWifiUseCase.connectToWifi(ssid, password)) }
                    .onStart {
                        isConnectingToWifi = true
                        _uiState.update { it.copy(isLoading = true) }
                    }
                    .onEach {
                        showLog("connect wifi ssid: $ssid password: $password")
                    }
                    .catch {
                        Timber.e("CWifi sender exception $it")
                        showLog("$functionName failed. $it")
                    }
                    .collect()
            }

            // 等待監聽器結束（成功或失敗會呼叫 cancel 或完成）
            listenerJob.join()
        }
    }

    private fun collectWifiList3(){
        val functionName = ::collectWifiList3.name
        collectWifiListJob?.cancel()
        collectWifiListJob = lockWifiUseCase.collectWifiList3()
            .catch { e -> showLog("$functionName exception $e") }
            .onEach { wifi ->
                when (wifi) {
                    WifiList.End -> {
                        _uiState.update { it.copy(isLoading = false) }
                        collectWifiListJob?.cancel()
                        showLog("$functionName end")
                        if(scanWifiJob != null){
                            scanWifiJob?.cancel()
                            scanWifiJob = null
                        }
                    }
                    is WifiList.Wifi -> {
                        showLog("$functionName result:\nssid: ${wifi.ssid} needPassword: ${wifi.needPassword}")
                    }
                }
            }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun scanWifi3(): Job{
        val functionName = ::scanWifi3.name
        if (scanWifiJob != null) return Job()
        val job = flow { emit(lockWifiUseCase.scanWifi3()) }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion {
                delay(10000)
                if(uiState.value.isLoading){
                    _uiState.update { it.copy(isLoading = false) }
                }
                if(scanWifiJob != null){
                    scanWifiJob?.cancel()
                    scanWifiJob = null
                }
            }
            .catch { e -> showLog("$functionName exception $e") }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
        scanWifiJob = job
        return job
    }

    private fun connectToWifi3(ssid: String, password: String): Job{
        val functionName = ::connectToWifi3.name
        return viewModelScope.launch {
            if (isCollectingConnectToWifiState) return@launch

            connectToWifiJob?.cancel()
            val listenerJob = lockWifiUseCase.collectConnectToWifiState3()
            .flowOn(Dispatchers.IO)
            .onStart { isCollectingConnectToWifiState = true }
            .onCompletion { isCollectingConnectToWifiState = false }
            .onEach { wifiConnectState ->
                val progressMessage =
                    when (wifiConnectState) {
                        WifiConnectState.ConnectWifiSuccess -> "Wifi connected, connecting to cloud service..."
                        WifiConnectState.ConnectWifiFail -> "Connect to Wi-Fi failed."
                        WifiConnectState.ConnectAwsSuccess -> "Cloud service connected, syncing data..."
                        WifiConnectState.ConnectCloudSuccess -> "Data sync completed, bluetooth connection disconnected."
                        WifiConnectState.Failed -> "Unknown error."
                    }
                showLog(progressMessage)

                if (wifiConnectState == WifiConnectState.ConnectCloudSuccess) {
                    isConnectingToWifi = false
                    isWifiConnected = true
                    connectToWifiJob?.cancel()
                    _uiState.update { it.copy(isLoading = false) }
                } else if (wifiConnectState == WifiConnectState.ConnectWifiFail) {
                    isConnectingToWifi = false
                    isWifiConnected = false
                    connectToWifiJob?.cancel()
                    Timber.e("CWifiFail")
                    showLog("$functionName failed, because not had provisionTicket.")
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
            .catch {
                Timber.e(it)
                if (it.message?.contains("Disconnected") == false)
                    Timber.e("CWifi listener exception $it")
                showLog("$functionName failed. $it")
                isConnectingToWifi = false
                isWifiConnected = false
                connectToWifiJob?.cancel()
                _uiState.update { state -> state.copy(isLoading = false) }
            }
            .launchIn(this)

            connectToWifiJob = listenerJob

            launch(Dispatchers.IO) {
                flow { emit(lockWifiUseCase.connectToWifi3(ssid, password)) }
                    .onStart {
                        isConnectingToWifi = true
                        _uiState.update { it.copy(isLoading = true) }
                    }
                    .onEach {
                        showLog("connect wifi ssid: $ssid password: $password")
                    }
                    .catch {
                        Timber.e("CWifi sender exception $it")
                        showLog("$functionName failed. $it")
                    }
                    .collect()
            }

            // 等待監聽器結束（成功或失敗會呼叫 cancel 或完成）
            listenerJob.join()
        }
    }

    private suspend fun setOTACancel(target: Int) {
        val functionName = "setOTACancel"
        val result = lockOTAUseCase.setOTACancel(target)
        showLog("$functionName result: $result")
    }

    private fun disconnect() {
        val functionName = ::disconnect.name
        if(isOtaWorkerExist()){
            WorkerManager.cancel(application, WorkerNames.OTA_WORKER)
        }
        statefulConnection.disconnect()
        _bleConnectionStateListener?.cancel()
        _bleSunionBleNotificationListener?.cancel()
        isCheckDeviceStatus = false
        isCheckLockConfig = false
        isCheckUnLockType = false
        if(currentQrCodeContent?.isDeviceUuid() == true) {
            currentConnectMacAddress = null
        }
        _uiState.update { it.copy(isLoading = false, isConnectedWithLock = false, taskList = BleDeviceFeature.initTaskList) }
        showLog(functionName)
        if(lockConnectionInfo == null){
            showLog("Please scan QR code to get lock connection information.")
        }
    }

    private fun isOtaWorkerExist() : Boolean {
        return WorkerManager.isEnqueuedOrRunning(application, WorkerNames.OTA_WORKER)
    }

    fun setQRCodeContent(content: String) {
        val functionName = ::setQRCodeContent.name
        currentQrCodeContent = content
        Timber.d("$functionName: $content ${content.isDeviceUuid()}")

        viewModelScope.launch {
            runWithLoading(functionName) {
                if (content.isDeviceUuid()) {
                    _uiEvent.emit(UiEvent.Complete)
                    currentProductionGetResponse = deviceApiRepository.getProduction(code = content)
                    startBleScan(content, currentProductionGetResponse!!)
                } else {
                    val qrCodeContent =
                        runCatching {
                            lockQRCodeUseCase.parseQRCodeContent(
                                BuildConfig.BARCODE_KEY,
                                content
                            )
                        }.getOrNull()
                            ?: runCatching {
                                lockQRCodeUseCase.parseWifiQRCodeContent(
                                    BuildConfig.BARCODE_KEY,
                                    content
                                )
                            }.getOrNull()

                    if (qrCodeContent == null) {
                        showLog("Unknown QR-Code.")
                        _uiEvent.emit(UiEvent.Complete)
                        return@runWithLoading
                    }

                    Timber.d("qrCodeContent: $qrCodeContent")
                    _lockConnectionInfo = LockConnectionInfo.from(qrCodeContent)
                    Timber.d("lockConnectionInfo: $lockConnectionInfo")
                    showLog("Lock connection information:", true)
                    showLog("macAddress: ${lockConnectionInfo!!.macAddress}")
                    showLog("oneTimeToken: ${lockConnectionInfo!!.oneTimeToken}")
                    showLog("keyOne: ${lockConnectionInfo!!.keyOne}")
                    showLog("model: ${lockConnectionInfo!!.model}")
                    showLog("Please execute Connect to pair with lock.")
                    _uiEvent.emit(UiEvent.Complete)
                    _uiState.update { it.copy(btnEnabled = true) }
                }
            }
        }
    }

    private fun showLog(msg: String, isClear: Boolean = false) {
        if (isClear)
            _logList.value.clear()
        _logList.update {
            _logList.value.toMutableList().apply { this.add("$msg\n") }
        }
    }

    fun closeMessageDialog() {
        _uiState.update { it.copy(message = "") }
    }

    fun closeBluetoothEnableDialog() {
        _uiState.update { it.copy(shouldShowBluetoothEnableDialog = false) }
    }

    fun handleFileSelection(fileUri: Uri?) {
        val functionName = ::handleFileSelection.name
        if (fileUri != null) {
            currentFileUri = fileUri
            viewModelScope.launch(Dispatchers.IO) {
                val fileLength = getFileLength(fileUri)
                Timber.d("$functionName: $fileLength")
            }
        }
    }

    @SuppressLint("Range")
    suspend fun getFileLength(fileUri: Uri): Int {
        return withContext(Dispatchers.IO) {
            val contentResolver: ContentResolver = application.contentResolver
            val cursor = contentResolver.query(fileUri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayName = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    val size = it.getInt(sizeIndex)
                    showLog("File name: $displayName size: $size bytes")
                    fileSize = size
                    return@withContext if (sizeIndex != -1) {
                        size
                    } else {
                        -1
                    }
                }
            }
            return@withContext -1
        }
    }

    private fun otaUpdate(target: Int, signature:String) {
        val checkResult = when (signature) {
            signatureV005 -> {
                fileCheck(hash256V005)
            }
            else -> {
                false
            }
        }
        if (currentFileUri != null && checkResult) {
            val contentResolver: ContentResolver = application.contentResolver
            val inputStream: InputStream? = contentResolver.openInputStream(currentFileUri!!)

            if (inputStream == null) {
                Timber.d("Failed to open input stream.")
                return
            }

            val chunkSize = 128
            val buffer = ByteArray(chunkSize)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    var bytesRead: Int
                    lockOTAUseCase.setOTAStart(target, fileSize)

                    inputStream.use { inputStream ->
                        var blockNumber = 0
                        bytesRead = inputStream.read(buffer)

                        showLog("Send start 0 / $fileSize = 0%")
                        while (bytesRead != -1) {

                            val result = lockOTAUseCase.transferOTAData(
                                blockNumber * chunkSize,
                                buffer.take(bytesRead).toByteArray()
                            )
                            Timber.d("end: $result")

                            blockNumber++
                            showLog("Sending ${blockNumber * chunkSize} / $fileSize: ${(blockNumber * chunkSize * 100 / fileSize) } %")
                            bytesRead = inputStream.read(buffer)
                        }
                        showLog("Send end ${blockNumber * chunkSize} / $fileSize = 100%")
                        val result = lockOTAUseCase.setOTAFinish(target, fileSize, ivString, signature)
                        Timber.d("end: $result")
                    }

                    inputStream.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (uiState.value.isConnectedWithLock) {
                        lockOTAUseCase.setOTACancel(target)
                    }
                }
            }
        }
    }

    private fun fileCheck(checkHash:String): Boolean {
        val functionName = ::fileCheck.name
        if (currentFileUri != null) {
            val contentResolver: ContentResolver = application.contentResolver
            val inputStream: InputStream? = contentResolver.openInputStream(currentFileUri!!)

            if (inputStream == null) {
                Timber.d("$functionName: Failed to open input stream.")
                return false
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val byteArray = ByteArray(1024)
            var bytesCount: Int

            while (inputStream.read(byteArray).also { bytesCount = it } != -1) {
                digest.update(byteArray, 0, bytesCount)
            }

            inputStream.close()
            val bytes = digest.digest()

            Timber.d("$functionName: ${bytes.toHexString()}")

            return bytes.toHexString() == checkHash
        }
        showLog("Not choose OTA file")
        return false
    }

    private suspend fun startBleScan(uuid: String, productionGetResponse: ProductionGetResponse, isReconnect: Boolean = false) {
        showLog("Scan and wait for find device...")
        // 建立一個信號燈，用來控制這段 suspend 程式何時結束
        val scanTaskSignal = CompletableDeferred<Unit>()
        val scanDisposable = bleScanUseCase.scanUuid(uuid = uuid)
            .timeout(30, TimeUnit.SECONDS)
            .take(1)
            .subscribe(
                { scanResult ->
                    // 處理掃描到的 BLE 裝置
                    currentConnectMacAddress = scanResult.bleDevice.macAddress
                    Timber.d("scanResult: ${scanResult.bleDevice.macAddress}")
                },
                { throwable ->
                    // 處理錯誤
                    Timber.e("Scan error: $throwable")
                    showLog("Ble Scan error: Can't not get lock info with $uuid with 30 seconds timeout.")
                    // 通知任務失敗並結束
                    scanTaskSignal.completeExceptionally(throwable)
                },
                {
                    // 處理掃描完成事件
                    Timber.d("Scan complete")
                    if(productionGetResponse.address.isNullOrBlank() && currentConnectMacAddress.isNullOrBlank()){
                        showLog("Production api or scan ble to get mac address failed.")
                    }
                    viewModelScope.launch {
                        if (isReconnect) {
                            _lockConnectionInfo =
                                lockConnectionInfo!!.copy(macAddress = currentConnectMacAddress!!)
                            try {
                                connect() // 這裡會繼續掛起，直到連線成功/失敗
                                scanTaskSignal.complete(Unit) // 連線成功後，釋放 startBleScan
                            } catch (e: Exception) {
                                scanTaskSignal.completeExceptionally(e)
                            }
                        } else {
                            _lockConnectionInfo = LockConnectionInfo.from(
                                productionGetResponse,
                                currentConnectMacAddress
                            )
                            Timber.d("lockConnectionInfo: $lockConnectionInfo")
                            showLog("Lock connection information:", true)
                            showLog("macAddress: ${lockConnectionInfo!!.macAddress}")
                            showLog("oneTimeToken: ${lockConnectionInfo!!.oneTimeToken}")
                            showLog("keyOne: ${lockConnectionInfo!!.keyOne}")
                            showLog("model: ${lockConnectionInfo!!.model}")
                            showLog("Please execute Connect to pair with lock.")
                            _uiState.update { it.copy(btnEnabled = true) }
                            scanTaskSignal.complete(Unit)
                        }
                    }
                }
            )
        try {
            //直到 scanTaskSignal 被 complete
            scanTaskSignal.await()
        } finally {
            // 如果協程被取消，確保 RxJava 訂閱也被釋放
            scanDisposable.dispose()
        }
    }

    private fun setModelSupportTaskList(model: String) {
        val functionName = ::setModelSupportTaskList.name
        val supportedVersions = BleDeviceFeature.modelVersions[model] ?: emptySet()
        val supportTaskList = BleDeviceFeature.taskList.filter { task ->
            task.third.intersect(supportedVersions).isNotEmpty()
        }.toMutableList()
        when(model) {
            "KD0" -> {
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleGuidingCode }
            }
            "TLR0" -> {
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ScanWifi }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ConnectToWifi }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.SetOTAUpdate }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.SetOTACancel }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.FactoryResetNoAdmin }
            }
            "TNRFp00", "KD01", "TNRFp01", "KDFa01", "TD01", "KDM01", "KDFp01" -> {
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ScanWifi }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ConnectToWifi }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.FactoryResetNoAdmin }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.TogglePlugState }
                if(model == "TNRFp00" || model == "TNRFp01"){
                    adminCode = "12345678"
                }
            }
            "KDW01", "TDW01", "TLRW01" -> {
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.FactoryResetNoAdmin }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.TogglePlugState }
            }
            "PWG01" -> {
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.FactoryResetNoAdmin }
            }
        }
        Timber.d("$functionName: $supportTaskList")
        _uiState.update { it.copy(taskList = supportTaskList.toTypedArray()) }
    }

    private fun setSupportTaskList(deviceStatus: DeviceStatus? = null, lockConfig: LockConfig? = null, supportedUnlockType: BleV2Lock.SupportedUnlockType? = null, userAbility: BleV3Lock.UserAbility? = null) {
        val functionName = ::setSupportTaskList.name
        val supportTaskList = uiState.value.taskList.toMutableList()
        if(deviceStatus != null && !isCheckDeviceStatus) {
            when (deviceStatus) {
                is DeviceStatus.A2 -> {
                    if (deviceStatus.direction.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DetermineLockDirection }
                    }
                    if (deviceStatus.vacationMode.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleVacationMode }
                    }
                    if (deviceStatus.lockState.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleLockState }
                    }
                    if (deviceStatus.securityBolt.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleSecurityBolt }
                    }
                    showLog("Please execute Get lock config and Get lock supported unlock types to update support task list after admin code already exist.")
                }
                is DeviceStatus.EightTwo -> {
                    if (deviceStatus.direction.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DetermineLockDirection }
                    }
                    if (deviceStatus.vacationMode.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleVacationMode }
                    }
                    if (deviceStatus.lockState.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleLockState }
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.AutoUnlockToggleLockState }
                    }
                    if (deviceStatus.securityBolt.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleSecurityBolt }
                    }
                    showLog("Please execute Get lock config and Get user ability to update support task list after admin code already exist.")
                }
                else -> {}
            }
            isCheckDeviceStatus = true
            Timber.d("$functionName: $supportTaskList")
            _uiState.update { it.copy(taskList = supportTaskList.toTypedArray()) }
        }
        if(lockConfig != null && !isCheckLockConfig) {
            when (lockConfig) {
                is LockConfig.A0 -> {
                    if (lockConfig.guidingCode.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleGuidingCode }
                    }
                    if (lockConfig.virtualCode.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleVirtualCode }
                    }
                    if (lockConfig.twoFA.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleTwoFA }
                    }
                    if (lockConfig.autoLock.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleAutoLock }
                    }
                    if (lockConfig.operatingSound.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleOperatingSound }
                    }
                    if (lockConfig.soundType.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleKeyPressBeep }
                    }
                    if (lockConfig.showFastTrackMode.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleShowFastTrackMode }
                    }
                }
                is LockConfig.Eighty -> {
                    if (lockConfig.guidingCode.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleGuidingCode }
                    }
                    if (lockConfig.virtualCode.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleVirtualCode }
                    }
                    if (lockConfig.twoFA.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleTwoFA }
                    }
                    if (lockConfig.autoLock.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleAutoLock }
                    }
                    if (lockConfig.operatingSound.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleOperatingSound }
                    }
                    if (lockConfig.soundType.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleKeyPressBeep }
                    }
                    if (lockConfig.showFastTrackMode.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleShowFastTrackMode }
                    }
                    if (lockConfig.sabbathMode.isNotSupport()) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ToggleSabbathMode }
                    }
                    if (lockConfig.phoneticLanguage.isNotSupport() || lockConfig.supportPhoneticLanguage == BleV3Lock.SupportPhoneticLanguage.NOT_SUPPORT.value) {
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.TogglePhoneticLanguage }
                    }
                }
                else -> {}
            }
            isCheckLockConfig = true
            Timber.d("$functionName: $supportTaskList")
            _uiState.update { it.copy(taskList = supportTaskList.toTypedArray()) }
        }
        if(supportedUnlockType != null && !isCheckUnLockType) {
            if(supportedUnlockType.accessCodeQuantity.isNotSupport2Byte()){
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetAccessCodeArray }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetAccessCode }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.AddAccessCode }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.EditAccessCode }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DeleteAccessCode }
            }
            if(supportedUnlockType.accessCardQuantity.isNotSupport2Byte()){
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetAccessCardArray }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetAccessCard }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.AddAccessCard }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.EditAccessCard }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DeleteAccessCard }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DeviceGetAccessCard }
            }
            if(supportedUnlockType.fingerprintQuantity.isNotSupport2Byte()){
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetFingerprintArray }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetFingerprint }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.AddFingerprint }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.EditFingerprint }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DeleteFingerprint }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DeviceGetFingerprint }
            }
            if(supportedUnlockType.faceQuantity.isNotSupport2Byte()){
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetFaceArray }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetFace }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.AddFace }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.EditFace }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DeleteFace }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DeviceGetFace }
            }
            isCheckUnLockType = true
            Timber.d("$functionName: $supportTaskList")
            _uiState.update { it.copy(taskList = supportTaskList.toTypedArray()) }
        }
        if(userAbility != null && !isCheckUnLockType) {
            if(userAbility.codeCredentialCount.isNotSupport()){
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetAccessCodeArray }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetAccessCode }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.AddAccessCode }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.EditAccessCode }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DeleteAccessCode }
            }
            if(userAbility.cardCredentialCount.isNotSupport()){
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetAccessCardArray }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetAccessCard }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.AddAccessCard }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.EditAccessCard }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DeleteAccessCard }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DeviceGetAccessCard }
            }
            if(userAbility.fpCredentialCount.isNotSupport()){
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetFingerprintArray }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetFingerprint }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.AddFingerprint }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.EditFingerprint }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DeleteFingerprint }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DeviceGetFingerprint }
            }
            if(userAbility.faceCredentialCount.isNotSupport()){
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetFaceArray }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.GetFace }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.AddFace }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.EditFace }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DeleteFace }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.DeviceGetFace }
            }
            isCheckUnLockType = true
            Timber.d("$functionName: $supportTaskList")
            _uiState.update { it.copy(taskList = supportTaskList.toTypedArray()) }
        }
    }

    private fun backGroundOTAUpdate(){
        if(fileCheck(hash256V005)) {
            disconnect()
            startBackgroundTask(lockConnectionInfo)
        }
    }

    private fun startBackgroundTask(lockConnectionInfo: LockConnectionInfo?) {
        if(lockConnectionInfo == null){
            showLog("lockConnectionInfo is null")
            return
        }
        val gson = Gson()
        val inputData = workDataOf(
            "lockInfo" to gson.toJson(lockConnectionInfo),
            "fileUri" to currentFileUri.toString(),
            "iv" to ivString,
            "signature" to signatureV005,
            "hash256" to hash256V005
        )

        val request = OneTimeWorkRequestBuilder<OtaWorker>()
            .setInitialDelay(3, TimeUnit.SECONDS)
            .setInputData(inputData)
            .build()

        WorkerManager.enqueueUnique(application, WorkerNames.OTA_WORKER, request, ExistingWorkPolicy.REPLACE)
        showLog("OTA將於3秒後背景執行")
    }

    private suspend fun runWithLoading(
        functionName: String,
        block: suspend () -> Unit
    ) {
        try {
            _uiState.update { it.copy(isLoading = true) }
            block()
        } catch (e: LockStatusException.LockFunctionNotSupportException) {
            showLog("$functionName:此功能不支援，已忽略。\nException: $e")
        } catch (e: Exception) {
            showLog("$functionName exception $e")
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun showInputDialog() {
        var title: String? = null
        var message: String? = null
        var initialText = ""
        when(uiState.value.taskCode){
            BleDeviceFeature.TaskCode.SetLockTime -> {
                title = "請輸入鎖體時間"
                initialText = Instant.now().atZone(ZoneId.systemDefault()).toEpochSecond().toString()
            }
            BleDeviceFeature.TaskCode.SetLockName -> {
                title = "請輸入鎖體名稱"
                initialText = "New_Lock"
            }
            BleDeviceFeature.TaskCode.SetLockTimeZone -> {
                title = "請輸入鎖體時區"
                initialText = ZoneId.systemDefault().id
            }
            BleDeviceFeature.TaskCode.ToggleAutoLock -> {
                title = "請輸入自動上鎖時間"
                initialText = "10"
            }
            BleDeviceFeature.TaskCode.CreateAdminCode -> {
                title = "請輸入管理者密碼"
                initialText = "1234"
            }
            BleDeviceFeature.TaskCode.UpdateAdminCode -> {
                title = "請輸入新的管理者密碼"
                initialText = "1234"
            }
            BleDeviceFeature.TaskCode.EditToken -> {
                title = "請輸入編輯Token的Index"
                initialText = lastTokenIndex.toString()
            }
            BleDeviceFeature.TaskCode.DeleteToken -> {
                title = "請輸入想要移除Token的Index"
                initialText = lastTokenIndex.toString()
            }
            BleDeviceFeature.TaskCode.AddAccessCode -> {
                title = "請輸入想要新增Code的Index"
                initialText = lastCodeCardIndex.toString()
            }
            BleDeviceFeature.TaskCode.EditAccessCode -> {
                title = "請輸入想要編輯Code的Index"
                initialText = lastCodeIndex.toString()
            }
            BleDeviceFeature.TaskCode.DeleteAccessCode -> {
                title = "請輸入想要移除Code的Index"
                initialText = lastCodeIndex.toString()
            }
            BleDeviceFeature.TaskCode.AddAccessCard -> {
                title = "請輸入想要新增Card的Index"
                initialText = lastCodeCardIndex.toString()
            }
            BleDeviceFeature.TaskCode.EditAccessCard -> {
                title = "請輸入想要編輯Card的Index"
                initialText = lastCardIndex.toString()
            }
            BleDeviceFeature.TaskCode.DeleteAccessCard -> {
                title = "請輸入想要移除Card的Index"
                initialText = lastCardIndex.toString()
            }
            BleDeviceFeature.TaskCode.AddFingerprint -> {
                title = "請輸入想要新增Fingerprint的Index"
                initialText = lastFingerprintIndex.toString()
            }
            BleDeviceFeature.TaskCode.EditFingerprint -> {
                title = "請輸入想要編輯Fingerprint的Index"
                initialText = lastFingerprintIndex.toString()
            }
            BleDeviceFeature.TaskCode.DeleteFingerprint -> {
                title = "請輸入想要移除Fingerprint的Index"
                initialText = lastFingerprintIndex.toString()
            }
            BleDeviceFeature.TaskCode.AddFace -> {
                title = "請輸入想要新增Face的Index"
                initialText = lastFaceIndex.toString()
            }
            BleDeviceFeature.TaskCode.EditFace -> {
                title = "請輸入想要編輯Face的Index"
                initialText = lastFaceIndex.toString()
            }
            BleDeviceFeature.TaskCode.DeleteFace -> {
                title = "請輸入想要移除Face的Index"
                initialText = lastFaceIndex.toString()
            }
            BleDeviceFeature.TaskCode.DeleteEvent -> {
                title = "請輸入想要移除Event的筆數"
                initialText = lastEventLogIndex.toString()
            }
            BleDeviceFeature.TaskCode.AddUser -> {
                title = "請輸入想要新增User的Index"
                initialText = lastUserIndex.toString()
            }
            BleDeviceFeature.TaskCode.EditUser -> {
                title = "請輸入想要編輯User的Index"
                initialText = lastUserIndex.toString()
            }
            BleDeviceFeature.TaskCode.DeleteUser -> {
                title = "請輸入想要移除User的Index"
                initialText = lastUserIndex.toString()
            }
            else -> {

            }
        }

        _uiState.update { it.copy(isShowInputDialog = true, inputDialogTitle = title, inputDialogMessage = message, inputDialogInitialText = initialText) }
    }

    fun closeInputDialog() {
        _uiState.update { it.copy(isShowInputDialog = false) }
    }

    fun setInputDialogContent(content: String): Boolean {
        val intContent = content.toIntOrNull()
        val longContent = content.toLongOrNull()
        if(content.isNotBlank()) {
            showLog("輸入內容: $content")
        }
        val functionName = "executeTask"
        viewModelScope.launch {
            runWithLoading(functionName) {
                when (uiState.value.taskCode) {
                    BleDeviceFeature.TaskCode.SetLockTime -> {
                        if (longContent != null) {
                            setLockTime(longContent)
                        }
                    }

                    BleDeviceFeature.TaskCode.SetLockName -> {
                        setLockName(content)
                    }

                    BleDeviceFeature.TaskCode.SetLockTimeZone -> {
                        setLockTimeZone(content)
                    }

                    BleDeviceFeature.TaskCode.ToggleAutoLock -> {
                        if (intContent != null) {
                            toggleAutoLock(intContent)
                        }
                    }

                    BleDeviceFeature.TaskCode.CreateAdminCode -> {
                        createAdminCode(content)
                    }

                    BleDeviceFeature.TaskCode.UpdateAdminCode -> {
                        updateAdminCode(adminCode, content)
                    }
                    // Edit Token
                    BleDeviceFeature.TaskCode.EditToken -> {
                        if (intContent != null) {
                            editToken(intContent, "A", "User $intContent ed")
                        }
                    }

                    BleDeviceFeature.TaskCode.DeleteToken -> {
                        if (intContent != null) {
                            deleteToken(intContent)
                        }
                    }

                    BleDeviceFeature.TaskCode.DeleteAccessCode -> {
                        if (intContent != null) {
                            deleteAccessCode(intContent)
                        }
                    }

                    BleDeviceFeature.TaskCode.AddAccessCard -> {
                        if (intContent != null) {
                            addAccessCard(index = intContent)
                        }
                    }

                    BleDeviceFeature.TaskCode.EditAccessCard -> {
                        if (intContent != null) {
                            editAccessCard(index = intContent)
                        }
                    }

                    BleDeviceFeature.TaskCode.DeleteAccessCard -> {
                        if (intContent != null) {
                            deleteAccessCard(intContent)
                        }
                    }

                    BleDeviceFeature.TaskCode.AddFingerprint -> {
                        if (intContent != null) {
                            addFingerprint(index = intContent)
                        }
                    }
                    // Edit Fingerprint
                    BleDeviceFeature.TaskCode.EditFingerprint -> {
                        if (intContent != null) {
                            editFingerprint(index = intContent)
                        }
                    }

                    BleDeviceFeature.TaskCode.DeleteFingerprint -> {
                        if (intContent != null) {
                            deleteFingerprint(intContent)
                        }
                    }

                    BleDeviceFeature.TaskCode.AddFace -> {
                        if (intContent != null) {
                            addFace(index = intContent)
                        }
                    }
                    // Edit Fingerprint
                    BleDeviceFeature.TaskCode.EditFace -> {
                        if (intContent != null) {
                            editFace(index = intContent)
                        }
                    }

                    BleDeviceFeature.TaskCode.DeleteFace -> {
                        if (intContent != null) {
                            deleteFace(intContent)
                        }
                    }

                    BleDeviceFeature.TaskCode.DeleteEvent -> {
                        if (intContent != null) {
                            deleteEvent(intContent)
                        }
                    }

                    BleDeviceFeature.TaskCode.AddUser -> {
                        if (intContent != null) {
                            addUser(intContent)
                        }
                    }
                    // Edit User
                    BleDeviceFeature.TaskCode.EditUser -> {
                        if (intContent != null) {
                            editUser(intContent)
                        }
                    }

                    BleDeviceFeature.TaskCode.DeleteUser -> {
                        if (intContent != null) {
                            deleteUser(intContent)
                        }
                    }

                    else -> {

                    }
                }
            }
        }
        return content.isNotBlank()
    }

    fun showAccessCodeInputDialog() {
        var title: String? = null
        var message: String? = null
        var initialIndex = ""
        var initialCode = ""
        when(uiState.value.taskCode){
            BleDeviceFeature.TaskCode.AddAccessCode -> {
                title = "請輸入想要新增Code的Index"
                initialIndex = lastCodeCardIndex.toString()
                initialCode = "1234"
            }

            BleDeviceFeature.TaskCode.EditAccessCode -> {
                title = "請輸入想要修改Code的Index"
                initialIndex = lastCodeCardIndex.toString()
                initialCode = ""
            }
            else -> {

            }
        }

        _uiState.update { it.copy(isShowInputAccessCodeDialog = true, inputDialogTitle = title, inputDialogMessage = message, inputDialogInitialIndex = initialIndex, inputDialogAccessCode = initialCode) }
    }

    fun closeAccessCodeInputDialog() {
        _uiState.update { it.copy(isShowInputAccessCodeDialog = false) }
    }

    fun setInputAccessCodeData(content: InputAccessCodeData){
        val functionName = "setInputAccessCodeData"
        val indexContent = content.index.toIntOrNull()
        val codeContent = content.code
        if(indexContent != null && codeContent.isNotBlank()) {
            showLog("輸入內容: $content")
        }
        viewModelScope.launch {
            runWithLoading(functionName) {
                when (uiState.value.taskCode) {
                    BleDeviceFeature.TaskCode.AddAccessCode -> {
                        if (indexContent != null) {
                            addAccessCode(code = codeContent, index = indexContent)
                        }
                    }

                    BleDeviceFeature.TaskCode.EditAccessCode -> {
                        if (indexContent != null) {
                            editAccessCode(code = codeContent, index = indexContent)
                        }
                    }

                    else -> {

                    }
                }
            }
        }
    }

    fun executeAutoTest(){
        if (!checkIsBluetoothEnable()) return
        val functionName = "AutoTest"
        val taskList = uiState.value.taskList.toMutableList()
        taskList.removeIf { it.first == BleDeviceFeature.TaskCode.Connect }
        taskList.removeIf { it.first == BleDeviceFeature.TaskCode.Disconnect }
        taskList.removeIf { it.first == BleDeviceFeature.TaskCode.AutoTest }
        viewModelScope.launch {
            runWithLoading(functionName) {
                // 初始化所有任務狀態為 IDLE
                _uiState.update { it.copy(btnEnabled = false, testResults = taskList.associate { taskList -> taskList.first to TestStatus.IDLE }, testTaskList = taskList.toTypedArray()) }
                // 1. 遍歷所有在 taskList 中的任務
                for (task in taskList) {
                    val taskCode = task.first
                    val taskName = task.second
                    // 2. 檢查是否有實作自動化邏輯
                    val isAutomatedSupported = when (taskCode) {
                        BleDeviceFeature.TaskCode.GetDeviceStatus,
                        BleDeviceFeature.TaskCode.ToggleLockState,
                        BleDeviceFeature.TaskCode.IsAdminCodeExists,
                        BleDeviceFeature.TaskCode.CreateAdminCode,
                        BleDeviceFeature.TaskCode.UpdateAdminCode,
                        BleDeviceFeature.TaskCode.GetAdminCodePosition,
                        BleDeviceFeature.TaskCode.DetermineLockDirection,
                        BleDeviceFeature.TaskCode.AutoUnlockToggleLockState,
                        BleDeviceFeature.TaskCode.GetLockName,
                        BleDeviceFeature.TaskCode.SetLockName,
                        BleDeviceFeature.TaskCode.GetLockTime,
                        BleDeviceFeature.TaskCode.SetLockTime,
                        BleDeviceFeature.TaskCode.GetLockTimeZone,
                        BleDeviceFeature.TaskCode.SetLockTimeZone,
                        BleDeviceFeature.TaskCode.GetLockConfig,
                        BleDeviceFeature.TaskCode.SetLockLocation,
                        BleDeviceFeature.TaskCode.ToggleSecurityBolt,
                        BleDeviceFeature.TaskCode.ToggleGuidingCode,
                        BleDeviceFeature.TaskCode.ToggleVirtualCode,
                        BleDeviceFeature.TaskCode.ToggleTwoFA,
                        BleDeviceFeature.TaskCode.ToggleVacationMode,
                        BleDeviceFeature.TaskCode.ToggleAutoLock,
                        BleDeviceFeature.TaskCode.ToggleOperatingSound,
                        BleDeviceFeature.TaskCode.ToggleKeyPressBeep,
                        BleDeviceFeature.TaskCode.ToggleShowFastTrackMode,
                        BleDeviceFeature.TaskCode.ToggleSabbathMode,
                        BleDeviceFeature.TaskCode.TogglePhoneticLanguage,
                        BleDeviceFeature.TaskCode.GetEventQuantity,
                        BleDeviceFeature.TaskCode.GetEvent,
                        BleDeviceFeature.TaskCode.DeleteEvent,
                        BleDeviceFeature.TaskCode.GetTokenArray,
                        BleDeviceFeature.TaskCode.GetToken,
                        BleDeviceFeature.TaskCode.AddOneTimeToken,
                        BleDeviceFeature.TaskCode.EditToken,
                        BleDeviceFeature.TaskCode.DeleteToken,
                        BleDeviceFeature.TaskCode.GetUserAbility,
                        BleDeviceFeature.TaskCode.GetUserCount,
                        BleDeviceFeature.TaskCode.IsMatterDevice,
                        BleDeviceFeature.TaskCode.GetUserArray,
                        BleDeviceFeature.TaskCode.GetUser,
                        BleDeviceFeature.TaskCode.AddUser,
                        BleDeviceFeature.TaskCode.EditUser,
                        BleDeviceFeature.TaskCode.DeleteUser,
                        BleDeviceFeature.TaskCode.GetCredentialArray,
                        BleDeviceFeature.TaskCode.GetCredential,
                        BleDeviceFeature.TaskCode.GetCredentialByCredential,
                        BleDeviceFeature.TaskCode.GetCredentialByUser,
                        BleDeviceFeature.TaskCode.GetLockSupportedUnlockTypes,
                        BleDeviceFeature.TaskCode.GetAccessCodeArray,
                        BleDeviceFeature.TaskCode.GetAccessCode,
                        BleDeviceFeature.TaskCode.AddAccessCode,
                        BleDeviceFeature.TaskCode.EditAccessCode,
                        BleDeviceFeature.TaskCode.DeleteAccessCode,
                        BleDeviceFeature.TaskCode.GetAccessCardArray,
                        BleDeviceFeature.TaskCode.GetAccessCard,
                        BleDeviceFeature.TaskCode.AddAccessCard,
                        BleDeviceFeature.TaskCode.EditAccessCard,
                        BleDeviceFeature.TaskCode.DeleteAccessCard,
                        BleDeviceFeature.TaskCode.DeviceGetAccessCard,
                        BleDeviceFeature.TaskCode.GetFingerprintArray,
                        BleDeviceFeature.TaskCode.GetFingerprint,
                        BleDeviceFeature.TaskCode.AddFingerprint,
                        BleDeviceFeature.TaskCode.EditFingerprint,
                        BleDeviceFeature.TaskCode.DeleteFingerprint,
                        BleDeviceFeature.TaskCode.DeviceGetFingerprint,
                        BleDeviceFeature.TaskCode.GetFaceArray,
                        BleDeviceFeature.TaskCode.GetFace,
                        BleDeviceFeature.TaskCode.AddFace,
                        BleDeviceFeature.TaskCode.EditFace,
                        BleDeviceFeature.TaskCode.DeleteFace,
                        BleDeviceFeature.TaskCode.DeviceGetFace,
                        BleDeviceFeature.TaskCode.GetUserCredentialHash,
                        BleDeviceFeature.TaskCode.GetBleUserHash,
                        BleDeviceFeature.TaskCode.SetAllDataSynced,
                        BleDeviceFeature.TaskCode.TogglePlugState,
                        BleDeviceFeature.TaskCode.GetFwVersion,
                        BleDeviceFeature.TaskCode.GetFwModel,
                        BleDeviceFeature.TaskCode.GetRfVersion,
                        BleDeviceFeature.TaskCode.GetMcuVersion,
                        BleDeviceFeature.TaskCode.ScanWifi,
                        BleDeviceFeature.TaskCode.ConnectToWifi,
                        BleDeviceFeature.TaskCode.FactoryReset, -> true
                        // not support
                        BleDeviceFeature.TaskCode.Connect,
                        BleDeviceFeature.TaskCode.Disconnect,
                        BleDeviceFeature.TaskCode.AutoTest,
                        BleDeviceFeature.TaskCode.SetOTAUpdate,
                        BleDeviceFeature.TaskCode.SetOTACancel,
                        BleDeviceFeature.TaskCode.Restart,
                        BleDeviceFeature.TaskCode.FactoryResetNoAdmin,-> false
                        else -> false
                    }

                    // 💡 如果不支援，更新狀態為 NOT_SUPPORTED 並跳過
                    if (!isAutomatedSupported) {
                        updateTaskStatus(taskCode, TestStatus.NOT_SUPPORTED)
                        continue
                    }

                    // 1. 更新 UI：目前正在執行哪一個任務
                    updateTaskStatus(taskCode, TestStatus.RUNNING)
                    _uiState.update { it.copy(currentTestTask = "正在執行: $taskName") }
                    val containsGetLockSupportedUnlockTypesTask = uiState.value.taskList.any { it.first == BleDeviceFeature.TaskCode.GetLockSupportedUnlockTypes }
                    try {
                        // 2. 根據 TaskCode 執行對應的 suspend 函數
                        when (taskCode) {
                            BleDeviceFeature.TaskCode.GetLockTime -> {
                                getLockTime()
                            }

                            BleDeviceFeature.TaskCode.SetLockTime -> {
                                setLockTime()
                            }

                            BleDeviceFeature.TaskCode.GetLockTimeZone -> {
                                getLockTimeZone()
                            }

                            BleDeviceFeature.TaskCode.SetLockTimeZone -> {
                                setLockTimeZone()
                            }

                            BleDeviceFeature.TaskCode.GetLockName -> {
                                getLockName()
                            }

                            BleDeviceFeature.TaskCode.SetLockName -> {
                                setLockName()
                            }

                            BleDeviceFeature.TaskCode.GetDeviceStatus -> {
                                getDeviceStatus()
                            }

                            BleDeviceFeature.TaskCode.GetLockConfig -> {
                                getLockConfig()
                            }

                            BleDeviceFeature.TaskCode.ToggleLockState -> {
                                toggleLockState()
                                toggleLockState()
                            }

                            BleDeviceFeature.TaskCode.AutoUnlockToggleLockState -> {
                                autoUnlockToggleLockState()
                            }

                            BleDeviceFeature.TaskCode.ToggleSecurityBolt -> {
                                toggleSecurityBolt()
                                toggleSecurityBolt()
                            }

                            BleDeviceFeature.TaskCode.ToggleKeyPressBeep -> {
                                toggleKeyPressBeep()
                                toggleKeyPressBeep(100)
                            }

                            BleDeviceFeature.TaskCode.ToggleVacationMode -> {
                                toggleVacationMode()
                                toggleVacationMode()
                            }

                            BleDeviceFeature.TaskCode.ToggleGuidingCode -> {
                                toggleGuidingCode()
                                toggleGuidingCode()
                            }

                            BleDeviceFeature.TaskCode.ToggleAutoLock -> {
                                toggleAutoLock()
                                toggleAutoLock()
                            }

                            BleDeviceFeature.TaskCode.SetLockLocation -> {
                                setLockLocation()
                            }

                            BleDeviceFeature.TaskCode.ToggleVirtualCode -> {
                                toggleVirtualCode()
                                toggleVirtualCode()
                            }

                            BleDeviceFeature.TaskCode.ToggleTwoFA -> {
                                toggleTwoFA()
                                toggleTwoFA()
                            }

                            BleDeviceFeature.TaskCode.ToggleOperatingSound -> {
                                toggleOperatingSound()
                                toggleOperatingSound()
                            }

                            BleDeviceFeature.TaskCode.ToggleShowFastTrackMode -> {
                                toggleShowFastTrackMode()
                                toggleShowFastTrackMode()
                            }

                            BleDeviceFeature.TaskCode.ToggleSabbathMode -> {
                                toggleSabbathMode()
                                toggleSabbathMode()
                            }

                            BleDeviceFeature.TaskCode.TogglePhoneticLanguage -> {
                                togglePhoneticLanguage()
                                togglePhoneticLanguage()
                            }

                            BleDeviceFeature.TaskCode.DetermineLockDirection -> {
                                determineLockDirection()
                            }

                            BleDeviceFeature.TaskCode.IsAdminCodeExists -> {
                                isAdminCodeExists()
                            }

                            BleDeviceFeature.TaskCode.CreateAdminCode -> {
                                createAdminCode()
                            }

                            BleDeviceFeature.TaskCode.UpdateAdminCode -> {
                                updateAdminCode()
                            }

                            BleDeviceFeature.TaskCode.GetAdminCodePosition -> {
                                getAdminCodePosition()
                            }

                            BleDeviceFeature.TaskCode.TogglePlugState -> {
                                togglePlugState()
                                togglePlugState()
                            }

                            BleDeviceFeature.TaskCode.GetFwVersion -> {
                                getFirmwareVersion()
                            }

                            BleDeviceFeature.TaskCode.GetFwModel -> {
                                getFirmwareModel()
                            }

                            BleDeviceFeature.TaskCode.GetRfVersion -> {
                                getRfVersion()
                            }

                            BleDeviceFeature.TaskCode.GetMcuVersion -> {
                                getMcuVersion()
                            }

                            BleDeviceFeature.TaskCode.FactoryReset -> {
                                factoryReset()
                            }

                            BleDeviceFeature.TaskCode.FactoryResetNoAdmin -> {
                                factoryResetNoAdmin()
                            }

                            BleDeviceFeature.TaskCode.Restart -> {
                                restart()
                            }

                            BleDeviceFeature.TaskCode.GetTokenArray -> {
                                getTokenArray()
                            }

                            BleDeviceFeature.TaskCode.GetToken -> {
                                getToken()
                            }

                            BleDeviceFeature.TaskCode.AddOneTimeToken -> {
                                addOneTimeToken()

                            }

                            BleDeviceFeature.TaskCode.EditToken -> {
                                editToken()
                            }

                            BleDeviceFeature.TaskCode.DeleteToken -> {
                                deleteToken()
                            }

                            BleDeviceFeature.TaskCode.GetAccessCodeArray -> {
                                getAccessCodeArray()
                            }

                            BleDeviceFeature.TaskCode.GetAccessCode -> {
                                getAccessCode()
                            }

                            BleDeviceFeature.TaskCode.AddAccessCode -> {
                                addAccessCode()
                            }

                            BleDeviceFeature.TaskCode.EditAccessCode -> {
                                editAccessCode()
                            }

                            BleDeviceFeature.TaskCode.DeleteAccessCode -> {
                                deleteAccessCode()
                            }

                            BleDeviceFeature.TaskCode.GetAccessCardArray -> {
                                getAccessCardArray()
                            }

                            BleDeviceFeature.TaskCode.GetAccessCard -> {
                                getAccessCard()
                            }

                            BleDeviceFeature.TaskCode.AddAccessCard -> {
                                addAccessCard()
                            }

                            BleDeviceFeature.TaskCode.EditAccessCard -> {
                                editAccessCard()
                            }

                            BleDeviceFeature.TaskCode.DeleteAccessCard -> {
                                deleteAccessCard()
                            }

                            BleDeviceFeature.TaskCode.DeviceGetAccessCard -> {
                                deviceGetAccessCard()
                                if (containsGetLockSupportedUnlockTypesTask) {
                                    deviceExitAccess(Access.Type.CARD.value, lastCodeCardIndex)
                                } else {
                                    deviceExitCredential(
                                        BleV3Lock.CredentialType.RFID.value,
                                        lastCodeCardIndex
                                    )
                                }
                            }

                            BleDeviceFeature.TaskCode.GetFingerprintArray -> {
                                getFingerprintArray()
                            }

                            BleDeviceFeature.TaskCode.GetFingerprint -> {
                                getFingerprint()
                            }

                            BleDeviceFeature.TaskCode.AddFingerprint -> {
                                addFingerprint()
                            }

                            BleDeviceFeature.TaskCode.EditFingerprint -> {
                                editFingerprint()
                            }

                            BleDeviceFeature.TaskCode.DeleteFingerprint -> {
                                deleteFingerprint()
                            }

                            BleDeviceFeature.TaskCode.DeviceGetFingerprint -> {
                                deviceGetFingerprint()
                                if (containsGetLockSupportedUnlockTypesTask) {
                                    deviceExitAccess(
                                        Access.Type.FINGERPRINT.value,
                                        lastFingerprintIndex
                                    )
                                } else {
                                    deviceExitCredential(
                                        BleV3Lock.CredentialType.FINGERPRINT.value,
                                        lastFingerprintIndex
                                    )
                                }
                            }

                            BleDeviceFeature.TaskCode.GetFaceArray -> {
                                getFaceArray()
                            }

                            BleDeviceFeature.TaskCode.GetFace -> {
                                getFace()
                            }

                            BleDeviceFeature.TaskCode.AddFace -> {
                                addFace()
                            }

                            BleDeviceFeature.TaskCode.EditFace -> {
                                editFace()
                            }

                            BleDeviceFeature.TaskCode.DeleteFace -> {
                                deleteFace()
                            }

                            BleDeviceFeature.TaskCode.DeviceGetFace -> {
                                deviceGetFace()
                                if (containsGetLockSupportedUnlockTypesTask) {
                                    deviceExitAccess(Access.Type.FACE.value, lastFaceIndex)
                                } else {
                                    deviceExitCredential(
                                        BleV3Lock.CredentialType.FACE.value,
                                        lastFaceIndex
                                    )
                                }
                            }

                            BleDeviceFeature.TaskCode.GetEventQuantity -> {
                                getEventQuantity()
                            }

                            BleDeviceFeature.TaskCode.GetEvent -> {
                                getEvent()
                            }

                            BleDeviceFeature.TaskCode.DeleteEvent -> {
                                deleteEvent()
                            }

                            BleDeviceFeature.TaskCode.GetLockSupportedUnlockTypes -> {
                                getLockSupportedUnlockTypes()
                            }

                            BleDeviceFeature.TaskCode.GetUserAbility -> {
                                getUserAbility()
                            }

                            BleDeviceFeature.TaskCode.GetUserCount -> {
                                getUserCount()
                            }

                            BleDeviceFeature.TaskCode.IsMatterDevice -> {
                                isMatterDevice()
                            }

                            BleDeviceFeature.TaskCode.GetUserArray -> {
                                getUserArray()
                            }

                            BleDeviceFeature.TaskCode.GetUser -> {
                                getUser()
                            }

                            BleDeviceFeature.TaskCode.AddUser -> {
                                addUser()
                                addUser()
                            }

                            BleDeviceFeature.TaskCode.EditUser -> {
                                editUser()
                            }

                            BleDeviceFeature.TaskCode.DeleteUser -> {
                                deleteUser()
                            }

                            BleDeviceFeature.TaskCode.GetCredentialArray -> {
                                getCredentialArray()
                            }

                            BleDeviceFeature.TaskCode.GetCredential -> {
                                getCredential()
                            }

                            BleDeviceFeature.TaskCode.GetCredentialByCredential -> {
                                getCredentialByCredential()
                            }

                            BleDeviceFeature.TaskCode.GetCredentialByUser -> {
                                getCredentialByUser()
                            }

                            BleDeviceFeature.TaskCode.GetUserCredentialHash -> {
                                getUserCredentialHash()
                            }

                            BleDeviceFeature.TaskCode.GetBleUserHash -> {
                                getBleUserHash()
                            }

                            BleDeviceFeature.TaskCode.SetAllDataSynced -> {
                                setAllDataSynced()
                            }

                            BleDeviceFeature.TaskCode.ScanWifi -> {
                                when (_currentDeviceStatus) {
                                    is DeviceStatus.EightTwo -> {
                                        collectWifiList3()
                                        scanWifi3().join()
                                    }

                                    is DeviceStatus.B0 -> {
                                        collectWifiList3()
                                        scanWifi3().join()
                                    }

                                    else -> {
                                        collectWifiList()
                                        scanWifi()
                                    }
                                }
                            }

                            BleDeviceFeature.TaskCode.ConnectToWifi -> {
                                when (_currentDeviceStatus) {
                                    is DeviceStatus.EightTwo -> {
                                        connectToWifi3("Sunion-SW", "S-device_W").join()
                                    }

                                    is DeviceStatus.B0 -> {
                                        connectToWifi3("Sunion-SW", "S-device_W").join()
                                    }

                                    else -> {
                                        connectToWifi("Sunion-SW", "S-device_W")
                                    }
                                }
                            }

                            BleDeviceFeature.TaskCode.SetOTAUpdate -> {
                                if (!isBackgroundOTA) {
                                    // Foreground OTA Update
                                    otaUpdate(currentTarget, signatureV005)
                                } else {
                                    // Background OTA Update
                                    backGroundOTAUpdate()
                                }
                            }

                            BleDeviceFeature.TaskCode.SetOTACancel -> {
                                setOTACancel(currentTarget)
                            }

                            else -> {
                                showLog("跳過任務: $taskCode (未實作自動化)")
                            }
                        }
                        // 3. 執行成功，更新狀態
                        updateTaskStatus(taskCode, TestStatus.SUCCESS)
                    } catch (e: NotConnectedException) {
                        showLog("$functionName: ${taskCode.name} 自動化測試中斷，鎖體藍牙斷線")
                        updateTaskStatus(taskCode, TestStatus.FAILED)
                    } catch (e: LockStatusException.LockFunctionNotSupportException) {
                        showLog("$functionName: ${taskCode.name} 不支援，已忽略")
                        updateTaskStatus(taskCode, TestStatus.NOT_SUPPORTED)
                    } catch (e: Exception) {
                        showLog("自動化測試中斷: $e")
                        updateTaskStatus(taskCode, TestStatus.FAILED)
                    }
                }
                _uiState.update { it.copy(btnEnabled = true, currentTestTask = "自動化測試完成", showReport = true) }
            }
        }
    }

    private fun updateTaskStatus(taskCode: BleDeviceFeature.TaskCode, status: TestStatus) {
        _uiState.update { state ->
            val newResults = state.testResults.toMutableMap()
            newResults[taskCode] = status
            state.copy(testResults = newResults)
        }
    }

    // 提供一個關閉報告的函式
    fun closeReport() {
        _uiState.update { it.copy(showReport = false) }
    }

    private fun getRandomCode(): String{
        return String.format("%04d", Random().nextInt(10000))
    }
}

enum class TestStatus {
    IDLE,       // 尚未開始
    RUNNING,    // 執行中
    SUCCESS,    // 成功
    FAILED,      // 失敗
    NOT_SUPPORTED   // 不支援
}

data class UiState(
    val isLoading:Boolean = false,
    val isBlueToothAvailable: Boolean = false,
    val isConnectedWithLock: Boolean = false,
    val shouldShowBluetoothEnableDialog: Boolean = false,
    val taskCode: BleDeviceFeature.TaskCode = BleDeviceFeature.TaskCode.Unknown,
    val btnEnabled: Boolean = false,
    val shouldShowTaskList: Boolean = false,
    val message: String = "",
    val taskList: Array<Triple<BleDeviceFeature.TaskCode, String, Set<String>>> = BleDeviceFeature.initTaskList,
    val isShowInputDialog: Boolean = false,
    val isShowInputAccessCodeDialog: Boolean = false,
    val inputDialogTitle: String? = null,
    val inputDialogMessage: String? = null,
    val inputDialogInitialText: String = "",
    val inputDialogInitialIndex: String = "",
    val inputDialogAccessCode: String = "",
    val inputDialogContent: String = "",
    val testResults: Map<BleDeviceFeature.TaskCode, TestStatus> = emptyMap(),
    val testTaskList: Array<Triple<BleDeviceFeature.TaskCode, String, Set<String>>> = BleDeviceFeature.initTaskList,
    val currentTestTask: String? = null,
    val showReport: Boolean = false
)

sealed class UiEvent {
    object Complete: UiEvent()
}
