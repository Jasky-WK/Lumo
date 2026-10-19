package com.qingjizhang.app.data

import com.qingjizhang.app.util.DateFmt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

/**
 * 周期记账引擎。
 *
 * 纯本地、不联网、也没有常驻后台服务，所以采用「打开 App 时按规则补齐」的策略：
 * 每次启动把上次生成时间到当前时间内所有该生成的账单一次性补上，不会漏记。
 */
object RecurringEngine {

    private const val MAX_ITERATION = 600

    /** 补齐可能被并发触发（Application 启动 + ViewModel 初始化），加锁避免重复入账 */
    private val catchUpMutex = Mutex()

    /** 计算某个执行日的毫秒值：用规则的开始时刻，避免生成出来都是 00:00 */
    private fun atRuleTime(date: LocalDate, startMillis: Long): Long {
        val time = java.time.Instant.ofEpochMilli(startMillis)
            .atZone(ZoneId.systemDefault()).toLocalTime()
        return DateFmt.toMillis(date.atTime(time))
    }

    /**
     * 第 n 期的日期。始终以「开始日期」为锚点推算，
     * 这样 1/31 的月度规则是 1/31 → 2/28 → 3/31 → 4/30，不会被逐月压缩成 28 号。
     */
    private fun occurrenceDate(start: LocalDate, freq: Freq, n: Int): LocalDate = when (freq) {
        Freq.DAILY -> start.plusDays(n.toLong())
        Freq.WEEKLY -> start.plusWeeks(n.toLong())
        Freq.MONTHLY -> addMonthsClamped(start, n.toLong())
        Freq.YEARLY -> addMonthsClamped(start, n.toLong() * 12)
    }

    /** 月末日期跨月时按当月最后一天收敛（1/31 + 1 月 -> 2/28） */
    private fun addMonthsClamped(date: LocalDate, months: Long): LocalDate {
        val target = date.plusMonths(months)
        val maxDay = target.lengthOfMonth()
        return if (date.dayOfMonth > maxDay) target.withDayOfMonth(maxDay) else target
    }

    /** 已生成的最后一期（毫秒）；没生成过则返回 0 */
    private fun lastGenerated(rule: RecurringRule): Long = rule.lastGeneratedMillis

    /**
     * 列出该规则从上次生成到 now 之间所有应生成的时间点（毫秒），按时间升序。
     */
    fun dueDates(rule: RecurringRule, now: Long): List<Long> {
        if (!rule.enabled) return emptyList()
        val start = DateFmt.dateOf(rule.startMillis)
        val interval = rule.interval.coerceAtLeast(1)
        val lastMs = lastGenerated(rule)
        val result = mutableListOf<Long>()
        var count = rule.generatedCount
        var n = 0

        while (n < MAX_ITERATION) {
            val ms = atRuleTime(occurrenceDate(start, rule.freq, n * interval), rule.startMillis)
            if (ms > now) break
            // 结束日期是「含当天」的，所以按自然日比较，而不是按毫秒
            if (rule.endMode == EndMode.UNTIL && rule.endMillis > 0 &&
                DateFmt.dateOf(ms) > DateFmt.dateOf(rule.endMillis)
            ) break
            if (rule.endMode == EndMode.COUNT && rule.endCount > 0 && count >= rule.endCount) break
            if (lastMs <= 0L || ms > lastMs) {
                result += ms
                count++
            }
            n++
        }
        return result
    }

