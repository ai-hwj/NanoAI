package com.lyentech.nano.agent.web

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 抓取指定 URL 并把正文转成 Markdown。
 *
 * 移植自 IDEA 插件的 WebFetchTool，保留正文定位（main/article/[role=main]）、
 * CSS 选择器限定、offset/limit 分页这几个核心能力。
 *
 * 与搜索客户端的分工：搜索回答"去哪看"，本工具回答"看到了什么"。
 *
 * 抓取失败不抛异常：[fetch] 返回包裹在 [FetchOutcome] 里的失败说明，由工具层翻成一段
 * 给模型看的文本——工具抛异常会中断整轮对话，而这只是"这个链接打不开"这种局部问题。
 */
class WebFetchClient(
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val userAgent: String = UserAgents.next(),
) {

    private val converter: FlexmarkHtmlConverter = FlexmarkHtmlConverter.builder().build()

    /** 抓取结果：成功携带页面，失败携带原因。 */
    sealed interface FetchOutcome {
        data class Success(val page: FetchedPage) : FetchOutcome

        /** [partial] 带上已拿到的元信息（状态码/最终地址），便于模型判断是 404 还是被墙。 */
        data class Failure(val message: String, val partial: FetchedPage? = null) : FetchOutcome
    }

    suspend fun fetch(
        url: String,
        selector: String? = null,
        offset: Int? = null,
        limit: Int? = null,
    ): FetchOutcome = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(url)
            ?: return@withContext FetchOutcome.Failure("URL 无效，只支持 http:// 或 https:// 开头的地址")

        val response = runCatching {
            Jsoup.connect(normalized)
                .userAgent(userAgent)
                .timeout(timeoutMs)
                .ignoreContentType(true)
                .followRedirects(true)
                // 1MB 上限：端上内存紧张，超大页面直接截断比 OOM 好。
                .maxBodySize(MAX_BODY_BYTES)
                .execute()
        }.getOrElse { e ->
            return@withContext FetchOutcome.Failure(
                "请求失败：${e.message ?: e::class.simpleName}"
            )
        }

        val contentType = response.contentType()
        val finalUrl = response.url().toExternalForm()
        val statusCode = response.statusCode()

        // 只有元信息、没有正文的空壳，失败时用来回传状态码。
        fun shell(title: String? = null) = FetchedPage(
            url = normalized,
            finalUrl = finalUrl,
            title = title,
            markdown = "",
            contentType = contentType,
            statusCode = statusCode,
        )

        if (!isHtml(contentType)) {
            return@withContext FetchOutcome.Failure(
                "不支持的内容类型：${contentType ?: "未知"}（本工具只处理 HTML 页面）",
                shell(),
            )
        }

        val body = response.body()
        if (body.isBlank()) {
            return@withContext FetchOutcome.Failure("页面返回了空内容", shell())
        }

        val document = Jsoup.parse(body, finalUrl)
        // 脚本/样式/模板里的文本进入 Markdown 只会变成噪音，先整块移除。
        document.select("script, style, noscript, template, svg, iframe").remove()
        val pageTitle = document.title().takeIf { it.isNotBlank() }

        val html = selectContent(document, selector)
            ?: return@withContext FetchOutcome.Failure(
                "没有内容匹配选择器 '$selector'",
                shell(pageTitle),
            )

        val markdown = converter.convert(html).trim()
        if (markdown.isEmpty()) {
            return@withContext FetchOutcome.Failure("页面里没有提取到可读正文", shell(pageTitle))
        }

        val slice = slicer.slice(markdown, offset, limit)
        FetchOutcome.Success(
            FetchedPage(
                url = normalized,
                finalUrl = finalUrl,
                title = pageTitle,
                markdown = slice.content,
                contentType = contentType,
                statusCode = statusCode,
                truncated = slice.truncated,
                startLine = slice.startLine,
                endLine = slice.endLine,
            )
        )
    }

    private val slicer = MarkdownSlicer()

    /**
     * 定位正文容器：优先用户指定的选择器，其次语义标签，最后退回整个 body。
     *
     * main/article/[role=main] 是主流站点放正文的位置；直接拿 body 会把导航、页脚、
     * 推荐位一起喂给模型，噪声很大。
     */
    private fun selectContent(document: Document, selector: String?): String? {
        if (!selector.isNullOrBlank()) {
            val selected = document.select(selector)
            if (selected.isEmpty()) return null
            return selected.joinToString("\n") { it.outerHtml() }
        }
        val root = document.selectFirst("main, article, [role=main]") ?: document.body()
        return root?.outerHtml()
    }

    private fun isHtml(contentType: String?): Boolean {
        if (contentType == null) return true
        val lower = contentType.lowercase()
        return lower.contains("text/html") || lower.contains("application/xhtml+xml")
    }

    private fun normalizeUrl(url: String): String? {
        val trimmed = url.trim()
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        return trimmed
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 15_000

        /** jsoup 的 maxBodySize 接收 Int，1MB 不会溢出。 */
        private const val MAX_BODY_BYTES = 1024 * 1024
    }
}

/**
 * Markdown 按行分页。
 *
 * 单独成类是因为它是纯逻辑且易错（首尾空行、越界 offset、末页判定），
 * 需要能脱离网络直接单测；放在 [WebFetchClient] 里会连带把 jsoup 依赖拖进测试。
 */
internal class MarkdownSlicer {

    data class Slice(
        val content: String,
        val truncated: Boolean,
        val startLine: Int?,
        val endLine: Int?,
    )

    fun slice(markdown: String, offset: Int?, limit: Int?): Slice {
        // 先去掉首尾空行：否则分页边界会被空行顶偏，第一页看起来像是空的。
        val lines = markdown.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        val total = lines.size
        val hasSlice = offset != null || limit != null

        val startIndex = ((offset ?: 1) - 1).coerceIn(0, total)
        val endExclusive = when {
            limit == null -> total
            else -> (startIndex + limit).coerceAtMost(total)
        }

        return Slice(
            content = lines.subList(startIndex, endExclusive).joinToString("\n"),
            // truncated 的语义是"后面还有内容没读到"，只看是否读到了末尾。
            // 不能把 startIndex > 0 也算进来 —— 那表示跳过了前面的行，不代表后面还有。
            truncated = hasSlice && endExclusive < total,
            startLine = if (hasSlice) startIndex + 1 else null,
            endLine = if (hasSlice) endExclusive else null,
        )
    }
}
