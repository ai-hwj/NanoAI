# 端侧密钥与数据安全方案

> 适用范围：`com.lyentech.nano`（Android / Kotlin / OkHttp 4.12.0 / Compose / Google ADK `com.google.adk.kt`）。
> 本文只描述现状与设计，不含任何代码改动记录。

---

## 1. 结论先行

1. **厂商 API key 放在客户端，就不可能真正保密。** APK 是攻击者手里的文件，key 无论如何编码、加密、混淆，最终都要在请求头里以明文形态出现一次，谁能运行或反编译这个 APK，谁就能拿到它。
2. **唯一的根治办法是把 key 移到自有服务端（BFF / 网关）**，客户端只持有自己的会话 token。客户端加固只能把成本抬高，不能把问题消掉。
3. **"SSE 流式 = 明文" 是误解。** SSE 只是 HTTP 响应体上的应用层分块格式，只要 URL 是 `https`，整条流（含每个 chunk）都在 TLS 里。把"流式"和"明文"划等号是错的。
4. **真正让流量可被解开的是信任 `user` 根证书**（[network_security_config.xml:10](app/src/main/res/xml/network_security_config.xml#L10)）。这一条才是要害：只要它存在，装了 Charles/Fiddler 根证书的人（或任何能装根证书的攻击者）就能解开全部 TLS，注释里写明的目的也正是如此。**另外两个允许明文的开关（`android:usesCleartextTraffic="true"`（[AndroidManifest.xml:18](app/src/main/AndroidManifest.xml#L18)）、`cleartextTrafficPermitted="true"`（[network_security_config.xml:4](app/src/main/res/xml/network_security_config.xml#L4)））是另一类问题**——它们允许流量走 `http://` 而不被系统拦截，与 TLS 校验无关。**这三项目前同时成立**，但必须分清：只关掉后两项不会让 TLS 变安全，只有删掉 `user` CA 才会。
5. 当前仓库**尚无 git 仓库**（`git status` 失败），`local.properties` 已被 [`.gitignore`](.gitignore) 忽略，所以 key 还没进过版本库；但它**一定会进 APK**（见第 3 节）。
6. 威胁要分开看：**网络中间人可以防；逆向 APK 的人防不住；盗刷第三方必须靠服务端**。混在一起谈"安全"会得出错误结论。

---

## 2. 威胁模型

先分清要防谁。四类对手的能力差异极大，投入产出比完全不同。

| 对手 | 能拿到什么 | 为什么 | 客户端能不能防 |
|---|---|---|---|
| (a) 用户自己 / root 设备 / 抓包者 | 完整请求头（含 `Authorization`）、完整请求与响应体 | root 后可绕过应用沙箱；装了自定义根证书就能解密 TLS；`objection` / `frida` 能读进程内存 | **不能。只能提高成本。** |
| (b) 逆向 APK 的人 | `BuildConfig.GREE_API_KEY` 的字面值、`GREE_MODEL` 等硬编码默认值、全部业务逻辑 | APK 是离线文件；`GREE_API_KEY` 是 `BuildConfig` 常量，R8 不开启时几乎原样留在 `classes.dex` 里 | **不能。只能提高成本。** |
| (c) 网络中间人（同 Wi-Fi、运营商、恶意代理） | 无（TLS 校验正确时）；若信任 user CA 或允许明文，则等于 (a) | 取决于 TLS 是否真的被校验 | **可以做，而且必须做。** |
| (d) 撞库 / 盗刷的第三方 | 用泄漏的 key 打爆配额、产生账单；拿到他人对话 | 不需要接触 APK 或设备，只要 key 泄漏一次即可长期复用 | **完全不能。必须靠服务端密钥 + 鉴权 + 配额。** |

关键推论：

- 对 (a)(b)，"客户端保密"这个命题本身不成立，目标应从"保密"改为"抬高逆向与提取成本 + 让服务端能快速识别并撤销"。
- 对 (d)，客户端做什么都没用：厂商 key 一旦出现在客户端，它就是一个**共享的、无法按用户撤销的全局凭据**。这是当前架构最严重的问题。
- 因此"大厂怎么做"的答案是统一的：**客户端不直连模型厂商，走自家网关**。

---

## 3. 两个问题的定性

### 3.1 「API key 暴露在请求头」：真问题，Critical

现状是 key 在**两个**地方同时存在：

**(1) 编进 APK。** [app/build.gradle.kts:47](app/build.gradle.kts#L47) 把 `GREE_API_KEY` 写进 `BuildConfig`：

```kotlin
buildConfigField("String", "GREE_API_KEY", "\"${localOr("GREE_API_KEY", "")}\"")
```

`localOr` 的解析顺序是 `local.properties` -> 环境变量 -> 字面默认值（[app/build.gradle.kts:11-13](app/build.gradle.kts#L11)）。注意：`local.properties` 被 gitignore **只意味着它不进 git**，不意味着它不进产物——`buildConfigField` 的结果是编译进 `BuildConfig.class` 的 `static final String` 常量。

**(2) 放进请求头。** [GreeChatModel.kt:51](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L51) 的 `apiKey` 默认取 `BuildConfig.GREE_API_KEY`，在 [L136-138](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L136) 被注入 `Authorization`：

```kotlin
val builder = Request.Builder().url(baseUrl + chatPath).post(body)
authHeader()?.let { builder.header(AUTHORIZATION, it) }   // L137
```

`authHeader()`（[L142-145](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L142)）拼出 `Bearer <key>`。任何拿到 APK 或抓到一次包的人，都同时拿到：**厂商 endpoint、模型名、以及长期有效的全局 key**。这不是"理论风险"，是最低成本的盗刷入口。

补充事实：`app/build.gradle.kts` 里另外 4 个 `buildConfigField`（[L48-51](app/build.gradle.kts#L48)）默认值是**硬编码的生产地址、路径、模型名和认证方案**（`https://baitong-aiw.gree.com/openapi/cllm`、`/chat/completions`、`deepseek-v4-flash`、`Bearer`）。这些不是密钥，但一起泄漏等于把攻击面完整交给对方——只有 key 需要猜。

**定性：Critical，且只有服务端方案能闭合。**

### 3.2 「数据是明文」：需要澄清，当前实现的真实风险在别处

先拆开两件事：

- **传输层**：只要 URL 是 `https`，TCP 之上就是 TLS，请求体和响应体（包括 SSE 每一个 chunk）都是密文。SSE 的 `data: {...}` 分块是**应用层**格式，和加密无关。逐块返回不等于逐块明文。
- **真正的明文风险**来自三类配置，但**它们不是同一个问题，修法也不同**：

| 配置 | 位置 | 效果 | 关掉它能解决什么 |
|---|---|---|---|
| `<certificates src="user"/>` | [network_security_config.xml:10](app/src/main/res/xml/network_security_config.xml#L10) | **信任用户安装的根证书** → 抓包工具能伪装成 `baitong-aiw.gree.com` 解密全部 TLS | **这一条才是"内容明文"的根因**；删掉它，TLS 校验才真正生效 |
| `android:usesCleartextTraffic="true"` | [AndroidManifest.xml:18](app/src/main/AndroidManifest.xml#L18) | 允许全局走 `http://`，任何 http URL 都不被系统拦截 | 只消除"明文 HTTP"这一类风险，**不改变 TLS 是否可被解密** |
| `base-config cleartextTrafficPermitted="true"` | [network_security_config.xml:4](app/src/main/res/xml/network_security_config.xml#L4) | 同上，且覆盖域名级配置 | 同上 |

**要害是第一条。** 文件里的注释把它说得很直白：不信任 user CA 就会报错 "configure your browser or application to trust the Charles"。换句话说，**当前配置的目的就是让 TLS 可被中间人解开**。这不只是"开发方便"，它是把攻击者 (c) 变成了攻击者 (a)。

后两条与 TLS 无关：它们决定"能不能走不加密的 http"，而不是"https 会不会被解开"。**因此只删掉这两条并不能阻止抓包**——这是整改时最容易搞错的地方（见第 6.1 节 (2) 的实际做法，三条要一起改）。

顺带修正一个常见说法上的轻微不准确：`base-config` 不是"取消 Android 9 的 https 强制"的唯一方式——Android 9+ 的默认 `base-config` 本身就是 `cleartextTrafficPermitted="false"`，这里显式置 `true` 是**主动放开**。

**定性：只要 `user` CA 被信任，"数据明文"就成立；删掉它之后，HTTPS 下的 SSE 流本身是安全的。允许明文的两个开关是独立的、另一类问题，要单独修。**

### 3.3 现有代码的卫生状况（值得保留的部分）

需要明确指出：**网络层之外的代码在日志卫生上做得很好，不要在这次整改中破坏它。**

- [GreeChatModel.kt:39-40](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L39) 的类注释声明 key "never reaches a log line, an exception message or a `toString()`"；实现与之一致。
- [L154-159](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L154) 的 `requireSuccessfulBody()` **只打状态行，不打 body**，理由是网关/代理可能把请求体（含凭据）回显在错误页里。这是正确决定。
- [L186-190](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L186) 的 `toTransportFailure()` 只由 `message` 重建 `IOException`，不转发原始异常对象。
- DTO / 解析层是纯函数且**完全没有日志**：[GreeChatDto.kt](app/src/main/java/com/lyentech/nano/http/gree/GreeChatDto.kt)（注释 L13-15 明确"这里不做网络、线程和日志"）、[GreeChatStreamParser.kt](app/src/main/java/com/lyentech/nano/http/gree/GreeChatStreamParser.kt)、[SseParser.kt](app/src/main/java/com/lyentech/nano/http/gree/SseParser.kt)。
- UI 侧唯一泄漏面是 [ApiChatActivity.kt:60](app/src/main/java/com/lyentech/nano/ui/ApiChatActivity.kt#L60)，用 `endpointHost()`（[L193-194](app/src/main/java/com/lyentech/nano/ui/ApiChatActivity.kt#L193)）**只展示 host**，不回显 key 和路径。设计是刻意的。
- 全仓日志点只有三处：`GreeChatModel.kt:157`（仅状态码，tag `NanoGreeModel`）、`RtLlmChatActivity.kt:114`（`Log.v("TAG", e.message)`，用于下载失败）、`LiteRtLmModelStore.kt:60`（打印下载 URL，非密钥）。

结论：**当前版本没有"日志泄漏 key"的 bug**；问题集中在"key 本来就不该在端上"和"TLS 被主动放开"。整改时保留上述卫生纪律。

---

## 4. 目标架构（有网关）

### 4.1 数据流

```
                 (今天)
  Android App ───────── Authorization: Bearer <GREE_API_KEY> ────────►  baitong-aiw.gree.com
   [key 在 APK 里]                                                       /openapi/cllm/chat/completions
        │
        └─ key 可被提取、无法按用户撤销、无配额、无审计

                 (目标)
  Android App ── Authorization: Bearer <app-session-token> ──►  自有 BFF / 网关
   [只持有自己的 token]                                              │  ├─ 校验 app token，解析出 userId
                                                                    │  ├─ 注入厂商密钥（仅此处可见）
                                                                    │  ├─ 配额 / 限流 / 熔断
                                                                    │  ├─ 审计日志（默认脱敏）
                                                                    │  ├─ 内容安全 / 敏感词
                                                                    │  └─ 多厂商路由与降级
                                                                    │
                                                                    └──►  厂商 API（SSE 透传）
```

### 4.2 网关职责

| 职责 | 具体做什么 | 为什么必须在服务端 |
|---|---|---|
| 密钥注入 | 出站请求由网关补上厂商 `Authorization` / 认证头 | 这是本方案的唯一目的：key 不出服务端边界 |
| 鉴权与用户绑定 | 校验 app session token，映射到 `userId` / 设备 / 会话 | 让"撤销某个人"成为可能；厂商 key 是全局的，做不到 |
| 配额与限流 | 按用户 / 按日 token 数、QPS、并发流数 | 盗刷的经济损失在这里被限死 |
| 审计日志 | 记录 `userId + 时间 + 模型 + token 数 + 状态`，正文默认不落库 | 事后追溯；同时避免日志变成新的泄漏源 |
| 内容安全 | 入站/出站敏感词与合规检查 | 客户端可被绕过 |
| 多厂商路由与降级 | 主厂商失败切备用；模型名映射 | 客户端不需要知道厂商是谁 |
| 缓存 | 相同 prompt 的短时缓存（可选） | 省成本、降延迟 |

### 4.3 接口契约（提案）

**提案**（当前代码中不存在，需新增）：客户端调用的 endpoint 从 `/chat/completions` 变为网关自己的路径，例如 `POST /v1/chat/completions`，请求体保持 **OpenAI 兼容格式不变**——因为 [GreeChatDto.kt:29](app/src/main/java/com/lyentech/nano/http/gree/GreeChatDto.kt#L29) 的 `toChatRequestJson(stream)` 已经在产出 OpenAI 兼容 schema，网关可以直接透传或改写 `model` 字段。

差异只在两处：

| 项 | 现在 | 目标 |
|---|---|---|
| `Authorization` | `Bearer <GREE_API_KEY>`（[GreeChatModel.kt:144](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L144)） | `Bearer <app-session-token>` |
| `baseUrl` / `chatPath` | `BuildConfig.GREE_BASE_URL` + `GREE_CHAT_PATH` | 指向自建域名 |

**这个改动在客户端是配置级别的**：`GreeChatModel` 的 `baseUrl` / `chatPath` / `apiKey` / `authScheme` / `model` 全是构造参数（[L51-56](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L51)），[ApiChatActivity.kt:83](app/src/main/java/com/lyentech/nano/ui/ApiChatActivity.kt#L83) 用 `GreeChatModel()` 默认值构造。改成注入网关地址 + 用户 token 即可，**传输与解析层不需要动**。

### 4.4 网关必须做 SSE 透传

这是最容易做错、且做错就毁掉产品体验的一点。

**要点：**

- **不要缓冲整个响应。** 网关必须边收边转、逐 chunk flush。若使用 nginx：`proxy_buffering off;`（同时建议 `proxy_cache off;`、`X-Accel-Buffering: no`）。
- **不要给上游设过短的读超时**，否则长回答会在网关侧被掐断。
- **尊重客户端断连取消。** 客户端取消时（当前实现见 [GreeChatModel.kt:103](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L103) 的 `awaitClose { call.cancel() }`），网关必须立刻取消上游请求并释放连接，否则会持续产生厂商侧计费。
- **保活。** 上游长时间不吐 token 时，中间设备（运营商 NAT、负载均衡）可能掐断空闲连接。发送 SSE 注释行 `: keep-alive` 心跳只是保活手段之一；**不能用注入伪造 `data:` 数据行来保活**，那会破坏客户端的解析契约。
- **响应头必须原样透传或正确设置**：`Content-Type: text/event-stream`、`Cache-Control: no-cache`、`Connection: keep-alive`；若网关做了 gzip，注意不要对 SSE 做会引入缓冲的压缩。
- 客户端侧已具备的处理能力无需改动：[GreeChatModel.kt:78-89](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L78) 用 `call.execute().use{}` + `body.source().readUtf8Line()` 逐行读，[SseParser.kt:28-38](app/src/main/java/com/lyentech/nano/http/gree/SseParser.kt#L28) 的 `Sse.Stream.next()` 逐行取 `data:` 负载并识别 `[DONE]`。透传只要保持"一行一个 `data: <json>`、结尾 `data: [DONE]`"即可无缝兼容。

### 4.5 为什么这层是可做的：已有测试缝

[GreeChatParsingTest.kt](app/src/test/java/com/lyentech/nano/http/gree/GreeChatParsingTest.kt) 证明解析层已与网络层解耦（纯函数 + 硬编码 fixture，不碰网络）。这意味着：

- 换 endpoint、换 token 形态时，解析行为有回归保护；
- 网关返回的 body 只要符合 OpenAI 兼容 schema，现有测试仍然有效；
- 反过来，若厂商方言变化，也可以在网关内做方言转换，客户端一行不改。

---

## 5. 密钥托管（服务端侧）

### 5.1 分级做法

| 级别 | 做法 | 适用 | 风险 |
|---|---|---|---|
| L0 | 明文写在代码库 / 提交进 git | **禁止** | 全员可读；一旦推送永久留在历史里 |
| L1 | 明文写在构建脚本 / CI 变量 | **禁止** | 见 [app/build.gradle.kts:47](app/build.gradle.kts#L47) 的现状说明：`buildConfigField` 的结果进 APK |
| L2 | 环境变量注入到网关进程 | 最低可接受 | 读 `/proc/<pid>/environ`、崩溃 dump、容器 inspect 可能带出 |
| L3 | KMS / 密钥管理服务 + 信封加密 | **推荐** | 如 AWS KMS、阿里云 KMS、GCP KMS、HashiCorp Vault；密钥只在网关内存中按需解密 |
| L4 | HSM / 托管密钥 | 合规要求高时 | 成本与接入复杂度高 |

**L3 的形态**：KMS 里存一份数据密钥（KEK），加密后的厂商密钥（DEK 密文）可以随配置走；网关启动或首次调用时调 KMS 解密，得到明文只放在进程内存；进程重启后重新解密。优势是**明文永不落盘、永不进配置库**，轮换时只需重新加密并重启。

### 5.2 密钥存放位置对比

| 存放位置 | 谁能读到 | 轮换难度 | 结论 |
|---|---|---|---|
| 明文在 repo | 所有能读仓库的人、所有历史版本、CI 日志 | 极难（历史清不掉，需吊销重发） | 禁止 |
| 明文在构建脚本 / `local.properties` 编进 APK | **任何拿到 APK 的人**（反编译即可），加上所有能读构建机的人 | 难（要重新发版，且旧版仍带旧 key） | 现状，禁止 |
| 环境变量（仅网关进程） | 能 exec 进容器/主机的人、进程 dump、误打的启动日志 | 中（改 env + 重启，可滚动） | 可接受下限 |
| KMS / Vault（信封加密） | 只有被授权的服务身份；审计可查每一次解密 | 易（改密文 + 重启，且有版本与审计） | 推荐 |

**本项目特有的残留泄漏面（必须清理，否则"轮换 key"会白做）：**

当前环境的 key 不只是"在 `local.properties` 里"这一处。它已经以明文出现在**构建产物和构建缓存**中：

- `app/build/generated/source/buildConfig/{debug,release}/com/lyentech/nano/BuildConfig.java`（`static final String` 常量）
- 对应的 `.class` 与 `.dex`（`app/build/intermediates/**`，含 `mergeDexRelease/classes*.dex`）
- **Gradle 执行历史缓存 `.gradle/9.6.1/executionHistory/executionHistory.bin`**

推论有三条：① 任何拿到产物或构建机文件的人都能提取它，**不需要反编译 APK**；② CI 若缓存了 `app/build/` 或 `.gradle/`，key 会随缓存被长期保存并可能被恢复；③ **因此轮换 key 时必须同时清理本机与 CI 的 `build/`、`.gradle/` 缓存和旧的构建归档**，否则旧 key 仍在新机器上活着。建议把这两类目录纳入 CI 的"不缓存/定期清理"策略。

清理范围要注意"凡是保存过任务输入的目录都算"：除 `executionHistory.bin` 外，`.gradle/configuration-cache/` 和全局 Gradle build cache 同样可能留存快照。**判断标准是看它是否可能存放过该次构建的输入，而不是只看文件名。**

### 5.3 运营纪律

- 密钥**只存在网关进程内存**，按需解密，**不落盘、不打印、不进异常消息**。
- 出站请求日志里 `Authorization` 必须被替换为 `Bearer ***`，禁止原样记录。
- 网关的**上行**请求体（用户对话）与**下行**响应体默认不落库；如需采样，走脱敏管道。
- 密钥轮换要有节奏：至少支持**双 key 并存**的过渡期（旧 key 仍可用），避免轮换即宕机。
- 撤销路径必须存在：怀疑泄漏时能立刻在厂商侧重建 key 并更新 KMS，**客户端无需发版**——这一点正是网关相对"密钥内嵌"的核心优势。

---

## 6. 客户端侧加固

以下措施**不能替代网关**，但过渡期和长期都应做，目的是抬高成本、缩小暴露面。

### 6.1 立刻做

**(1) 删掉 `android:usesCleartextTraffic="true"`。**

位置：[AndroidManifest.xml:18](app/src/main/AndroidManifest.xml#L18)。删掉后由 `networkSecurityConfig` 决定策略。代价：零（当前所有真实 endpoint 都是 https）。

**(2) 收紧 `network_security_config.xml`，并把抓包能力隔离到 debug 构建。**

现状问题见 [network_security_config.xml:4-12](app/src/main/res/xml/network_security_config.xml#L4)：`cleartextTrafficPermitted="true"` + 信任 `user` CA。

做法：**release / debug 各一份 config**，用 source set 切分——

- `app/src/release/res/xml/network_security_config.xml`：默认禁止明文，**只信 system CA**。
- `app/src/debug/res/xml/network_security_config.xml`：保留抓包能力（`cleartextTrafficPermitted="true"` + `user` CA）。
- `app/src/main/...` 里不再保留这份文件，或保留 release 版本并由 debug source set 覆盖。

**提案**（release 版本，示意，非实际文件）：

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors><certificates src="system"/></trust-anchors>
    </base-config>
</network-security-config>
```

要点：`trust-anchors` **只列 system**，不列 user。**这是让 TLS 真正生效的关键一步，也是本节唯一能阻止抓包的一项**——只把 `cleartextTrafficPermitted` 改成 `false` 并不够。代价：debug 抓包仍需装 Charles 证书（debug 版本照旧可用），但**release 包抓不了**，这是期望行为。

顺带说明：`cleartextTrafficPermitted="false"` 与删掉 manifest 里的 `usesCleartextTraffic` 是**同一类措施**（禁止明文 http），做它们是因为它们本身就不该开着，而不是因为它们能防抓包。两个都改，加上这里删掉 `user` CA，才算把第 3.2 节表格里的三项都收干净。

**(3) 关闭或精确约束自动备份。**

`android:allowBackup="true"`（[AndroidManifest.xml:9](app/src/main/AndroidManifest.xml#L9)），而 [backup_rules.xml](app/src/main/res/xml/backup_rules.xml) 与 [data_extraction_rules.xml](app/src/main/res/xml/data_extraction_rules.xml) 都还是未修改的 `<include>/<exclude>` TODO 模板——**当前没有任何东西被排除在云备份之外**。

两个选择：

- 简单：`android:allowBackup="false"`（代价：用户换机丢本地数据）。
- 精细：保留 `allowBackup="true"`，在 `data_extraction_rules.xml` 里对 token / 会话 / cookie 所在的 `sharedpref`、`file`、`database` 域做精确 `<exclude>`。**只要引入用户 token 存储，就至少要把承载 token 的 sharedpref 目录排除**，否则 token 会随云备份离开设备。

**(4) 给 OkHttp 加超时（连接/读/写）。**

现状：[GreeChatModel.kt:207](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L207) 是 `val DEFAULT_CLIENT: OkHttpClient = OkHttpClient()`——**无拦截器、无证书固定、没有配置任何 connect/read/write 超时**。OkHttp 默认 `readTimeout` 是 10 秒。

**流式场景下 10 秒读超时是致命的**：模型思考或长回答时，两个 chunk 之间可能超过 10 秒，连接会被判超时掐断，用户看到"回答到一半就失败"。

**提案**（示意）：

```kotlin
val DEFAULT_CLIENT = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS)   // 0 = 不限；SSE 长连接必须放宽
    .build()
```

对 `readTimeout(0)` 的注记：它意味着**不再有读超时保护**，所以真正的兜底应放在网关侧（网关给上游设合理超时并主动结束流），客户端靠 [GreeChatModel.kt:103](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L103) 的取消机制在用户离开页面时终止 `call`。若非流式请求（[L108-123](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L108)）也用同一个 client，也可以选择为短连接单独建 client。

**(5) release 开启 R8 + `shrinkResources`，并写好 keep 规则。**

现状：[app/build.gradle.kts:54-60](app/build.gradle.kts#L54) 是 `release { optimization { enable = false } }`。项目里**不存在任何 proguard/R8 keep 文件**（全仓 `**/*.pro` 无匹配）。

**先纠正一个容易搞错的机制**（已对照 AGP 9.3.1 的 DSL 与其 KDoc 核实）：

`optimization.enable` 的官方说明是 "Specifies whether to enable **code shrinking and resource optimization**"，并且注明 "By default, value is false **to avoid runtime issue**. When enabled the Android plugin uses R8 for optimization."。也就是说，**这个块确实与代码压缩相关，不是一个孤立的"优化 pass"**；本项目显式写 `enable = false`，意图正是**主动关闭它附带的风险行为**（注释语境就是怕 R8 出运行时问题）。

同时必须分清另一组开关：`isMinifyEnabled` 与 `isShrinkResources` 是 `BuildType` 上**独立的**属性（AGP 9.3.1 仍然存在），且**默认都是 `false`**，上面的 `optimization` 块不会替它们赋值。

所以准确的结论是：**当前 release 包既不混淆也不裁剪，`optimization { enable = false }` 和两个未设置的开关是两件事、都在起作用。** 整改时不要把二者混为一谈，也不要把 `optimization.enable` 当成"混淆总开关"——建议的做法是**显式设置 `isMinifyEnabled` / `isShrinkResources`，并单独实测 `optimization.enable` 在你们 AGP 版本下的行为**（它的语义在 AGP 各版本间有过调整，以实际构建结果为准，不要靠推测）。

**提案**（示意）：

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    // optimization.enable 与上面两个开关的关系在 AGP 各版本间有调整，
    // 建议改完后实机验证混淆与裁剪是否真的生效，而不是只看配置。
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
}
```

keep 规则必须覆盖本项目实际用到的反射/代码生成面：

- **kotlinx-serialization**（`kotlinx_serialization = 1.11.0`）：`@Serializable` 类的 `Companion` 与生成的 `$$serializer` 需要 keep。
- **Koin**（`koin 4.0.0`）：`inline`/`single` 走反射时类名与构造器不能被裁掉。
- **Google ADK**（`adk 1.2.0`）：项目用了 KSP 注解处理器（[app/build.gradle.kts:126](app/build.gradle.kts#L126) 的 `ksp(libs.adk.processor)`），生成的代码若被 `isShrinkResources` 判为未引用会被删除。
- Compose、`filekit`、`coil3` 通常自带 consumer rules，但需要实机回归。

**必须说清楚：混淆只是提高逆向成本，不是保护。** 反编译者仍能通过字符串常量（比如 endpoint、`deepseek-v4-flash`）和调用链定位到取 key 的位置。

**(6) 端侧模型下载路径也是一个攻击面（本方案此前遗漏，需一并处理）。**

[LiteRtLmModelStore.kt:60](app/src/main/java/com/lyentech/nano/http/LiteRtLmModelStore.kt#L60) 用**裸 `HttpURLConnection`** 从 HuggingFace 拉取 `gemma-4-E2B-it.litertlm`（[L109](app/src/main/java/com/lyentech/nano/http/LiteRtLmModelStore.kt#L109) 的 `downloadUrl()`），且 `REVISION` 固定为 `main`——**上游内容可变**。它不受 [GreeChatModel.kt:207](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L207) 那个 OkHttp client 管辖，因此**共享不到任何 OkHttp 侧的加固**（超时它自己设了，但没有 pinning、没有拦截器）。

风险组合是明确的：**信任 `user` CA（[network_security_config.xml:10](app/src/main/res/xml/network_security_config.xml#L10)）+ 从浮动 `main` 分支下载可执行的模型文件** = 中间人可替换模型权重，且本地无完整性校验。这是一条真实的数据投毒/供应链路径，不只是"下载失败"。

要点：① 把 `REVISION` 从 `main`（[L22](app/src/main/java/com/lyentech/nano/http/LiteRtLmModelStore.kt#L22)）改成**固定的 commit/标签**，并在下载后**校验已知哈希**（这是最有效、成本最低的一步，且与是否被中间人无关）；② 若保留裸 `HttpURLConnection`，至少显式只允许 https，并考虑改走已加固的 OkHttp client；③ 说明该模型文件落在 `getExternalFilesDir()`，其回退实现见 [L106-107](app/src/main/java/com/lyentech/nano/http/LiteRtLmModelStore.kt#L106)——外部存储目录在旧版本 Android 上可被其他应用读取，需确认是否可接受。

### 6.2 有余力做

**(1) 证书固定（certificate pinning）。**

在 OkHttp 上通过 `CertificatePinner` 实现，固定**中间证书或叶子证书的公钥哈希**（`sha256/...`），而不是整张证书。

**防的是什么**：中间人（威胁 (c)）。它**不防**逆向（(b)）——pin 在 APK 里，可被 patch 掉。

**运维代价是真实的**：证书轮换若不预先更新 pin，会**直接炸掉线上所有旧版本客户端**（无法回滚，只能等用户升级）。因此必须：

- 至少内置 **一个备用 pin**（覆盖即将签发的证书/备用 CA）；
- 更稳的做法是**远程配置下发 pin 列表**，把轮换决定权留在服务端；
- 灰度发布，先在内部版本观察 pin 失败率。

**(2) 用 `EncryptedSharedPreferences` / Android Keystore 存用户自己的 token。**

关键区分：

- **该存的是"用户自己的 app session token"**——它由服务端签发、可撤销、可过期，放在 Keystore 加密的存储里是合理的。
- **不该、也没法用 Keystore 保护厂商 key。** 常见误区是"把 `GREE_API_KEY` 用 Keystore 加密后再解密使用就安全了"。**这是错的。** Keystore 保护的是"密钥的存储"，但不保护"使用密钥的代码"：解密逻辑就在同一个 APK 里，攻击者在同一台设备上运行同一段代码即可解出明文。Keystore 让攻击者多花几分钟，不会让 key 变成秘密。**唯一能保护厂商 key 的地方是不存在这个 APK 里——即服务端。**

**(3) 防调试 / 反 Frida / root 检测。**

**明确标注：收益低、维护成本高、对决心强的人无效。建议优先级最低。**

理由：这些手段全部在客户端进程内实现，攻击者可以 patch、hook、或直接在未 root 的干净设备上做静态分析。root 检测永远追不上新的隐藏方案，且容易误伤正常用户（一些厂商 ROM、企业环境）。**不要把安全感建立在这上面**；它的正确定位是"抬高脚本小子的门槛"，而不是"安全边界"。

---

## 7. 用户数据出境与合规

事实层面：**应用内 AI 生成的对话内容会被发往第三方厂商**（当前是 `https://baitong-aiw.gree.com`，由 [app/build.gradle.kts:48](app/build.gradle.kts#L48) 决定）。

需要在以下位置如实体现：

- **隐私政策**：明确写出"用户输入与 AI 回复会传输至第三方模型服务商以生成结果"，说明传输目的、数据类型、保留策略、以及是否会用于训练（需要与厂商确认）。
- **合规评估**：企业内场景与出海场景需要单独看。国内涉及《个人信息保护法》下的个人信息处理告知同意与数据出境评估；跨境传输需确认厂商的部署区域与数据处理协议（DPA）。
- **网关带来的合规优势**：接入网关后，可以在出站前做**脱敏**（手机号、身份证、邮箱、内网地址等正则/规则替换），并让**审计日志默认脱敏**——日志里只留 `userId + 长度 + 模型 + 耗时 + 状态`，不留正文。这比客户端直连容易得多，因为只有一个出口要做策略。
- 需注意：[backup_rules.xml](app/src/main/res/xml/backup_rules.xml) / [data_extraction_rules.xml](app/src/main/res/xml/data_extraction_rules.xml) 目前什么都没排除，会话数据可能随云备份离开设备（见 6.1(3)）。

---

## 8. 分阶段落地路径

| 阶段 | 动作 | 产出 | 风险残留 |
|---|---|---|---|
| **阶段 0（今天就做，半天量级）** | ① 从 `AndroidManifest.xml` 删除 `usesCleartextTraffic`（[L18](app/src/main/AndroidManifest.xml#L18)）；② release 版 `network_security_config.xml` 默认禁明文、**移除 `user` CA**（[L4-10](app/src/main/res/xml/network_security_config.xml#L4)），抓包配置移入 debug source set；③ `allowBackup` 收紧（[L9](app/src/main/AndroidManifest.xml#L9)）或精确 exclude；④ 给 `DEFAULT_CLIENT` 加超时（[GreeChatModel.kt:207](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L207)），`readTimeout` 放宽；⑤ **把模型下载的 `REVISION` 固定到具体 commit 并校验哈希**（[LiteRtLmModelStore.kt:22](app/src/main/java/com/lyentech/nano/http/LiteRtLmModelStore.kt#L22)） | 三个配置文件的修改 + 一个 OkHttp.Builder 改动 + 下载固定；release 包不再可被装根证书的代理解密，模型文件不可被替换 | 厂商 key 仍在 APK 里，盗刷风险不变 |
| **阶段 1（过渡，客户端加固）** | ① release 开 R8 + `shrinkResources`（[app/build.gradle.kts:54-60](app/build.gradle.kts#L54)），补 kotlinx-serialization / Koin / ADK 的 keep 规则；② 引入用户 token 存储（Keystore + `EncryptedSharedPreferences`）；③ 评估证书固定（含备用 pin 或远程下发）；④ **在厂商侧轮换已泄漏的 key，并清理本机/CI 的 `build/` 与 `.gradle/` 缓存**（见 5.2） | 可用的 release 构建配置 + token 存储抽象；逆向成本上升；残留 key 副本被清除 | 仍是客户端直连，key 仍是全局共享、不可按用户撤销 |
| **阶段 2（接入网关）** | ① 部署 BFF/网关，实现密钥注入、token 鉴权、配额、审计、SSE 透传（`proxy_buffering off`）；② 客户端把 `baseUrl`/`chatPath`/`apiKey`（即 `GreeChatModel` 构造参数，[L51-55](app/src/main/java/com/lyentech/nano/http/gree/GreeChatModel.kt#L51)）切到网关，登录后拿 session token；③ 灰度切换 + 保留回退开关 | 厂商 key 从 APK 中消失；可撤销、可限额、可审计 | 网关成为新的单点与延迟来源；SSE 透传配置错误会毁掉流式体验；网关上仍需临时用环境变量存 key |
| **阶段 3（密钥托管 + 固定 + 审计闭环）** | ① key 迁到 KMS/Vault（信封加密），只在网关内存解密、不落盘不打印；② 启用证书固定（含轮换预案）；③ 审计与告警：异常用量、pin 失败率、上游错误率；④ 定期轮换演练 | 可轮换、可审计、可告警的密钥生命周期 | 运维复杂度上升；证书轮换流程若不演练仍可能炸线上 |

阶段 0 与阶段 1 可以并行推进；**阶段 2 是唯一能消除 Critical 风险的动作**，其余都是缓解。

---

## 9. 常见误区

| 误区 | 为什么错 | 谁仍然能拿到 |
|---|---|---|
| "key 放 `local.properties` 就安全了" | gitignore 只保证不进版本库，不保证不进产物；`buildConfigField` 会把它编进 `BuildConfig` 常量（[app/build.gradle.kts:47](app/build.gradle.kts#L47)） | 任何反编译 APK 的人：`strings classes.dex` 或直接读 `BuildConfig` |
| "用 Android Keystore 加密 key 就安全了" | 解密代码和 key 密文在同一个 APK 里；攻击者在同一台设备运行同一段代码即可解出明文 | 任何能运行该 APK（模拟器即可）或 hook 解密函数的人 |
| "代码混淆了就安全了" | 混淆只改名字，不改常量；endpoint、`deepseek-v4-flash` 这类字符串仍在常量池里，调用链仍可定位 | 任何愿意花时间逆混淆的人 |
| "HTTPS 了所以数据不会明文" | **在信任 `user` CA 的前提下这句话不成立**——装一次抓包根证书就能解密全部 TLS（[network_security_config.xml:10](app/src/main/res/xml/network_security_config.xml#L10)） | 能在设备上安装根证书的人（抓包者、root 用户） |
| "关掉 `usesCleartextTraffic` 就安全了" | 它只禁止 `http://`，**不改变 TLS 能否被解密**；真正的开关是 [network_security_config.xml:10](app/src/main/res/xml/network_security_config.xml#L10) 的 `user` CA | 任何装了抓包根证书的人——只关明文开关时，他依旧能解开 https |
| "用 SSE 所以是明文" | SSE 是应用层分块格式，与加密无关；`https` 下每个 chunk 都在 TLS 里 | 无（前提是真的校验了证书） |
| "加签名校验 / 防调试就够了" | 这些校验自身在客户端，可被 patch 或绕过；它们只提高脚本小子的门槛 | 任何会 patch APK 或使用 Frida 的人 |
| "把 key 硬编码成字节数组再拼接就安全" | 这属于编码，不是加密；运行时的内存里必然出现完整字符串，静态分析也很容易还原 | 静态分析者、内存 dump 者、任何 hook 请求构造点的人 |

**一句话总结**：以上每一条，攻击者都需要"拿到 APK 或控制设备"；而**服务端持有密钥时，攻击者拿到 APK 也一无所获**。这就是为什么答案是网关，而不是更聪明的客户端技巧。

---

## 附：本文中标注为"提案"的内容

以下在项目当前**不存在**，仅为设计建议：网关 endpoint 与接口契约（第 4.3 节）、网关本身、release/debug 双份 `network_security_config.xml`、OkHttp 超时构建器、R8 配置与 keep 规则、证书固定、用户 token 存储。本文未修改任何现有文件。
