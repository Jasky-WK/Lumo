package com.qingjizhang.app

import com.qingjizhang.app.data.BackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.1 打磨项 ②：备份安全网。
 *
 * 守住的语义：
 * 1. 恢复影响能正确区分「会丢数据」与「会多数据」；
 * 2. 回退超过 10%（或 50 笔）判为严重风险，要红色二次确认；
 * 3. 从未备份 / 超过 30 天的提示文案正确。
 */
class BackupSafetyTest {

    private val DAY = 24L * 60 * 60 * 1000

    // ---------------- 恢复影响 ----------------

    @Test
    fun impact_regression_whenBackupHasFewerTxns() {
        val i = BackupManager.impactOf(backupTxns = 320, backupLedgers = 1, currentTxns = 400, currentLedgers = 1)
        assertEquals(80, i.lostTxns)
        assertEquals(0, i.gainedTxns)
        assertTrue(i.isRegression)
    }

    @Test
    fun impact_gain_whenBackupHasMoreTxns() {
        val i = BackupManager.impactOf(backupTxns = 500, backupLedgers = 1, currentTxns = 400, currentLedgers = 1)
        assertEquals(0, i.lostTxns)
        assertEquals(100, i.gainedTxns)
        assertFalse(i.isRegression)
    }

    @Test
    fun impact_equal_isNeitherLostNorGained() {
        val i = BackupManager.impactOf(400, 1, 400, 1)
        assertEquals(0, i.lostTxns)
        assertEquals(0, i.gainedTxns)
        assertFalse(i.isRegression)
    }

    @Test
    fun impact_negativeInputs_areClamped() {
        val i = BackupManager.impactOf(-5, -1, -3, -2)
        assertEquals(0, i.backupTxns)
        assertEquals(0, i.currentTxns)
        assertEquals(0, i.lostTxns)
    }

    // ---------------- 风险等级 ----------------

    @Test
    fun risk_zero_whenNoRegression() {
        assertEquals(0, BackupManager.riskLevel(BackupManager.impactOf(500, 1, 400, 1)))
        assertEquals(0, BackupManager.riskLevel(BackupManager.impactOf(400, 1, 400, 1)))
    }

    @Test
    fun risk_severe_whenLosingTenPercent() {
        // 丢 40 / 共 400 = 10%，达阈值
        val i = BackupManager.impactOf(360, 1, 400, 1)
        assertEquals(2, BackupManager.riskLevel(i))
    }

    @Test
    fun risk_severe_whenLosingFiftyOrMore() {
        // 2000 笔里丢 50 笔（2.5%），但绝对数量达 50，同样判严重
        val i = BackupManager.impactOf(1950, 1, 2000, 1)
        assertEquals(2, BackupManager.riskLevel(i))
    }

    @Test
    fun risk_mild_whenSmallRegression() {
        // 400 笔里丢 10 笔 = 2.5%，未达任何阈值
        val i = BackupManager.impactOf(390, 1, 400, 1)
        assertEquals(1, BackupManager.riskLevel(i))
    }

    // ---------------- 备份新鲜度 ----------------

    @Test
    fun days_null_whenNeverBackedUp() {
        assertNull(BackupManager.daysSince(0L, 1_000_000L))
    }

    @Test
    fun days_countsWholeDays() {
        val now = 100 * DAY
        assertEquals(0L, BackupManager.daysSince(now - (DAY / 2), now))
        assertEquals(3L, BackupManager.daysSince(now - 3 * DAY, now))
    }

    @Test
    fun days_futureTimestamp_doesNotGoNegative() {
        assertEquals(0L, BackupManager.daysSince(200 * DAY, 100 * DAY))
    }

    @Test
    fun staleness_neverBackedUp_warnsClearly() {
        val hint = BackupManager.stalenessHint(0L, 10 * DAY)
        assertTrue(hint!!.contains("还没有做过备份"))
    }

    @Test
    fun staleness_recentBackup_noHint() {
        val now = 100 * DAY
        assertNull(BackupManager.stalenessHint(now - 5 * DAY, now))
    }

    @Test
    fun staleness_overThirtyDays_hints() {
        val now = 100 * DAY
        val hint = BackupManager.stalenessHint(now - 31 * DAY, now)
        assertTrue(hint!!.contains("建议备份"))
        assertTrue(hint.contains("31"))
    }

    @Test
    fun staleness_overSixtyDays_isStronger() {
        val now = 100 * DAY
        val hint = BackupManager.stalenessHint(now - 70 * DAY, now)
        assertTrue(hint!!.contains("强烈建议"))
    }
}
