package com.hermes.chat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        /** 1→2：为 messages 表追加 reasoning / toolCallsJson 两列（ALTER 不破坏已有数据）。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN toolCallsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 2→3：修正 v1.2.0 初版 1→2 迁移把列名误写成 tool_calls 的问题。
         * 部分设备已跑过坏迁移（库版本升到 2，但列名是 tool_calls），这里兜底纠回 toolCallsJson；
         * 全新安装（仍停留在 v1）会先跑 1→2 再跑本步，此时 toolCallsJson 已存在则跳过。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(messages)").use { cursor ->
                    val nameIdx = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) existing.add(cursor.getString(nameIdx))
                }
                if ("tool_calls" in existing && "toolCallsJson" !in existing) {
                    db.execSQL("ALTER TABLE messages RENAME COLUMN tool_calls TO toolCallsJson")
                } else if ("toolCallsJson" !in existing) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN toolCallsJson TEXT NOT NULL DEFAULT ''")
                }
                if ("reasoning" !in existing) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        /** 3→4：新增 is_streaming 列，标记仍在后台 Service 流式输出的助手消息。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN is_streaming INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun build(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hermes_chat.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                INSTANCE = instance
                instance
            }
    }
}
