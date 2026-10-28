package com.lyentech.nano.http.gree

import android.util.Log

/**
 * @author by jason-何伟杰，2026/10/10
 * des: 云端模型的日志出口。理由和 `agent/web/WebLog` 一样：
 *      GreeChatModel 里值得测的正是"请求怎么拼、响应怎么拆"，而 `android.util.Log` 在宿主 JVM
 *      单测里是会抛 `Method w in android.util.Log not mocked` 的桩实现 —— 工具调用一旦让模型
 *      在成功路径上打一行日志，没这层抽象就没法在写活体测试之前先跑起来。
 */
interface GreeLog {

    fun info(tag: String, message: String)

    fun warn(tag: String, message: String)

    /** 生产实现，走 logcat，按 `adb logcat -s NanoGreeModel` 过滤。 */
    object Default : GreeLog {
        override fun info(tag: String, message: String) {
            Log.i(tag, message)
        }

        override fun warn(tag: String, message: String) {
            Log.w(tag, message)
        }
    }

    /** 测试用空实现。 */
    object NoOp : GreeLog {
        override fun info(tag: String, message: String) = Unit

        override fun warn(tag: String, message: String) = Unit
    }
}
