package com.qingjizhang.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qingjizhang.app.data.Account
import com.qingjizhang.app.data.AccountType
import com.qingjizhang.app.data.AppDatabase
import com.qingjizhang.app.data.Category
import com.qingjizhang.app.data.CategoryType
import com.qingjizhang.app.data.Direction
import com.qingjizhang.app.data.Ledger
import com.qingjizhang.app.data.Repository
import com.qingjizhang.app.data.TransferKind
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
import java.time.temporal.ChronoUnit

/**
 * T8（v2.9.0 P1-8）：大数据量性能守卫 —— 把「1 万笔账单下统计不卡」固化成 CI 回归线。
 *
 * 守护的性能水位（阈值见 [STATS_AGG_THRESHOLD_MS] / [SQL_AGG_THRESHOLD_MS]，超了挂测试）：
 * - 路径 A（统计聚合）：复刻 AppViewModel 的真实统计路径（统计锚点取上一个自然月，
 *   避免月初 / 当天样本量波动，整月数据也更压秤）——
 *   statsTxns（近 6 个月窗口取数 + N20「不计入净资产」账户过滤）→ buildStats(kind=1 逐月)
 *   （区间过滤 → countsInStats 口径过滤 → effectiveCents 收支合计 → 分类分桶（二级归并一级）
 *   → 逐月趋势 6 桶）→ 日历页 calDays 逐日汇总 → 全量 txns 上的 Repository.accountMonths
 *   （24 个月逐月流水）与 balancesOf（总览净资产卡）。
 *   buildStats 是 private 无法直接调用，这里按源码结构逐段复刻；口径一律调用 Repository
 *   的真实函数（countsInStats / effectiveCents / inStatsAccountScope），不自己重写口径语义。
 * - 路径 B（SQL 下推聚合）：N60 的 TxnDao.observeMonthIncomeExpense /
 *   observeBudgetUsedCents（首页当月收支 / 当月预算已用），并与 Kotlin 口径对拍，
 *   确保守住的不是「快但算错」的数字。
 *
 * 造数规模：10,000 笔，日期均匀铺满近 24 个月（逐月分桶有真实压力），覆盖全部口径：
 * 支出 / 收入 / 互转（4 种子类型）/ 借贷（借出 + 借入 + 还款流水）/ 不计收支 / 不计预算 /
 * 二级分类 / 报销退款（含退款超额被钳到 0）/ 手续费 / 优惠 /「不计入净资产」账户 / 回收站软删除。
 *
 * 为什么用固定种子：java.util.Random(SEED) 让金额与口径组合完全可复现 —— 耗时波动只能
 * 来自机器性能，不会来自数据集漂移；换机器 / 换 CI 跑出来的数字才可比、可回归。
 *
 * 计时口径：插入用批量事务、**不计入**被测耗时；System.nanoTime 只包住统计调用本身，
 * 超阈值 assert 失败并打印实际耗时（诊断信息）。@Test(timeout) 只是整个方法的挂死兜底。
 *
 * 降级预案：若 Robolectric 造 1 万笔在 CI 上慢到拖爆整体超时，把 [TXN_COUNT] 降到 5,000
 * 并在此注明理由（真机 TTID / 滚动 / 报表 / 搜索 / 翻月走查由父代理负责，不走本测试）。
 */
@RunWith(RobolectricTestRunner::class)
class PerfGuardTest {

    /** 造出来的大账本（id + 账户 / 分类 / 排除账户 + 回收站笔数） */
    private data class BigLedger(
        val ledgerId: Long,
        val accounts: List<Account>,
        val categories: List<Category>,
        val excludedAccountIds: Set<Long>,
        val deletedCount: Int
    )

    /** 路径 A 的聚合结果（用来断言「真的算了东西」，避免空数据下虚过） */
    private data class StatsAggResult(
        val incomeCents: Long,
        val expenseCents: Long,
        val txnCount: Int,
        val buckets: Int,
        val monthTrend: List<Long>,
        val activeDays: Int,
        val accountMonths: Int
    )

