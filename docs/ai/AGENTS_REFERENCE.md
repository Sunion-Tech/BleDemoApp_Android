# AGENTS.md 參考資料（按需讀取）

本檔收錄從 [AGENTS.md](../../AGENTS.md) 搬出的參考段落，內文為原文、章節保留原編號；只有特定任務才需要讀（路由見 AGENTS.md §7），常駐規則仍在 AGENTS.md。

---

## 1. 環境限制與驗證方式（搬出的細節表格）

**簽章分工（2026-07-29 實測 APK 憑證，非推論）**：

| 版本 | 用哪個 keystore | 憑證 DN |
|------|----------------|---------|
| **debug** | AGP 內建的 `~/.android/debug.keystore` | `C=US, O=Android, CN=Android Debug` |
| **release** | `keystore.properties` 指向的 Sunion keystore | `C=TW, O=Sunion, CN=SunionAppTeam` |


---

## 2. 架構速覽

```
MainActivity（ComponentActivity，onCreate 一次性請求全部權限）
  └ NavigationComponent（定義在 `MainActivity.kt`，外層 NavHost；由 `onCreate` 的 `setContent` 呼叫）
      └ HomeNavHost（內層 NavHost）── HomeScreen / ScanQRCodeScreen（Compose Material 2）
        │ collectAsState()
     HomeViewModel     唯一 ViewModel（約 4500 行、注入 30 個依賴）
        │              StateFlow<UiState> + SharedFlow<UiEvent> + StateFlow<MutableList<String>> logList
        │ executeTask() 依 TaskCode 分派
     UseCase           core_ble_android/usecase/（suspend + Flow）
        │
     BleCmdRepository  封包組裝／解析＋AES-ECB 加解密
        │
     ReactiveStatefulConnection  RxJava2（RxAndroidBle）→ rx2 asFlow() 橋接給上層
```

### 模組

| 模組 | package | 檔數（`src/main`） | 性質 |
|------|---------|:---:|------|
| `:app` | `com.sunion.ble.demoapp` | 32 | Demo UI、HomeViewModel、Retrofit API、OTA Worker |
| `:core_ble_android` | `com.sunion.core.ble` | 74 | **Git submodule**，BLE 協定實作（見 §5） |

> 檔數不含測試；兩模組各有 2 個 Android 範本測試檔（共 4 個，見 backlog B4）。

### core_ble_android 目錄

| 目錄 | 檔數 | 放什麼 |
|------|:---:|--------|
| 根層 | 9 | `StatefulConnection` / `ReactiveStatefulConnection`（連線）、`BleCmdRepository`（封包＋加密）、`BleHandShakeUseCase`、`Scheduler`、`CountDownTimer`、`UseCase`、`unless`、`Extension.kt` |
| `command/` | 7 | `BleCommand<I,R>` 實作，指令建立與解析（`XxxCommand`） |
| `usecase/` | 27 | 業務邏輯（`XxxUseCase` ＋ `@Inject constructor`；帶 `@Singleton` 的**不是全體**——新增時跟隨同類既有檔） |
| `entity/` | 29 | sealed class 資料模型（`DeviceStatus`、`LockConfig`、`User`、`Access`、`Credential`、`BleV2Lock`、`BleV3Lock`…） |
| `exception/` | 2 | 自訂例外（`NotConnectedException`、`LockStatusException`…） |

### 我要找…

| 我要找… | 去這裡 |
|---------|--------|
| 某個 BLE 功能的執行邏輯 | `HomeViewModel.kt` 的 `executeTask()` when 分支 → 對應私有 suspend 函式 |
| 功能清單／機種支援矩陣 | `core_ble_android/entity/BleDeviceFeature.kt`（`taskList`、`modelVersions`、`TaskCode`） |
| 指令 byte 怎麼組／怎麼解 | `core_ble_android/BleCmdRepository.kt`（`createCommand` / `resolve` / `encrypt` / `decrypt`） |
| 連線、掃描、通知訂閱 | `core_ble_android/ReactiveStatefulConnection.kt`、`usecase/BleScanUseCase.kt`、`usecase/IncomingSunionBleNotificationUseCase.kt` |
| DI | `app/di/BleModule.kt`（RxBleClient、StatefulConnection binding）、`app/di/AppModule.kt`（OkHttp、Retrofit、DeviceAPI） |
| 遠端 API | `app/data/api/`（`DeviceAPI`、`DeviceApiRepository`、Interceptor） |
| OTA | `app/OtaWorker.kt`、`OtaNotificationManager.kt`、`WorkerManager.kt`、`core_ble_android/usecase/LockOTAUseCase.kt` |
| 共用 UI 元件／主題 | `app/ui/component/`、`app/ui/theme/` |

> `core_ble_android` **沒有自己的 Hilt Module**：全部靠 `@Singleton` ＋ `@Inject constructor` 由消費端解析。
> 新增 UseCase 照這個寫法即可，不要新開 Module。

> 「BLE 協定世代」子節（含新增機種要改兩處的規則）仍在 [AGENTS.md](../../AGENTS.md) §2。
