package com.lyentech.nano.data.repo

import android.content.Context
import androidx.core.content.edit
import com.lyentech.nano.data.model.ReasoningEffort
import com.lyentech.nano.data.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 轻量偏好设置。主题与推理强度都是单个短值，用 SharedPreferences 即可，不值得引入 DataStore。
 *      值在内存里以 StateFlow 暴露，改一次界面立刻重绘，同时异步落盘。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(ThemeMode.fromName(prefs.getString(KEY_THEME, null)))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _reasoningEffort =
        MutableStateFlow(ReasoningEffort.fromWire(prefs.getString(KEY_EFFORT, null)))
    val reasoningEffort: StateFlow<ReasoningEffort> = _reasoningEffort.asStateFlow()

    /** 上一次选中的会话，用于冷启动直接回到用户上次的对话。 */
    private val _lastConversationId = MutableStateFlow(prefs.getString(KEY_LAST_CONVERSATION, null))
    val lastConversationId: StateFlow<String?> = _lastConversationId.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.update { mode }
        prefs.edit { putString(KEY_THEME, mode.name) }
    }

    fun setReasoningEffort(effort: ReasoningEffort) {
        _reasoningEffort.update { effort }
        prefs.edit { putString(KEY_EFFORT, effort.wireValue) }
    }

    fun setLastConversationId(id: String?) {
        _lastConversationId.update { id }
        prefs.edit { putString(KEY_LAST_CONVERSATION, id) }
    }

    private companion object {
        const val PREFS_NAME = "nano_settings"
        const val KEY_THEME = "theme_mode"
        const val KEY_EFFORT = "reasoning_effort"
        const val KEY_LAST_CONVERSATION = "last_conversation_id"
    }
}
