# 給未來 session 的信

> 2026-07-27 由 Opus 5 撰寫（建制 session）。這套制度移植自姊妹專案
> `Sunion_iKeyConnect_v3_Android`，**協作邏輯與流程完全一致**，專案事實則全部重新實查。
> 通用制度規則集中於共用 skill `android-dev-governance`（單一來源，三個 Sunion Android 專案共用）；
> `docs/ai/` 只留專案專屬檔（本檔、CODE_PATTERNS）。
> 本檔正文凍結；只能在末尾「交接區」追加（見 skill `android-dev-governance` `rules/40-maintenance-protocol.md` §1）。

---

## 一、使用者沒問、但你最需要知道的四件事

### 1. 這不是產品，別用產品的標準做事

`BleDemoApp_Android` 是給韌體／App 工程師**逐條打 BLE 指令、看畫面 log 驗證**的工具。
它只有一個 Activity、一個 ViewModel、兩個畫面，UI 由 `BleDeviceFeature.taskList` 驅動渲染。

這個定位改變了很多預設判斷：

- `HomeViewModel` 4496 行是**已知且被接受的現狀**，不是等你來拯救的災難。它是一張扁平的指令分派表，
  加一個功能就是加一個 `when` 分支。**沒有實際痛點前不要提議拆它**（backlog B1 已標為低優先，
  並寫明了理由）。
- 相對地，**「改一個指令改壞另一個指令」才是這裡真正的痛**——所以 backlog 把 BLE 封包層測試（B3）
  排在最前面，而不是 UI 測試或架構重構。
- 「這裡缺少 XXX 抽象層」「應該導入 Repository pattern」這類建議在這個 repo 是**噪音**。
  真的看到問題，記進 backlog，不要當場動手。

### 2. 真相只有一份：repo 檔案 > 你的記憶 > 你的直覺

專案規則的唯一真相是 repo 內的檔案（[CLAUDE.md](../../CLAUDE.md)、`docs/`）；auto-memory 只放
使用者偏好與環境事實，且會過期——兩者矛盾時信 repo 檔案，並當場修記憶。
[docs/REFACTORING_BACKLOG.md](../REFACTORING_BACKLOG.md) 是這個專案的大腦：B1–B4、技術債、
Git 現況都在裡面；接手長期工作先讀它，做完立刻更新它。

**特別注意**：本專案與姊妹專案 iKeyConnect v3 共用同一套制度 skill，但**專案事實完全不同**
（v3 有 7 個 flavor、本專案 0 個；v3 用 MQTT、本專案沒有；v3 的 backlog 是 F1–F5、本專案是 B1–B4）。
不要把 v3 的記憶套過來——這是可預期的高頻錯誤。

### 3. 這位使用者的工作模式

台灣人，用繁中溝通，**回覆一律用繁體中文**（技術名詞保留英文；注意 CLAUDE.md §6 的禁用詞表）。
工程紀律強：commit 要明確授權才做、格式講究、單一主題一個 commit、討厭灌水（「已驗證」亂寫是大忌）。
他讀得懂技術細節，不用過度解釋，但**要給證據**（指令輸出、檔案:行號）。

他會逐項拍板，也會直接指出你哪裡搞錯——被指正時修正即可，不要長篇道歉。
本 repo 建制當下的工作脈絡：`feature/ble_v3_cmd` 分支上進行 BLE v3 指令開發。

### 4. 這個環境的物理限制與本專案的特有地雷

Windows 11＋PowerShell 5.1（`&&` 不能用、預設編碼陷阱多）、使用者路徑帶空格（`Tom Chen`）。
檔案操作只用 Read/Write/Edit/Grep/Glob；gradle 長任務用 run_in_background。

本專案特有地雷：

| 地雷 | 說明 |
|------|------|
| **`core_ble_android` 是 submodule** | 先在子模組 commit 再更新主專案 gitlink，忘記分離是最高頻錯誤。而且**新增 BLE 功能幾乎一定會動到它**（TaskCode 與 UseCase 都在裡面）。 |
| **`.gitignore:94` 的 `core_ble_android` 是誤導** | 那條 ignore 無效（gitlink 已在 index，mode 160000）。看到它不要推論「submodule 沒被追蹤」。已記入 backlog B4。 |
| **編譯通過 ≠ BLE 功能正確** | 改指令行為時，唯一有效的驗收是**實機對真鎖跑一次該 TaskCode，貼出畫面 log 輸出**。做不到就明說「未實機驗證」。 |
| **測試基建未導入** | 只有 4 個 Android 範本檔（B3）。「測試通過」目前不可能為真。 |
| **無 productFlavors** | task 名是 `:app:compileDebugKotlin`，不是 v3 那種帶 flavor 的長名字。 |
| **協定世代 1/2/3** | 同一功能在不同世代的封包可能不同或不支援。`BleDeviceFeature.modelVersions` 是機種↔世代對照表，動功能前先查。 |

