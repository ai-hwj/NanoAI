# NanoAI 开发规范（AI 编码代理与人共同遵守）

本文是仓库级约定。所有改动（无论人工还是 AI 代理）都必须满足这里写出的硬规则；
每条规则都可判断"违反 / 未违反"，若与本文冲突，以本文为准并先修正本文。

---

## 1. 项目概述

- NanoAI 是原生 Android 端侧 AI Agent 应用，技术栈 Kotlin + Jetpack Compose + Material3，
  包名与 applicationId 均为 `com.lyentech.nano`。
- 两条推理链路并存，二者通过 Google ADK-Kotlin 的 `Model` 抽象统一：
  - 端侧 LiteRT-LM：`google-adk-kotlin-litertlm` + `com.google.ai.edge.litertlm:litertlm-android`，
    模型为 `.litertlm` 文件，由 `app/src/main/java/com/lyentech/nano/http/LiteRtLmModelStore.kt`
    从 HuggingFace 下载，或用户用 adb push 到该目录（自己放的文件优先）。
  - 云端 OpenAI 兼容接口：公司 Gree 网关，`app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt`
    实现 ADK 的 `Model`，走 OkHttp + SSE 流式，支持 `tool_calls`。
- Agent 编排统一走 Google ADK-Kotlin：`LlmAgent` + `InMemoryRunner` + `RoomSessionService`
  （正式 Agent 页 `app/src/main/java/com/lyentech/nano/ui/chat/ChatActivity.kt`）。
- 首页 `app/src/main/java/com/lyentech/nano/ui/StartActivity.kt` 是三个示例页入口：
  Agent 聊天（`ui/chat/ChatActivity.kt`）、LiteRT-LM 端侧（`ui/RtLlmChatActivity.kt`）、
  API 云端（`ui/ApiChatActivity.kt`）。只有 `ui/chat` 是正式 Agent 页，另两个是示例屏。
- 工具能力（`app/src/main/java/com/lyentech/nano/agent/tools/`）：任务清单、工作区文件沙箱、
  联网搜索与正文抓取、设备信息、技能。
- 技能目录 `app/src/main/assets/skills` **当前不存在**，`AgentToolkit.skillToolsetOrNull()`
  会返回 null（即不挂技能工具集）。新增技能时必须新建该目录并按 `assets/skills/<name>/SKILL.md` 布局放文件。

---

## 2. 架构地图

分层（改动前先判断代码属于哪一层，不要跨层塞逻辑）：

- `ui/`：Activity + Compose 屏。`ui/chat/` 是正式 Agent 页；`ui/widget/`、`ui/chat/ChatCommon.kt`
  放可复用组件。
- `agent/tools/`：ADK 工具实现；`agent/web/`：可脱离 Android 单测的联网核心逻辑。
- `http/` 与 `http/gree/`：模型层与传输层（DTO、SSE、流式解析）。
- `data/db`、`data/model`、`data/repo`：Room 实体与 DAO、领域模型、仓库、进程级服务。
- `global/`：主题、扩展、Activity 基类。`di/`：Koin 依赖**已引入但尚未接线**，
  `di/KoinApp.kt` 是空占位，当前全部依赖为显式手工构造，不要假设存在注入容器。
- 典型请求链路（`ui/chat`）：
  `ChatActivity` → `ChatViewModel.send` → `InMemoryRunner.runAsync` → `LlmAgent`
  → `GreeChatModel.generateContent`（序列化 tools、SSE 流式、`ToolCallAccumulator` 累积分片、
  `functionResponse` 回灌为 `role = "tool"` 消息，一轮上限 `MAX_LLM_CALLS`）
  → 事件流 → `TimelineReducer.accept` → `ChatTimelineItem` 列表上屏。
- **两套独立的 sqlite 库**，不要混用，也不要试图合并：
  - 应用自己的 `NanoDatabase`（`data/db/NanoDatabase.kt`，库名 `nano_app.db`）：项目 + 会话元数据。
  - ADK 的 `RoomSessionService`：会话事件日志（由 ADK 自带 schema 管理）。
  - 会话 id 即 ADK 的 sessionId，两库靠它对齐（见 `data/repo/ConversationRepository.kt`）。

---

## 3. 构建与验证

环境事实（与配置文件一致，勿凭记忆）：
- AGP 9.3.1、Kotlin 2.4.10、KSP 2.3.6、Gradle 9.6.1（`gradle/wrapper/gradle-wrapper.properties`）。
- JDK toolchain 21（`gradle/gradle-daemon-jvm.properties` 的 `toolchainVersion=21`）。
- `compileSdk` 与 `targetSdk` 均为 37，`minSdk` 26。
- `abiFilters` 只有 `arm64-v8a`（`app/build.gradle.kts`），不要新增其他 ABI 后声称已构建。
- `configuration-cache` 已开启（`gradle.properties`），`kotlin.code.style=official`。
- 仓库顺序：`settings.gradle.kts` 的 `dependencyResolutionManagement` 里**阿里云镜像必须排在
  `google()` / `mavenCentral()` 之前**（注释已写明：本机直连 Google 仓库会 READ TIMEOUT，
  Gradle 在第一个仓库超时后直接判定整次解析失败，不会继续落到后面的仓库）。调整顺序需自行验证。
