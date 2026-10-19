package com.qingjizhang.app

import com.qingjizhang.app.data.Category
import com.qingjizhang.app.data.CategoryType
import com.qingjizhang.app.data.Direction
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.data.TxnType
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.vm.buildCatTrend
import com.qingjizhang.app.vm.buildCumulativeSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * v2.9.0 报表三图的计算单测（对应任务 T7）。
 *
 * 被测对象是 `vm/ReportCharts.kt` 里的两个纯函数：
 * - [buildCumulativeSeries]：月内逐日累计（只画到今天 / 过去的月份画满）
 * - [buildCatTrend]：一级分类近 12 个月逐月金额（子分类上卷到父分类）
 *
 * 纯 JUnit，不需要 Robolectric：输入账单列表，输出数字。
 */
class ReportChartTest {

    private val oct2026 = LocalDate.of(2026, 10, 18) // 固定「今天」

    private fun ms(d: LocalDate): Long = DateFmt.toMillis(d)

    private fun txn(
        type: TxnType,
        amount: Long,
        date: LocalDate,
        categoryId: Long = 0L,
        excludeFromStats: Boolean = false,
        refund: Long = 0L,
        fee: Long = 0L,
        discount: Long = 0L
    ) = Txn(
        ledgerId = 1L,
        accountId = 1L,
        categoryId = categoryId,
        type = type,
        direction = Direction.NONE,
        amountCents = amount,
        dateMillis = ms(date),
        excludeFromStats = excludeFromStats,
        refundCents = refund,
        feeCents = fee,
        discountCents = discount
    )

    private fun monthRange(d: LocalDate): Pair<Long, Long> =
        DateFmt.toMillis(d.withDayOfMonth(1)) to
            DateFmt.toMillis(d.withDayOfMonth(1).plusMonths(1))

    // ---------------------------------------------------------------- 累计曲线

    @Test
    fun cumulative_midMonthDrawsOnlyUpToToday() {
        val (start, end) = monthRange(LocalDate.of(2026, 10, 1))
        val list = listOf(
            txn(TxnType.EXPENSE, 1000, LocalDate.of(2026, 10, 1)),
            txn(TxnType.EXPENSE, 2000, LocalDate.of(2026, 10, 18)),
            // 未来的账（理论上不会存在，但必须被截断，不能出现在累计里）
            txn(TxnType.EXPENSE, 9999, LocalDate.of(2026, 10, 20))
        )
        val cum = buildCumulativeSeries(list, start, end, ms(oct2026))
        assertEquals(18, cum.dayCount)
        assertEquals(18, cum.expense.size)
        assertEquals(1000L, cum.expense[0])
        // 1 号到 17 号都是 1000，18 号加上 2000
        assertEquals(1000L, cum.expense[16])
        assertEquals(3000L, cum.expense[17])
    }

    @Test
    fun cumulative_pastMonthDrawsWholeMonth() {
        val sep = LocalDate.of(2026, 9, 1)
        val (start, end) = monthRange(sep)
        val list = listOf(
            txn(TxnType.EXPENSE, 500, LocalDate.of(2026, 9, 1)),
            txn(TxnType.EXPENSE, 700, LocalDate.of(2026, 9, 30))
        )
        val cum = buildCumulativeSeries(list, start, end, ms(oct2026))
        assertEquals(30, cum.dayCount)
        assertEquals(500L, cum.expense[0])
        assertEquals(1200L, cum.expense[29])
    }

    @Test
    fun cumulative_excludesTransfersAndExcludedFromStats() {
        val (start, end) = monthRange(LocalDate.of(2026, 10, 1))
        val list = listOf(
            txn(TxnType.EXPENSE, 1000, LocalDate.of(2026, 10, 2)),
            // 互转：不计入收支
            txn(TxnType.TRANSFER, 5000, LocalDate.of(2026, 10, 2)),
            // 勾了「不计入收支」：只动余额，不进累计
            txn(TxnType.EXPENSE, 4000, LocalDate.of(2026, 10, 3), excludeFromStats = true),
            // 收入：进收入线
            txn(TxnType.INCOME, 20000, LocalDate.of(2026, 10, 4))
        )
        val cum = buildCumulativeSeries(list, start, end, ms(oct2026))
        assertEquals(1000L, cum.expense.last())
        assertEquals(20000L, cum.income.last())
    }

