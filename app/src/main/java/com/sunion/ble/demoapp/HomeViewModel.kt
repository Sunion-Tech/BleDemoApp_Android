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
import com.sunion.core.ble.isNotSupport
import com.sunion.core.ble.isSupport2Byte
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
    private val deviceStatus82UseCase: DeviceStatus82UseCase,
    private val lockConfig80UseCase: LockConfig80UseCase,
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

    private var _currentDeviceStatus: SunionBleNotification = DeviceStatus.UNKNOWN

    private var _currentSunionBleNotification: SunionBleNotification = SunionBleNotification.UNKNOWN

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
            // Get lock time zone
            TaskCode.GetLockTimeZone -> {
                getLockTimeZone()
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
            // Auto unlock toggle lock state
            TaskCode.AutoUnlockToggleLockState -> {
                autoUnlockToggleLockState()
            }
            // Toggle security bolt
            TaskCode.ToggleSecurityBolt -> {
                toggleSecurityBolt()
            }
            // Auto unlock toggle lock state
            TaskCode.AutoUnlockToggleSecurityBolt -> {
                autoUnlockToggleSecurityBolt()
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
            // Toggle sabbath mode
            TaskCode.ToggleSabbathMode -> {
                toggleSabbathMode()
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
            // Get RF version
            TaskCode.GetRfVersion -> {
                getRfVersion()
            }
            // Get MCU version
            TaskCode.GetMcuVersion -> {
                getMcuVersion()
            }
            // Factory reset
            TaskCode.FactoryReset -> {
                factoryReset(adminCode)
            }
            // Factory reset
            TaskCode.FactoryResetNoAdmin -> {
                factoryReset()
            }
            // Restart
            TaskCode.Restart -> {
                restart()
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
            // Get Event By Address
            TaskCode.GetEventByAddress -> {
                getEventByAddress(0)
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
        val functionName = ::connect.name
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
                                showLog("$functionName to lock timeout")
                            }
                            else -> {
                                unless(
                                    event.data != null
                                ) {
                                    showLog("$functionName to lock failed: ${event.message}")
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
                            .catch { e -> showLog("Incoming SunionBleNotification exception $e") }
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
                            showLog("$functionName to lock succeed.")
                            // connect with oneTimeToken
                            if (_lockConnectionInfo!!.permanentToken.isNullOrEmpty()) {
                                showLog("After pairing with lock, you can get lock connection information from statefulConnection.lockConnectionInfo and save permanent token for later use.")
                            }
                            showLog("Lock connection information:")
                            showLog("${_lockConnectionInfo}")
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
            showLog("Connecting to ${_lockConnectionInfo!!.macAddress}...")
            statefulConnection.establishConnection(
                macAddress = _lockConnectionInfo!!.macAddress,
                keyOne = _lockConnectionInfo!!.keyOne,
                oneTimeToken = _lockConnectionInfo!!.oneTimeToken,
                permanentToken = _lockConnectionInfo!!.permanentToken,
                isSilentlyFail = false
            )
        }
    }

    private fun getLockTime() {
        val functionName = ::getLockTime.name
        flow { emit(lockTimeUseCase.getTime()) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { timeStamp ->
                showLog("$functionName: $timeStamp")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun setLockTime() {
        val functionName = ::setLockTime.name
        flow { emit(lockTimeUseCase.setTime(Instant.now().atZone(ZoneId.systemDefault()).toEpochSecond())) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName result: $result")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getLockTimeZone() {
        val functionName = ::getLockTimeZone.name
        flow { emit(lockTimeUseCase.getTimeZone()) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName result: $result")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun setLockTimeZone() {
        val functionName = ::setLockTimeZone.name
        flow { emit(lockTimeUseCase.setTimeZone(ZoneId.systemDefault().id)) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName to ${ZoneId.systemDefault().id} result: $result")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getDeviceStatus() {
        val functionName = ::getDeviceStatus.name
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                flow { emit(deviceStatusD6UseCase()) }
                    .catch { e -> showLog("$functionName.D6 exception $e") }
                    .map { deviceStatus ->
                        _currentDeviceStatus = DeviceStatus.D6(
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
            is DeviceStatus.A2 -> {
                flow { emit(deviceStatusA2UseCase()) }
                    .catch { e -> showLog("$functionName.A2 exception $e") }
                    .map { deviceStatus ->
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
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.B0 -> {
                flow { emit(plugConfigUseCase()) }
                    .catch { e -> showLog("$functionName.B0 exception $e") }
                    .map { deviceStatus ->
                        _currentDeviceStatus = DeviceStatus.B0(
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
            is DeviceStatus.EightTwo -> {
                flow { emit(deviceStatus82UseCase()) }
                    .catch { e -> showLog("$functionName.82 exception $e") }
                    .map { deviceStatus ->
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
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private fun toggleLockState() {
        val functionName = ::toggleLockState.name
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
                flow { emit(deviceStatusD6UseCase.setLockState(desiredState)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { deviceStatus ->
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
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
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
                flow { emit(deviceStatusA2UseCase.setLockState(desiredState)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { deviceStatus ->
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
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
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
                flow { emit(deviceStatus82UseCase.setLockState(desiredState)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { deviceStatus ->
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
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private fun autoUnlockToggleLockState() {
        val functionName = ::autoUnlockToggleLockState.name
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
                flow { emit(deviceStatus82UseCase.setAutoUnlockLockState(desiredState)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName to $desiredState result: $result")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private fun toggleSecurityBolt() {
        val functionName = ::toggleSecurityBolt.name
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
                flow { emit(deviceStatusA2UseCase.setSecurityBolt(state)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { deviceStatus ->
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
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
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
                flow { emit(deviceStatus82UseCase.setSecurityBolt(state)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { deviceStatus ->
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
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private fun autoUnlockToggleSecurityBolt() {
        val functionName = ::autoUnlockToggleSecurityBolt.name
        when (_currentDeviceStatus) {
            is DeviceStatus.EightTwo -> {
                val deviceStatus82 = _currentDeviceStatus as DeviceStatus.EightTwo
                if (deviceStatus82.securityBolt.isNotSupport()) {
                    showLog("$functionName not support.")
                    return
                }
                val state: Int = when (deviceStatus82.securityBolt) {
                    BleV3Lock.SecurityBolt.PROTRUDE.value -> { BleV3Lock.SecurityBolt.NOT_PROTRUDE.value }
                    BleV3Lock.SecurityBolt.NOT_PROTRUDE.value -> {
                        showLog("Already not protrude.")
                        return
                    }
                    else -> {
                        showLog("Unknown security bolt.")
                        return
                    }
                }
                flow { emit(deviceStatus82UseCase.setAutoUnlockSecurityBolt(state)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName to $state result: $result")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private fun getLockConfig() {
        val functionName = ::getLockConfig.name
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                flow { emit(lockConfigD4UseCase.query()) }
                    .catch { e -> showLog("$functionName getLockConfig exception $e") }
                    .map { lockConfig ->
                        showLog("$functionName.D4: $lockConfig")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.A2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        showLog("$functionName.A0: $lockConfig")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.EightTwo -> {
                flow { emit(lockConfig80UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        showLog("$functionName.80: $lockConfig")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun toggleKeyPressBeep(soundValue:Int = 0) {
        val functionName = ::toggleKeyPressBeep.name
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.D6
                val isSoundOn = when (deviceStatusD6.config.isSoundOn) {
                    true -> { false }
                    false -> { true }
                }
                flow { emit(lockConfigD4UseCase.setKeyPressBeep(isSoundOn)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName to $isSoundOn result: $result")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.A2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("lockConfigA0UseCase.query() exception $e") }
                    .map { lockConfig ->
                        val value = when (lockConfig.soundType) {
                            0x01 -> if(lockConfig.soundValue == 100) 0 else 100
                            0x02 -> if(lockConfig.soundValue == 100) 50 else if(lockConfig.soundValue == 50) 0 else 100
                            else -> soundValue
                        }
                        val result = lockConfigA0UseCase.setSoundValue(value)
                        showLog("$functionName at type ${lockConfig.soundType} result: $result")
                        result
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.EightTwo -> {
                flow { emit(lockConfig80UseCase.query()) }
                    .catch { e -> showLog("lockConfig80UseCase.query() exception $e") }
                    .map { lockConfig ->
                        val value = when (lockConfig.soundType) {
                            0x01 -> if(lockConfig.soundValue == 100) 0 else 100
                            0x02 -> if(lockConfig.soundValue == 100) 50 else if(lockConfig.soundValue == 50) 0 else 100
                            else -> soundValue
                        }
                        val result = lockConfig80UseCase.setSoundValue(value)
                        showLog("$functionName at type ${lockConfig.soundType} result: $result")
                        result
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Device status not support.") }
        }
    }

    private fun toggleVirtualCode() {
        val functionName = ::toggleVirtualCode.name
        when (_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isVirtualCodeOn = lockConfig.virtualCode == BleV2Lock.VirtualCode.CLOSE.value
                        val result = lockConfigA0UseCase.setVirtualCode(isVirtualCodeOn)
                        showLog("$functionName to $isVirtualCodeOn result: $result")
                        result
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.EightTwo -> {
                flow { emit(lockConfig80UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isVirtualCodeOn = lockConfig.virtualCode == BleV3Lock.VirtualCode.CLOSE.value
                        val result = lockConfig80UseCase.setVirtualCode(isVirtualCodeOn)
                        showLog("$functionName to $isVirtualCodeOn result: $result")
                        result
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private fun toggleTwoFA() {
        val functionName = ::toggleTwoFA.name
        when (_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isTwoFAOn = lockConfig.twoFA == BleV2Lock.TwoFA.CLOSE.value
                        val result = lockConfigA0UseCase.setTwoFA(isTwoFAOn)
                        showLog("$functionName to $isTwoFAOn result: $result")
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.EightTwo -> {
                flow { emit(lockConfig80UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isTwoFAOn = lockConfig.twoFA == BleV3Lock.TwoFA.CLOSE.value
                        val result = lockConfig80UseCase.setTwoFA(isTwoFAOn)
                        showLog("$functionName to $isTwoFAOn result: $result")
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private fun toggleVacationMode() {
        val functionName = ::toggleVacationMode.name
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.D6
                val isVacationModeOn = when (deviceStatusD6.config.isVacationModeOn) {
                    true -> { false }
                    false -> { true }
                }
                flow { emit(lockConfigD4UseCase.setVacationMode(isVacationModeOn)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName to $isVacationModeOn result: $result")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.A2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isVacationModeOn = lockConfig.vacationMode == BleV2Lock.VacationMode.CLOSE.value
                        val result = lockConfigA0UseCase.setVacationMode(isVacationModeOn)
                        showLog("$functionName to $isVacationModeOn result: $result")
                        result
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.EightTwo -> {
                flow { emit(lockConfig80UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isVacationModeOn = lockConfig.vacationMode == BleV3Lock.VacationMode.CLOSE.value
                        val result = lockConfig80UseCase.setVacationMode(isVacationModeOn)
                        showLog("$functionName to $isVacationModeOn result: $result")
                        result
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private fun toggleGuidingCode() {
        val functionName = ::toggleGuidingCode.name
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.D6
                val isGuidingCodeOn = when (deviceStatusD6.config.isGuidingCodeOn) {
                    true -> { false }
                    false -> { true }
                }
                flow { emit(lockConfigD4UseCase.setGuidingCode(isGuidingCodeOn)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName to $isGuidingCodeOn result: $result")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.A2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isGuidingCodeOn = lockConfig.guidingCode == BleV2Lock.GuidingCode.CLOSE.value
                        val result = lockConfigA0UseCase.setGuidingCode(isGuidingCodeOn)
                        showLog("$functionName to $isGuidingCodeOn result: $result")
                        result
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.EightTwo -> {
                flow { emit(lockConfig80UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isGuidingCodeOn = lockConfig.guidingCode == BleV3Lock.GuidingCode.CLOSE.value
                        val result = lockConfig80UseCase.setGuidingCode(isGuidingCodeOn)
                        showLog("$functionName to $isGuidingCodeOn result: $result")
                        result
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private fun toggleAutoLock(autoLockTime: Int) {
        val functionName = ::toggleAutoLock.name
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.D6
                val isAutoLock = when (deviceStatusD6.config.isAutoLock) {
                    true -> { false }
                    false -> { true }
                }
                flow { emit(lockConfigD4UseCase.setAutoLock(isAutoLock, autoLockTime)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        if (isAutoLock)
                            showLog("$functionName to $isAutoLock and auto lock time to $autoLockTime result: $result")
                        else
                            showLog("$functionName to $isAutoLock result: $result")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.A2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isAutoLock = lockConfig.autoLock == BleV2Lock.AutoLock.CLOSE.value
                        val result = lockConfigA0UseCase.setAutoLock(isAutoLock, autoLockTime)
                        showLog("$functionName to $isAutoLock and auto lock time to $autoLockTime result: $result")
                        result
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.EightTwo -> {
                flow { emit(lockConfig80UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isAutoLock = lockConfig.autoLock == BleV3Lock.AutoLock.CLOSE.value
                        val result = lockConfig80UseCase.setAutoLock(isAutoLock, autoLockTime)
                        showLog("$functionName to $isAutoLock and auto lock time to $autoLockTime result: $result")
                        result
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private fun setLockLocation(latitude: Double, longitude: Double) {
        val functionName = ::setLockLocation.name
        when (_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                flow { emit(lockConfigD4UseCase.setLocation(latitude = latitude, longitude = longitude)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName to (${latitude}, ${longitude}) result: $result")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.A2 -> {
                flow { emit(lockConfigA0UseCase.setLocation(latitude = latitude, longitude = longitude)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName to (${latitude}, ${longitude}) result: $result")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.EightTwo -> {
                flow { emit(lockConfig80UseCase.setLocation(latitude = latitude, longitude = longitude)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName to (${latitude}, ${longitude}) result: $result")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun toggleOperatingSound() {
        val functionName = ::toggleOperatingSound.name
        when (_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isOperatingSoundOn = lockConfig.operatingSound == BleV2Lock.OperatingSound.CLOSE.value
                        val result = lockConfigA0UseCase.setOperatingSound(isOperatingSoundOn)
                        showLog("$functionName to $isOperatingSoundOn result: $result")
                        result
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.EightTwo -> {
                flow { emit(lockConfig80UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isOperatingSoundOn = lockConfig.operatingSound == BleV3Lock.OperatingSound.CLOSE.value
                        val result = lockConfig80UseCase.setOperatingSound(isOperatingSoundOn)
                        showLog("$functionName to $isOperatingSoundOn result: $result")
                        result
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private fun toggleShowFastTrackMode() {
        val functionName = ::toggleShowFastTrackMode.name
        when (_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockConfigA0UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isShowFastTrackModeOn = lockConfig.showFastTrackMode == BleV2Lock.ShowFastTrackMode.CLOSE.value
                        val result = lockConfigA0UseCase.setShowFastTrackMode(isShowFastTrackModeOn)
                        showLog("$functionName to $isShowFastTrackModeOn result: $result")
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.EightTwo -> {
                flow { emit(lockConfig80UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isShowFastTrackModeOn = lockConfig.showFastTrackMode == BleV3Lock.ShowFastTrackMode.CLOSE.value
                        val result = lockConfig80UseCase.setShowFastTrackMode(isShowFastTrackModeOn)
                        showLog("$functionName to $isShowFastTrackModeOn result: $result")
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private fun toggleSabbathMode() {
        val functionName = ::toggleSabbathMode.name
        when (_currentDeviceStatus) {
            is DeviceStatus.EightTwo -> {
                flow { emit(lockConfig80UseCase.query()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        val isSabbathMode = lockConfig.sabbathMode == BleV3Lock.SabbathMode.CLOSE.value
                        val result = lockConfig80UseCase.setSabbathMode(isSabbathMode)
                        showLog("$functionName to $isSabbathMode result: $result")
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> { showLog("$functionName not support.") }
        }
    }

    private fun determineLockDirection() {
        val functionName = ::determineLockDirection.name
        showLog(functionName)
        flow { emit(lockDirectionUseCase()) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { deviceStatus ->
                when (deviceStatus) {
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
                            throw LockStatusException.LockFunctionNotSupportException()
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
                            throw LockStatusException.LockFunctionNotSupportException()
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
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getLockName() {
        val functionName = ::getLockName.name
        flow { emit(lockNameUseCase.getName()) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { name ->
                showLog("$functionName: ${name}")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun setLockName(name: String) {
        val functionName = ::setLockName.name
        flow { emit(lockNameUseCase.setName(name)) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName to \"$name\" result: ${result}")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun isAdminCodeExists() {
        val functionName = ::isAdminCodeExists.name
        flow { emit(adminCodeUseCase.isAdminCodeExists()) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName: ${result}")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun createAdminCode(code: String) {
        val functionName = ::createAdminCode.name
        flow { emit(adminCodeUseCase.createAdminCode(code)) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName $code result: ${result}")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun updateAdminCode(oldCode: String, newCode: String) {
        val functionName = ::updateAdminCode.name
        flow { emit(adminCodeUseCase.updateAdminCode(oldCode, newCode)) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                adminCode = newCode
                showLog("$functionName from $oldCode to $newCode result: $result")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun togglePlugState() {
        val functionName = ::togglePlugState.name
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
                flow { emit(plugConfigUseCase.setPlugState(plugState)) }
                    .catch { e -> showLog("$functionName exception $e") }
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
                showLog("Current is ${sunionBleNotification::class.simpleName}: ${_currentDeviceStatus}")
            }
            is Alert -> {
                showLog("Current is ${sunionBleNotification::class.simpleName}: ${_currentSunionBleNotification}")
            }
            is Access -> {
                showLog("Current is ${sunionBleNotification::class.simpleName}: ${_currentSunionBleNotification}")
            }
            else -> {
                showLog("Unknown device status or alert!!")
            }
        }
    }

    private fun getFirmwareVersion() {
        val functionName = ::getFirmwareVersion.name
        flow { emit(lockUtilityUseCase.getFirmwareVersion()) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { version ->
                showLog("$functionName: $version")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getRfVersion() {
        val functionName = ::getRfVersion.name
        flow { emit(lockUtilityUseCase.getFirmwareVersion(1)) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { version ->
                showLog("$functionName: $version")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getMcuVersion() {
        val functionName = ::getMcuVersion.name
        flow { emit(lockUtilityUseCase.getFirmwareVersion(0)) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { version ->
                showLog("$functionName: $version")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun factoryReset(adminCode: String) {
        val functionName = "factoryReset"
        flow { emit(lockUtilityUseCase.factoryReset(adminCode)) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName: $result")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun factoryReset() {
        val functionName = "factoryReset"
        flow { emit(lockUtilityUseCase.factoryReset()) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName: $result")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun restart() {
        val functionName = ::restart.name
        flow { emit(lockUtilityUseCase.restart()) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName: $result")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun queryTokenArray(){
        val functionName = ::queryTokenArray.name
        flow { emit(lockTokenUseCase.queryTokenArray()) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { tokenArray ->
                showLog("$functionName: $tokenArray")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun queryToken(){
        val functionName = ::queryToken.name
        flow { emit(lockTokenUseCase.queryTokenArray()) }
            .catch { e -> showLog("$functionName array exception $e") }
            .map { tokenArray ->
                tokenArray.forEach { index ->
                    val deviceToken = lockTokenUseCase.queryToken(index)
                    if(deviceToken.isPermanent){
                        showLog("$functionName[$index] is permanent token: $deviceToken")
                    } else {
                        showLog("$functionName[$index] is one time token: ${deviceToken.token} name: ${deviceToken.name} permission: ${deviceToken.permission}")
                    }
                }
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun addOneTimeToken(permission: String, name: String) {
        val functionName = ::addOneTimeToken.name
        flow { emit(lockTokenUseCase.addOneTimeToken(permission,name)) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                    showLog("$functionName permission: $permission name: $name\nresult: $result")
                }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun editToken(index:Int, permission: String, name: String) {
        val functionName = ::editToken.name
        flow { emit(lockTokenUseCase.editToken(index, permission, name)) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName[$index] permission: $permission name: $name\nresult: $result")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun deleteToken(index: Int, code: String = "") {
        val functionName = ::deleteToken.name
        flow { emit(lockTokenUseCase.deleteToken(index, code)) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName[$index] code: $code\nresult: $result")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getAccessCodeArray(){
        val functionName = ::getAccessCodeArray.name
        when(_currentDeviceStatus){
            is DeviceStatus.D6 -> {
                flow { emit(lockAccessCodeUseCase.getAccessCodeArray()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { accessCodeArray ->
                        showLog("$functionName: $accessCodeArray")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.accessCodeQuantity.isSupport2Byte()) {
                            val accessCodeArray = lockAccessUseCase.getAccessCodeArray()
                            showLog("$functionName: $accessCodeArray")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun queryAccessCode(){
        val functionName = ::queryAccessCode.name
        when(_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                flow { emit(lockAccessCodeUseCase.getAccessCodeArray()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { list ->
                        val indexIterable = list
                            .mapIndexed { index, boolean -> if (boolean && index != 0) index else -1 }
                            .filter { index -> index != -1 }
                        indexIterable.forEach { index ->
                            val accessCode = lockAccessCodeUseCase.queryAccessCode(index)
                            showLog("$functionName[$index] is access code: $accessCode")
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if (result.accessCodeQuantity.isSupport2Byte()) {
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
                    .catch { e -> showLog("$functionName array exception $e") }
                    .map { indexIterable ->
                        indexIterable.forEach { index ->
                            val accessCode = lockAccessUseCase.queryAccessCode(index)
                            if(accessCode.type == 0) {
                                showLog("$functionName[$index] is access code: $accessCode")
                            }
                        }
                    }
                    .catch { e -> showLog("queryAccessCode exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun addAccessCode() {
        val functionName = ::addAccessCode.name
        val isEnabled = true
        val name = "Tom"
        val code = "1234"
        val index = 1
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                flow { emit(lockAccessCodeUseCase.addAccessCode(index, isEnabled, name, code, scheduleType)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nresult: $result")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.accessCodeQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.addAccessCode(index, isEnabled, scheduleType, name, code)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nisSuccess: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun editAccessCode() {
        val functionName = ::editAccessCode.name
        val isEnabled = true
        val name = "Tom"
        val code = "2345"
        val index = 1
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                flow { emit(lockAccessCodeUseCase.editAccessCode(index, isEnabled, name, code, scheduleType)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nresult: $result")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.accessCodeQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.editAccessCode(index, isEnabled, scheduleType, name, code)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nisSuccess: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun deleteAccessCode(index: Int) {
        val functionName = ::deleteAccessCode.name
        when(_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                flow { emit(lockAccessCodeUseCase.deleteAccessCode(index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName[$index]\nresult: $result")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.accessCodeQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.deleteAccessCode(index)
                            showLog("$functionName[$index]\nisSuccess: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun getAccessCardArray(){
        val functionName = ::getAccessCardArray.name
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        if(result.accessCardQuantity.isSupport2Byte()) {
                            val accessCardArray = lockAccessUseCase.getAccessCardArray()
                            showLog("$functionName: $accessCardArray")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun queryAccessCard(){
        val functionName = ::queryAccessCard.name
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        if (result.accessCardQuantity.isSupport2Byte()) {
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
                    .catch { e -> showLog("$functionName array exception $e") }
                    .map{ indexIterable ->
                        indexIterable.forEach { index ->
                            val accessCard = lockAccessUseCase.queryAccessCard(index)
                            if(accessCard.type == 1) {
                                showLog("$functionName[$index] is access card: $accessCard")
                            }
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun addAccessCard() {
        val functionName = ::addAccessCard.name
        val isEnabled = true
        val name = "Tom2"
        val code = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0xFC.toByte(),
            0xE8.toByte(), 0xFA.toByte(), 0x5B)
        val index = 2
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        if(result.accessCardQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.addAccessCard(index, isEnabled, scheduleType, name, code)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nisSuccess: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun editAccessCard() {
        val functionName = ::editAccessCard.name
        val isEnabled = true
        val name = "Tom23"
        val code =  byteArrayOf(0x88.toByte(), 0x04, 0x37, 0x75, 0x6A, 0x57, 0x58, 0x81.toByte())
        val index = 2
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.accessCardQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.editAccessCard(index, isEnabled, scheduleType, name, code)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nisSuccess: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun deleteAccessCard(index: Int) {
        val functionName = ::deleteAccessCard.name
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.accessCardQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.deleteAccessCard(index)
                            showLog("$functionName[$index]\nisSuccess: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun deviceGetAccessCard(){
        val functionName = ::deviceGetAccessCard.name
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.accessCardQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.deviceGetAccessCard(5)
                            showLog("$functionName: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun getFingerprintArray(){
        val functionName = ::getFingerprintArray.name
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.fingerprintQuantity.isSupport2Byte()) {
                            val fingerprintArray = lockAccessUseCase.getFingerprintArray()
                            showLog("$functionName: $fingerprintArray")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun queryFingerprint(){
        val functionName = ::queryFingerprint.name
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.fingerprintQuantity.isSupport2Byte()) {
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
                    .catch { e -> showLog("$functionName array exception $e") }
                    .map { indexIterable ->
                        indexIterable.forEach { index ->
                            val fingerprint = lockAccessUseCase.queryFingerprint(index)
                            if(fingerprint.type == 2) {
                                showLog("$functionName[$index] is Fingerprint: $fingerprint")
                            }
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun addFingerprint() {
        val functionName = ::addFingerprint.name
        val isEnabled = true
        val name = "Tom3"
        val code = "80"
        val index = 3
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.fingerprintQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.addFingerprint(index, isEnabled, scheduleType, name)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nisSuccess: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun editFingerprint() {
        val functionName = ::editFingerprint.name
        val isEnabled = true
        val name = "Tom34"
        val code = "100"
        val index = 3
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.fingerprintQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.editFingerprint(index, isEnabled, scheduleType, name)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nisSuccess: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun deleteFingerprint(index: Int) {
        val functionName = ::deleteFingerprint.name
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.fingerprintQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.deleteFingerprint(index)
                            showLog("$functionName[$index]\nisSuccess: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun deviceGetFingerprint(){
        val functionName = ::deviceGetFingerprint.name
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.fingerprintQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.deviceGetFingerprint(5)
                            showLog("$functionName: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun getFaceArray(){
        val functionName = ::getFaceArray.name
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.faceQuantity.isSupport2Byte()) {
                            val faceArray = lockAccessUseCase.getFaceArray()
                            showLog("$functionName: $faceArray")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun queryFace(){
        val functionName = ::queryFace.name
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if (result.faceQuantity.isSupport2Byte()) {
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
                    .catch { e -> showLog("$functionName array exception $e") }
                    .map { indexIterable ->
                        indexIterable.forEach { index ->
                            val queryFace = lockAccessUseCase.queryFace(index)
                            if (queryFace.type == 3) {
                                showLog("$functionName[$index] is face: $queryFace")
                            }
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun addFace() {
        val functionName = ::addFace.name
        val isEnabled = true
        val name = "Tom4"
        val code = "70"
        val index = 4
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.faceQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.addFace(index, isEnabled, scheduleType, name)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nisSuccess: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun editFace() {
        val functionName = ::editFace.name
        val isEnabled = true
        val name = "Tom45"
        val code = "80"
        val index = 4
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.faceQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.editFace(index, isEnabled, scheduleType, name)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nisSuccess: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun deleteFace(index: Int) {
        val functionName = ::deleteFace.name
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.faceQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.deleteFace(index)
                            showLog("$functionName[$index]\nisSuccess: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun deviceGetFace(){
        val functionName = ::deviceGetFace.name
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.faceQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.deviceGetFace(5)
                            showLog("$functionName: $isSuccess")
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("$functionName not support.")
            }
        }
    }

    private fun getEventQuantity(){
        val functionName = ::getEventQuantity.name
        flow { emit(lockEventLogUseCase.getEventQuantity()) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName result: $result")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getEvent(){
        val functionName = ::getEvent.name
        flow { emit(lockEventLogUseCase.getEventQuantity()) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                for(index in 0 until result){
                    val eventLog = lockEventLogUseCase.getEvent(index)
                    showLog("$functionName index[$index]\neventLog: $eventLog")
                }
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getEventByAddress(offset: Int){
        val functionName = ::getEventByAddress.name
        flow { emit(lockEventLogUseCase.getEventByAddress(offset)) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName offset[$offset]\neventLog: $result")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun deleteEvent(index: Int){
        val functionName = ::deleteEvent.name
        flow { emit(lockEventLogUseCase.deleteEvent(index)) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName index[$index]\nresult: $result")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getLockSupportedUnlockTypes() {
        val functionName = ::getLockSupportedUnlockTypes.name
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName result: $result")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
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

    private fun scanWifi(){
        val functionName = ::scanWifi.name
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
            .catch { e -> showLog("$functionName exception $e") }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun connectToWifi(ssid: String, password: String){
        val functionName = ::connectToWifi.name
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
                    showLog("$functionName failed, because not had provisionTicket.")
                }
            }
            .catch {
                Timber.e(it)
                if (it.message?.contains("Disconnected") == false)
                    Timber.e("CWifi listener exception $it")
                showLog("$functionName failed. $it")
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
                showLog("$functionName failed. $it")
            }
            .launchIn(viewModelScope)

        viewModelScope.launch(Dispatchers.IO) {
            delay(120_000)
            if (isConnectingToWifi) {
                Timber.e("CWifi timeout")
                showLog("$functionName failed.")
            }
        }
    }

    private fun setOTACancel(target: Int) {
        val functionName = ::setOTACancel.name
        flow { emit(lockOTAUseCase.setOTACancel(target)) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                showLog("$functionName result: $result")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun disconnect() {
        val functionName = ::disconnect.name
        statefulConnection.disconnect()
        _bleConnectionStateListener?.cancel()
        _bleSunionBleNotificationListener?.cancel()
        _currentDeviceStatus = DeviceStatus.UNKNOWN
        _currentSunionBleNotification = SunionBleNotification.UNKNOWN
        _uiState.update { it.copy(isLoading = false, isConnectedWithLock = false) }
        showLog(functionName)
    }

    fun setQRCodeContent(content: String) {
        val functionName = ::setQRCodeContent.name
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
            showLog("macAddress: ${_lockConnectionInfo!!.macAddress}")
            showLog("oneTimeToken: ${_lockConnectionInfo!!.oneTimeToken}")
            showLog("keyOne: ${_lockConnectionInfo!!.keyOne}")
            showLog("Please execute Connect to pair with lock.")
            _uiEvent.emit(UiEvent.Complete)
            _uiState.update { it.copy(btnEnabled = true) }
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
                    lockOTAUseCase.setOTACancel(target)
                }
            }
        }
    }

    fun fileCheck(checkHash:String): Boolean {
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
    object Complete: UiEvent()
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
    const val GetLockTimeZone = 60
    const val ToggleSabbathMode = 61
    const val AutoUnlockToggleLockState = 62
    const val AutoUnlockToggleSecurityBolt = 63
    const val GetEventByAddress = 64
    const val GetFwVersion = 80
    const val GetRfVersion = 81
    const val GetMcuVersion = 82
    const val FactoryReset = 83
    const val FactoryResetNoAdmin = 84
    const val Restart = 85
    const val Disconnect = 99
}