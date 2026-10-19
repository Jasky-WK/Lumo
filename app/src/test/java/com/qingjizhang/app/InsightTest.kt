package com.qingjizhang.app

import com.qingjizhang.app.data.AnomalyDetector
import com.qingjizhang.app.data.SuggestionEngine
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.data.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * P1 两个「纯本地智能」模块的单元测试：
 * - [SuggestionEngine]：记账建议（备注 / 时段分桶 / 金额档）
 * - [AnomalyDetector]：异常账单检测（疑似重复扣款 / 金额偏高）
 *
 * v2.5.1 按用户反馈补了两个关键场景：
 * 1. 「每天早上打车吃早饭」→ 早上 8 点应该建议早饭，而不是永远推荐同一个分类；
 * 2. 「每天中午一笔 15 元午饭」→ 这是规律消费，**不能**报成疑似重复扣款。
 */
class InsightTest {

    private val day = 24L * 60 * 60 * 1000

    private fun at(daysAgo: Long, hour: Int = 12): Long {
        val date = LocalDate.now().minusDays(daysAgo)
        return date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun txn(
        id: Long,
        type: TxnType = TxnType.EXPENSE,
        accountId: Long = 1L,
        categoryId: Long = 10L,
        amount: Long = 3800L,
        note: String = "",
        daysAgo: Long = 1L,
        hour: Int = 12
    ) = Txn(
        id = id,
        ledgerId = 1L,
        accountId = accountId,
        categoryId = categoryId,
        type = type,
        amountCents = amount,
        note = note,
        dateMillis = at(daysAgo, hour)
    )

    // ------------------------------------------------------------ 记账建议

    @Test
    fun suggest_byNoteKeyword() {
        val history = listOf(
            txn(1, note = "公司楼下咖啡", categoryId = 11, accountId = 2, daysAgo = 3),
            txn(2, note = "咖啡续杯", categoryId = 11, accountId = 2, daysAgo = 10),
            txn(3, note = "咖啡豆", categoryId = 11, accountId = 2, daysAgo = 20),
            txn(4, note = "午饭", categoryId = 12, accountId = 1, daysAgo = 2)
        )
        val s = SuggestionEngine.suggest(
            history, TxnType.EXPENSE, note = "楼下咖啡", amountCents = 3800, now = at(0, 13)
        )
        assertNotNull(s)
        assertEquals(11L, s!!.categoryId)
        assertEquals(2L, s.accountId)
        assertEquals(SuggestionEngine.Source.NOTE, s.source)
    }

    @Test
    fun suggest_needsEnoughSamples() {
        val history = listOf(txn(1, note = "咖啡", categoryId = 11))
        assertNull(SuggestionEngine.suggest(history, TxnType.EXPENSE, note = "咖啡", amountCents = 3800))
    }

    /** 用户场景 1：早上 8 点建议「早饭」，中午 12 点建议「午饭」——按时间段不同 */
    @Test
    fun suggest_changesByTimeSlot() {
        val breakfast = 30L
        val lunch = 31L
        val rides = 32L
        val history = listOf(
            txn(1, categoryId = breakfast, accountId = 1, amount = 800, note = "早饭", daysAgo = 1, hour = 8),
            txn(2, categoryId = breakfast, accountId = 1, amount = 900, note = "早饭", daysAgo = 2, hour = 8),
            txn(3, categoryId = rides, accountId = 1, amount = 1500, note = "打车", daysAgo = 3, hour = 9),
            txn(4, categoryId = lunch, accountId = 1, amount = 1500, note = "午饭", daysAgo = 1, hour = 12),
            txn(5, categoryId = lunch, accountId = 1, amount = 1600, note = "午饭", daysAgo = 2, hour = 12),
            txn(6, categoryId = lunch, accountId = 1, amount = 1500, note = "午饭", daysAgo = 5, hour = 13)
        )

        val morning = SuggestionEngine.suggest(
            history, TxnType.EXPENSE, note = "", amountCents = 800, now = at(0, 8)
        )
        assertNotNull(morning)
        assertEquals(breakfast, morning!!.categoryId)
        assertEquals(SuggestionEngine.Slot.MORNING, morning.slot)

        val noon = SuggestionEngine.suggest(
            history, TxnType.EXPENSE, note = "", amountCents = 1500, now = at(0, 12)
        )
        assertNotNull(noon)
        assertEquals(lunch, noon!!.categoryId)
        assertEquals(SuggestionEngine.Slot.NOON, noon.slot)
    }

    @Test
    fun suggest_ignoresOtherTypesAndTombstones() {
        val history = listOf(
            txn(1, type = TxnType.TRANSFER, categoryId = 31, accountId = 4),
            txn(2, type = TxnType.TRANSFER, categoryId = 31, accountId = 4),
            txn(3, categoryId = 31, accountId = 4, note = "咖啡", daysAgo = 2).copy(deletedAt = 1L)
        )
        assertNull(SuggestionEngine.suggest(history, TxnType.EXPENSE, note = "咖啡", amountCents = 3800))
        assertNull(SuggestionEngine.suggest(history, TxnType.TRANSFER, note = "", amountCents = 100))
    }

    // ------------------------------------------------------------ 异常检测

    /** 用户场景 2：每天中午一笔 15 元午饭 —— 规律消费，绝不能报「疑似重复扣款」 */
    @Test
    fun anomaly_dailyLunchIsNotDuplicate() {
        val history = (1..20).map {
            txn(it.toLong(), accountId = 1, amount = 1500, note = "午饭", daysAgo = it.toLong(), hour = 12)
        }
        val flags = AnomalyDetector.detect(history)
        assertTrue(
            "每天固定的午饭被误报成重复扣款：" + flags.map { it.title },
            flags.none { it.kind == AnomalyDetector.Kind.DUPLICATE }
        )
    }

    /** 低频（历史只出现过 2 次）且 7 天内重复 —— 才是真正的重复扣款 */
    @Test
    fun anomaly_detectsDuplicateCharge() {
        val history = listOf(
            txn(1, accountId = 1, amount = 3800, note = "超市", daysAgo = 2),
            txn(2, accountId = 1, amount = 3800, note = "超市", daysAgo = 1),
            txn(3, accountId = 1, amount = 1200, note = "地铁", daysAgo = 1)
        )
        val flags = AnomalyDetector.detect(history)
        val dup = flags.filter { it.kind == AnomalyDetector.Kind.DUPLICATE }
        assertEquals(1, dup.size)
        assertEquals(listOf(1L, 2L), dup.first().txnIds)
    }

    @Test
    fun anomaly_duplicateTooFarApartOrTooSmall() {
        val far = listOf(
            txn(1, amount = 3800, daysAgo = 12),
            txn(2, amount = 3800, daysAgo = 2)
        )
        val tiny = listOf(
            txn(3, amount = 50, daysAgo = 2),
            txn(4, amount = 50, daysAgo = 1)
        )
        assertTrue(AnomalyDetector.detect(far).none { it.kind == AnomalyDetector.Kind.DUPLICATE })
        assertTrue(AnomalyDetector.detect(tiny).none { it.kind == AnomalyDetector.Kind.DUPLICATE })
    }

    @Test
    fun anomaly_detectsUnusuallyLargeExpense() {
        // 25 笔十几元的日常支出 + 1 笔近 7 天的 3000 元（这一档金额以前没出现过）
        val history = (1..25).map {
            txn(it.toLong(), amount = 1000L + it * 10L, daysAgo = (it + 10).toLong())
        } + txn(99, amount = 300_000L, note = "换手机", daysAgo = 2)

        val flags = AnomalyDetector.detect(history)
        val large = flags.filter { it.kind == AnomalyDetector.Kind.LARGE }
        assertEquals(1, large.size)
        assertEquals(listOf(99L), large.first().txnIds)
    }

    /** 每月固定房租 3000 元 —— 金额高但完全正常，不该报 */
    @Test
    fun anomaly_fixedMonthlyRentIsNotFlagged() {
        val small = (1..25).map {
            txn(it.toLong(), amount = 1000L + it * 10L, daysAgo = (it + 20).toLong())
        }
        val rent = (1..6).map {
            txn(100L + it, amount = 300_000L, note = "房租", daysAgo = (it * 30).toLong())
        }
        val flags = AnomalyDetector.detect(small + rent)
        assertTrue(
            "固定房租被误报：" + flags.map { it.title },
            flags.none { it.kind == AnomalyDetector.Kind.LARGE }
        )
    }

    @Test
    fun anomaly_noLargeFlagWithTooFewSamples() {
        val history = (1..5).map { txn(it.toLong(), amount = 1000L, daysAgo = (it + 3).toLong()) } +
            txn(99, amount = 500_000L, daysAgo = 1)
        assertTrue(AnomalyDetector.detect(history).none { it.kind == AnomalyDetector.Kind.LARGE })
    }

    @Test
    fun anomaly_ignoresTrashAndIncome() {
        val history = listOf(
            txn(1, amount = 3800, daysAgo = 2).copy(deletedAt = 1L),
            txn(2, amount = 3800, daysAgo = 1).copy(deletedAt = 1L),
            txn(3, type = TxnType.INCOME, amount = 900_000, daysAgo = 1),
            txn(4, type = TxnType.INCOME, amount = 900_000, daysAgo = 2)
        )
        assertTrue(AnomalyDetector.detect(history).isEmpty())
    }
}
