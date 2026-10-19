package com.qingjizhang.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qingjizhang.app.data.Account
import com.qingjizhang.app.data.AppDatabase
import com.qingjizhang.app.data.Category
import com.qingjizhang.app.data.CategoryType
import com.qingjizhang.app.data.Ledger
import com.qingjizhang.app.data.Repository
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.data.TxnType
import com.qingjizhang.app.util.DateFmt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * N60：统计 / 搜索 SQL 下推的一致性测试。
 *
 * 统计聚合（当月收支、当月预算已用）改成 SQL 后，必须与 Repository 里的 Kotlin
 * 口径算出一模一样的结果——这里用一批覆盖边界的账单对拍（退款超额、手续费 /
 * 优惠、不计收支、不计预算、排除账户、互转借贷、跨月边界）。
 */
@RunWith(RobolectricTestRunner::class)
class SqlPushdownTest {

    private suspend fun freshDb(): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun monthSumAndBudget_sqlMatchesKotlin() = runBlocking {
        val db = freshDb()
        try {
            val ledgerId = db.ledgerDao().insert(Ledger(name = "测试账", isDefault = true))
            val accId = db.accountDao().insert(
                Account(ledgerId = ledgerId, name = "现金", type = "现金与储值")
            )
            val excludedAccId = db.accountDao().insert(
                Account(ledgerId = ledgerId, name = "排除账户", type = "其他", countInNetWorth = false)
            )
            val catId = db.categoryDao().insert(
                Category(ledgerId = ledgerId, name = "餐饮", type = CategoryType.EXPENSE, isPreset = true)
            )

            val monthStart = LocalDate.now().withDayOfMonth(1)
            fun at(day: Int) = DateFmt.toMillis(monthStart.plusDays(day.toLong() - 1))
            listOf(
                // 普通支出 / 收入
                Txn(ledgerId = ledgerId, accountId = accId, categoryId = catId, type = TxnType.EXPENSE, amountCents = 3800, dateMillis = at(1)),
                Txn(ledgerId = ledgerId, accountId = accId, categoryId = catId, type = TxnType.INCOME, amountCents = 1200000, dateMillis = at(2)),
                // 退款 > 金额 → 实计 0
                Txn(ledgerId = ledgerId, accountId = accId, categoryId = catId, type = TxnType.EXPENSE, amountCents = 1000, refundCents = 5000, dateMillis = at(3)),
                // 手续费 + 优惠 → 实计 9700
                Txn(ledgerId = ledgerId, accountId = accId, categoryId = catId, type = TxnType.EXPENSE, amountCents = 10000, feeCents = 200, discountCents = 500, dateMillis = at(4)),
                // 不计入收支（仍进预算之外的一切）
                Txn(ledgerId = ledgerId, accountId = accId, categoryId = catId, type = TxnType.EXPENSE, amountCents = 9999, excludeFromStats = true, dateMillis = at(5)),
                // 排除账户名下的支出（整笔不进统计）
                Txn(ledgerId = ledgerId, accountId = excludedAccId, categoryId = catId, type = TxnType.EXPENSE, amountCents = 7777, dateMillis = at(6)),
                // 不计入预算（但计入收支）
                Txn(ledgerId = ledgerId, accountId = accId, categoryId = catId, type = TxnType.EXPENSE, amountCents = 5000, excludeFromBudget = true, dateMillis = at(7)),
                // 互转 / 借贷不计收支
                Txn(ledgerId = ledgerId, accountId = accId, transferToAccountId = excludedAccId, type = TxnType.TRANSFER, amountCents = 100, dateMillis = at(8)),
                Txn(ledgerId = ledgerId, accountId = accId, transferToAccountId = excludedAccId, type = TxnType.LEND_BORROW, amountCents = 200, dateMillis = at(9)),
                // 上个月 / 下个月：不在当月区间
                Txn(ledgerId = ledgerId, accountId = accId, categoryId = catId, type = TxnType.EXPENSE, amountCents = 12345, dateMillis = DateFmt.toMillis(monthStart.minusMonths(1).plusDays(2))),
                Txn(ledgerId = ledgerId, accountId = accId, categoryId = catId, type = TxnType.INCOME, amountCents = 54321, dateMillis = DateFmt.toMillis(monthStart.plusMonths(1).plusDays(1)))
            ).forEach { db.txnDao().insert(it) }

            val (s, e) = DateFmt.monthRange(LocalDate.now())
            val all = db.txnDao().byLedger(ledgerId)
            val excluded = Repository.statsExcludedAccountIds(db.accountDao().byLedger(ledgerId))

            // Kotlin 参考实现（与改造前的 VM 逻辑一致）
            var income = 0L
            var expense = 0L
            var budget = 0L
            all.forEach { t ->
                if (t.dateMillis in s until e && Repository.inStatsAccountScope(t, excluded)) {
                    if (Repository.countsInStats(t)) {
                        when (t.type) {
                            TxnType.INCOME -> income += Repository.effectiveCents(t)
                            TxnType.EXPENSE -> expense += Repository.effectiveCents(t)
                            else -> Unit
                        }
                    }
                    if (Repository.countsInBudget(t)) budget += Repository.effectiveCents(t)
                }
            }

            val sentinel = excluded.ifEmpty { setOf(-1L) }
            val sql = db.txnDao().observeMonthIncomeExpense(ledgerId, s, e, sentinel).first()
            val sqlBudget = db.txnDao().observeBudgetUsedCents(ledgerId, s, e, sentinel).first()

            assertEquals(income, sql.incomeCents)
            assertEquals(expense, sql.expenseCents)
            assertEquals(budget, sqlBudget)
        } finally {
            db.close()
        }
    }

