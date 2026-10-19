package com.qingjizhang.app.vm

import android.net.Uri
import com.qingjizhang.app.data.*
import com.qingjizhang.app.ui.autoIconKeyFor
import com.qingjizhang.app.ui.theme.ThemeColor
import com.qingjizhang.app.ui.theme.ThemeMode
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.util.ReceiptStore
import com.qingjizhang.app.util.UserError
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.widget.WidgetRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 记账命令：账单的保存 / 再记 / 删除 / 批量 / 借贷结算（P2-1 从 AppViewModel 拆出）
 */

fun AppViewModel.saveTxn(txn: Txn, isNew: Boolean, tagIds: List<Long> = emptyList()) {
        scope.launch {
            if (writeTxn(txn, isNew, tagIds)) _snack.emit(if (isNew) "已记一笔" else "已保存修改")
        }
    }

    /**
     * 真正落库（保存 / 再记共用）：写入账单与它的标签，并记住「分类 → 账户」的搭配。
     * N38：新增账单要等拿到自增 id 之后才能写关联表，所以先取 id 再 setTxnTags。
     * 失败时给出原因并返回 false，调用方据此决定要不要清空面板。
     */
internal suspend fun AppViewModel.writeTxn(txn: Txn, isNew: Boolean, tagIds: List<Long>): Boolean = runCatching {
        val id = if (isNew) repo().addTxn(txn) else {
            repo().updateTxn(txn)
            txn.id
        }
        repo().setTxnTags(id, tagIds)
        // 只有收入 / 支出才有「分类 → 账户」的记忆
        if (txn.type == TxnType.EXPENSE || txn.type == TxnType.INCOME) {
            rememberAccount(txn.categoryId, txn.accountId)
        }
        // N57 + 预算预警：支出记账后当场算一次预算。
        // 到预警线（默认 80%）先提醒一次，超支再提醒一次；各自每月最多一条。
        // 包在自己的 runCatching 里：提醒失败不影响记账结果。
        if (txn.type == TxnType.EXPENSE && _currentLedgerId.value > 0L) {
            runCatching {
                val state = BudgetReminder.overspentOf(requireDb(), _currentLedgerId.value)
                BudgetReminder.notifyIfWarn(
                    app, state.monthBudgetCents, state.monthUsedCents, warnPercent.value
                )
                BudgetReminder.notifyIfOverspent(app, state)
            }
        }
    }.fold(
        onSuccess = { true },
        onFailure = {
            _snack.emit(UserError.text("保存失败", it))
            false
        }
    )

    /**
     * 「再记」用：等这笔账单真的写进数据库再回来，成功才清空面板继续记下一笔，
     * 校验规则与提示与「保存」完全一致（校验在面板里做，这里只负责写库）。
     */
internal suspend fun AppViewModel.saveTxnAndContinue(txn: Txn, isNew: Boolean, tagIds: List<Long> = emptyList()): Boolean {        val ok = writeTxn(txn, isNew, tagIds)
        if (ok) _snack.emit("已记一笔，继续记下一笔")
        return ok
    }

    /**
     * 借贷还款（N7）：对某笔借贷记一笔「收回 / 还出」。
     * 借出的收回 = 借贷账户 → 资金账户；借入的还出 = 资金账户 → 借贷账户，
     * 方向与原借贷相反，所以账务自动对得上，借贷仍不计入收支。
     */
fun AppViewModel.settleLend(lend: Txn, cents: Long, dateMillis: Long = System.currentTimeMillis()) {
        if (cents <= 0L || lend.type != TxnType.LEND_BORROW) return
        scope.launch {
            runCatching {
                val record = lendSettlementOf(lend, cents, dateMillis)
                repo().addTxn(record)
                _snack.emit("已记录" + lendActionOf(lend) + " ¥" + Money.text(cents))
            }.onFailure { _snack.emit(UserError.text("操作失败", it)) }
        }
    }

    /**
     * N55 借贷批量收回：长按多选若干笔借贷后一次性收回。
     * 逐笔按既有收回逻辑生成还款流水（已结清的跳过），所有账单在**同一个事务**里写入，
     * 所以余额与借贷状态是一次性更新到位的，不会出现「只收了一半」的中间态。
     */
