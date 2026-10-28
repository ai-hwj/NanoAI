package com.lyentech.nano.agent.tools

import android.content.Context
import com.google.adk.kt.models.Model
import com.google.adk.kt.skills.AssetSkillSource
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.SkillToolset
import com.google.adk.kt.tools.Toolset
import java.io.File

/**
 * @author by jason-何伟杰，2026/10/10
 * des: 应用内 Agent 工具的装配点 —— 一处看清"这个 Agent 到底能用什么"。
 *
 * 关于技能（Skill）：插件侧的 LoadSkillTool 是**自己实现**的一套技能加载。
 * ADK-Kotlin 1.3.1 已经内置了等价且更完整的实现（`SkillToolset`，提供
 * `list_skills` / `load_skill` / `load_skill_resource`，并会自动把技能目录注入 system prompt），
 * 所以这里直接用官方的，不再重复造轮子。
 *
 * 技能放在 `assets/skills/<skill-name>/SKILL.md`，前面是 YAML frontmatter（name/title/description），
 * 后面是正文指令；资源放在同目录的 references/ assets/ scripts/ 子目录下。
 */
class AgentToolkit(context: Context) {

    private val appContext = context.applicationContext

    /** 文件工具的工作目录：应用外部私有目录，卸载即清理，且不需要任何存储权限。 */
    private val workspace: WorkspaceTools by lazy {
        WorkspaceTools(
            appContext.getExternalFilesDir(null)?.let { File(it, WORKSPACE_DIR) }
                ?: File(appContext.filesDir, WORKSPACE_DIR)
        )
    }

    /** 任务清单状态，供聊天页观察并渲染成任务卡。 */
    val todos: TodoStore = TodoStore()

    /**
     * 与具体模型无关的工具集（不含子 Agent，因为子 Agent 需要模型才能构造）。
     *
     * @param includeWorkspaceFiles 是否开放文件读写。默认关闭：文件工具能改用户数据，
     *   应当由接入方显式打开，而不是默认就具备这个能力。
     */
    fun coreTools(includeWorkspaceFiles: Boolean = false): List<BaseTool> = buildList {
        add(TodoWriteTool(todos))
        add(ListWorkspaceFilesTool(workspace))
        add(ReadWorkspaceFileTool(workspace))
        add(SearchWorkspaceFilesTool(workspace))
        if (includeWorkspaceFiles) {
            add(WriteWorkspaceFileTool(workspace))
        }
    }

    /** 联网工具。 */
    fun webTools(): List<BaseTool> = listOf(WebSearchTool(), WebFetchTool())

    /**
     * 设备信息工具（电量、机型等）。
     *
     * [DeviceTools] 用的是 ADK 的 `@Tool` 注解，`generatedTools()` 由 `google-adk-kotlin-processor`
     * 在编译期生成（见 `build/generated/ksp`），把注解除掉方法逐个包成 FunctionTool。
     */
    fun deviceTools(): List<BaseTool> = DeviceTools(appContext).generatedTools()

    /**
     * 子 Agent 编排工具。 先别用，暂时不考虑子Agent
     *
     * @param model 子 Agent 使用的模型工厂。子 Agent 可能与主 Agent 用同一个模型，
     *   但必须是新实例，避免共享状态。
     */
    fun taskTool(model: () -> Model, specs: List<SubagentSpec> = listOf(DefaultSubagents.generalPurpose())): BaseTool =
        TaskTool(specs = specs, model = model)

    /**
     * 技能工具集。`assets/skills` 不存在时 [AssetSkillSource] 会报错，
     * 所以先探测一次，没有技能就直接不挂这个 toolset，避免运行期噪音。
     */
    fun skillToolsetOrNull(skillsDir: String = DEFAULT_SKILLS_DIR): Toolset? {
        val exists = runCatching {
            appContext.assets.list(skillsDir)?.isNotEmpty() == true
        }.getOrDefault(false)
        if (!exists) return null
        return SkillToolset(AssetSkillSource.fromContext(appContext, skillsDir))
    }

    companion object {
        private const val WORKSPACE_DIR = "agent-workspace"

        /** 与 `app/src/main/assets/skills` 对应。 */
        const val DEFAULT_SKILLS_DIR = "skills"
    }
}