---

## 二、這套制度最可能的退化方式與預防

| 退化方式 | 症狀 | 預防（已內建的防線） |
|----------|------|----------------------|
| **規則通膨** | 每踩一次坑加一條規則，CLAUDE.md 膨脹到沒有一條顯眼 | skill `rules/40-maintenance-protocol.md` §3 行數預算；教訓優先寫成既有條目的正反例，不新開規則 |
| **儀式化合規** | 跑了 checklist 的形式、沒跑實質——「✅」打了但沒有證據 | 每個 ✅ 必附證據（指令＋輸出）；fresh-context 驗收不自驗；空口宣稱＝鐵律 10 違規 |
| **拿產品標準套 Demo App** | 提議拆 God VM、導入抽象層、統一 UI 字串——全是「正確但不該現在做」 | backlog 開頭「先講清楚：這是 Demo App」章節；B1 明確標示低優先並寫明理由 |
| **姊妹專案記憶污染** | 把 v3 的 flavor／MQTT／F1-F5 編號套到本專案 | 本檔 §1.2 明列差異；CLAUDE.md 開頭「本專案的定位決定規範強度」段落 |
| **文件與現實脫鉤** | 行號、檔案數變了沒人更新，模型開始整體不信任文件 | skill `rules/10-model-dispatch.md` 規則零（實際檔案為準＋有義務回寫）；skill `rules/40` §4 健檢 |
| **路由失靈** | session 從不點開 `docs/ai/`，制度存在但沒人讀 | 路由表放在每次必載的 CLAUDE.md §1，觸發條件寫成具體情境而非「建議參考」 |

最根本的一條：**制度只在被使用時存活**。如果你發現自己想跳過某條規則，
先問「這條在防什麼坑」——答不出來就去讀對應檔案，答得出來且坑真的不存在了，
走 skill `rules/40-maintenance-protocol.md` §1 的流程改掉它。
默默不遵守是最壞的選項：規則還在、保護沒了、下一個 session 更困惑。

---

## 三、交接區（後續 session 依 skill `rules/40-maintenance-protocol.md` §1 只可追加）

