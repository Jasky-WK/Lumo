package com.qingjizhang.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.util.ReportCat
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.exportExcel
import com.qingjizhang.app.vm.openTxnList
import com.qingjizhang.app.vm.setCustomRange
import com.qingjizhang.app.vm.setPeriodKind
import com.qingjizhang.app.vm.shiftPeriod

/**
 * 报表（v2.7.1 改版，配合「方案 D」）：
 *
 * 定位 = **导出与定制**，不再做第二份总览：
 * - 时间范围与总览**共用同一个状态**（按日 / 按月 / 按年 / 自定义 + 左右切期），切一处两边同步；
 * - 主体是**分类表格**（分类 / 笔数 / 金额 / 占比），点一行进该分类的账单明细；
 * - 导出：**保存图片**（报告卡，跟随所选范围，不再是写死的"当月"）与 **导出 Excel**；
 * - 看图请去「总览」。
 */
@Composable
fun ReportsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val stats by vm.reportStats.collectAsState()
    val kind by vm.periodKind.collectAsState()
    val anchor by vm.anchor.collectAsState()
    val rangeStart by vm.rangeStart.collectAsState()
    val rangeEnd by vm.rangeEnd.collectAsState()
    val context = LocalContext.current

    var showReportCard by remember { mutableStateOf(false) }
    var showRange by remember { mutableStateOf(false) }
    var trendCatId by remember { mutableStateOf(0L) }
    val periodLabel =
        if (kind == 3) DateFmt.isoRange(rangeStart, rangeEnd) else DateFmt.periodLabel(kind, anchor)

    if (showRange) {
        RangePickerDialog(
            initialStart = rangeStart,
            initialEnd = rangeEnd,
            onDismiss = { showRange = false },
            onConfirm = { s, e ->
                vm.setCustomRange(s, e)
                showRange = false
            }
        )
    }
    if (showReportCard) {
        MonthlyReportSheet(vm = vm, stats = stats, onDismiss = { showReportCard = false })
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        BackTopBar(
            "报表",
            onBack,
            actions = {
                IconAction(Icons.Rounded.FileDownload, {
                    vm.exportExcel()
                    Toast.makeText(context, "正在导出 Excel…", Toast.LENGTH_SHORT).show()
                })
            }
        )

        Column(Modifier.padding(horizontal = 14.dp)) {

            // ---------------- 时间范围（与总览共用） ----------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardWhite),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconAction(Icons.Rounded.ChevronLeft, { vm.shiftPeriod(-1) }, tint = Sub2, size = 18.dp)
                    Text(periodLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
                    IconAction(Icons.Rounded.ChevronRight, { vm.shiftPeriod(1) }, tint = Sub2, size = 18.dp)
                }
                Spacer(Modifier.weight(1f))
                Text("共 ${stats.count} 笔", fontSize = 11.5.sp, color = Sub)
            }
            Spacer(Modifier.height(10.dp))
            SegTabs(listOf("按日", "按月", "按年", "自定义"), if (kind == 3) 3 else kind) {
                if (it == 3) showRange = true else vm.setPeriodKind(it)
            }

            // ---------------- 汇总（一行数字带） ----------------
            Spacer(Modifier.height(12.dp))
            CardBox {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        LedgerStat("收入", Money.withSymbol(stats.incomeCents), Income, Modifier.weight(1f))
                        LedgerStat("支出", Money.withSymbol(stats.expenseCents), Expense, Modifier.weight(1f))
                        LedgerStat(
                            "结余",
                            (if (stats.balanceCents >= 0) "+" else "−") +
                                Money.withSymbol(Math.abs(stats.balanceCents)),
                            if (stats.balanceCents >= 0) Primary else Expense,
                            Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stats.ledgerName + " · " + stats.title,
                        fontSize = 11.sp,
                        color = Muted2
                    )
                }
            }

            // ---------------- v2.9.0 图表：月内累计 / 环比 ----------------
            if (kind == 1 && stats.cumExpense.size >= 2) {
                SectionTitle(
                    "本月累计",
                    trailing = if (stats.budgetCents > 0L) "预算线 " + Money.text(stats.budgetCents) else "未设预算"
                )
                CardBox {
                    Column(Modifier.padding(vertical = 10.dp)) {
                        CumulativeChart(
                            cumIncome = stats.cumIncome,
                            cumExpense = stats.cumExpense,
                            budgetCents = stats.budgetCents,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        XAxis(
                            listOf(
                                "1日",
                                "${(stats.cumDayCount + 1) / 2}日",
                                "${stats.cumDayCount}日"
                            )
                        )
                        LegendDots()
                    }
                }
            }

            if (kind == 1) {
                SectionTitle("环比", trailing = "对比上月同期")
                CardBox {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        CompareRow("支出", stats.expenseCents, stats.prevSameExpenseCents, Expense, upIsGood = false)
                        Spacer(Modifier.height(10.dp))
                        CompareRow("收入", stats.incomeCents, stats.prevSameIncomeCents, Income, upIsGood = true)
                    }
                }
            }

            if (kind == 2 && stats.months.size >= 2) {
                SectionTitle("月度收支", trailing = "收入 / 支出")
                CardBox {
                    Column(Modifier.padding(vertical = 10.dp)) {
                        MonthBars(stats.months, Modifier.padding(horizontal = 12.dp))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stats.months.first().label, fontSize = 10.5.sp, color = Sub)
                            Text(stats.months.last().label, fontSize = 10.5.sp, color = Sub)
                        }
                        LegendDots()
                    }
                }
            }

            // ---------------- 支出分类表 ----------------
            SectionTitle("支出分类", trailing = "点一行看明细 ›")
            CatTable(
                rows = stats.expenseCats,
                totalCents = stats.expenseCents,
                color = Expense,
                onRowClick = {
                    vm.openTxnList(
                        TxnListSpecs.category(
                            kind = kind,
                            anchor = anchor,
                            categoryId = it.id,
                            name = it.name,
                            amountText = "",
                            rangeStart = rangeStart,
                            rangeEnd = rangeEnd
                        )
                    )
                }
            )

            // ---------------- 收入分类表 ----------------
            if (stats.incomeCats.isNotEmpty()) {
                SectionTitle("收入分类", trailing = "点一行看明细 ›")
                CatTable(
                    rows = stats.incomeCats,
                    totalCents = stats.incomeCents,
                    color = Income,
                    onRowClick = {
                        vm.openTxnList(
                            TxnListSpecs.category(
                            kind = kind,
                            anchor = anchor,
                            categoryId = it.id,
                            name = it.name,
                            amountText = "",
                            rangeStart = rangeStart,
                            rangeEnd = rangeEnd
                        )
                        )
                    }
                )
            }

            // ---------------- v2.9.0 分类逐月趋势 ----------------
            if (stats.expenseCats.isNotEmpty() && stats.trendMonths.size == 12) {
                val rows = stats.expenseCats.take(6)
                val selected = rows.firstOrNull { it.id == trendCatId } ?: rows.first()
                SectionTitle("分类逐月趋势", trailing = "近 12 个月 · 点分类切换")
                CardBox {
                    Column(Modifier.padding(vertical = 10.dp)) {
                        Row(
                            Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp)
                        ) {
                            rows.forEach { row ->
                                val on = row.id == selected.id
                                Text(
                                    row.name,
                                    fontSize = 12.sp,
                                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                    color = if (on) OnPrimary else Ink,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (on) Primary else FieldBg)
                                        .clickable { trendCatId = row.id }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        TrendChart(
                            values = stats.catTrend[selected.id] ?: List(12) { 0L },
                            lineColor = Expense,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        XAxis(
                            listOf(
                                stats.trendMonths[0],
                                stats.trendMonths[3],
                                stats.trendMonths[6],
                                stats.trendMonths[9],
                                stats.trendMonths[11]
                            )
                        )
                    }
                }
            }

            // ---------------- 导出 ----------------
            SectionTitle("导出", trailing = "图片与表格都跟随上面的时间")
            Row(Modifier.fillMaxWidth()) {
                ExportTile(
                    "保存图片",
                    "汇总卡 · 跟随所选时间",
                    Icons.Rounded.Image,
                    Primary,
                    Modifier.weight(1f)
                ) { showReportCard = true }
                Spacer(Modifier.width(10.dp))
                ExportTile(
                    "导出 Excel",
                    ".xlsx · 金额可求和",
                    Icons.Rounded.TableChart,
                    Income,
                    Modifier.weight(1f)
                ) { vm.exportExcel() }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "报表只做筛选、表格与导出；看趋势与构成请回「总览」",
                fontSize = 11.sp,
                color = Muted2,
                modifier = Modifier.padding(start = 4.dp)
            )

            Spacer(Modifier.height(100.dp))
        }
    }
}