    @Test
    fun cumulative_usesEffectiveCents() {
        val (start, end) = monthRange(LocalDate.of(2026, 10, 1))
        // 金额 100 元，退款 20 元，优惠 5 元，手续费 3 元 → 78 元
        val list = listOf(
            txn(
                TxnType.EXPENSE, 10_000, LocalDate.of(2026, 10, 5),
                refund = 2_000, discount = 500, fee = 300
            )
        )
        val cum = buildCumulativeSeries(list, start, end, ms(oct2026))
        assertEquals(7_800L, cum.expense.last())
    }

    @Test
    fun cumulative_futureMonthIsEmpty() {
        val next = LocalDate.of(2026, 11, 1)
        val (start, end) = monthRange(next)
        val cum = buildCumulativeSeries(emptyList(), start, end, ms(oct2026))
        assertEquals(0, cum.dayCount)
        assertTrue(cum.expense.isEmpty())
        assertTrue(cum.income.isEmpty())
    }

    // ---------------------------------------------------------------- 分类趋势

    private val cats = listOf(
        Category(id = 1L, ledgerId = 1L, name = "餐饮", type = CategoryType.EXPENSE),
        Category(id = 11L, ledgerId = 1L, name = "午饭", type = CategoryType.EXPENSE, parentId = 1L),
        Category(id = 12L, ledgerId = 1L, name = "晚饭", type = CategoryType.EXPENSE, parentId = 1L),
        Category(id = 2L, ledgerId = 1L, name = "工资", type = CategoryType.INCOME)
    )

    private fun endOfOct2026(): Long =
        DateFmt.toMillis(LocalDate.of(2026, 11, 1))

    @Test
    fun catTrend_rollsChildCategoriesUpToParent() {
        val list = listOf(
            txn(TxnType.EXPENSE, 1000, LocalDate.of(2026, 10, 1), categoryId = 11L),
            txn(TxnType.EXPENSE, 2000, LocalDate.of(2026, 10, 2), categoryId = 12L),
            txn(TxnType.EXPENSE, 500, LocalDate.of(2026, 10, 2), categoryId = 1L)
        )
        val (labels, map) = buildCatTrend(list, cats, endOfOct2026())
        assertEquals(12, labels.size)
        // 窗口是 2025/11 ~ 2026/10，最后一个月是 2026/10
        assertEquals("2025/11月", labels[0])
        assertEquals("10月", labels[11])
        val dining = map[1L] ?: error("餐饮应有数据")
        assertEquals(3500L, dining[11])
        assertEquals(12, dining.size)
    }

    @Test
    fun catTrend_跨年_dataLandsInRightBucket() {
        val list = listOf(
            txn(TxnType.EXPENSE, 777, LocalDate.of(2025, 12, 20), categoryId = 11L),
            txn(TxnType.EXPENSE, 888, LocalDate.of(2026, 1, 5), categoryId = 11L)
        )
        val (labels, map) = buildCatTrend(list, cats, endOfOct2026())
        assertEquals("12月", labels[1])
        assertEquals("2026/1月", labels[2])
        val dining = map[1L]!!
        assertEquals(777L, dining[1])
        assertEquals(888L, dining[2])
        assertEquals(0L, dining[11])
    }

    @Test
    fun catTrend_excludesTransfersAndExcludedStats() {
        val list = listOf(
            txn(TxnType.TRANSFER, 10_000, LocalDate.of(2026, 10, 1), categoryId = 1L),
            txn(TxnType.EXPENSE, 10_000, LocalDate.of(2026, 10, 1), categoryId = 11L, excludeFromStats = true),
            txn(TxnType.EXPENSE, 300, LocalDate.of(2026, 10, 1), categoryId = 11L)
        )
        val (_, map) = buildCatTrend(list, cats, endOfOct2026())
        assertEquals(300L, map[1L]!![11])
    }

    @Test
    fun catTrend_usesEffectiveCents() {
        val list = listOf(
            txn(
                TxnType.INCOME, 20_000, LocalDate.of(2026, 10, 1),
                categoryId = 2L, refund = 1_000, fee = 200
            )
        )
        val (_, map) = buildCatTrend(list, cats, endOfOct2026())
        // 20_000 - 1_000 + 200 = 19_200
        assertEquals(19_200L, map[2L]!![11])
    }
}
