# Sunion BleDemoApp（Android）— AI agent 指引

**本檔是工具中立的共通事實**，任何 AI coding agent（Codex、Antigravity、Claude Code、Gemini CLI…）
都以本檔為入口。Claude Code 另有 [CLAUDE.md](CLAUDE.md) 補充它專屬的制度（skill、派工模式），
該檔以 `@AGENTS.md` 匯入本檔——**共通事實只寫在這裡，不要在兩處各寫一份**。

Sunion 智慧門鎖 **BLE 指令驗證用 Demo App**（不是消費者端 App）。
技術棧：Kotlin（K2）、Jetpack Compose（全 Compose、**Material 2**）、Hilt ＋ KSP、
Coroutines/Flow（app 層）＋ RxJava2（`core_ble_android` 連線層）、Retrofit、WorkManager（OTA）、Timber。
minSdk 26、**無 productFlavors**，只有 `debug` / `release` 兩個 buildType。

> **版本矩陣刻意不寫在本檔**：Gradle／AGP／Kotlin／KSP／Hilt／Compose／compileSdk 的具體版本
> 以 root `build.gradle` 的 `ext` 與 `app/build.gradle` 為唯一真相；升級紀錄與版本對照見
> [docs/REFACTORING_BACKLOG.md](docs/REFACTORING_BACKLOG.md) §B5。
> **為什麼**：寫進文件的版本號每次升級都會過期一次——2026-07-29 升 Android 17（targetSdk 37／AGP 9）時，
> 本段原本列的五個版本號全部作廢。**不要再把版本號寫回這裡。**
> 附帶事實：**annotation processor 已全面是 KSP，kapt 已從專案移除**（看到文件或範例寫 `kapt` 就是過期資訊）。

> **本專案的定位決定規範強度**：它是給韌體／App 工程師逐條打 BLE 指令、看 log 驗證的工具，
> 不是產品。所以「UI 好不好看」「架構純不純」不是重點，**指令正確、log 誠實、加新指令不破壞既有的**才是。
> 下面的規則按這個定位寫，不要拿消費者 App（姊妹專案 iKeyConnect v3）的標準來套。

---

## 0. 鐵律（違反任一條 = 改動直接打回）

1. **禁用 Material 3**（`androidx.compose.material3`）。全專案 Compose Material 2，import 出現 material3 就是錯。
2. 狀態收集一律 `collectAsState()`；**不用** `collectAsStateWithLifecycle()`（全專案僅 3 處收集點，統一即慣例）。
3. `core_ble_android` 是 **Git Submodule**：先進子模組目錄依 §5 規範獨立 commit，主專案再 commit 更新 reference。
4. **新增 BLE 功能一律走 TaskCode 鏈路**（§3），不要在 `HomeScreen` 硬塞按鈕——UI 是清單驅動的。
5. BLE 指令呼叫**不可**寫進 Composable，一律經 `HomeViewModel`。
6. 每個功能函式開頭取 `val functionName`，錯誤與 log 都帶上它——這支 App 的價值就在 log 可追溯。
   既有寫法**兩種都有**（`::xxx.name` 反射式、`"xxx"` 字面字串）；新程式碼優先用 `::xxx.name`
   （改名時會跟著走），改既有函式則跟隨該檔鄰近寫法。
7. log 一律 `Timber`（app 層 73 處，`android.util.Log` 0 處）；面向使用者的執行結果另走 `showLog()` 進畫面 log 區。
8. StateFlow 更新一律 `.update { it.copy(...) }`；清單 emit 前建立新參考（`toMutableList()` / `toList()`），
   原地改 MutableList 不會觸發 recomposition。
9. **未收到明確指示不得 commit**；commit 訊息依 §5。
10. 沒實際執行過的驗證不得聲稱「已驗證」；跑不了就明說「未驗證」。
    本專案**測試基建尚未導入**（僅 4 個 Android 範本測試檔），「測試通過」目前不可能為真。
