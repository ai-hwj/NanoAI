package com.lyentech.nano.agent.tools

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * @author by jason-何伟杰，2026/10/10
 * des: 文件工具（读/写/列目录/搜索），作用域限定在**应用私有沙箱**内。
 *
 * 为什么不照搬插件的 ReadTool / WriteTool：
 *  - 插件版面向"开发者的代码工程"：会去读 pom.xml 推 groupId、按 package 推导
 *    src/main/java 路径、直接操作 LocalFileSystem 与 PSI。手机上既没有源码树，
 *    也没有 mvn/gradle 工程，这些逻辑没有立足点。
 *  - 插件版接受任意绝对路径。在 Android 上这既做不到（沙箱隔离），也不该做：
 *    把"模型可以读写任意路径"的能力交给一个联网 Agent，等于把用户数据全开放。
 *
 * 所以这里保留**文件工具的能力形状**（读、写、列、搜），但把根目录钉死在应用私有目录：
 * `<外部私有目录>/agent-workspace`（`getExternalFilesDir`，卸载即清理、无需任何权限）。
 *
 * 覆盖已有文件这件事**必须用户确认**：写文件是不可逆的破坏性操作，
 * 而模型可能因为一次误解就把用户存好的笔记抹掉。新建文件则不需要确认。
 */
class WorkspaceTools(private val rootDir: File) {

    /** 根目录不存在时按需创建；首次调用就走这里，避免调用方先手动 mkdirs。 */
    private val root: File by lazy {
        if (!rootDir.exists()) rootDir.mkdirs()
        rootDir.canonicalFile
    }

    /**
     * 把模型给的相对路径解析成根目录下的真实文件，并确认没有越界。
     *
     * 这是整个文件工具类的**安全关键点**：模型（或提示注入）完全可能给出
     * `../../databases/nano_app.db` 这种路径去读写应用数据，所以必须用 canonicalFile
     * 把 `..` 和符号链接都展开成真实路径后，再比对是否仍在根目录之下。
     *
     * 注意不要额外加"路径里出现 .. 就拒绝"这类快速判断：`notes/sub/../plan.md` 是
     * 完全合法且模型常写的形式，一刀切会把正常请求也挡掉，而真正的安全边界只有
     * 下面这处前缀比对。
     *
     * @return 解析后的文件；越界或路径非法时返回 null。
     */
    internal fun resolveSafe(relativePath: String): File? {
        // 先把反斜杠统一成斜杠。Android 上反斜杠本身是合法文件名字符，
        // 若不归一化，`..\secret.txt` 会被当成一个普通文件名（落在根目录内），
        // 虽然不算越界，但模型以为自己在写上级目录、实际却生成了怪文件名的文件，
        // 语义混乱且难以排查。统一成分隔符后，越界意图会被下面的前缀比对正确拦下。
        val cleaned = relativePath.trim().replace('\\', '/').trimStart('/')
        if (cleaned.isEmpty()) return null

        val candidate = File(root, cleaned)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null

        // 前缀比对必须带分隔符，否则 /workspace-evil 会被误判为在 /workspace 之下。
        val rootPath = root.path
        val canonicalPath = canonical.path
        val inBounds = canonicalPath == rootPath || canonicalPath.startsWith(rootPath + File.separator)
        return if (inBounds) canonical else null
    }

    /** 把绝对路径转回相对路径，用于回显给模型（不暴露真实文件系统布局）。 */
    internal fun toRelative(file: File): String =
        runCatching { file.relativeTo(root).path }.getOrDefault(file.name)

    /**
     * 读取文本文件，按行号分页。
     *
     * 沿用插件版的 `cat -n` 形态加行号：模型引用位置时可以直接说"第 12 行"，
     * 后续用 [EditWorkspaceFileTool] 改的时候更容易对上。
     */
    suspend fun read(path: String, offset: Int?, limit: Int?): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            val file = resolveSafe(path)
                ?: return@withContext err("路径无效或超出工作目录：$path")
            if (!file.exists()) return@withContext err("文件不存在：$path")
            if (file.isDirectory) return@withContext err("$path 是目录，读取请用 list_workspace_files")
            if (file.length() > MAX_READ_BYTES) {
                return@withContext err(
                    "文件过大（${file.length()} 字节，上限 $MAX_READ_BYTES）。请用 offset/limit 分页读取。"
                )
            }

