package com.qingjizhang.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.qingjizhang.app.data.AccountLedgerSync
import com.qingjizhang.app.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * N60：数据库迁移测试。
 *
 * 用手写 SQL 的迁移链最大的风险是「SQL 写错 → 用户升级闪退丢数据」。
 * 这里用 Robolectric 提供真实 SQLite，把库从旧版本一级级升到最新，
 * 并校验升级后的 schema 与 Room 期望的完全一致（表、列、索引），老数据原样保留。
 *
 * schema JSON 由构建时导出到 app/schemas/，再复制到 src/debug/assets/ 下
 * （只进 Debug 包，Release APK 不携带）；MigrationTestHelper 从 assets 读它们。
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    companion object {
        private const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList()
    )

    private val migrations = AppDatabase.allMigrations.toTypedArray()

    /** v14（= 已发布的 v2.3.4 / v2.3.5 结构）一路升到 v16：数据、表、列、索引全对 */
    @Test
    fun migrate14To16_keepsData_andAddsReceiptPhotosLedgerSoftDeleteIndices() {
        // 1) 建 v14 库，写入模拟老用户的真实数据（列必须写全：Room 建的表没有 DEFAULT）
        helper.createDatabase(TEST_DB, 14).use { db ->
            db.execSQL(
                "INSERT INTO ledger (id, name, isDefault, monthlyBudgetCents) " +
                    "VALUES (1, '日常账', 1, 50000)"
            )
            db.execSQL(
                "INSERT INTO account (id, ledgerId, name, type, initialBalanceCents, sortOrder, " +
                    "ledgerIdsCsv, countInNetWorth, hidden, note, creditDueDay, creditRemind, deletedAt) " +
                    "VALUES (1, 1, '支付宝', '网络账户', 100000, 0, '1', 1, 0, '', 0, 0, 0)"
            )
            db.execSQL(
                "INSERT INTO txn (id, ledgerId, accountId, transferToAccountId, categoryId, type, " +
                    "direction, amountCents, counterparty, note, dateMillis, createdAt, ruleId, " +
                    "excludeFromStats, excludeFromBudget, refundCents, feeCents, discountCents, " +
                    "settledTxnId, deletedAt, transferKind) " +
                    "VALUES (1, 1, 1, 0, 0, 'EXPENSE', 'NONE', 3800, '', '午餐', 1727000000000, " +
                    "1727000000000, 0, 0, 0, 0, 0, 0, 0, 0, '互转')"
            )
        }
        // 2) 升级到 16：schema 校验 + 依次跑 15→16 的迁移
        val db = helper.runMigrationsAndValidate(TEST_DB, 16, true, *migrations)

        // 3) 老数据原样还在；ledger 新列默认 0（= 未删除）
        db.query("SELECT name, monthlyBudgetCents, deletedAt FROM ledger WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("日常账", c.getString(0))
            assertEquals(50000L, c.getLong(1))
            assertEquals(0L, c.getLong(2))
        }
        db.query("SELECT amountCents, note FROM txn WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3800L, c.getLong(0))
            assertEquals("午餐", c.getString(1))
        }

        // 4) receipt_photos 表已存在且可写（v15 迁移建的）
        db.execSQL("INSERT INTO receipt_photos (txnId, filePath, createdAt) VALUES (1, '/x.jpg', 1)")

        // 5) v16 迁移建的 5 个索引都在（名字必须和实体注解生成的一致，否则 Room 校验会挂）
        listOf(
            "txn" to "index_txn_ledgerId_dateMillis",
            "txn" to "index_txn_ledgerId_deletedAt",
            "txn" to "index_txn_ruleId",
            "account" to "index_account_ledgerId_deletedAt",
            "receipt_photos" to "index_receipt_photos_txnId"
        ).forEach { (table, index) ->
            db.query("PRAGMA index_list(`$table`)").use { c ->
                var found = false
                while (c.moveToNext()) {
                    if (c.getString(1) == index) found = true
                }
                assertTrue("缺少索引 $index（$table）", found)
            }
        }

        // 6) 软删除语义：删账本只写 deletedAt，账本数据一行不动
        db.execSQL("UPDATE ledger SET deletedAt = 1727000000000 WHERE id = 1")
        db.query("SELECT COUNT(*) FROM ledger WHERE deletedAt > 0").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
        }
        db.query("SELECT COUNT(*) FROM txn").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
        }
    }

    /** v15（= v2.3.5-step 结构）升到 v16：小票数据保留，账本软删除与索引到位 */
    @Test
    fun migrate15To16_addsLedgerSoftDeleteAndIndices_keepsReceiptPhotos() {
        helper.createDatabase(TEST_DB, 15).use { db ->
            db.execSQL(
                "INSERT INTO ledger (id, name, isDefault, monthlyBudgetCents) " +
                    "VALUES (1, '日常账', 1, 0)"
            )
            db.execSQL(
                "INSERT INTO receipt_photos (id, txnId, filePath, createdAt) " +
                    "VALUES (1, 1, '/receipts/1/x.jpg', 1)"
            )
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 16, true, *migrations)

        db.query("SELECT filePath FROM receipt_photos WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("/receipts/1/x.jpg", c.getString(0))
        }
        db.query("SELECT deletedAt FROM ledger WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0L, c.getLong(0))
        }
        // 索引同样要建出来（从 v15 升上来的老库）
        db.query("PRAGMA index_list(txn)").use { c ->
            val names = mutableListOf<String>()
            while (c.moveToNext()) names += c.getString(1)
            assertTrue(names.contains("index_txn_ledgerId_dateMillis"))
            assertTrue(names.contains("index_txn_ruleId"))
        }
    }

    /** v16 升到 v17（P2-2）：account_ledgers 从 CSV 回填，同步幂等 */
    @Test
    fun migrate16To17_backfillsAccountLedgersFromCsv() {
        helper.createDatabase(TEST_DB, 16).use { db ->
            db.execSQL(
                "INSERT INTO ledger (id, name, isDefault, monthlyBudgetCents, deletedAt) " +
                    "VALUES (1, '日常账', 1, 0, 0)"
            )
            db.execSQL(
                "INSERT INTO ledger (id, name, isDefault, monthlyBudgetCents, deletedAt) " +
                    "VALUES (2, '家庭账', 0, 0, 0)"
            )
            // 空 CSV：只关联自己的账本
            db.execSQL(
                "INSERT INTO account (id, ledgerId, name, type, initialBalanceCents, sortOrder, " +
                    "ledgerIdsCsv, countInNetWorth, hidden, note, creditDueDay, creditRemind, deletedAt) " +
                    "VALUES (1, 1, '支付宝', '网络账户', 0, 0, '', 1, 0, '', 0, 0, 0)"
            )
            // CSV 多账本：1 和 2 都要进来
            db.execSQL(
                "INSERT INTO account (id, ledgerId, name, type, initialBalanceCents, sortOrder, " +
                    "ledgerIdsCsv, countInNetWorth, hidden, note, creditDueDay, creditRemind, deletedAt) " +
                    "VALUES (2, 1, '共用卡', '银行卡', 0, 1, '1,2', 1, 0, '', 0, 0, 0)"
            )
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 17, true, *migrations)

        db.query("SELECT ledgerId FROM account_ledgers WHERE accountId = 1 ORDER BY ledgerId").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertTrue("空 CSV 只应关联自己的账本", !c.moveToNext())
        }
        db.query("SELECT ledgerId FROM account_ledgers WHERE accountId = 2 ORDER BY ledgerId").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertTrue(c.moveToNext())
            assertEquals(2L, c.getLong(0))
            assertTrue(!c.moveToNext())
        }
        // 同步幂等：再跑一遍，行数不变（不会重复插）
        AccountLedgerSync.sync(db)
        db.query("SELECT COUNT(*) FROM account_ledgers").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3L, c.getLong(0))
        }
    }
}