fun AppViewModel.settleLendsBatch(lends: List<Txn>) {
        val outstanding = Repository.outstandingMap(txns.value)
        val targets = lends
            .filter { it.type == TxnType.LEND_BORROW && !Repository.isSettlement(it) }
            .mapNotNull { l ->
                val left = outstanding[l.id] ?: 0L
                if (left > 0L) l to left else null
            }
        if (targets.isEmpty()) {
            scope.launch { _snack.emit("所选借贷都已经结清了") }
            return
        }
        scope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                repo().addTxns(targets.map { (lend, left) -> lendSettlementOf(lend, left, now) })
                targets
            }.onSuccess { list ->
                _snack.emit(
                    "已收回 / 还出 " + list.size + " 笔，合计 ¥" +
                        Money.text(list.sumOf { it.second })
                )
            }.onFailure { _snack.emit(UserError.text("批量收回失败", it)) }
        }
    }

    /** 收回 / 还出的动作名：借出的叫「收回」、借入的叫「还出」 */
internal fun AppViewModel.lendActionOf(lend: Txn): String =
        if (lend.direction == Direction.LEND) "收回" else "还出"

    /** 组装一笔借贷还款流水（与原借贷方向相反，挂在原借贷下可追溯） */
internal fun AppViewModel.lendSettlementOf(lend: Txn, cents: Long, dateMillis: Long): Txn {
        val isReceivable = lend.direction == Direction.LEND
        val action = if (isReceivable) "收回" else "还出"
        return Txn(
            ledgerId = lend.ledgerId,
            accountId = lend.accountId,
            transferToAccountId = lend.transferToAccountId,
            type = TxnType.LEND_BORROW,
            direction = if (isReceivable) Direction.BORROW else Direction.LEND,
            amountCents = cents,
            counterparty = lend.counterparty,
            note = action + if (lend.counterparty.isBlank()) "" else "：" + lend.counterparty,
            dateMillis = dateMillis,
            settledTxnId = lend.id
        )
    }

    /**
     * 删除账单：先进回收站（30 天内可还原）。
     * @param keepBalance true = 删除后账户余额保持不变（补一笔「不计入收支」的漏记款调整把余额补回来）；
     *                    false = 余额跟着回滚（默认）。
     */
fun AppViewModel.deleteTxn(txn: Txn, keepBalance: Boolean = false) {
        scope.launch {
            runCatching {
                repo().trashTxn(txn)
                if (keepBalance) {
                    compensateBalance(txn)
                    _snack.emit("已删除，并补了一笔不计收支的余额调整")
                } else {
                    _snack.emit("已删除，可在回收站还原")
                }
            }.onFailure { _snack.emit(UserError.text("删除失败", it)) }
        }
    }

    /**
     * 「余额保持不变」：删除账单会让余额回滚，这里给每个受影响的账户补一笔
     * 「漏记款 · 余额调整」（标记为不计入收支、不计入预算），把余额补回删除前的数值。
     */
internal suspend fun AppViewModel.compensateBalance(txn: Txn) {
        Repository.accountDeltas(listOf(txn)).forEach { (accountId, delta) ->
            if (accountId == 0L || delta == 0L) return@forEach
            adjustBalanceFor(accountId, delta, txn.dateMillis, "删除账单后保留余额")
        }
    }

    /**
     * 批量删除且选「余额保持不变」（N28）：汇总这批量账单对各账户的净影响，
     * 每个受影响账户只补一笔「漏记款 · 余额调整」，避免逐笔生成一堆调整账单。
     */
internal suspend fun AppViewModel.compensateBalanceFor(txns: List<Txn>) {
        val at = txns.maxOfOrNull { it.dateMillis } ?: System.currentTimeMillis()
        Repository.accountDeltas(txns).forEach { (accountId, delta) ->
            if (accountId == 0L || delta == 0L) return@forEach
            adjustBalanceFor(accountId, delta, at, "批量删除后保留余额")
        }
    }

    /** 给某个账户补一笔「漏记款 · 余额调整」（不计收支 / 不计预算），抵消 [delta] 的余额影响 */
internal suspend fun AppViewModel.adjustBalanceFor(accountId: Long, delta: Long, dateMillis: Long, note: String) {
        val ledgerId = _currentLedgerId.value
        val isExpense = delta < 0
        val catId = ensureCategory(
            ledgerId,
            if (isExpense) CategoryType.EXPENSE else CategoryType.INCOME,
            "漏记款",
            "余额调整"
        )
        repo().addTxn(
            Txn(
                ledgerId = ledgerId,
                accountId = accountId,
                categoryId = catId,
                type = if (isExpense) TxnType.EXPENSE else TxnType.INCOME,
                amountCents = kotlin.math.abs(delta),
                note = note,
                dateMillis = dateMillis,
                excludeFromStats = true,
                excludeFromBudget = true
            )
        )
    }

    // ---------------- 批量账单操作（N28） ----------------

    /** 批量删除：沿用单笔删除的同一套语义（余额跟着动 / 余额保持不变），记录进回收站 */
