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

    @Test
    fun migrate2To3_createsSettingsAndPeriods_keepsData() {
        // 建 v2 库并写入一行账目（用 v2 的表，含 v2 的 manuallyEdited 列）。
        helper.createDatabase(TEST_DB2, 2).apply {
            execSQL(
                "INSERT INTO transactions " +
                    "(id,amountFen,type,time,accountId,categoryId,merchant,paymentMethod,note,groupId,source,isPendingReview,manuallyEdited,createdAt,updatedAt) " +
                    "VALUES ('t1',4210,'EXPENSE',100, NULL,NULL,'美团',NULL,NULL,NULL,'AUTO',0,1,0,0)"
            )
            close()
        }

        // 执行 v2→v3 迁移并校验 schema 演化到 v3。
        val db = helper.runMigrationsAndValidate(TEST_DB2, 3, true, MIGRATION_2_3)

        // 存量账目仍在。
        db.query("SELECT manuallyEdited FROM transactions WHERE id='t1'").use { c ->
            assertTrue("数据应保留", c.moveToFirst())
        }
        // 新表可用：写入并读回设置与时段。
        db.execSQL("INSERT INTO app_settings (`key`,`value`,`updatedAt`) VALUES ('monthly_budget_fen','150000',0)")
        db.query("SELECT value FROM app_settings WHERE `key`='monthly_budget_fen'").use { c ->
            assertTrue("设置应可读", c.moveToFirst())
            assertEquals("预算应为 150000 分", "150000", c.getString(0))
        }
        db.execSQL(
            "INSERT INTO time_periods (id,name,type,startMillis,endMillis,createdAt) " +
                "VALUES ('p1','2026 暑假','SUMMER',0,86400000,0)"
        )
        db.query("SELECT name,type FROM time_periods WHERE id='p1'").use { c ->
            assertTrue("时段应可读", c.moveToFirst())
            assertEquals("2026 暑假", c.getString(0))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test"
        const val TEST_DB2 = "migration-test-2to3"
    }
}