11. **規則同步——依規範性質判斷，不依檔案**。改到**具四專案共通性的規範**時
    （§0 鐵律、§5 commit 規範、§1 的驗證流程與環境限制——同一台機器共用），
    `Sunion_iKeyConnect_v3_Android`、`Sunion_multiFamily_Android`、`BleDemoApp_Android`、
    `BleMFRDemoApp_Android` 四個專案的對應檔案必須一併修正；共用制度（skill
    `android-dev-governance` 的 `rules/*.md`）更動亦同。
    **只屬單一專案的事實不必同步**：§2 架構、§3 TaskCode 鏈路、§4 類別歸位、§6 語言現況、
    gradlew task 名、submodule、機種清單。

---

## 1. 環境限制與驗證方式（最容易踩雷，動手前先讀）

**編譯前置條件（缺檔會在 configuration 階段就失敗，連 sync 都過不了）**：
`app/build.gradle` 在 configuration 階段讀 repo 外的兩個檔案，都刻意不入庫——**這不是 bug**：

| 檔案 | 提供什麼 | 缺檔後果 |
|------|---------|---------|
| `../Config/BleDemoApp/secure.properties` | `BARCODE_KEY`、`API_GATEWAY_ENDPOINT`、`API_KEY`（`buildConfigField`，在 `defaultConfig` 內，所有 variant 都要） | 建置失敗 |
| `../Config/BleDemoApp/keystore.properties` ＋ 其指向的 keystore | **release 簽章**用的 keystore 路徑、alias 與密碼 | 建置失敗（**含 debug**，理由見下方 ⚠️） |

**簽章分工（2026-07-29 實測 APK 憑證，非推論）**：

| 版本 | 用哪個 keystore | 憑證 DN |
|------|----------------|---------|
| **debug** | AGP 內建的 `~/.android/debug.keystore` | `C=US, O=Android, CN=Android Debug` |
| **release** | `keystore.properties` 指向的 Sunion keystore | `C=TW, O=Sunion, CN=SunionAppTeam` |

**`signingConfig` 雖然宣告在 `defaultConfig`，debug 並不會拿到 Sunion 簽章**——AGP 的 debug
buildType 預設簽章優先。所以「**debug 版不需要 Sunion 的 keystore**」，這點與 BleMFRDemoApp 相同。

⚠️ **但 `keystore.properties` 這個檔案，連跑 debug 都必須存在**：`app/build.gradle:31` 的
`signingConfigs { BleDemoApp { … new FileInputStream(…) } }` 在 **configuration 階段無條件執行**，
缺檔時 `:app:compileDebugKotlin` 直接以 `FileNotFoundException` 失敗（已實測：暫時改名該檔 → build 失敗
並指向 `app/build.gradle` line 31 → 還原後恢復正常）。
**「debug 不需要 release 簽章」與「debug 仍需要這個設定檔」是兩件事，不要混為一談。**
附帶問題：那個 `FileInputStream` 沒關閉，gradle daemon 存活期間會持有檔案 handle——想改名或換
keystore 得先 `--stop`（本專案的 build script 寫法問題，已記入 backlog 技術債）。

**在 Claude Code 桌面版的 shell 內，直接跑 `.\gradlew` 必失敗**：
`java.io.IOException: Unable to establish loopback connection`。根因是 Claude 桌面 App 裝在 MSIX/APPX
容器，子進程繼承容器環境，JVM `Selector.open()` 建自我喚醒管線時 `connect` 被回 EINVAL（JDK-8312215）。
與 gradle daemon、lint 鎖檔、防毒都無關（Kaspersky 曾被誤判過）。
陷阱：`gradlew --version` 會成功（不起 daemon），不能當探測指令。

改用脫離腳本（WMI 讓 WmiPrvSE 當父進程；本機路徑，四個 Sunion 專案共用）：

```
& "$HOME\.claude\tools\gradle-detached.ps1" :app:compileDebugKotlin
& "$HOME\.claude\tools\gradle-detached.ps1" -TimeoutSec 900 :app:assembleRelease
```

