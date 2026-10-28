package com.lyentech.nano.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lyentech.nano.data.db.dao.ConversationDao
import com.lyentech.nano.data.db.dao.ProjectDao
import com.lyentech.nano.data.db.entity.ConversationEntity
import com.lyentech.nano.data.db.entity.ProjectEntity

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 应用自身数据的 Room 数据库（项目 + 会话元数据）。
 *
 * 用的是经典 androidx.room，和 ADK-Kotlin 依赖的是同一套运行时。与 ADK 的 session 库仍是
 * **两个独立的 sqlite 文件**：ADK 的 RoomSessionService 自带自己的 schema，不能把它的表
 * 并进这里的 @Database。
 */
@Database(
    entities = [ProjectEntity::class, ConversationEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class NanoDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao

    abstract fun conversationDao(): ConversationDao

    companion object {
        private const val DB_NAME = "nano_app.db"

        @Volatile private var instance: NanoDatabase? = null

        /** 进程内单例；Room 实例本身是线程安全的，重复构建只会浪费连接池。 */
        fun get(context: Context): NanoDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NanoDatabase::class.java,
                    DB_NAME,
                ).build().also { instance = it }
            }
    }
}