- **2026-07-27（Opus 5，建制 session）**：制度檔落地 6 件——`CLAUDE.md`、
  `.claude/settings.local.json`、`.gitignore`（追加一行）、`docs/ai/LETTER_TO_FUTURE_SESSIONS.md`（本檔）、
  `docs/ai/CODE_PATTERNS.md`、`docs/REFACTORING_BACKLOG.md`。
  協作邏輯與流程沿用姊妹專案 iKeyConnect v3（已通過該專案的對抗審查），專案事實全部重查。

  **驗證狀態（誠實記錄）**：

  ① **主 session 親自查證（可信度最高）**：`build.gradle` 全部設定與「無 productFlavors」、
  submodule gitlink（`git ls-files -s core_ble_android` → mode `160000`）、
  `HomeViewModel` 4496 行、`!!` 43 行／44 次、`collectAsState` 3 處使用、Timber 73 處／`android.util.Log` 0 處、
  `runWithLoading`／`showLog`／`executeTask`／`getLockTime` 原始碼逐字、`BleDeviceFeature.kt` 內容、
  `HomeScreen` 結構、`className` 定義、分支與 tag 現況（`git branch --merged` / `rev-list --left-right`）。

  ② **由 scout agent 回報、主 session 未逐字複查（可信度次之，用到時請先驗）**：
  `LockAccessCodeUseCase` 完整程式碼（只複查了 `className` 那一行）、`Command.kt` 介面與
  `DeviceStatus82Command` 全文、`core_ble_android` 各目錄檔數（74）與 `entity/` 清單、
  `BleCmdRepository` 的行號標註（`:25` CIPHER_MODE、`:200` pad、`:229` requestMtu）、
  `MainActivity.kt:24` 權限請求、`di/AppModule.kt`／`di/BleModule.kt` 內容。

  ③ **fresh-context 對抗審查：已執行**（`verifier` agent，事實查證視角）。判定 **REFUTED**，
  抓出 7 項硬事實錯誤 ＋ 12 項不精確，全部已修正並回頭複查。被抓到的錯誤值得記住，
  因為它們正是「照抄姊妹專案」最容易產生的那一類：

  | 錯誤 | 實際 | 教訓 |
  |------|------|------|
  | 「注入 27 個依賴」 | **30** 個 | scout 回報的數字沒複查就寫進文件 |
  | 「資源語系有 `values-zh-rTW/`」 | **不存在**，只有 `values/` | 看到 `resConfigs "en","zh-rTW"` 就腦補資源目錄；那個設定只過濾 library 語系 |
  | 「route 字串 snake_case」 | 實際是 **PascalCase**（`"Home"`、`"Scan"`） | 直接把 v3 的慣例搬過來，沒開 `HomeNavHost.kt` 看 |
  | 「usecase 27 個都是 `@Singleton`」 | 只有 **21** 個 | 看了一個範例就推論全體 |
  | 「core_ble 根層 8 個檔」 | **9** 個（表格加總 73≠74 就該發現） | 自己的表格內部矛盾沒自檢 |
  | 「6 個 Job 追蹤變數」 | **5** 個（括號裡自己只列了 5 個） | 同上 |
  | 「`.gitignore` 末行」 | 在 `:94`，末行是後來加的 `.claude/…` | 寫的當下是末行，改完自己的檔就過期了 |

  另補上兩個 verifier 發現、原本完全漏寫的**真實地雷**：`setModelSupportTaskList()` 的機種硬編碼
  （新增機種要改兩處，已寫進 CLAUDE.md §2 並記為技術債）、`initTaskList` 導致「未連線看不到新 TaskCode」
  （已寫進 CLAUDE.md §3 與 CODE_PATTERNS §0）。

  ④ **未執行**：⚠️ **無 gradle 編譯驗證**（本次只新增 Markdown 與 JSON，不影響編譯，
  但也因此沒有機械證據證明 CODE_PATTERNS 的程式碼片段可編譯）。
  ⚠️ **對抗審查只跑了「事實查證」單一視角**，未跑 v3 那種三視角（規則衝突／弱模型誤讀）。

  **待補**：① 補跑「規則衝突」與「弱模型誤讀」兩個視角；
  ② 上列 ②類（scout 未複查）事實的行號日後仍需抽查——submodule 更新後行號最容易漂。

