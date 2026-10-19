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
 * 账本 / 回收站 / 周期规则命令（P2-1 拆出）
 */

    // ---------------- 操作 ----------------

    /**
     * 切换账本：记账数据（账单 / 分类 / 账户 / 周期规则）都跟着 _currentLedgerId 重新查询，
     * 并写进 SharedPreferences，重启后仍然是这个账本。
     */
fun AppViewModel.switchLedger(id: Long) {
        if (id <= 0L || id == _currentLedgerId.value) return
        ledgerPickedByUser = true
        _currentLedgerId.value = id
        prefs.edit().putLong("current_ledger_id", id).apply()
    }

fun AppViewModel.addLedger(name: String) {
        scope.launch {
            runCatching {
                val id = repo().addLedger(name)
                // 新账本自动带上预设的一级 / 二级分类与默认账户
                Seed.initLedgerPresets(requireDb(), id)
                _snack.emit("已创建账本：$name，并写入预设分类")
                switchLedger(id)
            }.onFailure { _snack.emit(UserError.text("创建失败", it)) }
        }
    }

    /**
     * v2.9.0：一键生成「示例账本」。
     * 独立账本 + 约 80 笔演示数据，用户可在「账本管理」里整本删除。
     * 全部走 DAO / 预设播种，不直接写 SQL，保证关系表（account_ledgers）同步。
     */
    fun AppViewModel.createDemoLedger() {
        scope.launch {
            _busy.value = true
            runCatching {
                val id = DemoData.create(requireDb())
                switchLedger(id)
                _snack.emit("示例账本已生成，可随时在「账本管理」里删除")
            }.onFailure { _snack.emit(UserError.text("生成示例账本失败", it)) }
            _busy.value = false
        }
    }

fun AppViewModel.renameLedger(ledger: Ledger, name: String) {        scope.launch {
            runCatching {
                repo().renameLedger(ledger, name)
                _snack.emit("已重命名为：$name")
            }.onFailure { _snack.emit(UserError.text("重命名失败", it)) }
        }
    }

fun AppViewModel.deleteLedger(ledger: Ledger) {
        scope.launch {
            runCatching {
                if (ledgers.value.size <= 1) {
                    _snack.emit("至少要保留一个账本")
                    return@runCatching
                }
                // N59：软删除——账本进回收站，数据原样保留，30 天内可还原
                repo().deleteLedger(ledger)
                val next = ledgers.value.firstOrNull { it.id != ledger.id }
                if (next != null) switchLedger(next.id)
                _snack.emit("已删除账本，30 天内可在回收站还原")
            }.onFailure { _snack.emit(UserError.text("删除失败", it)) }
        }
    }

    /** N59：从回收站还原账本（连同它名下的账单 / 账户 / 分类） */
fun AppViewModel.restoreLedger(ledger: Ledger) {
        scope.launch {
            runCatching {
                repo().restoreLedger(ledger)
                _snack.emit("已还原账本「${ledger.name}」")
            }.onFailure { _snack.emit(UserError.text("还原失败", it)) }
        }
    }

    /** N59：彻底删除账本（回收站里操作）：级联清空该账本所有数据 */
fun AppViewModel.purgeLedger(ledger: Ledger) {
        scope.launch {
            runCatching {
                repo().purgeLedger(ledger)
                sweepReceiptDirs()
                _snack.emit("已彻底删除账本")
            }.onFailure { _snack.emit(UserError.text("删除失败", it)) }
        }
    }

fun AppViewModel.clearAllTxns() {
        scope.launch {
            runCatching {
                repo().clearTransactions(_currentLedgerId.value)
                _snack.emit("已清空当前账本的全部账单")
            }.onFailure { _snack.emit(UserError.text("清空失败", it)) }
        }
    }

    /**
     * N33「删除全部账单」：把当前账本的全部账单一次性放进回收站（软删除），
     * 保留 30 天、期间可在回收站还原，不做物理删除。删除后各账户余额回退为初始余额。
     */
fun AppViewModel.trashAllTxns() {
        scope.launch {
            runCatching {
                val n = txns.value.size
                repo().trashAllTxns(_currentLedgerId.value)
                _snack.emit("已把 $n 笔账单移入回收站，保留 30 天，期间可还原")
            }.onFailure { _snack.emit(UserError.text("删除失败", it)) }
        }
    }

    // ---------------- 周期记账 ----------------

