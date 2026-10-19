package com.qingjizhang.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.ui.theme.Chevron
import com.qingjizhang.app.ui.theme.Expense
import com.qingjizhang.app.ui.theme.FieldBg
import com.qingjizhang.app.ui.theme.Income
import com.qingjizhang.app.ui.theme.Ink
import com.qingjizhang.app.ui.theme.Sub
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.util.ReportMonth

/**
 * 环形图/图例的一片。
 * [id] 与 [isAccount] 只服务于「点图例下钻」（F17）：带上被点对象的真实 id 去过滤明细，
 * 不再用名字反查（小类 / 账户名都能对上，也不会重名串数据）。id = 0 表示合并出来的「其他」桶，不可下钻。
 */
data class DonutSlice(
    val label: String,
    val valueCents: Long,
    val color: Color,
    val id: Long = 0L,
    val isAccount: Boolean = false
)

@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    totalCents: Long,
    centerLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = 118.dp,
    ring: Dp = 20.dp
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = ring.toPx()
            val inset = stroke / 2f
            val total = slices.sumOf { it.valueCents }.toFloat().coerceAtLeast(1f)
            var start = -90f
            slices.forEach { s ->
                val sweep = s.valueCents / total * 360f
                drawArc(
                    color = s.color,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.toPx() - stroke, size.toPx() - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerLabel, fontSize = 10.5.sp, color = Sub, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            val text = Money.text(totalCents)
            Text(
                text,
                fontSize = if (text.length > 7) 11.5.sp else 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            )
        }
    }
}

@Composable
fun LegendList(
    slices: List<DonutSlice>,
    totalCents: Long,
    modifier: Modifier = Modifier,
    onSliceClick: ((DonutSlice) -> Unit)? = null
) {
    Column(modifier) {
        slices.forEach { s ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onSliceClick != null) Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSliceClick(s) }
                        else Modifier
                    )
                    .padding(vertical = 3.5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(s.color))
                Spacer(Modifier.width(8.dp))
                Text(
                    s.label,
                    fontSize = 12.5.sp,
                    color = Ink,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(Money.text(s.valueCents), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.width(6.dp))
                Text(
                    pct(s.valueCents, totalCents),
                    fontSize = 11.sp,
                    color = Sub,
                    modifier = Modifier.width(34.dp)
                )
                if (onSliceClick != null) {
                    Icon(
                        androidx.compose.material.icons.Icons.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Chevron,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

/** 收支对比条（每行可点击下钻） */
@Composable
fun CompareBars(
    rows: List<Triple<String, Long, Color>>,
    modifier: Modifier = Modifier,
    onRowClick: ((Int) -> Unit)? = null
) {
    val max = rows.maxOfOrNull { Math.abs(it.second) }?.coerceAtLeast(1L) ?: 1L
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp)) {
        rows.forEachIndexed { index, (label, value, color) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onRowClick != null) Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRowClick(index) }
                        else Modifier
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, fontSize = 13.sp, color = com.qingjizhang.app.ui.theme.Sub2, modifier = Modifier.width(40.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(9.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(FieldBg)
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((Math.abs(value).toFloat() / max).coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(color.copy(alpha = 0.75f), color)
                                )
                            )
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    Money.text(Math.abs(value)),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    modifier = Modifier.width(84.dp)
                )
                if (onRowClick != null) {
                    Icon(
                        androidx.compose.material.icons.Icons.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Chevron,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

/** 面积折线趋势图 */
@Composable
fun TrendChart(
    values: List<Long>,
    modifier: Modifier = Modifier,
    height: Dp = 104.dp,
    lineColor: Color = Expense
) {
    // 折线末端圆点用卡片底色镂空：深色下不能写死白色，否则卡片上会冒出一个亮斑
    val dotFill = com.qingjizhang.app.ui.theme.colors.card
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.size < 2) return@Canvas
        val maxV = (values.maxOrNull() ?: 0L).toFloat().coerceAtLeast(1f) * 1.15f
        val padX = 6.dp.toPx()
        val usableH = size.height - 22.dp.toPx()
        val step = (size.width - padX * 2) / (values.size - 1)
        val pts = values.mapIndexed { i, v ->
            Offset(padX + i * step, usableH - v / maxV * usableH + 8.dp.toPx())
        }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 0 until pts.size - 1) {
                val p0 = pts[if (i - 1 < 0) 0 else i - 1]
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val p3 = pts[if (i + 2 > pts.size - 1) pts.size - 1 else i + 2]
                cubicTo(
                    p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                    p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                    p2.x, p2.y
                )
            }
        }
        val area = Path().apply {
            addPath(path)
            lineTo(pts.last().x, size.height)
            lineTo(pts.first().x, size.height)
            close()
        }
        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0f)),
                startY = pts.minOf { it.y },
                endY = size.height
            )
        )
        drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        if (values.last() > 0L) {
            drawCircle(dotFill, radius = 4.dp.toPx(), center = pts.last())
            drawCircle(
                lineColor,
                radius = 4.dp.toPx(),
                center = pts.last(),
                style = Stroke(width = 2.4.dp.toPx())
            )
        }
    }
}

