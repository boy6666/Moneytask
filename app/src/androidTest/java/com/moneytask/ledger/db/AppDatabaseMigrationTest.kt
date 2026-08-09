package com.moneytask.ledger.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room v1 → v2 迁移回归（androidTest，需真机/模拟器执行：:app:connectedDebugAndroidTest）。
 *
 * 验证：从 v1 schema 造数据 → 执行 [MIGRATION_1_2] → 数据无损、新增列带默认值。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate1To2_keepsData_andFillsNewColumnsWithDefaults() {
        // 建 v1 库并写入一行分类 + 一行账目（用 v1 的列集合）。
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO categories (id,name,type,icon,isSystemDefault,createdAt,updatedAt) " +
                    "VALUES ('c1','餐饮','EXPENSE','🍜',1,0,0)"
            )
            execSQL(
                "INSERT INTO transactions " +
                    "(id,amountFen,type,time,accountId,categoryId,merchant,paymentMethod,note,groupId,source,isPendingReview,createdAt,updatedAt) " +
                    "VALUES ('t1',4210,'EXPENSE',100, NULL,'c1','美团',NULL,NULL,NULL,'AUTO',1,0,0)"
            )
            close()
        }

        // 执行迁移并校验 schema 演化到 v2。
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        // 存量行仍在。
        db.query("SELECT manuallyEdited FROM transactions WHERE id='t1'").use { c ->
            assertTrue("数据应保留", c.moveToFirst())
            assertEquals("新列默认 0", 0, c.getInt(0))
        }
        db.query("SELECT sortOrder FROM categories WHERE id='c1'").use { c ->
            assertTrue("数据应保留", c.moveToFirst())
            assertEquals("新列默认 0", 0, c.getInt(0))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
