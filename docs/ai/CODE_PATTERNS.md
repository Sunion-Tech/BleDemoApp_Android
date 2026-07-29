# 程式碼範式（完整範例）

> **何時讀本檔**：要新增或修改 BLE 指令、UseCase、`HomeViewModel` 函式，或動到 Coroutine/Flow 之前**必讀**；
> 只做小幅修改（改字串、調參數）不必讀。
> 規則摘要在 [CLAUDE.md](../../CLAUDE.md)，本檔是可直接照抄的完整範例（**內容全部源自本 repo 實際程式碼**，
> 以 `HomeViewModel`、`LockAccessCodeUseCase`、`DeviceStatus82Command` 為範本）。
> 範例即規格：**新程式碼長得跟範例不一樣就是錯**（除非該檔既有寫法不同——跟隨既有寫法並在回報中註明）。
>
> **最後與程式碼對齊：2026-07-29**（Android 17／AGP 9 升級後）。行號會漂，以實際檔案為準。
> 該次升級**沒有改動任何程式碼範式**——只動 build script 與 `HiltApplication` 的 WorkManager API。
> 例外語意／取消傳播的修正刻意排到 backlog **§R 階段 R3.5**（見 §3.2 的提醒）。
> 本檔不寫任何套件版本號——版本以 `build.gradle` 為準（理由見 [AGENTS.md](../../AGENTS.md) 開頭）。

---

## 0. 先看懂：一個 BLE 功能的完整鏈路

```
使用者點清單項目 → HomeScreen 呼叫 viewModel.setTaskCode(TaskCode.XXX)
使用者按 Execute  → viewModel.executeTask()
                      └ runWithLoading("executeTask") { when(taskCode) { TaskCode.XXX -> xxx() } }
                            └ private suspend fun xxx()
                                  └ xxxUseCase()                     ← core_ble_android/usecase/
                                        └ statefulConnection.setupSingleNotificationThenSendCommand(sendCmd)
                                              └ BleCmdRepository.createCommand() / resolve()  ← 封包＋AES
                                  └ showLog("結果…")                  ← 印到畫面 log 區
```

新增功能＝**在這條鏈路上補齊四個點**（TaskCode／taskList、UseCase、VM 分支、VM 私有函式）。
細節見 [CLAUDE.md §3](../../CLAUDE.md)。

> ⚠️ **清單是連線後才長出來的**：`UiState.taskList` 預設是 `BleDeviceFeature.initTaskList`
> （只有 Connect / Disconnect，`HomeViewModel.kt:4479`）；連線成功後 `setModelSupportTaskList()`
> （`:3305`）依機種 `removeIf` 過濾後才換上完整 `taskList`（`:3340`），斷線重設回 initTaskList（`:3045`）。
> 加了新 TaskCode 在未連線畫面看不到是正常的，不是你寫錯。

---

## 1. BLE UseCase 標準結構（`core_ble_android/usecase/`）

以 `LockAccessCodeUseCase.getAccessCodeArray()` 為範本（`core_ble_android/.../usecase/LockAccessCodeUseCase.kt:15`）。
下列片段為**可讀性整理版**：省略了原始碼第二個 `.map` 內一行無作用的 `list.toList()`（死碼），
並把多行呼叫壓成單行。邏輯與算子順序與原檔一致，要逐字對照請開原檔。

