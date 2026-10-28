package com.lyentech.nano.agent.tools

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.lyentech.nano.agent.web.WebFetchClient
import com.lyentech.nano.agent.web.WebSearchClient
import com.lyentech.nano.agent.web.filterByDomains
import com.lyentech.nano.agent.web.truncateForModel
import java.time.LocalDate

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 联网工具（ADK 版）。
 *
 * 移植自 IDEA 插件 `baitong-intellij`（Koog 框架）的 WebSearchTool / WebFetchTool。
 * 插件侧继承的是 Koog 的 `Tool`，这里改成继承 ADK 的 [FunctionTool]：两者的
 * `execute` 都是 suspend，所以核心的网络与解析逻辑可以原样搬运，只换外壳。
 *
 * 为什么不用 `@Tool` 注解 + KSP 生成：注解处理器只支持同步函数，而这两个工具本质是
 * 网络调用，必须挂起执行，所以手写 [FunctionTool] 子类。
 *
 * 与插件版的差异（有意为之）：
 *  - 去掉了 HookManager（插件用它在工具调用前后跑用户脚本，Android 端没有这套设施）
 *  - 去掉了 token 精确截断（端上没有 tokenizer），改用等量级的字符数截断
 *  - 不搬迁 WebParserTool：那里硬编码了一个阿里云百炼的 API key，且功能与 WebFetch 重叠
 */
class WebSearchTool(
    private val client: WebSearchClient = WebSearchClient(),
) : FunctionTool(
    name = NAME,
    description = description(),
) {

    override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
        val query = args.stringArg("query")
            ?: return error("缺少 query 参数")

        val response = client.search(query)
        if (response.results.isEmpty()) {
            return error("没有找到与「$query」相关的结果（搜索引擎可能暂时不可用或被限流，可稍后重试或换个说法）")
        }

        val allowed = args.stringListArg("allowed_domains")
        val blocked = args.stringListArg("blocked_domains")
        val filtered = response.results.filterByDomains(allowed, blocked)

        if (filtered.isEmpty()) {
            return error("搜索结果都被域名过滤规则排除了（未过滤前有 ${response.results.size} 条）")
        }

        // 10 条上限与插件一致：再多对模型判断帮助有限，反而挤占上下文。
        val finalResults = filtered.take(MAX_RESULTS)
        return mapOf(
            BaseTool.RESULT_KEY to buildString {
                appendLine("[${finalResults.size} 条结果｜引擎：${response.engine ?: "未知"}]")
                appendLine()
                finalResults.forEachIndexed { index, result ->
                    appendLine("[${index + 1}. ${result.title}](${result.url})")
                    if (result.snippet.isNotBlank()) appendLine("   ${result.snippet}")
                }
                appendLine()
                append(
                    "如需正文，用 ${WebFetchTool.NAME} 打开其中某个链接。" +
                        "回答时请附上信息来源链接。"
                )
            }.truncateForModel()
        )
    }

    override fun declaration(): FunctionDeclaration = FunctionDeclaration(
        name = NAME,
        description = description(),
        parameters = Schema(
            type = Type.OBJECT,
            properties = mapOf(
                "query" to Schema(
                    type = Type.STRING,
                    description = "要搜索的关键词。查询最新信息时请带上当前年份。",
                ),
                "allowed_domains" to Schema(
                    type = Type.ARRAY,
                    description = "只保留这些域名下的结果，可为空。",
                    items = Schema(type = Type.STRING),
                ),
                "blocked_domains" to Schema(
                    type = Type.ARRAY,
                    description = "排除这些域名下的结果，可为空。",
                    items = Schema(type = Type.STRING),
                ),
            ),
            required = listOf("query"),
        ),
    )

    private fun error(message: String) = mapOf(FunctionTool.ERROR_KEY to message)

    companion object {
        const val NAME = "web_search"
        private const val MAX_RESULTS = 10

        /**
         * 描述里带上当天日期，避免模型用旧年份搜索导致结果过时——这是插件版踩过的坑，
         * 原样保留该提示。
         */
        private fun description() = """
            联网搜索，用于获取模型知识截止之后的信息、实时数据与新闻。

            用法说明：
            - 传入搜索关键词，返回若干条结果（标题 + 链接 + 摘要），已按域名过滤规则筛选
            - 本工具只返回"去哪里看"，要读正文请接着用 ${WebFetchTool.NAME} 打开具体链接
            - 今天是 ${LocalDate.now()}，搜索最新信息时必须使用当前年份，例如搜「2026 年最新进展」
              而不是「2025 年最新进展」

            适用场景：时事、版本发布、价格、天气等时效性内容；不适用于需要精确计算的题目。
        """.trimIndent()
    }
}