            val lines = runCatching { file.readLines() }.getOrElse {
                return@withContext err("读取失败：${it.message ?: it::class.simpleName}")
            }

            val start = ((offset ?: 1) - 1).coerceIn(0, lines.size)
            val end = when (limit) {
                null -> lines.size
                else -> (start + limit).coerceAtMost(lines.size)
            }

            val body = lines.subList(start, end)
                .mapIndexed { index, line ->
                    // 超长单行截断，避免压缩过的文件把上下文撑爆。
                    val trimmed = if (line.length > MAX_LINE_CHARS) {
                        line.take(MAX_LINE_CHARS) + " …(本行超过 $MAX_LINE_CHARS 字符已截断)"
                    } else {
                        line
                    }
                    "${start + index + 1}\t$trimmed"
                }
                .joinToString("\n")

            val header = buildString {
                appendLine("文件：$path（共 ${lines.size} 行）")
                if (end < lines.size || start > 0) {
                    append("本次显示第 ${start + 1}-$end 行，可用 offset/limit 继续读取。")
                }
            }.trimEnd()

            mapOf(BaseTool.RESULT_KEY to "$header\n\n$body")
        }

    suspend fun write(path: String, content: String, overwrite: Boolean): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            val file = resolveSafe(path)
                ?: return@withContext err("路径无效或超出工作目录：$path")
            if (file.isDirectory) return@withContext err("$path 是目录，不能写入")
            if (content.isBlank()) return@withContext err("content 不能为空")

            val exists = file.exists()
            if (exists && !overwrite) {
                return@withContext err(
                    "$path 已存在。要覆盖请把 overwrite 设为 true，或先用 read_workspace_file 看看现有内容。"
                )
            }

            file.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs() && !parent.exists()) {
                    return@withContext err("无法创建目录：${toRelative(parent)}")
                }
            }

            runCatching { file.writeText(content) }.getOrElse {
                return@withContext err("写入失败：${it.message ?: it::class.simpleName}")
            }

            val action = if (exists) "已覆盖" else "已创建"
            mapOf(
                BaseTool.RESULT_KEY to
                    "$action $path（${content.toByteArray().size} 字节，共 ${content.lines().size} 行）"
            )
        }

    /** 列出目录内容；递归深度受限，避免大目录返回上万条。 */
    suspend fun list(path: String, recursive: Boolean): Map<String, Any?> = withContext(Dispatchers.IO) {
        val base = if (path.isBlank()) root else resolveSafe(path)
            ?: return@withContext err("路径无效或超出工作目录：$path")
        if (!base.exists()) return@withContext err("目录不存在：$path")
        if (!base.isDirectory) return@withContext err("$path 不是目录")

        val entries = mutableListOf<String>()
        val maxDepth = if (recursive) MAX_LIST_DEPTH else 1
        var truncated = false

        fun walk(dir: File, depth: Int) {
            if (entries.size >= MAX_LIST_ENTRIES) {
                truncated = true
                return
            }
            val children = dir.listFiles()?.sortedBy { it.name }.orEmpty()
            children.forEach { child ->
                if (entries.size >= MAX_LIST_ENTRIES) {
                    truncated = true
                    return
                }
                val rel = toRelative(child)
                entries += if (child.isDirectory) "$rel/" else "$rel (${child.length()} 字节)"
                if (child.isDirectory && depth < maxDepth) walk(child, depth + 1)
            }
        }

        walk(base, 1)

        val header = buildString {
            appendLine("工作目录：${if (path.isBlank()) "." else path}")
            append("共 ${entries.size} 项")
            if (truncated) append("（已达上限 $MAX_LIST_ENTRIES，结果被截断）")
        }
        val body = if (entries.isEmpty()) "（空目录）" else entries.joinToString("\n")
        mapOf(BaseTool.RESULT_KEY to "$header\n\n$body")
    }

    /**
     * 按关键词搜索文件内容。
     *
     * 这是插件版没有、但 Agent 极需要的能力：会话历史里存了十几份笔记，
     * 让模型"自己想起来在哪份里"是不现实的，必须能搜。
     */
    suspend fun search(query: String, path: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val needle = query.trim()
        if (needle.isEmpty()) return@withContext err("query 不能为空")

        val base = if (path.isBlank()) root else resolveSafe(path)
            ?: return@withContext err("路径无效或超出工作目录：$path")
        if (!base.exists()) return@withContext err("目录不存在：$path")

        val lowerNeedle = needle.lowercase()
        val hits = mutableListOf<String>()
        var scanned = 0

        val files = if (base.isDirectory) {
            base.walkTopDown().filter { it.isFile && it.length() <= MAX_READ_BYTES }.take(MAX_SEARCH_FILES).toList()
        } else {
            listOf(base)
        }

        for (file in files) {
            scanned++
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            lines.forEachIndexed { index, line ->
                if (hits.size >= MAX_SEARCH_HITS) return@withContext finishSearch(hits, scanned, true)
                if (line.lowercase().contains(lowerNeedle)) {
                    hits += "${toRelative(file)}:${index + 1}: ${line.trim().take(MAX_SEARCH_LINE_CHARS)}"
                }
            }
        }
        finishSearch(hits, scanned, false)
    }

    private fun finishSearch(hits: List<String>, scanned: Int, truncated: Boolean): Map<String, Any?> {
        if (hits.isEmpty()) {
            return mapOf(BaseTool.RESULT_KEY to "没有匹配的内容（已扫描 $scanned 个文件）")
        }
        val header = buildString {
            append("找到 ${hits.size} 处匹配（已扫描 $scanned 个文件）")
            if (truncated) append("，结果已达上限被截断")
        }
        return mapOf(BaseTool.RESULT_KEY to "$header\n\n${hits.joinToString("\n")}")
    }

    private fun err(message: String) = mapOf(FunctionTool.ERROR_KEY to message)

    companion object {
        private const val MAX_READ_BYTES = 512 * 1024L
        private const val MAX_LINE_CHARS = 2_000
        private const val MAX_LIST_ENTRIES = 500
        private const val MAX_LIST_DEPTH = 4
        private const val MAX_SEARCH_FILES = 200
        private const val MAX_SEARCH_HITS = 100
        private const val MAX_SEARCH_LINE_CHARS = 200
    }
}