- 例外：`com.google.adk`（ADK-Kotlin）走直连 `https://repo1.maven.org/maven2`，放在最前且用
  `content { includeGroupByRegex }` 限定到该组。原因：阿里云对 Central 是半同步的，新版本常出现
  pom/.module 已到、jar 仍 404（ADK-Kotlin 1.4.0 实测），而 Gradle 的模块元数据一旦命中某仓库，
  **只在该仓库找 artifact**，不会落到后面的 `mavenCentral()`，所以镜像排前面就会永久卡死。
  升 ADK 版本前先用 `curl -I` 打一下镜像上的 `.jar` 路径，比 `--refresh-dependencies` 有效。

可用命令（可直接复制）：

```bash
./gradlew :app:assembleDebug                 # 出 Debug APK
./gradlew :app:compileDebugKotlin            # 最快校验，改完先跑这个
./gradlew :app:testDebugUnitTest             # JVM 单元测试
# 默认跳过的活体测试，按需显式打开：
./gradlew :app:testDebugUnitTest --tests "*WebLiveSmokeTest"    -Pnano.live.web
./gradlew :app:testDebugUnitTest --tests "*GreeToolLoopLiveTest" -Pnano.live.gree
```

- **硬性要求：改动完成后至少跑通 `./gradlew :app:compileDebugKotlin` 与
  `./gradlew :app:testDebugUnitTest`，并在汇报中给出实际执行的命令与结果。**
- 敏感配置（GREE API key、base url、model、auth scheme 等）走 `local.properties` 的 `GREE_*`，
  经 `app/build.gradle.kts` 注入 `BuildConfig`；`local.properties` 不入库（已 gitignore），
  严禁把真实值写进源码或资源。

---

## 4. 注释规范（中文）

### 4.1 文件头 KDoc（统一格式）

- 每个新建的 Kotlin 文件必须有文件头 KDoc，格式照抄现有写法：

```kotlin
/**
 * @author by jason-何伟杰，YYYY/M/D
 * des: 一句话职责
 */
```

- `des` 只写一句话职责，不写实现过程；日期填实际创建日期。

### 4.2 注释写作规则（硬规则）

- **新写的注释一律用简体中文**，一句到底，不堆修饰语，不写"本方法主要用于…"这类套话。
- 注释解释**为什么这么做 / 边界在哪 / 踩过什么坑**，**不复述代码在做什么**。
  违反示例：`// 遍历列表`、`// 判断是否为空`。
- 已有英文注释（来自 Google 样例或早期文件）**不强制全量翻译**；但**该文件被改动时，
  应在被改动的关键处补上中文说明**，至少保证改动点有中文"为什么"注释。
- 公开 API（public / internal 且被跨文件调用）用 KDoc 写清**参数含义与失败语义**
  （返回 null、抛异常、错误字符串分别代表什么）。
- 日志文本与用户可见文案用中文；日志 tag 沿用既有常量（如 `NanoGreeModel`、`NanoWebSearch`）。
- **禁止**把对话式说明、大段 TODO 清单、被注释掉的死代码留在仓库。

---

## 5. 必须写注释的复杂逻辑清单

满足下列任一触发条件时，**在实现前或函数内分段处写"为什么"注释**，并参考给出的现成范例：

- 函数体超过约 40 行；或嵌套 / 分支超过约 4 层；或含下列任一类语义。
- UI 组装函数（纯 @Composable 布局）**不写废话注释**，只写与逻辑相关的说明。

分类与范例（各自对应一个真实文件）：

1. **并发 / 取消与结构化并发**
   范例：`app/src/main/java/com/lyentech/nano/ui/chat/ChatViewModel.kt` 的 `send` 结尾用
   `withContext(NonCancellable)` 保证收尾一定跑完；
   `app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt` 的 `callbackFlow` +
   `awaitClose { call.cancel() }`，说明为什么不能用普通 `flow { }` 包 `execute()`。
   要求：涉及取消 / NonCancellable / callbackFlow 的位置必须写明"取消后会发生什么"。
2. **会话切换守卫（防串会话）**
   范例：`ChatViewModel` 内多处 `_currentId.value == / != conversationId` 比对。
   要求：任何在异步回调里写 `_timeline` / `_sending` / reducer 的地方，必须解释守卫理由。