    /** 搜索下推：文本 / 金额 / 类型 / 分类 / 账户各维度 + LIKE 转义 */
    @Test
    fun search_pushdownMatchesExpectations() = runBlocking {
        val db = freshDb()
        try {
            val ledgerId = db.ledgerDao().insert(Ledger(name = "测试账", isDefault = true))
            val accId = db.accountDao().insert(
                Account(ledgerId = ledgerId, name = "支付宝", type = "网络账户")
            )
            val otherAccId = db.accountDao().insert(
                Account(ledgerId = ledgerId, name = "现金", type = "现金与储值")
            )
            val catId = db.categoryDao().insert(
                Category(ledgerId = ledgerId, name = "餐饮美食", type = CategoryType.EXPENSE, isPreset = true)
            )
            val now = System.currentTimeMillis()
            val ids = listOf(
                db.txnDao().insert(Txn(ledgerId = ledgerId, accountId = accId, categoryId = catId, type = TxnType.EXPENSE, amountCents = 3800, note = "公司楼下午餐", dateMillis = now)),
                db.txnDao().insert(Txn(ledgerId = ledgerId, accountId = otherAccId, categoryId = catId, type = TxnType.INCOME, amountCents = 1200000, note = "工资", dateMillis = now - 1000)),
                db.txnDao().insert(Txn(ledgerId = ledgerId, accountId = accId, categoryId = catId, type = TxnType.TRANSFER, transferToAccountId = otherAccId, amountCents = 50000, note = "转到现金", dateMillis = now - 2000))
            )

            suspend fun search(q: String, catIds: List<Long> = emptyList(), accIds: List<Long> = emptyList(), types: List<String> = emptyList()) =
                db.txnDao().observeSearch(
                    ledgerId, q,
                    catIds.ifEmpty { listOf(-1L) },
                    accIds.ifEmpty { listOf(-1L) },
                    types.ifEmpty { listOf("__no_match__") }
                ).first().map { it.id }

            // 备注包含
            assertEquals(listOf(ids[0]), search("午餐"))
            // 金额：38 命中 38.00；12 命中 12000.00
            assertEquals(listOf(ids[0]), search("38"))
            assertEquals(listOf(ids[1]), search("12"))
            // 类型名（VM 的 searchTypes 会把「互转」解析成 TRANSFER 传下来）
            assertEquals(listOf(ids[2]), search("互转", types = listOf("TRANSFER")))
            // 分类名 / 账户名（按 id）
            assertEquals(listOf(ids[0], ids[1], ids[2]), search("餐饮", catIds = listOf(catId)).sorted())
            assertEquals(listOf(ids[0], ids[2]), search("支付宝", accIds = listOf(accId)).sorted())
            // LIKE 转义：VM 会先把 % _ \ 转掉再进 SQL，这里按同样方式转义后按字面匹配
            fun esc(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            assertTrue(search(esc("%")).isEmpty())
            assertTrue(search(esc("_")).isEmpty())
        } finally {
            db.close()
        }
    }
}