/** 读取沙箱内文件。 */
class ReadWorkspaceFileTool(private val tools: WorkspaceTools) : FunctionTool(
    name = NAME,
    description = """
        读取工作目录里的文本文件，返回带行号的内容。

        参数：
        - path（必填）：相对工作目录的路径，例如 notes/plan.md
        - offset / limit（可选）：从第几行开始读、读多少行，用于分页读大文件

        说明：
        - 只能访问工作目录内的文件，越界路径会被拒绝
        - 返回的行号可以直接用于定位
        - 写文件前建议先读一遍，确认要覆盖的内容是预期的
    """.trimIndent(),
) {

    override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
        val path = args.stringArg("path") ?: return mapOf(FunctionTool.ERROR_KEY to "缺少 path 参数")
        return tools.read(path, args.intArg("offset"), args.intArg("limit"))
    }

    override fun declaration(): FunctionDeclaration = FunctionDeclaration(
        name = NAME,
        description = description,
        parameters = Schema(
            type = Type.OBJECT,
            properties = mapOf(
                "path" to Schema(type = Type.STRING, description = "相对工作目录的文件路径。"),
                "offset" to Schema(type = Type.INTEGER, description = "从第几行开始读（从 1 开始），可选。"),
                "limit" to Schema(type = Type.INTEGER, description = "读取多少行，可选。"),
            ),
            required = listOf("path"),
        ),
    )

    companion object {
        const val NAME = "read_workspace_file"
    }
}

