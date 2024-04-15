package com.sunion.ble.demoapp

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sunion.ble.demoapp.data.api.DeviceApiRepository
import com.sunion.core.ble.ReactiveStatefulConnection
import com.sunion.core.ble.accessByteArrayToString
import com.sunion.core.ble.entity.*
import com.sunion.core.ble.exception.ConnectionTokenException
import com.sunion.core.ble.exception.LockStatusException
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject

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
    var fileSize: Int = 0
    val currentTarget = 0 // 0:mcu 1:rf
    val ivString = ""
    val signatureV005 = ""
    val hash256V005 = ""

    private var adminCode = "0000"

    private var currentQrCodeContent: String? = null
    private var currentProductionGetResponse: ProductionGetResponse? = null
    private var currentConnectMacAddress: String? = null
    private var userAbility: BleV3Lock.UserAbility? = null
    private var lastCodeCardIndex = 0
    private var lastCodeIndex = 0
    private var lastCardIndex = 0
    private var lastFingerprintIndex = 0
    private var lastFingerVeinIndex = 0
    private var lastFaceIndex = 0
    private val _currentAccessA9Data = MutableStateFlow(Access.A9(-1,-1,-1,false, byteArrayOf()))
    val currentAccessA9Data: StateFlow<Access.A9> = _currentAccessA9Data
    private val _currentCredential97Data = MutableStateFlow(Credential.NinetySeven(-1,-1,-1,0, byteArrayOf()))
    val currentCredential97Data: StateFlow<Credential.NinetySeven> = _currentCredential97Data
    private var lastTokenIndex = 0
    private var lastEventLogIndex = 0
    private var lastUserIndex = 0
    private var lastCredentialIndex = 0
    private var lastUnsyncedData: Data.NinetyB? = null
    private var isCheckDeviceStatus = false
    private var isCheckLockConfig = false
    private var isCheckUnLockType = false

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
        _uiState.update { it.copy(btnEnabled = false) }
        when (_uiState.value.taskCode) {
            // Connect
            BleDeviceFeature.TaskCode.Connect -> {
                if(currentQrCodeContent != null && currentQrCodeContent!!.isDeviceUuid() && currentConnectMacAddress == null){
                    startBleScan(currentQrCodeContent!!, currentProductionGetResponse!!, true)
                } else {
                    connect()
                }
            }
            // Get lock time
            BleDeviceFeature.TaskCode.GetLockTime -> {
                getLockTime()
            }
            // Set lock time
            BleDeviceFeature.TaskCode.SetLockTime -> {
                setLockTime()
            }
            // Get lock time zone
            BleDeviceFeature.TaskCode.GetLockTimeZone -> {
                getLockTimeZone()
            }
            // Set lock timezone
            BleDeviceFeature.TaskCode.SetLockTimeZone -> {
                setLockTimeZone()
            }
            // Get lock name
            BleDeviceFeature.TaskCode.GetLockName -> {
                getLockName()
            }
            // Set lock name
            BleDeviceFeature.TaskCode.SetLockName -> {
                setLockName(name = "my door lock")
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
            // Auto unlock toggle lock state
            BleDeviceFeature.TaskCode.AutoUnlockToggleSecurityBolt -> {
                autoUnlockToggleSecurityBolt()
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
                toggleAutoLock(10)
            }
            // Set lock location
            BleDeviceFeature.TaskCode.SetLockLocation -> {
                setLockLocation(25.03369, 121.564128)
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
            // Is Admin code exists
            BleDeviceFeature.TaskCode.IsAdminCodeExists -> {
                isAdminCodeExists()
            }
            // Create Admin code
            BleDeviceFeature.TaskCode.CreateAdminCode -> {
                createAdminCode(adminCode)
            }
            // Update Admin code
            BleDeviceFeature.TaskCode.UpdateAdminCode -> {
                updateAdminCode(adminCode, newCode = "1234")
            }
            // Plug on
            BleDeviceFeature.TaskCode.TogglePlugState -> {
                togglePlugState()
            }
            // Get firmware version
            BleDeviceFeature.TaskCode.GetFwVersion -> {
                getFirmwareVersion()
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
                factoryReset(adminCode)
            }
            // Factory reset
            BleDeviceFeature.TaskCode.FactoryResetNoAdmin -> {
                factoryReset()
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
                addOneTimeToken("L","User ${lastTokenIndex + 1}")
            }
            // Edit Token
            BleDeviceFeature.TaskCode.EditToken -> {
                editToken(lastTokenIndex,"A","User $lastTokenIndex ed")
            }
            // Delete Token
            BleDeviceFeature.TaskCode.DeleteToken -> {
                deleteToken(lastTokenIndex)
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
                addAccessCode()
            }
            // Edit Access Code
            BleDeviceFeature.TaskCode.EditAccessCode -> {
                editAccessCode()
            }
            // Delete Access Code
            BleDeviceFeature.TaskCode.DeleteAccessCode -> {
                deleteAccessCode(lastCodeIndex)
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
                addAccessCard()
            }
            // Edit Access Card
            BleDeviceFeature.TaskCode.EditAccessCard -> {
                editAccessCard()
            }
            // Delete Access Card
            BleDeviceFeature.TaskCode.DeleteAccessCard -> {
                deleteAccessCard(lastCardIndex)
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
                addFingerprint()
            }
            // Edit Fingerprint
            BleDeviceFeature.TaskCode.EditFingerprint -> {
                editFingerprint()
            }
            // Delete Fingerprint
            BleDeviceFeature.TaskCode.DeleteFingerprint -> {
                deleteFingerprint(lastFingerprintIndex)
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
                addFace()
            }
            // Edit Face
            BleDeviceFeature.TaskCode.EditFace -> {
                editFace()
            }
            // Delete Face
            BleDeviceFeature.TaskCode.DeleteFace -> {
                deleteFace(lastFaceIndex)
            }
            // Device Get Face
            BleDeviceFeature.TaskCode.DeviceGetFace -> {
                deviceGetFace()
            }
            // Add Credential Finger Vein
            BleDeviceFeature.TaskCode.AddCredentialFingerVein -> {
                addCredentialFingerVein()
            }
            // Edit Credential Finger Vein
            BleDeviceFeature.TaskCode.EditCredentialFingerVein -> {
                editCredentialFingerVein()
            }
            // Delete Credential Finger Vein
            BleDeviceFeature.TaskCode.DeleteCredentialFingerVein -> {
                deleteCredentialFingerVein(lastFingerVeinIndex)
            }
            // Device Get Credential Finger Vein
            BleDeviceFeature.TaskCode.DeviceGetCredentialFingerVein -> {
                deviceGetCredentialFingerVein()
            }
            // Get Event Quantity
            BleDeviceFeature.TaskCode.GetEventQuantity -> {
                getEventQuantity()
            }
            // Get Event
            BleDeviceFeature.TaskCode.GetEvent -> {
                getEvent()
            }
            // Get Event By Address
            BleDeviceFeature.TaskCode.GetEventByAddress -> {
                getEventByAddress(lastEventLogIndex)
            }
            // Delete Event
            BleDeviceFeature.TaskCode.DeleteEvent -> {
                deleteEvent(lastEventLogIndex)
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
                addUser()
            }
            // Edit User
            BleDeviceFeature.TaskCode.EditUser -> {
                editUser()
            }
            // Delete User
            BleDeviceFeature.TaskCode.DeleteUser -> {
                deleteUser(lastUserIndex)
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
            BleDeviceFeature.TaskCode.GetCredentialHash -> {
                getCredentialHash()
            }
            // Get User Hash
            BleDeviceFeature.TaskCode.GetUserHash -> {
                getUserHash()
            }
            // Has Unsynced Data
            BleDeviceFeature.TaskCode.HasUnsyncedData -> {
                hasUnsyncedData()
            }
            // Get Unsynced Data
            BleDeviceFeature.TaskCode.GetUnsyncedData -> {
                getUnsyncedData()
            }
            // Set Credential Unsynced Data
            BleDeviceFeature.TaskCode.SetCredentialUnsyncedData -> {
                setCredentialUnsyncedData()
            }
            // Set User Unsynced Data
            BleDeviceFeature.TaskCode.SetUserUnsyncedData -> {
                setUserUnsyncedData()
            }
            // Set Log Unsynced Data
            BleDeviceFeature.TaskCode.SetLogUnsyncedData -> {
                setLogUnsyncedData()
            }
            // Set Token Unsynced Data
            BleDeviceFeature.TaskCode.SetTokenUnsyncedData -> {
                setTokenUnsyncedData()
            }
            // Set Setting Unsynced Data
            BleDeviceFeature.TaskCode.SetSettingUnsyncedData -> {
                setSettingUnsyncedData()
            }
            // Set All Data Synced
            BleDeviceFeature.TaskCode.SetAllDataSynced -> {
                setAllDataSynced()
            }
            // Scan Wifi
            BleDeviceFeature.TaskCode.ScanWifi -> {
                collectWifiList()
                scanWifi()
            }
            // Connect To Wifi
            BleDeviceFeature.TaskCode.ConnectToWifi -> {
                connectToWifi("Sunion-SW", "S-device_W")
            }
            // Set OTA Status
            BleDeviceFeature.TaskCode.SetOTAUpdate -> {
                otaUpdate(currentTarget, "")
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
        _uiState.update { it.copy(btnEnabled = true) }
    }

    private fun connect() {
        val functionName = ::connect.name
        if(_lockConnectionInfo == null) {
            showLog("Please scan QR code to get lock connection information.")
            return
        }
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
                            // filter model not support function
                            setModelSupportTaskList(lockConnectionInfo!!.model)
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
                flow { emit(lockConfigD4UseCase.get()) }
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
                flow { emit(lockConfigA0UseCase.get()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        showLog("$functionName.A0: $lockConfig")
                        // filter not support function
                        setSupportTaskList(lockConfig = lockConfig)
                    }
                    .onStart { _uiState.update { it.copy(isLoading = true) } }
                    .onCompletion { _uiState.update { it.copy(isLoading = false) } }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            is DeviceStatus.EightTwo -> {
                flow { emit(lockConfig80UseCase.get()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
                        showLog("$functionName.80: $lockConfig")
                        // filter not support function
                        setSupportTaskList(lockConfig = lockConfig)
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
                flow { emit(lockConfigA0UseCase.get()) }
                    .catch { e -> showLog("lockConfigA0UseCase.get() exception $e") }
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
                flow { emit(lockConfig80UseCase.get()) }
                    .catch { e -> showLog("lockConfig80UseCase.get() exception $e") }
                    .map { lockConfig ->
                        val value = when (lockConfig.soundType) {
                            0x01 -> if(lockConfig.soundValue == 100) 0 else 100
                            0x02 -> if(lockConfig.soundValue == 100) 50 else if(lockConfig.soundValue == 50) 0 else 100
                            else -> soundValue
                        }
                        val result = lockConfig80UseCase.setSoundValue(value)
                        showLog("$functionName at type ${lockConfig.soundType} value $value result: $result")
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
                flow { emit(lockConfigA0UseCase.get()) }
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
                flow { emit(lockConfig80UseCase.get()) }
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
                flow { emit(lockConfigA0UseCase.get()) }
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
                flow { emit(lockConfig80UseCase.get()) }
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
                flow { emit(lockConfigA0UseCase.get()) }
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
                flow { emit(lockConfig80UseCase.get()) }
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
                flow { emit(lockConfigA0UseCase.get()) }
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
                flow { emit(lockConfig80UseCase.get()) }
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
                flow { emit(lockConfigA0UseCase.get()) }
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
                flow { emit(lockConfig80UseCase.get()) }
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
                flow { emit(lockConfigA0UseCase.get()) }
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
                flow { emit(lockConfig80UseCase.get()) }
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
                flow { emit(lockConfigA0UseCase.get()) }
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
                flow { emit(lockConfig80UseCase.get()) }
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
                flow { emit(lockConfig80UseCase.get()) }
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

    private fun togglePhoneticLanguage() {
        val functionName = ::togglePhoneticLanguage.name
        when (_currentDeviceStatus) {
            is DeviceStatus.EightTwo -> {
                flow { emit(lockConfig80UseCase.get()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { lockConfig ->
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
                                val languageName = BleV3Lock.PhoneticLanguage.values().firstOrNull { it.value == nextPhoneticLanguage } ?: BleV3Lock.PhoneticLanguage.NOT_SUPPORT
                                showLog("$functionName to $languageName result: $result")
                            } else {
                                val languageName = BleV3Lock.PhoneticLanguage.values().firstOrNull { it.value == phoneticLanguage } ?: BleV3Lock.PhoneticLanguage.NOT_SUPPORT
                                showLog("Already set $languageName language.")
                            }
                        }
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
                showLog("Current is ${sunionBleNotification::class.simpleName}: ${_currentSunionBleNotification}")
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
        flow { emit(lockUtilityUseCase.getRfVersion()) }
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
        flow { emit(lockUtilityUseCase.getMcuVersion()) }
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
                if(result){
                    _lockConnectionInfo = null
                    userAbility = null
                    _uiState.update { it.copy(btnEnabled = false) }
                }
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
                if(result){
                    _lockConnectionInfo = null
                    userAbility = null
                    _uiState.update { it.copy(btnEnabled = false) }
                }
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

    private fun getTokenArray(){
        val functionName = ::getTokenArray.name
        flow { emit(lockTokenUseCase.getTokenArray()) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { tokenArray ->
                tokenArray.forEach { index ->
                    lastTokenIndex = index
                }
                showLog("$functionName: $tokenArray")
            }
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .onCompletion { _uiState.update { it.copy(isLoading = false) } }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun getToken(){
        val functionName = ::getToken.name
        flow { emit(lockTokenUseCase.getTokenArray()) }
            .catch { e -> showLog("$functionName array exception $e") }
            .map { tokenArray ->
                tokenArray.forEach { index ->
                    val deviceToken = lockTokenUseCase.getToken(index)
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
        flow { emit(lockTokenUseCase.addOneTimeToken(permission, name)) }
            .catch { e -> showLog("$functionName exception $e") }
            .map { result ->
                    showLog("$functionName permission: $permission name: $name\nresult: $result")
                    if(result.isSuccessful){
                        lastTokenIndex += 1
                    }
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
                if(result) {
                    lastTokenIndex -= 1
                }
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
                        accessCodeArray.forEachIndexed { index, value ->
                            if (value) {
                                lastCodeIndex = index
                            }
                        }
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

    private fun getAccessCode(){
        val functionName = ::getAccessCode.name
        when(_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                flow { emit(lockAccessCodeUseCase.getAccessCodeArray()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { list ->
                        val indexIterable = list.mapIndexedNotNull { index, boolean -> if (boolean && index != 0) index else null }
                        indexIterable.forEach { index ->
                            val accessCode = lockAccessCodeUseCase.getAccessCode(index)
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
                            val indexIterable = list.mapIndexedNotNull { index, boolean -> if (boolean) index else null }
                            Timber.d("indexIterable: $indexIterable")
                            indexIterable
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName array exception $e") }
                    .map { indexIterable ->
                        indexIterable.forEach { index ->
                            val accessCode = lockAccessUseCase.getAccessCode(index)
                            if(accessCode.type == 0) {
                                showLog("$functionName[$index] is access code: $accessCode")
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

    private fun addAccessCode() {
        val functionName = ::addAccessCode.name
        val isEnabled = true
        val name = "User ${lastCodeCardIndex + 1}"
        val code = "1234"
        val index = lastCodeCardIndex + 1
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.D6 -> {
                flow { emit(lockAccessCodeUseCase.addAccessCode(index, isEnabled, name, code, scheduleType)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nresult: $result")
                        if(result){
                            lastCodeIndex += 1
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
                        if(result.accessCodeQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.addAccessCode(index, isEnabled, scheduleType, name, code)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: $code scheduleType: $scheduleType\nisSuccess: $isSuccess")
                            if(isSuccess.isSuccess){
                                lastCodeIndex = index
                                lastCodeCardIndex += 1
                            }
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
            is DeviceStatus.EightTwo -> {
                val credentialStatus = BleV3Lock.UserStatus.OCCUPIED_ENABLED.value
                var userIndex = lastUserIndex
                if (userAbility == null){
                    showLog("$functionName need getUserAbility first, try again.")
                    getUserAbility()
                    return
                }
                flow { emit(lockCredentialUseCase.getCredentialByUser(lastUserIndex)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        if (userAbility!!.codeCredentialCount.isSupport()) {
                            if (userAbility!!.codeCredentialCount == (result.credentialDetail?.filter { it.type == BleV3Lock.CredentialType.PIN.value && it.status != BleV3Lock.UserStatus.AVAILABLE.value }?.size ?: 0)) {
                                userIndex += 1
                            }
                            lockCredentialUseCase.addCredentialCode(index, credentialStatus, userIndex, code)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index credentialStatus: $credentialStatus userIndex: $userIndex code: $code\nresult: $result")
                        if(result){
                            lastCodeIndex = index
                            lastCodeCardIndex += 1
                            lastCredentialIndex += 1
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
        val name = "User $lastCodeCardIndex ed"
        val code = "2345"
        val index = lastCodeIndex
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
            is DeviceStatus.EightTwo -> {
                val credentialStatus = BleV3Lock.UserStatus.OCCUPIED_ENABLED.value
                val userIndex = lastUserIndex
                flow { emit(lockCredentialUseCase.editCredentialCode(index, credentialStatus, userIndex, code)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index credentialStatus: $credentialStatus userIndex: $userIndex code: $code\nresult: $result")
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
                        if(result){
                            lastCodeIndex -= 1
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
                        if(result.accessCodeQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.deleteAccessCode(index)
                            showLog("$functionName[$index]\nisSuccess: $isSuccess")
                            if(isSuccess){
                                lastCodeCardIndex -= 1
                                lastCodeIndex -= 1
                            }
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
            is DeviceStatus.EightTwo -> {
                flow { emit(lockCredentialUseCase.deleteCredential(index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index result: $result")
                        if(result){
                            lastCodeCardIndex -= 1
                            lastCredentialIndex -= 1
                            lastCodeIndex -= 1
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

    private fun getAccessCard(){
        val functionName = ::getAccessCard.name
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        if (result.accessCardQuantity.isSupport2Byte()) {
                            val list = lockAccessUseCase.getAccessCardArray()
                            val indexIterable = list.mapIndexedNotNull { index, boolean -> if (boolean) index else null }
                            Timber.d("indexIterable: $indexIterable")
                            indexIterable
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName array exception $e") }
                    .map{ indexIterable ->
                        indexIterable.forEach { index ->
                            val accessCard = lockAccessUseCase.getAccessCard(index)
                            if(accessCard.type == 1) {
                                lastCardIndex = index
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
        val name = "User ${lastCodeCardIndex + 1}"
        val index = lastCodeCardIndex + 1
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        if(result.accessCardQuantity.isSupport2Byte()) {
                            val code = currentAccessA9Data.value.data
                            val isSuccess = lockAccessUseCase.addAccessCard(index, isEnabled, scheduleType, name, code)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: ${code.accessByteArrayToString()} scheduleType: $scheduleType\nisSuccess: $isSuccess")
                            if(isSuccess.isSuccess){
                                lastCodeCardIndex += 1
                                lastCardIndex = index
                            }
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
            is DeviceStatus.EightTwo -> {
                val credentialStatus = BleV3Lock.UserStatus.OCCUPIED_ENABLED.value
                var userIndex = lastUserIndex
                if (userAbility == null){
                    showLog("$functionName need getUserAbility first, try again.")
                    getUserAbility()
                    return
                }
                flow { emit(lockCredentialUseCase.getCredentialByUser(lastUserIndex)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        if (userAbility!!.cardCredentialCount.isSupport()) {
                            val code = currentCredential97Data.value.data
                            if (userAbility!!.cardCredentialCount == (result.credentialDetail?.filter { it.type == BleV3Lock.CredentialType.RFID.value && it.status != BleV3Lock.UserStatus.AVAILABLE.value }?.size ?: 0)) {
                                userIndex += 1
                            }
                            lockCredentialUseCase.addCredentialCard(index, credentialStatus, userIndex, code)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index credentialStatus: $credentialStatus userIndex: $userIndex code: ${currentCredential97Data.value.data.accessByteArrayToString()}\nresult: $result")
                        if(result){
                            lastCodeCardIndex += 1
                            lastCredentialIndex += 1
                            lastCardIndex = index
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
        val name = "User $lastCodeCardIndex ed"
        val index = lastCardIndex
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.accessCardQuantity.isSupport2Byte()) {
                            val code = currentAccessA9Data.value.data
                            val isSuccess = lockAccessUseCase.editAccessCard(index, isEnabled, scheduleType, name, code)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name code: ${code.accessByteArrayToString()} scheduleType: $scheduleType\nisSuccess: $isSuccess")
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
            is DeviceStatus.EightTwo -> {
                val credentialStatus = BleV3Lock.UserStatus.OCCUPIED_ENABLED.value
                val userIndex = lastUserIndex
                val code = currentCredential97Data.value.data
                flow { emit(lockCredentialUseCase.editCredentialCard(index, credentialStatus, userIndex, code)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index credentialStatus: $credentialStatus userIndex: $userIndex code: ${code.accessByteArrayToString()}\nresult: $result")
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
                            if(isSuccess){
                                lastCodeCardIndex -= 1
                                lastCardIndex -= 1
                            }
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
            is DeviceStatus.EightTwo -> {
                flow { emit(lockCredentialUseCase.deleteCredential(index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index result: $result")
                        if(result){
                            lastCodeCardIndex -= 1
                            lastCredentialIndex -= 1
                            lastCardIndex -= 1
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
        val index = lastCodeCardIndex + 2
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.accessCardQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.deviceGetAccessCard(index)
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
            is DeviceStatus.EightTwo -> {
                flow { emit(lockCredentialUseCase.deviceGetCredentialCard(index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName: $result")
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

    private fun getFingerprint(){
        val functionName = ::getFingerprint.name
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.fingerprintQuantity.isSupport2Byte()) {
                            val list = lockAccessUseCase.getFingerprintArray()
                            val indexIterable = list.mapIndexedNotNull { index, boolean -> if (boolean) index else null }
                            Timber.d("indexIterable: $indexIterable")
                            indexIterable
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName array exception $e") }
                    .map { indexIterable ->
                        indexIterable.forEach { index ->
                            val fingerprint = lockAccessUseCase.getFingerprint(index)
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
        val name = "User ${lastFingerprintIndex + 1}"
        val index = lastFingerprintIndex + 1
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.fingerprintQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.addFingerprint(index, isEnabled, scheduleType, name)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name scheduleType: $scheduleType\nisSuccess: $isSuccess")
                            if(isSuccess.isSuccess){
                                lastFingerprintIndex += 1
                            }
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
            is DeviceStatus.EightTwo -> {
                val credentialStatus = BleV3Lock.UserStatus.OCCUPIED_ENABLED.value
                var userIndex = lastUserIndex
                if (userAbility == null){
                    showLog("$functionName need getUserAbility first, try again.")
                    getUserAbility()
                    return
                }
                flow { emit(lockCredentialUseCase.getCredentialByUser(lastUserIndex)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        if (userAbility!!.fpCredentialCount.isSupport()) {
                            if (userAbility!!.fpCredentialCount == (result.credentialDetail?.filter { it.type == BleV3Lock.CredentialType.FINGERPRINT.value && it.status != BleV3Lock.UserStatus.AVAILABLE.value }?.size ?: 0)) {
                                userIndex += 1
                            }
                            lockCredentialUseCase.addCredentialFingerPrint(index, credentialStatus, userIndex)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index credentialStatus: $credentialStatus userIndex: $userIndex\nresult: $result")
                        if(result){
                            lastFingerprintIndex += 1
                            lastCredentialIndex += 1
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
        val name = "User $lastFingerprintIndex ed"
        val index = lastFingerprintIndex
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.fingerprintQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.editFingerprint(index, isEnabled, scheduleType, name)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name scheduleType: $scheduleType\nisSuccess: $isSuccess")
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
            is DeviceStatus.EightTwo -> {
                val credentialStatus = BleV3Lock.UserStatus.OCCUPIED_ENABLED.value
                val userIndex = lastUserIndex
                flow { emit(lockCredentialUseCase.editCredentialFingerPrint(index, credentialStatus, userIndex)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index credentialStatus: $credentialStatus userIndex: $userIndex\nresult: $result")
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
                            if(isSuccess){
                                lastFingerprintIndex -= 1
                            }
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
            is DeviceStatus.EightTwo -> {
                flow { emit(lockCredentialUseCase.deleteCredential(index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        if(result){
                            lastFingerprintIndex -= 1
                            lastCredentialIndex -= 1
                        }
                        showLog("$functionName index: $index result: $result")
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
        val index = lastFingerprintIndex + 2
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.fingerprintQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.deviceGetFingerprint(index)
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
            is DeviceStatus.EightTwo -> {
                flow { emit(lockCredentialUseCase.deviceGetCredentialFingerprint(index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName: $result")
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

    private fun getFace(){
        val functionName = ::getFace.name
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if (result.faceQuantity.isSupport2Byte()) {
                            val list = lockAccessUseCase.getFaceArray()
                            val indexIterable = list.mapIndexedNotNull { index, boolean -> if (boolean) index else null }
                            Timber.d("indexIterable: $indexIterable")
                            indexIterable
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName array exception $e") }
                    .map { indexIterable ->
                        indexIterable.forEach { index ->
                            val getFace = lockAccessUseCase.getFace(index)
                            if (getFace.type == 3) {
                                showLog("$functionName[$index] is face: $getFace")
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
        val name = "User ${lastFaceIndex + 1}"
        val index = lastFaceIndex + 1
        val scheduleType: AccessScheduleType = AccessScheduleType.All
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.faceQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.addFace(index, isEnabled, scheduleType, name)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name scheduleType: $scheduleType\nisSuccess: $isSuccess")
                            if(isSuccess.isSuccess) {
                                lastFaceIndex += 1
                            }
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
            is DeviceStatus.EightTwo -> {
                val credentialStatus = BleV3Lock.UserStatus.OCCUPIED_ENABLED.value
                var userIndex = lastUserIndex
                if (userAbility == null){
                    showLog("$functionName need getUserAbility first, try again.")
                    getUserAbility()
                    return
                }
                flow { emit(lockCredentialUseCase.getCredentialByUser(lastUserIndex)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        if (userAbility!!.faceCredentialCount.isSupport()) {
                            if (userAbility!!.faceCredentialCount == (result.credentialDetail?.filter { it.type == BleV3Lock.CredentialType.FACE.value && it.status != BleV3Lock.UserStatus.AVAILABLE.value }?.size ?: 0)) {
                                userIndex += 1
                            }
                            lockCredentialUseCase.addCredentialFace(index, credentialStatus, userIndex)
                        } else {
                            throw LockStatusException.LockFunctionNotSupportException()
                        }
                    }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index credentialStatus: $credentialStatus userIndex: $userIndex\nresult: $result")
                        if(result){
                            lastFaceIndex += 1
                            lastCredentialIndex += 1
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
        val name = "User $lastFaceIndex ed"
        val index = lastFaceIndex
        val scheduleType: AccessScheduleType = AccessScheduleType.SingleEntry
        when(_currentDeviceStatus) {
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.faceQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.editFace(index, isEnabled, scheduleType, name)
                            showLog("$functionName index: $index isEnabled: $isEnabled name: $name scheduleType: $scheduleType\nisSuccess: $isSuccess")
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
            is DeviceStatus.EightTwo -> {
                val credentialStatus = BleV3Lock.UserStatus.OCCUPIED_ENABLED.value
                val userIndex = lastUserIndex
                flow { emit(lockCredentialUseCase.editCredentialFace(index, credentialStatus, userIndex)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index credentialStatus: $credentialStatus userIndex: $userIndex\nresult: $result")
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
                            if(isSuccess){
                                lastFaceIndex -= 1
                            }
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
            is DeviceStatus.EightTwo -> {
                flow { emit(lockCredentialUseCase.deleteCredential(index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index result: $result")
                        if(result){
                            lastFaceIndex -= 1
                            lastCredentialIndex -= 1
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
        val index = lastFaceIndex + 2
        when(_currentDeviceStatus){
            is DeviceStatus.A2 -> {
                flow { emit(lockUtilityUseCase.getLockSupportedUnlockTypes()) }
                    .catch { e -> showLog("getLockSupportedUnlockTypes exception $e") }
                    .map { result ->
                        if(result.faceQuantity.isSupport2Byte()) {
                            val isSuccess = lockAccessUseCase.deviceGetFace(index)
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
            is DeviceStatus.EightTwo -> {
                flow { emit(lockCredentialUseCase.deviceGetCredentialFace(index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName: $result")
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

    private fun deviceExitAccess(accessType: Int, index:Int) {
        val functionName = ::deviceExitAccess.name
        when(accessType){
            Access.Type.CARD.value -> {
                flow { emit(lockAccessUseCase.deviceExitAccessCard(index)) }
                    .map { result ->
                        Timber.d("$functionName: $result\n")
                    }
                    .catch { e -> Timber.e("$functionName: exception $e") }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            Access.Type.FINGERPRINT.value-> {
                flow { emit(lockAccessUseCase.deviceExitFingerprint(index)) }
                    .map { result ->
                        Timber.d("$functionName: $result\n")
                    }
                    .catch { e -> Timber.e("$functionName: exception $e") }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            Access.Type.FINGERPRINT.value -> {
                flow { emit(lockAccessUseCase.deviceExitFace(index)) }
                    .map { result ->
                        Timber.d("$functionName: $result\n")
                    }
                    .catch { e -> Timber.e("$functionName: exception $e") }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
        }
    }

    private fun deviceExitCredential(accessType: Int, index:Int) {
        val functionName = ::deviceExitCredential.name
        when(accessType){
            BleV3Lock.CredentialType.RFID.value -> {
                flow { emit(lockCredentialUseCase.deviceExitCredentialCard(index)) }
                    .map { result ->
                        Timber.d("$functionName: $result\n")
                    }
                    .catch { e -> Timber.e("$functionName: exception $e") }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            BleV3Lock.CredentialType.FINGERPRINT.value-> {
                flow { emit(lockCredentialUseCase.deviceExitCredentialFingerprint(index)) }
                    .map { result ->
                        Timber.d("$functionName: $result\n")
                    }
                    .catch { e -> Timber.e("$functionName: exception $e") }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
            BleV3Lock.CredentialType.FACE.value -> {
                flow { emit(lockCredentialUseCase.deviceExitCredentialFace(index)) }
                    .map { result ->
                        Timber.d("$functionName: $result\n")
                    }
                    .catch { e -> Timber.e("$functionName: exception $e") }
                    .flowOn(Dispatchers.IO)
                    .launchIn(viewModelScope)
            }
        }
    }

    private fun addCredentialFingerVein() {
        val functionName = ::addCredentialFingerVein.name
        val index = lastFingerVeinIndex + 1
        val credentialStatus = BleV3Lock.UserStatus.OCCUPIED_ENABLED.value
        val userIndex = lastUserIndex
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                if (userAbility == null){
                    showLog("$functionName need getUserAbility first, try again.")
                    getUserAbility()
                    return
                }
                flow { emit(lockCredentialUseCase.addCredentialFingerVein(index, credentialStatus, userIndex)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index credentialStatus: $credentialStatus userIndex: $userIndex\nresult: $result")
                        if(result){
                            lastFingerVeinIndex += 1
                            lastCredentialIndex += 1
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

    private fun editCredentialFingerVein() {
        val functionName = ::editCredentialFingerVein.name
        val index = lastFingerVeinIndex
        val credentialStatus = BleV3Lock.UserStatus.OCCUPIED_ENABLED.value
        val userIndex = lastUserIndex
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockCredentialUseCase.editCredentialFingerVein(index, credentialStatus, userIndex)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index credentialStatus: $credentialStatus userIndex: $userIndex\nresult: $result")
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

    private fun deleteCredentialFingerVein(index: Int) {
        val functionName = ::deleteCredentialFingerVein.name
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockCredentialUseCase.deleteCredential(index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName index: $index result: $result")
                        if(result){
                            lastFingerVeinIndex -= 1
                            lastCredentialIndex -= 1
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

    private fun deviceGetCredentialFingerVein(){
        val functionName = ::deviceGetCredentialFingerVein.name
        val index = lastFingerVeinIndex + 2
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockCredentialUseCase.deviceGetCredentialFingerVein(index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName: $result")
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
                    lastEventLogIndex = index
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
                if(result){
                    lastEventLogIndex -= 1
                }
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
                        // filter not support function
                        setSupportTaskList(supportedUnlockType = result)
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

    private fun getUserAbility() {
        val functionName = ::getUserAbility.name
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockUtilityUseCase.getUserAbility()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName result: $result")
                        userAbility = result
                        // filter not support function
                        setSupportTaskList(userAbility = result)
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

    private fun getUserCount() {
        val functionName = ::getUserCount.name
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockUtilityUseCase.getUserCount()) }
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

    private fun isMatterDevice() {
        val functionName = ::isMatterDevice.name
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockUtilityUseCase.isMatterDevice()) }
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

    private fun getUserArray() {
        val functionName = ::getUserArray.name
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockUserUseCase.getUserArray()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        result.forEachIndexed { index, value ->
                            if (value) {
                                lastUserIndex = index
                            }
                        }
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

    private fun getUser() {
        val functionName = ::getUser.name
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockUserUseCase.getUserArray()) }
                    .catch { e -> showLog("$functionName array exception $e") }
                    .map { list ->
                        val indexIterable = list.mapIndexedNotNull { index, boolean -> if (boolean) index else null }
                        Timber.d("indexIterable: $indexIterable")
                        indexIterable.forEach { index ->
                            val user = lockUserUseCase.getUser(index)
                            showLog("$functionName[$index] result: $user")
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

    private fun addUser() {
        val functionName = ::addUser.name
        val name = "User ${lastUserIndex + 1}"
        val index = lastUserIndex + 1
        val uid = index
        val userStatus = BleV3Lock.UserStatus.OCCUPIED_ENABLED.value
        val userType = BleV3Lock.UserType.UNRESTRICTED.value
        val credentialRule = BleV3Lock.CredentialRule.SINGLE.value
        val credentialList = mutableListOf<BleV3Lock.Credential>()
        val weekDaySchedule = mutableListOf<BleV3Lock.WeekDaySchedule>()
        val yearDaySchedule = mutableListOf<BleV3Lock.YearDaySchedule>()
        if (userAbility == null){
            showLog("$functionName need getUserAbility first, try again.")
            getUserAbility()
            return
        }
        val timestamp = System.currentTimeMillis()
        if(userAbility!!.isMatter) {
            for (i in 0 until userAbility!!.codeCredentialCount){
                credentialList.add(BleV3Lock.Credential(BleV3Lock.CredentialType.PIN.value, 0xFFFF))
            }
            for (i in 0 until userAbility!!.cardCredentialCount){
                credentialList.add(BleV3Lock.Credential(BleV3Lock.CredentialType.RFID.value, 0xFFFF))
            }
            for (i in 0 until userAbility!!.fpCredentialCount){
                credentialList.add(BleV3Lock.Credential(BleV3Lock.CredentialType.FINGERPRINT.value, 0xFFFF))
            }
            for (i in 0 until userAbility!!.faceCredentialCount){
                credentialList.add(BleV3Lock.Credential(BleV3Lock.CredentialType.FACE.value, 0xFFFF))
            }
            for (i in 0 until userAbility!!.weekDayScheduleCount){
                weekDaySchedule.add(BleV3Lock.WeekDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, enumValues<BleV3Lock.DaysMaskMap>()[i].value, 8, 0, 18, 0))
            }
            for (i in 0 until userAbility!!.yearDayScheduleCount){
                yearDaySchedule.add(BleV3Lock.YearDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, timestamp, timestamp))
            }
        } else {
            for (i in 0 until userAbility!!.codeCredentialCount){
                credentialList.add(BleV3Lock.Credential(BleV3Lock.CredentialType.PIN.value, 0xFFFF))
            }
            for (i in 0 until userAbility!!.weekDayScheduleCount){
                weekDaySchedule.add(BleV3Lock.WeekDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, enumValues<BleV3Lock.DaysMaskMap>()[i].value, 8, 0, 18, 0))
            }
            for (i in 0 until userAbility!!.yearDayScheduleCount){
                yearDaySchedule.add(BleV3Lock.YearDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, timestamp, timestamp))
            }
        }
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockUserUseCase.addUser(index, name, uid, userStatus, userType, credentialRule, credentialList, weekDaySchedule, yearDaySchedule)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName name: $name index: $index uid: $uid userStatus: $userStatus userType: $userType credentialRule: $credentialRule credentialList: $credentialList weekDaySchedule: $weekDaySchedule yearDaySchedule: $yearDaySchedule\nresult: $result")
                        if(result){
                            lastUserIndex += 1
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

    private fun editUser() {
        val functionName = ::editUser.name
        val name = "User $lastUserIndex ed"
        val index = lastUserIndex
        val uid = index
        val userStatus = BleV3Lock.UserStatus.OCCUPIED_ENABLED.value
        val userType = BleV3Lock.UserType.DISPOSABLE.value
        val credentialRule = BleV3Lock.CredentialRule.SINGLE.value
        val credentialList = mutableListOf<BleV3Lock.Credential>()
        val weekDaySchedule = mutableListOf<BleV3Lock.WeekDaySchedule>()
        val yearDaySchedule = mutableListOf<BleV3Lock.YearDaySchedule>()
        if (userAbility == null){
            showLog("$functionName need getUserAbility first, try again.")
            getUserAbility()
            return
        }
        val timestamp = System.currentTimeMillis()
        if(userAbility!!.isMatter) {
            for (i in 0 until userAbility!!.codeCredentialCount){
                credentialList.add(BleV3Lock.Credential(BleV3Lock.CredentialType.PIN.value, 0xFFFF))
            }
            for (i in 0 until userAbility!!.cardCredentialCount){
                credentialList.add(BleV3Lock.Credential(BleV3Lock.CredentialType.RFID.value, 0xFFFF))
            }
            for (i in 0 until userAbility!!.fpCredentialCount){
                credentialList.add(BleV3Lock.Credential(BleV3Lock.CredentialType.FINGERPRINT.value, 0xFFFF))
            }
            for (i in 0 until userAbility!!.faceCredentialCount){
                credentialList.add(BleV3Lock.Credential(BleV3Lock.CredentialType.FACE.value, 0xFFFF))
            }
            for (i in 0 until userAbility!!.weekDayScheduleCount){
                weekDaySchedule.add(BleV3Lock.WeekDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, enumValues<BleV3Lock.DaysMaskMap>()[i].value, 8, 0, 18, 0))
            }
            for (i in 0 until userAbility!!.yearDayScheduleCount){
                yearDaySchedule.add(BleV3Lock.YearDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, timestamp, timestamp))
            }
        } else {
            for (i in 0 until userAbility!!.codeCredentialCount){
                credentialList.add(BleV3Lock.Credential(BleV3Lock.CredentialType.PIN.value, 0xFFFF))
            }
            for (i in 0 until userAbility!!.weekDayScheduleCount){
                weekDaySchedule.add(BleV3Lock.WeekDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, enumValues<BleV3Lock.DaysMaskMap>()[i].value, 8, 0, 18, 0))
            }
            for (i in 0 until userAbility!!.yearDayScheduleCount){
                yearDaySchedule.add(BleV3Lock.YearDaySchedule(BleV3Lock.ScheduleStatus.AVAILABLE.value, timestamp, timestamp))
            }
        }

        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockUserUseCase.editUser(index, name, uid, userStatus, userType, credentialRule, credentialList, weekDaySchedule, yearDaySchedule)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName name: $name index: $index uid: $uid userStatus: $userStatus userType: $userType credentialRule: $credentialRule credentialList: $credentialList weekDaySchedule: $weekDaySchedule yearDaySchedule: $yearDaySchedule\nresult: $result")
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

    private fun deleteUser(index: Int){
        val functionName = ::deleteUser.name
        when(_currentDeviceStatus) {
            is DeviceStatus.EightTwo -> {
                flow { emit(lockUserUseCase.deleteUser(index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map{ result ->
                        showLog("$functionName result: $result")
                        if(result){
                            lastUserIndex -= 1
                        }
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

    private fun getCredentialArray() {
        val functionName = ::getCredentialArray.name
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockCredentialUseCase.getCredentialArray()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        result.forEachIndexed { index, value ->
                            if (value) {
                                lastCredentialIndex = index
                            }
                        }
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

    private fun getCredential() {
        val functionName = ::getCredential.name
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockCredentialUseCase.getCredentialArray()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        result.forEachIndexed { index, value ->
                            if (value) {
                                lastCredentialIndex = index
                                val credential = lockCredentialUseCase.getCredentialByCredential(index)
                                showLog("$functionName credential[$index]: $credential")
                                if(credential.type == BleV3Lock.CredentialType.PIN.value){
                                    lastCodeIndex = index
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

    private fun getCredentialByCredential() {
        val functionName = ::getCredentialByCredential.name
        val index = lastCredentialIndex
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockCredentialUseCase.getCredentialByCredential(index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map {
                        showLog("$functionName credential[$index] result: $it")
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

    private fun getCredentialByUser() {
        val functionName = ::getCredentialByUser.name
        val index = lastUserIndex
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockCredentialUseCase.getCredentialByUser(index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map {
                        showLog("$functionName user[$index] result: $it")
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

    private fun getCredentialHash() {
        val functionName = ::getCredentialHash.name
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockDataUseCase.getCredentialHash()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName result: $result")
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

    private fun getUserHash() {
        val functionName = ::getUserHash.name
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockDataUseCase.getUserHash()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName result: $result")
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

    private fun hasUnsyncedData() {
        val functionName = ::hasUnsyncedData.name
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockDataUseCase.hasUnsyncedData()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName result: $result")
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

    private fun getUnsyncedData() {
        val functionName = ::getUnsyncedData.name
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockDataUseCase.getUnsyncedData()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        lastUnsyncedData = result
                        showLog("$functionName result: $result")
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

    private fun setUserUnsyncedData() {
        val functionName = ::setUserUnsyncedData.name
        if (lastUnsyncedData == null){
            showLog("$functionName lastUnsyncedData is null.")
            return
        }
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockDataUseCase.setUserUnsyncedData(lastUnsyncedData!!.index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName result: $result")
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

    private fun setCredentialUnsyncedData() {
        val functionName = ::setCredentialUnsyncedData.name
        if (lastUnsyncedData == null){
            showLog("$functionName lastUnsyncedData is null.")
            return
        }
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockDataUseCase.setCredentialUnsyncedData(lastUnsyncedData!!.index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName result: $result")
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

    private fun setLogUnsyncedData() {
        val functionName = ::setLogUnsyncedData.name
        if (lastUnsyncedData == null){
            showLog("$functionName lastUnsyncedData is null.")
            return
        }
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockDataUseCase.setLogUnsyncedData(lastUnsyncedData!!.index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName result: $result")
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

    private fun setTokenUnsyncedData() {
        val functionName = ::setTokenUnsyncedData.name
        if (lastUnsyncedData == null){
            showLog("$functionName lastUnsyncedData is null.")
            return
        }
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockDataUseCase.setTokenUnsyncedData(lastUnsyncedData!!.index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName result: $result")
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

    private fun setSettingUnsyncedData() {
        val functionName = ::setSettingUnsyncedData.name
        if (lastUnsyncedData == null){
            showLog("$functionName lastUnsyncedData is null.")
            return
        }
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockDataUseCase.setSettingUnsyncedData(lastUnsyncedData!!.index)) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName result: $result")
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

    private fun setAllDataSynced() {
        val functionName = ::setAllDataSynced.name
        when(_currentDeviceStatus){
            is DeviceStatus.EightTwo -> {
                flow { emit(lockDataUseCase.setAllDataSynced()) }
                    .catch { e -> showLog("$functionName exception $e") }
                    .map { result ->
                        showLog("$functionName result: $result")
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

    fun setQRCodeContent(content: String) {
        val functionName = ::setQRCodeContent.name
        currentQrCodeContent = content
        Timber.d("$functionName: $content ${content.isDeviceUuid()}")

        viewModelScope.launch {
            if(content.isDeviceUuid()) {
                _uiEvent.emit(UiEvent.Complete)
                currentProductionGetResponse = deviceApiRepository.getProduction(code = content)
                startBleScan(content, currentProductionGetResponse!!)
            } else {
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

    fun startBleScan(uuid: String, productionGetResponse: ProductionGetResponse, isReconnect: Boolean = false) {
        val disposable = bleScanUseCase.scanUuid(uuid = uuid)
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
                },
                {
                    // 處理掃描完成事件
                    Timber.d("Scan complete")
                    if(productionGetResponse.address.isNullOrBlank() && currentConnectMacAddress.isNullOrBlank()){
                        showLog("Production api or scan ble to get mac address failed.")
                    }
                    if(isReconnect){
                        _lockConnectionInfo = _lockConnectionInfo!!.copy(macAddress = currentConnectMacAddress!!)
                        connect()
                    } else {
                        _lockConnectionInfo = LockConnectionInfo.from(productionGetResponse, currentConnectMacAddress)
                        Timber.d("_lockConnectionInfo: $_lockConnectionInfo")
                        showLog("Lock connection information:", true)
                        showLog("macAddress: ${_lockConnectionInfo!!.macAddress}")
                        showLog("oneTimeToken: ${_lockConnectionInfo!!.oneTimeToken}")
                        showLog("keyOne: ${_lockConnectionInfo!!.keyOne}")
                        showLog("Please execute Connect to pair with lock.")
                        _uiState.update { it.copy(btnEnabled = true) }
                    }
                }
            )
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
            "TNRFp00" -> {
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ScanWifi }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ConnectToWifi }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.FactoryResetNoAdmin }
            }
            "KD01" -> {
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ScanWifi }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.ConnectToWifi }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.FactoryResetNoAdmin }
                supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.TogglePlugState }
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
                        supportTaskList.removeIf { it.first == BleDeviceFeature.TaskCode.AutoUnlockToggleSecurityBolt }
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
    val taskList: Array<Triple<BleDeviceFeature.TaskCode, String, Set<String>>> = BleDeviceFeature.initTaskList
)

sealed class UiEvent {
    object Complete: UiEvent()
}
