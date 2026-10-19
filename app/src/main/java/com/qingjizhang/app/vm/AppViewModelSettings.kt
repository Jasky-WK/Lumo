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
 * 设置 / 外观 / 周期与视角命令（P2-1 拆出）
 */

fun AppViewModel.setReminderEnabled(enabled: Boolean) {
        RecurringReminder.setEnabled(app, enabled)
        _reminderEnabled.value = enabled
        scope.launch {
            if (enabled) {
                runCatching {
                    RecurringReminder.schedule(app, rules.value)
                    _snack.emit("已开启周期提醒（本地通知，不联网）")
                }
            } else {
                _snack.emit("已关闭周期提醒")
            }
        }
    }

fun AppViewModel.setBudgetRemindEnabled(enabled: Boolean) {
        BudgetReminder.setEnabled(app, enabled)
        _budgetRemindEnabled.value = enabled
        scope.launch {
            if (enabled) {
                runCatching { BudgetReminder.scheduleNext(app) }
                _snack.emit("已开启预算超支提醒（本地通知，不联网）")
            } else {
                _snack.emit("已关闭预算超支提醒")
            }
        }
    }

fun AppViewModel.setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putInt("theme_mode", mode.ordinal).apply()
    }

fun AppViewModel.setThemeColor(color: ThemeColor) {
        _themeColor.value = color
        prefs.edit().putInt("theme_color", color.ordinal).apply()
    }

    // ---------------- 账户记忆（最近这个分类用过的账户） ----------------

    /** 上次记这个分类时用的账户；没有记忆就返回 0 */
fun AppViewModel.rememberAccountFor(categoryId: Long): Long =
        if (categoryId == 0L) 0L else prefs.getLong("acct_$categoryId", 0L)

    /** 记账成功后记住「这个分类 + 这个账户」的搭配，下次默认带出 */
internal fun AppViewModel.rememberAccount(categoryId: Long, accountId: Long) {
        if (categoryId == 0L || accountId == 0L) return
        prefs.edit().putLong("acct_$categoryId", accountId).apply()
    }

fun AppViewModel.setNoteMemoryEnabled(on: Boolean) {
        _noteMemoryEnabled.value = on
        prefs.edit().putBoolean("note_memory", on).apply()
    }

fun AppViewModel.setVibrateFeedback(on: Boolean) {
        _vibrateFeedback.value = on
        prefs.edit().putBoolean("vibrate_feedback", on).apply()
    }

fun AppViewModel.setKeySound(on: Boolean) {
        _keySound.value = on
        prefs.edit().putBoolean("key_sound", on).apply()
    }

fun AppViewModel.toast(msg: String) {
        scope.launch { _snack.emit(msg) }
    }

fun AppViewModel.openTxnList(spec: TxnListSpec) {
        _txnListSpec.value = spec
    }

fun AppViewModel.dismissImportReport() {
        _importReport.value = null
    }

fun AppViewModel.setPeriodKind(kind: Int) {
        _periodKind.value = kind
    }

    /** 自定义区间（含首尾两天）；自动切到自定义模式 */
fun AppViewModel.setCustomRange(start: LocalDate, end: LocalDate) {
        val s = if (start.isAfter(end)) end else start
        val e = if (start.isAfter(end)) start else end
        _rangeStart.value = s
        _rangeEnd.value = e
        _periodKind.value = 3
    }

fun AppViewModel.shiftPeriod(delta: Int) {
        when (_periodKind.value) {
            0 -> _anchor.value = _anchor.value.plusDays(delta.toLong())
            1 -> _anchor.value = _anchor.value.plusMonths(delta.toLong())
            2 -> _anchor.value = _anchor.value.plusYears(delta.toLong())
            else -> {
                // 自定义区间：整段前后平移（长度不变）
                val days = java.time.temporal.ChronoUnit.DAYS.between(_rangeStart.value, _rangeEnd.value) + 1
                val shift = days * delta
                _rangeStart.value = _rangeStart.value.plusDays(shift)
                _rangeEnd.value = _rangeEnd.value.plusDays(shift)
            }
        }
    }

fun AppViewModel.resetPeriod() {
        _anchor.value = LocalDate.now()
        _rangeStart.value = LocalDate.now().withDayOfMonth(1)
        _rangeEnd.value = LocalDate.now()
        if (_periodKind.value == 3) _periodKind.value = 1
    }

fun AppViewModel.setCatMode(mode: Int) {
        _catMode.value = mode
    }

    /** 总览图表口径切换（F15）：0 = 支出，1 = 收入 */
fun AppViewModel.setFlowMode(mode: Int) {
        _flowMode.value = mode
    }

fun AppViewModel.shiftCalMonth(delta: Int) {
        _calAnchor.value = _calAnchor.value.plusMonths(delta.toLong())
    }

fun AppViewModel.resetCalMonth() {
        _calAnchor.value = LocalDate.now().withDayOfMonth(1)
    }


    /** 新建 / 保存自定义预算（删除只是删定义，历史账单与统计不受影响） */
fun AppViewModel.saveBudget(budget: Budget, isNew: Boolean) {
        scope.launch {
            runCatching {
                if (isNew) repo().addBudget(budget) else repo().updateBudget(budget)
                _snack.emit(if (isNew) "已新建预算：${budget.name}" else "已保存预算：${budget.name}")
            }.onFailure { _snack.emit(UserError.text("保存失败", it)) }
        }
    }

    /** 暂停 / 启用：定义与历史统计都保留 */
fun AppViewModel.toggleBudgetEnabled(budget: Budget) {
        scope.launch {
            runCatching {
                repo().setBudgetEnabled(budget.id, !budget.enabled)
                _snack.emit(if (budget.enabled) "已暂停「${budget.name}」" else "已启用「${budget.name}」")
            }.onFailure { _snack.emit(UserError.text("操作失败", it)) }
        }
    }

fun AppViewModel.deleteBudget(budget: Budget) {
        scope.launch {
            runCatching {
                repo().deleteBudget(budget)
                _snack.emit("已删除预算「${budget.name}」，历史账单与统计不受影响")
            }.onFailure { _snack.emit(UserError.text("删除失败", it)) }
        }
    }

/**
 * P1 IA 重组：一次性提示「导入导出 / 回收站已收纳到「数据」组」。
 * 老用户习惯从抽屉点导出，改版后需要一句引导，看完就不再出现。
 */
internal fun AppViewModel.iaMoveHintPending(): Boolean =
    !prefs.getBoolean("ia_move_hint_shown", false)

internal fun AppViewModel.markIaMoveHintShown() {
    prefs.edit().putBoolean("ia_move_hint_shown", true).apply()
}