/**
 * 打开指定 URL 并返回 Markdown 正文。
 *
 * 与 [WebSearchTool] 配合：先搜再抓。也可以直接用用户给的链接。
 */
class WebFetchTool(
    private val client: WebFetchClient = WebFetchClient(),
) : FunctionTool(
    name = NAME,
    description = description(),
) {

    override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
        val url = args.stringArg("url") ?: return error("缺少 url 参数")
        val selector = args.stringArg("selector")
        val offset = args.intArg("offset")
        val limit = args.intArg("limit")

        return when (val outcome = client.fetch(url, selector, offset, limit)) {
            is WebFetchClient.FetchOutcome.Failure -> {
                val status = outcome.partial?.statusCode?.let { "（HTTP $it）" }.orEmpty()
                error("打开 $url 失败$status：${outcome.message}")
            }

            is WebFetchClient.FetchOutcome.Success -> {
                val page = outcome.page
                mapOf(
                    BaseTool.RESULT_KEY to buildString {
                        appendLine("# ${page.title ?: page.finalUrl}")
                        appendLine()
                        appendLine("来源：${page.finalUrl}")
                        if (page.truncated) {
                            appendLine()
                            append(
                                "（内容已截取第 ${page.startLine}-${page.endLine} 行，" +
                                    "可用 offset/limit 继续读取后面的部分）"
                            )
                        }
                        appendLine()
                        appendLine(page.markdown)
                    }.truncateForModel()
                )
            }
        }
    }

    override fun declaration(): FunctionDeclaration = FunctionDeclaration(
        name = NAME,
        description = description(),
        parameters = Schema(
            type = Type.OBJECT,
            properties = mapOf(
                "url" to Schema(
                    type = Type.STRING,
                    description = "要打开的网页地址，必须以 http:// 或 https:// 开头。",
                ),
                "selector" to Schema(
                    type = Type.STRING,
                    description = "可选，CSS 选择器，用于只提取页面某一部分，例如 article 或 .content。",
                ),
                "offset" to Schema(
                    type = Type.INTEGER,
                    description = "可选，从正文第几行开始读取（从 1 开始），用于分页读长页面。",
                ),
                "limit" to Schema(
                    type = Type.INTEGER,
                    description = "可选，从 offset 起读取多少行，与 offset 配合分页。",
                ),
            ),
            required = listOf("url"),
        ),
    )

    private fun error(message: String) = mapOf(FunctionTool.ERROR_KEY to message)

    companion object {
        const val NAME = "web_fetch"

        private fun description() = """
            打开一个网页并把正文转成 Markdown 返回。

            用法说明：
            - 传入具体 URL，返回该页正文（已去掉脚本、样式、导航等噪声）
            - 长页面会分段返回，可按返回提示用 offset / limit 继续读取后续内容
            - 页面结构复杂、正文混杂时，用 selector 指定容器（如 article）能显著提高可读性

            典型组合：先用 web_search 找到相关链接，再用本工具打开其中最相关的几条读正文。
        """.trimIndent()
    }
}

/** 取字符串数组参数；模型偶尔会给单个字符串，这里一并兼容。 */
internal fun Map<String, Any?>.stringListArg(key: String): List<String>? = when (val value = this[key]) {
    is List<*> -> value.mapNotNull { it as? String }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
    is String -> value.takeIf { it.isNotBlank() }?.let { listOf(it) }
    else -> null
}
