package com.qingjizhang.app

import com.qingjizhang.app.data.SuggestionEngine
import com.qingjizhang.app.data.SuggestionEngine.Source
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.data.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 记账建议引擎（v2.9.0）单测：备注词级重叠 / 工作日-周末信号 / 金额分档 / 底线回归。
 * 全部纯函数，不需要 Android 运行时。
 * 时间一律用固定日期算时间戳（引擎按系统时区解释 dateMillis，这里用同一时区，结果可重复），
 * 不使用 System.currentTimeMillis。
 *
 * 固定「今天」的两个基准点：
 * - 2026-10-17（周六）19:00 —— 晚上时段 + 周末；
 * - 2026-10-14（周三）19:00 —— 晚上时段 + 工作日。
 */
class SuggestionTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val nowSatEvening: Long = at(LocalDate.of(2026, 10, 17), 19)
    private val nowWedEvening: Long = at(LocalDate.of(2026, 10, 14), 19)

    /** 本地日期 + 小时 → 固定时间戳 */
    private fun at(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** 造一笔支出样本（其余字段用 Entities.kt 的默认值，deletedAt=0 正常态） */
    private fun txn(
        date: LocalDate,
        hour: Int,
        note: String = "",
        amountCents: Long = 0L,
        categoryId: Long = 1L,
        accountId: Long = 10L
    ) = Txn(
        ledgerId = 1L,
        accountId = accountId,
        categoryId = categoryId,
        type = TxnType.EXPENSE,
        amountCents = amountCents,
        note = note,
        dateMillis = at(date, hour)
    )

    /** 默认传支出类型，now 默认用周三晚（工作日 · 晚上） */
    private fun suggest(
        history: List<Txn>,
        note: String = "",
        amountCents: Long = 0L,
        now: Long = nowWedEvening
    ) = SuggestionEngine.suggest(history, TxnType.EXPENSE, note, amountCents, now)

    // ---------- 备注词级重叠 ----------

    @Test fun note_wordOverlap_starbucksHitsCoffee() {
        // 历史备注整串「星巴克咖啡」包含 query 的词「咖啡」→ 命中；
        // keyword 记的是命中的「词」而不是整串备注，reason 展示「备注有「咖啡」」
        val history = listOf(
            txn(LocalDate.of(2026, 10, 13), 19, note = "星巴克咖啡", categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 10, 12), 12, note = "星巴克咖啡", categoryId = 2L, accountId = 11L)
        )
        val s = suggest(history, note = "咖啡")!!
        assertEquals(2L, s.categoryId)
        assertEquals(11L, s.accountId)
        assertEquals("咖啡", s.keyword)
        assertEquals(Source.NOTE, s.source)
        assertEquals("备注有「咖啡」", s.reason)
    }

    @Test fun note_wordOverlap_queryLongerThanHistory() {
        // 反方向：query「星巴克」包含历史备注的词，keyword 取较短的历史词
        val history = listOf(
            txn(LocalDate.of(2026, 10, 13), 19, note = "星巴克咖啡", categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 10, 12), 12, note = "星巴克咖啡", categoryId = 2L, accountId = 11L)
        )
        val s = suggest(history, note = "星巴克")!!
        assertEquals("星巴克", s.keyword)
        assertEquals(Source.NOTE, s.source)
    }

    @Test fun note_wordOverlap_tokenReorder() {
        // 「咖啡 蛋糕」与「蛋糕 咖啡」：整串互相包含做不到，切词后任一词相同即命中
        val history = listOf(
            txn(LocalDate.of(2026, 9, 23), 10, note = "蛋糕 咖啡", categoryId = 3L, accountId = 12L),
            txn(LocalDate.of(2026, 9, 16), 10, note = "蛋糕 咖啡", categoryId = 3L, accountId = 12L)
        )
        val s = suggest(history, note = "咖啡 蛋糕")!!
        assertEquals(3L, s.categoryId)
        assertEquals("咖啡", s.keyword)
    }

    @Test fun note_singleCharOrEmptyQuery_silent() {
        // 单字 / 空 query 不得因备注得分。样本刻意放在不同时段 + 跨工作日/周末 + 非近期，
        // 除备注外没有任何信号，备注不命中就只能返回 null
        fun quiet(note: String) = listOf(
            txn(LocalDate.of(2026, 9, 26), 10, note = note, categoryId = 1L, accountId = 10L),
            txn(LocalDate.of(2026, 9, 19), 10, note = note, categoryId = 1L, accountId = 10L)
        )
        assertNull(suggest(quiet("星巴克咖啡"), note = "咖"))
        assertNull(suggest(quiet("打车"), note = "车"))
        assertNull(suggest(quiet("打车"), note = "打"))
        assertNull(suggest(quiet("咖啡"), note = ""))
        assertNull(suggest(quiet("咖啡"), note = "   "))
    }

    // ---------- 工作日 / 周末信号 ----------

    @Test fun weekend_comboWinsOnWeekend() {
        // 周六晚上：周末搭配 5 样本（时段 24 + 周末 6 = 30/条，共 150）
        // 压过工作日搭配 6 样本（只有时段 24/条，共 144）。
        // 没有 W_WEEKEND 的话 120 < 144，建议会被工作日样本抢走——本用例守的就是这点
        val history = listOf(
            txn(LocalDate.of(2026, 10, 3), 19, categoryId = 1L, accountId = 10L),
            txn(LocalDate.of(2026, 9, 26), 19, categoryId = 1L, accountId = 10L),
            txn(LocalDate.of(2026, 9, 19), 19, categoryId = 1L, accountId = 10L),
            txn(LocalDate.of(2026, 9, 12), 19, categoryId = 1L, accountId = 10L),
            txn(LocalDate.of(2026, 9, 5), 19, categoryId = 1L, accountId = 10L),
            txn(LocalDate.of(2026, 10, 8), 19, categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 10, 7), 19, categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 10, 6), 19, categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 10, 5), 19, categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 9, 30), 19, categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 9, 29), 19, categoryId = 2L, accountId = 11L)
        )
        val s = suggest(history, now = nowSatEvening)!!
        assertEquals(1L, s.categoryId)
        assertEquals(10L, s.accountId)
        assertEquals(Source.ROUTINE, s.source)
        assertEquals("晚上常记", s.reason)
    }

    @Test fun weekday_comboWinsOnWeekday() {
        // 周三晚上的镜像：工作日搭配 5 样本（150）压过周末搭配 6 样本（144）。
        // 信号是对称的——工作日样本在工作日同样该被加权
        val history = listOf(
            txn(LocalDate.of(2026, 10, 6), 19, categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 10, 5), 19, categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 9, 30), 19, categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 9, 29), 19, categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 9, 28), 19, categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 10, 4), 19, categoryId = 1L, accountId = 10L),
            txn(LocalDate.of(2026, 10, 3), 19, categoryId = 1L, accountId = 10L),
            txn(LocalDate.of(2026, 9, 27), 19, categoryId = 1L, accountId = 10L),
            txn(LocalDate.of(2026, 9, 26), 19, categoryId = 1L, accountId = 10L),
            txn(LocalDate.of(2026, 9, 20), 19, categoryId = 1L, accountId = 10L),
            txn(LocalDate.of(2026, 9, 19), 19, categoryId = 1L, accountId = 10L)
        )
        val s = suggest(history, now = nowWedEvening)!!
        assertEquals(2L, s.categoryId)
        assertEquals(11L, s.accountId)
        assertEquals(Source.ROUTINE, s.source)
    }

    // ---------- 金额分档（≤¥100 ±50% / ¥100~1000 ±30% / >¥1000 ±20%） ----------

    /**
     * 金额信号专用构造：样本 A 同时段、非近期、跨工作日/周末（最多拿时段 24 + 金额 10）；
     * 样本 B 不同时段、在 7 天窗口内（只拿近期 8）。两条同属一个搭配：
     * 金额命中时 34+8=42 ≥ 36 有建议，未命中时 24+8=32 < 36 返回 null——
     * 建议的有无只取决于金额档，与其他信号无关。
     */
    private fun amountPair(sampleCents: Long) = listOf(
        txn(LocalDate.of(2026, 9, 26), 19, amountCents = sampleCents, categoryId = 1L, accountId = 10L),
        txn(LocalDate.of(2026, 10, 10), 10, amountCents = sampleCents, categoryId = 1L, accountId = 10L)
    )

    @Test fun amount_tier_small_at100Yuan() {
        // ¥100 整：±50% 档。¥60（差 40%）命中；¥40（差 60%）不命中
        val hit = suggest(amountPair(6000L), amountCents = 10000L)!!
        assertEquals(1L, hit.categoryId)
        assertEquals(10L, hit.accountId)
        assertNull(suggest(amountPair(4000L), amountCents = 10000L))
    }

    @Test fun amount_tier_mid_justAbove100() {
        // ¥101：刚过 ¥100，进 ±30% 档。¥80（差 20.8%）命中；¥55（差 45.5%）不命中
        // ——¥55 放在 ±50% 档里是能过的，这里不过，证明容差确实收窄了
        val hit = suggest(amountPair(8000L), amountCents = 10100L)!!
        assertEquals(1L, hit.categoryId)
        assertNull(suggest(amountPair(5500L), amountCents = 10100L))
    }

    @Test fun amount_tier_mid_at1000Yuan() {
        // ¥1000 整：仍是 ±30% 档。¥750（差 25%）命中；¥650（差 35%）不命中
        val hit = suggest(amountPair(75000L), amountCents = 100000L)!!
        assertEquals(1L, hit.categoryId)
        assertNull(suggest(amountPair(65000L), amountCents = 100000L))
    }

    @Test fun amount_tier_large_justAbove1000() {
        // ¥1001：刚过 ¥1000，进 ±20% 档。¥850（差 15.1%）命中；¥750（差 25.1%）不命中
        // ——¥750 放在 ±30% 档里能过，这里不过，证明大额容差再收窄（房租不该被误判为接近）
        val hit = suggest(amountPair(85000L), amountCents = 100100L)!!
        assertEquals(1L, hit.categoryId)
        assertNull(suggest(amountPair(75000L), amountCents = 100100L))
    }

    // ---------- 备注命中回归：高频搭配不抢戏 ----------

    @Test fun noteHit_beatsHighFrequencyCombo() {
        // 回归现有逻辑：备注一旦有命中，只认命中的搭配。
        // 高频时段搭配（6 条同时段样本，总分更高）不能抢走备注命中的搭配（2 条）
        val history = listOf(
            txn(LocalDate.of(2026, 10, 13), 19, note = "星巴克咖啡", categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 10, 12), 12, note = "星巴克咖啡", categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 10, 8), 19, note = "午饭", categoryId = 4L, accountId = 13L),
            txn(LocalDate.of(2026, 10, 7), 19, note = "午饭", categoryId = 4L, accountId = 13L),
            txn(LocalDate.of(2026, 10, 6), 19, note = "午饭", categoryId = 4L, accountId = 13L),
            txn(LocalDate.of(2026, 10, 5), 19, note = "午饭", categoryId = 4L, accountId = 13L),
            txn(LocalDate.of(2026, 9, 30), 19, note = "午饭", categoryId = 4L, accountId = 13L),
            txn(LocalDate.of(2026, 9, 29), 19, note = "午饭", categoryId = 4L, accountId = 13L)
        )
        val s = suggest(history, note = "咖啡")!!
        assertEquals(2L, s.categoryId)
        assertEquals(11L, s.accountId)
        assertEquals("咖啡", s.keyword)
        assertEquals(Source.NOTE, s.source)
    }

    // ---------- 底线：宁可不建议 ----------

    @Test fun emptyHistory_returnsNull() {
        assertNull(suggest(emptyList(), note = "咖啡", amountCents = 2500L))
    }

    @Test fun insufficientSamples_returnsNull() {
        // 只有 1 条样本：即使备注 + 时段 + 近期全命中，也不够 MIN_SUPPORT
        val history = listOf(
            txn(LocalDate.of(2026, 10, 13), 19, note = "星巴克咖啡", categoryId = 2L, accountId = 11L)
        )
        assertNull(suggest(history, note = "咖啡"))
    }

    @Test fun supportTooSmall_returnsNull() {
        // 2 条备注命中样本分属不同搭配：各自 support=1 < 2，过不了底线
        val history = listOf(
            txn(LocalDate.of(2026, 10, 13), 19, note = "星巴克咖啡", categoryId = 2L, accountId = 11L),
            txn(LocalDate.of(2026, 10, 12), 19, note = "咖啡加餐", categoryId = 3L, accountId = 12L)
        )
        assertNull(suggest(history, note = "咖啡"))
    }
}