/** 汇总里的三格数字（收入 / 支出 / 结余） */
@Composable
private fun LedgerStat(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier
) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = Sub)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

/**
 * v2.9.0 环比一行：本期金额 + 「较上月 ±x%」。
 * 支出的百分比上涨用红色（不好），收入上涨用绿色；反之反过来。
 */
@Composable
private fun CompareRow(
    label: String,
    current: Long,
    prev: Long,
    color: Color,
    upIsGood: Boolean
) {
    val delta = if (prev <= 0L) null else (current - prev) * 100.0 / prev
    val deltaColor = when {
        delta == null -> Muted2
        delta >= 0 -> if (upIsGood) Income else Expense
        else -> if (upIsGood) Expense else Income
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
        Text(
            Money.withSymbol(current),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (delta == null) "上月同期无数据"
            else {
                val sign = if (delta >= 0) "+" else "−"
                "较上月 $sign${Math.abs(delta).toInt()}%"
            },
            fontSize = 11.sp,
            color = deltaColor,
            modifier = Modifier.width(92.dp),
            textAlign = TextAlign.End,
            maxLines = 1
        )
    }
}

/** 图表图例：收入 / 支出两个小圆点 */
@Composable
private fun LegendDots() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf("收入" to Income, "支出" to Expense).forEach { (name, color) ->
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(4.dp))
            Text(name, fontSize = 10.sp, color = Sub)
            Spacer(Modifier.width(10.dp))
        }
    }
}