- 一次呼叫做完：建可見視窗 → 視窗即時串流 build log → 回傳完整 log 與 `EXITCODE=n`（log 落在 `%TEMP%\gradle-detached-<repo>.log`，UTF-8）
- 視窗跑完 10 秒自動關閉或按任意鍵關閉；`-CloseAfterSec 0` 不留視窗
- 逾時預設 600s（`-TimeoutSec` 可調），逾時或無 log ＝ 視窗沒真的跑起來，重跑一次
- 已驗證無效、不要再試：`dangerouslyDisableSandbox`、`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`、
  `Start-Process`（EPERM）、改用 git bash
- **在其他 agent 工具（Codex／Antigravity）裡**：若該工具不在 MSIX 容器內，直接跑 `.\gradlew` 就行；
  先跑一次真實 build task 探測（不要用 `--version`），失敗訊息含 loopback 才需要上面的腳本

**debug 優先**：平常改程式碼驗證**只跑 debug**；release 編譯只在使用者明確要求驗證 release
或發版前跑（release 冷編譯 1 分鐘以上，R8／proguard 錯誤會拉長來回修正時間）。

**驗證指令（無 flavor）**：編譯檢查 `:app:compileDebugKotlin`；
BLE 模組單獨編譯 `:core_ble_android:compileDebugKotlin`；
出簽章版 `:app:assembleRelease`；
單元測試 `testDebugUnitTest`（測試基建尚未導入，backlog B3；
**B3 完成前，編譯通過就是機械驗證底線**，不要謊稱「測試通過」）。

**release 產物的機械驗證**（升級或改 build script 後才需要，指令已實跑過）：

```
zipalign -c -P 16 4 <apk>       # 16KB page size 對齊，exit 0 為通過
apksigner verify -v <apk>       # 目前只有 v2 簽章（v1/v3 皆 false，已記入 backlog 待確認）
```

**版號**：`app/build.gradle` 的 `ext` 四段版號（`versionMajor/Minor/Patch/Build`），
`versionCode = major*10000 + minor*1000 + patch*100 + build`（**沒有** v3／MFR 那個 `versionMinSdk` 前綴，
是否對齊仍待拍板，見 backlog B5-1a）；發版改 `ext` 即可，不手改 `versionCode`。
APK 輸出名由 `apkBaseName` ＋ `androidComponents.onVariants` 產生：
`BleDemoApp-<buildType>-<versionCode>-<versionName>.apk`。
⚠️ `apkBaseName` **不叫** `archivesBaseName`——後者是 Gradle base plugin 已移除的屬性名，同名會混淆。

**BLE 功能無法靠編譯驗證**：改動指令行為時，編譯通過只代表沒打錯字。真正的驗收是
**實機對真鎖跑一次該 TaskCode，貼出畫面 log 區的輸出**。做不到就明說「未實機驗證」（鐵律 10）。

**驗證工作流**：編譯先行 → 失敗自癒（自行擷取 log 失敗段修正重編，不邊改邊丟回使用者）→ 實機收尾
（需人工上機的項目累積成一份清單一次交付）。細則見 skill `android-dev-governance` `rules/50-android-workflow.md`。

**檔案讀寫用工具內建的檔案編輯功能，不要用 shell**（Windows PowerShell 5.1 編碼陷阱多：
無 BOM 的 `.ps1` 會被當 ANSI codepage 950 讀，CJK 註解會破壞語法）；shell 只用來執行程式（git、gradle）。

⚠️ **同一個編碼陷阱會讓「數行數」默默算錯**（2026-07-29 實測）：對 `HomeViewModel.kt` 跑
`Get-Content x.kt | Measure-Object -Line` 回報 **4191**，實際是 **4496** 行（少 305 行）——PowerShell 5.1 把
無 BOM UTF-8 當 codepage 950 讀，CJK 位元組吃掉了換行。要數行數用
`[System.IO.File]::ReadAllLines($f, [System.Text.Encoding]::UTF8).Count`，
或直接用 Read／Grep 工具看行號。**別拿 `Measure-Object -Line` 的數字寫進文件。**

