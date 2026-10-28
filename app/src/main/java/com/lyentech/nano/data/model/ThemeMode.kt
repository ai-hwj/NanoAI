package com.lyentech.nano.data.model

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 主题模式。DARK / LIGHT 是用户显式选择，SYSTEM 跟随系统。
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        fun fromName(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
