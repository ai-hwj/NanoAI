package com.lyentech.nano.data.model

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 推理强度档位。取值按 OpenAI 兼容的 reasoning_effort 命名，便于直接透传到请求体，
 *      端点是否识别该字段由部署决定，识别不了时只是一个被忽略的额外字段。
 */
enum class ReasoningEffort(val wireValue: String, val label: String) {
    LOW("low", "快速"),
    MEDIUM("medium", "均衡"),
    HIGH("high", "深入");

    companion object {
        fun fromWire(value: String?): ReasoningEffort =
            entries.firstOrNull { it.wireValue == value } ?: MEDIUM
    }
}