```kotlin
@Singleton
class LockAccessCodeUseCase @Inject constructor(
    private val bleCmdRepository: BleCmdRepository,
    private val statefulConnection: ReactiveStatefulConnection
) {
    // log 前綴，每個 UseCase 都有這一行（:19）
    private val className = this::class.simpleName ?: "LockAccessCodeUseCase"

    suspend fun getAccessCodeArray(): List<Boolean> {
        // ① 前置檢查：未連線直接丟例外，不要回傳空值假裝成功
        if (!statefulConnection.isConnectedWithDevice()) throw NotConnectedException()

        // ② functionName ＋ function code 常數化（CLAUDE.md 鐵律 6）
        val functionName = ::getAccessCodeArray.name
        val function = 0xEA

        // ③ 組指令
        val sendCmd = bleCmdRepository.createCommand(
            function = function,
            key = statefulConnection.key(),
        )

        // ④ 送出 → 過濾出屬於自己的通知 → 只取一筆 → 解析 → 收斂
        return statefulConnection
            .setupSingleNotificationThenSendCommand(sendCmd, "$className.$functionName")
            .filter { notification ->
                bleCmdRepository.isValidNotification(statefulConnection.key(), notification, function)
            }
            .take(1)
            .map { notification ->
                bleCmdRepository.resolve(function, statefulConnection.key(), notification) as ByteArray
            }
            .map { decoded ->
                val list = mutableListOf<Boolean>()
                decoded.forEach { it.toBooleanList(list) }
                list
            }
            .flowOn(Dispatchers.IO)
            .catch { e -> Timber.e("$functionName exception $e") }
            .single()
    }
}
```

**這五個算子的順序是規格，不是風格**：

| 算子 | 為什麼不能省 |
|------|-------------|
| `.filter { isValidNotification(...) }` | BLE notification 是**共用通道**，別的指令的回應也會流過來。不過濾＝解析到別人的封包。 |
| `.take(1)` | 不加會一直等，`single()` 收到第二筆就丟例外。 |
| `.flowOn(Dispatchers.IO)` | BLE I/O 不能佔用主執行緒。 |
| `.catch { Timber.e(...) }` | Flow 內例外不 catch 會炸穿到 VM 的 `runWithLoading`，log 就少了指令層的脈絡。 |
| `.single()` | 把 Flow 收斂成 suspend 回傳值，上層才好寫。 |

規則：
- `@Inject constructor`，**不要新增 Hilt Module**（本模組全靠 constructor injection，零 `@Module`）。
- `@Singleton` **不是全體慣例**：27 個 UseCase 中 21 個有、6 個沒有
  （`LockCredentialUseCase`、`LockDataUseCase`、`LockEventLogUseCase`、`LockUserUseCase`、
  `LockWifiUseCase`、`PlugConfigUseCase`）。新增時跟隨同性質的鄰近檔，不要一律加。
- UseCase 只回傳解析後的資料型別，**不碰 UI、不碰 Timber 以外的 log**。
- 命名 `Lock{Feature}UseCase` / `{Feature}UseCase`（唯一例外：`BluetoothAvailableStateCollector`）。

---

## 2. BLE 指令類別（`core_ble_android/command/`）

只有「封包組裝／解析邏輯需要獨立複用」時才在 `command/` 新增類別；一般功能直接用
`bleCmdRepository.createCommand()` / `resolve()` 就夠。

`Command.kt` 全檔 30 行，裡面有**兩個**東西（`core_ble_android/.../command/Command.kt`）：

```kotlin
interface BleCommand<I, R> {
    fun create(function:Int, key: String, data: I): ByteArray      // 注意：專案寫法無空格
    fun parseResult(function:Int, key: String, data: ByteArray): R
    fun match(function:Int, key: String, data: ByteArray): Boolean

    companion object {                       // WiFi 指令用的字首常數
        const val CMD_LIST_WIFI = "L"
        const val CMD_SET_SSID_PREFIX = "S"
        const val CMD_SET_PASSWORD_PREFIX = "P"
        const val CMD_CONNECT = "C"
    }
}

abstract class BaseCommand<I, R>(private val bleCmdRepository: BleCmdRepository) :
    BleCommand<I, R> {
    override fun match(function:Int, key: String, data: ByteArray): Boolean {
        val decrypted = bleCmdRepository.decrypt(key.hexToByteArray(), data)!!
        val match = decrypted.component3().unSignedInt() == function
        Timber.d("match:$match (${decrypted.toHexPrint()})")
        return match
    }
}
```

**該繼承 `BaseCommand` 還是直接實作 `BleCommand`？**

