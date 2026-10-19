package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
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
import com.qingjizhang.app.data.ArApDirection
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.TxnListSpec
import com.qingjizhang.app.vm.resetPeriod
import com.qingjizhang.app.vm.setCatMode
import com.qingjizhang.app.vm.setCustomRange
import com.qingjizhang.app.vm.setFlowMode
import com.qingjizhang.app.vm.setPeriodKind
import com.qingjizhang.app.vm.shiftPeriod
import kotlinx.coroutines.launch

@Composable
fun OverviewScreen(
    vm: AppViewModel,
    onMenu: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenReports: () -> Unit,
    /** 应收应付汇总表格：点进去看应收应付明细 */
    onOpenArAp: () -> Unit,
    onDrillDown: (TxnListSpec) -> Unit
) {
    val stats by vm.periodStats.collectAsState()
    val kind by vm.periodKind.collectAsState()
    val anchor by vm.anchor.collectAsState()
    val rangeStart by vm.rangeStart.collectAsState()
    val rangeEnd by vm.rangeEnd.collectAsState()
    val catMode by vm.catMode.collectAsState()
    // 图表口径（F15）：0 支出 / 1 收入。下钻的 filter 也要跟着它走（F17），所以提到最外层
    val flowMode by vm.flowMode.collectAsState()
    val ledger by vm.currentLedger.collectAsState()
    val lend by vm.lendSummary.collectAsState()
    val budgetUsed by vm.budgetUsedCents.collectAsState()
    // v2.8.0 高级功能开关
    val advLend by vm.advLend.collectAsState()
    val advArAp by vm.advArAp.collectAsState()
    val advCustomBudget by vm.advCustomBudget.collectAsState()
    val categories by vm.categories.collectAsState()
    // 应收应付（N44）：下表按「剩余未收 / 未付」口径实时聚合，不计入任何收支与余额
    val arAps by vm.arAps.collectAsState()
    val arPending = arAps.filter { it.direction == ArApDirection.RECEIVABLE }
    val apPending = arAps.filter { it.direction == ArApDirection.PAYABLE }
    val remainingOf: (List<com.qingjizhang.app.data.ArAp>) -> Long = { list ->
        list.sumOf { (it.amountCents - it.settledCents).coerceAtLeast(0L) }
    }
    // kind == 3 时显示自定义区间（含首尾两天）
    val periodLabel =
        if (kind == 3) DateFmt.isoRange(rangeStart, rangeEnd) else DateFmt.periodLabel(kind, anchor)

    var showRange by remember { mutableStateOf(false) }
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

    /**
     * 点图例下钻（F17）：直接用切片带的真实 id 过滤明细 ——
     * 分类 id（大类 / 小类都适用）或账户 id，不再按名字反查（名字反查只认一级分类，
     * 小类与账户会匹配不到，落到「当期全部账单」）。合并出来的「其他」桶（id = 0）保持看全部。
     * 口径 filter 跟随当前图表口径（1 支出 / 2 收入），否则收入分类点进去会是空的。
     */
    fun drillSlice(slice: DonutSlice) {
        val amountText = "¥" + Money.text(slice.valueCents)
        val filter = if (flowMode == 1) 2 else 1
        when {
            slice.id == 0L -> onDrillDown(
                TxnListSpecs.period(
                    kind, anchor, filter,
                    title = "${slice.label} · 明细",
                    rangeStart = rangeStart,
                    rangeEnd = rangeEnd
                )
            )
            slice.isAccount -> onDrillDown(
                TxnListSpecs.account(
                    kind, anchor, slice.id, slice.label, amountText, rangeStart, rangeEnd, filter
                )
            )
            else -> onDrillDown(
                TxnListSpecs.category(
                    kind, anchor, slice.id, slice.label, amountText, rangeStart, rangeEnd, filter
                )
            )
        }
    }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // 顶栏固定不随内容滚动；长按标题回到顶部
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "总览",
            onMenu = onMenu,
            onTitleLongPress = { scope.launch { scrollState.animateScrollTo(0) } },
            actions = {
                IconAction(Icons.Rounded.FilterList, { showRange = true })
                IconAction(Icons.Rounded.Insights, onOpenReports)
                IconAction(Icons.Rounded.CalendarMonth, onOpenCalendar)
            }
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                // P1：底栏改成悬浮胶囊后不再占位，页面自己留出底部空间，
                // 内容滚动时仍会从胶囊底下穿过（毛玻璃取样）
                .padding(bottom = 96.dp)
        ) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            // 周期选择
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CardWhite,
                    modifier = Modifier.height(38.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconAction(Icons.Rounded.ChevronLeft, { vm.shiftPeriod(-1) }, tint = Sub2, size = 18.dp)
                        Text(
                            periodLabel,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                        IconAction(Icons.Rounded.ChevronRight, { vm.shiftPeriod(1) }, tint = Sub2, size = 18.dp)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (kind != 1 || anchor.monthValue != java.time.LocalDate.now().monthValue) {
                    Text(
                        "回到本月",
                        fontSize = 12.sp,
                        color = Primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { vm.resetPeriod() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // 日 / 月 / 年 / 自定义
            SegTabs(listOf("按日", "按月", "按年", "自定义"), if (kind == 3) 3 else kind) {
                if (it == 3) showRange = true else vm.setPeriodKind(it)
            }
            Spacer(Modifier.height(12.dp))

            // 数字带（v2.7.1）：把原来的「收支对比卡 + 本月预算卡」压成一条，
            // 内容一个不少（三格数字都能点进明细、预算进度与设置入口都在），但只占一行的高度
            val budget = ledger?.monthlyBudgetCents ?: 0L
            CardBox {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        listOf(
                            Triple("收入", stats.incomeCents, Income),
                            Triple("支出", stats.expenseCents, Expense),
                            Triple("结余", stats.balanceCents, Primary)
                        ).forEachIndexed { i, (label, cents, color) ->
                            if (i > 0) Spacer(Modifier.width(8.dp))
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable {
                                        onDrillDown(
                                            TxnListSpecs.period(
                                                kind, anchor,
                                                when (i) {
                                                    0 -> 2
                                                    1 -> 1
                                                    else -> 0
                                                },
                                                rangeStart = rangeStart,
                                                rangeEnd = rangeEnd
                                            )
                                        )
                                    }
                            ) {
                                Text(label, fontSize = 11.sp, color = Sub)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    Money.withSymbol(cents),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = color,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (budget > 0L) {
                        val pct = ((budgetUsed * 100) / budget).toInt()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDrillDown(
                                        TxnListSpecs.period(
                                            kind, anchor, 1,
                                            title = "本月支出 · 明细",
                                            rangeStart = rangeStart,
                                            rangeEnd = rangeEnd
                                        )
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "预算 " + pct + "% · 剩 " + Money.withSymbol((budget - budgetUsed).coerceAtLeast(0L)),
                                fontSize = 10.5.sp,
                                color = Muted2
                            )
                            Spacer(Modifier.weight(1f))
                            Text("看本月支出 ›", fontSize = 10.5.sp, color = Primary)
                        }
                        Spacer(Modifier.height(5.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Track)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth((pct / 100f).coerceIn(0.01f, 1f))
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (budgetUsed > budget) Expense else Primary)
                            )
                        }
                    } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.setBudget(500000L) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("还没设预算，点这里快速设为 ¥5,000", fontSize = 10.5.sp, color = Primary)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Rounded.KeyboardArrowRight, null, tint = Chevron, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }

            // 图表口径切换（F15）：收入 / 支出，管住下面的分类饼图与趋势折线两张图
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("图表", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.weight(1f))
            }
            CardBox {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    listOf("支出" to 0, "收入" to 1).forEach { (label, mode) ->
                        val on = flowMode == mode
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(11.dp))
                                .clickable { vm.setFlowMode(mode) },
                            shape = RoundedCornerShape(11.dp),
                            color = if (on) (if (mode == 1) Income else Expense) else FieldBg
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(vertical = 9.dp),
                                textAlign = TextAlign.Center,
                                fontSize = 13.5.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else Sub2
                            )
                        }
                        if (mode == 0) Spacer(Modifier.width(8.dp))
                    }
                }
            }

            // 分类占比（点图例下钻）
            SectionTitle("${stats.flowLabel}分类", trailing = "点分类看明细 ›")
            CardBox {
                Column(Modifier.padding(14.dp)) {
                    SegSmall(listOf("大类", "小类", "账户"), catMode) { vm.setCatMode(it) }
                    Spacer(Modifier.height(16.dp))
                    if (stats.slices.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("这段时间没有${stats.flowLabel}记录", fontSize = 13.sp, color = Sub)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DonutChart(
                                slices = stats.slices,
                                totalCents = stats.flowCents,
                                centerLabel = "总${stats.flowLabel}"
                            )
                            Spacer(Modifier.width(16.dp))
                            LegendList(
                                stats.slices,
                                stats.flowCents,
                                Modifier.weight(1f),
                                onSliceClick = { drillSlice(it) }
                            )
                        }
                    }
                }
            }

            // 趋势（点图表看明细）；下钻口径跟着当前图表口径走
            SectionTitle(stats.trendTitle, trailing = "点图表看明细 ›")
            CardBox(Modifier.clickable {
                onDrillDown(
                    TxnListSpecs.period(
                        kind, anchor, if (flowMode == 1) 2 else 1,
                        title = stats.trendTitle + " · 明细",
                        rangeStart = rangeStart,
                        rangeEnd = rangeEnd
                    )
                )
            }) {
                Column {
                    TrendChart(stats.trendValues)
                    XAxis(stats.trendLabels)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Line)
                    )
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (flowMode == 1) Income else Expense)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(stats.legend, fontSize = 11.5.sp, color = Sub2)
                    }
                }
            }

            // 自定义预算（N30）：与月度预算并存；没有自定义预算时不显示、不占空间
            if (advCustomBudget) CustomBudgetOverview(vm)

            // 借贷汇总（点任意一格看明细）
            if (advLend) {
            SectionTitle("资产与借贷", trailing = "点任意一格看明细 ›")
            CardBox {
                Row(Modifier.padding(14.dp)) {
                    LendCell("待收回", lend.first, Expense, Modifier.weight(1f)) {
                        onDrillDown(TxnListSpecs.lend(kind, anchor, rangeStart, rangeEnd))
                    }
                    Spacer(Modifier.width(10.dp))
                    LendCell("待归还", lend.second, Income, Modifier.weight(1f)) {
                        onDrillDown(TxnListSpecs.lend(kind, anchor, rangeStart, rangeEnd))
                    }
                    Spacer(Modifier.width(10.dp))
                    LendCell("净额", lend.first - lend.second, Primary, Modifier.weight(1f)) {
                        onDrillDown(TxnListSpecs.lend(kind, anchor, rangeStart, rangeEnd))
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "待收回 = 借出未收回，待归还 = 借入未还；都不计入收支与预算",
                fontSize = 10.5.sp,
                color = Muted2,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            }

            // 应收应付总款表格（N44）：点表格进应收应付明细，结算后的账单才计入余额
            if (advArAp) {
            Spacer(Modifier.height(2.dp))
            CardBox(Modifier.clickable { onOpenArAp() }) {
                ArApTableRow("应收款（待收）", arPending.size, remainingOf(arPending), Income)
                RowDivider()
                ArApTableRow("应付款（待付）", apPending.size, remainingOf(apPending), Expense)
                RowDivider()
                ArApTableRow(
                    "净额",
                    null,
                    remainingOf(arPending) - remainingOf(apPending),
                    Ink
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "应收应付只是备忘：不计入余额，收付后才生成账单",
                fontSize = 10.5.sp,
                color = Muted2,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            }
            Spacer(Modifier.height(100.dp))
        }
        }
    }
}

/** 应收应付总款表格的一行：项目 + 笔数 + 金额（金额按待收 / 待付口径） */
@Composable
private fun ArApTableRow(
    label: String,
    count: Int?,
    cents: Long,
    color: Color
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.5.sp, color = Ink)
        Spacer(Modifier.weight(1f))
        if (count != null) {
            Text("$count 笔", fontSize = 12.sp, color = Sub2)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            "¥" + Money.text(cents),
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun LendCell(
    label: String,
    cents: Long,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Column(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(FieldBgAlt)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 11.dp)
    ) {
        Text(label, fontSize = 11.5.sp, color = Sub, maxLines = 1)
        Spacer(Modifier.height(5.dp))
        Text(
            "¥" + Money.text(cents),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1
        )
    }
}

// ---------------- 自定义时间跨度（N5） ----------------

/** 自定义区间选择：快捷区间 + 起止日期，确认后总览的统计与下钻都按这个区间 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RangePickerDialog(
    initialStart: java.time.LocalDate,
    initialEnd: java.time.LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (java.time.LocalDate, java.time.LocalDate) -> Unit
) {
    var start by remember { mutableStateOf(initialStart) }
    var end by remember { mutableStateOf(initialEnd) }
    var picking by remember { mutableIntStateOf(0) } // 0 关闭 1 选开始 2 选结束

    val today = java.time.LocalDate.now()
    val quarterStart = java.time.LocalDate.of(today.year, ((today.monthValue - 1) / 3) * 3 + 1, 1)
    val presets = listOf(
        "近 7 天" to (today.minusDays(6) to today),
        "近 30 天" to (today.minusDays(29) to today),
        "本季度" to (quarterStart to today),
        "今年" to (java.time.LocalDate.of(today.year, 1, 1) to today)
    )
    val dayCount = java.time.temporal.ChronoUnit.DAYS.between(
        if (start.isAfter(end)) end else start,
        if (start.isAfter(end)) start else end
    ) + 1

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        shape = RoundedCornerShape(22.dp),
        title = {
            Text("自定义时间跨度", fontSize = 16.5.sp, fontWeight = FontWeight.Bold, color = Ink)
        },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (label, range) ->
                        Text(
                            label,
                            fontSize = 12.5.sp,
                            color = Primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(PrimaryBg)
                                .clickable {
                                    start = range.first
                                    end = range.second
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                RangeRow("开始日期", start) { picking = 1 }
                Spacer(Modifier.height(8.dp))
                RangeRow("结束日期", end) { picking = 2 }
                Spacer(Modifier.height(10.dp))
                Text(
                    "统计与下钻都按这个区间，含首尾两天，共 $dayCount 天",
                    fontSize = 11.5.sp,
                    color = Sub
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(start, end) }) {
                Text("确定", color = Primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Sub) }
        }
    )

    if (picking != 0) {
        OneDatePickerDialog(
            initial = if (picking == 1) start else end,
            onDismiss = { picking = 0 },
            onPick = { d ->
                if (picking == 1) start = d else end = d
                picking = 0
            }
        )
    }
}

@Composable
private fun RangeRow(label: String, date: java.time.LocalDate, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(FieldBgAlt)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = Sub)
        Spacer(Modifier.weight(1f))
        Text(
            DateFmt.isoDate(DateFmt.toMillis(date)),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink
        )
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.CalendarMonth, null, tint = Primary, modifier = Modifier.size(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OneDatePickerDialog(
    initial: java.time.LocalDate,
    onDismiss: () -> Unit,
    onPick: (java.time.LocalDate) -> Unit
) {
    val zone = java.time.ZoneId.systemDefault()
    val state = rememberDatePickerState(initialSelectedDateMillis = DateFmt.toMillis(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = DatePickerDefaults.colors(containerColor = CardWhite),
        confirmButton = {
            TextButton(onClick = {
                val d = state.selectedDateMillis
                    ?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                    ?: initial
                onPick(d)
            }) {
                Text("确定", color = Primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Sub) }
        }
    ) {
        DatePicker(
            state = state,
            showModeToggle = false,
            colors = DatePickerDefaults.colors(
                containerColor = CardWhite,
                selectedDayContainerColor = Primary,
                todayDateBorderColor = Primary,
                todayContentColor = Primary
            )
        )
    }
}