/** 分类表格：表头（分类 / 笔数 / 金额 / 占比）+ 数据行，点整行进明细 */
@Composable
private fun CatTable(
    rows: List<ReportCat>,
    totalCents: Long,
    color: androidx.compose.ui.graphics.Color,
    onRowClick: (ReportCat) -> Unit
) {
    CardBox {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("分类", fontSize = 10.5.sp, color = Muted2, modifier = Modifier.weight(1.6f))
                Text("笔数", fontSize = 10.5.sp, color = Muted2, modifier = Modifier.weight(0.7f), textAlign = TextAlign.End)
                Text("金额", fontSize = 10.5.sp, color = Muted2, modifier = Modifier.weight(1.1f), textAlign = TextAlign.End)
                Text("占比", fontSize = 10.5.sp, color = Muted2, modifier = Modifier.weight(0.7f), textAlign = TextAlign.End)
            }
            RowDivider()
            if (rows.isEmpty()) {
                Text(
                    "这段时间没有记录",
                    fontSize = 12.5.sp,
                    color = Sub,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                rows.forEachIndexed { index, row ->
                    if (index > 0) RowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onRowClick(row) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(row.name, fontSize = 13.sp, color = Ink, maxLines = 1, modifier = Modifier.weight(1.6f))
                        Text(
                            "${row.count}",
                            fontSize = 12.sp,
                            color = Sub,
                            modifier = Modifier.weight(0.7f),
                            textAlign = TextAlign.End
                        )
                        Text(
                            Money.text(row.cents),
                            fontSize = 13.sp,
                            color = color,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1.1f),
                            textAlign = TextAlign.End
                        )
                        Text(
                            if (totalCents <= 0L) "—" else ((row.cents * 100.0 / totalCents).toInt()).toString() + "%",
                            fontSize = 12.sp,
                            color = Sub2,
                            modifier = Modifier.weight(0.7f),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

/** 导出入口方块（图标 + 标题 + 说明） */
@Composable
private fun ExportTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CardWhite)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontSize = 10.5.sp, color = Muted2)
    }
}