    /** 下一次会生成的时间点，用于列表上的「下次 xx」；没有下一次返回 null */
    fun nextRun(rule: RecurringRule, now: Long): Long? {
        val start = DateFmt.dateOf(rule.startMillis)
        val interval = rule.interval.coerceAtLeast(1)
        val lastMs = maxOf(lastGenerated(rule), 0L)
        var n = 0
        while (n < MAX_ITERATION) {
            val ms = atRuleTime(occurrenceDate(start, rule.freq, n * interval), rule.startMillis)
            val ended = (rule.endMode == EndMode.UNTIL && rule.endMillis > 0 &&
                DateFmt.dateOf(ms) > DateFmt.dateOf(rule.endMillis)) ||
                (rule.endMode == EndMode.COUNT && rule.endCount > 0 && rule.generatedCount >= rule.endCount)
            if (ended) return null
            if (ms > now && (lastMs <= 0L || ms > lastMs)) return ms
            n++
        }
        return null
    }

    /** 是否已到结束条件 */
    fun isFinished(rule: RecurringRule, now: Long): Boolean {
        if (rule.endMode == EndMode.UNTIL && rule.endMillis > 0 && now > rule.endMillis) return true
        if (rule.endMode == EndMode.COUNT && rule.endCount > 0 && rule.generatedCount >= rule.endCount) return true
        return false
    }

    /** 一年会生成几次，用于规则预览 */
    fun timesPerYear(rule: RecurringRule): Int {
        val interval = rule.interval.coerceAtLeast(1)
        return when (rule.freq) {
            Freq.DAILY -> 365 / interval
            Freq.WEEKLY -> 52 / interval
            Freq.MONTHLY -> 12 / interval
            Freq.YEARLY -> (1.0 / interval).let { if (it < 1) 1 else it.toInt() }
        }
    }

    /**
     * 启动时把所有启用规则的欠账补齐。
     * @return 本次生成的账单数
     */
    suspend fun catchUp(db: AppDatabase, now: Long = System.currentTimeMillis()): Int =
        catchUpMutex.withLock {
            doCatchUp(db, now)
        }

    private suspend fun doCatchUp(db: AppDatabase, now: Long): Int {
        var generated = 0
        // N59：账本软删除后它的周期规则不再补生成账单，还原账本后照旧继续
        for (rule in db.ruleDao().enabledRulesOfLiveLedgers()) {
            val due = dueDates(rule, now)
            if (due.isEmpty()) continue
            val txns = due.map { ms ->
                Txn(
                    ledgerId = rule.ledgerId,
                    accountId = rule.accountId,
                    transferToAccountId = rule.transferToAccountId,
                    categoryId = rule.categoryId,
                    type = rule.type,
                    direction = Direction.NONE,
                    amountCents = rule.amountCents,
                    counterparty = rule.counterparty,
                    note = if (rule.note.isBlank()) rule.name else rule.note,
                    dateMillis = ms,
                    createdAt = now,
                    ruleId = rule.id
                )
            }
            db.txnDao().insertAll(txns)
            db.ruleDao().update(
                rule.copy(
                    lastGeneratedMillis = due.last(),
                    generatedCount = rule.generatedCount + due.size
                )
            )
            generated += txns.size
        }
        return generated
    }

    /** 某条规则当前有多少笔待生成（用于「本月有 N 笔待生成」提示） */
    fun pendingCount(rule: RecurringRule, now: Long): Int = dueDates(rule, now).size

    fun freqLabel(freq: Freq): String = when (freq) {
        Freq.DAILY -> "每天"
        Freq.WEEKLY -> "每周"
        Freq.MONTHLY -> "每月"
        Freq.YEARLY -> "每年"
    }

    fun describe(rule: RecurringRule): String {
        val interval = rule.interval.coerceAtLeast(1)
        val base = if (interval == 1) freqLabel(rule.freq) else "每 $interval 个周期"
        val d = DateFmt.dateOf(rule.startMillis)
        return when (rule.freq) {
            Freq.DAILY -> base
            Freq.WEEKLY -> "$base 周" + "一二三四五六日"[d.dayOfWeek.value - 1]
            Freq.MONTHLY -> "$base ${d.dayOfMonth} 日"
            Freq.YEARLY -> "$base ${d.monthValue} 月 ${d.dayOfMonth} 日"
        }
    }
}
