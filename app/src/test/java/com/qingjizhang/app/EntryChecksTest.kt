package com.qingjizhang.app

import com.qingjizhang.app.ui.EntryChecks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.1 打磨项 ③：记一笔的即时校验。
 *
 * 设计原则：**提示但不阻断**。余额不足仍可保存（允许透支后补），
 * 只有「互转/借贷选同一个账户」这种毫无意义的组合才在 UI 上置灰。
 */
class EntryChecksTest {

    // ---------------- 互转同账户 ----------------

    @Test
    fun transfer_sameAccount_detected() {
        assertTrue(EntryChecks.isSameAccountForTransfer(5L, 5L))
    }

    @Test
    fun transfer_differentAccounts_ok() {
        assertFalse(EntryChecks.isSameAccountForTransfer(5L, 6L))
    }

    @Test
    fun transfer_zeroIds_notTreatedAsSame() {
        // 未选择时都是 0，不能误判成「同一个账户」
        assertFalse(EntryChecks.isSameAccountForTransfer(0L, 0L))
        assertFalse(EntryChecks.isSameAccountForTransfer(0L, 5L))
        assertFalse(EntryChecks.isSameAccountForTransfer(5L, 0L))
    }

    // ---------------- 借贷同账户 ----------------

    @Test
    fun lend_sameAccount_detected() {
        assertTrue(EntryChecks.isSameAccountForLend(3L, 3L))
        assertFalse(EntryChecks.isSameAccountForLend(3L, 4L))
        assertFalse(EntryChecks.isSameAccountForLend(0L, 0L))
    }

    // ---------------- 余额提示（不阻断） ----------------

    @Test
    fun balance_overdraft_showsHint() {
        val hint = EntryChecks.balanceHint(
            isExpense = true, isCredit = false,
            balanceCents = 1000L, amountCents = 1500L
        )
        assertNotNull(hint)
        assertTrue(hint!!.contains("超过"))
        assertTrue(hint.contains("仍可保存"))     // 明确告知不阻断
    }

    @Test
    fun balance_withinBalance_noHint() {
        assertNull(
            EntryChecks.balanceHint(true, false, balanceCents = 2000L, amountCents = 1500L)
        )
    }

    @Test
    fun balance_equalAmount_noHint() {
        assertNull(
            EntryChecks.balanceHint(true, false, balanceCents = 1500L, amountCents = 1500L)
        )
    }

    @Test
    fun balance_creditCard_neverHints() {
        // 信用卡本来就允许透支，不该打扰
        assertNull(
            EntryChecks.balanceHint(true, isCredit = true, balanceCents = 0L, amountCents = 9999L)
        )
    }

    @Test
    fun balance_income_neverHints() {
        assertNull(
            EntryChecks.balanceHint(isExpense = false, isCredit = false, balanceCents = 0L, amountCents = 9999L)
        )
    }

    @Test
    fun balance_negativeBalance_noHint() {
        // 账户已经是负数（如信用卡或已透支），再提示「超过余额」没有意义
        assertNull(
            EntryChecks.balanceHint(true, false, balanceCents = -500L, amountCents = 100L)
        )
    }

    @Test
    fun balance_zeroAmount_noHint() {
        assertNull(
            EntryChecks.balanceHint(true, false, balanceCents = 100L, amountCents = 0L)
        )
    }

    @Test
    fun balance_zeroBalance_noHint() {
        // 余额为 0 的新账户：几乎每笔都会「超过」，提示会变成噪音
        assertNull(
            EntryChecks.balanceHint(true, false, balanceCents = 0L, amountCents = 100L)
        )
    }
}
