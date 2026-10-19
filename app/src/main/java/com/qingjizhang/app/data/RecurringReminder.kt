package com.qingjizhang.app.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.qingjizhang.app.MainActivity
import com.qingjizhang.app.R
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money

/**
 * 周期记账的本地到期提醒（N8）。
 *
 * 纯本地实现：不用联网、不申请新权限，只用 AlarmManager 排一次「最近的一次提醒」，
 * 到点由 [RecurringReminderReceiver] 发通知并顺手排下一次。
 * 提醒开关存在 SharedPreferences，默认关闭，用户可以在设置 / 周期记账页打开。
 */
object RecurringReminder {

    private const val CHANNEL_ID = "recurring_reminder"
    private const val CHANNEL_NAME = "周期记账提醒"
    private const val NOTIFY_ID = 9001
    private const val REQUEST_CODE = 9001
    private const val DAY = 24L * 60 * 60 * 1000

    private const val PREFS = "bookkeeping_prefs"
    private const val KEY_ENABLED = "recurring_reminder_on"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) cancel(context)
    }

    /** 下一次该提醒的时刻（毫秒）；没有可提醒的规则返回 null */
    fun nextReminderAt(rules: List<RecurringRule>, now: Long): Long? {
        var earliest: Long? = null
        rules.forEach { r ->
            if (!r.enabled || r.notifyAheadDays <= 0) return@forEach
            val next = RecurringEngine.nextRun(r, now) ?: return@forEach
            val at = next - r.notifyAheadDays.coerceAtLeast(0) * DAY
            // 已经过了提醒点（比如刚打开 App 才补上）就给个近在眼前的时刻
            val fixed = if (at <= now) now + 5_000L else at
            if (earliest == null || fixed < earliest!!) earliest = fixed
        }
        return earliest
    }

    /** 排下一次提醒；关掉开关或没有规则时取消已有提醒 */
    fun schedule(context: Context, rules: List<RecurringRule>, now: Long = System.currentTimeMillis()) {
        val at = if (isEnabled(context)) nextReminderAt(rules, now) else null
        if (at == null) {
            cancel(context)
            return
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            // 不申请精确闹钟权限，用允许待机时触发的方式，差几分钟对记账场景没影响
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RecurringReminderReceiver::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /** 到点的规则：下一期在「提前 N 天」范围内 */
    fun dueRules(rules: List<RecurringRule>, now: Long): List<Pair<RecurringRule, Long>> =
        rules.filter { it.enabled && it.notifyAheadDays >= 0 }
            .mapNotNull { r ->
                val next = RecurringEngine.nextRun(r, now) ?: return@mapNotNull null
                val at = next - r.notifyAheadDays.coerceAtLeast(0) * DAY
                if (at <= now + 60_000L) r to next else null
            }

    /** 发通知：列出最近要到期的几笔周期账单 */
    fun notifyDue(context: Context, rules: List<RecurringRule>, now: Long = System.currentTimeMillis()) {
        val due = dueRules(rules, now)
        if (due.isEmpty()) return
        ensureChannel(context)

        val title = if (due.size == 1) "有 1 笔周期账单要入账" else "有 ${due.size} 笔周期账单要入账"
        val lines = due.take(4).joinToString("\n") { (r, next) ->
            "· " + r.name + " ¥" + Money.text(r.amountCents) + "（" + DateFmt.isoDate(next) + "）"
        }
        val text = if (due.size > 4) lines + "\n· 还有 ${due.size - 4} 笔" else lines

        val tap = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open", "recurring")
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(context, REQUEST_CODE, tap, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recurring)
            .setContentTitle(title)
            .setContentText(lines.replace("\n", " · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.notify(NOTIFY_ID, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "周期记账到期前的本地提醒，不需要联网"
            }
        )
    }
}

/** 提醒到点：发通知 + 排下一次 */
class RecurringReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            runCatching {
                val db = AppDatabase.get(appContext)
                val rules = kotlinx.coroutines.runBlocking { db.ruleDao().enabledRules() }
                if (RecurringReminder.isEnabled(appContext)) {
                    RecurringReminder.notifyDue(appContext, rules)
                    RecurringReminder.schedule(appContext, rules)
                }
            }
            pending.finish()
        }.start()
    }
}
