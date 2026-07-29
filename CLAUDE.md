# Sunion BleDemoApp Android — Claude Code 專屬規則

@AGENTS.md

**共通事實（定位說明、鐵律、環境限制與驗證方式、架構、TaskCode 鏈路、類別歸位、語言規則、
Git 規範、進度文件路由）全部寫在 [AGENTS.md](AGENTS.md)**，由上面那行匯入——本檔只放 Claude Code 專屬制度。

**共通入口是 `AGENTS.md`**：其他 agent 工具讀得懂 `CLAUDE.md`，但未必會**自動載入**它
（Codex 自動讀 git root 起的 `AGENTS.md` 階層；Antigravity 自動讀 `AGENTS.md`／`GEMINI.md`／`.agents/rules`）。
所以共通事實要改就改 AGENTS.md，**不要在本檔重寫一份**——兩處各寫必然漂移。
規則同步義務見 [AGENTS.md](AGENTS.md) §0 鐵律 11（依規範性質判斷，不依檔案）。

---

## 1. 文件路由（Claude 專屬；其餘見 AGENTS.md §7）

| 情境 | 讀這個 |
|------|--------|
| 派工／選模型／驗收、判斷完成、寫派工 prompt、commit／merge、Android 驗證工作流、改制度 | skill `android-dev-governance`（**唯一來源**；其 SKILL.md 有路由表 → rules/00–60） |
| Android 程式寫法（BLE／Compose／Coroutines／ProGuard 等領域規範） | skill `android-expert` |

---

## 2. AI 作業規則

- **預設工作模式：Orchestrator**——任何 Android 任務先套用 `android-dev-governance` 與
  `android-expert` 兩個 skill；規劃拆解與最終審核由主模型負責，執行細節委派低成本 subagent，
  context 只留決策與總結，每個子任務附機械可查的驗收標準，盡量平行委派
  （細則見 skill `android-dev-governance` `rules/10-model-dispatch.md`）。除非使用者明確說「直接執行」，否則都用此模式。
- **Skill 白名單**（唯一定義處；名稱以 session 內 available-skills 清單所列**全名**為準，plugin skill 帶
  `plugin:` 前綴）：`android-dev-governance`、`android-expert`、`orchestrator`、
  `engineering:code-review`、`review`、`security-review`、`run`、`simplify`、`update-config`、
  `anthropic-skills:skill-creator`；其他 skill 僅在使用者明確指名（輸入 `/<skill>`）時使用。
  **清單以 session 實際載入的 available-skills 為準**——名稱對不上就以 session 為準並回報，
  不要硬呼叫不存在的 skill。
- **制度分工（三層，各有明確職責）**：
  - skill `android-dev-governance`（rules/00–60）＝**跨專案**的開發流程制度（派工／驗證／判準／commit／merge），
    該範圍的**唯一來源**；skill `android-expert` ＝ Android 程式寫法領域規範。
  - [AGENTS.md](AGENTS.md) ＝ **本專案的共通規範與專案事實**，工具中立、所有 agent 共讀
    （鐵律、環境限制與驗證流程、架構、TaskCode 鏈路、類別歸位、Git 規範、進度路由）。
  - 本檔 ＝ **只補 Claude Code 專屬制度**（Orchestrator 派工模式、skill 白名單、memory 定位）。
  - **仲裁順序（衝突時）：使用者當下指示 ＞ AGENTS.md／本檔 ＞ skill 通則**
    （改制度見 skill `rules/40-maintenance-protocol.md`）。
- **檔案操作用專用工具**（Read/Write/Edit/Grep/Glob），不用 shell 讀寫檔；shell 只用來執行程式（git、gradle）。
- **memory 只是快取**：`~/.claude/projects/<slug>/memory/` 不在版控、其他工具讀不到。
  凡是跨工具需要知道的事實（環境限制、進度、決策），唯一來源放 repo（AGENTS.md 或 `docs/`），memory 僅供快速召回。
