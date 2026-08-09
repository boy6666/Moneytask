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