---

## 2. 架構速覽

```
MainActivity（ComponentActivity，onCreate 一次性請求全部權限）
  └ NavigationComponent（MainActivity.kt:89，外層 NavHost；呼叫點 :53）
      └ HomeNavHost（內層 NavHost）── HomeScreen / ScanQRCodeScreen（Compose Material 2）
        │ collectAsState()
     HomeViewModel     唯一 ViewModel（約 4500 行、注入 30 個依賴）
        │              StateFlow<UiState> + SharedFlow<UiEvent> + StateFlow<MutableList<String>> logList
        │ executeTask() 依 TaskCode 分派
     UseCase           core_ble_android/usecase/（27 個 @Singleton，suspend + Flow）
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
| `usecase/` | 27 | 業務邏輯（`XxxUseCase` ＋ `@Inject constructor`；27 個中 21 個帶 `@Singleton`，**不是全體**——新增時跟隨同類既有檔） |
| `entity/` | 29 | sealed class 資料模型（`DeviceStatus`、`LockConfig`、`User`、`Access`、`Credential`、`BleV2Lock`、`BleV3Lock`…） |
| `exception/` | 2 | 自訂例外（`NotConnectedException`、`LockStatusException`…） |

### BLE 協定世代（決定功能可見於哪些機種）

`core_ble_android/entity/BleDeviceFeature.kt` 的 `modelVersions` 把機種對應到協定世代
（`"1"` / `"2"` / `"3"`），`taskList` 的每個 `Triple` 第三元素就是該功能支援的世代集合。
V2 設定值定義在 `entity/BleV2Lock.kt`、V3 在 `entity/BleV3Lock.kt`。

**新增機種要改兩個地方，只改一個會出錯**：
1. `BleDeviceFeature.modelVersions` 加一行（機種 → 世代）；
2. `HomeViewModel.setModelSupportTaskList()`（`:3305`）——那裡有一段逐機種 `removeIf` 的
   `when(model)` 硬編碼，漏改的話該機種會拿到**未過濾的完整清單**，跑出不支援的指令。

> **本檔所有行號都會漂**（改一次 `HomeViewModel` 就漂一批），只當定位提示用，**以實際檔案為準**；
> 發現不符就用 Grep 重新定位，並回寫本檔。

新增功能則要想清楚它屬於哪幾代（`taskList` 第三元素）。

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

---

## 3. 新增 BLE 功能的標準鏈路（本專案最重要的一條）

依序四步，缺一步功能就不會出現在 UI 或不會被執行：

1. **`core_ble_android/entity/BleDeviceFeature.kt`**：`TaskCode` enum 加一項；`taskList` 加一個
   `Triple(TaskCode.XXX, "顯示名稱", setOf("3"))`（第三元素＝支援的協定世代）。
2. **`core_ble_android/usecase/XxxUseCase.kt`**：實作指令（`@Singleton`、`suspend fun`、
   `setupSingleNotificationThenSendCommand` → `filter` → `take(1)` → `map` → `single()`，範式見 CODE_PATTERNS §2）。
   複雜封包才需要在 `command/` 新增 `XxxCommand`。
3. **`HomeViewModel`**：建構子注入該 UseCase；`executeTask()` 的 `when` 加分支；
   新增 `private suspend fun xxx()` 呼叫 UseCase 並 `showLog(...)` 輸出結果。
4. **UI 不用改**——`HomeScreen` 的下拉選單直接 `uiState.taskList.forEach` 渲染。
   需要輸入參數才動 `showInputDialog()` 的 `when`。

> ⚠️ **加完看不到新功能不代表失敗**：`UiState.taskList` 預設值是 `BleDeviceFeature.initTaskList`
> （只有 Connect / Disconnect）。**連線成功後**才由 `setModelSupportTaskList()` 換成依機種過濾的
> `taskList`（`:3340`），斷線又重設回 `initTaskList`（`:3045`）。驗證新 TaskCode 一定要先連上鎖。

步驟 1、2 在 submodule 內 → **必須獨立 commit**（鐵律 3）。

---

## 4. 類別歸位規則

| 類別性質 | 位置 | 命名 |
|---------|------|------|
| BLE 業務邏輯 | `core_ble_android/usecase/`（submodule） | `Lock{Feature}UseCase` / `{Feature}UseCase` |
| BLE 指令封包類 | `core_ble_android/command/`（submodule） | `{Subject}{Code}Command`（例 `DeviceStatus82Command`） |
| BLE 資料模型 | `core_ble_android/entity/`（submodule） | sealed class ＋ 以功能碼命名的子類（例 `DeviceStatus.EightTwo`） |
| 自訂例外 | `core_ble_android/exception/` | `{Reason}Exception` |
| Demo UI 元件 | `app/ui/component/`（跨畫面重用才放） | 描述性名稱 |
| 遠端 API | `app/data/api/` | `DeviceAPI`（Retrofit interface）／`DeviceApiRepository` |
| Extension functions | `core_ble_android/Extension.kt`（BLE 相關） | top-level function |

導航：`HomeNavHost.kt` 用 `sealed class HomeRoute(val route: String)`，
route 字串**沿用既有的 PascalCase**（`HomeRoute("Home")`、`HomeRoute("Scan")`，`HomeNavHost.kt:24`）——
與姊妹專案 v3 的 snake_case 慣例不同，**以本專案既有寫法為準**。
本專案畫面極少，**不要為了「架構完整」拆出多餘的 NavGraph／Route 檔**。

---

## 5. Git 規範

**未收到明確指示不得 commit。**

- 訊息用**繁體中文（台灣）**，技術名詞／識別字／設定 key／版本號保留英文。
- 格式：`[分類] 繁中摘要` ＋ 空行 ＋ 繁中條列細節；分類前綴 `[Add]`／`[Fix]`／`[Update]`／`[Refactor]`／`[Build]`／`[Docs]`。
- 「已驗證：…」trailer 只在真的跑過 build／test 時才寫。
- 單一主題一個 commit，不相關改動拆開；開新 commit 前先查未推送 commit（`git log @{u}..HEAD`），
  同類型且改到重疊檔案就 fold 進去。
- merge 回主線走 `--no-ff`，merge body 條列被併入的主要修改項目。
- **不 push**，除非使用者明確要求。

本專案特定：

- `core_ble_android` 是 submodule（`.gitmodules` → `Sunion-Tech/core_ble_android`，
  branch `feature/ble_v3_cmd`——**該 submodule 的事實主線不是 `master`**，`origin/master` 停在較舊的
  commit；v3 專案也指向這條分支），同時被 `settings.gradle` 以本地 module `include`。
  動到它 → **先在 submodule 內 commit，主專案再 commit 更新 gitlink**。這是本專案最高頻的錯誤。
- **SDK 發版即更新 demo 的 gitlink（雙向義務，2026-07-29 立規）**：`core_ble_android` 一旦在
  `Sunion_iKeyConnect_v3_Android` 側推進（新指令、bug fix、build 升級），**本 demo 必須同批更新 gitlink 並編譯驗證**；
  反之在本 demo 改了 submodule，也要通知 v3 側同步。
  **為什麼要立這條**：2026-07-28 實查發現本 demo 的 gitlink 落後 v3 兩個 commit
  （`8d4e8b6` kapt→KSP、`de76a71` compileSdk 37＋JVM 21）且無人察覺——demo 的價值就在「先於產品驗證指令」，
  版本一 drift 就等於用舊 SDK 驗新指令，驗過也不算數。檢查方式：
  ```
  git -C core_ble_android fetch origin
  git -C core_ble_android log --oneline HEAD..origin/feature/ble_v3_cmd   # 有輸出＝落後，要更新
  ```
- 根 `.gitignore:94` 有一條 `core_ble_android`——**那是無效且危險的殘留**：gitlink 已在 index
  （mode 160000），git 不忽略已追蹤項目（`git check-ignore core_ble_android` 無輸出即為證）。
  不要因為看到它就以為 submodule 沒被追蹤。**危險在於**：哪天 submodule 被 `git rm --cached`
  移出 index，這條會立刻生效並讓整個目錄靜默消失。已記入 backlog B4。
- 事實主線是 `feature/ble_v3_cmd`，`master` 停滯。分支現況見 backlog。

（Claude Code 的完整 commit 制度見 skill `android-dev-governance` `rules/60-commit.md`；本節前半是其工具中立摘要。）

---

## 6. 語言規則（強制）

- 對話與文件**全程繁體中文（台灣）**，技術名詞保留英文。
- 禁用詞→正確用詞：数组→陣列、线程→執行緒、崩溃→閃退、后台→背景、组件→元件。
- 本專案 `showLog()` 與 Dialog 提示字**混用中英文**（既有現況，例如「請輸入鎖體時間」、
  「OTA將於3秒後背景執行」）；新增時跟隨鄰近既有寫法，**不要順手統一**（記進 backlog）。
- **沒有多語系資源**：`app/src/main/res/` 底下只有 `values/`，無 `values-zh-rTW/`。
  `app/build.gradle:57` 的 `resConfigs "en", "zh-rTW"` 只是過濾第三方 library 帶進來的語系，
  不代表本專案有 zh-rTW 資源。UI 文字多為 hardcode（Demo App 現況，不必修正）。

---

## 7. 進度、決策與過去執行狀況去哪看

| 想知道什麼 | 讀這個 |
|---|---|
| 新增／修改 BLE 指令、UseCase、HomeViewModel 函式；動 Coroutine/Flow | [docs/ai/CODE_PATTERNS.md](docs/ai/CODE_PATTERNS.md)（完整範式，照抄即正確） |
| 技術債（B1–B6）、測試導入、KMP 評估、Git 現況 | [docs/REFACTORING_BACKLOG.md](docs/REFACTORING_BACKLOG.md)（活文件，最新進度看這裡） |
| **接下來要做什麼、為什麼是這個順序** | 同上 §R 演進路線圖（R0–R7）。R0–R3 已完成，下一步是 **R4 跨語言測試向量**（＝B3 階段 1）；KMP 評估要等 B1–B5＋R1–R4 全部完成，判準見 §R.3.1 |
| Android 17（targetSdk 37）升級的實際做法 | **本專案已於 2026-07-29 升完**，實跑結果與踩雷紀錄見 backlog B5-2／B5-4。姊妹專案 `Sunion_iKeyConnect_v3_Android/docs/android-17-migration/android-sdk-upgrade-guide.md` 仍是最完整的通用指南，**下次大版升級（Gradle 10／Android 18）再讀** |
| 接手長期工作的背景與陷阱 | [docs/ai/LETTER_TO_FUTURE_SESSIONS.md](docs/ai/LETTER_TO_FUTURE_SESSIONS.md) |
| 過去實際做了什麼、何時做的 | `git log --oneline`（commit 訊息是繁中且分類前綴齊全，比任何摘要可靠） |

多步驟任務（>3 個子項）開工前先把 checklist 落檔（backlog 表格或工作檔），完成一項勾一項；
順帶發現的問題只記進 backlog 技術債清單，不當場修（唯一例外：不修則本次驗收條件無法通過）。

**更新義務**：完成一段有結論的工作後，把「決策與現況」寫回 `docs/REFACTORING_BACKLOG.md`
或 `docs/ai/LETTER_TO_FUTURE_SESSIONS.md`——那是唯一跨工具共享的進度來源。各工具自己的私有記憶
（Claude Code 的 memory、Antigravity 的 memories）只是快取，**不要當唯一來源**。
