package com.qingjizhang.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** 小票上的一行：分类 / 笔数 / 金额 */
data class ReceiptRow(val label: String, val count: Int, val cents: Long)

/** 小票数据（N6）：一次性快照，导出图片与界面预览共用 */
data class ReceiptData(
    val ledgerName: String,
    val rangeLabel: String,
    val filterLabel: String,
    val dateTime: String,
    val no: String,
    val rows: List<ReceiptRow>,
    val txnCount: Int,
    val expenseCents: Long,
    val incomeCents: Long
) {
    val balanceCents: Long get() = incomeCents - expenseCents
}

/**
 * 小票图片：用 Canvas 手绘一张白底小票 PNG。
 * 不依赖屏幕截图，导出的图与界面上看到的版式一致。
 */
object ReceiptImage {

    private const val W = 720f
    private const val PAD = 44f
    private const val BARCODE_H = 74f

    /** 画小票并写到 cacheDir/share/receipt-<单号>.png */
    suspend fun render(context: Context, data: ReceiptData): File = withContext(Dispatchers.IO) {
        val rowH = 54f
        val rowsShown = data.rows.size.coerceAtMost(16)
        val extraRows = if (data.rows.size > rowsShown) 1 else 0
        val height = 620f + (rowsShown + extraRows) * rowH
        val bmp = Bitmap.createBitmap(W.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val ink = Color.parseColor("#1B2430")
        val sub = Color.parseColor("#8A93A5")
        val line = Color.parseColor("#D8DEE9")

        val title = paint(ink, 40f, bold = true)
        val label = paint(ink, 26f)
        val value = paint(ink, 26f, bold = true, align = Paint.Align.RIGHT)
        val head = paint(sub, 25f, bold = true)
        val small = paint(sub, 22f)
        val classic = paint(ink, 27f)
        val total = paint(ink, 32f, bold = true)
        val totalV = paint(ink, 32f, bold = true, align = Paint.Align.RIGHT)
        val mono = paint(ink, 22f, align = Paint.Align.CENTER)

        var y = PAD + 34f

        // 顶部三个筛选胶囊（与界面一致，导出的图也带上当前选择）
        var x = PAD
        listOf(data.ledgerName, data.rangeLabel, data.filterLabel).forEach { t ->
            val w = label.measureText(t) + 40f
            val rect = RectF(x, y - 26f, x + w, y + 18f)
            c.drawRoundRect(rect, 22f, 22f, fill(Color.parseColor("#F2F5FB")))
            c.drawText(t, x + 20f, y + 2f, label)
            x += w + 14f
        }

        y += 92f
        c.drawText("Lumo", W / 2f, y, title.apply { textAlign = Paint.Align.CENTER })
        y += 46f
        c.drawText("小票", W / 2f, y, classic.apply { textAlign = Paint.Align.CENTER })
        y += 34f

        y = dashed(c, y, line)
        y += 46f
        c.drawText("小票日期：", PAD, y, label)
        c.drawText(data.dateTime, W - PAD, y, value)
        y += 42f
        c.drawText("小票单号：", PAD, y, label)
        c.drawText("No." + data.no, W - PAD, y, value)
        y += 30f
        y = dashed(c, y, line)
        y += 44f

        // 表头：分类 / 笔数 / 金额
        c.drawText("分类", PAD, y, head)
        c.drawText("笔数", W * 0.62f, y, head.apply { textAlign = Paint.Align.CENTER })
        c.drawText("金额", W - PAD, y, head.apply { textAlign = Paint.Align.RIGHT })
        y += 34f

        if (data.rows.isEmpty()) {
            y += 40f
            c.drawText("暂无数据", W / 2f, y, small.apply { textAlign = Paint.Align.CENTER })
            y += 26f
        } else {
            data.rows.take(rowsShown).forEach { r ->
                y += rowH
                c.drawText(r.label, PAD, y, classic)
                c.drawText(r.count.toString(), W * 0.62f, y, classic.apply { textAlign = Paint.Align.CENTER })
                c.drawText("¥" + Money.text(r.cents), W - PAD, y, classic.apply { textAlign = Paint.Align.RIGHT })
            }
            if (data.rows.size > rowsShown) {
                y += rowH
                c.drawText("其他 ${data.rows.size - rowsShown} 类", PAD, y, small)
            }
        }

        y += 34f
        y = dashed(c, y, line)
        y += 46f
        c.drawText("记账笔数：", PAD, y, label)
        c.drawText(data.txnCount.toString(), W - PAD, y, value)
        y += 42f
        c.drawText("支出合计：", PAD, y, label)
        c.drawText("¥" + Money.text(data.expenseCents), W - PAD, y, value)
        y += 42f
        c.drawText("收入合计：", PAD, y, label)
        c.drawText("¥" + Money.text(data.incomeCents), W - PAD, y, value)
        y += 50f
        c.drawText("结余：", PAD, y, total)
        c.drawText("¥" + Money.text(data.balanceCents), W - PAD, y, totalV)

        // 条码 + 单号
        y += 52f
        barcode(c, y, data.no, ink)
        y += BARCODE_H + 30f
        c.drawText("*" + data.no + "*", W / 2f, y, mono)

        val dir = File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
        val file = File(dir, "receipt-" + data.no + ".png")
        FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bmp.recycle()
        file
    }

    /** 保存一份到「图片/Lumo小票」，方便用户在图库 / 文件管理器里找到 */
    suspend fun save(context: Context, file: File): ImageSaveResult =
        ImageExport.saveImage(context, file, "Lumo小票")

    /** 分享小票图片（本地分享，不联网） */
    fun share(context: Context, file: File) {
        ImageExport.share(context, file, "分享小票")
    }

    // ---------------- 绘制小工具 ----------------

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

    private fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    /** 虚线分隔，返回新的 y */
    private fun dashed(c: Canvas, y: Float, color: Int): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(9f, 8f), 0f)
        }
        c.drawLine(PAD, y, W - PAD, y, p)
        return y
    }

    /** 用单号生成稳定条形码（不联网、纯本地画） */
    private fun barcode(c: Canvas, top: Float, no: String, color: Int) {
        val p = Paint().apply { this.color = color }
        val total = W - PAD * 2
        val bars = ArrayList<Float>()
        no.forEachIndexed { i, ch ->
            val v = (ch.code * (i + 3)) % 7
            bars.add(2f + v * 0.6f)
            bars.add(2f + ((v + i) % 4) * 0.7f)
        }
        val unit = total / bars.sum()
        var x = PAD
        bars.forEachIndexed { i, w ->
            val width = w * unit
            if (i % 2 == 0) c.drawRect(x, top, x + width, top + BARCODE_H, p)
            x += width
        }
    }
}
