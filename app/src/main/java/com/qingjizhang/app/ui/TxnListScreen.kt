package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.*
import com.qingjizhang.app.data.Direction
import com.qingjizhang.app.data.Repository
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.TxnListSpec
import com.qingjizhang.app.vm.deleteTxn

/**
 * 通用账单明细页：首页「更多明细」、总览的收支 / 分类 / 借贷下钻都用它。
 */
@Composable
fun TxnListScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenTxn: (Long) -> Unit
) {
    val spec by vm.txnListSpec.collectAsState()
    val txns by vm.txns.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()

    var filter by remember(spec) { mutableStateOf(spec.filter) }
    // N36：排序状态绑定到当前 spec（= 当前明细的筛选范围）。spec 一变就自动回到默认「时间最新优先」，
    // 所以从别的报表 / 别的下钻进来不会残留上一次的排序。
    var sort by remember(spec) { mutableStateOf(TxnSort.TIME_DESC) }
    var pendingDelete by remember { mutableStateOf<Txn?>(null) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    // N28：长按账单进入多选
    val multi = remember { TxnMultiSelectState() }
    // F20：滚动状态提到变量，交给左滑行做「一开始滚动就自动收起」的宿主
    val scrollState = rememberScrollState()
    val swipe = rememberSwipeOpenState(scrollState.isScrollInProgress)

    val catById = remember(categories) { categories.associateBy { it.id } }
    val accById = remember(accounts) { accounts.associateBy { it.id } }
    // N38：标签名（txnId -> 标签），列表副标题里展示
    val tagMap by vm.txnTagMap.collectAsState()

    // F20：切换筛选 / 排序 / 明细范围后，原先展开的那一行可能已经不在列表里了，
    // 统一收起，避免留下一个「点哪里都只会收起」的幽灵展开态。
    LaunchedEffect(spec, filter, sort) { swipe.close() }

    if (pendingDelete != null) {
        val t = pendingDelete!!
        TxnDeleteDialog(
            summary = DateFmt.isoDate(t.dateMillis) + " · " + Money.withSymbol(t.amountCents),
            onConfirm = { keep ->
                vm.deleteTxn(t, keepBalance = keep)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
    editing?.let { t ->
        AddEntrySheet(vm = vm, request = AddSheetRequest(editing = t), onDismiss = { editing = null })
    }

    // 时间范围 + 分类 + 账户 + 借贷 + 标签 过滤（F17：下钻必须带上被点对象的 id，不再落到「当期全部」）
    // v2.8.3：谓词抽成纯函数 matchesSpec，逻辑由 TxnListSpecFilterTest 单测守住
    // （曾经的 bug：通过条件写成 spec.categoryId != 0L，带分类 id 时恒真不过滤、
    //  不带分类 id 时反而只放行无分类/一级分类的账单，总览下钻的支出账单整段消失）
    val scoped = remember(txns, spec, tagMap) {
        txns.filter { matchesSpec(it, spec, catById, tagMap) }
    }
    val filtered = remember(scoped, filter) {
        scoped.filter {
            when (filter) {
                1 -> it.type == TxnType.EXPENSE
                2 -> it.type == TxnType.INCOME
                3 -> it.type == TxnType.TRANSFER
                else -> true
            }
        }
    }

    // N20：不计入净资产的账户不参与统计口径 —— 摘要与日表头按账户范围过滤，下面的流水列表照常展示
    val statsExcluded by vm.statsExcludedAccountIds.collectAsState()
    val countedScoped = remember(scoped, statsExcluded) {
        scoped.filter { Repository.inStatsAccountScope(it, statsExcluded) }
    }

    val income = countedScoped.filter { Repository.countsInStats(it) && it.type == TxnType.INCOME }
        .sumOf { Repository.effectiveCents(it) }
    val expense = countedScoped.filter { Repository.countsInStats(it) && it.type == TxnType.EXPENSE }
        .sumOf { Repository.effectiveCents(it) }
    // 借贷页的汇总改成「待收回 / 待归还」，已扣除收回 / 还出的部分。
    // F16：借贷汇总独立于「不计入净资产」口径，所以用完整 scoped（与下面列出的流水同源），
    // 否则会出现「列表里有借贷、汇总却是 0」的矛盾
    val lendTotals = Repository.lendTotals(scoped.filter { it.type == TxnType.LEND_BORROW })
    val lendOut = lendTotals.first
    val lendIn = lendTotals.second
    // 列表里每行都要显示未结金额，这里一次性算好，避免逐行全表过滤
    val lendOutstanding = remember(txns) { Repository.outstandingMap(txns) }

    // 分类下钻时的二级分类小计
    val subSummary: List<Triple<String, Long, Int>> = remember(countedScoped, spec) {
        if (spec.categoryId == 0L) emptyList()
        else countedScoped.filter { it.type == TxnType.EXPENSE }
            .groupBy { catById[it.categoryId]?.name ?: "未分类" }
            .map { (name, list) -> Triple(name, list.sumOf { it.amountCents }, list.size) }
            .sortedByDescending { it.second }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            // F20：用提到变量的 scrollState，左滑状态才能感知「列表正在滚动」
            .verticalScroll(scrollState)
    ) {
        BackTopBar(spec.title, onBack)
        Column(Modifier.padding(horizontal = 14.dp)) {

            // 顶部摘要
            CardBox {
                Column(Modifier.padding(16.dp)) {
                    Text(spec.subtitle, fontSize = 11.5.sp, color = Sub)
                    Spacer(Modifier.height(7.dp))
                    if (spec.lendOnly) {
                        AmountText(lendOut - lendIn, fontSize = 27.sp, color = Ink)
                    } else {
                        AmountText(income - expense, fontSize = 27.sp, color = Ink)
                    }
                    Spacer(Modifier.height(13.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    Spacer(Modifier.height(12.dp))
                    Row {
                        if (spec.lendOnly) {
                            SummaryCell("待收回", Money.text(lendOut), Expense, Modifier.weight(1f))
                            SummaryCell("待归还", Money.text(lendIn), Income, Modifier.weight(1f))
                            SummaryCell("账单笔数", "${countedScoped.size} 笔", Ink, Modifier.weight(1f))
                        } else {
                            SummaryCell("收入", "+" + Money.text(income), Income, Modifier.weight(1f))
                            SummaryCell("支出", "-" + Money.text(expense), Expense, Modifier.weight(1f))
                            SummaryCell("账单笔数", "${countedScoped.size} 笔", Ink, Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 筛选
            if (!spec.lendOnly) {
                SegSmall(listOf("全部", "支出", "收入", "互转"), filter) { filter = it }
                Spacer(Modifier.height(4.dp))
            }

            // 二级分类小计
            if (subSummary.isNotEmpty()) {
                SectionTitle("二级分类小计", trailing = "共 ${subSummary.size} 项")
                CardBox {
                    Column {
                        subSummary.forEachIndexed { index, (name, amount, count) ->
                            val p = if (expense > 0) amount * 100.0 / expense else 0.0
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(catStyle(name).color)
                                )
                                Spacer(Modifier.width(9.dp))
                                Text(name, fontSize = 13.sp, color = Sub2, modifier = Modifier.weight(1f))
                                Text("$count 笔", fontSize = 11.sp, color = Sub)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    String.format(java.util.Locale.CHINA, "%.1f%%", p),
                                    fontSize = 11.sp,
                                    color = Sub,
                                    modifier = Modifier.width(42.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    Money.text(amount),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Ink
                                )
                            }
                            if (index != subSummary.lastIndex) RowDivider()
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // N36：排序入口和列表标题同一行，按钮上直接显示当前排序状态
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("全部账单", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.weight(1f))
                TxnSortChip(current = sort, onPick = { sort = it })
            }
            // 金额排序的状态标识：说明已经改成平铺、且是跨天比较
            if (sort.flat) {
                Text(
                    "按金额排序 · 跨天比较，已取消按天分组",
                    fontSize = 11.sp,
                    color = Sub,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
            }
            if (filtered.isEmpty()) {
                CardBox {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Rounded.ReceiptLong, null, tint = Chevron, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("这个条件下还没有账单", fontSize = 13.sp, color = Sub2)
                    }
                }
            } else {
                // N36：排序只作用在当前筛选结果 filtered 上（不碰 vm.txns / 全局数据）。
                // 排序与分组都只在「筛选结果或排序方式」变化时算一次，避免每次重组都重排一遍（N16）。
                val sorted = remember(filtered, sort) { sortTxns(filtered, sort) }
                // 时间排序保留按天分组：sorted 本身已按日期排好，groupBy 的首次出现顺序就是日期顺序，
                // 不必再排一次 key；金额排序按天分组没有意义（会退化成"每天各自排序"），直接平铺。
                val byDay = remember(sorted, sort) {
                    if (sort.flat) emptyList<Pair<java.time.LocalDate, List<Txn>>>()
                    else sorted.groupBy { DateFmt.dateOf(it.dateMillis) }.toList()
                }
                if (sort.flat) {
                    sorted.forEach { t ->
                        DetailTxnRow(
                            t = t,
                            multi = multi,
                            swipe = swipe,
                            catById = catById,
                            accById = accById,
                            lendOutstanding = lendOutstanding,
                            tagMap = tagMap,
                            onOpenTxn = onOpenTxn,
                            onEdit = { editing = it },
                            onDelete = { pendingDelete = it }
                        )
                    }
                } else {
                    byDay.forEach { (day, list) ->
                        DayHeader(day, list, statsExcluded)
                        list.forEach { t ->
                            DetailTxnRow(
                                t = t,
                                multi = multi,
                                swipe = swipe,
                                catById = catById,
                                accById = accById,
                                lendOutstanding = lendOutstanding,
                                tagMap = tagMap,
                                onOpenTxn = onOpenTxn,
                                onEdit = { editing = it },
                                onDelete = { pendingDelete = it }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(if (multi.active) 190.dp else 30.dp))
        }
    }
        // N28：多选操作栏 + 批量弹窗（未进入多选时不渲染）
        TxnBatchHost(
            vm = vm,
            state = multi,
            allTxns = filtered,
            modifier = Modifier.align(Alignment.BottomCenter),
            // N55：借贷明细列表多选时提供「批量收回」
            lendMode = spec.lendOnly
        )
    }
}

/**
 * 明细页的一行账单：时间排序（按天分组）与金额排序（平铺）共用同一份渲染，
 * 保证两种排序下的行样式、长按多选、左滑行为完全一致。
 */
@Composable
private fun DetailTxnRow(
    t: Txn,
    multi: TxnMultiSelectState,
    swipe: SwipeOpenState,
    catById: Map<Long, Category>,
    accById: Map<Long, Account>,
    lendOutstanding: Map<Long, Long>,
    tagMap: Map<Long, List<String>>,
    onOpenTxn: (Long) -> Unit,
    onEdit: (Txn) -> Unit,
    onDelete: (Txn) -> Unit
) {
    Box(Modifier.padding(bottom = 6.dp)) {
        TxnSelectableRow(
            state = multi,
            id = t.id,
            onTap = { onOpenTxn(t.id) },
            onLongPress = { multi.start(t.id) },
            onEdit = { onEdit(t) },
            onDelete = { onDelete(t) },
            // F20：左滑展开状态由本页统一托管（滚动 / 点别处 / 滑开别的行都会自动收起）
            swipeState = swipe
        ) {
            TxnRowContent(
                title = txnTitle(t, catById),
                subtitle = txnSubtitle(t, accById, tagMap[t.id].orEmpty()) +
                    (if (t.type == TxnType.LEND_BORROW)
                        " · " + lendStatusText(lendOutstanding[t.id] ?: 0L, t)
                    else ""),
                amountCents = displayAmount(t),
                amountColor = amountColor(t),
                bottomRight = txnBottomRight(t, accById),
                tag = txnTag(t),
                badge = { TxnBadge(t, catById) }
            )
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = Sub)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

/** 供总览页构造下钻规格 */
object TxnListSpecs {
    /** N54：某个标签下的全部账单（时间不限，按标签聚合） */
    fun tag(name: String): TxnListSpec = TxnListSpec(
        title = "标签 · $name",
        subtitle = "含「$name」标签的全部账单，可继续按时间 / 排序查看",
        tagName = name
    )

    fun month(filter: Int, label: String): TxnListSpec {
        val now = java.time.LocalDate.now()
        val start = now.withDayOfMonth(1)
        return TxnListSpec(
            title = "本月账单",
            subtitle = DateFmt.isoDate(DateFmt.toMillis(start)) + " ~ " + DateFmt.isoDate(System.currentTimeMillis()) + " · " + label,
            filter = filter,
            monthStart = DateFmt.monthRange(now).first,
            monthEnd = DateFmt.monthRange(now).second
        )
    }

    /** 总览当前选中的 日 / 月 / 年 / 自定义区间 对应的时间范围 */
    private fun rangeOf(
        kind: Int,
        anchor: java.time.LocalDate,
        rs: java.time.LocalDate? = null,
        re: java.time.LocalDate? = null
    ): Pair<Long, Long> = when {
        kind == 3 && rs != null && re != null ->
            DateFmt.toMillis(rs) to DateFmt.toMillis(re.plusDays(1))
        kind == 0 -> DateFmt.dayRange(anchor)
        kind == 1 -> DateFmt.monthRange(anchor)
        else -> DateFmt.yearRange(anchor)
    }

    private fun rangeLabel(
        kind: Int,
        anchor: java.time.LocalDate,
        rs: java.time.LocalDate? = null,
        re: java.time.LocalDate? = null
    ): String {
        if (kind == 3 && rs != null && re != null) return DateFmt.isoRange(rs, re)
        val (s, e) = rangeOf(kind, anchor)
        val lastDay = DateFmt.isoDate(e - 1)
        return DateFmt.isoDate(s) + " ~ " + lastDay
    }

    /** 区间标题：自定义区间显示 "2026-01-01 ~ 2026-03-31"，其余沿用 日/月/年 文案 */
    private fun labelOf(
        kind: Int,
        anchor: java.time.LocalDate,
        rs: java.time.LocalDate? = null,
        re: java.time.LocalDate? = null
    ): String = if (kind == 3 && rs != null && re != null) DateFmt.isoRange(rs, re)
    else DateFmt.periodLabel(kind, anchor)

    /**
     * 总览下钻：跟随总览当前选中的 日 / 月 / 年（或自定义区间），不再固定成"系统当前月"。
     * 总览切到 2026-03，下钻就是 2026-03；切到 2025 年，下钻就是 2025 全年。
     */
    fun period(
        kind: Int,
        anchor: java.time.LocalDate,
        filter: Int,
        title: String? = null,
        rangeStart: java.time.LocalDate? = null,
        rangeEnd: java.time.LocalDate? = null
    ): TxnListSpec {
        val (s, e) = rangeOf(kind, anchor, rangeStart, rangeEnd)
        val label = labelOf(kind, anchor, rangeStart, rangeEnd)
        return TxnListSpec(
            title = title ?: "$label 账单",
            subtitle = rangeLabel(kind, anchor, rangeStart, rangeEnd) + " · 总览下钻",
            filter = filter,
            monthStart = s,
            monthEnd = e
        )
    }

    /**
     * 分类下钻（F17）：按分类 id 精确过滤，一级分类会连它下面的二级分类账单一起显示。
     * [filter] 跟随图表口径（1 支出 / 2 收入），否则收入分类点进去会空。
     */
    fun category(
        kind: Int,
        anchor: java.time.LocalDate,
        categoryId: Long,
        name: String,
        amountText: String,
        rangeStart: java.time.LocalDate? = null,
        rangeEnd: java.time.LocalDate? = null,
        filter: Int = 1
    ): TxnListSpec {
        val (s, e) = rangeOf(kind, anchor, rangeStart, rangeEnd)
        return TxnListSpec(
            title = "$name · 明细",
            subtitle = labelOf(kind, anchor, rangeStart, rangeEnd) + " · " +
                rangeLabel(kind, anchor, rangeStart, rangeEnd) +
                (if (amountText.isBlank()) "" else " · $amountText"),
            filter = filter,
            categoryId = categoryId,
            categoryName = name,
            monthStart = s,
            monthEnd = e
        )
    }

    /** 账户下钻（F17）：按账户 id 精确过滤，只显示该账户的账单 */
    fun account(
        kind: Int,
        anchor: java.time.LocalDate,
        accountId: Long,
        name: String,
        amountText: String,
        rangeStart: java.time.LocalDate? = null,
        rangeEnd: java.time.LocalDate? = null,
        filter: Int = 1
    ): TxnListSpec {
        val (s, e) = rangeOf(kind, anchor, rangeStart, rangeEnd)
        return TxnListSpec(
            title = "$name · 明细",
            subtitle = labelOf(kind, anchor, rangeStart, rangeEnd) + " · " +
                rangeLabel(kind, anchor, rangeStart, rangeEnd) + " · 账户" +
                (if (amountText.isBlank()) "" else " · $amountText"),
            filter = filter,
            accountId = accountId,
            monthStart = s,
            monthEnd = e
        )
    }

    /** 借贷明细：同样跟随总览当前周期 */
    fun lend(
        kind: Int,
        anchor: java.time.LocalDate,
        rangeStart: java.time.LocalDate? = null,
        rangeEnd: java.time.LocalDate? = null
    ): TxnListSpec {
        val (s, e) = rangeOf(kind, anchor, rangeStart, rangeEnd)
        return TxnListSpec(
            title = "借贷明细",
            subtitle = labelOf(kind, anchor, rangeStart, rangeEnd) + " · 待收回 = 借出未收回、待归还 = 借入未还；点某笔可记收回 / 还款",
            filter = 0,
            lendOnly = true,
            monthStart = s,
            monthEnd = e
        )
    }

    /** 某一天的全部账单（收支日历点日期进来） */
    fun day(date: java.time.LocalDate): TxnListSpec {
        val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]
        return TxnListSpec(
            title = DateFmt.isoDate(DateFmt.toMillis(date)) + " 账单",
            subtitle = "$week · 当天的收入 / 支出 / 互转 / 借贷",
            filter = 0,
            monthStart = DateFmt.toMillis(date),
            monthEnd = DateFmt.toMillis(date.plusDays(1))
        )
    }
}

/**
 * 下钻规格的过滤谓词（v2.8.3 从明细页内联逻辑抽出，便于单测）。
 *
 * 时间范围 + 分类（id 精确 / 一级连二级 / 分类名兜底）+ 账户 + 借贷 + 标签。
 *
 * ⚠️ 曾经的 bug（v2.8.2 及以前）：分类通过条件写成 `spec.categoryId != 0L || ...`，
 * 导致「带了分类 id 的下钻恒真不过滤」+「不带分类 id 的下钻反而只放行
 * 无分类或一级分类的账单」——总览预算 / 支出分类 / 逐月支出的下钻里，
 * 挂在二级分类下的支出账单整段消失（只剩互转）。改成下面的三段式：
 * 没带分类条件 → 全放行；带了 id → 按 id（含子类）；只带了名 → 按名兜底。
 */
internal fun matchesSpec(
    t: Txn,
    spec: TxnListSpec,
    catById: Map<Long, Category>,
    tagMap: Map<Long, List<String>>
): Boolean {
    val inRange = t.dateMillis >= spec.monthStart && t.dateMillis < spec.monthEnd
    if (!inRange) return false
    val noCategoryFilter = spec.categoryId == 0L && spec.categoryName.isEmpty()
    val byId = spec.categoryId != 0L &&
        (t.categoryId == spec.categoryId || catById[t.categoryId]?.parentId == spec.categoryId)
    val byName = spec.categoryName.isNotEmpty() &&
        (catById[t.categoryId]?.parentId?.takeIf { it != 0L }
            ?.let { catById[it]?.name } ?: catById[t.categoryId]?.name) == spec.categoryName
    if (!(noCategoryFilter || byId || byName)) return false
    if (spec.accountId != 0L && t.accountId != spec.accountId) return false
    if (spec.lendOnly && t.type != TxnType.LEND_BORROW) return false
    if (spec.tagName.isNotEmpty() && tagMap[t.id]?.contains(spec.tagName) != true) return false
    return true
}
