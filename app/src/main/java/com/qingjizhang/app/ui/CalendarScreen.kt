package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.DayStat
import com.qingjizhang.app.vm.resetCalMonth
import com.qingjizhang.app.vm.shiftCalMonth
import com.qingjizhang.app.vm.toast
import java.time.LocalDate

/**
 * 收支日历：总览右上角日历图标进来。
 * 月历每格显示当天的支出（红）/ 收入（绿），点某一天看当天全部账单。
 */
@Composable
fun CalendarScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenDay: (LocalDate) -> Unit
) {
    val anchor by vm.calAnchor.collectAsState()
    val days by vm.calDays.collectAsState()
    val today = LocalDate.now()

    val income = days.sumOf { it.incomeCents }
    val expense = days.sumOf { it.expenseCents }
    val count = days.sumOf { it.count }
    val balance = income - expense

    val total = anchor.lengthOfMonth()
    val lead = anchor.withDayOfMonth(1).dayOfWeek.value - 1   // 周一为一周起始
    val rows = (lead + total + 6) / 7

    Column(Modifier.fillMaxSize()) {
        BackTopBar(
            title = "收支日历",
            onBack = onBack,
            actions = {
                IconAction(Icons.Rounded.HelpOutline, {
                    vm.toast("每格显示当天的支出与收入，点日期可看当天全部账单")
                }, tint = Sub2, size = 19.dp)
            }
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(Icons.Rounded.ChevronLeft, { vm.shiftCalMonth(-1) }, tint = Sub2, size = 20.dp)
                Text(
                    DateFmt.periodLabel(1, anchor),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                IconAction(Icons.Rounded.ChevronRight, { vm.shiftCalMonth(1) }, tint = Sub2, size = 20.dp)
                Spacer(Modifier.weight(1f))
                if (anchor != today.withDayOfMonth(1)) {
                    Text(
                        "回到本月",
                        fontSize = 12.sp,
                        color = Primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { vm.resetCalMonth() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // 本月合计
            CardBox {
                Column(Modifier.padding(16.dp)) {
                    Text(DateFmt.periodLabel(1, anchor) + " 本月合计", fontSize = 11.5.sp, color = Sub)
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            if (balance < 0) "-¥" else "¥",
                            fontSize = 16.sp,
                            color = Ink,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            Money.text(kotlin.math.abs(balance)),
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (balance < 0) Expense else Ink
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 13.dp)) {
                        CalStat("收入", "+" + Money.text(income), Income, Modifier.weight(1f))
                        CalStat("支出", "-" + Money.text(expense), Expense, Modifier.weight(1f))
                        CalStat("账单笔数", "$count 笔", Ink, Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 月历
            CardBox {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                            Text(
                                "周$it",
                                fontSize = 11.sp,
                                color = Sub,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                            )
                        }
                    }
                    for (r in 0 until rows) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            for (c in 0 until 7) {
                                val dayNum = r * 7 + c - lead + 1
                                if (dayNum in 1..total) {
                                    val stat = days.getOrNull(dayNum - 1) ?: DayStat(dayNum)
                                    val date = anchor.withDayOfMonth(dayNum)
                                    DayCell(
                                        stat = stat,
                                        isToday = date == today,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onOpenDay(date) }
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendDot(Expense, "当天支出")
                Spacer(Modifier.width(14.dp))
                LegendDot(Income, "当天收入")
                Spacer(Modifier.width(14.dp))
                LegendDot(Sub, "仅互转 / 借贷")
            }
            Text(
                "浅蓝底 = 当天有账单，点日期看当天全部账单",
                fontSize = 11.sp,
                color = Sub,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CalStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = Sub)
        Spacer(Modifier.height(3.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, color = Sub)
    }
}

@Composable
private fun DayCell(
    stat: DayStat,
    isToday: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = when {
        isToday -> PrimaryBg
        stat.hasData -> FieldBg
        else -> Color.Transparent
    }
    Column(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = stat.hasData) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(5.dp))
        Text(
            stat.day.toString(),
            fontSize = 12.5.sp,
            fontWeight = if (isToday || stat.hasData) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) Primary else Ink
        )
        if (stat.expenseCents > 0L) {
            Text("-" + compactYuan(stat.expenseCents), fontSize = 9.sp, color = Expense, maxLines = 1)
        }
        if (stat.incomeCents > 0L) {
            Text("+" + compactYuan(stat.incomeCents), fontSize = 9.sp, color = Income, maxLines = 1)
        }
        if (stat.expenseCents == 0L && stat.incomeCents == 0L && stat.otherCount > 0) {
            Text("${stat.otherCount} 笔", fontSize = 9.sp, color = Sub, maxLines = 1)
        }
    }
}

/** 日历格子里的紧凑金额：过万缩写成 1.2w，其余取整 */
private fun compactYuan(cents: Long): String {
    val yuan = cents / 100.0
    return if (yuan >= 10000) {
        String.format(java.util.Locale.US, "%.1fw", yuan / 10000).replace(".0w", "w")
    } else {
        Math.round(yuan).toString()
    }
}