- **2026-07-29（Opus 5）**：**Android 17（targetSdk 37）／AGP 9.2.0 升級完成**，兩個 commit
  （`[Build]` 升級 ＋ `[Docs]` 文件）。機械驗證全綠（debug／release 編譯、16KB 對齊、apksigner v2），
  **且 release 版由使用者實機驗收通過**（B5-4 真機五項全過：安裝、核心流程、OTA、QR 掃描、Retrofit 實連）。
  **這是本專案第一次有完整的實機驗收**——在此之前所有「已驗證」都只到編譯層，鐵律 10 的那句
  「跑不了就明說未驗證」寫的就是這個落差。B5／R1 至此結案。

  **升級的實際範圍比想像小**：build script ＋ 相依版本 ＋ submodule gitlink（→ `de76a71`，
  v3 已推進的 commit，SDK 37 準備工作白拿）＋ **`HiltApplication` 的 WorkManager API 一處**——
  那是唯一必需的程式碼改動。`HomeViewModel`／`OtaWorker` **完全沒動**。

  ⚠️ **這裡有個刻意的決策，接手時別以為是漏做**：我原本連帶做了「BLE 例外繼承鏈改 `Exception`
  ＋ 7 處補 `CancellationException` rethrow」，做完也驗證過，**後來整批抽出到 backlog R3.5**。
  理由：那批改動與升級無關（撤掉後 debug／release 仍綠），但**會動到 submodule，一動就逼姊妹專案
  v3 跟著更新 gitlink**。升級要能獨立進版，這批就得分開走。抽出後 submodule 停在 `de76a71`
  ＝ v3 同一個 commit，**本次升級對 v3 零影響**。
  程式碼沒丟：submodule 側在 tag `archive/exception-rework-20260729`，app 側在主專案 tag
  `archive/pre-split-20260729`，做 R3.5 時直接取回。

  **本檔正文有三處已過期**（正文凍結不改，在此更正）：
  ① §1.2 說「本專案 backlog 是 B1–B4」→ 現在是 **B1–B6 ＋ §R 演進路線圖 R0–R7**，
  且 backlog 的入口章節已改成「當前主線看 §R」。
  ② §1.2 說「制度真相是 CLAUDE.md、docs/」→ 已改成三層：**AGENTS.md（工具中立共通事實）**
  ＋ CLAUDE.md（Claude 專屬）＋ docs/。共通事實只寫在 AGENTS.md。
  ③ 地雷表「無 productFlavors → task 名 `:app:compileDebugKotlin`」仍成立，
  但**現在還要知道跑 debug 也必須有 repo 外的 `keystore.properties`**——不是因為 debug 需要簽章
  （debug 用的是 AGP 內建 `debug.keystore`，實測憑證 `CN=Android Debug`），
  而是 `app/build.gradle:31` 在 **configuration 階段**無條件 `new FileInputStream` 讀那個檔（AGENTS.md §1 已補）。
  ④ §1.1 與 §3 的「`HomeViewModel` 4496 行」**仍然正確**（升級沒動這支檔案）。
  但要知道：用 `Get-Content | Measure-Object -Line` 數它會得到 **4191**——PowerShell 5.1 把無 BOM
  UTF-8 當 codepage 950 讀，CJK 吃掉換行，少算 305 行。**別拿那個數字寫文件**（AGENTS.md §1 已補）。

  **這次最值得記住的教訓**（都不是版本問題，是判斷問題）：

  | 教訓 | 內容 |
  |------|------|
  | **一刀切的 rethrow 會製造靜默失敗** | 把 BLE 例外改繼承 `Exception` 後，我在 7 處寬泛 catch 補了 `if (e is CancellationException) throw e`——結果「30 秒連不上鎖」變成畫面**一個字都不印**、執行按鈕永久卡死。根因是 `TimeoutCancellationException` 也是 `CancellationException`，而 `btnEnabled` 的還原不在 `finally`。**這批改動雖已抽出，坑與正解都記在 backlog §R.3.2**，做 R3.5 時照那張表走，別再犯一次 |
  | **死 catch 會被升級「引爆」** | `withTimeout` 丟 `kotlinx.coroutines.TimeoutCancellationException`，原程式 catch 的是 `java.util.concurrent.TimeoutException`——那個 catch 從來沒觸發過。這種既有 bug 平常無感，一旦上游行為變了就致命。**改例外相關的東西之前，先確認每個 catch 真的抓得到它宣稱要抓的型別** |
  | **文件寫死版本號等於埋下過期** | AGENTS.md 開頭原本列 Kotlin 1.9.24／AGP 8.6.1／Compose 1.7.0／Hilt 2.52／compileSdk 35，升級後五個全錯。已改成指向 `build.gradle`（做法抄姊妹專案 BleMFRDemoApp）。**不要再把版本號寫回文件** |
  | **scout 的「沒發現問題」不等於沒問題** | 派 scout 掃過時技術事實，它回報四個文件都乾淨；實際上 CODE_PATTERNS §3.2 的 `runWithLoading` 範例已與程式碼不符（它只比對關鍵字，不會察覺「這段程式碼變了」）。**scout findings 是輸入，不是驗證結果** |
  | **fresh-context verifier 值得跑** | 上面第一、二條都是 verifier 抓出來的，不是我自己發現的。它同時抓到 submodule 內漏改的第 7 處 catch 與 `LockDirection` 誤繼承 `Throwable` |

  **接手時該知道的下一步**：R0–R3 已完成，下一個是 **R3.5（例外語意與取消傳播五項，見 §R.3.2）**，
  再往後才是 **R4（跨語言 JSON 測試向量，＝B3 階段 1）**；
  KMP 評估要等 B1–B5＋R1–R4 全部完成（判準見 backlog §R.3.1，逐項可勾）。
  **需要使用者拍板的**：B5-1 版號公式與遞增。
  **push 由使用者自己做**（AI 不 push）；本次 submodule 零改動，所以只推主專案即可，
  日後做 R3.5 動到 submodule 時才要注意「submodule 先推 ＋ 通知 v3 同步」（backlog G6）。
  **B5-4 下半段全是真機項目**，在那些跑完前 B5／R1 不算結案（鐵律 10）。
