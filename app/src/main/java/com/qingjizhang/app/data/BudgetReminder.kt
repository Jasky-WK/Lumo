package com.qingjizhang.app.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import com.qingjizhang.app.MainActivity
import com.qingjizhang.app.R
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * N57：预算超支本地提醒。
 *
 * 纯本地实现，与 [CreditReminder] 同一套路：
 * - **记账即时提醒**：保存一笔支出后当场算一次，超支且本月还没提醒过就发通知；
 * - **每日检查**：AlarmManager 只排「最近的一次早上 9 点」，到点由
 *   [BudgetReminderReceiver] 把所有账本都查一遍，超支就提醒，然后排下一次；
 * - **去重**：同一个自然月最多提醒一次（两种触发共用），不刷屏；
 * - 设置页可整个关掉（默认开启），关掉即取消闹钟；
 * - 不联网、不上传任何数据。
 *
 * 超支口径与 App 内完全一致：月度预算用 `countsInBudget` + `effectiveCents`，
 * 自定义预算（N30）用 [Repository.budgetUsedCents]，都不统计「不计入预算 /
 * 不计入收支」的账单与「不计入净资产」账户的流水。
 */
object BudgetReminder {

    private const val CHANNEL_ID = "budget_reminder"
    private const val CHANNEL_NAME = "预算超支提醒"
    private const val NOTIFY_ID = 9003
    private const val REQUEST_CODE = 9003
    /** 80% 预警档用独立的通知 id / 请求码，避免把超支那条覆盖掉 */
    private const val NOTIFY_ID_WARN = 9004
    private const val REQUEST_CODE_WARN = 9004

    /** 每日检查时刻：当天早上 9 点 */
    private const val HOUR = 9

    private const val PREFS_NAME = "bookkeeping_prefs"
    private const val KEY_ENABLED = "budget_remind"
    private const val KEY_NOTIFIED_MONTH = "budget_notified_month"
    /** 80% 预警档的去重 key（与 100% 档各自独立，每月各发一次） */
    private const val KEY_NOTIFIED_MONTH_80 = "budget_notified_month_80"
    /** 预警线（百分比，默认 80；设置页可改 80 / 90 / 100） */
    private const val KEY_WARN_PERCENT = "budget_warn_percent"
    private const val WARN_DEFAULT = 80

    /** 开关（默认开启） */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    /** 预算预警线（百分比）。100 表示只在超支时提醒，不开 80/90 档 */
    fun warnPercent(context: Context): Int =
        prefs(context).getInt(KEY_WARN_PERCENT, WARN_DEFAULT).coerceIn(50, 100)