@Composable
fun XAxis(labels: List<String>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        labels.forEach { Text(it, fontSize = 10.5.sp, color = Sub) }
    }
}

/** 预算虚线的颜色：深浅模式下都可读的暖金色 */
private val BudgetLineColor = Color(0xFFB08A2E)

/**
 * v2.9.0：月内累计曲线（收入 / 支出两条线 + 预算虚线）。
 *
 * 数据由 `AppViewModel.buildCumulativeSeries` 给出，两条线与当月已过天数等长。
 * 支出累计超过预算时，超出的那一小段描红加粗，让「按这个速度会不会超」一眼可见。
 */
@Composable
fun CumulativeChart(
    cumIncome: List<Long>,
    cumExpense: List<Long>,
    budgetCents: Long,
    modifier: Modifier = Modifier,
    height: Dp = 128.dp
) {
    // 主题色是 @Composable getter，必须在进入 Canvas 之前取出来
    val incomeColor = Income
    val expenseColor = Expense
    val fieldBg = FieldBg
    if (cumExpense.size < 2) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text("本月累计数据不足", fontSize = 12.sp, color = Sub)
        }
        return
    }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val maxV = maxOf(
            cumExpense.maxOrNull() ?: 0L,
            cumIncome.maxOrNull() ?: 0L,
            budgetCents
        ).toFloat().coerceAtLeast(1f) * 1.12f
        val bottom = size.height - 6.dp.toPx()
        val top = 6.dp.toPx()
        val usable = bottom - top
        val n = cumExpense.size
        val step = size.width / (n - 1).coerceAtLeast(1)

        fun yOf(v: Long): Float = bottom - (v / maxV) * usable
        fun xOf(i: Int): Float = i * step

        // 基线
        drawLine(fieldBg, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())

        // 预算虚线
        if (budgetCents > 0L) {
            val by = yOf(budgetCents)
            drawLine(
                BudgetLineColor,
                Offset(0f, by),
                Offset(size.width, by),
                strokeWidth = 1.6.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))
            )
        }

        // 收入、支出两条折线（直线连接，累计值不需要平滑）
        fun polyline(values: List<Long>, color: Color, width: Float) {
            if (values.size < 2) return
            val p = Path()
            values.forEachIndexed { i, v ->
                if (i == 0) p.moveTo(xOf(i), yOf(v)) else p.lineTo(xOf(i), yOf(v))
            }
            drawPath(p, color, style = Stroke(width = width, cap = StrokeCap.Round))
            drawCircle(color, 3.4.dp.toPx(), Offset(xOf(values.lastIndex), yOf(values.last())))
        }
        polyline(cumIncome, incomeColor, 1.8.dp.toPx())
        polyline(cumExpense, expenseColor, 2.2.dp.toPx())

        // 超出预算的那一段加粗描红（从预算线与支出线的交点起到末端）
        if (budgetCents > 0L && (cumExpense.lastOrNull() ?: 0L) > budgetCents) {
            val startIdx = cumExpense.indexOfFirst { it > budgetCents }
            if (startIdx >= 0 && startIdx < n - 1) {
                val p = Path()
                for (i in startIdx until n) {
                    if (i == startIdx) p.moveTo(xOf(i), yOf(cumExpense[i]))
                    else p.lineTo(xOf(i), yOf(cumExpense[i]))
                }
                drawPath(p, expenseColor.copy(alpha = 0.55f), style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

/**
 * v2.9.0：逐月收支双柱（按年视图用）。每个月两根：收入（绿）在左、支出（红）在右。
 */
@Composable
fun MonthBars(
    months: List<ReportMonth>,
    modifier: Modifier = Modifier,
    height: Dp = 118.dp
) {
    if (months.isEmpty()) return
    val incomeColor = Income
    val expenseColor = Expense
    val fieldBg = FieldBg
    Canvas(modifier.fillMaxWidth().height(height)) {
        val maxV = months.maxOf { maxOf(it.incomeCents, it.expenseCents) }.coerceAtLeast(1L).toFloat()
        val bottom = size.height - 4.dp.toPx()
        val usable = bottom - 4.dp.toPx()
        val slot = size.width / months.size
        val barW = (slot * 0.26f).coerceAtLeast(2.dp.toPx())
        val corner = CornerRadius(barW / 2f, barW / 2f)
        drawLine(fieldBg, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
        months.forEachIndexed { i, m ->
            val center = slot * i + slot / 2f
            val ih = (m.incomeCents / maxV) * usable
            val eh = (m.expenseCents / maxV) * usable
            if (ih > 0.5f) {
                drawRoundRect(
                    incomeColor,
                    topLeft = Offset(center - barW - 1.dp.toPx(), bottom - ih),
                    size = Size(barW, ih),
                    cornerRadius = corner
                )
            }
            if (eh > 0.5f) {
                drawRoundRect(
                    expenseColor,
                    topLeft = Offset(center + 1.dp.toPx(), bottom - eh),
                    size = Size(barW, eh),
                    cornerRadius = corner
                )
            }
        }
    }
}
