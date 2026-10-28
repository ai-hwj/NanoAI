package com.lyentech.nano.agent.web

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 联网工具的共用数据模型。
 *
 * 移植自 IDEA 插件 `baitong-intellij`（Koog 框架）的 WebSearchTool / WebFetchTool，
 * 保留其核心业务逻辑（多引擎回退、域名过滤、正文提取），去掉插件侧的 Hook / Token 计数等基础设施。
 */

/** 一条搜索结果。 */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

/** 一次搜索的完整结果，[engine] 记录最终生效的引擎，便于排查"为什么结果不对"。 */
data class SearchResponse(
    val query: String,
    val results: List<SearchResult>,
    val engine: String?,
)

/** 抓取到的网页正文。 */
data class FetchedPage(
    val url: String,
    /** 跟随重定向后的最终地址，可能和请求地址不同。 */
    val finalUrl: String,
    val title: String?,
    val markdown: String,
    val contentType: String?,
    val statusCode: Int?,
    val truncated: Boolean = false,
    val startLine: Int? = null,
    val endLine: Int? = null,
)

/**
 * 用户代理轮换。
 *
 * 搜索引擎对同一 UA 的高频请求会拦截，插件侧的做法是在几个真实浏览器 UA 之间轮转；
 * 这里沿用同样的思路，`@Synchronized` 保证多线程下索引不会错乱。
 */
internal object UserAgents {
    private val AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        // 端上默认按手机 UA，否则部分站点会返回桌面版重排版页面。
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
    )

    private var index = 0

    @Synchronized
    fun next(): String {
        val agent = AGENTS[index]
        index = (index + 1) % AGENTS.size
        return agent
    }
}

/**
 * 按域名白/黑名单过滤结果，规则与插件一致：黑名单命中即丢弃；白名单存在时必须命中其一。
 *
 * 两端都为空时全部保留。
 */
internal fun List<SearchResult>.filterByDomains(
    allowedDomains: List<String>?,
    blockedDomains: List<String>?,
): List<SearchResult> = filter { result ->
    val url = result.url.lowercase()
    val blocked = blockedDomains.orEmpty().any { url.contains(it.lowercase()) }
    if (blocked) return@filter false
    val allowed = allowedDomains.orEmpty()
    allowed.isEmpty() || allowed.any { url.contains(it.lowercase()) }
}

/**
 * 结果体积上限。
 *
 * 插件侧按 token 精确截断，端上没有 tokenizer，改用字符数近似（中文大致 1 字 ≈ 1 token，
 * 阈值取 16000 与插件一致，量级相符即可）。
 */
internal const val MAX_RESULT_CHARS = 16_000

/**
 * 超出上限时保留头尾、中间用省略号替代。
 *
 * 只从头截断会丢掉页面结论部分，而正文工具的结论往往在结尾，所以两端都留。
 *
 * 头尾的预算要先扣掉省略号本身的长度，否则返回值会比 [maxChars] 长出几个字符——
 * 这个上限是用来控制上下文占用的，超出去就失去了意义。
 */
internal fun String.truncateForModel(maxChars: Int = MAX_RESULT_CHARS): String {
    if (length <= maxChars) return this
    val budget = (maxChars - GAP_MARKER.length).coerceAtLeast(2)
    val headChars = (budget * 0.85).toInt()
    val tailChars = budget - headChars
    return take(headChars) + GAP_MARKER + takeLast(tailChars)
}

private const val GAP_MARKER = "\n...\n"