    fun setWarnPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_WARN_PERCENT, percent.coerceIn(50, 100)).apply()
    }

    /** 设置页切换开关：关掉时取消闹钟，打开时立刻排一次 */
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) scheduleNext(context) else cancel(context)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 超支情况：月度预算是否超 + 哪些自定义预算超了 + 当月已用 / 上限（预警档用） */
    data class Overspent(
        val monthOver: Boolean,
        val customNames: List<String>,
        val monthUsedCents: Long = 0L,
        val monthBudgetCents: Long = 0L
    ) {
        val any: Boolean get() = monthOver || customNames.isNotEmpty()
    }

    /**
     * 某个账本当前的超支情况（现算，不依赖 Flow，保证刚写完账就是最新数据）。
     * 月度预算上限为 0（未设置）时不算超支。
     */
    suspend fun overspentOf(db: AppDatabase, ledgerId: Long): Overspent {
        val ledger = db.ledgerDao().byId(ledgerId)
        val txns = db.txnDao().byLedger(ledgerId)
        val accounts = db.accountDao().byLedger(ledgerId)
        val excluded = Repository.statsExcludedAccountIds(accounts)
        val (s, e) = DateFmt.monthRange(LocalDate.now())
        val used = txns.filter { it.dateMillis in s until e && Repository.countsInBudget(it) }
            .sumOf { Repository.effectiveCents(it) }
        val monthBudget = ledger?.monthlyBudgetCents ?: 0L
        val monthOver = monthBudget > 0L && used > monthBudget
        val names = db.budgetDao().byLedger(ledgerId)
            .filter { it.enabled && Repository.budgetUsedCents(it, txns, excluded) > it.amountCents }
            .map { it.name }
        return Overspent(monthOver, names, used, monthBudget)
    }

    /**
     * 预算预警（默认 80% 档）：已用达到预警线、但还没超支时提醒；
     * 同一自然月只提醒一次（与超支提醒各自独立）。100% 档不在这里发（交给 [notifyIfOverspent]）。
     */
    fun notifyIfWarn(context: Context, budgetCents: Long, usedCents: Long, thresholdPercent: Int) {
        if (budgetCents <= 0L || thresholdPercent >= 100) return
        if (!isEnabled(context)) return
        val threshold = budgetCents * thresholdPercent / 100
        // 到线了（≥ 预警线）但还没超（< 100%）才发；超了由超支提醒负责
        if (usedCents < threshold || usedCents >= budgetCents) return
        val ym = YearMonth.now().toString()
        val prefs = prefs(context)
        if (prefs.getString(KEY_NOTIFIED_MONTH_80, "") == ym) return
        ensureChannel(context)

        val percent = (usedCents * 100 / budgetCents).toInt().coerceIn(0, 99)
        val text = "本月预算已用 $percent%（" + Money.text(usedCents) + " / " + Money.text(budgetCents) + "）"

        val tap = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open", "budget")
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(context, REQUEST_CODE_WARN, tap, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recurring)
            .setContentTitle("预算快到线了（$percent%）")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.notify(NOTIFY_ID_WARN, notification) }
        prefs.edit().putString(KEY_NOTIFIED_MONTH_80, ym).apply()
    }

    /**
     * 超支就发通知；同一个自然月只发一次（记账即时提醒与每日检查共用去重），
     * 没超支 / 已提醒过 / 用户关掉了开关都不发。
     */
    fun notifyIfOverspent(context: Context, state: Overspent) {
        if (!state.any) return
        if (!isEnabled(context)) return
        val ym = YearMonth.now().toString() // "2026-10"
        val prefs = prefs(context)
        if (prefs.getString(KEY_NOTIFIED_MONTH, "") == ym) return
        ensureChannel(context)

        val text = buildString {
            if (state.monthOver) append("· 月度预算已超出\n")
            state.customNames.take(4).forEach { append("· 自定义预算「$it」已超出\n") }
            if (state.customNames.size > 4) append("· 还有 ${state.customNames.size - 4} 个自定义预算已超出")
        }.trimEnd()

        val tap = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open", "budget")
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(context, REQUEST_CODE, tap, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recurring)
            .setContentTitle("本月预算已超支")
            .setContentText(text.replace("\n", " · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.notify(NOTIFY_ID, notification) }
        prefs.edit().putString(KEY_NOTIFIED_MONTH, ym).apply()
    }

    /** 排「今天 / 明天早上 9 点」的每日检查；开关关着就不排 */
    fun scheduleNext(context: Context, now: Long = System.currentTimeMillis()) {
        if (!isEnabled(context)) return
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var at = today.atTime(HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        if (at <= now) {
            at = today.plusDays(1).atTime(HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context)) }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BudgetReminderReceiver::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "月度预算或自定义预算超支时的本地提醒，不需要联网"
            }
        )
    }
}

/** 每日检查到点：把所有账本都查一遍，超支就提醒，然后排下一次 */
class BudgetReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            runCatching {
                val db = AppDatabase.get(appContext)
                val ledgers = runBlocking { db.ledgerDao().all() }
                var monthOver = false
                val names = mutableListOf<String>()
                ledgers.forEach { l ->
                    val o = runBlocking { BudgetReminder.overspentOf(db, l.id) }
                    if (o.monthOver) monthOver = true
                    names += o.customNames
                    // 预警档按账本各自判断（月度预算各账本独立）
                    BudgetReminder.notifyIfWarn(
                        appContext,
                        o.monthBudgetCents,
                        o.monthUsedCents,
                        BudgetReminder.warnPercent(appContext)
                    )
                }
                BudgetReminder.notifyIfOverspent(
                    appContext,
                    BudgetReminder.Overspent(monthOver, names.distinct())
                )
                BudgetReminder.scheduleNext(appContext)
            }
            pending.finish()
        }.start()
    }
}