fun AppViewModel.saveRule(rule: RecurringRule, isNew: Boolean, runCatchUp: Boolean = true) {
        scope.launch {
            runCatching {
                if (isNew) repo().addRule(rule) else repo().updateRule(rule)
                if (runCatchUp) {
                    val n = RecurringEngine.catchUp(requireDb())
                    _snack.emit(if (n > 0) "规则已保存，补记 $n 笔" else "规则已保存")
                } else {
                    _snack.emit("规则已保存")
                }
            }.onFailure { _snack.emit(UserError.text("保存失败", it)) }
        }
    }

fun AppViewModel.toggleRule(rule: RecurringRule, enabled: Boolean) {
        scope.launch {
            runCatching {
                repo().updateRule(rule.copy(enabled = enabled))
                if (enabled) {
                    val n = RecurringEngine.catchUp(requireDb())
                    if (n > 0) _snack.emit("已补记 $n 笔")
                }
            }.onFailure { _snack.emit(UserError.text("操作失败", it)) }
        }
    }

fun AppViewModel.deleteRule(rule: RecurringRule) {
        scope.launch {
            runCatching {
                repo().deleteRule(rule)
                _snack.emit("已删除规则")
            }.onFailure { _snack.emit(UserError.text("删除失败", it)) }
        }
    }

fun AppViewModel.ruleById(id: Long, onResult: (RecurringRule?) -> Unit) {
        scope.launch { onResult(runCatching { repo().ruleById(id) }.getOrNull()) }
    }

fun AppViewModel.catchUpNow() {
        scope.launch {
            runCatching {
                val n = RecurringEngine.catchUp(requireDb())
                _snack.emit(if (n > 0) "已补记 $n 笔周期账单" else "没有需要补记的账单")
            }.onFailure { _snack.emit(UserError.text("补记失败", it)) }
        }
    }

fun AppViewModel.restoreTxn(txn: Txn) {
        scope.launch {
            runCatching {
                repo().restoreTxn(txn)
                _snack.emit("已还原这笔账单")
            }.onFailure { _snack.emit(UserError.text("还原失败", it)) }
        }
    }

fun AppViewModel.purgeTxn(txn: Txn) {
        scope.launch {
            runCatching {
                repo().purgeTxn(txn)
                sweepReceiptDirs()
                _snack.emit("已彻底删除")
            }.onFailure { _snack.emit(UserError.text("删除失败", it)) }
        }
    }

fun AppViewModel.restoreAccount(account: Account) {
        scope.launch {
            runCatching {
                repo().restoreAccount(account)
                _snack.emit("已还原账户与它的账单")
            }.onFailure { _snack.emit(UserError.text("还原失败", it)) }
        }
    }

fun AppViewModel.purgeAccount(account: Account) {
        scope.launch {
            runCatching {
                repo().purgeAccount(account)
                _snack.emit("已彻底删除账户")
            }.onFailure { _snack.emit(UserError.text("删除失败", it)) }
        }
    }

fun AppViewModel.clearTrash() {
        scope.launch {
            runCatching {
                repo().purgeAllTrash(_currentLedgerId.value)
                sweepReceiptDirs()
                _snack.emit("回收站已清空")
            }.onFailure { _snack.emit(UserError.text("清空失败", it)) }
        }
    }

    /** 打开 App 时清理超过 30 天的回收站记录 */
fun AppViewModel.purgeExpiredTrash() {
        scope.launch {
            runCatching {
                repo().purgeExpiredTrash()
                sweepReceiptDirs()
            }
        }
    }

    // ---------------- 账户增强 ----------------

fun AppViewModel.toggleHiddenAccounts() {
        _showHiddenAccounts.value = !_showHiddenAccounts.value
    }

    /**
     * 小票文件清扫（N56）：彻底删除账单 / 清空回收站 / 删账本之后调用，
     * 把「账单已经不存在」的照片目录清掉，避免私有目录里留孤儿文件。
     * 只扫 files/receipts 下以账单 id 命名的目录，误删风险为零。
     */
internal suspend fun AppViewModel.sweepReceiptDirs() {
        withContext(Dispatchers.IO) {
            val root = File(app.filesDir, "receipts")
            val dirs = root.listFiles() ?: return@withContext
            dirs.forEach { d ->
                val txnId = d.name.toLongOrNull() ?: return@forEach
                val exists = runCatching { requireDb().txnDao().byId(txnId) != null }
                    .getOrDefault(true)
                if (!exists) runCatching { d.deleteRecursively() }
            }
        }
    }
