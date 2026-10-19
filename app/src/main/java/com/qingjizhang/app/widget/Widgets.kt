package com.qingjizhang.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.qingjizhang.app.R
import com.qingjizhang.app.data.AppDatabase
import com.qingjizhang.app.data.Ledger
import com.qingjizhang.app.data.Repository
import com.qingjizhang.app.data.TxnType
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/** 小组件要显示的今日数据（N10） */
data class TodaySnapshot(
    val ledgerName: String,
    val incomeCents: Long,
    val expenseCents: Long,
    val count: Int
) {
    val balanceCents: Long get() = incomeCents - expenseCents
}

object WidgetData {

    /** 当前账本的今日收支；账本取最近一次在 App 里选中的那个 */
    suspend fun load(context: Context): TodaySnapshot {
        val db = AppDatabase.get(context)
        val ledger = WidgetLedger.current(context, db)
        val ledgerId = ledger?.id ?: 0L
        val name = ledger?.name ?: "Lumo"
        if (ledgerId == 0L) return TodaySnapshot(name, 0L, 0L, 0)

        val txns = db.txnDao().byLedger(ledgerId)
        val (start, end) = DateFmt.dayRange(LocalDate.now())
        // N20：不计入净资产的账户不参与统计口径，小组件的今日收支也把它们排除
        val excluded = Repository.statsExcludedAccountIds(
            db.accountDao().forLedger(ledgerId)
        )
        val today = txns.filter {
            it.dateMillis in start until end && Repository.inStatsAccountScope(it, excluded)
        }
        val income = today.filter { Repository.countsInStats(it) && it.type == TxnType.INCOME }
            .sumOf { Repository.effectiveCents(it) }
        val expense = today.filter { Repository.countsInStats(it) && it.type == TxnType.EXPENSE }
            .sumOf { Repository.effectiveCents(it) }
        return TodaySnapshot(name, income, expense, today.size)
    }

    /** 广播接收器里同步取一次数据（已经在子线程调用） */
    fun loadBlocking(context: Context): TodaySnapshot = runBlocking { load(context) }
}

/**
 * 各小组件共用的「当前账本」判定：取最近一次在 App 里选中的那个账本，
 * 取不到（没存过 / 账本已删）就退回到列表里的第一个。
 */
internal object WidgetLedger {

    private const val PREFS = "bookkeeping_prefs"
    private const val KEY_LEDGER = "current_ledger_id"

    suspend fun current(context: Context, db: AppDatabase = AppDatabase.get(context)): Ledger? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedId = prefs.getLong(KEY_LEDGER, 0L)
        return savedId.takeIf { it > 0L }?.let { db.ledgerDao().byId(it) }
            ?: db.ledgerDao().all().firstOrNull()
    }
}

/** 两个小组件共用的视图构建 */
object WidgetViews {

    fun today(context: Context, snap: TodaySnapshot): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_today).apply {
            setTextViewText(R.id.w_ledger, snap.ledgerName)
            setTextViewText(R.id.w_label, "今日支出")
            setTextViewText(R.id.w_amount, Money.withSymbol(snap.expenseCents))
            setTextViewText(
                R.id.w_footer,
                "收入 " + Money.text(snap.incomeCents) +
                    " · 结余 " + Money.text(snap.balanceCents) +
                    " · " + snap.count + " 笔"
            )
            setOnClickPendingIntent(R.id.w_root, WidgetIntents.openApp(context))
        }

    fun quickAdd(context: Context, snap: TodaySnapshot): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_quick_add).apply {
            setTextViewText(R.id.q_title, snap.ledgerName + " · 今日")
            setTextViewText(R.id.q_amount, Money.withSymbol(snap.expenseCents))
            setTextViewText(
                R.id.q_footer,
                "收入 " + Money.text(snap.incomeCents) + " · 结余 " + Money.text(snap.balanceCents)
            )
            setOnClickPendingIntent(R.id.q_root, WidgetIntents.openApp(context))
            setOnClickPendingIntent(R.id.q_add, WidgetIntents.quickAdd(context))
        }
}

