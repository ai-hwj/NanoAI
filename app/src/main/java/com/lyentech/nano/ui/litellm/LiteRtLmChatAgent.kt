package com.lyentech.nano.ui.litellm

import android.content.Context
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import com.lyentech.nano.agent.tools.DeviceTools
import com.lyentech.nano.agent.tools.generatedTools
import java.io.File

/**
 * @author by jason-何伟杰，2026/9/29
 * des: 端侧 LiteRT-LM agent 的装配点：负责把模型文件开成引擎，并把设备工具挂到 agent 上。
 */
internal object LiteRtLmChatAgent {

    const val NAME: String = "litertlm_chat_agent"

    /**
     * Opens [modelFile]; the returned model owns a native engine and must be closed.
     *
     * [cacheDir] puts the compiled-model cache where the system can reclaim it. A non-null
     * [threads] is only honoured for the CPU backend — GPU ignores it.
     */
    fun createModel(
        modelFile: File,
        cacheDir: File,
        backend: BackendChoice = BackendChoice.CPU,
        threads: CpuThreads = CpuThreads.AUTO,
    ): LiteRtLmModel =
        LiteRtLmModel.create(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend.toBackend(threads),
                cacheDir = cacheDir.absolutePath,
            ),
            name = modelFile.name,
        )

    private fun BackendChoice.toBackend(threads: CpuThreads): Backend =
        when (this) {
            BackendChoice.CPU -> Backend.CPU(numOfThreads = threads.count)
            BackendChoice.GPU -> Backend.GPU()
        }

    /**
     * Builds the agent around an already-created [model], with [context]'s device tools.
     *
     * [instruction] 由调用方给出：正式示例页用固定的一句，本地模型测试页让用户自己改提示词，
     * 这样同一个 agent 装配逻辑不用为两个页面各写一遍。
     */
    fun create(
        model: LiteRtLmModel,
        context: Context,
        instruction: String = DEFAULT_INSTRUCTION,
    ): LlmAgent =
        LlmAgent(
            name = NAME,
            model = model,
            instruction = Instruction(instruction.trimIndent()),
            // 用 KSP 生成的 `generatedTools()` 扩展把 @Tool 注解法包成 FunctionTool。
            tools = DeviceTools(context).generatedTools(),
        )

    /** 示例页用的默认提示词，保持原有行为。 */
    const val DEFAULT_INSTRUCTION: String =
        """
        You are a helpful assistant running entirely on this device. Keep replies to one or two
        short sentences. Call get_battery_level when the user asks about the battery, and
        get_device_info when they ask what device this is, then state the exact value the tool
        returned.
        """
}

/**
 * 加载模型时可选的 CPU 线程数。null 交给引擎按核数自选。
 *
 * 只有 CPU 后端可调线程：0.14.0 的 `Backend.CPU(threadCount)` 直接透传给 native，
 * 线程数超过物理核会让小模型在手机上抢核反而更慢，所以默认不指定，交给引擎。
 */
internal enum class CpuThreads(val label: String, val count: Int?) {
    AUTO("自动", null),
    FOUR("4 线程", 4),
    SIX("6 线程", 6),
    EIGHT("8 线程", 8),
}

/**
 * 后端档位。GPU 用 OpenCL delegate，Adreno/Mali 上 prefill 快数倍；
 * 但 0.14.0 的 `Backend.GPU` 不暴露任何参数，失败时只能整体退回 CPU，所以这里做成显式选项，
 * 由测试页把两种后端都跑一遍对比，而不是在代码里替用户猜。
 */
internal enum class BackendChoice(val label: String) {
    CPU("CPU"),
    GPU("GPU (OpenCL)"),
}

/**
 * 是否把模型里的 `<think>` 通道打开。
 *
 * MiniCPM5-2B 的 int4/int8 文件都声明了 `thought` 通道（`<think>` / `</think>`），
 * 但模板默认关闭：不设置时模型直接给答案。int4 在 thinking 打开时思考链会拖到几千字符
 * （官方实测 10 题里 0 题能在预算内收尾），所以默认关闭，改由用户在测试页手动开。
 *
 * 注意：**当前接不通**——原因不是 thinking 本身，而是 litertlm 版本被 ADK 1.4.0 钉在 0.14.0
 * （它的 `toConversationConfig` 编死了 11 槽合成重载，0.17.1 起变 14 槽，升级即
 * `NoSuchMethodError`，详见 `gradle/libs.versions.toml` 里 litertlm 版本的注释），而
 * `ThinkingConfig` 是 0.17.1 才有的类。所以测试页里这一项只记录选择、不影响引擎行为，
 * 要真开得同时换 ADK 版本并走自己的 conversation 创建路径。
 */
internal enum class ThinkingSetting(val label: String) {
    OFF("关闭"),
    ON("开启"),
}