    @Test(timeout = TEST_TIMEOUT_MS)
    fun tenThousandTxns_statsAggregationAndSqlPushdown_stayUnderWatermark() = runBlocking {
        val db = freshDb()
        try {
            // 造数（批量事务，耗时不计入被测路径）
            val data = seed(db)
            // 统计锚点取「上一个自然月」而不是今天：月初当天只有个位数样本、月末也不完整，
            // 锚整月让分桶 / 对拍的样本量稳定（约 400 笔），比随机日期更压秤也更可复现
            val anchor = LocalDate.now().withDayOfMonth(1).minusMonths(1)

            // 造数自检：行数对得上（软删除的也在库里，只是查询口径看不见）
            assertEquals(
                (TXN_COUNT - data.deletedCount).toLong(),
                db.txnDao().count(data.ledgerId).toLong()
            )

            // ---------- 路径 A：统计聚合（复刻 statsTxns → buildStats → calDays → accountMonths） ----------
            val aggStart = System.nanoTime()
            val agg = runStatsAggregation(db, data, anchor)
            val aggMs = (System.nanoTime() - aggStart) / 1_000_000
            println(
                "PerfGuard 路径A 统计聚合：${aggMs}ms" +
                    "（阈值 ${STATS_AGG_THRESHOLD_MS}ms；$TXN_COUNT 笔 / 近 24 个月）"
            )
            assertTrue(
                "路径A 统计聚合耗时 ${aggMs}ms 超过水位 ${STATS_AGG_THRESHOLD_MS}ms" +
                    "（$TXN_COUNT 笔 / 近 24 个月 / 6 个月窗口 + 全量逐月流水）",
                aggMs < STATS_AGG_THRESHOLD_MS
            )

            // ---------- 路径 B：SQL 下推聚合（N60：首页当月收支 / 当月预算已用） ----------
            val (monthStart, monthEnd) = DateFmt.monthRange(anchor)
            // 空集合传哨兵 -1：NOT IN (-1) 恒真（与 AppViewModel.monthStats 一致）
            val sentinel = data.excludedAccountIds.ifEmpty { setOf(-1L) }

            val sqlStart = System.nanoTime()
            val monthSum = db.txnDao()
                .observeMonthIncomeExpense(data.ledgerId, monthStart, monthEnd, sentinel).first()
            val sqlMs = (System.nanoTime() - sqlStart) / 1_000_000
            println(
                "PerfGuard 路径B 当月收支 SQL 聚合：${sqlMs}ms" +
                    "（阈值 ${SQL_AGG_THRESHOLD_MS}ms）"
            )
            assertTrue(
                "路径B 当月收支 SQL 聚合耗时 ${sqlMs}ms 超过水位 ${SQL_AGG_THRESHOLD_MS}ms",
                sqlMs < SQL_AGG_THRESHOLD_MS
            )

            val budgetStart = System.nanoTime()
            val budgetUsed = db.txnDao()
                .observeBudgetUsedCents(data.ledgerId, monthStart, monthEnd, sentinel).first()
            val budgetMs = (System.nanoTime() - budgetStart) / 1_000_000
            println(
                "PerfGuard 路径B 当月预算已用 SQL 聚合：${budgetMs}ms" +
                    "（阈值 ${SQL_AGG_THRESHOLD_MS}ms）"
            )
            assertTrue(
                "路径B 当月预算已用 SQL 聚合耗时 ${budgetMs}ms 超过水位 ${SQL_AGG_THRESHOLD_MS}ms",
                budgetMs < SQL_AGG_THRESHOLD_MS
            )

            // ---------- 正确性对拍：SQL 与 Kotlin 口径必须一致 ----------
            val all = db.txnDao().byLedger(data.ledgerId)
            var refIncome = 0L
            var refExpense = 0L
            var refBudget = 0L
            all.filter { it.dateMillis in monthStart until monthEnd }
                .filter { Repository.inStatsAccountScope(it, data.excludedAccountIds) }
                .forEach { t ->
                    if (Repository.countsInStats(t)) {
                        when (t.type) {
                            TxnType.INCOME -> refIncome += Repository.effectiveCents(t)
                            TxnType.EXPENSE -> refExpense += Repository.effectiveCents(t)
                            else -> Unit
                        }
                    }
                    if (Repository.countsInBudget(t)) refBudget += Repository.effectiveCents(t)
                }
            assertEquals(refIncome, monthSum.incomeCents)
            assertEquals(refExpense, monthSum.expenseCents)
            assertEquals(refBudget, budgetUsed)
            // 复刻的路径 A 当月收支与 SQL 对拍一致（证明计时没有包住空转）
            assertEquals(monthSum.incomeCents, agg.incomeCents)
            assertEquals(monthSum.expenseCents, agg.expenseCents)

            // ---------- 数据集要有统计压力：非空才算守住水位 ----------
            assertTrue("当月应有收入样本", agg.incomeCents > 0L)
            assertTrue("当月应有支出样本", agg.expenseCents > 0L)
            assertTrue("当月应有成规模的口径样本", agg.txnCount > 100)
            assertTrue("分类分桶应不少于 3 个桶", agg.buckets >= 3)
            assertTrue("逐月趋势至少一个桶非零", agg.monthTrend.any { it > 0L })
            assertTrue("日历当月应有交易日", agg.activeDays > 0)
            assertTrue("账户逐月至少 12 个月有流水", agg.accountMonths >= 12)
        } finally {
            db.close()
        }
    }

