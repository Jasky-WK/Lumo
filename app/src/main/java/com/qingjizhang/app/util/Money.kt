package com.qingjizhang.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 金额一律用「分」存储，这里统一做展示与解析，避免浮点误差 */
object Money {

    /** 分 -> "1,234.56"。用 String.format 而不是共享的 DecimalFormat，保证多线程安全。 */
    fun text(cents: Long): String =
        String.format(java.util.Locale.CHINA, "%,.2f", cents / 100.0)

    /** 分 -> "¥1,234.56" */
    fun withSymbol(cents: Long): String = "¥" + text(cents)

    /** 分 -> "1,234"（整数，用于统计卡片） */
    fun textInt(cents: Long): String =
        String.format(java.util.Locale.CHINA, "%,d", Math.round(cents / 100.0))

    /** 分 -> 带符号："+1,234.56" / "-1,234.56" */
    fun signed(cents: Long): String = (if (cents < 0) "-" else "+") + text(Math.abs(cents))

    /** "1234.5" / "1234" -> 分；非法输入返回 null */
    fun parse(input: String): Long? {
        val s = input.trim().replace(",", "").replace("¥", "")
        if (s.isEmpty() || s == ".") return null
        val d = s.toDoubleOrNull() ?: return null
        if (d < 0 || d.isNaN() || d.isInfinite()) return null
        return Math.round(d * 100)
    }
}

object DateFmt {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun toMillis(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun toMillis(dateTime: LocalDateTime): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    fun dateOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun dateTimeOf(millis: Long): LocalDateTime =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    /** "2026-09"，用于按月分组 */
    fun monthKey(millis: Long): String =
        dateOf(millis).format(DateTimeFormatter.ofPattern("yyyy-MM"))

    /** "2026-09" -> "2026 年 9 月" */
    fun monthKeyLabel(key: String): String {
        val parts = key.split("-")
        val y = parts.getOrNull(0) ?: ""
        val m = parts.getOrNull(1)?.trimStart('0') ?: ""
        return "$y 年 $m 月"
    }

    /** "2026-09" */
    fun isoDate(millis: Long): String =
        dateOf(millis).format(DateTimeFormatter.ISO_LOCAL_DATE)

    /** "2026-09-21 18:42" */
    fun isoDateTime(millis: Long): String =
        dateTimeOf(millis).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    /** "18:42" */
    fun time(millis: Long): String =
        dateTimeOf(millis).format(DateTimeFormatter.ofPattern("HH:mm"))

    /** "09-21 周一" */
    fun dayHeader(millis: Long): String {
        val d = dateOf(millis)
        val week = "周" + "一二三四五六日"[d.dayOfWeek.value - 1]
        return d.format(DateTimeFormatter.ofPattern("MM-dd")) + " " + week
    }

    /** 列表里的相对时间："今天 15:45" / "昨天 09:19" / "09-18 12:30" */
    fun friendly(millis: Long): String {
        val d = dateOf(millis)
        val today = LocalDate.now()
        val prefix = when {
            d == today -> "今天 "
            d == today.minusDays(1) -> "昨天 "
            d.year == today.year -> d.format(DateTimeFormatter.ofPattern("MM-dd")) + " "
            else -> d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " "
        }
        return prefix + time(millis)
    }

    /** "2026-09" / "2026 年" / "09-21 周一" */
    fun periodLabel(kind: Int, anchor: LocalDate): String = when (kind) {
        0 -> dayHeader(toMillis(anchor))
        1 -> anchor.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        else -> anchor.year.toString() + " 年"
    }

    /** "2026-01-01 ~ 2026-03-31"，自定义区间用 */
    fun isoRange(start: LocalDate, end: LocalDate): String =
        start.format(DateTimeFormatter.ISO_LOCAL_DATE) + " ~ " +
            end.format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun monthRange(anchor: LocalDate): Pair<Long, Long> {
        val start = anchor.withDayOfMonth(1)
        val end = start.plusMonths(1)
        return toMillis(start) to toMillis(end)
    }

    fun yearRange(anchor: LocalDate): Pair<Long, Long> {
        val start = anchor.withDayOfYear(1)
        val end = start.plusYears(1)
        return toMillis(start) to toMillis(end)
    }

    fun dayRange(anchor: LocalDate): Pair<Long, Long> =
        toMillis(anchor) to toMillis(anchor.plusDays(1))
}
