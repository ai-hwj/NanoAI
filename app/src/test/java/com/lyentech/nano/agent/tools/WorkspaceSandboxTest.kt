package com.lyentech.nano.agent.tools

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 文件工具的安全边界测试。
 *
 * 这是本次新增里**风险最高**的一段代码：模型（或在网页里被注入的指令）完全可能给出
 * `../../databases/nano_app.db` 这类路径去读写应用私有数据。所以路径越界的各种变形
 * 必须逐个钉死，而不是只测一个 `..` 了事。
 */
class WorkspaceSandboxTest {

    private lateinit var root: File
    private lateinit var tools: WorkspaceTools

    @Before
    fun setUp() {
        root = Files.createTempDirectory("nano-workspace-test").toFile()
        tools = WorkspaceTools(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---- 路径越界（安全关键）----

    @Test
    fun rejectsParentTraversal() {
        assertNull(tools.resolveSafe("../secret.txt"))
        assertNull(tools.resolveSafe("notes/../../secret.txt"))
        assertNull(tools.resolveSafe("a/b/../../../outside.txt"))
    }

    @Test
    fun rejectsBackslashTraversal() {
        // Windows 风格分隔符同样不能绕过，否则换平台就漏了。
        assertNull(tools.resolveSafe("..\\secret.txt"))
        assertNull(tools.resolveSafe("notes\\..\\..\\secret.txt"))
    }

    @Test
    fun rejectsAbsolutePathOutsideRoot() {
        // 前导斜杠会被剥掉当成相对路径，但 `..` 仍然挡不住的部分必须继续挡。
        assertNull(tools.resolveSafe("/../etc/passwd"))
        assertNull(tools.resolveSafe("/../../data/data/com.lyentech.nano/databases/nano_app.db"))
    }

    @Test
    fun rejectsSiblingDirectorySharingPrefix() {
        // 前缀比对必须带分隔符：/tmp/nano-workspace-test-evil 不能因为字符串前缀相同就放行。
        val sibling = File(root.parentFile, root.name + "-evil").apply { mkdirs() }
        try {
            val escaped = File(sibling, "stolen.txt")
            assertTrue(escaped.exists() || escaped.parentFile.exists())
            // 用相对路径描述兄弟目录，必须被拒绝。
            assertNull(tools.resolveSafe("../${root.name}-evil/stolen.txt"))
        } finally {
            sibling.deleteRecursively()
        }
    }

    @Test
    fun rejectsEmptyAndBlankPath() {
        assertNull(tools.resolveSafe(""))
        assertNull(tools.resolveSafe("   "))
        assertNull(tools.resolveSafe("/"))
    }

    @Test
    fun acceptsNormalNestedPaths() {
        val resolved = tools.resolveSafe("notes/plan.md")
        assertNotNull(resolved)
        assertTrue(resolved!!.path.startsWith(root.canonicalPath))
        assertEquals("notes/plan.md", tools.toRelative(resolved))
    }

    @Test
    fun normalizesRedundantSegmentsThatStayInside() {
        // 留在根目录内部的 `.` 与可抵消的 `..` 无害，应该允许（模型不一定写得干净）。
        // 反过来说，安全问题只由 canonical 路径的前缀比对兜住，不能靠"看到 .. 就拒绝"。
        val resolved = tools.resolveSafe("notes/./sub/../plan.md")
        assertNotNull(resolved)
        assertEquals("notes/plan.md", tools.toRelative(resolved!!))
    }

    // ---- 读写行为 ----

    @Test
    fun writeThenReadRoundTrips() = runBlocking {
        val write = tools.write("notes/plan.md", "第一行\n第二行", overwrite = false)
        assertNull("写入不该报错", write[FunctionTool.ERROR_KEY])

        val read = tools.read("notes/plan.md", offset = null, limit = null)
        val text = read[BaseTool.RESULT_KEY] as String
        assertTrue(text.contains("第一行"))
        assertTrue(text.contains("2\t第二行"))
    }

    @Test
    fun writeRefusesToOverwriteWithoutFlag() = runBlocking {
        tools.write("a.txt", "原始内容", overwrite = false)
        val second = tools.write("a.txt", "新内容", overwrite = false)

        // 覆盖是不可逆的，默认必须拒绝，否则模型一次误解就能抹掉用户数据。
        assertNotNull(second[FunctionTool.ERROR_KEY])
        val read = tools.read("a.txt", null, null)[BaseTool.RESULT_KEY] as String
        assertTrue("原内容不该被改动", read.contains("原始内容"))
    }

    @Test
    fun writeAllowsOverwriteWhenExplicitlyRequested() = runBlocking {
        tools.write("a.txt", "原始内容", overwrite = false)
        val second = tools.write("a.txt", "新内容", overwrite = true)
        assertNull(second[FunctionTool.ERROR_KEY])

        val read = tools.read("a.txt", null, null)[BaseTool.RESULT_KEY] as String
        assertTrue(read.contains("新内容"))
    }

    @Test
    fun readRejectsTraversalInsteadOfLeakingFiles() = runBlocking {
        val outside = File(root.parentFile, "outside-secret.txt")
        outside.writeText("不该被读到")
        try {
            val read = tools.read("../outside-secret.txt", null, null)
            val error = read[FunctionTool.ERROR_KEY] as String?
            assertNotNull("越界读取必须被拒绝", error)
            assertTrue("错误信息里不该出现文件内容", !error!!.contains("不该被读到"))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun writeRejectsTraversalInsteadOfEscaping() = runBlocking {
        val result = tools.write("../escaped.txt", "恶意内容", overwrite = true)
        assertNotNull(result[FunctionTool.ERROR_KEY])
        assertTrue("越界文件不该被创建", !File(root.parentFile, "escaped.txt").exists())
    }

    @Test
    fun readReportsMissingFileInsteadOfThrowing() = runBlocking {
        val read = tools.read("nope.txt", null, null)
        assertNotNull(read[FunctionTool.ERROR_KEY])
    }

    @Test
    fun readPaginatesWithOffsetAndLimit() = runBlocking {
        tools.write("big.txt", (1..50).joinToString("\n") { "行$it" }, overwrite = false)
        val read = tools.read("big.txt", offset = 11, limit = 5)
        val text = read[BaseTool.RESULT_KEY] as String

        assertTrue(text.contains("11\t行11"))
        assertTrue(text.contains("15\t行15"))
        assertTrue("不该包含范围外的行", !text.contains("16\t行16"))
    }

    @Test
    fun listingShowsNestedEntries() = runBlocking {
        tools.write("notes/a.md", "x", overwrite = false)
        tools.write("notes/deep/b.md", "y", overwrite = false)

        val flat = tools.list("", recursive = false)[BaseTool.RESULT_KEY] as String
        assertTrue(flat.contains("notes/"))

        val deep = tools.list("", recursive = true)[BaseTool.RESULT_KEY] as String
        assertTrue(deep.contains("notes/deep/b.md"))
    }

    @Test
    fun searchFindsMatchingLinesWithLocation() = runBlocking {
        tools.write("notes/a.md", "苹果\n香蕉", overwrite = false)
        tools.write("notes/b.md", "橘子\n香蕉牛奶", overwrite = false)

        val hits = tools.search("香蕉", "")[BaseTool.RESULT_KEY] as String
        assertTrue("应命中 a.md 第 2 行", hits.contains("notes/a.md:2"))
        assertTrue("应命中 b.md 第 2 行", hits.contains("notes/b.md:2"))
    }

    @Test
    fun searchIsCaseInsensitive() = runBlocking {
        tools.write("a.txt", "Hello World", overwrite = false)
        val hits = tools.search("hello", "")[BaseTool.RESULT_KEY] as String
        assertTrue(hits.contains("Hello World"))
    }

    @Test
    fun searchReportsNoMatchClearly() = runBlocking {
        tools.write("a.txt", "内容", overwrite = false)
        val hits = tools.search("不存在的东西", "")[BaseTool.RESULT_KEY] as String
        assertTrue(hits.contains("没有匹配"))
    }
}
