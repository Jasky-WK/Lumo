package com.qingjizhang.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** 报表里一个月的数据 */
data class ReportMonth(
    val label: String,
    val incomeCents: Long,
    val expenseCents: Long,
    val count: Int
) {
    val balanceCents: Long get() = incomeCents - expenseCents
}

/** 报表里的分类排行项 */
data class ReportCat(val name: String, val cents: Long, val count: Int, val id: Long = 0L)

/** 报表数据（N9）：年报告 / 自定义区间共用一份结构 */
data class ReportStats(
    val ledgerName: String,
    val title: String,
    val months: List<ReportMonth>,
    val incomeCents: Long,
    val expenseCents: Long,
    val count: Int,
    val expenseCats: List<ReportCat>,
    val incomeCats: List<ReportCat>,
    /* ---- v2.7.1：任意区间的报告卡（导出图片跟随所选时间范围）---- */
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val prevIncomeCents: Long = 0L,
    val prevExpenseCents: Long = 0L,
    val maxExpenseCents: Long = 0L,
    val maxExpenseNote: String = "",
    val activeDays: Int = 0,
    val budgetCents: Long = 0L,
    val budgetUsedCents: Long = 0L,
    /* ---- v2.9.0：报表三图的数据（累计曲线 / 环比 / 分类逐月趋势）---- */
    /** 月内逐日累计收入（下标 0 = 1 号），只在「按月」下非空 */
    val cumIncome: List<Long> = emptyList(),
    /** 月内逐日累计支出（下标 0 = 1 号），只在「按月」下非空 */
    val cumExpense: List<Long> = emptyList(),
    /** 累计曲线的天数（= cumExpense.size，单独存一份便于 UI 文案） */
    val cumDayCount: Int = 0,
    /** 上一个自然月「同期」（同一天数窗口）的收入 / 支出，仅用于环比 */
    val prevSameIncomeCents: Long = 0L,
    val prevSameExpenseCents: Long = 0L,
    /** 分类趋势的 12 个月标签（含跨年） */
    val trendMonths: List<String> = emptyList(),
    /** 一级分类 id -> 近 12 个月逐月金额（与 trendMonths 等长） */
    val catTrend: Map<Long, List<Long>> = emptyMap()
) {
    val balanceCents: Long get() = incomeCents - expenseCents
    /** 月均支出（按报表里的月份数算） */
    val avgExpenseCents: Long get() = if (months.isEmpty()) 0L else expenseCents / months.size
}

/**
 * 报表导出图片（N9）：把报表画成一张竖长图，方便存下来或发给别人。
 * 纯本地绘制，不联网、不依赖屏幕截图。
 */
object ReportImage {

    private const val W = 760f
    private const val PAD = 40f