| 情況 | 選擇 |
|------|------|
| 回應可用「解密後第 3 byte == function」判斷 | `BaseCommand`，`match()` 白拿 |
| 需要自訂比對（例如同時接受 `82` 或 `EF`） | 直接實作 `BleCommand`，自己寫 `match()`——即 `DeviceStatus82Command` 的做法 |

典型實作（`core_ble_android/.../command/DeviceStatus82Command.kt`，全檔 29 行）：

```kotlin
class DeviceStatus82Command(private val bleCmdRepository: BleCmdRepository) :
    BleCommand<Unit, DeviceStatus.EightTwo> {

    override fun create(function: Int, key: String, data: Unit): ByteArray {
        return bleCmdRepository.createCommand(
            function = function,
            key = key.hexToByteArray()
        )
    }

    override fun parseResult(function: Int, key: String, data: ByteArray): DeviceStatus.EightTwo {
        return bleCmdRepository.resolve(
            function = function,
            key = key.hexToByteArray(),
            notification = data
        ) as DeviceStatus.EightTwo
    }

    /** receive 82 or EF **/
    override fun match(function: Int, key: String, data: ByteArray): Boolean {
        return bleCmdRepository.isValidNotification(key.hexToByteArray(), data, function)
    }
}
```

命名：`{Subject}{功能碼}Command`（`DeviceStatus82Command`、`DeviceStatusA2Command`、`DeviceStatusD6Command`）
或 `{Subject}Command`（`AccessCodeCommand`、`WifiListCommand`、`WifiConnectCommand`）。
回傳型別對應 `entity/` 的 sealed class 子類（`DeviceStatus.EightTwo`、`LockConfig.D4`…）。

封包底層（`BleCmdRepository.kt`）：AES-ECB（`AES/ECB/NoPadding`，`:25`）、
`pad()` 補到 16 bytes 對齊（`:200`）、MTU 由 `ReactiveStatefulConnection` 協商
（`connection.requestMtu(GATT_MTU_MAXIMUM)`，`:229`）。**上層不管分包。**

---

## 3. HomeViewModel 範式（`app/.../HomeViewModel.kt`）

