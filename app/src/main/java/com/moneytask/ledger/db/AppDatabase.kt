package com.moneytask.ledger.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地账本文库（《MVP技术设计》3）。
 *
 * MVP schema 直接定为 version 1（含 accounts / categories / transactions）。
 * 后续若需演进（如新增 raw_captures / capture_groups 表做全链路审计），通过 Room Migration 升级——
 * 设计文档中的 Migration(1→2) 即针对该演进路径预留。
 */
@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "moneytask.db",
                ).build().also { instance = it }
            }
    }
}