    private suspend fun freshDb(): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    /**
     * 内存库造 [TXN_COUNT] 笔账单（批量事务插入，插入耗时不计入被测路径）。
     * 口径组合与金额全部由固定种子的 [java.util.Random] 决定，跨机器可复现。
     */
    private suspend fun seed(db: AppDatabase): BigLedger {
        val rnd = java.util.Random(SEED)
        val ledgerId = db.ledgerDao().insert(
            Ledger(name = "性能账本", isDefault = true, monthlyBudgetCents = 500_000)
        )

        // 6 个账户：4 个资金账户 + 1 个借贷账户 + 1 个「不计入净资产」账户（压 N20 过滤路径）
        val accountSpecs = listOf(
            Account(ledgerId = ledgerId, name = "微信", type = AccountType.NETWORK, sortOrder = 0),
            Account(ledgerId = ledgerId, name = "支付宝", type = AccountType.NETWORK, sortOrder = 1),
            Account(ledgerId = ledgerId, name = "招行卡", type = AccountType.BANK_CARD, sortOrder = 2),
            Account(ledgerId = ledgerId, name = "现金", type = AccountType.CASH, sortOrder = 3),
            Account(ledgerId = ledgerId, name = "借贷账户", type = AccountType.LEND, sortOrder = 4),
            Account(
                ledgerId = ledgerId, name = "排除账户", type = AccountType.OTHER, sortOrder = 5,
                countInNetWorth = false
            )
        )
        val accounts = accountSpecs.map { it.copy(id = db.accountDao().insert(it)) }
        val lendAccountId = accounts.first { it.type == AccountType.LEND }.id
        // 资金账户 = 除借贷账户外的全部（含「不计入净资产」账户：它的流水要被统计口径排除）
        val fundAccounts = accounts.filter { it.type != AccountType.LEND }.map { it.id }

        // 分类：9 个一级支出（「餐饮」下挂 3 个二级）+ 4 个一级收入
        val foodCatId = db.categoryDao().insert(
            Category(
                ledgerId = ledgerId, name = "餐饮", type = CategoryType.EXPENSE,
                isPreset = true, sortOrder = 0
            )
        )
        val expenseChildIds = listOf("午餐", "下午茶", "夜宵").mapIndexed { i, name ->
            db.categoryDao().insert(
                Category(
                    ledgerId = ledgerId, name = name, type = CategoryType.EXPENSE,
                    parentId = foodCatId, sortOrder = i
                )
            )
        }
        val expenseParentIds = mutableListOf(foodCatId)
        expenseParentIds += listOf("交通", "购物", "娱乐", "医疗", "学习", "人情", "居家", "通讯")
            .mapIndexed { i, name ->
                db.categoryDao().insert(
                    Category(
                        ledgerId = ledgerId, name = name, type = CategoryType.EXPENSE,
                        isPreset = true, sortOrder = i + 1
                    )
                )
            }
        val incomeParentIds = listOf("工资", "兼职", "红包", "报销").mapIndexed { i, name ->
            db.categoryDao().insert(
                Category(
                    ledgerId = ledgerId, name = name, type = CategoryType.INCOME,
                    isPreset = true, sortOrder = i
                )
            )
        }
        val categories = db.categoryDao().byLedger(ledgerId)

        // 日期：近 24 个月（最早那个月的 1 号起）均匀铺开，逐月分桶有真实压力
        val today = LocalDate.now()
        val spanStart = today.minusMonths(23).withDayOfMonth(1)
        val spanDays = ChronoUnit.DAYS.between(spanStart, today).toInt() + 1
        fun randomMillis(): Long =
            DateFmt.toMillis(spanStart.plusDays(rnd.nextInt(spanDays).toLong())) +
                rnd.nextInt(86_400_000)

        val notes = arrayOf("午餐", "打车", "超市采购", "咖啡", "话费", "房租", "工资", "兼职", "还款", "理财")
        val counterparties = arrayOf("", "", "", "公司", "好友")

        val txns = ArrayList<Txn>(TXN_COUNT)
        val lendIds = ArrayList<Long>() // 已生成的借贷账单 id（还款流水引用它们）
        var deletedCount = 0
        for (i in 0 until TXN_COUNT) {
            // 全新内存库 + 顺序插入 ⇒ 第 i 笔的自增 id 必然是 i + 1
            val predictedId = i + 1L
            val dateMillis = randomMillis()
            val r = rnd.nextInt(100)

            var type = TxnType.EXPENSE
            var accountId = 0L
            var transferToAccountId = 0L
            var categoryId = 0L
            var direction = Direction.NONE
            var amountCents = 0L
            var excludeFromStats = false
            var excludeFromBudget = false
            var refundCents = 0L
            var feeCents = 0L
            var discountCents = 0L
            var settledTxnId = 0L
            var transferKind = TransferKind.COMMON

            when {
                r < 55 -> {
                    // 普通支出：40% 落到二级分类，10% 带报销 / 手续费 / 优惠
                    type = TxnType.EXPENSE
                    accountId = fundAccounts[rnd.nextInt(fundAccounts.size)]
                    categoryId = if (rnd.nextInt(100) < 40) {
                        expenseChildIds[rnd.nextInt(expenseChildIds.size)]
                    } else {
                        expenseParentIds[rnd.nextInt(expenseParentIds.size)]
                    }
                    amountCents = (100 + rnd.nextInt(100_000)).toLong()
                    if (rnd.nextInt(100) < 10) {
                        // 其中 5% 退款超额（实计被钳到 0，守住 SQL MAX(...,0) 分支），其余退一半
                        refundCents = if (rnd.nextInt(100) < 5) {
                            amountCents * 2
                        } else {
                            amountCents / 2
                        }
                        feeCents = rnd.nextInt(500).toLong()
                        discountCents = rnd.nextInt(1_000).toLong()
                    }
                }

                r < 58 -> {
                    // 不计入收支的支出（仍改变账户余额）
                    type = TxnType.EXPENSE
                    accountId = fundAccounts[rnd.nextInt(fundAccounts.size)]
                    categoryId = expenseParentIds[rnd.nextInt(expenseParentIds.size)]
                    amountCents = (100 + rnd.nextInt(100_000)).toLong()
                    excludeFromStats = true
                }

                r < 73 -> {
                    // 收入
                    type = TxnType.INCOME
                    accountId = fundAccounts[rnd.nextInt(fundAccounts.size)]
                    categoryId = incomeParentIds[rnd.nextInt(incomeParentIds.size)]
                    amountCents = (10_000 + rnd.nextInt(3_000_000)).toLong()
                    if (rnd.nextInt(100) < 5) refundCents = rnd.nextInt(amountCents.toInt()).toLong()
                }

                r < 76 -> {
                    // 不计入收支的收入
                    type = TxnType.INCOME
                    accountId = fundAccounts[rnd.nextInt(fundAccounts.size)]
                    categoryId = incomeParentIds[rnd.nextInt(incomeParentIds.size)]
                    amountCents = (10_000 + rnd.nextInt(3_000_000)).toLong()
                    excludeFromStats = true
                }

                r < 86 -> {
                    // 互转（互转 / 还款 / 理财买入 / 理财赎回）
                    type = TxnType.TRANSFER
                    accountId = fundAccounts[rnd.nextInt(fundAccounts.size)]
                    transferToAccountId = fundAccounts
                        .filter { it != accountId }[rnd.nextInt(fundAccounts.size - 1)]
                    amountCents = (1_000 + rnd.nextInt(5_000_000)).toLong()
                    transferKind = TransferKind.presets[rnd.nextInt(TransferKind.presets.size)]
                }

                r < 94 -> {
                    // 借贷：借出 / 借入；约两成是还款流水（引用更早的借贷账单）
                    type = TxnType.LEND_BORROW
                    accountId = fundAccounts[rnd.nextInt(fundAccounts.size)]
                    transferToAccountId = lendAccountId
                    direction = if (rnd.nextBoolean()) Direction.LEND else Direction.BORROW
                    amountCents = (5_000 + rnd.nextInt(2_000_000)).toLong()
                    if (lendIds.isNotEmpty() && rnd.nextInt(100) < 20) {
                        settledTxnId = lendIds[rnd.nextInt(lendIds.size)]
                    } else {
                        lendIds += predictedId
                    }
                }

                else -> {
                    // 不计入当月预算的支出（仍计入收支）
                    type = TxnType.EXPENSE
                    accountId = fundAccounts[rnd.nextInt(fundAccounts.size)]
                    categoryId = expenseParentIds[rnd.nextInt(expenseParentIds.size)]
                    amountCents = (100 + rnd.nextInt(100_000)).toLong()
                    excludeFromBudget = true
                }
            }

            // 约 1% 进回收站（软删除）：查询口径必须把它们排除在外
            val deletedAt = if (rnd.nextInt(100) < 1) {
                deletedCount++
                DateFmt.toMillis(today)
            } else {
                0L
            }

            txns += Txn(
                ledgerId = ledgerId,
                accountId = accountId,
                transferToAccountId = transferToAccountId,
                categoryId = categoryId,
                type = type,
                direction = direction,
                amountCents = amountCents,
                counterparty = counterparties[rnd.nextInt(counterparties.size)],
                note = notes[rnd.nextInt(notes.size)],
                dateMillis = dateMillis,
                createdAt = dateMillis,
                ruleId = 0L,
                excludeFromStats = excludeFromStats,
                excludeFromBudget = excludeFromBudget,
                refundCents = refundCents,
                feeCents = feeCents,
                discountCents = discountCents,
                settledTxnId = settledTxnId,
                deletedAt = deletedAt,
                transferKind = transferKind
            )
        }

        // 批量事务插入：Room @Insert(List) 单语句绑定参数有上限（约 3.2 万），500 行一批稳妥
        val insertStart = System.nanoTime()
        txns.chunked(INSERT_BATCH).forEach { db.txnDao().insertAll(it) }
        println(
            "PerfGuard 造数：$TXN_COUNT 笔，" +
                "耗时 ${(System.nanoTime() - insertStart) / 1_000_000}ms" +
                "（批量事务，不计入被测耗时）"
        )

        return BigLedger(
            ledgerId = ledgerId,
            accounts = accounts,
            categories = categories,
            excludedAccountIds = Repository.statsExcludedAccountIds(accounts),
            deletedCount = deletedCount
        )
    }

