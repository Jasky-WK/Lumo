package com.qingjizhang.app.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.qingjizhang.app.R
import com.qingjizhang.app.data.AppDatabase
import com.qingjizhang.app.data.Repository
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 剩余预算小组件要显示的数据（N34）。
 * [budgetCents] <= 0 表示当月还没设置预算。
 */
data class BudgetSnapshot(
    val budgetCents: Long,
    val usedCents: Long
) {
    val hasBudget: Boolean get() = budgetCents > 0L

    /** 剩余 = 预算 − 已用，可以为负（超支） */
    val remainingCents: Long get() = budgetCents - usedCents

    /** 进度条用的千分比（0..1000），超支时封顶 100% */
    val progressPermille: Int
        get() = if (!hasBudget) 0
        else ((usedCents * 1000.0) / budgetCents).roundToInt().coerceIn(0, 1000)
}

object BudgetData {

    /**
     * 本月已用与月度预算。
     * 口径与 App 内的 `AppViewModel.budgetUsedCents` 完全一致：
     * 本月区间内 + [Repository.countsInBudget]（只算支出、且没勾「不计入预算」）
     * + [Repository.effectiveCents]（报销 / 退款后实计金额）
     * + 排除 N20「不计入净资产」账户的流水。
     */
    suspend fun load(context: Context): BudgetSnapshot {
        val db = AppDatabase.get(context)
        val ledger = WidgetLedger.current(context, db) ?: return BudgetSnapshot(0L, 0L)
        val txns = db.txnDao().byLedger(ledger.id)
        val excluded = Repository.statsExcludedAccountIds(
            db.accountDao().forLedger(ledger.id)
        )
        val (start, end) = DateFmt.monthRange(LocalDate.now())
        val used = txns.filter {
            it.dateMillis in start until end &&
                Repository.countsInBudget(it) &&
                Repository.inStatsAccountScope(it, excluded)
        }.sumOf { Repository.effectiveCents(it) }
        return BudgetSnapshot(ledger.monthlyBudgetCents, used)
    }

    /** 广播接收器里同步取一次数据（已经在子线程调用） */
    fun loadBlocking(context: Context): BudgetSnapshot = runBlocking { load(context) }
}

object BudgetViews {

    fun build(context: Context, snap: BudgetSnapshot): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_budget).apply {
            setOnClickPendingIntent(R.id.w_b_root, WidgetIntents.openBudget(context))
            if (!snap.hasBudget) {
                setTextViewText(R.id.w_b_label, "本月剩余预算")
                setTextViewText(R.id.w_b_amount, "未设置预算")
                setTextColor(R.id.w_b_amount, context.getColor(R.color.widget_sub2))
                setTextViewText(R.id.w_b_used, "点这里去设置 →")
                setViewVisibility(R.id.w_b_bar, View.GONE)
            } else {
                val over = snap.remainingCents < 0L
                setTextViewText(R.id.w_b_label, if (over) "本月已超支" else "本月剩余预算")
                setTextViewText(
                    R.id.w_b_amount,
                    Money.withSymbol(if (over) -snap.remainingCents else snap.remainingCents)
                )
                setTextColor(
                    R.id.w_b_amount,
                    context.getColor(if (over) R.color.widget_amount else R.color.widget_positive)
                )
                setTextViewText(
                    R.id.w_b_used,
                    "已用 " + Money.text(snap.usedCents) + " / " + Money.text(snap.budgetCents)
                )
                setViewVisibility(R.id.w_b_bar, View.VISIBLE)
                // 4 参版本（API 24+）：indeterminate = false
                setProgressBar(R.id.w_b_bar, 1000, snap.progressPermille, false)
            }
        }
}

/** 剩余预算小组件（N34，4×1） */
class BudgetWidgetProvider : BaseSnapshotWidget<BudgetSnapshot>() {

    override fun load(context: Context): BudgetSnapshot = BudgetData.loadBlocking(context)

    override fun build(context: Context, data: BudgetSnapshot, widgetId: Int): RemoteViews =
        BudgetViews.build(context, data)
}
