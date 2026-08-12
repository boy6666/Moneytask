package com.moneytask.ledger.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2 迁移（存量数据无损）。
 *
 * v2 变更：
 *  - `transactions` 新增 `manuallyEdited`（人工修改/复核标记）。手动补录或复核改账后置 1，
 *    用于在列表 UI 上区分"机器自动落账"与"人工处理过"的账目。
 *  - `categories` 新增 `sortOrder`（分类排序）。补录 UI 里分类下拉按此排序展示。
 *
 * 均为 `ALTER TABLE ... ADD COLUMN` 且带默认值，因此旧的 v1 行不需回填即可兼容。
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN manuallyEdited INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE categories ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v2 → v3 迁移（新增两张表，无存量表结构变更，不影响旧数据）：
 *  - `app_settings`：键值设置（月度预算等）。
 *  - `time_periods`：寒假/暑假/学期/自定义时间段，供报表按时段分开统计。
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_settings` (" +
                "`key` TEXT NOT NULL, `value` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`key`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `time_periods` (" +
                "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                "`startMillis` INTEGER NOT NULL, `endMillis` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
    }
}