### 3.1 結構（`:49` 註解、`:50` class 宣告）

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val isBlueToothEnabledUseCase: IsBlueToothEnabledUseCase,
    private val bluetoothAvailableStateCollector: BluetoothAvailableStateCollector,
    private val statefulConnection: ReactiveStatefulConnection,
    private val lockQRCodeUseCase: LockQRCodeUseCase,
    // …中間還有 24 個 UseCase…
    private val deviceApiRepository: DeviceApiRepository,
    private val bleScanUseCase: BleScanUseCase,
    private val application: Application,
): ViewModel() {                      // 共 30 個建構子參數

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent

    // 畫面 log 區（這支 App 的主要輸出）
    private val _logList: MutableStateFlow<MutableList<String>> = MutableStateFlow(mutableListOf())
    val logList: MutableStateFlow<MutableList<String>> get() = _logList

    // Job 追蹤變數
    private var _bleConnectionStateListener: Job? = null
    private var _bleSunionBleNotificationListener: Job? = null
}
```

> ⚠️ **這個 ViewModel 是約 4500 行、注入 30 個依賴的 God ViewModel，且未實作 `onCleared()`**。
> 這是**已知技術債（backlog B1、B2），不是值得模仿的架構**。
> 新增功能時照既有寫法加沒問題（一致性優先），但**不要把它當作「大 VM 沒問題」的依據**，
> 也不要在別處複製這個規模。

### 3.2 兩個必用的 helper

```kotlin
// :3549 — 統一的 loading ＋ 例外收斂。所有 BLE 動作都包在裡面。
private suspend fun runWithLoading(functionName: String, block: suspend () -> Unit) {
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

// :3104 — 印到畫面 log 區。注意 emit 前建立新 list（否則不觸發 recomposition）
private fun showLog(msg: String, isClear: Boolean = false) {
    if (isClear) _logList.value.clear()
    _logList.update { _logList.value.toMutableList().apply { this.add("$msg\n") } }
}
```

**`LockFunctionNotSupportException` 被單獨接住是刻意的**：不同機種／協定世代支援的指令不同，
不支援不是錯誤，是預期結果——所以印訊息、不當成 exception 洗版。新增指令若有類似「預期內失敗」，
照這個模式加 catch 分支，不要吞掉一般 `Exception`。

> ⚠️ **這一段的例外處理有三個已知缺陷，但現在刻意維持原狀**（2026-07-29）：
> ① BLE 自訂例外繼承 `Throwable`，`catch (e: Exception)` **接不到**，會逃到 coroutine 頂層閃退；
> ② 寬泛 catch 沒有放行 `CancellationException`（改繼承 `Exception` 後才會成為問題）；
> ③ `btnEnabled` 的還原在呼叫端而非 `finally`。
> 三者互相牽連，**必須一起改、且要實機驗證**，所以整批排進 backlog **§R 階段 R3.5**
> （在 R4 測試向量之前），不隨 Android 17 升級一起動。
> **在 R3.5 完成前，新增的 catch 就照上面現有形狀寫**，不要單獨補 rethrow——只補一半會把
> 「連線逾時」變成畫面完全沒有 log（已實測過這個後果，見 backlog R3.5 說明）。

### 3.3 新增一個功能函式（照抄這個形狀）

```kotlin
// ① executeTask() 的 when 加分支（:192 起）
BleDeviceFeature.TaskCode.GetLockTime -> {
    getLockTime()
}

// ② 私有 suspend 函式（HomeViewModel.kt 實際程式碼，逐字）
private suspend fun getLockTime(): Int {
    val functionName = "getLockTime"
    val result = lockTimeUseCase.getTime()
    showLog("$functionName: $result")
    return result
}
```

`functionName` 兩種寫法在本專案都存在，且**字面字串佔絕大多數**（`HomeViewModel` 內字面 86 處、
反射式 `::xxx.name` 17 處）。新程式碼優先反射式（改名會跟著走），改既有函式則跟隨該檔鄰近寫法。

函式有無回傳值都可以——`executeTask()` 與 `executeAutoTest()` 的 when 分支都是**裸呼叫、不取回傳值**，
全檔只有 `:745`（`isAdminCodeExists()`）、`:3033`（`isOtaWorkerExist()`）真的消費回傳值。
所以回傳型別只在你自己需要串接時才加。

外部進入點（非 `executeTask` 觸發的）則自己起 coroutine 並包 `runWithLoading`（`:3056` `setQRCodeContent` 為例）：

```kotlin
fun setQRCodeContent(content: String) {
    val functionName = ::setQRCodeContent.name
    Timber.d("$functionName: $content ${content.isDeviceUuid()}")
    viewModelScope.launch {
        runWithLoading(functionName) {
            // …
            _uiState.update { it.copy(btnEnabled = true) }
        }
    }
}
```

### 3.4 Coroutine／Flow 規範

```kotlin
// ✅ IO 操作指定 Dispatcher
viewModelScope.launch(Dispatchers.IO) { /* … */ }

// ✅ 同一 Job 重新啟動前先取消（避免重複訂閱）
_bleConnectionStateListener?.cancel()
_bleConnectionStateListener = statefulConnection.connState
    .onEach { /* … */ }
    .catch { Timber.e("connState exception $it") }
    .flowOn(Dispatchers.Default)
    .launchIn(viewModelScope)

// ✅ 一次性 suspend 操作用 runCatching
runCatching { deviceApiRepository.getProduction(code = content) }
    .onSuccess { /* … */ }
    .onFailure { e -> Timber.e(e, "getProduction failed") }
```

### 3.5 Null Safety

```kotlin
// ✅ 安全呼叫 + Elvis（提早 return 並記 log）
val mac = currentConnectMacAddress ?: run { showLog("mac is null, skip"); return }

// ❌ 禁止 Force Unwrap（除非緊鄰上方已明確判斷非 null）
startBleScan(currentQrCodeContent!!, currentProductionGetResponse!!)
```

> ⚠️ `HomeViewModel` 現存 `!!` **43 行／44 次**（backlog B2）。既有程式碼維持原狀，
> **新寫的程式碼不准再加**。要改既有的請走 B2 統一處理，不要零散改動。

---

## 4. Compose Screen 範式（`app/ui/screen/`）

```kotlin
@Composable
fun HomeScreen(viewModel: HomeViewModel, navController: NavController) {
    val uiState = viewModel.uiState.collectAsState().value   // 專案慣例：collectAsState()
    val logList = viewModel.logList.collectAsState().value

    // 有狀態的外層 Composable 只負責取狀態、綁 callback，
    // 真正的 UI 交給同名的無狀態 overload（stateless），參數全部傳進去
    HomeScreen(
        uiState,
        logList,
        onExecuteClicked = { viewModel.executeTask() },
        shouldShowTaskList = viewModel::shouldShowTaskList,
        onTaskItemClicked = viewModel::setTaskCode,
    )

    if (uiState.isLoading) LoadingScreen()
}

// ❌ 禁止在 Composable 直接呼叫 BLE
Button(onClick = { statefulConnection.setupSingleNotificationThenSendCommand(/* … */) }) { /* … */ }
```

- 全 Material 2（`import androidx.compose.material.*`），**不准出現 material3**。
- 相機權限用 Accompanist（`rememberPermissionState`）；BLE／通知權限則在
  `MainActivity.onCreate()` 一次性用 `RequestMultiplePermissions` 請求
  （launcher 宣告 `MainActivity.kt:24`，實際 `launch()` 在 `:83`）。
- 導航：`HomeNavHost.kt` 的 `sealed class HomeRoute(val route: String)`，
  route 字串**沿用既有 PascalCase**（`"Home"`、`"Scan"`，`:24`）。
  另注意 `MainActivity.kt:89` 的 `NavigationComponent` 是外層 NavHost，`HomeNavHost` 是內層——**巢狀雙 NavHost**。

---

## 5. Timber Log 規範

```kotlin
Timber.d("$functionName: $content")                       // 函式進入點＋關鍵參數
Timber.w("Device not found for mac: $macAddress")         // 非預期但可恢復
Timber.e(exception, "BLE command failed: $functionName")  // 錯誤含堆疊
.catch { Timber.e("$functionName exception $it") }        // Flow 內部用字串格式
```

**Timber 與 `showLog()` 是兩條管道，用途不同**：

| 管道 | 給誰看 | 放什麼 |
|------|--------|--------|
| `Timber` | 開發者（logcat） | 流程追蹤、例外堆疊、內部狀態 |
| `showLog()` | 操作這支 Demo 的人（畫面 log 區） | 指令執行結果、失敗原因摘要 |

指令的最終結果**兩邊都要有**——logcat 會被洗掉，畫面 log 是使用者實際驗收的依據。

---

## 6. 平台注意事項（快速查閱）

| 情境 | 注意點 |
|------|--------|
| Android 12+ BLE 權限 | `BLUETOOTH_SCAN`＋`BLUETOOTH_CONNECT`；本專案在 `MainActivity.onCreate()` 一次性請求 |
| Android 13+ 通知 | `POST_NOTIFICATIONS`（OTA 進度通知需要） |
| minSdk 26 | 可用 `java.time`（`Instant`、`ZoneId`），不需 desugaring 相容寫法 |
| ProGuard / R8 | `release` 開 `minifyEnabled` ＋ `shrinkResources`；Retrofit／Gson 序列化 class 需確認 rules，release 閃退多源於此 |
| OTA | 走 WorkManager（`OtaWorker`），`WorkerManager.enqueueUnique(..., ExistingWorkPolicy.REPLACE)`；長時間背景任務不要塞進 ViewModel |
| 協定世代差異 | 同一功能在 V1/V2/V3 可能封包不同或不支援；先查 `BleDeviceFeature.taskList` 的世代集合與 `BleV2Lock` / `BleV3Lock` |
| 時間處理 | 鎖體時間用 epoch second（`Instant.now().atZone(ZoneId.systemDefault()).toEpochSecond()`） |
