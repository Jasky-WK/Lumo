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
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * N52 信用卡还款日前一天提醒。
 *
 * 纯本地实现：与 [RecurringReminder] 同一套路——AlarmManager 只排「最近的一次」，
 * 到点由 [CreditReminderReceiver] 发通知并顺手排下一次；不联网、不申请 INTERNET 权限。
 * 开关与还款日都放在账户表里（Account.creditRemind / Account.creditDueDay），默认关闭。
 *
 * 跨月 / 月底边界：先把还款日按「当月最后一天」夹取（31 号在 30 天的月份算 30 号、
 * 2 月算 28/29 号），再减一天作为提醒日，所以 1 号的还款日会在上个月最后一天提醒。
 */
object CreditReminder {

    private const val CHANNEL_ID = "credit_reminder"
    private const val CHANNEL_NAME = "信用卡还款提醒"
    private const val NOTIFY_ID = 9002
    private const val REQUEST_CODE = 9002

    /** 提醒时刻：当天早上 9 点 */
    private const val HOUR = 9

    /** 本月实际的还款日（该月没有这一天就取当月最后一天） */
    private fun dueDateOf(ym: YearMonth, dueDay: Int): LocalDate =
        ym.atDay(dueDay.coerceIn(1, ym.lengthOfMonth()))

    /** 需要提醒的账户：信用卡 + 开了提醒 + 设了还款日 */
    private fun reminding(accounts: List<Account>): List<Account> =
        accounts.filter { it.isCreditCard && it.creditRemind && it.creditDueDay in 1..31 }

    /** 下一次提醒时刻（毫秒）；没有需要提醒的账户返回 null */
    fun nextRemindAt(accounts: List<Account>, now: Long): Long? {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var earliest: Long? = null
        reminding(accounts).forEach { acc ->
            // 本月 / 下月 / 下下月里找第一个还没到的提醒点（避免 1 号的账户跨月取不到）
            for (step in 0..2) {
                val ym = YearMonth.from(today).plusMonths(step.toLong())
                val remindDate = dueDateOf(ym, acc.creditDueDay).minusDays(1)
                val at = remindDate.atTime(HOUR, 0).atZone(zone).toInstant().toEpochMilli()
                if (at > now) {
                    if (earliest == null || at < earliest!!) earliest = at
                    break
                }
            }
        }
        return earliest
    }

    /** 今天就是「还款日前一天」的账户 */
    fun dueToday(accounts: List<Account>, now: Long): List<Account> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return reminding(accounts).filter { acc ->
            dueDateOf(YearMonth.from(today), acc.creditDueDay).minusDays(1) == today
        }
    }

    /** 排下一次提醒；没有可提醒的账户时取消已有提醒 */
    fun schedule(context: Context, accounts: List<Account>, now: Long = System.currentTimeMillis()) {
        val at = nextRemindAt(accounts, now)
        if (at == null) {
            cancel(context)
            return
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CreditReminderReceiver::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /** 发通知：列出明天要还款的信用卡 */
    fun notifyDue(context: Context, accounts: List<Account>, now: Long = System.currentTimeMillis()) {
        val due = dueToday(accounts, now)
        if (due.isEmpty()) return
        ensureChannel(context)

        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val title = if (due.size == 1) "明天有 1 张信用卡要还款" else "明天有 ${due.size} 张信用卡要还款"
        val lines = due.take(4).joinToString("\n") { acc ->
            val day = dueDateOf(YearMonth.from(today), acc.creditDueDay).dayOfMonth
            "· " + acc.name + "（每月 $day 号）"
        }
        val text = if (due.size > 4) lines + "\n· 还有 ${due.size - 4} 张" else lines

        val tap = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open", "accounts")
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
                description = "信用卡还款日前一天的本地提醒，不需要联网"
            }
        )
    }
}

/** 提醒到点：发通知 + 排下一次 */
class CreditReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            runCatching {
                val db = AppDatabase.get(appContext)
                val accounts = runBlocking { db.accountDao().all() }
                // N59：账本软删除后，它名下账户的还款提醒也停（还原账本后恢复）
                val liveLedgerIds = runBlocking { db.ledgerDao().all() }.map { it.id }.toSet()
                val scoped = accounts.filter { it.ledgerIds.any { id -> id in liveLedgerIds } }
                CreditReminder.notifyDue(appContext, scoped)
                CreditReminder.schedule(appContext, scoped)
            }
            pending.finish()
        }.start()
    }
}