/** App 内数据变化后主动刷新小组件 */
object WidgetRefresh {
    fun refresh(context: Context) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        listOf(
            TodayWidgetProvider::class.java,
            QuickAddWidgetProvider::class.java,
            BudgetWidgetProvider::class.java,
            TemplateWidgetProvider::class.java
        ).forEach { cls ->
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(context, cls)) }.getOrDefault(IntArray(0))
            if (ids != null && ids.isNotEmpty()) {
                val intent = Intent(context, cls).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}

/**
 * 小组件点进 App 时携带的 extra key（N34）。
 * App 侧读取这两个 key 即可：带上 [CATEGORY_ID] 表示「按该分类打开记一笔」。
 */
object WidgetExtras {

    /** 分类 id，类型 Long。0 表示不带分类 */
    const val CATEGORY_ID = "extra_category_id"

    /** 是否直接打开记一笔面板，类型 Boolean */
    const val OPEN_ADD_ENTRY = "extra_open_add_entry"
}

internal object WidgetIntents {
    private const val REQ_OPEN = 4101
    private const val REQ_QUICK = 4102
    private const val REQ_BUDGET = 4103
    private const val REQ_TEMPLATE = 4110

    fun openApp(context: Context) = android.app.PendingIntent.getActivity(
        context,
        REQ_OPEN,
        Intent(context, com.qingjizhang.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        flags()
    )

    fun quickAdd(context: Context) = android.app.PendingIntent.getActivity(
        context,
        REQ_QUICK,
        Intent(context, com.qingjizhang.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("quick_add", true)
        },
        flags()
    )

    /** 剩余预算小组件：打开 App 并带上 open=budget，交给 App 侧决定是否落到预算设置 */
    fun openBudget(context: Context) = android.app.PendingIntent.getActivity(
        context,
        REQ_BUDGET,
        Intent(context, com.qingjizhang.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open", "budget")
        },
        flags()
    )

    /**
     * 模板记账小组件：点第 slot 个模板 → 打开 App 并带入该分类。
     * 每个槽位用不同 requestCode，避免 4 个模板共用同一个 PendingIntent。
     */
    fun template(context: Context, categoryId: Long, slot: Int) =
        android.app.PendingIntent.getActivity(
            context,
            REQ_TEMPLATE + slot,
            Intent(context, com.qingjizhang.app.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(WidgetExtras.CATEGORY_ID, categoryId)
                putExtra(WidgetExtras.OPEN_ADD_ENTRY, true)
            },
            flags()
        )

    private fun flags(): Int {
        var f = android.app.PendingIntent.FLAG_UPDATE_CURRENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            f = f or android.app.PendingIntent.FLAG_IMMUTABLE
        }
        return f
    }
}

/**
 * 小组件通用基类：在子线程里取一次数据，再给每个实例各建一份 RemoteViews。
 * 「今日收支 / 快速记一笔」（N10）与「剩余预算 / 模板记账」（N34）共用这套刷新流程。
 */
abstract class BaseSnapshotWidget<T> : AppWidgetProvider() {

    /** 在子线程调用：取这次刷新要用到的数据 */
    protected abstract fun load(context: Context): T

    /** 子类提供各自的视图构建 */
    protected abstract fun build(context: Context, data: T, widgetId: Int): RemoteViews

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            val data = runCatching { load(app) }.getOrNull()
            if (data != null) {
                appWidgetIds.forEach { id ->
                    runCatching { manager.updateAppWidget(id, build(app, data, id)) }
                }
            }
            pending.finish()
        }.start()
    }
}

abstract class BaseTodayWidget : BaseSnapshotWidget<TodaySnapshot>() {

    /** 子类提供各自的视图构建 */
    abstract fun views(context: Context, snap: TodaySnapshot, widgetId: Int): RemoteViews

    final override fun load(context: Context): TodaySnapshot = WidgetData.loadBlocking(context)

    final override fun build(context: Context, data: TodaySnapshot, widgetId: Int): RemoteViews =
        views(context, data, widgetId)
}

/** 今日收支小组件 */
class TodayWidgetProvider : BaseTodayWidget() {
    override fun views(context: Context, snap: TodaySnapshot, widgetId: Int): RemoteViews =
        WidgetViews.today(context, snap)
}

/** 快速记一笔小组件：点右侧按钮直接打开记账面板 */
class QuickAddWidgetProvider : BaseTodayWidget() {
    override fun views(context: Context, snap: TodaySnapshot, widgetId: Int): RemoteViews =
        WidgetViews.quickAdd(context, snap)
}