    /**
     * 路径 A 被测体：复刻 AppViewModel 的真实统计路径（buildStats 是 private，无法直接调用）。
     * 结构按源码逐段对齐，口径一律调用 Repository 的真实函数：
     * statsTxns → buildStats(kind=1) → calDays → accountMonths / balancesOf。
     */
    private suspend fun runStatsAggregation(
        db: AppDatabase,
        data: BigLedger,
        anchor: LocalDate
    ): StatsAggResult {
        // ---- statsTxns：逐月周期（kind=1）取数窗口 = 近 6 个月；取出后按 N20 账户范围过滤 ----
        val windowStart = DateFmt.toMillis(anchor.minusMonths(5).withDayOfMonth(1))
        val windowEnd = DateFmt.toMillis(anchor.withDayOfMonth(1).plusMonths(1))
        val windowTxns = db.txnDao()
            .observeByLedgerBetween(data.ledgerId, windowStart, windowEnd).first()
            .filter { Repository.inStatsAccountScope(it, data.excludedAccountIds) }

        // ---- buildStats(kind=1)：当月区间 → 会计口径 → 收支合计 ----
        val (rangeStart, rangeEnd) = DateFmt.monthRange(anchor)
        val inRange = windowTxns.filter { it.dateMillis in rangeStart until rangeEnd }
        val stats = inRange.filter { Repository.countsInStats(it) }
        val income = stats.filter { it.type == TxnType.INCOME }.sumOf { Repository.effectiveCents(it) }
        val expense = stats.filter { it.type == TxnType.EXPENSE }.sumOf { Repository.effectiveCents(it) }

        // ---- 分类分桶（catMode=0：二级归并一级；VM 用 firstOrNull 线性查找，如实复刻）----
        val catOf = { id: Long -> data.categories.firstOrNull { it.id == id } }
        // 与 buildReport 的 parentIdOf 一致：二级分类归到一级，本身就是一级就归自己
        val parentIdOf = { id: Long -> catOf(id)?.let { if (it.parentId != 0L) it.parentId else it.id } ?: 0L }
        val nameOf = { id: Long -> catOf(id)?.name ?: "未分类" }
        val grouped = mutableMapOf<String, Long>()
        stats.filter { it.type == TxnType.EXPENSE }.forEach { t ->
            val key = parentIdOf(t.categoryId).takeIf { it != 0L }?.let { p -> nameOf(p) } ?: "未分类"
            grouped[key] = (grouped[key] ?: 0L) + Repository.effectiveCents(t)
        }

        // ---- 逐月趋势 6 桶：每桶一次全量 filter（VM 的 O(桶数 × n) 路径）----
        val monthTrend = (5 downTo 0).map { anchor.minusMonths(it.toLong()) }.map { m ->
            val r = DateFmt.monthRange(m)
            windowTxns.filter {
                Repository.countsInStats(it) && it.type == TxnType.EXPENSE &&
                    it.dateMillis in r.first until r.second
            }.sumOf { Repository.effectiveCents(it) }
        }

        // ---- 日历页 calDays：当月逐日汇总（单遍扫窗口）----
        val calDays = MutableList(anchor.lengthOfMonth()) { 0L }
        windowTxns.forEach { t ->
            if (t.dateMillis in rangeStart until rangeEnd && Repository.countsInStats(t)) {
                val idx = DateFmt.dateTimeOf(t.dateMillis).dayOfMonth - 1
                if (idx in calDays.indices) calDays[idx] += Repository.effectiveCents(t)
            }
        }

        // ---- 全量 txns（ViewModel.txns 同款全量读取）→ accountMonths / balancesOf ----
        val allTxns = db.txnDao().byLedger(data.ledgerId)
        val repo = Repository(db)
        val mainAccount = data.accounts.first { it.id !in data.excludedAccountIds }
        val months = repo.accountMonths(mainAccount, allTxns) { DateFmt.monthKey(it) }
        repo.balancesOf(data.accounts, allTxns)

        return StatsAggResult(
            incomeCents = income,
            expenseCents = expense,
            txnCount = stats.size,
            buckets = grouped.size,
            monthTrend = monthTrend,
            activeDays = calDays.count { it != 0L },
            accountMonths = months.size
        )
    }

    companion object {
        /** 路径 A 水位：统计聚合（含窗口取数 / 全量逐月流水）< 2000ms */
        private const val STATS_AGG_THRESHOLD_MS = 2_000L

        /** 路径 B 水位：SQL 下推聚合（当月收支 / 当月预算已用）< 1000ms */
        private const val SQL_AGG_THRESHOLD_MS = 1_000L

        /** 造数规模：1 万笔（CI 上过慢时按文件头注释降级到 5,000） */
        private const val TXN_COUNT = 10_000

        /** 固定随机种子：同一数据集跨机器可复现，耗时数字才可比 */
        private const val SEED = 20_250_918L

        /** 批量插入行数：Room @Insert 单语句绑定参数有上限（约 3.2 万），500 行一批稳妥 */
        private const val INSERT_BATCH = 500

        /** 整个测试方法的挂死兜底超时（含 Robolectric 启动 + 造数），非性能断言 */
        private const val TEST_TIMEOUT_MS = 240_000L
    }
}