3. **安全边界**
   范例：`app/src/main/java/com/lyentech/nano/agent/tools/WorkspaceTools.kt` 的 `resolveSafe`，
   用 `canonicalFile` 前缀比对，并**明确写出"不要一刀切拒绝 `..`"的理由**
   （`notes/sub/../plan.md` 合法，真正的边界只有前缀比对）。
4. **协议对齐与 id 配对**
   范例：`GreeChatModel.kt` 末尾 `// ---- TOOL CALLS ----` 段落注释；
   `app/src/main/java/com/lyentech/nano/http/gree/GreeToolDto.kt`（`toWireTools` / `toWireMessages`）。
   要求：涉及 `tool_calls` / `tool_call_id` / `role = "tool"` / id 重建的地方必须写清配对规则。
5. **流式增量解析**
   范例：`app/src/main/java/com/lyentech/nano/http/gree/SseParser.kt`、
   `app/src/main/java/com/lyentech/nano/http/gree/GreeChatStreamParser.kt`。
   要求：工具分片"只累积不外发"这类决策必须显式注释（否则容易被后来者改坏）。
6. **多引擎回退**
   范例：`app/src/main/java/com/lyentech/nano/agent/web/WebSearchClient.kt`（Bing CN → SearXNG → 百度）。
   要求：新增 / 调整回退链时注释每个引擎的作用与失败处理。
7. **跨库一致性**
   范例：`app/src/main/java/com/lyentech/nano/data/repo/ConversationRepository.kt`（两库靠 sessionId 对齐）。
8. **状态推导而非重复维护**
   范例：`ChatViewModel.currentProjectName`（由 `combine` 推导，不单独存状态）。
   要求：凡是"能由已有状态算出来"的值，禁止再存一份，并在注释里说明推导来源。
9. **单例与生命周期**
   范例：`app/src/main/java/com/lyentech/nano/data/repo/AgentServices.kt`、
   `app/src/main/java/com/lyentech/nano/data/db/NanoDatabase.kt`。
   要求：单例的创建 / 复用 / 关闭时机必须写清。

---

## 6. 复用与封装规范

### 6.1 核心硬规则

- **同一逻辑出现第 2 次就要开始抽，第 3 次必须抽。** 达不到"第 2 次"就抽属于过早抽象，不允许新增无第二个调用方的抽象层。
- **禁止复制粘贴改个名字。** 发现重复要么当场收敛，要么在他处留 TODO 并在本次提交里说明（不得静默复制）。
- 抽出来的东西**只暴露最小 API**：能用 `internal` 就不用 `public`，能用扩展函数 / `object` 单例 /
  装配点就不新增类。调用方**不得重复实现底层细节**。

### 6.2 已有复用入口（改动前先查它们，能在里面加就别另起炉灶）

- `agent/tools/AgentToolkit.kt`：工具装配**唯一入口**。
- `data/repo/AgentServices.kt`：`runner` / `sessionService` / `APP_NAME` / `USER_ID`。
- `data/db/NanoDatabase.kt` 的 `get`：应用数据库单例。
- `data/repo/SettingsRepository.kt`：偏好设置。
- `http/LiteRtLmModelStore.kt`：端侧模型查找 / 下载。
- `agent/tools/WorkspaceTools.kt`：`resolveSafe` / `toRelative` / `err`。
- `agent/tools/TodoTools.kt`：`TodoStore`（界面观察任务清单）。
- `agent/web/WebLog.kt`、`http/gree/GreeLog.kt`：日志注入接口 + `NoOp`，
  保证宿主 JVM 可测。
- `global/Exts.kt`：`Event.foldTextParts` / `Event.foldThoughtParts`。
- `ToolResult` 约定：`FunctionTool.ERROR_KEY`、`BaseTool.RESULT_KEY`。
- 参数读取扩展 `Map<String, Any?>.stringArg` / `stringListArg`（当前定义在 `TodoTools.kt` 与 `WebTools.kt`）。
- `truncateForModel` / `filterByDomains`（`agent/web`）。
- `ui/chat/ChatCommon.kt`（复用小组件与动画）、`ui/widget/ChatUi.kt`、
  `global/ScopedActivity.kt`、`global/Theme.kt` 的 `AppTheme`。

### 6.3 已知待收敛的重复（下次改到相关文件时一并收敛，不要新增同类重复）

- `agent/tools/WebTools.kt` 内两处 `private fun error(...)` 与 `WorkspaceTools.err` /
  `SubagentTools.err` 语义相同，应收敛为一个 `internal` 工具结果 helper
  （建议新建 `agent/tools/ToolResults.kt`，提供 `ok(...)` / `err(...)` 两个出口）。
- `stringArg` / `stringListArg` 建议从 `TodoTools.kt` / `WebTools.kt` 迁到上述公共文件。
- 收敛时**不得改变现有工具返回结构**（仍是 `Map`，key 仍为 `RESULT_KEY` / `ERROR_KEY`）。