fun AppViewModel.deleteTxns(txns: List<Txn>, keepBalance: Boolean = false) {
        if (txns.isEmpty()) return
        val n = txns.size
        scope.launch {
            runCatching {
                repo().trashTxnsByIds(txns.map { it.id })
                if (keepBalance) {
                    compensateBalanceFor(txns)
                    _snack.emit("已批量删除 $n 笔，并补了余额调整")
                } else {
                    _snack.emit("已批量删除 $n 笔，可在回收站还原")
                }
            }.onFailure { _snack.emit(UserError.text("批量删除失败", it)) }
        }
    }

    /** 批量移动账本 */
fun AppViewModel.moveTxnsToLedger(txns: List<Txn>, target: Ledger) {
        if (txns.isEmpty()) return
        scope.launch {
            runCatching {
                repo().moveTxnsToLedger(txns.map { it.id }, target.id)
                _snack.emit("已批量移动 ${txns.size} 笔到「${target.name}」")
            }.onFailure { _snack.emit(UserError.text("批量移动失败", it)) }
        }
    }

    /** 批量修改分类 */
fun AppViewModel.changeTxnsCategory(txns: List<Txn>, category: Category) {
        if (txns.isEmpty()) return
        scope.launch {
            runCatching {
                repo().setTxnsCategory(txns.map { it.id }, category.id)
                _snack.emit("已把 ${txns.size} 笔改到分类「${category.name}」")
            }.onFailure { _snack.emit(UserError.text("批量修改失败", it)) }
        }
    }

    /** 批量修改账户 */
fun AppViewModel.changeTxnsAccount(txns: List<Txn>, account: Account) {
        if (txns.isEmpty()) return
        scope.launch {
            runCatching {
                repo().setTxnsAccount(txns.map { it.id }, account.id)
                _snack.emit("已把 ${txns.size} 笔改到账户「${account.name}」")
            }.onFailure { _snack.emit(UserError.text("批量修改失败", it)) }
        }
    }

    /** 批量统计口径：只下发调用方明确指定的那一项，避免误覆盖另一项 */
fun AppViewModel.changeTxnsStats(txns: List<Txn>, excludeFromStats: Boolean? = null, excludeFromBudget: Boolean? = null) {
        if (txns.isEmpty()) return
        scope.launch {
            runCatching {
                val ids = txns.map { it.id }
                if (excludeFromStats != null) repo().setTxnsExcludeFromStats(ids, excludeFromStats)
                if (excludeFromBudget != null) repo().setTxnsExcludeFromBudget(ids, excludeFromBudget)
                _snack.emit("已更新 ${txns.size} 笔的统计口径")
            }.onFailure { _snack.emit(UserError.text("批量更新失败", it)) }
        }
    }

    /**
     * 批量报销 / 退款：退款额按每笔金额封顶（逐笔 coerce），与单笔保存时「退款不能超过该笔金额」同口径，
     * 所以按 coerce 后的金额分组，每组一次写回。互转 / 借贷不涉及报销，自动跳过。
     */
fun AppViewModel.changeTxnsRefund(txns: List<Txn>, refundCents: Long) {
        val targets = txns.filter { Repository.affectsIncomeExpense(it) }
        if (targets.isEmpty()) {
            toast("所选账单不支持报销 / 退款")
            return
        }
        scope.launch {
            runCatching {
                targets.groupBy { refundCents.coerceIn(0L, it.amountCents) }
                    .forEach { (cents, group) -> repo().setTxnsRefund(group.map { it.id }, cents) }
                _snack.emit("已设置报销 / 退款：${targets.size} 笔")
            }.onFailure { _snack.emit(UserError.text("批量设置失败", it)) }
        }
    }

fun AppViewModel.txnById(id: Long, onResult: (Txn?) -> Unit) {
        scope.launch { onResult(runCatching { repo().txnById(id) }.getOrNull()) }
    }

/**
 * 记账建议（P0-1）：按当前输入（类型 / 备注 / 金额）从本账本历史里挑一个分类 + 账户。
 * 纯本地计算，样本不足时返回 null（宁可不建议）。
 * 只在「记一笔」面板里用，账号状态同步读取已订阅的 Flow，不额外查库。
 */
fun AppViewModel.suggestionFor(
    type: TxnType,
    note: String,
    amountCents: Long
): SuggestionEngine.Suggestion? {
    if (!suggestEnabled.value) return null
    return SuggestionEngine.suggest(
        history = txns.value,
        type = type,
        note = note,
        amountCents = amountCents,
        now = System.currentTimeMillis()
    )
}
