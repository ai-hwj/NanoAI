package com.lyentech.nano.agent.web

import android.util.Log

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 联网工具的日志出口。
 *
 * 为什么不让工具直接调 `android.util.Log`：这些类里承载的是搜索回退与解析的核心逻辑，
 * 需要能在宿主 JVM 上单测；而 `android.util.Log` 在单元测试里是会抛
 * `RuntimeException: Method d in android.util.Log not mocked` 的桩实现，
 * 一旦被测代码碰到它，测试就失败了。抽一层接口后，测试传 [NoOp] 即可。
 */
interface WebLog {
    fun warn(tag: String, message: String, error: Throwable? = null)

    /** 生产实现，走 logcat。默认按 `NanoWeb` 前缀过滤即可看到全部联网工具日志。 */
    object Default : WebLog {
        override fun warn(tag: String, message: String, error: Throwable?) {
            Log.w(tag, if (error == null) message else "$message: ${error.message}")
        }
    }

    /** 测试用空实现。 */
    object NoOp : WebLog {
        override fun warn(tag: String, message: String, error: Throwable?) = Unit
    }
}
