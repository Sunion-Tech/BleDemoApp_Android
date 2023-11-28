package com.sunion.ble.demoapp

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sunion.core.ble.usecase.LockQRCodeUseCase
import com.sunion.core.ble.ReactiveStatefulConnection
import com.sunion.core.ble.usecase.LockNameUseCase
import com.sunion.core.ble.entity.*
import com.sunion.core.ble.exception.LockStatusException
import com.sunion.core.ble.accessCodeToHex
import com.sunion.core.ble.toHexString
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
import javax.inject.Inject
import java.util.concurrent.TimeoutException

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
    private val application: Application
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

    private var _currentDeviceStatus : SunionBleNotification = DeviceStatus.UNKNOWN

    private var _currentSunionBleNotification : SunionBleNotification = SunionBleNotification.UNKNOWN

    private var isCollectingConnectToWifiState = false
    private var isConnectingToWifi = false
    private var isWifiConnected = false

    private var scanWifiJob: Job? = null
    private var collectWifiListJob: Job? = null
    private var connectToWifiJob: Job? = null

    private var currentFileUri: Uri? = null
    var fileSize: Int = 0
    val currentTarget = 0 // 0:mcu 1:rf
    val ivString = "0439F307DD7DC259C4242D8A83FF70A7"
    val signatureV005 =
        "3045022100CE013DE7E4816F0F9D35955879FB34DF2C0CB0BC335E9C4D598B23498C978DE30220401808C3E08E36F899EC36B57944731F48B54300E229EA6ADF181F374763E94E"
    val signatureV008 =
        "3046022100BC8B2E6E479E81680C16CA2AE61FA88D84B063AF8334361D1B01ABA57941AD04022100D7E894B0F4DE3F448CD1E112AE48557F5664D0A686DCFC797233ED3D119851C0"
    val signatureV012 =
        "30460221008DA3CFC12FD79DB4E604198AB1ACEB415AF83B20CCD9B689DC5D497969164ABE022100DF11393C059817F87B8102F799E0DE45350F1DBE29E444475F5BAF65BF4107B0"
    val hash256V005 = "76FE255B21C7A3B2A7108C7215E543F87A85E93B47EF0CC521ACA958A776AEF0"
    val hash256V008 = "E7DC8DB6ED39CD24A5BEEC3A46BE1109D466B5E9D830C0A41DBEEEC800BCAD01"
    val hash256V012 = "A02317A9E4DC5F7F2AD8FA2F8A5C7970B2D710D081BB3BE6C756EBF0458A0A07"

    private var adminCode = "0000"

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

    fun setTaskCode(code: Int) {
        _uiState.update { it.copy(taskCode = code, shouldShowTaskList = false) }
    }

    fun executeTask() {
        if (!checkIsBluetoothEnable()) return
        _uiState.update { it.copy(btnEnabled = false) }
        when (_uiState.value.taskCode) {
            // Connect
            TaskCode.Connect -> {
                connect()
            }
            // Get lock time
            TaskCode.GetLockTime -> {
                getLockTime()
            }
            // Set lock time
            TaskCode.SetLockTime -> {
                setLockTime()
            }
            // Set lock timezone
            TaskCode.SetLockTimeZone -> {
                setLockTimeZone()
            }
            // Get lock name
            TaskCode.GetLockName -> {
                getLockName()
            }
            // Set lock name
            TaskCode.SetLockName -> {
                setLockName(name = "my door lock")
            }
            // Get DeviceStatus
            TaskCode.GetDeviceStatus -> {
                getDeviceStatus()
            }
            // Get lock config
            TaskCode.GetLockConfig -> {
                getLockConfig()
            }
            // Toggle lock state
            TaskCode.ToggleLockState -> {
                toggleLockState()
            }
            // Toggle security bolt
            TaskCode.ToggleSecurityBolt -> {
                toggleSecurityBolt()
            }
            // Toggle key press beep
            TaskCode.ToggleKeyPressBeep -> {
                toggleKeyPressBeep()
            }
            // Toggle vacation mode
            TaskCode.ToggleVacationMode -> {
                toggleVacationMode()
            }
            // Toggle guiding code
            TaskCode.ToggleGuidingCode -> {
                toggleGuidingCode()
            }
            // Toggle auto lock
            TaskCode.ToggleAutoLock -> {
                toggleAutoLock(10)
            }
            // Set lock location
            TaskCode.SetLockLocation -> {
                setLockLocation(25.03369, 121.564128)
            }
            // Toggle virtual code
            TaskCode.ToggleVirtualCode -> {
                toggleVirtualCode()
            }
            // Toggle twoFA
            TaskCode.ToggleTwoFA -> {
                toggleTwoFA()
            }
            // Toggle operating sound
            TaskCode.ToggleOperatingSound -> {
                toggleOperatingSound()
            }
            // Toggle show fast track mode
            TaskCode.ToggleShowFastTrackMode -> {
                toggleShowFastTrackMode()
            }
            // Determine lock direction
            TaskCode.DetermineLockDirection -> {
                determineLockDirection()
            }
            // Is Admin code exists
            TaskCode.IsAdminCodeExists -> {
                isAdminCodeExists()
            }
            // Create Admin code
            TaskCode.CreateAdminCode -> {
                createAdminCode(adminCode)
            }
            // Update Admin code
            TaskCode.UpdateAdminCode -> {
                updateAdminCode(adminCode, newCode = "1234")
            }
            // Plug on
            TaskCode.TogglePlugState -> {
                togglePlugState()
            }
            // Get firmware version
            TaskCode.GetFwVersion -> {
                getFirmwareVersion()
            }
            // Factory reset
            TaskCode.FactoryReset -> {
                factoryReset(adminCode)
            }
            // Factory reset
            TaskCode.FactoryResetNoAdmin -> {
                factoryReset()
            }
            // Query TokenArray
            TaskCode.QueryTokenArray -> {
                queryTokenArray()
            }
            // Query Token
            TaskCode.QueryToken -> {
                queryToken()
            }
            // Add OneTime Token
            TaskCode.AddOneTimeToken -> {
                addOneTimeToken("L","Tom1")
            }
            // Edit Token
            TaskCode.EditToken -> {
                editToken(9,"A","Tom2")
            }
            // Delete Token
            TaskCode.DeleteToken -> {
                deleteToken(9)
            }
            // Get Access Code Array
            TaskCode.GetAccessCodeArray -> {
                getAccessCodeArray()
            }
            // Query Access Code
            TaskCode.QueryAccessCode -> {
                queryAccessCode()
            }
            // Add Access Code
            TaskCode.AddAccessCode -> {
                addAccessCode()
            }
            // Edit Access Code
            TaskCode.EditAccessCode -> {
                editAccessCode()
            }
            // Delete Access Code
            TaskCode.DeleteAccessCode -> {
                deleteAccessCode(1)
            }
            // Get Access Card Array
            TaskCode.GetAccessCardArray -> {
                getAccessCardArray()
            }
            // Query Access Card
            TaskCode.QueryAccessCard -> {
                queryAccessCard()
            }
            // Add Access Card
            TaskCode.AddAccessCard -> {
                addAccessCard()
            }
            // Edit Access Card
            TaskCode.EditAccessCard -> {
                editAccessCard()
            }
            // Delete Access Card
            TaskCode.DeleteAccessCard -> {
                deleteAccessCard(2)
            }
            // Device Get Access Card
            TaskCode.DeviceGetAccessCard -> {
                deviceGetAccessCard()
            }
            // Get Fingerprint Array
            TaskCode.GetFingerprintArray -> {
                getFingerprintArray()
            }
            // Query Fingerprint
            TaskCode.QueryFingerprint -> {
                queryFingerprint()
            }
            // Add Fingerprint
            TaskCode.AddFingerprint -> {
                addFingerprint()
            }
            // Edit Fingerprint
            TaskCode.EditFingerprint -> {
                editFingerprint()
            }
            // Delete Fingerprint
            TaskCode.DeleteFingerprint -> {
                deleteFingerprint(3)
            }
            // Device Get Fingerprint
            TaskCode.DeviceGetFingerprint -> {
                deviceGetFingerprint()
            }
            // Get FaceArray
            TaskCode.GetFaceArray -> {
                getFaceArray()
            }
            // Query Face
            TaskCode.QueryFace -> {
                queryFace()
            }
            // Add Face
            TaskCode.AddFace -> {
                addFace()
            }
            // Edit Face
            TaskCode.EditFace -> {
                editFace()
            }
            // Delete Face
            TaskCode.DeleteFace -> {
                deleteFace(4)
            }
            // Device Get Face
            TaskCode.DeviceGetFace -> {
                deviceGetFace()
            }
            // Get Event Quantity
            TaskCode.GetEventQuantity -> {
                getEventQuantity()
            }
            // Get Event
            TaskCode.GetEvent -> {
                getEvent()
            }
            // Delete Event
            TaskCode.DeleteEvent -> {
                deleteEvent(1)
            }
            // Get Lock Supported Unlock Types
            TaskCode.GetLockSupportedUnlockTypes -> {
                getLockSupportedUnlockTypes()
            }
            // Scan Wifi
            TaskCode.ScanWifi -> {
                collectWifiList()
                scanWifi()
            }
            // Connect To Wifi
            TaskCode.ConnectToWifi -> {
                connectToWifi("Sunion-SW", "S-device_W")
            }
            // Set OTA Status
            TaskCode.SetOTAUpdate -> {
                otaUpdate(currentTarget, signatureV012)
            }
            // Set OTA Cancel
            TaskCode.SetOTACancel -> {
                setOTACancel(currentTarget)
            }
            // Disconnect
            TaskCode.Disconnect -> {
                disconnect()
            }
        }
        _uiState.update { it.copy(btnEnabled = true) }
    }

    private fun connect() {
        _uiState.update { it.copy(isLoading = true) }
        // Setup BLE connection state observer
        _bleConnectionStateListener?.cancel()
        _bleConnectionStateListener = statefulConnection.connState
            .onEach { event ->
                when (event.status) {
                    // something wrong
                    EventState.ERROR -> {
                        _uiState.update { it.copy(isLoading = false, isConnectedWithLock = false) }
                        when (event.message) {
                            TimeoutException::class.java.simpleName -> {
                                showLog("connect to lock timeout\n")
                            }
                            else -> {
                                unless(
                                    event.data != null
                                ) {
                                    showLog("connect to lock failed: ${event.message} \n")
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
                                    }
                                    is Alert -> {
                                        _currentSunionBleNotification = sunionBleNotification
                                        showLog("Incoming ${sunionBleNotification::class.simpleName} arrived.")
                                    }
                                    is Access -> {
                                        _currentSunionBleNotification = sunionBleNotification
                                        showLog("Incoming ${sunionBleNotification::class.simpleName} arrived.")
                                    }
                                    else -> {
                                        _currentDeviceStatus = DeviceStatus.UNKNOWN
                                        _currentSunionBleNotification = SunionBleNotification.UNKNOWN
                                    }
                                }
                                updateCurrentDeviceStatusOrNotification(sunionBleNotification)
                            }
                            .catch { e -> showLog("Incoming SunionBleNotification exception $e \n") }
                            .flowOn(Dispatchers.IO)
                            .launchIn(viewModelScope)
                    }
                    // connected
                    EventState.SUCCESS -> {
                        if (event.status == EventState.SUCCESS && event.data?.first == true) {
                            _uiState.update { it.copy(isLoading = false, isConnectedWithLock = true) }
                            _lockConnectionInfo = _lockConnectionInfo!!.copy(
                                permission = statefulConnection.lockConnectionInfo.permission,
                                keyTwo = statefulConnection.lockConnectionInfo.keyTwo,
                                permanentToken = statefulConnection.lockConnectionInfo.permanentToken
                            )
                            showLog("connect to lock succeed.\n")
                            // connect with oneTimeToken
                            if (_lockConnectionInfo!!.permanentToken.isNullOrEmpty()) {
                                showLog("After pairing with lock, you can get lock connection information from statefulConnection.lockConnectionInfo and save permanent token for later use.\n")
                            }
                            showLog("Lock connection information:")
                            showLog("${_lockConnectionInfo}\n")
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
        viewModelScope.launch {
            showLog("Connecting to ${_lockConnectionInfo!!.macAddress!!}...")
            statefulConnection.establishConnection(
                macAddress = _lockConnectionInfo!!.macAddress!!,
                keyOne = _lockConnectionInfo!!.keyOne!!,
                oneTimeToken = _lockConnectionInfo!!.oneTimeToken!!,
                permanentToken = _lockConnectionInfo!!.permanentToken,
                isSilentlyFail = false
            )
        }
    }

    private fun getLockTime() {
        flow { emit(lockTimeUseCase.getTime()) }
            .catch { e -> showLog("getLockTime exception $e \n") }
            .map { timeStamp ->
                showLog("Lock time = ${timeStamp}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun setLockTime() {
        flow { emit(lockTimeUseCase.setTime(Instant.now().atZone(ZoneId.systemDefault()).toEpochSecond())) }
            .catch { e -> showLog("setLockTime exception $e \n") }
            .map { result ->
                showLog("Set lock time to now, result = ${result}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun setLockTimeZone() {
        flow { emit(lockTimeUseCase.setTimeZone(ZoneId.systemDefault().id)) }
            .catch { e -> showLog("setLockTimeZone exception $e \n") }
            .map { result ->
                showLog("Set lock timezone to ${ZoneId.systemDefault().id}, result = ${result}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getDeviceStatus() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                flow { emit(deviceStatusD6UseCase()) }
                    .catch { e -> showLog("getDeviceStatusD6 exception $e \n") }
                    .map { deviceStatus ->
                        _currentDeviceStatus = DeviceStatus.DeviceStatusD6(
                            deviceStatus.config,
                            deviceStatus.lockState,
                            deviceStatus.battery,
                            deviceStatus.batteryState,
                            deviceStatus.timestamp
                        )
                        updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(deviceStatusA2UseCase()) }
                    .catch { e -> showLog("getDeviceStatusA2 exception $e \n") }
                    .map { deviceStatus ->
                        _currentDeviceStatus = DeviceStatus.DeviceStatusA2(
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
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusB0 -> {
                flow { emit(plugConfigUseCase()) }
                    .catch { e -> showLog("getDeviceStatusB0 exception $e \n") }
                    .map { deviceStatus ->
                        _currentDeviceStatus = DeviceStatus.DeviceStatusB0(
                            deviceStatus.setWifi,
                            deviceStatus.connectWifi,
                            deviceStatus.plugState
                        )
                        updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Not support getDeviceStatus.\n") }
        }
    }

    private fun toggleLockState() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.DeviceStatusD6
                val desiredState = when (deviceStatusD6.lockState) {
                    LockState.LOCKED -> { LockState.UNLOCKED }
                    LockState.UNLOCKED -> { LockState.LOCKED }
                    else -> {
                        showLog("Unknown lock state.\n")
                        return
                    }
                }
                if (deviceStatusD6.config.direction is LockDirection.NotDetermined) {
                    showLog("Lock direction is not determined. Please set lock direction before toggle lock state.\n")
                    return
                }
                flow { emit(deviceStatusD6UseCase.setLockState(desiredState)) }
                    .catch { e -> showLog("toggleLockState exception $e \n") }
                    .map { deviceStatus ->
                        showLog("Set LockState to ${desiredState}:")
                        _currentDeviceStatus = DeviceStatus.DeviceStatusD6(
                            deviceStatus.config,
                            deviceStatus.lockState,
                            deviceStatus.battery,
                            deviceStatus.batteryState,
                            deviceStatus.timestamp
                        )
                        updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                val deviceStatusA2 = _currentDeviceStatus as DeviceStatus.DeviceStatusA2
                if (deviceStatusA2.direction == BleV2Lock.Direction.UNKNOWN.value) {
                    showLog("Lock direction is not determined. Please set lock direction before toggle lock state.\n")
                    return
                }
                val state: Int = when (deviceStatusA2.lockState) {
                    BleV2Lock.LockState.LOCKED.value -> { BleV2Lock.LockState.UNLOCKED.value }
                    BleV2Lock.LockState.UNLOCKED.value -> { BleV2Lock.LockState.LOCKED.value }
                    else -> {
                        showLog("Unknown lock state.\n")
                        return
                    }
                }
                flow { emit(deviceStatusA2UseCase.setLockState(state)) }
                    .catch { e -> showLog("toggleLockState exception $e \n") }
                    .map { deviceStatus ->
                        showLog("Set LockState to $state")
                        _currentDeviceStatus = DeviceStatus.DeviceStatusA2(
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
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Not support toggleLockState.\n") }
        }
    }

    private fun toggleSecurityBolt() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                val deviceStatusA2 = _currentDeviceStatus as DeviceStatus.DeviceStatusA2
                if (deviceStatusA2.securityBolt == BleV2Lock.SecurityBolt.NOT_SUPPORT.value) {
                    showLog("Lock securityBolt is not support.\n")
                    return
                }
                val state: Int = when (deviceStatusA2.securityBolt) {
                    BleV2Lock.SecurityBolt.PROTRUDE.value -> { BleV2Lock.SecurityBolt.NOT_PROTRUDE.value }
                    BleV2Lock.SecurityBolt.NOT_PROTRUDE.value -> { BleV2Lock.SecurityBolt.PROTRUDE.value }
                    else -> {
                        showLog("Unknown security bolt.\n")
                        return
                    }
                }
                flow { emit(deviceStatusA2UseCase.setSecurityBolt(state)) }
                    .catch { e -> showLog("toggleSecurityBolt exception $e \n") }
                    .map { deviceStatus ->
                        showLog("Set security bolt to $state")
                        _currentDeviceStatus = DeviceStatus.DeviceStatusA2(
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
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Not support toggleSecurityBolt.\n") }
        }
    }

    private fun getLockConfig() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                flow { emit(lockConfigD4UseCase.query()) }
                    .catch { e -> showLog("getLockConfig exception $e \n") }
                    .map { lockConfig ->
                        showLog("getLockConfigD4: $lockConfig \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("getLockConfig exception $e \n") }
                    .map { lockConfig ->
                        showLog("getLockConfigA0: $lockConfig \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support getLockConfig.\n")
            }
        }
    }

    private fun toggleKeyPressBeep(soundValue:Int = 0) {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.DeviceStatusD6
                val isSoundOn = when (deviceStatusD6.config.isSoundOn) {
                    true -> { false }
                    false -> { true }
                }
                flow { emit(lockConfigD4UseCase.setKeyPressBeep(isSoundOn)) }
                    .catch { e -> showLog("toggleKeyPressBeep exception $e \n") }
                    .map { result ->
                        showLog("Set key press beep to ${isSoundOn}, result = $result \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("lockConfigA0UseCase.query() exception $e \n") }
                    .map { lockConfig ->
                        val value = when (lockConfig.soundType) {
                            0x01 -> if(lockConfig.soundValue == 100) 0 else 100
                            0x02 -> if(lockConfig.soundValue == 100) 50 else if(lockConfig.soundValue == 50) 0 else 100
                            else -> soundValue
                        }
                        val result = lockConfigA0UseCase.setSoundValue(value)
                        showLog("Toggle sound value at type ${lockConfig.soundType}, result = $result \n")
                        result
                    }
                    .catch { e -> showLog("setSoundValue exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Not support device status.\n") }
        }
    }

    private fun toggleVirtualCode() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("toggleVirtualCode exception $e \n") }
                    .map { lockConfig ->
                        val isVirtualCodeOn = lockConfig.virtualCode == BleV2Lock.VirtualCode.CLOSE.value
                        val result = lockConfigA0UseCase.setVirtualCode(isVirtualCodeOn)
                        showLog("Set guiding code to ${isVirtualCodeOn}, result = $result \n")
                        result
                    }
                    .catch { e -> showLog("setVirtualCode exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Not support toggleVirtualCode.\n") }
        }
    }

    private fun toggleTwoFA() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("toggleTwoFA exception $e \n") }
                    .map { lockConfig ->
                        val isTwoFAOn = lockConfig.twoFA == BleV2Lock.TwoFA.CLOSE.value
                        val result = lockConfigA0UseCase.setTwoFA(isTwoFAOn)
                        showLog("Set twoFA to ${isTwoFAOn}, result = $result \n")
                    }
                    .catch { e -> showLog("toggleTwoFA exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Not support toggleTwoFA.\n") }
        }
    }

    private fun toggleVacationMode() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.DeviceStatusD6
                val isVacationModeOn = when (deviceStatusD6.config.isVacationModeOn) {
                    true -> { false }
                    false -> { true }
                }
                flow { emit(lockConfigD4UseCase.setVactionMode(isVacationModeOn)) }
                    .catch { e -> showLog("toggleVacationMode exception $e \n") }
                    .map { result ->
                        showLog("Set vacation mode to ${isVacationModeOn}, result = $result \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("toggleVacationMode exception $e \n") }
                    .map { lockConfig ->
                        val isVacationModeOn = lockConfig.vacationMode == BleV2Lock.VacationMode.CLOSE.value
                        val result = lockConfigA0UseCase.setVacationMode(isVacationModeOn)
                        showLog("Set vacation mode to ${isVacationModeOn}, result = $result \n")
                        result
                    }
                    .catch { e -> showLog("toggleVacationMode exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Not support toggleVacationMode.\n") }
        }
    }

    private fun toggleGuidingCode() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.DeviceStatusD6
                val isGuidingCodeOn = when (deviceStatusD6.config.isGuidingCodeOn) {
                    true -> { false }
                    false -> { true }
                }
                flow { emit(lockConfigD4UseCase.setGuidingCode(isGuidingCodeOn)) }
                    .catch { e -> showLog("toggleGuidingCode exception $e \n") }
                    .map { result ->
                        showLog("Set guiding code to ${isGuidingCodeOn}, result = $result \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("toggleGuidingCode exception $e \n") }
                    .map { lockConfig ->
                        val isGuidingCodeOn = lockConfig.guidingCode == BleV2Lock.GuidingCode.CLOSE.value
                        val result = lockConfigA0UseCase.setGuidingCode(isGuidingCodeOn)
                        showLog("Set guiding code to ${isGuidingCodeOn}, result = $result \n")
                        result
                    }
                    .catch { e -> showLog("toggleGuidingCode exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Not support toggleGuidingCode.\n") }
        }
    }

    private fun toggleAutoLock(autoLockTime: Int) {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.DeviceStatusD6
                val isAutoLock = when (deviceStatusD6.config.isAutoLock) {
                    true -> { false }
                    false -> { true }
                }
                flow { emit(lockConfigD4UseCase.setAutoLock(isAutoLock, autoLockTime)) }
                    .catch { e -> showLog("toggleAutoLock exception $e \n") }
                    .map { result ->
                        if (isAutoLock)
                            showLog("Set auto lock to ${isAutoLock} and auto lock time to $autoLockTime, result = $result \n")
                        else
                            showLog("Set auto lock to ${isAutoLock}, result = $result \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("toggleAutoLock exception $e \n") }
                    .map { lockConfig ->
                        val isAutoLock = lockConfig.autoLock == BleV2Lock.AutoLock.CLOSE.value
                        val result = lockConfigA0UseCase.setAutoLock(isAutoLock, autoLockTime)
                        showLog("Set auto lock to $isAutoLock and auto lock time to $autoLockTime, result = $result \n")
                        result
                    }
                    .catch { e -> showLog("toggleAutoLock exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
        }

            else -> { showLog("Not support toggleAutoLock.\n") }
        }
    }

    private fun setLockLocation(latitude: Double, longitude: Double) {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                flow { emit(lockConfigD4UseCase.setLocation(latitude = latitude, longitude = longitude)) }
                    .catch { e -> showLog("setLockLocation exception $e \n") }
                    .map { result ->
                        showLog("Set lock location to (${latitude}, ${longitude}), result = $result \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockConfigA0UseCase.setLocation(latitude = latitude, longitude = longitude)) }
                    .catch { e -> showLog("setLockLocation exception $e \n") }
                    .map { result ->
                        showLog("Set lock location to (${latitude}, ${longitude}), result = $result \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support setLockLocation.\n")
            }
        }
    }

    private fun toggleOperatingSound() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("toggleOperatingSound exception $e \n") }
                    .map { lockConfig ->
                        val isOperatingSoundOn = lockConfig.operatingSound == BleV2Lock.OperatingSound.CLOSE.value
                        val result = lockConfigA0UseCase.setOperatingSound(isOperatingSoundOn)
                        showLog("Toggle operating sound to ${isOperatingSoundOn}, result = $result \n")
                        result
                    }
                    .catch { e -> showLog("toggleOperatingSound exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Not support toggleOperatingSound.\n") }
        }
    }

    private fun toggleShowFastTrackMode() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("toggleShowFastTrackMode exception $e \n") }
                    .map { lockConfig ->
                        val isShowFastTrackModeOn = lockConfig.showFastTrackMode == BleV2Lock.ShowFastTrackMode.CLOSE.value
                        val result = lockConfigA0UseCase.setShowFastTrackMode(isShowFastTrackModeOn)
                        showLog("Set show fast track mode to ${isShowFastTrackModeOn}, result = $result \n")
                    }
                    .catch { e -> showLog("toggleShowFastTrackMode exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Not support toggleShowFastTrackMode.\n") }
        }
    }

    private fun determineLockDirection() {
        showLog("Determine lock direction:")
        flow { emit(lockDirectionUseCase()) }
            .catch { e -> showLog("determineLockDirection exception $e \n") }
            .map { deviceStatus ->
                when (deviceStatus) {
                    is DeviceStatus.DeviceStatusD6 -> {
                        _currentDeviceStatus = DeviceStatus.DeviceStatusD6(
                            deviceStatus.config,
                            deviceStatus.lockState,
                            deviceStatus.battery,
                            deviceStatus.batteryState,
                            deviceStatus.timestamp
                        )
                        updateCurrentDeviceStatusOrNotification(_currentDeviceStatus)
                    }
                    is DeviceStatus.DeviceStatusA2 -> {
                        if(deviceStatus.direction == BleV2Lock.Direction.NOT_SUPPORT.value) {
                            throw LockStatusException.LockFunctionNotSupportException()
                        } else {
                            _currentDeviceStatus = DeviceStatus.DeviceStatusA2(
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
                        showLog("Not support determine lock direction!!\n")
                    }
                }
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getLockName() {
        flow { emit(lockNameUseCase.getName()) }
            .catch { e -> showLog("getLockName exception $e \n") }
            .map { name ->
                showLog("Lock name = ${name}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun setLockName(name: String) {
        flow { emit(lockNameUseCase.setName(name)) }
            .catch { e -> showLog("setLockName exception $e \n") }
            .map { result ->
                showLog("Set lock name to \"$name\", result = ${result}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun isAdminCodeExists() {
        flow { emit(adminCodeUseCase.isAdminCodeExists()) }
            .catch { e -> showLog("isAdminCodeExists exception $e \n") }
            .map { result ->
                showLog("Is Admin code exists = ${result}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun createAdminCode(code: String) {
        flow { emit(adminCodeUseCase.createAdminCode(code)) }
            .catch { e -> showLog("createAdminCode exception $e \n") }
            .map { result ->
                showLog("Create Admin code \"$code\", result = ${result}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun updateAdminCode(oldCode: String, newCode: String) {
        flow { emit(adminCodeUseCase.updateAdminCode(oldCode, newCode)) }
            .catch { e -> showLog("updateAdminCode exception $e \n") }
            .map { result ->
                adminCode = newCode
                showLog("Update Admin code from \"$oldCode\" to \"$newCode\", result = ${result}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun togglePlugState() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusB0 -> {
                val deviceStatusB0 = _currentDeviceStatus as DeviceStatus.DeviceStatusB0
                val plugState =
                    if (deviceStatusB0.plugState == BleV2Lock.PlugState.POWER_ON.value) {
                        BleV2Lock.PlugState.POWER_OFF.value
                    } else {
                        BleV2Lock.PlugState.POWER_ON.value
                    }
                showLog("togglePlugState $plugState\n")
                flow { emit(plugConfigUseCase.setPlugState(plugState)) }
                    .catch { e -> showLog("togglePlugState exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {}
        }
    }

    private fun updateCurrentDeviceStatusOrNotification(sunionBleNotification: SunionBleNotification) {
        when (sunionBleNotification) {
            is DeviceStatus -> {
                showLog("Current is ${sunionBleNotification::class.simpleName}: ${_currentDeviceStatus}\n")
            }
            is Alert -> {
                showLog("Current is ${sunionBleNotification::class.simpleName}: ${_currentSunionBleNotification}\n")
            }
            is Access -> {
                showLog("Current is ${sunionBleNotification::class.simpleName}: ${_currentSunionBleNotification}\n")
            }
            else -> {
                showLog("Unknown device status or alert!!\n")
            }
        }
    }

    private fun getFirmwareVersion() {
        flow { emit(lockUtilityUseCase.getFirmwareVersion()) }
            .catch { e -> showLog("getFirmwareVersion exception $e \n") }
            .map { version ->
                showLog("Get firmware version: $version \n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun factoryReset(adminCode: String) {
        flow { emit(lockUtilityUseCase.factoryReset(adminCode)) }
            .catch { e -> showLog("factoryReset exception $e \n") }
            .map { result ->
                showLog("Factory reset: $result \n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun factoryReset() {
        flow { emit(lockUtilityUseCase.factoryReset()) }
            .catch { e -> showLog("factoryReset exception $e \n") }
            .map { result ->
                showLog("Factory reset: $result \n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun queryTokenArray(){
        flow { emit(lockTokenUseCase.queryTokenArray()) }
            .catch { e -> showLog("queryTokenArray exception $e \n") }
            .map { tokenArray ->
                showLog("queryTokenArray: $tokenArray\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun queryToken(){
        flow { emit(lockTokenUseCase.queryTokenArray()) }
            .catch { e -> showLog("queryToken exception $e \n") }
            .map { tokenArray ->
                tokenArray.forEach { index ->
                    val deviceToken = lockTokenUseCase.queryToken(index)
                    if(deviceToken.isPermanent){
                        showLog("queryToken[$index] is permanent token: $deviceToken\n")
                    } else {
                        showLog("queryToken[$index] is one time token: ${deviceToken.token} name:${deviceToken.name} permission:${deviceToken.permission}\n")
                    }
                }
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun addOneTimeToken(permission: String, name: String) {
        flow { emit(lockTokenUseCase.addOneTimeToken(permission,name)) }
            .catch { e -> showLog("addOneTimeToken exception $e \n") }
            .map { result ->
                    showLog("addOneTimeToken permission:$permission name:$name \nresult= $result\n")
                }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun editToken(index:Int, permission: String, name: String) {
        flow { emit(lockTokenUseCase.editToken(index, permission, name)) }
            .catch { e -> showLog("editToken exception $e \n") }
            .map { result ->
                showLog("editToken[$index] permission:$permission name:$name \nresult= $result\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun deleteToken(index: Int, code: String = "") {
        flow { emit(lockTokenUseCase.deleteToken(index, code)) }
            .catch { e -> showLog("deleteToken exception $e \n") }
            .map { result ->
                showLog("deleteToken[$index] code:$code \nresult= $result\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getAccessCodeArray(){
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusD6 -> {
                flow { emit(lockAccessCodeUseCase.getAccessCodeArray()) }
                    .catch { e -> showLog("getAccessCodeArray exception $e \n") }
                    .map { accessCodeArray ->
                        showLog("getAccessCodeArray: $accessCodeArray\n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.accessCodeQuantity != BleV2Lock.AccessCodeQuantity.NOT_SUPPORT.value) {
                            val accessCodeArray = lockAccessUseCase.getAccessCodeArray()
                            showLog("getAccessCodeArray: $accessCodeArray\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("getAccessCodeArray exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support getAccessCodeArray. \n")
            }
        }
    }

    private fun queryAccessCode(){
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                flow { emit(lockAccessCodeUseCase.getAccessCodeArray()) }
                    .catch { e -> showLog("getAccessCodeArray exception $e \n") }
                    .map { list ->
                        val indexIterable = list
                            .mapIndexed { index, boolean -> if (boolean && index != 0) index else -1 }
                            .filter { index -> index != -1 }
                        indexIterable.forEach { index ->
                            val accessCode = lockAccessCodeUseCase.queryAccessCode(index)
                            showLog("queryAccessCode[$index] is access code: $accessCode\n")
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if (result.accessCodeQuantity != BleV2Lock.AccessCodeQuantity.NOT_SUPPORT.value) {
                            val list = lockAccessUseCase.getAccessCodeArray()
                            val indexIterable = list.subList(0, list.size - 2)
                                .mapIndexed { index, boolean -> if (boolean && index != 0) index else -1 }
                                .filter { index -> index != -1 }
                            Timber.d("indexIterable: $indexIterable")
                            indexIterable
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("getAccessCodeArray exception $e \n") }
                    .map { indexIterable ->
                        indexIterable.forEach { index ->
                            val accessCode = lockAccessUseCase.queryAccessCode(index)
                            if(accessCode.type == 0) {
                                showLog("queryAccessCode[$index] is access code: $accessCode\n")
                            }
                        }
                    }
                    .catch { e -> showLog("queryAccessCode exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support queryAccessCode. \n")
            }
        }
    }

    private fun addAccessCode() {
        val isEnabled = true
        val name = "Tom"
        val code = "1234"
        val index = 1
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                flow { emit(lockAccessCodeUseCase.addAccessCode(index, isEnabled, name, code, scheduleType)) }
                    .catch { e -> showLog("addAccessCode exception $e \n") }
                    .map { result ->
                        showLog("addAccessCode index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nresult= $result\n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.accessCodeQuantity != BleV2Lock.AccessCodeQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.addAccessCode(index, isEnabled, scheduleType, name, code)
                            showLog("addAccessCode index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("addAccessCode exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support addAccessCode. \n")
            }
        }
    }

    private fun editAccessCode() {
        val isEnabled = true
        val name = "Tom"
        val code = "2345"
        val index = 1
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                flow { emit(lockAccessCodeUseCase.editAccessCode(index, isEnabled, name, code, scheduleType)) }
                    .catch { e -> showLog("editAccessCode exception $e \n") }
                    .map { result ->
                        showLog("editAccessCode:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nresult= $result\n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.accessCodeQuantity != BleV2Lock.AccessCodeQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.editAccessCode(index, isEnabled, scheduleType, name, code)
                            showLog("editAccessCode index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("editAccessCode exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support editAccessCode. \n")
            }
        }
    }

    private fun deleteAccessCode(index: Int) {
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                flow { emit(lockAccessCodeUseCase.deleteAccessCode(index)) }
                    .catch { e -> showLog("deleteAccessCode exception $e \n") }
                    .map { result ->
                        showLog("deleteAccessCode[$index] \nresult= $result\n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.accessCodeQuantity != BleV2Lock.AccessCodeQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.deleteAccessCode(index)
                            showLog("deleteAccessCode[$index] \nisSuccess= $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("deleteAccessCode exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support deleteAccessCode. \n")
            }
        }
    }

    private fun getAccessCardArray(){
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.accessCardQuantity != BleV2Lock.AccessCardQuantity.NOT_SUPPORT.value) {
                            val accessCardArray = lockAccessUseCase.getAccessCardArray()
                            showLog("getAccessCardArray: $accessCardArray\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("getAccessCardArray exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support getAccessCardArray. \n")
            }
        }
    }

    private fun queryAccessCard(){
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if (result.accessCardQuantity != BleV2Lock.AccessCardQuantity.NOT_SUPPORT.value) {
                            val list = lockAccessUseCase.getAccessCardArray()
                            val indexIterable = list.subList(0, list.size - 2)
                                .mapIndexed { index, boolean -> if (boolean && index != 0) index else -1 }
                                .filter { index -> index != -1 }
                            Timber.d("indexIterable: $indexIterable")
                            indexIterable
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("getAccessCardArray exception $e \n") }
                    .map{ indexIterable ->
                        indexIterable.forEach { index ->
                            val accessCard = lockAccessUseCase.queryAccessCard(index)
                            if(accessCard.type == 1) {
                                showLog("queryAccessCard[$index] is access card: $accessCard\n")
                            }
                        }
                    }
                    .catch { e -> showLog("queryAccessCard exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support queryAccessCard. \n")
            }
        }
    }

    private fun addAccessCard() {
        val isEnabled = true
        val name = "Tom2"
        val code = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0xFC.toByte(),
            0xE8.toByte(), 0xFA.toByte(), 0x5B)
        val index = 2
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.accessCardQuantity != BleV2Lock.AccessCardQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.addAccessCard(index, isEnabled, scheduleType, name, code)
                            showLog("addAccessCard index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("addAccessCard exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support addAccessCard. \n")
            }
        }
    }

    private fun editAccessCard() {
        val isEnabled = true
        val name = "Tom23"
        val code =  byteArrayOf(0x88.toByte(), 0x04, 0x37, 0x75, 0x6A, 0x57, 0x58, 0x81.toByte())
        val index = 2
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.accessCardQuantity != BleV2Lock.AccessCardQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.editAccessCard(index, isEnabled, scheduleType, name, code)
                            showLog("editAccessCard index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("editAccessCard exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support editAccessCard. \n")
            }
        }
    }

    private fun deleteAccessCard(index: Int) {
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.accessCardQuantity != BleV2Lock.AccessCardQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.deleteAccessCard(index)
                            showLog("deleteAccessCard[$index] \nisSuccess= $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("deleteAccessCard exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support deleteAccessCard. \n")
            }
        }
    }

    private fun deviceGetAccessCard(){
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.accessCardQuantity != BleV2Lock.AccessCardQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.deviceGetAccessCard(5)
                            showLog("deviceGetAccessCard: $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("deviceGetAccessCard exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support deviceGetAccessCard. \n")
            }
        }
    }

    private fun getFingerprintArray(){
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.fingerprintQuantity != BleV2Lock.FingerprintQuantity.NOT_SUPPORT.value) {
                            val fingerprintArray = lockAccessUseCase.getFingerprintArray()
                            showLog("getFingerprintArray: $fingerprintArray\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("getFingerprintArray exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support getFingerprintArray. \n")
            }
        }
    }

    private fun queryFingerprint(){
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.fingerprintQuantity != BleV2Lock.FingerprintQuantity.NOT_SUPPORT.value) {
                            val list = lockAccessUseCase.getFingerprintArray()
                            val indexIterable = list.subList(0, list.size - 2)
                                .mapIndexed { index, boolean -> if (boolean && index != 0) index else -1 }
                                .filter { index -> index != -1 }
                            Timber.d("indexIterable: $indexIterable")
                            indexIterable
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("getFingerprintArray exception $e \n") }
                    .map { indexIterable ->
                        indexIterable.forEach { index ->
                            val fingerprint = lockAccessUseCase.queryFingerprint(index)
                            if(fingerprint.type == 2) {
                                showLog("queryFingerprint[$index] is Fingerprint: $fingerprint\n")
                            }
                        }
                    }
                    .catch { e -> showLog("queryFingerprint exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support queryFingerprint. \n")
            }
        }
    }

    private fun addFingerprint() {
        val isEnabled = true
        val name = "Tom3"
        val code = "80"
        val index = 3
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.fingerprintQuantity != BleV2Lock.FingerprintQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.addFingerprint(index, isEnabled, scheduleType, name, code.accessCodeToHex())
                            showLog("addFingerprint index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("addFingerprint exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support addFingerprint. \n")
            }
        }
    }

    private fun editFingerprint() {
        val isEnabled = true
        val name = "Tom34"
        val code = "100"
        val index = 3
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.fingerprintQuantity != BleV2Lock.FingerprintQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.editFingerprint(index, isEnabled, scheduleType, name, code.accessCodeToHex())
                            showLog("editFingerprint index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("editFingerprint exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support editFingerprint. \n")
            }
        }
    }

    private fun deleteFingerprint(index: Int) {
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.fingerprintQuantity != BleV2Lock.FingerprintQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.deleteFingerprint(index)
                            showLog("deleteFingerprint[$index] \nisSuccess= $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("deleteFingerprint exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support deleteFingerprint. \n")
            }
        }
    }

    private fun deviceGetFingerprint(){
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.fingerprintQuantity != BleV2Lock.FingerprintQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.deviceGetFingerprint(5)
                            showLog("deviceGetFingerprint: $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("deviceGetFingerprint exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support deviceGetFingerprint. \n")
            }
        }
    }

    private fun getFaceArray(){
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.faceQuantity != BleV2Lock.FaceQuantity.NOT_SUPPORT.value) {
                            val faceArray = lockAccessUseCase.getFaceArray()
                            showLog("getFaceArray: $faceArray\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("getFaceArray exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support getFaceArray. \n")
            }
        }
    }

    private fun queryFace(){
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if (result.faceQuantity != BleV2Lock.FaceQuantity.NOT_SUPPORT.value) {
                            val list = lockAccessUseCase.getFaceArray()
                            val indexIterable = list.subList(0, list.size - 2)
                                .mapIndexed { index, boolean -> if (boolean && index != 0) index else -1 }
                                .filter { index -> index != -1 }
                            Timber.d("indexIterable: $indexIterable")
                            indexIterable
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("getFaceArray exception $e \n") }
                    .map { indexIterable ->
                        indexIterable.forEach { index ->
                            val queryFace = lockAccessUseCase.queryFace(index)
                            if (queryFace.type == 3) {
                                showLog("queryFace[$index] is face: $queryFace\n")
                            }
                        }
                    }
                    .catch { e -> showLog("queryFace exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support queryFace. \n")
            }
        }
    }

    private fun addFace() {
        val isEnabled = true
        val name = "Tom4"
        val code = "70"
        val index = 4
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.faceQuantity != BleV2Lock.FaceQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.addFace(index, isEnabled, scheduleType, name, code.accessCodeToHex())
                            showLog("addFace index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("addFace exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support addFace. \n")
            }
        }
    }

    private fun editFace() {
        val isEnabled = true
        val name = "Tom45"
        val code = "80"
        val index = 4
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.faceQuantity != BleV2Lock.FaceQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.editFace(index, isEnabled, scheduleType, name, code.accessCodeToHex())
                            showLog("editFace index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("editFace exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support editFace. \n")
            }
        }
    }

    private fun deleteFace(index: Int) {
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.faceQuantity != BleV2Lock.FaceQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.deleteFace(index)
                            showLog("deleteFace[$index] \nisSuccess= $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("deleteFace exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support deleteFace. \n")
            }
        }
    }

    private fun deviceGetFace(){
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        if(result.faceQuantity != BleV2Lock.FaceQuantity.NOT_SUPPORT.value) {
                            val isSuccess = lockAccessUseCase.deviceGetFace(5)
                            showLog("deviceGetFace: $isSuccess\n")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("deviceGetFace exception $e \n") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support deviceGetFace. \n")
            }
        }
    }

    private fun getEventQuantity(){
        flow { emit(lockEventLogUseCase.getEventQuantity()) }
            .catch { e -> showLog("getEventQuantity exception $e \n") }
            .map { result ->
                showLog("getEventQuantity \nresult= $result\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getEvent(){
        flow { emit(lockEventLogUseCase.getEventQuantity()) }
            .catch { e -> showLog("getEvent exception $e \n") }
            .map { result ->
                for(index in 0 until result){
                    val eventLog = lockEventLogUseCase.getEvent(index)
                    showLog("getEvent index[$index] \neventLog: $eventLog\n")
                }
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun deleteEvent(index: Int){
        flow { emit(lockEventLogUseCase.deleteEvent(index)) }
            .catch { e -> showLog("deleteEvent exception $e \n") }
            .map { result ->
                showLog("deleteEvent index[$index] \nresult= $result\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getLockSupportedUnlockTypes() {
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusA2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .map { result ->
                        showLog("getLockSupportedUnlockTypes result= $result\n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Not support getLockSupportedUnlockTypes. \n")
            }
        }
    }

    private fun collectWifiList(){
        collectWifiListJob?.cancel()
        collectWifiListJob = lockWifiUseCase.collectWifiList()
            .catch { e -> showLog("collectWifiList exception $e \n") }
            .onEach { wifi ->
                when (wifi) {
                    WifiList.End -> {
                        _uiState.update { it.copy(isLoading = false) }
                        collectWifiListJob?.cancel()
                        showLog("collectWifiList end\n")
                        if(scanWifiJob != null){
                            scanWifiJob?.cancel()
                            scanWifiJob = null
                        }
                    }
                    is WifiList.Wifi -> {
                        showLog("collectWifiList result: \nssid: ${wifi.ssid} needPassword: ${wifi.needPassword}\n")
                    }
                }
            }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun scanWifi(){
        if (scanWifiJob != null) return
        scanWifiJob = flow { emit(lockWifiUseCase.scanWifi()) }
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
            .catch { e -> Timber.e(e) }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun connectToWifi(ssid: String, password: String){
        if (isCollectingConnectToWifiState) return
        connectToWifiJob?.cancel()
        connectToWifiJob = lockWifiUseCase
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
                    Timber.e("CWifiFail")
                    showLog("Connect to Wi-Fi failed, because not had provisionTicket.")
                }
            }
            .catch {
                Timber.e(it)
                if (it.message?.contains("Disconnected") == false)
                    Timber.e("CWifi listener exception $it")
                showLog("Connect to Wi-Fi failed. $it")
            }
            .launchIn(viewModelScope)

        flow { emit(lockWifiUseCase.connectToWifi(ssid, password)) }
            .flowOn(Dispatchers.IO)
            .onStart {
                isConnectingToWifi = true
                _uiState.update { it.copy(isLoading = true) }
            }
            .catch {
                Timber.e("CWifi sender exception $it")
                showLog("Connect to Wi-Fi failed. $it")
            }
            .launchIn(viewModelScope)

        viewModelScope.launch(Dispatchers.IO) {
            delay(120_000)
            if (isConnectingToWifi) {
                Timber.e("CWifi timeout")
                showLog("Connect to Wi-Fi failed.")
            }
        }
    }

    private fun setOTACancel(target: Int) {
        flow { emit(lockOTAUseCase.setOTACancel(target)) }
            .catch { e -> showLog("getOTAStatusCancel exception $e \n") }
            .map { result ->
                showLog("getOTAStatusCancel result= $result\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun disconnect() {
        statefulConnection.disconnect()
        _bleConnectionStateListener?.cancel()
        _bleSunionBleNotificationListener?.cancel()
        _currentDeviceStatus = DeviceStatus.UNKNOWN
        _currentSunionBleNotification = SunionBleNotification.UNKNOWN
        _uiState.update { it.copy(isLoading = false, isConnectedWithLock = false) }
        showLog("Disconnected \n")
    }

    fun setQRCodeContent(content: String) {
        viewModelScope.launch {
            val qrCodeContent =
                runCatching { lockQRCodeUseCase.parseQRCodeContent(BuildConfig.BARCODE_KEY, content) }.getOrNull()
                    ?: runCatching { lockQRCodeUseCase.parseWifiQRCodeContent(BuildConfig.BARCODE_KEY, content) }.getOrNull()

            if (qrCodeContent == null) {
                showLog("Unknown QR-Code.")
                _uiEvent.emit(UiEvent.Complete)
                return@launch
            }

            Timber.d("qrCodeContent: $qrCodeContent")
            _lockConnectionInfo = LockConnectionInfo.from(qrCodeContent)
            Timber.d("_lockConnectionInfo: $_lockConnectionInfo")
            showLog("Lock connection information:", true)
            showLog("  macAddress = ${_lockConnectionInfo!!.macAddress}")
            showLog("  oneTimeToken = ${_lockConnectionInfo!!.oneTimeToken}")
            showLog("  keyOne = ${_lockConnectionInfo!!.keyOne} \n")
            showLog("Please execute Connect to pair with lock.\n")
            _uiEvent.emit(UiEvent.Complete)
            _uiState.update { it.copy(btnEnabled = true) }
        }
    }

    private fun showLog(msg: String, isClear: Boolean = false) {
        if (isClear)
            _logList.value.clear()
        _logList.update {
            _logList.value.toMutableList().apply { this.add(msg) }
        }
    }

    fun closeMessageDialog() {
        _uiState.update { it.copy(message = "") }
    }

    fun closeBluetoothEnableDialog() {
        _uiState.update { it.copy(shouldShowBluetoothEnableDialog = false) }
    }

    fun handleFileSelection(fileUri: Uri?) {
        if (fileUri != null) {
            currentFileUri = fileUri
            viewModelScope.launch(Dispatchers.IO) {
                val fileLength = getFileLength(fileUri)
                Timber.d("handleFileSelection: $fileLength")
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

    fun otaUpdate(target: Int, signature:String) {
        val checkResult = when (signature) {
            signatureV005 -> {
                fileCheck(hash256V005)
            }
            signatureV008 -> {
                fileCheck(hash256V008)
            }
            signatureV012 -> {
                fileCheck(hash256V012)
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

                        showLog("Send start ${blockNumber * chunkSize} / $fileSize = 0%")
                        while (bytesRead != -1) {

                            val result = lockOTAUseCase.transferOTAData(
                                blockNumber * chunkSize,
                                buffer.take(bytesRead).toByteArray()
                            )
                            Timber.d("end: $result")

                            blockNumber++
                            showLog("Sending ${blockNumber * chunkSize} / $fileSize = ${(blockNumber * chunkSize * 100 / fileSize) } %")
                            bytesRead = inputStream.read(buffer)
                        }
                        showLog("Send end ${blockNumber * chunkSize} / $fileSize = 100%")
                        val result = lockOTAUseCase.setOTAFinish(target, fileSize, ivString, signature)
                        Timber.d("end: $result")
                    }

                    inputStream.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                    lockOTAUseCase.setOTACancel(target)
                }
            }
        }
    }

    fun fileCheck(checkHash:String): Boolean {
        if (currentFileUri != null) {
            val contentResolver: ContentResolver = application.contentResolver
            val inputStream: InputStream? = contentResolver.openInputStream(currentFileUri!!)

            if (inputStream == null) {
                Timber.d("fileCheck: Failed to open input stream.")
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

            Timber.d("fileCheck: ${bytes.toHexString()}")

            return bytes.toHexString() == checkHash
        }
        showLog("Not choose OTA file")
        return false
    }
}

data class UiState(
    val isLoading:Boolean = false,
    val isBlueToothAvailable: Boolean = false,
    val isConnectedWithLock: Boolean = false,
    val shouldShowBluetoothEnableDialog: Boolean = false,
    val taskCode: Int = -1,
    val btnEnabled: Boolean = false,
    val shouldShowTaskList: Boolean = false,
    val message: String = ""
)

sealed class UiEvent {
    object Complete : UiEvent()
}

object TaskCode {
    const val Connect = 0
    const val GetLockTime = 1
    const val SetLockTime = 2
    const val SetLockTimeZone = 3
    const val GetDeviceStatus = 4
    const val IsAdminCodeExists = 5
    const val CreateAdminCode = 6
    const val UpdateAdminCode = 7
    const val GetLockName = 8
    const val SetLockName = 9
    const val ToggleLockState = 10
    const val DetermineLockDirection = 11
    const val ToggleKeyPressBeep = 12
    const val ToggleVacationMode = 13
    const val GetLockConfig = 14
    const val ToggleGuidingCode = 15
    const val ToggleAutoLock = 16
    const val SetLockLocation = 17
    const val QueryTokenArray = 18
    const val QueryToken = 19
    const val AddOneTimeToken = 20
    const val EditToken = 21
    const val DeleteToken = 22
    const val GetAccessCodeArray = 23
    const val QueryAccessCode = 24
    const val AddAccessCode = 25
    const val EditAccessCode = 26
    const val DeleteAccessCode = 27
    const val GetAccessCardArray = 28
    const val QueryAccessCard = 29
    const val AddAccessCard = 30
    const val EditAccessCard = 31
    const val DeleteAccessCard = 32
    const val DeviceGetAccessCard = 33
    const val GetFingerprintArray = 34
    const val QueryFingerprint = 35
    const val AddFingerprint = 36
    const val EditFingerprint = 37
    const val DeleteFingerprint = 38
    const val DeviceGetFingerprint = 39
    const val GetFaceArray = 40
    const val QueryFace = 41
    const val AddFace = 42
    const val EditFace = 43
    const val DeleteFace = 44
    const val DeviceGetFace = 45
    const val GetEventQuantity = 46
    const val GetEvent = 47
    const val DeleteEvent = 48
    const val ToggleSecurityBolt = 49
    const val ToggleVirtualCode = 50
    const val ToggleTwoFA = 51
    const val ToggleOperatingSound = 52
    const val ToggleShowFastTrackMode = 53
    const val GetLockSupportedUnlockTypes = 54
    const val ScanWifi = 55
    const val ConnectToWifi = 56
    const val SetOTAUpdate = 57
    const val SetOTACancel = 58
    const val TogglePlugState = 59
    const val GetFwVersion = 80
    const val FactoryReset = 81
    const val FactoryResetNoAdmin = 82
    const val Disconnect = 99
}