package com.sunion.ble.demoapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sunion.core.ble.usecase.LockQRCodeUseCase
import com.sunion.core.ble.ReactiveStatefulConnection
import com.sunion.core.ble.usecase.LockNameUseCase
import com.sunion.core.ble.entity.*
import com.sunion.core.ble.exception.LockStatusException
import com.sunion.core.ble.unless
import com.sunion.core.ble.usecase.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
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
): ViewModel(){
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
                toggleAutoLock(6)
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
                createAdminCode(code = "0000")
            }
            // Update Admin code
            TaskCode.UpdateAdminCode -> {
                updateAdminCode(oldCode = "0000", newCode = "1234")
            }
            // Get firmware version
            TaskCode.GetFwVersion -> {
                getFirmwareVersion()
            }
            // Factory reset
            TaskCode.FactoryReset -> {
                factoryReset("0000")
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
                            showLog("connect to lock succeed.\n")
                            showLog("Lock connection information:")
                            showLog("${statefulConnection.lockConnectionInfo}\n")
                            // connect with oneTimeToken
                            if (_lockConnectionInfo!!.permanentToken.isNullOrEmpty()) {
                                showLog("After pairing with lock, you can get lock connection information from statefulConnection.lockConnectionInfo and save permanent token for later use.\n")
                            }
                            _lockConnectionInfo = statefulConnection.lockConnectionInfo
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
        lockTimeUseCase.getTime()
            .map { timeStamp ->
                showLog("Lock time = ${timeStamp}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("getLockTime exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun setLockTime() {
        lockTimeUseCase.setTime(Instant.now().atZone(ZoneId.systemDefault()).toEpochSecond())
            .map { result ->
                showLog("Set lock time to now, result = ${result}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("setLockTime exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun setLockTimeZone() {
        lockTimeUseCase.setTimeZone(ZoneId.systemDefault().id)
            .map { result ->
                showLog("Set lock timezone to ${ZoneId.systemDefault().id}, result = ${result}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("setLockTimeZone exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun getDeviceStatus() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                deviceStatusD6UseCase()
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
                    .catch { e -> showLog("getDeviceStatusD6 exception $e \n") }
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                deviceStatusA2UseCase()
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
                    .catch { e -> showLog("getDeviceStatusA2 exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Unknown device status.\n") }
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
                deviceStatusD6UseCase.setLockState(desiredState)
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
                    .catch { e -> showLog("toggleLockState exception $e \n") }
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
                deviceStatusA2UseCase.setLockState(state)
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
                    .catch { e -> showLog("toggleLockState exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Unknown device status.\n") }
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
                deviceStatusA2UseCase.setSecurityBolt(state)
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
                    .catch { e -> showLog("toggleSecurityBolt exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Unknown device status.\n") }
        }
    }

    private fun getLockConfig() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                lockConfigD4UseCase.query()
                    .map { lockConfig ->
                        showLog("getLockConfigD4: $lockConfig \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockConfig exception $e \n") }
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                lockConfigA0UseCase.query()
                    .map { lockConfig ->
                        showLog("getLockConfigA0: $lockConfig \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockConfig exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status.\n")
            }
        }
    }

    private fun toggleKeyPressBeep() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                val deviceStatusD6 = _currentDeviceStatus as DeviceStatus.DeviceStatusD6
                val isSoundOn = when (deviceStatusD6.config.isSoundOn) {
                    true -> { false }
                    false -> { true }
                }
                lockConfigD4UseCase.setKeyPressBeep(isSoundOn)
                    .map { result ->
                        showLog("Set key press beep to ${isSoundOn}, result = $result \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("toggleKeyPressBeep exception $e \n") }
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                lockConfigA0UseCase.query()
                    .map { lockConfig ->
                        if (lockConfig.soundType != BleV2Lock.SoundType.NOT_SUPPORT.value) {
                            lockConfigA0UseCase.setSoundValue(lockConfig.soundType)
                                .map { result ->
                                    showLog("Toggle sound value at type ${lockConfig.soundType}, result = $result \n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("toggleKeyPressBeep exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("toggleKeyPressBeep exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Unknown device status.\n") }
        }
    }

    private fun toggleVirtualCode() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockConfigA0UseCase.query()
                    .map { lockConfig ->
                        if (lockConfig.virtualCode != BleV2Lock.VirtualCode.NOT_SUPPORT.value) {
                            val isVirtualCodeOn = lockConfig.virtualCode == BleV2Lock.VirtualCode.CLOSE.value
                            lockConfigA0UseCase.setVirtualCode(isVirtualCodeOn)
                                .map { result ->
                                    showLog("Set guiding code to ${isVirtualCodeOn}, result = $result \n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("toggleVirtualCode exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("toggleVirtualCode exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Unknown device status.\n") }
        }
    }

    private fun toggleTwoFA() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockConfigA0UseCase.query()
                    .map { lockConfig ->
                        if (lockConfig.twoFA != BleV2Lock.TwoFA.NOT_SUPPORT.value) {
                            val isTwoFAOn = lockConfig.twoFA == BleV2Lock.TwoFA.CLOSE.value
                            lockConfigA0UseCase.setTwoFA(isTwoFAOn)
                                .map { result ->
                                    showLog("Set twoFA to ${isTwoFAOn}, result = $result \n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("toggleTwoFA exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("toggleTwoFA exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Unknown device status.\n") }
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
                lockConfigD4UseCase.setVactionMode(isVacationModeOn)
                    .map { result ->
                        showLog("Set vacation mode to ${isVacationModeOn}, result = $result \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("toggleVacationMode exception $e \n") }
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                lockConfigA0UseCase.query()
                    .map { lockConfig ->
                        if (lockConfig.vacationMode != BleV2Lock.VacationMode.NOT_SUPPORT.value) {
                            val isVacationModeOn = lockConfig.vacationMode == BleV2Lock.VacationMode.CLOSE.value
                            lockConfigA0UseCase.setVacationMode(isVacationModeOn)
                                .map { result ->
                                    showLog("Set vacation mode to ${isVacationModeOn}, result = $result \n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("toggleVacationMode exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("toggleVacationMode exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Unknown device status.\n") }
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
                lockConfigD4UseCase.setGuidingCode(isGuidingCodeOn)
                    .map { result ->
                        showLog("Set guiding code to ${isGuidingCodeOn}, result = $result \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("toggleGuidingCode exception $e \n") }
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                lockConfigA0UseCase.query()
                    .map { lockConfig ->
                        if (lockConfig.guidingCode != BleV2Lock.GuidingCode.NOT_SUPPORT.value) {
                            val isGuidingCodeOn = lockConfig.guidingCode == BleV2Lock.GuidingCode.CLOSE.value
                            lockConfigA0UseCase.setGuidingCode(isGuidingCodeOn)
                                .map { result ->
                                    showLog("Set guiding code to ${isGuidingCodeOn}, result = $result \n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("toggleGuidingCode exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("toggleGuidingCode exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Unknown device status.\n") }
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
                lockConfigD4UseCase.setAutoLock(isAutoLock, autoLockTime)
                    .map { result ->
                        if (isAutoLock)
                            showLog("Set auto lock to ${isAutoLock} and auto lock time to $autoLockTime, result = $result \n")
                        else
                            showLog("Set auto lock to ${isAutoLock}, result = $result \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("toggleAutoLock exception $e \n") }
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
            lockConfigA0UseCase.query()
                .map { lockConfig ->
                    if (lockConfig.autoLock != BleV2Lock.AutoLock.NOT_SUPPORT.value) {
                        val isAutoLock = lockConfig.autoLock == BleV2Lock.AutoLock.CLOSE.value
                        if (autoLockTime < lockConfig.autoLockTimeUpperLimit || autoLockTime > lockConfig.autoLockTimeLowerLimit) {
                            showLog("Set auto lock will fail because autoLockTime is not support value")
                        }
                        lockConfigA0UseCase.setAutoLock(isAutoLock, autoLockTime)
                            .map { result ->
                                showLog("Set auto lock to $isAutoLock and auto lock time to $autoLockTime, result = $result \n")
                            }
                            .onStart { _uiState.update { it.copy(isLoading = true) } }
                            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                            .flowOn(Dispatchers.IO)
                            .catch { e -> showLog("toggleAutoLock exception $e \n") }
                            .launchIn(viewModelScope)
                    } else {
                        throw LockStatusException.LockFunctionNotSupportException()
                    }
                }
                .onStart { _uiState.update { it.copy(isLoading = true) } }
                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                .flowOn(Dispatchers.IO)
                .catch { e -> showLog("toggleAutoLock exception $e \n") }
                .launchIn(viewModelScope)
        }

            else -> { showLog("Unknown device status.\n") }
        }
    }

    private fun setLockLocation(latitude: Double, longitude: Double) {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                lockConfigD4UseCase.setLocation(latitude = latitude, longitude = longitude)
                    .map { result ->
                        showLog("Set lock location to (${latitude}, ${longitude}), result = $result \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("setLockLocation exception $e \n") }
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                lockConfigA0UseCase.setLocation(latitude = latitude, longitude = longitude)
                    .map { result ->
                        showLog("Set lock location to (${latitude}, ${longitude}), result = $result \n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("setLockLocation exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status.\n")
            }
        }
    }

    private fun toggleOperatingSound() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockConfigA0UseCase.query()
                    .map { lockConfig ->
                        if (lockConfig.operatingSound != BleV2Lock.OperatingSound.NOT_SUPPORT.value) {
                            val isOperatingSoundOn = lockConfig.operatingSound == BleV2Lock.OperatingSound.CLOSE.value
                            lockConfigA0UseCase.setOperatingSound(isOperatingSoundOn)
                                .map { result ->
                                    showLog("Toggle operating sound to ${isOperatingSoundOn}, result = $result \n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("toggleOperatingSound exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("toggleOperatingSound exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Unknown device status.\n") }
        }
    }

    private fun toggleShowFastTrackMode() {
        when (_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockConfigA0UseCase.query()
                    .map { lockConfig ->
                        if (lockConfig.showFastTrackMode != BleV2Lock.ShowFastTrackMode.NOT_SUPPORT.value) {
                            val isShowFastTrackModeOn = lockConfig.showFastTrackMode == BleV2Lock.ShowFastTrackMode.CLOSE.value
                            lockConfigA0UseCase.setShowFastTrackMode(isShowFastTrackModeOn)
                                .map { result ->
                                    showLog("Set show fast track mode to ${isShowFastTrackModeOn}, result = $result \n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("toggleShowFastTrackMode exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("toggleShowFastTrackMode exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> { showLog("Unknown device status.\n") }
        }
    }

    private fun determineLockDirection() {
        showLog("Determine lock direction:")
        lockDirectionUseCase()
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
            .catch { e -> showLog("determineLockDirection exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun getLockName() {
        lockNameUseCase.getName()
            .map { name ->
                showLog("Lock name = ${name}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("getLockName exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun setLockName(name: String) {
        lockNameUseCase.setName(name)
            .map { result ->
                showLog("Set lock name to \"$name\", result = ${result}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("setLockName exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun isAdminCodeExists() {
        adminCodeUseCase.isAdminCodeExists()
            .map { result ->
                showLog("Is Admin code exists = ${result}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("isAdminCodeExists exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun createAdminCode(code: String) {
        adminCodeUseCase.createAdminCode(code)
            .map { result ->
                showLog("Create Admin code \"$code\", result = ${result}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("createAdminCode exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun updateAdminCode(oldCode: String, newCode: String) {
        adminCodeUseCase.updateAdminCode(oldCode, newCode)
            .map { result ->
                showLog("Update Admin code from \"$oldCode\" to \"$newCode\", result = ${result}\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("updateAdminCode exception $e \n") }
            .launchIn(viewModelScope)
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
        lockUtilityUseCase.getFirmwareVersion()
            .map { version ->
                showLog("Get firmware version: $version \n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("getFirmwareVersion exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun factoryReset(adminCode: String) {
        lockUtilityUseCase.factoryReset(adminCode)
            .map { result ->
                showLog("Factory reset: $result \n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("factoryReset exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun queryTokenArray(){
        lockTokenUseCase.queryTokenArray()
            .map { tokenArray ->
                showLog("queryTokenArray: $tokenArray\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("queryTokenArray exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun queryToken(){
        lockTokenUseCase.queryTokenArray()
            .map { tokenArray ->
                tokenArray.forEach { index ->
                    lockTokenUseCase.queryToken(index)
                        .collect { deviceToken ->
                            if(deviceToken.isPermanent){
                                showLog("queryToken[$index] is permanent token: $deviceToken\n")
                            } else {
                                showLog("queryToken[$index] is one time token: ${deviceToken.token} name:${deviceToken.name} permission:${deviceToken.permission}\n")
                            }
                        }
                }
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("queryToken exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun addOneTimeToken(permission: String, name: String) {
        lockTokenUseCase.addOneTimeToken(permission,name)
            .map { result ->
                    showLog("addOneTimeToken permission:$permission name:$name \nresult= $result\n")
                }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("addOneTimeToken exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun editToken(index:Int, permission: String, name: String) {
        lockTokenUseCase.editToken(index, permission, name)
            .map { result ->
                showLog("editToken[$index] permission:$permission name:$name \nresult= $result\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("editToken exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun deleteToken(index: Int, code: String = "") {
        lockTokenUseCase.deleteToken(index, code)
            .map { result ->
                showLog("deleteToken[$index] code:$code \nresult= $result\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("deleteToken exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun getAccessCodeArray(){
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusD6 -> {
                lockAccessCodeUseCase.getAccessCodeArray()
                    .map { accessCodeArray ->
                        showLog("getAccessCodeArray: $accessCodeArray\n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getAccessCodeArray exception $e \n") }
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.accessCodeQuantity != BleV2Lock.AccessCodeQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.getAccessCodeArray()
                                .map { accessCodeArray ->
                                    showLog("getAccessCodeArray: $accessCodeArray\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("getAccessCodeArray exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not getAccessCodeArray. \n")
            }
        }
    }

    private fun queryAccessCode(){
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                lockAccessCodeUseCase.getAccessCodeArray()
                    .map { list ->
                        val indexIterable = list
                            .mapIndexed { index, boolean -> if (boolean && index != 0) index else -1 }
                            .filter { index -> index != -1 }
                        indexIterable.forEach { index ->
                            lockAccessCodeUseCase.queryAccessCode(index)
                                .collect { accessCode ->
                                    showLog("queryAccessCode[$index] is access code: $accessCode\n")
                                }
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("queryAccessCode exception $e \n") }
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.accessCodeQuantity != BleV2Lock.AccessCodeQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.getAccessCodeArray()
                                .map { list ->
                                    val indexIterable = list.subList(0, list.size - 2)
                                        .mapIndexed { index, boolean -> if (boolean && index != 0) index else -1 }
                                        .filter { index -> index != -1 }
                                    Timber.d("indexIterable: $indexIterable")
                                    indexIterable.forEach { index ->
                                        lockAccessUseCase.queryAccessCode(index)
                                            .filter { result -> result.type == 0 }
                                            .collect { accessCode ->
                                                showLog("queryAccessCode[$index] is access code: $accessCode\n")
                                            }
                                    }
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("queryAccessCode exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not queryAccessCode. \n")
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
                lockAccessCodeUseCase.addAccessCode(index, isEnabled, name, code, scheduleType)
                    .map { result ->
                        showLog("addAccessCode index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nresult= $result\n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("addAccessCode exception $e \n") }
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.accessCodeQuantity != BleV2Lock.AccessCodeQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.addAccessCode(index, isEnabled, scheduleType, name, code)
                                .map { isSuccess ->
                                    showLog("addAccessCode index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("addAccessCode exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not addAccessCode. \n")
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
                lockAccessCodeUseCase.editAccessCode(index, isEnabled, name, code, scheduleType)
                    .map { result ->
                        showLog("editAccessCode:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nresult= $result\n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("editAccessCode exception $e \n") }
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.accessCodeQuantity != BleV2Lock.AccessCodeQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.editAccessCode(index, isEnabled, scheduleType, name, code)
                                .map { isSuccess ->
                                    showLog("editAccessCode index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("editAccessCode exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not editAccessCode. \n")
            }
        }
    }

    private fun deleteAccessCode(index: Int) {
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusD6 -> {
                lockAccessCodeUseCase.deleteAccessCode(index)
                    .map { result ->
                        showLog("deleteAccessCode[$index] \nresult= $result\n")
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("deleteAccessCode exception $e \n") }
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.accessCodeQuantity != BleV2Lock.AccessCodeQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.deleteAccessCode(index)
                                .map { isSuccess ->
                                    showLog("deleteAccessCode[$index] \nisSuccess= $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("deleteAccessCode exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not deleteAccessCode. \n")
            }
        }
    }

    private fun getAccessCardArray(){
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.accessCardQuantity != BleV2Lock.AccessCardQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.getAccessCardArray()
                                .map { accessCardArray ->
                                    showLog("getAccessCardArray: $accessCardArray\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("getAccessCardArray exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not getAccessCardArray. \n")
            }
        }
    }

    private fun queryAccessCard(){
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.accessCardQuantity != BleV2Lock.AccessCardQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.getAccessCardArray()
                                .map { list ->
                                    val indexIterable = list.subList(0, list.size - 2)
                                        .mapIndexed { index, boolean -> if (boolean && index != 0) index else -1 }
                                        .filter { index -> index != -1 }
                                    Timber.d("indexIterable: $indexIterable")
                                    indexIterable.forEach { index ->
                                        lockAccessUseCase.queryAccessCard(index)
                                            .filter { result -> result.type == 1 }
                                            .collect { accessCard ->
                                                showLog("queryAccessCard[$index] is access card: $accessCard\n")
                                            }
                                    }
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("queryAccessCard exception $e \n") }
                                .launchIn(viewModelScope)
                                    } else {
                                        throw LockStatusException.LockFunctionNotSupportException()
                                    }
                                }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not queryAccessCard. \n")
            }
        }
    }

    private fun addAccessCard() {
        val isEnabled = true
        val name = "Tom2"
        val code = "12345"
        val index = 2
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.accessCardQuantity != BleV2Lock.AccessCardQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.addAccessCard(index, isEnabled, scheduleType, name, code)
                                .map { isSuccess ->
                                    showLog("addAccessCard index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("addAccessCard exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not addAccessCard. \n")
            }
        }
    }

    private fun editAccessCard() {
        val isEnabled = true
        val name = "Tom23"
        val code = "23456"
        val index = 2
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.accessCardQuantity != BleV2Lock.AccessCardQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.editAccessCard(index, isEnabled, scheduleType, name, code)
                                .map { isSuccess ->
                                    showLog("editAccessCard index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("editAccessCard exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not editAccessCard. \n")
            }
        }
    }

    private fun deleteAccessCard(index: Int) {
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.accessCardQuantity != BleV2Lock.AccessCardQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.deleteAccessCard(index)
                                .map { isSuccess ->
                                    showLog("deleteAccessCard[$index] \nisSuccess= $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("deleteAccessCard exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not deleteAccessCard. \n")
            }
        }
    }

    private fun deviceGetAccessCard(){
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.accessCardQuantity != BleV2Lock.AccessCardQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.deviceGetAccessCard(1,5)
                                .map { isSuccess ->
                                    showLog("deviceGetAccessCard: $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("deviceGetAccessCard exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not deviceGetAccessCard. \n")
            }
        }
    }

    private fun getFingerprintArray(){
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.fingerprintQuantity != BleV2Lock.FingerprintQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.getFingerprintArray()
                                .map { fingerprintArray ->
                                    showLog("getFingerprintArray: $fingerprintArray\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("getFingerprintArray exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not getFingerprintArray. \n")
            }
        }
    }

    private fun queryFingerprint(){
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.fingerprintQuantity != BleV2Lock.FingerprintQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.getFingerprintArray()
                                .map { list ->
                                    val indexIterable = list.subList(0, list.size - 2)
                                        .mapIndexed { index, boolean -> if (boolean && index != 0) index else -1 }
                                        .filter { index -> index != -1 }
                                    Timber.d("indexIterable: $indexIterable")
                                    indexIterable.forEach { index ->
                                        lockAccessUseCase.queryFingerprint(index)
                                            .filter { result -> result.type == 2 }
                                            .collect { fingerprint ->
                                                showLog("queryFingerprint[$index] is Fingerprint: $fingerprint\n")
                                            }
                                    }
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("queryFingerprint exception $e \n") }
                                .launchIn(viewModelScope)
                                    } else {
                                        throw LockStatusException.LockFunctionNotSupportException()
                                    }
                                }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not queryFingerprint. \n")
            }
        }
    }

    private fun addFingerprint() {
        val isEnabled = true
        val name = "Tom3"
        val code = "123456"
        val index = 3
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.fingerprintQuantity != BleV2Lock.FingerprintQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.addFingerprint(index, isEnabled, scheduleType, name, code)
                                .map { isSuccess ->
                                    showLog("addFingerprint index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("addFingerprint exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not addFingerprint. \n")
            }
        }
    }

    private fun editFingerprint() {
        val isEnabled = true
        val name = "Tom34"
        val code = "234567"
        val index = 3
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.fingerprintQuantity != BleV2Lock.FingerprintQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.editFingerprint(index, isEnabled, scheduleType, name, code)
                                .map { isSuccess ->
                                    showLog("editFingerprint index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("editFingerprint exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not editFingerprint. \n")
            }
        }
    }

    private fun deleteFingerprint(index: Int) {
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.fingerprintQuantity != BleV2Lock.FingerprintQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.deleteFingerprint(index)
                                .map { isSuccess ->
                                    showLog("deleteFingerprint[$index] \nisSuccess= $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("deleteFingerprint exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not deleteFingerprint. \n")
            }
        }
    }

    private fun deviceGetFingerprint(){
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.fingerprintQuantity != BleV2Lock.FingerprintQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.deviceGetFingerprint(1,5)
                                .map { isSuccess ->
                                    showLog("deviceGetFingerprint: $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("deviceGetFingerprint exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not deviceGetFingerprint. \n")
            }
        }
    }

    private fun getFaceArray(){
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.faceQuantity != BleV2Lock.FaceQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.getFaceArray()
                                .map { faceArray ->
                                    showLog("getFaceArray: $faceArray\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("getFaceArray exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not getFaceArray. \n")
            }
        }
    }

    private fun queryFace(){
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.faceQuantity != BleV2Lock.FaceQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.getFaceArray()
                                .map { list ->
                                    val indexIterable = list.subList(0, list.size - 2)
                                        .mapIndexed { index, boolean -> if (boolean && index != 0) index else -1 }
                                        .filter { index -> index != -1 }
                                    Timber.d("indexIterable: $indexIterable")
                                    indexIterable.forEach { index ->
                                        lockAccessUseCase.queryFace(index)
                                            .filter { result -> result.type == 3 }
                                            .collect { queryFace ->
                                                showLog("queryFace[$index] is face: $queryFace\n")
                                            }
                                    }
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("queryFace exception $e \n") }
                                .launchIn(viewModelScope)
                                    } else {
                                        throw LockStatusException.LockFunctionNotSupportException()
                                    }
                                }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not queryFace. \n")
            }
        }
    }

    private fun addFace() {
        val isEnabled = true
        val name = "Tom4"
        val code = "1234567"
        val index = 4
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.faceQuantity != BleV2Lock.FaceQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.addFace(index, isEnabled, scheduleType, name, code)
                                .map { isSuccess ->
                                    showLog("addFace index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("addFace exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not addFace. \n")
            }
        }
    }

    private fun editFace() {
        val isEnabled = true
        val name = "Tom45"
        val code = "2345678"
        val index = 4
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.faceQuantity != BleV2Lock.FaceQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.editFace(index, isEnabled, scheduleType, name, code)
                                .map { isSuccess ->
                                    showLog("editFace index:$index isEnabled:$isEnabled name:$name code:$code scheduleType:$scheduleType \nisSuccess= $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("editFace exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not editFace. \n")
            }
        }
    }

    private fun deleteFace(index: Int) {
        when(_currentDeviceStatus) {
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.faceQuantity != BleV2Lock.FaceQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.deleteFace(index)
                                .map { isSuccess ->
                                    showLog("deleteFace[$index] \nisSuccess= $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("deleteFace exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not deleteFace. \n")
            }
        }
    }

    private fun deviceGetFace(){
        when(_currentDeviceStatus){
            is DeviceStatus.DeviceStatusA2 -> {
                lockUtilityUseCase.getLockSupportedUnlockTypes()
                    .map { result ->
                        if(result.faceQuantity != BleV2Lock.FaceQuantity.NOT_SUPPORT.value) {
                            lockAccessUseCase.deviceGetFace(1,5)
                                .map { isSuccess ->
                                    showLog("deviceGetFace: $isSuccess\n")
                                }
                                .onStart { _uiState.update { it.copy(isLoading = true) } }
                                .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                                .flowOn(Dispatchers.IO)
                                .catch { e -> showLog("deviceGetFace exception $e \n") }
                                .launchIn(viewModelScope)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
                    .launchIn(viewModelScope)
            }
            else -> {
                showLog("Unknown device status not deviceGetFace. \n")
            }
        }
    }

    private fun getEventQuantity(){
        lockEventLogUseCase.getEventQuantity()
            .map { result ->
                showLog("getEventQuantity \nresult= $result\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("getEventQuantity exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun getEvent(){
        lockEventLogUseCase.getEventQuantity()
            .map { result ->
                for(index in 0 until result){
                    lockEventLogUseCase.getEvent(index)
                        .collect{ eventLog ->
                            showLog("getEvent index[$index] \neventLog: $eventLog\n")
                        }
                }
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("getEvent exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun deleteEvent(index: Int){
        lockEventLogUseCase.deleteEvent(index)
            .map { result ->
                showLog("deleteEvent index[$index] \nresult= $result\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("deleteEvent exception $e \n") }
            .launchIn(viewModelScope)
    }

    private fun getLockSupportedUnlockTypes() {
        lockUtilityUseCase.getLockSupportedUnlockTypes()
            .map { result ->
                showLog("getLockSupportedUnlockTypes result= $result\n")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .catch { e -> showLog("getLockSupportedUnlockTypes exception $e \n") }
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
    const val GetFwVersion = 80
    const val FactoryReset = 81
    const val Disconnect = 99
}