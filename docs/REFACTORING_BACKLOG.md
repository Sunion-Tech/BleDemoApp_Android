# 重構待辦清單（Refactoring Backlog）

> 🔄 **這是活文件，隨開發持續更新。**
> 長期規則見 [../CLAUDE.md](../CLAUDE.md)；程式碼範式見 [ai/CODE_PATTERNS.md](ai/CODE_PATTERNS.md)。
>
> 進度標記：⬜ 規劃中 ｜ 🟦 進行中 ｜ ✅ 完成
> 完成項目請於該列補上完成日期與對應 commit。
>
> **當前主線看 [§R — BLE SDK 演進路線圖](#r--ble-sdk-演進路線圖漸進路徑)**：B1–B6 是「有什麼債」，
> R0–R7 是「先做哪個、憑什麼順序」。兩者交叉引用，不要各做各的。

最後更新：2026-07-29（Android 17 升級 **含 release 實機驗收全部結案**；下一步是 R3.5，見 §R.3.2。剩餘待拍板：B5-1 版號）

---

## 先講清楚：這是 Demo App，不是產品

`BleDemoApp_Android` 的用途是**逐條驗證 BLE 指令**。因此本清單的優先度判準與消費者端 App
（姊妹專案 iKeyConnect v3）不同：

- **值得做**：讓「新增／修改指令」更不容易出錯、讓驗證結果更可信（B2、B3）。
- **不值得做**：為了架構潔癖而重寫 UI、抽象層、設計 token。
- **B1（God ViewModel）刻意排在低優先**：約 4500 行確實難讀，但它是一支扁平的指令分派表，
  改動風險低、閱讀成本主要落在 AI 而非人。**沒有實際痛點前不要動它**——
  拆分本身會製造大量 diff 與回歸風險，而這支 App 沒有測試作為安全網。

---

## 重構主軸 B1–B6

| 編號 | 主題 | 優先 | 狀態 |
|------|------|:---:|------|
| **B1** | 拆分 `HomeViewModel`（約 4500 行 God ViewModel） | 🟢 低（見上） | ⬜ 規劃中 |
| **B2** | 消除 `!!` Force Unwrap（43 行／44 次） | 🔴 高 | ⬜ 規劃中 |
| **B3** | 導入自動化測試（BLE 封包層優先） | 🔴 高 | ⬜ 規劃中 |
| **B4** | 清理誤導性設定與 Dead Code | 🟡 中 | ⬜ 規劃中 |
| **B5** | 同步版號 ＋ 升級 Android 17（targetSdk 37），對齊姊妹專案 | 🔴 高 | ✅ **2026-07-29 結案**（B5-2 技術升級＋B5-4 真機驗收全通過）；唯一遺留是 **B5-1 版號待拍板** |
| **B6** | Kotlin Multiplatform 雙平台化 | ⚪ 方向已定，時點未定 | ⬜ 方案 B 已定（見 §R.1）；**開工時點：B1–B5 與 R1–R4 全部完成後的檢查點**（見 §R.3.1） |

> **B3 與 B6 共用同一個第一步**：把純邏輯（封包組裝／解析／entity）與平台相依（BLE stack、DI、
> crypto、Android API）隔離開。做完這一步，單元測試才好寫，KMP 才有 `commonMain` 可放。
> 如果兩件事都想做，**先做這一步，不要各做各的**。

---

### B1 — 拆分 HomeViewModel ⬜ 規劃中（低優先，勿主動發起）

- **現況：** `app/.../HomeViewModel.kt` **4496 行**（2026-07-29 實測，與建檔時相同——
  Android 17 升級沒有動這支檔案）、建構子注入 30 個依賴、未實作 `onCleared()`。
- **真正的風險點不是行數，是這兩個：**
  1. **未實作 `onCleared()`** → 5 個 Job 追蹤變數（`_bleConnectionStateListener`、
     `_bleSunionBleNotificationListener`、`scanWifiJob`、`collectWifiListJob`、`connectToWifiJob`；
     `:96,98,108,109,110`）在 VM 銷毀時不會被取消。Demo App 單 Activity 單 VM 影響有限，
     但**這是實質 bug，不是風格問題**。
  2. 30 個依賴讓 Hilt graph 一有問題就整支 VM 掛掉。
- **建議做法（若要動）：** 先補 `onCleared()` → `cancelJobs()`（小、獨立、立即有價值），
  拆分本身留待有測試安全網（B3）之後。
- **驗收：** `onCleared()` 實作並取消全部 5 個 Job；拆分（若執行）後注入依賴 ≤ 10。

> **可先做的一小步**：只補 `onCleared()`，不拆。這一步跟 B1 其餘部分可完全解耦。

### B2 — 消除 Force Unwrap ⬜ 規劃中

- **問題：** `HomeViewModel.kt` 現存 `!!` **43 行／44 次**（例如
  `startBleScan(currentQrCodeContent!!, currentProductionGetResponse!!)`），閃退風險。
- **目標：** 改用 `?: return`／`?: run { showLog(...); return }`／明確判斷＋自訂例外，並補 `Timber.w/e`。
- **注意：** 這支 App 的例外會被 `runWithLoading` 接住印成 log，**閃退不明顯但錯誤會被靜默吞掉**——
  所以改寫時務必補上 `showLog()`，否則只是把閃退換成「什麼都沒發生」。
- **做法：** 整批處理，不零散改動（避免與其他改動衝突）。
- **驗收：** `grep -c '!!' app/src/main/java/com/sunion/ble/demoapp/HomeViewModel.kt` 歸零
  （資料實體內部除外，需個案確認）。

### B3 — 導入自動化測試 ⬜ 規劃中

- **現況：** 全專案無有效測試，僅 4 個 Android 範本檔（見 B4）。實質覆蓋率 0%。
  這也是 CLAUDE.md 鐵律 10 說「測試通過目前不可能為真」的原因。
- **本專案的高 CP 值切入點是 BLE 封包層**：`BleCmdRepository.createCommand()` / `resolve()` /
  `encrypt()` / `decrypt()` 是純 `ByteArray` 輸入輸出，**不需實機、不需 mock BLE stack**，
  最能直接防止「改指令改壞既有指令」這個本專案最痛的回歸。

#### 先決步驟

1. ⬜ 移除 4 個範本測試檔，建立正式測試目錄結構
2. ⬜ 補齊測試相依：`mockk`、`turbine`、`kotlinx-coroutines-test`

#### 導入順序（由高 CP 值到低）

| 階段 | 測試目標 | 工具 / 做法 | 狀態 |
|:---:|---------|------------|:---:|
| 1 | BLE 封包組裝／解析／加解密 | `core_ble_android` 純 byte array 輸入輸出比對，JUnit，無需實機。⚠️ **測資寫成 §R 階段 R4 的跨語言 JSON 向量格式（bytes → 期望值），不要寫成 Android 專屬 hardcode 測資**——否則 KMP／iOS 共用時要重寫一次 | ⬜ |
| 2 | UseCase（mock `StatefulConnection`，驗證 filter/take/map 鏈路） | JUnit + MockK + `turbine` | ⬜ |
| 3 | `HomeViewModel` 狀態轉換（`runWithLoading` 的 loading/例外分支） | `turbine` + `kotlinx-coroutines-test`（`runTest`、`StandardTestDispatcher`） | ⬜ |
| 4 | Compose UI | ❌ **不做**——Demo App UI 是清單驅動的，測試成本遠高於價值 | — |

### B4 — 清理誤導性設定與 Dead Code ⬜ 規劃中

| 項目 | 位置 | 說明 | 狀態 |
|------|------|------|:---:|
| `.gitignore` 的 `core_ble_android` 條目 | 根 `.gitignore:94` | **無效且危險**：gitlink 已在 index（mode 160000），git 不忽略已追蹤項目（`git check-ignore core_ble_android` 無輸出、`--no-index` 才 match）。除了誤導，真正的風險是——若 submodule 哪天被 `git rm --cached` 移出 index，這條會立刻生效並讓整個目錄靜默消失。 | ⬜ |
| Android 範本測試（app） | `app/src/test/.../ExampleUnitTest.kt`、`app/src/androidTest/.../ExampleInstrumentedTest.kt` | 與 B3 同步移除 | ⬜ |
| Android 範本測試（BLE） | `core_ble_android/src/test/…`、`src/androidTest/…` | 同上，注意 submodule 需獨立 commit | ⬜ |

### B5 — 同步版號 ＋ 升級 Android 17（targetSdk 37）⬜ 規劃中

> **首要參考**：姊妹專案已完成同一升級並留下實戰紀錄——
> `Sunion_iKeyConnect_v3_Android/docs/android-17-migration/android-sdk-upgrade-guide.md`
> （版本矩陣、五階段順序、真實錯誤訊息與解法、targetSdk 37 行為變更全 15 項）。
> **本專案升級前務必先讀那份，不要重新踩一次雷。**

#### B5-1 版號同步

「版號」有兩層，**兩層都要處理，但做法不同**：

| 層 | BleDemoApp | v3 | 狀態 |
|----|-----------|-----|------|
| **相依套件版本矩陣** | 升級前 Kotlin 1.9.24 / AGP 8.6.1 / Hilt 2.52 / Compose 1.7.0 / compileSdk 35 → **現已對齊 v3** | Kotlin 2.3.10 / AGP 9.2.0 / Hilt 2.59.2 / Compose 1.9.0 / compileSdk 37 | ✅ **2026-07-29 完成**（B5-2）。往後版本以 `build.gradle` 的 `ext` 為唯一真相，**不要再抄進文件** |
| **App 自身版號** | `1.0.0.0`（`versionMajor..Build` 在 `app/build.gradle` 的 `ext`） | `2.2.9.1` | ⚠️ **不該對齊**——兩者是不同 App，各自遞增。但 `versionCode` **公式**可以對齊（B5-1a 待拍板） |

`versionCode` 公式差異（實測）：

```groovy
// BleDemoApp（app/build.gradle:42）
versionCode versionMajor * 10000 + versionMinor * 1000 + versionPatch * 100 + versionBuild

// v3（app/build.gradle:164）—— 多了 minSdk 前綴
versionCode versionMinSdk * 10000000 + versionMajor * 10000 + ...
```

> ⚠️ **B5-1 兩項都還沒動**（2026-07-29）：B5-2 技術升級已完成，但版號公式與遞增涉及產品決策，
> 兩項都標「需你拍板」，未拍板前**刻意不改**——改了再改回會讓 `versionCode` 曲線變髒。
> 目前仍是 `1.0.0.0` / `versionCode 10000`（release APK 檔名即 `BleDemoApp-release-10000-1.0.0.0.apk`）。

- ⬜ **B5-1a** 決定是否採用 v3 的 `versionMinSdk` 前綴公式。
  **建議：不採用。** 那個前綴是為了多 ABI／多 minSdk 併存上架設計的，Demo App 不上架、不需要。
  除非要送 Play Console internal testing 才有意義。**此項需你拍板。**
- ⬜ **B5-1b** 升級後把 `versionMinor` 或 `versionBuild` 遞增並打 tag（現有 tag 最新為 `V.3.0.0`，
  與 `versionName 1.0.0.0` 已經對不上——tag 用的是 BLE 協定版本、versionName 用的是 App 版本，
  **這兩套編號體系混用是既有現況**，升級時順便確認要不要統一）。

#### B5-2 Android 17 升級（照 v3 五階段順序）

> ✅ **五個階段全部完成**（2026-07-29）：①–④ 本機機械驗證，⑤ 由使用者實機執行 release 版驗收通過。

| 階段 | 內容 | 本專案特有注意點 | 狀態 |
|:---:|------|-----------------|:---:|
| ① | Gradle wrapper ＋ AGP 9.2.0 ＋ Kotlin 2.3.10／KSP 2.3.9／Hilt 2.59.2 ＋ plugin 宣告改寫 | root `build.gradle` 移除 `org.jetbrains.kotlin.android`、改宣告 `kotlin.plugin.compose`；移除 `kotlinCompilerExtensionVersion` | ✅ |
| ② | compileSdk/targetSdk 37 ＋ JDK 21 | submodule 側**已由 v3 做完**，本專案只需 R0 更新 gitlink（原本預期要自己改，實際白拿） | ✅ |
| ③ | Build script 重構（Variant API V2、Configuration Cache） | `applicationVariants.all` 改 `androidComponents.onVariants`；APK 檔名產出正確：`BleDemoApp-release-10000-1.0.0.0.apk` | ✅ |
| ④ | Release 打包驗證 | `:app:assembleRelease` 綠（R8 Full Mode ＋ `shrinkResources` ＋ `lintVitalRelease` 全過）；`zipalign -c -P 16` 通過、apksigner v2 驗章通過 | ✅ |
| ⑤ | Release 真機執行 | **本專案的 R8 高風險點：`OtaWorker`（WorkManager 反射建構子，v3 §6.2）**——實機驗收含 OTA 流程，**未出現 R8 Full Mode 誤殺**（work-runtime-ktx 2.11.2 自帶的 consumer-rules 生效） | ✅ 使用者實機驗收 |

**實跑註記（2026-07-29，WMI 脫離法）**

- **唯一的實質 breakage 是 Compose icons**：`androidx.compose.material:material` 自 1.9.0 起不再傳遞
  `material-icons-core`，`HomeScreen.kt` 的 `Icons.*` 全數 unresolved（連帶 `:379-384` 的 `Triple`
  解構推導失敗，看起來像另一個 bug，實際是同一個根因）。補宣告 `material-icons-core:1.7.8` 即解決
  ——**該 artifact 停版於 1.7.8，不要「順手對齊」到 `compose_ui_version`**。
- 其餘 v3／MFR 踩過的雷本專案**都沒踩到**：無 Room（跳過 KSP2 衝突）、無 Firebase／AWS（跳過 R8 誤殺
  registrar 與 shrinker 誤殺 raw 資源）、無 productFlavors（跳過 `resValues`／多 flavor 驗證）；
  `AndroidManifest` 的 `WorkManagerInitializer` **早已有** `tools:node="remove"`（MFR 是升級時才發現缺）。
- 另外順手處理掉的地雷：`gradle.properties` 的 `-XX:MaxPermSize`（JDK 21 不接受）、
  `android.enableJetifier`（AGP 9 已移除）、ext `archivesBaseName` 與 Gradle base plugin 已移除的
  同名屬性撞名（改 `apkBaseName`）、兩個重複的 `buildFeatures` 區塊。
- Kotlin 2.3.10（K2）**零語意錯誤**，只剩既有警告（`when` 冗餘 `else`、RxBle `setLogLevel` deprecated 等），
  未新增警告。

**本專案相對 v3 的差異（會讓升級簡單很多）**：

- ✅ 無 productFlavors → 跳過 v3 §3.5（`resValues`）、§5.5（多 flavor 動態資源）、
  §8 的「全部 flavor 都要驗」
- ✅ 無 AWS SDK / Firebase / MQTT / Room → 跳過 v3 §4.1（Room+KSP2）、§6.1（awsconfiguration 被 shrink）、
  §6.4（Firebase ComponentRegistrar 被砍）——**這三個是 v3 最痛的三項**
- ⚠️ **仍會踩到的**：§5.1 Variant API V2（本專案有客製 `outputFileName`）、
  §6.2 WorkManager R8 Full Mode（OTA 用 WorkManager ＋ hilt-work）、§4.4 Accompanist 舊庫（權限流程）

#### B5-3 targetSdk 37 行為變更（只列與本專案相關的）

| 相關 | 變更 | 本專案檢查點 | 狀態 |
|:--:|------|-------------|:---:|
| 🔴 | **ECH（Encrypted Client Hello）預設開啟** | `DeviceAPI` 的 Retrofit 連線要真機實連一次 | ✅ 實機通過 |
| 🔴 | **Certificate Transparency 預設開啟** | 同上；若後端用非公開 CA 會被擋 | ✅ 實機通過 |
| 🟡 | **`System.load()` native lib 需唯讀** | `rxandroidble`、`zxing`（QR 掃描）帶 `.so`；release 打包時 `libandroidx.graphics.path.so` 有 strip 警告（僅無法移除符號，非錯誤） | ✅ 實機通過（release 版啟動與 QR 掃描皆正常） |
| 🟡 | **大螢幕強制忽略 orientation** | `AndroidManifest.xml:31` 有 `android:screenOrientation="portrait"`（`MainActivity`）→ **sw≥600dp 裝置上會被忽略**。Demo 以手機使用為主，**不改程式**，僅記錄；若要在平板驗指令需知道畫面會轉 | ✅ 已查證 |
| ⚪ | BLE RFCOMM `read()` 回傳 -1 | **不適用**——本專案用 GATT（RxAndroidBle），非 Bluetooth Classic | ✅ |
| ⚪ | 強制 `ACCESS_LOCAL_NETWORK` 權限 | **不適用**——無 mDNS／NsdManager／本地 socket（BLE ＋ HTTPS API） | ✅ 已查證 |
| — | **16KB page size 對齊** | `zipalign -c -P 16 4` 對 release APK 通過（exit 0） | ✅ 2026-07-29 |

#### B5-4 驗收（2026-07-29 更新）

```
✅ :core_ble_android:compileDebugKotlin 通過（隨 :app 一併建置）
✅ :app:compileDebugKotlin 通過
✅ release APK 編譯通過 + 16KB 對齊（zipalign -c -P 16 4）+ 簽章（apksigner v2）
✅ submodule gitlink 停在 `de76a71`（＝v3 現在指向的 commit，**submodule 本身零改動**）
✅ .gitignore 補 .kotlin/（Kotlin 2.x 工作目錄）
✅ 真機安裝 release 版（不是 debug）
✅ 核心流程回歸：BLE 掃描 → 連線 → 至少 5 個不同 TaskCode → 斷線
✅ OTA 流程回歸（release 版，R8 Full Mode 只在這時炸）
✅ QR Code 掃描回歸（zxing + 相機權限 + Accompanist）
✅ Retrofit 對後端實連一次（B5-3 的 ECH／Certificate Transparency 兩項）
```

> 上半段是本機機械驗證（實跑，非推測）。
> **下半段的真機項目由使用者於 2026-07-29 實機執行 release 版並回報全部通過**——
> 這是本專案第一次有完整的實機驗收，**B5／R1 至此結案**。
> **例外行為的實機抽驗不在這張清單**——那批改動已抽到 **R3.5**，驗收項目寫在 §R.3.2，
> 屆時要再上機一次（連線失敗、token 被拒、30 秒逾時三條路徑）。

---

### B6 — Kotlin Multiplatform 雙平台化 ⬜ 評估中（未決策）

> **先講結論**：`core_ble_android` **有相當比例可以直接共用**，但**連線層必須整層換掉**。
> 這不是「加個 `commonMain` 就好」的改造，是**協定層與傳輸層的正式分家**。
> 下方逐條列出必要調整，並標示每項是「無腦搬」還是「要重寫」。

#### B6-0 先算清楚可共用比例（本機實測，2026-07-27）

`core_ble_android/src/main/java/com/sunion/core/ble/` 共 **74** 個 .kt：

| 類別 | 檔數 | KMP 處置 |
|------|:---:|---------|
| 完全無平台相依（無 `import android`、無 RxJava、無 Hilt、無 `javax.crypto`） | **36** | ✅ 直接進 `commonMain` |
| 含 `import android` | 9 | ⚠️ 要拆或 expect/actual |
| 含 RxJava2 / RxBle | 8 | ❌ 整層重寫 |
| 含 `javax.inject` / dagger 註解 | 33 | ⚠️ 註解要換（改動機械但量大） |
| 用 `javax.crypto` / `java.util` / `java.time` | 7 | ⚠️ 要換 KMP 對應品 |

**最有價值的發現**：`entity/` 29 個檔中 **28 個完全乾淨**。
那是全部的封包資料模型（`DeviceStatus`、`LockConfig`、`User`、`Access`、`Credential`、
`BleV2Lock`、`BleV3Lock`…），也是 iOS 端最需要、最容易寫錯的部分。
**即使不做完整 KMP，只把 `entity/` + 封包解析抽成共用模組，就已經拿到八成價值。**

#### B6-1 必要的重構調整（逐條）

**A. 模組與 source set 重組**

1. ⬜ `core_ble_android` 從 `com.android.library` 改為 `kotlin("multiplatform")` plugin，
   建立 `commonMain` / `androidMain` / `iosMain` 三個 source set。
2. ⬜ **切成兩個模組**（這是整個 B6 的關鍵決策）：
   - `ble-protocol`（純 KMP）：封包組裝／解析／加解密／entity／指令定義。**無 I/O、無平台 API。**
   - `ble-transport`（platform-specific）：實際的 GATT 連線、掃描、notification 訂閱。
   - 上層依賴 `ble-protocol` 的介面，由 `ble-transport` 提供實作。
   > 不切這一刀，`commonMain` 會被連線層污染，KMP 就退化成「兩份程式碼放同一個資料夾」。
3. ⬜ Gradle 從 Groovy DSL 轉 Kotlin DSL（`build.gradle` → `build.gradle.kts`）。
   KMP 的 Groovy DSL 支援差、範例幾乎全是 kts。**建議與 B5（AGP 9 升級）同批做**，
   否則等於改兩次 build script。

**B. 相依套件替換（每一項都是「換掉」，不是「包一層」）**

| # | 現況 | KMP 替代 | 難度 | 說明 |
|:-:|------|---------|:---:|------|
| 4 | **RxAndroidBle**（8 檔） | **Kable**（JuulLabs，Android/iOS/JS）或自寫 `expect/actual` | 🔴 高 | `ReactiveStatefulConnection` 整支重寫。這是最大的一塊工，也是唯一無法迴避的 |
| 5 | **RxJava2 `Observable`／`Disposable`** | Coroutines `Flow` | 🔴 高 | 與 #4 綁在一起。**好消息**：UseCase 層已經是 Flow，只有連線層是 Rx，橋接點 `rx2.asFlow()` 拿掉即可 |
| 6 | **Hilt / `javax.inject`**（33 檔） | **Koin**（KMP 原生）或建構子手動注入 | 🟡 中 | 改動機械但檔數多。Demo App 依賴圖淺，**其實手動注入就夠**——27 個 UseCase 只是 `@Inject constructor`，沒有複雜 scope |
| 7 | **`javax.crypto` AES-ECB**（`BleCmdRepository`、`LockQRCodeUseCase`） | `expect/actual` 包 platform crypto，或用 KMP crypto 庫 | 🟡 中 | iOS 端用 CommonCrypto。**AES-ECB 是簡單模式，自己 expect/actual 兩份實作最省事** |
| 8 | **Timber** | **Kermit**（Touchlab，KMP logging） | 🟢 低 | 純機械替換，API 幾乎一對一 |
| 9 | **`androidx.lifecycle.LiveData`**（`StatefulConnection.kt:29`） | 直接刪除 | 🟢 低 | 該處已有 `SharedFlow` 版本並行，LiveData 是舊 API 殘留。**刪掉即可，順手還清了技術債** |
| 10 | **`android.os.CountDownTimer`** | Coroutines `delay()` 自寫 | 🟢 低 | 根層 `CountDownTimer.kt`，改寫約 20 行 |
| 11 | **`android.util.Base64`** | `kotlin.io.encoding.Base64`（stdlib，已穩定） | 🟢 低 | 一處 |
| 12 | **`java.time`**（`Instant`、`ZoneId`） | `kotlinx-datetime` | 🟢 低 | 用量少 |
| 13 | **`android.graphics.Bitmap`**（QR Code 產生） | 留在 `androidMain` | 🟢 低 | QR 產生本來就該是平台端的事，不要硬塞進 common |
| 14 | **Retrofit**（app 層 `DeviceAPI`） | **Ktor Client** | 🟡 中 | 只有共用遠端 API 才需要；Demo App 的 API 很薄，**建議先不共用** |
| 15 | **WorkManager**（OTA） | 各平台各自實作（iOS 用 `BGTaskScheduler`） | 🟡 中 | 背景任務無法共用，只共用 OTA 的**封包協定**部分 |

**C. 程式碼層面的調整**

16. ⬜ **`BleCmdRepository` 要拆兩半**：純封包組裝／解析（common）vs 加解密（expect/actual）。
    目前兩者混在同一個 class（`createCommand` 內直接呼叫 `encrypt`）。
17. ⬜ **移除 `StatefulConnection` 介面裡的 Android 型別**：`LiveData`（#9）、`Context`、
    `BluetoothManager`。介面只留純 Kotlin 型別（`Flow<ByteArray>`、`String`、`ByteArray`）。
18. ⬜ **`BleScanUseCase` 整支移出 common**：掃描 API 平台差異最大（Android 的 `ScanFilter`
    vs iOS 的 `CBCentralManager`），且 iOS **拿不到 MAC address**（只有 UUID）——
    ⚠️ **這會影響現有設計**：本專案用 MAC 當裝置識別（`currentConnectMacAddress`），
    iOS 端必須改用 CBPeripheral identifier。**這是設計層問題，不是搬程式碼能解決的。**
19. ⬜ 例外型別（`exception/` 2 檔）確認無 Android 相依後移入 common。
20. ⬜ `Extension.kt` 拆分：純 byte/hex 操作進 common，Android 專屬（`Context` extension）留 androidMain。

**D. 測試（與 B3 合流）**

21. ⬜ 測試改放 `commonTest`，用 `kotlin.test` 而非 JUnit 專屬 API。
22. ⬜ **B3 階段 1（BLE 封包測試）直接寫成 `commonTest`** —— 一份測試同時驗證兩平台的協定實作，
    這是 KMP 對本專案最大的實質收益：**再也不會有「Android 解得出、iOS 解不出」的封包 bug。**

#### B6-2 建議的分期（不要一次做完）

| 期 | 範圍 | 產出 | 風險 |
|:--:|------|------|:---:|
| **第 1 期** | 只抽 `entity/`（28 檔乾淨）＋ 封包解析成 `ble-protocol` KMP 模組，Android 端照舊使用 | iOS 拿到型別安全的協定層 | 🟢 低——Android 端行為不變 |
| **第 2 期** | 加解密 expect/actual、Timber→Kermit、LiveData/CountDownTimer/Base64 清理 | `ble-protocol` 完整可用 | 🟢 低 |
| **第 3 期** | DI 換 Koin 或手動注入 | 移除 Hilt 對 common 的污染 | 🟡 中 |
| **第 4 期** | 連線層換 Kable，`ble-transport` 雙平台 | 真正的雙平台 BLE | 🔴 高——**這一期才是真的重寫** |

**第 1、2 期做完就能停**。那時 iOS 端已能共用協定與資料模型，連線層各寫各的——
對 Sunion 這種「協定複雜、連線相對標準」的產品，**這個停損點的 CP 值最高**。

#### B6-3 決策前要先回答的問題（未答不要開工）

- ⬜ iOS 端目前是什麼狀態？已有原生 App 還是從零開始？（決定是「共用給既有 iOS」還是「一起重寫」）
- ⬜ 這支 **Demo App** 要不要雙平台，還是只有**產品 App**（iKeyConnect）需要？
  Demo App 的價值在「工程師手上有工具」，若 iOS 工程師也需要，才有動機。
- ⬜ `core_ble_android` 是 submodule 且被 v3 共用——**改 KMP 會同時衝擊 v3**。
  要不要開 KMP 分支平行維護？（否則 v3 的 Android 升級與 KMP 改造會打架）
- ⬜ 團隊有 iOS/KMP 經驗嗎？第 4 期（連線層 `expect`/`actual`）沒有 iOS BLE 經驗會很痛。

> ⚠️ **B6 的方向已被 §R 收斂**（2026-07-28）：採**方案 B（只共用協議層）**、**不採 Kable**、
> **不做完整 KMP**。所以上表 #4「連線層換 Kable」改為「連線層各平台 `expect`/`actual` 原生」，
> B6-2 第 4 期同理。B6-0 的可共用比例統計與 B6-1 的逐條清單仍然有效，**那是 §R 階段 R6 的施工圖**。

---

## R — BLE SDK 演進路線圖（漸進路徑）

> 來源：`BleMFRDemoApp_Android/docs/REFACTORING_BACKLOG.md` §5（2026-07-27 三專案 BLE SDK 綜合評估）。
> **公司策略**：做一套 BLE SDK 讓 Android／iOS 共用，未來只維護一套。
> **本節是把該路線圖映射到本專案**——MFR demo 對口 `core_ble_mfr_android`／`Sunion_multiFamily_Android`；
> **本專案對口 `core_ble_android`／`Sunion_iKeyConnect_v3_Android`**，同一條路線但起點不同（見 R0/R1）。

### R.1 已定的方向決策（沿用，不重新討論）

- **採方案 B（協議層共用）**：協議層（加密握手／封包編解碼／`entity`）走 KMP `commonMain`；
  BLE 連線層（掃描／GATT vs CoreBluetooth）各平台 `expect`/`actual` 原生。
- **不採 Kable**：不暴露 `BluetoothGatt`、無 custom operation 出口，現行 `BluetoothGatt.refresh()`
  反射無處安放；且仍 0.x。
- **不採完整 KMP（方案 A）**：連線層本就得兩套 `actual`，全塞 KMP 收益邊際遞減。
- **KMP 決策時點：B1–B5 與 R1–R4 全部完成後的檢查點**（2026-07-28 決定；比 MFR 的「R5 後」更保守）。
  在此之前不預先投入 KMP 改寫、也不為了 KMP 而改既有程式。

### R.2 本專案的起點差異（決定 R0–R2 的實際內容）

| 事實（2026-07-28 本機 git／程式實查） | 對路線圖的影響 |
|---|---|
| ~~本專案 gitlink 指向 `36d515c`~~ → **已更新至 `de76a71`（R0），並刻意停在這裡** | ✅ R0 完成。落後的兩個 commit 正是 `8d4e8b6` kapt→KSP、`de76a71` compileSdk/targetSdk 37＋JVM 21 → submodule 側的 Android 17 準備工作由 v3 做完，本專案更新 gitlink 就白拿。**`de76a71` 正是 v3 目前指向的 commit，所以本次升級對姊妹專案零影響**（例外繼承鏈的改動已抽到 R3.5） |
| ~~`.gitmodules` 寫 `branch = master`~~ → **已改 `feature/ble_v3_cmd`** | ✅ G5 完成（隨 R0）。submodule 也從 detached HEAD 切回 `feature/ble_v3_cmd` 分支，之後 commit 不會落在無分支狀態 |
| app 層只有 `di/BleModule.kt`、`di/AppModule.kt` 兩檔 import RxJava2／RxBle | **R4 解耦對 app 層衝擊極小**，成本主要落在 submodule 內部 |
| 自訂例外**全部繼承 `Throwable`**（`exception/bleException.kt:3,5,12,14`、`LockStatusException.kt:3`）——**仍是現況** | 比 MFR 起點更差：`catch (e: Exception)` **抓不到**這些例外，會逃到 coroutine 頂層閃退。改繼承 `Exception` 是**行為變更**，不是純型別調整 → 整批排入 **R3.5**（見 §R.3.2） |
| 寬泛 `catch (Exception)` 未 rethrow `CancellationException`（`HomeViewModel.kt:3202,3275,3558,4434`、`OtaWorker.kt:112,274`）——**仍是現況** | 與上一列綁在一起，**不能單獨補**（會把連線逾時變成靜默）→ **R3.5** |
| `HiltApplication.kt:24` 用舊寫法 `override fun getWorkManagerConfiguration()` | R1 升級時要改 `override val workManagerConfiguration`（MFR 已踩過） |
| `StatefulConnection.kt` 對外暴露 `CompositeDisposable`(:19)、`Disposable`(:23)、`PublishSubject`(:25)、`LiveData`(:27,29,35)、`Observable<RxBleConnection>`(:37,43,49,52)、`RxBleConnection`(:39) | R4 的精確施工清單 |
| `BleCmdRepository`：`cmd()` 內直接 `encrypt`（:273,278,282）、`resolve` 直接 `decrypt`（:538）、`Cipher.getInstance`（:172,188） | R6 拆「純封包 vs 加解密」的切點（＝B6-1 #16） |

### R.3 階段表（依相依順序；R0–R5 無論走不走 KMP 都有獨立價值）

| 階段 | 狀態 | 動作 | 產出／驗收 | 關聯 |
|:---:|:---:|------|-----------|------|
| **R0** | ✅ | **追上主專案（v3）的 submodule 版本**：gitlink `36d515c` → `de76a71`；併同修正 `.gitmodules` 的 `branch = master` 失準（→ `feature/ble_v3_cmd`） | 2026-07-29 完成（併入 `[Build]` 升級 commit）。submodule 本身零改動 | B5、G4、**G5 ✅** |
| **R1** | ✅ | **app 層 Android 17（API 37）升級** | 2026-07-29 **完全結案**：`:app:compileDebugKotlin`／`:app:assembleRelease` 綠、16KB 對齊、apksigner 驗章，**且 release 版已實機驗收通過**（使用者執行，B5-4 全項 ✅） | **B5**（施工細節在 B5） |
| **R2** | ✅ | **修正升級後的問題——只做編譯必需的**：`HiltApplication` 的 `getWorkManagerConfiguration()` → `override val workManagerConfiguration`（WorkManager 2.9 起全面 Kotlin 化，不改編不過）。**這是整個升級唯一必需的程式碼改動** | 2026-07-29 完成（併入升級 commit）。編譯與 release 皆綠。**例外語意／取消傳播的修正已抽出到 R3.5**，submodule 因此維持在 `de76a71`——與 v3 同一個 commit，**姊妹專案不需要更新 gitlink** | **R3.5** |
| **R3** | ✅ | **建立「SDK 發版即更新 demo gitlink」規則** | 2026-07-29 寫進 [AGENTS.md](../AGENTS.md) §5，含雙向義務與 `git log HEAD..origin/feature/ble_v3_cmd` 檢查指令。**姊妹專案 BleMFRDemoApp 的同一條規則寫在它的 AGENTS.md §5「【發版必檢】」** | G4 |
| **R3.5** | ⬜ | **例外語意與取消傳播修正（R4 之前做，五項必須整批一起）**——內容與理由見 **§R.3.2** | 修完的驗收是**實機**：連線失敗、token 被拒、30 秒逾時三條路徑的畫面 log 都正確且按鈕可恢復 | B2（`!!` 與例外處理同批看）、CODE_PATTERNS §3.2 |
| **R4** | ⬜ | **協議層導入跨語言測試向量（JSON：bytes → 期望值）＋補單元測試**（照 iOS `BleTransport`/`MockBleTransport` 模式） | 一份向量 Android／iOS 各寫 runner 跑，協議一致性由測試保證 | **B3 階段 1 直接寫成這個形式**（別先寫成 Android 專屬測試再重寫） |
| **—** | ⬜ | **【檢查點】KMP 實證可行性盤點**。**前置條件：B1–B5 與 R1–R4 全部完成**（2026-07-28 決定） | 實際盤點協議層行數、平台 API 依賴點、`expect`/`actual` 清單、估工（**含 R5 解耦的估工**）→ 決定是否進 R5→R6/R7 | 更新 **B6-0**（現有統計為 2026-07-27 靜態實測） |
| **R5** | ⬜ | **協議層／連線層解耦**：拔掉 `StatefulConnection` 的 Rx 與 Android 型別（見 R.2 行號清單），改 `suspend`/`Flow`；`LiveData` 直接刪（已有 `SharedFlow` 並行） | demo 不再被迫 import `BleDisconnectedException`；**這是 R6（KMP module）的技術前提**。RxAndroidBle 仍留在實作內部，只是不洩漏到介面 | B6-1 #5/#9/#17、技術債「Rx 混用」 |
| **R6** | ⬜ | 協議層抽成 KMP module（`commonMain`），Android `actual` 接現有連線層 | 協議層真正只改一套 | **B6-2 第 1–3 期**即施工圖 |
| **R7** | ⬜ | iOS 接 KMP framework 取代 Swift 協議層，保留 CoreBluetooth 連線層＋遷移測試 | 「一套 SDK 雙平台」 | B6-2 第 4 期（改 `expect`/`actual`，非 Kable） |

> **為什麼本專案把測試（R4）排在解耦（R5）前面，與 MFR 相反**（2026-07-28 決定）：
> MFR 把解耦寫成「補測試的前提」，那句話**只對 UseCase 層測試成立**（需要 mock `StatefulConnection`，
> 而它現在滿是 Rx 型別）。**協議層向量測試不需要它**——`BleCmdRepository.createCommand`/`resolve`/
> `encrypt`/`decrypt` 是純 `ByteArray` 進出，完全不碰 `StatefulConnection`。
> 所以本專案倒過來：**先建回歸網，再動連線層那一刀**。R5 是本路線圖風險最高的一步
> （直接衝擊 v3，見 R.4），沒有測試網就動它等於閉著眼睛改。
> 對應到 B3：**階段 1（封包層）排 R4，階段 2（UseCase，需 mock）必須等 R5 之後**。

### R.3.1 KMP 檢查點的前置條件（逐項可勾）

**全部勾完才做 KMP 可行性盤點**，任一項未完成不得提前開工 R5/R6
（R3.5 也算前置——它會動到 submodule 的例外定義，那是協議層盤點的一部分）：

```
□ B1  HomeViewModel：至少完成 onCleared() → cancelJobs()（5 個 Job 全取消）
      ⚠️ 完整拆分（依賴 ≤ 10）不在此前置條件內——B1 本體仍是「無痛點勿主動發起」，
        且拆分應在 R4 測試網之後才有安全網。此處只要求那個獨立、小、立即有價值的一步
□ B2  HomeViewModel 的 43 行／44 次 `!!` 歸零（資料實體內部個案除外），並補上 showLog()
□ B3  階段 1 完成（＝R4）；階段 2/3 不在前置條件內（階段 2 依賴 R5）
□ B4  .gitignore 的 core_ble_android 條目與 4 個 Android 範本測試檔清掉
🔄 B5  技術升級 ＋ 真機驗收都 ✅（2026-07-29）；**只剩 B5-1 版號待拍板**
✅ R1  同 B5，含 release 實機驗收
✅ R2  HiltApplication 的 WorkManager API 新寫法（升級唯一必需的程式碼改動）
✅ R3  「SDK 發版即更新 demo gitlink」規則已寫進 AGENTS.md §5
□ R3.5 例外語意與取消傳播五項整批修完（見 §R.3.2），且**實機驗過**三條路徑的 log
□ R4  跨語言 JSON 測試向量落地，Android runner 可跑、綠燈
```

> **盤點要產出什麼**（不是寫一份「建議做 KMP」的心得）：
> 協議層實際行數與檔案清單、平台 API 依賴點逐一列出、`expect`/`actual` 清單、
> **R5 解耦的估工**、iOS 端現況與意願、submodule 分支策略（R.4 那條待拍板項）。
> 依這份盤點才決定是否進 R5 → R6/R7。B6-0 的統計要在此時重測（現有數字是 2026-07-27 靜態實測，
> 且 R1/R2 會改動檔案內容）。

### R.3.2 R3.5：例外語意與取消傳播修正（已寫好但刻意抽出，等待整批處理）

**這五項曾在 2026-07-29 一起做完並通過編譯／release 驗證，之後刻意從升級 commit 抽出**，
理由是：它們與 Android 17 升級無關（撤掉後 `:app:compileDebugKotlin` 與 `:app:assembleRelease` 仍綠），
但會動到 submodule，**一動 submodule 就等於逼姊妹專案 v3 跟著更新 gitlink**。
升級要能獨立進版，這批就得分開走。

> 💾 **程式碼沒有丟**：submodule 側完整改動保存在 tag
> `archive/exception-rework-20260729`（submodule repo 內，`4a71a62`），要做時直接 cherry-pick；
> app 側改動可從主專案 tag `archive/pre-split-20260729` 取回。

| # | 改哪裡 | 內容 | 為什麼不能單獨做 |
|:-:|--------|------|-----------------|
| 1 | submodule `exception/bleException.kt`、`exception/LockStatusException.kt` | 自訂例外由繼承 `Throwable` 改 `Exception`（共 2 檔、10 個類別） | 這是根因。不改，呼叫端的 `catch (e: Exception)` **接不到** BLE 例外，例外會逃到 `viewModelScope` 頂層閃退 |
| 2 | submodule `entity/LockDirection.kt` | 移除 `: Throwable()`（它是純資料值，從未被 throw，繼承 Throwable 是誤植） | 與 #1 同一主題，一起清 |
| 3 | app 6 處寬泛 catch（`HomeViewModel` `runWithLoading`／OTA 傳送／scan→connect 橋接／自動化測試，`OtaWorker` `doWork`／`otaUpdate`） | 補 `if (e is CancellationException) throw e` | **只做 #1 不做這個**：改繼承後這些 catch 會一併吞掉 coroutine／WorkManager 取消 |
| 4 | `HomeViewModel:737` | 死 catch 修正：`withTimeout` 丟的是 `kotlinx.coroutines.TimeoutCancellationException`，原程式 catch 的是 `java.util.concurrent.TimeoutException`（兩者無繼承關係，該 catch 從未觸發） | **只做 #3 不做這個會出大事**：逾時例外是 `CancellationException` 子類，會被 #3 的 rethrow 靜默放行 → **30 秒連不上鎖時畫面一個字都不印**（已實測）。正解是 rethrow 條件寫成 `e is CancellationException && e !is TimeoutCancellationException` |
| 5 | `HomeViewModel` `runWithLoading` 的 `finally` ＋ `executeTask` 尾端 | `btnEnabled = true` 移進 `finally`（原本在 `runWithLoading` 呼叫端的下一行） | 同上：rethrow 後那行跑不到 → **執行按鈕永久 disable，只能殺 App**（已實測） |

**#3 只做一半就是負收益**——這是本專案實際踩過的坑（一刀切補 rethrow，把最常見的失敗路徑變成靜默，
對一支「價值就在 log 可追溯」的 Demo App 剛好相反）。所以整批一起，且**驗收必須實機**。

**排在 R4 之前的理由**：R4 是協議層測試向量，測不到 UI 與 coroutine 取消行為，
所以這批得靠實機驗；先修好例外語意，R4 之後的 UseCase 層測試（B3 階段 2）才有乾淨的前提。

### R.4 本專案特有的風險（MFR 沒有，不要照抄 MFR 的樂觀評估）

- 🔴 **`core_ble_android` 被 v3 正式產品共用**（MFR 的 `core_ble_mfr_android` 只服務產測 demo）。
  R5 拔 Rx 型別、R6 改 KMP module **都會直接衝擊 v3**。動 R5 之前必須先確認：
  ⬜ 是否開 submodule 的 KMP／解耦分支平行維護？（否則 v3 的升級與本改造會打架）
- 🟡 **本專案 release 有 `minifyEnabled` ＋ `shrinkResources`，且 OTA 走 WorkManager 反射**
  → R1 的 ④⑤ 階段風險比 MFR 高（MFR 的 OTA 裝置端未支援、驗證被免除，本專案不能比照免除）。
- 🟡 **R2 的例外行為變更沒有測試安全網**（B3 未做）→ 只能靠實機抽驗，改動要小批、可回溯。

### R.5 Demo App 在這條路線上的角色

新增／修改 cmd 時**先在本 demo ＋ 測試向量（R5）驗證**，通過後連同向量移植到
`Sunion_iKeyConnect_v3_Android`，讓移植有客觀驗收、不靠人工比對。
這也是本 demo 除了「手動打指令」之外的長期價值所在。

---

## 技術債清單（不進 B1–B6 主軸，但已知）

| 問題 | 位置 | 建議 | 優先 | 狀態 |
|------|------|------|:---:|:---:|
| RxJava2 與 Coroutines 混用 | `core_ble_android` 連線層（RxAndroidBle）→ `rx2.asFlow()` 橋接 | **相依維持現狀，介面要去 Rx**：不換掉 RxAndroidBle（外部相依，換掉成本遠大於收益），但 `StatefulConnection` 不該把 `Observable`/`Disposable`/`PublishSubject` 洩漏到介面上——見 **§R 階段 R5**。新 UseCase 一律 Coroutines/Flow | 🟡 | ⬜ |
| `showLog()` 訊息中英文混用 | `HomeViewModel`（「請輸入鎖體時間」vs `"$functionName exception $e"`） | 統一為單一語言；**須整批處理**，勿順手改個別字串 | 🟢 | ⬜ |
| `core_ble_android` 無 Hilt Module | 全靠 `@Singleton` + `@Inject constructor` | **維持現狀**，可行且簡潔；不要為「架構完整」新開 Module | — | ✅ 不處理 |
| 功能／機種支援矩陣分散在 `BleDeviceFeature.kt` 的 `Triple` | `core_ble_android/entity/BleDeviceFeature.kt` | 資料結構樸素但可讀；若 TaskCode 再膨脹一倍再評估改 data class | 🟢 | ⬜ |
| **Gradle 10 相容性**：`--warning-mode all` 列出三類 deprecation——Groovy space-assignment 語法（`compileSdk 37` 應寫 `compileSdk = 37`，app 8 處＋submodule 1 處）、ext 跨專案隱式查找（`$coreVersion`／`$compose_ui_version`／`$hilt_version`／`$timber_version`，app 11 處＋submodule 若干）、`implementation project(':core_ble_android')` 的 Project notation | `app/build.gradle`、`core_ble_android/build.gradle` | **Gradle 10 才會 fail，現在不修**。真要修等於整份 build script 改寫＋ext 搬到 `gradle.properties` 或 version catalog，該與下次 Gradle 大版升級同批做 | 🟢 | ⬜ |
| **release APK 只有 v2 簽章**（`v1: false, v2: true, v3: false`） | `app/build.gradle` 的 `signingConfigs.BleDemoApp` | targetSdk 37 安裝無礙（≥API 34 最低要 v2），但 AGP 通常會同時產 v3。**確認是否刻意**，若非則補 `enableV3Signing`。非本次升級造成（`a994ce1` 建立簽章時就如此） | 🟢 | ⬜ |
| **`signingConfigs` 讀檔的兩個問題**（2026-07-29 實測）：① `new FileInputStream` **未關閉** → gradle daemon 存活期間持有 `keystore.properties` 的 handle，想改名／換 keystore 必須先 `--stop`；② 該 block 在 configuration 階段**無條件執行** → **debug 明明用 AGP 內建 debug.keystore，卻也被迫依賴 release 的設定檔**，缺檔時 `:app:compileDebugKotlin` 直接 `FileNotFoundException` | `app/build.gradle:24-36`（`signingConfigs` block，`:31` 是 `load(new FileInputStream(...))`） | 兩者一起修：改用 `file.withInputStream { }`（自動關閉，姊妹專案 BleMFRDemoApp 的 `applySigningConfig()` 就是這個寫法）；若要讓 debug 不依賴該檔，再加缺檔時跳過建立 signingConfig 的判斷（**但要確認 release 仍會因缺檔而明確失敗**，不能靜默出未簽章的 APK） | 🟡 | ⬜ |
| **機種支援規則有兩份、且分屬兩個 repo** | `BleDeviceFeature.modelVersions`（submodule）＋ `HomeViewModel.setModelSupportTaskList()`（`:3305`，逐機種 `removeIf` 硬編碼） | 新增機種漏改其一 → 該機種拿到未過濾清單、跑出不支援的指令。**這是真實踩得到的坑**，建議把過濾規則收斂進 `BleDeviceFeature`（單一來源），app 層只讀不判斷 | 🟡 | ⬜ |

---

## Git 現況與待辦

> 來源：2026-07-27 本機 git 實測（`git branch --merged` / `--no-merged` / `rev-list --left-right`）。

### 分支實測（2026-07-27）

| Branch | 狀態 | 說明 |
|--------|------|------|
| `feature/ble_v3_cmd` | ✅ 事實主線 | 持續開發中（`dbdcb1e`，2026-07-27） |
| `master` | ⚠️ 落後 5 個 commit | `d6568d5`（2025-12-19），是 v3 的**直接祖先**（`0 ahead / 5 behind`），快轉即可 |
| `develop`、`feature/ble_v2_cmd`、`feature/new_function` | 🗑️ 已合併進 v3 | `--merged` 確認，可歸檔刪除 |
| `feature/ble_v3_cmd_multi`、`feature/color_test` | ⚠️ 待盤點 | `--no-merged`：有 unique commit 未進 v3。**兩者皆為 local-only（無 origin 備份）**——刪掉就沒了，盤點前不要動 |
| Tags | ✅ 已有 | `1.0`、`V.1.0.1`、`V.2.0.1`、`V.2.0.3`、`V.3.0.0`（submodule 目前指向 `V.3.0.0-26-g36d515c`） |

### 待辦

| 優先 | 編號 | 項目 | 狀態 |
|:---:|------|------|:---:|
| 🔴 | G1 | 把 `master` 快轉到 `feature/ble_v3_cmd` 並打 tag（純快轉，零衝突風險） | ⬜ |
| 🟡 | G2 | 盤點 `feature/ble_v3_cmd_multi`、`feature/color_test` 的 unique commits（**兩者皆 local-only，刪前務必先 push 或打 archive tag**） | ⬜ |
| 🟡 | G3 | 清理已合併 branch：`develop`、`feature/ble_v2_cmd`、`feature/new_function`（先打 `archive/` tag 再刪 local+remote） | ⬜ |
| 🟡 | G6 | **做 R3.5 時才會用到的 push 規矩**（本次升級不涉及，因為 submodule 零改動）：一旦主專案 gitlink 指向新的 submodule commit，**submodule 必須先推**——順序顛倒或只推主專案，別人 `git clone --recursive`／`git submodule update --init` 會直接失敗（`upload-pack: not our ref`，實際驗證過這個風險）。正確順序：`git -C core_ble_android push origin feature/ble_v3_cmd` → `git push origin feature/ble_v3_cmd`。**推完還要通知 v3 側同步 gitlink**（R3 規則的另一半，見 AGENTS.md §5） | ⬜ |
| 🟢 | G4 | 確立「submodule 先 commit、主專案再更新 gitlink」的檢查習慣（AGENTS.md §5 已記，2026-07-29 的 R0–R2 已實際遵循）。**另一半是 §R 階段 R3**：SDK 發版就要更新 demo 的 gitlink | ⬜ |
| 🟡 | G5 | `.gitmodules` 的 `branch` 由 `master` 改為 `feature/ble_v3_cmd`（submodule 事實主線） | ✅ 2026-07-29（隨 R0） |

---

## 更新紀錄

> 一天一列，只記「決策與結果」，不記過程。
> ⚠️ **本檔不寫 commit SHA**：2026-07-29 的 commit 重整過兩次（先依修正順序拆分，
> 後又壓成兩個 commit），寫進文件的 SHA 每次都失效一輪。要查誰改了什麼用 `git log --oneline`。

| 日期 | 變更 |
|------|------|
| 2026-07-27 | **初版建檔**：B1–B6 主軸（B1 God VM／B2 `!!`／B3 測試／B4 dead code／B5 Android 17／B6 KMP）、技術債清單、Git 現況。所有數字為本機實查——`HomeViewModel` 4496 行、`!!` 43 行 44 次、submodule 74 檔中 36 檔無平台相依、`entity/` 28/29 乾淨——非沿用姊妹專案 |
| 2026-07-28 | **新增 §R 演進路線圖（R0–R7）**，把 `BleMFRDemoApp_Android` backlog §5 的漸進路徑映射到本專案，沿用其方向決策（方案 B 只共用協議層、不採 Kable、不做完整 KMP），並據此收斂 B6（連線層改 `expect`/`actual`）。**與 MFR 相反的一點**：本專案把跨語言測試向量（R4）排在連線層解耦（R5）**之前**——協議層向量不依賴解耦，先建回歸網再動風險最高的 R5。KMP 可行性盤點的前置條件定為「B1–B5 與 R1–R4 全部完成」（§R.3.1 逐項清單） |
| 2026-07-29 | **R0–R3／B5-2 完成：Android 17（targetSdk 37）／AGP 9.2.0 升級**。內容：追上 v3 的 submodule 版本（gitlink → `de76a71`）＋ build script 與相依升級 ＋ `HiltApplication` 的 WorkManager API（**升級唯一必需的程式碼改動**）。機械驗證全綠（debug／release 編譯、16KB 對齊、apksigner v2 驗章）。唯一實質 breakage：Compose 1.9.0 不再傳遞 `material-icons-core`。<br>**刻意抽出**：例外語意／取消傳播的五項修正（原本一起做完並驗證過）整批移到 **R3.5**——它們與升級無關，卻會動到 submodule，一動就逼姊妹專案 v3 更新 gitlink。抽出後 submodule 停在 `de76a71`（＝v3 同一個 commit），**本次升級對 v3 零影響**。程式碼保存在 tag `archive/exception-rework-20260729`（submodule）與 `archive/pre-split-20260729`（主專案）。<br>新增技術債：Gradle 10 三類 deprecation、release 只有 v2 簽章、`signingConfigs` 的 `FileInputStream` 未關閉＋debug 被迫依賴 release 設定檔。<br>**✅ release 版實機驗收通過（使用者執行）**：B5-4 真機五項全過（安裝、核心流程、OTA、QR 掃描、Retrofit 實連），**B5／R1 結案**。這是本專案第一次有完整實機驗收——先前所有「已驗證」都只到編譯層。唯一遺留是 B5-1 版號待拍板 |
| 2026-07-29 | **文件與升級後現況對齊**（做法參考 BleMFRDemoApp 的 AGENTS.md）：移除寫死的版本矩陣，改指向 `build.gradle`，並寫明原因（那些版本號在本次升級後全部作廢，**不要再寫回去**）；補「編譯前置條件」與**實測的簽章分工**（debug 用 AGP 內建 `debug.keystore`／`CN=Android Debug`，release 才用 Sunion keystore／`CN=SunionAppTeam`；但 `keystore.properties` 在 configuration 階段被無條件讀取，所以連 debug 都要有這個檔）；補版號公式與 release 產物驗證指令；§7 路由改為指向 §R 的下一步；新增 **§R.3.2**（R3.5 的五項內容與「為什麼不能只做一半」）。本更新紀錄由 6 條雜項壓成 4 條並改為不寫 SHA |