    suspend fun render(context: Context, stats: ReportStats, generatedAt: String): File =
        withContext(Dispatchers.IO) {
            val monthRows = stats.months.size
            val catRows = stats.expenseCats.take(8).size
            // v2.9.0：有累计数据时给累计曲线留出高度
            val cumExtra = if (stats.cumExpense.size >= 2) 210f else 0f
            val height = 560f + monthRows * 26f + catRows * 60f + 260f + cumExtra
            val bmp = Bitmap.createBitmap(W.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)

            val ink = Color.parseColor("#1B2430")
            val sub = Color.parseColor("#8A93A5")
            val line = Color.parseColor("#E3E8F1")
            val expense = Color.parseColor("#E87461")
            val income = Color.parseColor("#2E9E6B")
            val primary = Color.parseColor("#4C7DF0")

            val title = paint(ink, 42f, bold = true, align = Paint.Align.CENTER)
            val subTitle = paint(sub, 24f, align = Paint.Align.CENTER)
            val section = paint(ink, 28f, bold = true)
            val label = paint(sub, 24f)
            val value = paint(ink, 26f, bold = true, align = Paint.Align.RIGHT)
            val small = paint(sub, 22f)
            val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F0F3F9") }

            var y = PAD + 26f
            c.drawText("Lumo · 报表", W / 2f, y, title)
            y += 38f
            c.drawText(stats.ledgerName + " · " + stats.title + " · 生成于 " + generatedAt, W / 2f, y, subTitle)
            y += 34f
            c.drawLine(PAD, y, W - PAD, y, paint(line, 2f))
            y += 44f

            // 汇总
            c.drawText("收支汇总", PAD, y, section)
            y += 40f
            fun summaryRow(left: String, lv: String, right: String, rv: String, color: Int) {
                c.drawText(left, PAD, y, label)
                c.drawText(lv, W / 2f + 60f, y, paint(color, 26f, bold = true, align = Paint.Align.RIGHT))
                c.drawText(right, W / 2f + 90f, y, label)
                c.drawText(rv, W - PAD, y, value)
                y += 36f
            }
            summaryRow("收入", "¥" + Money.text(stats.incomeCents), "支出", "¥" + Money.text(stats.expenseCents), income)
            summaryRow("结余", "¥" + Money.text(stats.balanceCents), "笔数", stats.count.toString(), primary)
            c.drawText("月均支出", PAD, y, label)
            c.drawText("¥" + Money.text(stats.avgExpenseCents), W - PAD, y, value)
            y += 46f

            // v2.9.0：月内累计曲线 + 预算虚线（只在「按月」且有数据时出现）
            if (stats.cumExpense.size >= 2) {
                c.drawText("月内累计", PAD, y, section)
                c.drawText("收入 · 支出 · 预算线", W - PAD, y, paint(sub, 22f, align = Paint.Align.RIGHT))
                y += 26f
                val chartTop = y
                val chartH = 130f
                val left = PAD
                val right = W - PAD
                val n = stats.cumExpense.size
                val maxCum = maxOf(
                    stats.cumExpense.maxOf { it },
                    stats.cumIncome.maxOfOrNull { it } ?: 0L,
                    stats.budgetCents
                ).coerceAtLeast(1L)
                fun px(i: Int): Float = left + (right - left) * i / (n - 1).coerceAtLeast(1)
                fun py(v: Long): Float = chartTop + chartH - (v.toFloat() / maxCum) * chartH

                // 网格：底线 + 中线
                c.drawLine(left, chartTop + chartH, right, chartTop + chartH, paint(line, 2f))
                c.drawLine(left, chartTop + chartH / 2f, right, chartTop + chartH / 2f, paint(line, 2f))

                // 预算虚线
                if (stats.budgetCents > 0L) {
                    val by = py(stats.budgetCents)
                    val budgetPaint = paint(Color.parseColor("#B08A2E"), 2.4f).apply {
                        style = Paint.Style.STROKE
                        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
                    }
                    c.drawLine(left, by, right, by, budgetPaint)
                    c.drawText("预算", right - 4f, by - 6f, paint(Color.parseColor("#B08A2E"), 20f, align = Paint.Align.RIGHT))
                }

                fun polyline(values: List<Long>, color: Int) {
                    if (values.size < 2) return
                    val p = Path()
                    values.forEachIndexed { i, v ->
                        if (i == 0) p.moveTo(px(i), py(v)) else p.lineTo(px(i), py(v))
                    }
                    c.drawPath(p, paint(color, 3f).apply { style = Paint.Style.STROKE })
                    c.drawCircle(px(values.lastIndex), py(values.last()), 5f, paint(color, 0f).apply { style = Paint.Style.FILL })
                }
                polyline(stats.cumIncome, income)
                polyline(stats.cumExpense, expense)

                // X 轴：1 日 / 中 / 末
                y = chartTop + chartH + 24f
                c.drawText("1日", left, y, paint(sub, 20f))
                c.drawText(stats.cumDayCount.toString() + "日", right, y, paint(sub, 20f, align = Paint.Align.RIGHT))
                c.drawText(Money.text(stats.cumExpense.lastOrNull() ?: 0L), right, chartTop + 18f, paint(expense, 22f, align = Paint.Align.RIGHT))
                y += 24f
            }

            // 逐月柱状
            c.drawText("逐月收支", PAD, y, section)
            y += 30f
            val maxV = stats.months.maxOfOrNull { maxOf(it.incomeCents, it.expenseCents) }?.coerceAtLeast(1L) ?: 1L
            val chartH = 150f
            stats.months.forEach { m ->
                val labelW = 120f
                val barLeft = PAD + labelW
                val barRight = W - PAD - 150f
                val barW = barRight - barLeft
                c.drawText(m.label, PAD, y + 18f, small)
                c.drawRect(RectF(barLeft, y + 2f, barLeft + barW, y + 14f), barBg)
                val iw = barW * (m.incomeCents.toFloat() / maxV)
                if (iw > 0f) c.drawRect(RectF(barLeft, y + 2f, barLeft + iw, y + 14f), paint(income, 0f).apply { style = Paint.Style.FILL })
                c.drawRect(RectF(barLeft, y + 18f, barLeft + barW, y + 30f), barBg)
                val ew = barW * (m.expenseCents.toFloat() / maxV)
                if (ew > 0f) c.drawRect(RectF(barLeft, y + 18f, barLeft + ew, y + 30f), paint(expense, 0f).apply { style = Paint.Style.FILL })
                c.drawText("+ " + Money.text(m.incomeCents), W - PAD - 140f, y + 12f, small)
                c.drawText("- " + Money.text(m.expenseCents), W - PAD - 140f, y + 28f, small)
                y += 34f
            }
            if (stats.months.isEmpty()) {
                c.drawText("这段时间没有账单", PAD + 120f, y + 10f, small)
                y += 34f
            }
            y += 20f

            // 支出分类排行
            c.drawText("支出分类排行", PAD, y, section)
            y += 34f
            val catMax = stats.expenseCats.maxOfOrNull { it.cents }?.coerceAtLeast(1L) ?: 1L
            if (stats.expenseCats.isEmpty()) {
                c.drawText("暂无数据", PAD, y, small)
                y += 40f
            } else {
                stats.expenseCats.take(8).forEach { cat ->
                    c.drawText(cat.name + "（" + cat.count + " 笔）", PAD, y, label)
                    c.drawText("¥" + Money.text(cat.cents), W - PAD, y, value)
                    y += 14f
                    val bw = W - PAD * 2
                    c.drawRect(RectF(PAD, y, PAD + bw, y + 10f), barBg)
                    val w = bw * (cat.cents.toFloat() / catMax)
                    if (w > 0f) {
                        c.drawRect(RectF(PAD, y, PAD + w, y + 10f), paint(expense, 0f).apply { style = Paint.Style.FILL })
                    }
                    y += 46f
                }
            }

            y += 10f
            c.drawLine(PAD, y, W - PAD, y, paint(line, 2f))
            y += 34f
            c.drawText("本机生成 · 无网络权限 · 数据只存在这台手机", W / 2f, y, subTitle)

            val dir = File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
            val safeTitle = stats.title.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fa5]"), "")
            val file = File(dir, "report-" + safeTitle + "-" + System.currentTimeMillis() + ".png")
            FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bmp.recycle()
            file
        }

    private fun paint(
        color: Int,
        size: Float,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        textAlign = align
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }
}