/** 写入沙箱内文件。覆盖已有文件需要显式确认，见 [WorkspaceTools] 的类注释。 */
class WriteWorkspaceFileTool(private val tools: WorkspaceTools) : FunctionTool(
    name = NAME,
    description = """
        把文本写入工作目录里的文件，父目录不存在会自动创建。

        参数：
        - path（必填）：相对工作目录的路径，例如 notes/summary.md
        - content（必填）：完整文件内容，会整体写入
        - overwrite（可选，默认 false）：目标文件已存在时必须显式设为 true 才会覆盖

        说明：
        - 只能写入工作目录内的文件，越界路径会被拒绝
        - 覆盖会丢失原有内容，写之前建议先用 read_workspace_file 确认
    """.trimIndent(),
    // 覆盖是破坏性且不可逆的，交给 ADK 的确认门控，让用户点一下再写。
    requiresConfirmation = { args -> args["overwrite"] == true || args["overwrite"] == "true" },
) {

    override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
        val path = args.stringArg("path") ?: return mapOf(FunctionTool.ERROR_KEY to "缺少 path 参数")
        val content = (args["content"] as? String)
            ?: return mapOf(FunctionTool.ERROR_KEY to "缺少 content 参数")
        return tools.write(path, content, args.boolArg("overwrite"))
    }

    override fun declaration(): FunctionDeclaration = FunctionDeclaration(
        name = NAME,
        description = description,
        parameters = Schema(
            type = Type.OBJECT,
            properties = mapOf(
                "path" to Schema(type = Type.STRING, description = "相对工作目录的文件路径。"),
                "content" to Schema(type = Type.STRING, description = "要写入的完整文件内容。"),
                "overwrite" to Schema(
                    type = Type.BOOLEAN,
                    description = "目标文件已存在时是否覆盖。默认 false，此时会拒绝写入。",
                ),
            ),
            required = listOf("path", "content"),
        ),
    )

    companion object {
        const val NAME = "write_workspace_file"
    }
}

/** 列出工作目录内容。 */
class ListWorkspaceFilesTool(private val tools: WorkspaceTools) : FunctionTool(
    name = NAME,
    description = """
        列出工作目录里的文件和子目录。

        参数：
        - path（可选）：相对工作目录的子目录，省略则列出整个工作目录
        - recursive（可选，默认 false）：是否递归列出子目录

        在不确定某个文件在哪、或想知道有哪些笔记时，先用这个工具看看。
    """.trimIndent(),
) {

    override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any =
        tools.list(args.stringArg("path").orEmpty(), args.boolArg("recursive"))

    override fun declaration(): FunctionDeclaration = FunctionDeclaration(
        name = NAME,
        description = description,
        parameters = Schema(
            type = Type.OBJECT,
            properties = mapOf(
                "path" to Schema(type = Type.STRING, description = "相对工作目录的子目录，可选。"),
                "recursive" to Schema(type = Type.BOOLEAN, description = "是否递归列出，默认 false。"),
            ),
        ),
    )

    companion object {
        const val NAME = "list_workspace_files"
    }
}

/** 按关键词搜索工作目录内的文件内容。 */
class SearchWorkspaceFilesTool(private val tools: WorkspaceTools) : FunctionTool(
    name = NAME,
    description = """
        在工作目录里按关键词搜索文件内容，返回「文件:行号: 该行内容」。

        参数：
        - query（必填）：要搜索的关键词，大小写不敏感
        - path（可选）：限定搜索的子目录或文件

        适用场景：记不清某个信息写在哪份笔记里时，先搜索定位，再用 read_workspace_file 读上下文。
    """.trimIndent(),
) {

    override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
        val query = args.stringArg("query") ?: return mapOf(FunctionTool.ERROR_KEY to "缺少 query 参数")
        return tools.search(query, args.stringArg("path").orEmpty())
    }

    override fun declaration(): FunctionDeclaration = FunctionDeclaration(
        name = NAME,
        description = description,
        parameters = Schema(
            type = Type.OBJECT,
            properties = mapOf(
                "query" to Schema(type = Type.STRING, description = "搜索关键词。"),
                "path" to Schema(type = Type.STRING, description = "限定搜索的子目录或文件，可选。"),
            ),
            required = listOf("query"),
        ),
    )

    companion object {
        const val NAME = "search_workspace_files"
    }
}
