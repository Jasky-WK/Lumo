package com.qingjizhang.app.vm

import com.qingjizhang.app.data.Category
import com.qingjizhang.app.data.Repository
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.data.TxnType
import com.qingjizhang.app.util.DateFmt

/**
 * v2.9.0 报表三图的纯计算（从 AppViewModel.buildReport 抽出，便于单测）。
 *
 * 这里只做「按天 / 按月分桶 + 累计」，口径完全交给 Repository：
 * - 是否计入统计：`Repository.countsInStats`
 * - 金额：`Repository.effectiveCents`（金额 − 退款 − 优惠 + 手续费，下限 0）
 *
 * 不持有任何状态、不碰数据库，输入账单列表即可计算，所以单测不需要 Android 环境。
 */

/** 月内累计曲线数据（两条线与 [dayCount] 等长） */
internal data class CumulativeSeries(
    val income: List<Long>,
    val expense: List<Long>,
    val dayCount: Int
)

/**
 * 月内逐日累计收入 / 支出。
 *
 * - 当月只画到今天（[nowMs]），过去的月份画满整月；
 * - 未来月份、或区间起点晚于今天时返回空；
 * - 互转 / 借贷不计入累计（只算收入与支出）。
 */
internal fun buildCumulativeSeries(
    list: List<Txn>,
    startMs: Long,
    endMs: Long,
    nowMs: Long
): CumulativeSeries {
    val first = DateFmt.dateOf(startMs)
    val monthLast = DateFmt.dateOf(endMs - 1)
    val today = DateFmt.dateOf(nowMs)
    if (first.isAfter(today)) return CumulativeSeries(emptyList(), emptyList(), 0)
    val last = if (monthLast.isAfter(today)) today else monthLast
    val dayCount = java.time.temporal.ChronoUnit.DAYS.between(first, last).toInt() + 1
    if (dayCount <= 0) return CumulativeSeries(emptyList(), emptyList(), 0)

    val incomeByDay = LongArray(dayCount)
    val expenseByDay = LongArray(dayCount)
    list.forEach { t ->
        if (!Repository.countsInStats(t)) return@forEach
        if (t.dateMillis < startMs || t.dateMillis >= endMs) return@forEach
        val idx = java.time.temporal.ChronoUnit.DAYS
            .between(first, DateFmt.dateOf(t.dateMillis)).toInt()
        if (idx !in 0 until dayCount) return@forEach
        when (t.type) {
            TxnType.INCOME -> incomeByDay[idx] += Repository.effectiveCents(t)
            TxnType.EXPENSE -> expenseByDay[idx] += Repository.effectiveCents(t)
            else -> Unit
        }
    }
    val income = ArrayList<Long>(dayCount)
    val expense = ArrayList<Long>(dayCount)
    var si = 0L
    var se = 0L
    for (i in 0 until dayCount) {
        si += incomeByDay[i]
        se += expenseByDay[i]
        income += si
        expense += se
    }
    return CumulativeSeries(income, expense, dayCount)
}

/**
 * 一级分类近 12 个月逐月金额。
 *
 * 窗口 = 所选范围的最后一个月往前推 12 个月（含当月）。
 * 返回 (月份标签, 一级分类 id -> 12 个金额)；无分类的账单 key = 0。
 * 标签跨年时首月写「2025/12月」，其余写「3月」，保证 X 轴看得出年份切换。
 */
internal fun buildCatTrend(
    list: List<Txn>,
    cats: List<Category>,
    endMs: Long
): Pair<List<String>, Map<Long, List<Long>>> {
    val endDate = DateFmt.dateOf(endMs - 1)
    val endMonth = java.time.LocalDate.of(endDate.year, endDate.monthValue, 1)
    val startMonth = endMonth.minusMonths(11)
    val labels = (0..11).map { i ->
        val m = startMonth.plusMonths(i.toLong())
        // 跨年时把年份写出来：窗口首月与每年 1 月都带年份，其余只写月份
        if (i == 0 || m.monthValue == 1) "${m.year}/${m.monthValue}月" else "${m.monthValue}月"
    }
    val parentOf = HashMap<Long, Long>()
    cats.forEach { parentOf[it.id] = if (it.parentId != 0L) it.parentId else it.id }
    val buckets = HashMap<Long, LongArray>()
    list.forEach { t ->
        if (!Repository.countsInStats(t)) return@forEach
        if (t.type != TxnType.EXPENSE && t.type != TxnType.INCOME) return@forEach
        val d = DateFmt.dateOf(t.dateMillis)
        val idx = java.time.temporal.ChronoUnit.MONTHS
            .between(startMonth, java.time.LocalDate.of(d.year, d.monthValue, 1)).toInt()
        if (idx !in 0..11) return@forEach
        val pid = parentOf[t.categoryId] ?: t.categoryId
        buckets.getOrPut(pid) { LongArray(12) }[idx] += Repository.effectiveCents(t)
    }
    return labels to buckets.mapValues { it.value.toList() }
}