### 6.4 其他规则

- 新建文件前先 Grep 是否已有同类能力，禁止重复造轮子。
- 同一类跨包复用逻辑放 `agent/web`、`http`、`global` 这类**可单测层**，不掺 Android 组件。
- 常量（超时、上限、TAG、目录名）集中在被复用处的 `companion`，不散落魔法数。

---

## 7. 编码分层约定

- Compose 屏只接状态与转发事件，业务在 ViewModel（`ui/chat` 已按此分层，示例屏除外）。
- `MutableStateFlow` 私有 `_x` + 对外 `StateFlow`（用 `asStateFlow()`）。
- 派生流用 `combine(...).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …)`；
  但**需要权威值时直接查库**（见 `ChatViewModel` 注释：stateIn 初值陷阱——冷启动 / 判断标题时
  不能读 `.value`）。
- 长生命周期对象（tools、sessionService）不挂在会被重建的 runner / ViewModel 上
  （见 `ChatViewModel.toolkit`、`AgentServices`）。
- `Dispatchers.IO` 做文件与网络；UI 更新切回 `Dispatchers.Main`。
- `runCatching` 只用于**可容忍失败的单点**（如单张图片读取失败跳过），
  不得吞掉必须上报的错误。
- 工具返回统一 `Map`（`RESULT_KEY` / `ERROR_KEY`），错误文案要让模型知道"下一步该怎么做"，
  不能只写"失败"。
- ADK 的 `@Tool` 注解 + KSP（`generatedTools()`）**只支持同步函数**；suspend 工具必须手写
  `FunctionTool` 子类并实现 `declaration()`（见 `WebTools.kt` 的说明）。
- Room 必须保持经典 `androidx.room`，且与 ADK 传递依赖同版本（2.8.4）；
  **不得引入 `androidx.room3`**（包名不同，会导致 `Unresolved reference 'room'`）。

---

## 8. 安全红线

结论来源见 `docs/ai-端侧密钥与数据安全方案.md`（本文不复述其长文）。以下为不可违反项：

- API key 只进 `Authorization` 头；**绝不**进日志、异常消息、`toString()`、界面文本。
- 错误响应体进日志前必须 `flatPreview` + `scrubSecret`（见 `GreeChatModel`）；
  异常消息只放状态行，不放 body。
- 不把密钥写进源码 / 资源或提交到版本库，本地一律用 `local.properties`。
- 模型文件与文件工具只能在应用私有沙箱内操作，路径必须过 `WorkspaceTools.resolveSafe`。
- **不放开** `usesCleartextTraffic`（`app/src/main/AndroidManifest.xml` 当前为 `true`）
  与信任 user CA（`app/src/main/res/xml/network_security_config.xml` 当前信任 `user` 证书）
  到 release —— 二者与 `cleartextTrafficPermitted="true"` 均属**待整改项**，
  **勿新增依赖这些开关才能工作的功能**。
- 新增网络调用必须设超时；SSE 读超时要放宽（参考 `GreeChatModel` 与联网工具的 `timeoutMs`）。
- 端侧模型下载不应跟随浮动 `main` 分支；引入校验时需做完整性校验
  （现状 `LiteRtLmModelStore` 的 `REVISION = "main"` 属待整改）。
- `allowBackup`（当前 `true`）与备份排除规则在引入 token 存储前必须处理。

---

## 9. 测试约定

- 核心逻辑写成**不依赖 `android.util.Log`** 的实现：注入 `WebLog` / `GreeLog`，测试传 `NoOp`
  （否则宿主 JVM 会撞 `Method ... not mocked`）。
- 解析 / 回退 / 配对逻辑优先写 JVM 单测；`org.json` 由 `testImplementation` 补齐，
  APK 仍使用系统实现。
- 需要真连外网或真连端点的测试用 `-Pnano.live.web` / `-Pnano.live.gree` + `assumeTrue` 门控，
  **默认不跑**（开关语义：非 "false" 即开启，因为 `-Pnano.live.web` 不带值时是空串）。
- **不给测试加 sleep 轮询**；不把 live 测试变成 CI 必跑。

---

## 10. 给 AI 代理的工作流程（简短清单）

1. 先读现有实现与本文列出的装配点，再动手。
2. 最小差异：不做无关重构，不做全量格式化。
3. 不新增依赖，除非必要且先确认镜像仓库可解析（注意阿里云顺序）。
4. 改注释 / 格式**不算**"顺手"改动：未经要求不动英文注释与其他模块。
5. 完成后跑 `compileDebugKotlin` + `testDebugUnitTest`，报告实际命令与结果。
6. 无法验证的（需真机 / 需密钥 / 需外网）要**明确说未验证**，不要声称通过。
7. 新代码注释用中文，遵循第 5、6 节的封装与复杂逻辑注释规则。
