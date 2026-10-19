package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.*
import com.qingjizhang.app.data.Direction
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.KeySound
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.deleteTxn
import com.qingjizhang.app.vm.switchLedger
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 首页只显示最近几笔账单（v2.7.0） */
private const val HOME_RECENT_LIMIT = 5

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onMenu: () -> Unit,
    onAddEntry: () -> Unit,
    onOpenTxn: (Long) -> Unit,
    onOpenMonthTxns: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenRecurring: () -> Unit,
    /** N43 / 修正 1：右上角记账本图案 → 应收应付 */
    onOpenArAp: () -> Unit,
    /** F56：多选模式变化上报，宿主据此隐藏悬浮加号，避免遮住底部「完成」 */
    onMultiActive: (Boolean) -> Unit = {}
) {
    val ledger by vm.currentLedger.collectAsState()
    val txns by vm.txns.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val monthStats by vm.monthStats.collectAsState()
    val budgetUsed by vm.budgetUsedCents.collectAsState()
    val pending by vm.pendingRecurring.collectAsState()
    // N20：不计入净资产的账户不参与统计口径（列表照常展示，只是表头汇总不含它们）
    val statsExcluded by vm.statsExcludedAccountIds.collectAsState()
    // N38：账单标签（账单 id -> 标签名），列表副标题末尾展示
    val tagMap by vm.txnTagMap.collectAsState()
    // N39：空态里的「记一笔」按钮沿用「记一笔」音效
    val soundOn by vm.keySound.collectAsState()
    // P1-3：异常账单提示（疑似重复扣款 / 金额偏高），忽略过的不会再出现
    val anomalies by vm.anomalies.collectAsState()
    val keyDing = rememberKeySound(soundOn)

    val catById = remember(categories) { categories.associateBy { it.id } }
    val accById = remember(accounts) { accounts.associateBy { it.id } }

    var pendingDelete by remember { mutableStateOf<Txn?>(null) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    var showReceipt by remember { mutableStateOf(false) }
    var showLedgerSwitch by remember { mutableStateOf(false) }
    if (showLedgerSwitch) {
        LedgerSwitchSheet(vm = vm, onDismiss = { showLedgerSwitch = false })
    }
    if (showReceipt) {
        ReceiptSheet(vm = vm, onDismiss = { showReceipt = false })
    }
    if (editing != null) {
        AddEntrySheet(vm = vm, request = AddSheetRequest(editing = editing), onDismiss = { editing = null })
    }
    pendingDelete?.let { t ->
        TxnDeleteDialog(
            summary = DateFmt.isoDate(t.dateMillis) + " · " + Money.withSymbol(t.amountCents),
            onConfirm = { keep ->
                vm.deleteTxn(t, keepBalance = keep)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }

    // v2.7.0：首页只放最近 5 笔（原来把全部账单按日期全量铺开，越用越长）。
    // 想看更早的点底部「查看全部账单」进明细页。
    val recentTxns = remember(txns) {
        txns.sortedByDescending { it.dateMillis }.take(HOME_RECENT_LIMIT)
    }
    val groups = remember(recentTxns) {
        recentTxns.groupBy { DateFmt.dateOf(it.dateMillis) }
            .toList()
            .sortedByDescending { it.first }
    }

    // 顶栏固定在列表之外，不随滚动移动；长按标题回到顶部
    val listState = rememberLazyListState()
    val homeScope = rememberCoroutineScope()
    // N28：长按账单进入多选
    val multi = remember { TxnMultiSelectState() }
    // F56：多选时把状态告诉宿主（AppRoot），它会隐藏右下角的悬浮加号；
    // 离开首页时恢复，保证加号不会一直消失
    LaunchedEffect(multi.active) { onMultiActive(multi.active) }
    DisposableEffect(Unit) { onDispose { onMultiActive(false) } }
    // F20：左滑展开项由列表统一托管，滚动列表时自动收起，且同一时间只展开一行
    val swipe = rememberSwipeOpenState(listState.isScrollInProgress)

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
    TopBar(
        title = ledger?.name ?: "账本",
        onMenu = onMenu,
        caret = true,
        onTitleLongPress = { homeScope.launch { listState.animateScrollToItem(0) } },
        // 点左上角账本名 / ∨ 直接切换账本（抽屉里也能切）
        onTitleClick = { showLedgerSwitch = true },
        actions = {
            IconAction(Icons.Rounded.Search, onOpenSearch)
            // N43 / 修正 1：右上角记账本图案 → 应收应付
            IconAction(Icons.Rounded.MenuBook, onOpenArAp)
        }
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = if (multi.active) 200.dp else 140.dp)
    ) {
        // P1-3：异常账单横幅（最多 3 条，可逐条忽略；忽略后写在本地，不再提示）
        if (anomalies.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    CardBox {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(
                                    Icons.Rounded.ReportGmailerrorred,
                                    Amber,
                                    size = 30.dp,
                                    iconSize = 16.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "发现 ${anomalies.size} 条可能记错的账",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Ink
                                )
                            }
                            anomalies.forEach { f ->
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            f.title,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Ink,
                                            maxLines = 1
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(f.detail, fontSize = 11.sp, color = Sub, maxLines = 2)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "查看",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(9.dp))
                                            .clickable { onOpenTxn(f.txnIds.first()) }
                                            .padding(horizontal = 9.dp, vertical = 6.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "忽略",
                                        fontSize = 12.sp,
                                        color = Sub,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(9.dp))
                                            .clickable { vm.ignoreAnomaly(f.key) }
                                            .padding(horizontal = 9.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 14.dp)) {
                // 本月结余（长按 → 小票模式）
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(onLongPress = { showReceipt = true })
                        },
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Transparent
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(HeroGradient)
                    ) {
                        // 装饰圆
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 40.dp, y = (-50).dp)
                                .size(150.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.13f))
                        )
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("本月结余", fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.9f))
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "长按看小票",
                                    fontSize = 10.5.sp,
                                    color = Color.White.copy(alpha = 0.78f)
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            AmountText(
                                monthStats.first - monthStats.second,
                                color = Color.White,
                                fontSize = 34.sp
                            )
                            Spacer(Modifier.height(14.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color.White.copy(alpha = 0.26f))
                            )
                            Spacer(Modifier.height(11.dp))
                            Row {
                                Column {
                                    Text("本月收入", fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.85f))
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        "+" + Money.text(monthStats.first),
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Spacer(Modifier.width(30.dp))
                                Column {
                                    Text("本月支出", fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.85f))
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        "-" + Money.text(monthStats.second),
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                val budget = ledger?.monthlyBudgetCents ?: 0L
                                if (budget > 0) {
                                    Spacer(Modifier.width(30.dp))
                                    Column {
                                        Text("预算", fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.85f))
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            Money.text(budget),
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 预算进度（不含「不计入预算」的账单）
                val budget = ledger?.monthlyBudgetCents ?: 0L
                if (budget > 0L) {
                    Spacer(Modifier.height(11.dp))
                    CardBox { BudgetBar(used = budgetUsed, budget = budget) }
                }

                // 周期账单待生成
                if (pending > 0) {
                    Spacer(Modifier.height(11.dp))
                    TipBanner(
                        text = "本月有 $pending 笔周期账单待生成",
                        icon = Icons.Rounded.ErrorOutline,
                        bg = WarnBg,
                        fg = WarnFg,
                        action = {
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = WarnIcon,
                                modifier = Modifier.clickable { onOpenRecurring() }
                            ) {
                                Text(
                                    "一键入账",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                                )
                            }
                        }
                    )
                }
            }
        }

        if (groups.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.ReceiptLong,
                    title = "还没有账单",
                    hint = "点右下角 ＋ 记第一笔；也可以到「设置 → 数据」生成一份示例账本先看看",
                    action = {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Primary,
                            modifier = Modifier.clickable {
                                keyDing(KeySound.Scene.ENTER)
                                onAddEntry()
                            }
                        ) {
                            Text(
                                "记一笔",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp)
                            )
                        }
                    }
                )
            }
        } else {
            groups.forEach { (date, list) ->
                item(key = "h" + date.toString()) {
                    DayHeader(date, list, statsExcluded)
                }
                items(list, key = { it.id }) { t ->
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        TxnSelectableRow(
                            state = multi,
                            id = t.id,
                            onTap = { onOpenTxn(t.id) },
                            onLongPress = { multi.start(t.id) },
                            onEdit = { editing = t },
                            onDelete = { pendingDelete = t },
                            swipeState = swipe
                        ) {
                            TxnRowContent(
                                title = txnTitle(t, catById),
                                subtitle = txnSubtitle(t, accById, tagMap[t.id].orEmpty()),
                                amountCents = displayAmount(t),
                                amountColor = amountColor(t),
                                bottomRight = txnBottomRight(t, accById),
                                tag = txnTag(t),
                                badge = { TxnBadge(t, catById) }
                            )
                        }
                    }
                }
            }
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        Modifier.clickable { onOpenMonthTxns() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("更多明细（共 ${txns.size} 笔）", fontSize = 13.sp, color = Sub)
                        Icon(
                            Icons.Rounded.KeyboardArrowRight,
                            null,
                            tint = Sub,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
    }
        // N28：多选操作栏 + 批量弹窗（未进入多选时不渲染）
        TxnBatchHost(
            vm = vm,
            state = multi,
            allTxns = txns,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun BudgetBar(used: Long, budget: Long) {
    val p = (used.toFloat() / budget.coerceAtLeast(1L)).coerceIn(0f, 1f)
    val over = used > budget
    Column(Modifier.padding(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Flag, null, tint = Sub2, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("本月预算", fontSize = 12.5.sp, color = Sub2, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (over) "已超支" else "已用 " + (p * 100).toInt() + "%",
                fontSize = 12.sp,
                color = if (over) Expense else Sub
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(FieldBg)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(p)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (over) Expense else Primary)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("已用 ¥" + Money.text(used), fontSize = 11.5.sp, color = Sub)
            Text("预算 ¥" + Money.text(budget), fontSize = 11.5.sp, color = Sub)
            Text(
                (if (over) "超出 ¥" else "剩余 ¥") + Money.text(Math.abs(budget - used)),
                fontSize = 11.5.sp,
                color = if (over) Expense else Sub
            )
        }
    }
}

@Composable
internal fun DayHeader(date: LocalDate, list: List<Txn>, excludedAccountIds: Set<Long> = emptySet()) {
    // N20：「不计入净资产」的账户不参与统计口径，表头的收支汇总先按账户范围过滤（下面的流水列表照常展示）
    val counted = list.filter { Repository.inStatsAccountScope(it, excludedAccountIds) }
    val inc = counted.filter { it.type == TxnType.INCOME }.sumOf { Repository.effectiveCents(it) }
    val exp = counted.filter { it.type == TxnType.EXPENSE }.sumOf { Repository.effectiveCents(it) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PrimaryBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.CalendarMonth, null, tint = Primary, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(7.dp))
        Text(
            DateFmt.dayHeader(DateFmt.toMillis(date)),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Ink
        )
        Spacer(Modifier.weight(1f))
        Text("收 " + Money.text(inc), fontSize = 12.sp, color = Income, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(12.dp))
        Text("支 " + Money.text(exp), fontSize = 12.sp, color = Expense, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun TxnBadge(t: Txn, catById: Map<Long, Category>) {
    when (t.type) {
        TxnType.TRANSFER -> IconBadge(Icons.Rounded.SwapHoriz, Primary)
        TxnType.LEND_BORROW -> IconBadge(
            if (t.direction == Direction.LEND) Icons.Rounded.UploadFile else Icons.Rounded.Download,
            if (t.direction == Direction.LEND) Expense else Income
        )
        else -> {
            val cat = catById[t.categoryId]
            val parent = cat?.let { c -> if (c.parentId == 0L) c else catById[c.parentId] }
            val fallback = if (t.type == TxnType.INCOME) "其他收入" else "其他支出"
            val icon = categoryIcon(parent, fallback) ?: Icons.Rounded.Label
            IconBadge(icon, categoryColor(parent, fallback))
        }
    }
}

/** 账单上的统计口径标记（两个都勾时优先显示「不计收支」） */
internal fun txnTag(t: Txn): String? = when {
    t.excludeFromStats -> "不计收支"
    t.excludeFromBudget -> "不计预算"
    else -> null
}

internal fun txnTitle(t: Txn, catById: Map<Long, Category>): String = when (t.type) {
    // 互转的子类型（N26）直接当标题显示：还款 / 理财买入 / 理财赎回，普通互转仍是「账户互转」
    TxnType.TRANSFER -> when (t.transferKind) {
        TransferKind.REPAY -> "还款"
        TransferKind.INVEST_BUY -> "理财买入"
        TransferKind.INVEST_SELL -> "理财赎回"
        TransferKind.COMMON -> "账户互转"
        else -> t.transferKind
    }
    TxnType.LEND_BORROW -> lendTitle(t)
    else -> catById[t.categoryId]?.name
        ?: catById[catById[t.categoryId]?.parentId ?: 0L]?.name
        ?: if (t.type == TxnType.INCOME) "收入" else "支出"
}

internal fun txnSubtitle(
    t: Txn,
    accById: Map<Long, Account>,
    /** N38：这笔账单的标签名（列表统一从 vm.txnTagMap 取，默认空表示不展示标签） */
    tags: List<String> = emptyList()
): String {
    val time = DateFmt.friendly(t.dateMillis)
    val base = if (t.note.isNotBlank()) "$time · ${t.note}" else time
    // 有报销 / 退款时，把原价与退款额也写在副标题里（列表金额显示的是实计）
    val withRefund = if (Repository.hasRefund(t)) {
        base + " · 原价 ¥" + Money.text(t.amountCents) + " 退款 ¥" + Money.text(t.refundCents)
    } else base
    // 标签追加在最后（单行省略号已在列表里，最长只显示 3 个）
    val trail = Repository.tagTrail(tags)
    return if (trail.isEmpty()) withRefund else "$withRefund · $trail"
}

/**
 * 账户名占位（N35）：账户被删除但账单选择「保留」时，账单里的 accountId 仍然保留，
 * 这时查不到账户对象，统一显示「已删除账户」而不是空白，避免看起来像「凭空消失」。
 */
internal fun accountLabel(accById: Map<Long, Account>, id: Long): String = when {
    id == 0L -> ""
    else -> accById[id]?.name ?: "已删除账户"
}

internal fun txnBottomRight(t: Txn, accById: Map<Long, Account>): String = when (t.type) {
    TxnType.TRANSFER ->
        accountLabel(accById, t.accountId).ifBlank { "?" } + " → " +
            accountLabel(accById, t.transferToAccountId).ifBlank { "?" }
    else -> accountLabel(accById, t.accountId)
}

internal fun displayAmount(t: Txn): Long = when (t.type) {
    TxnType.EXPENSE -> -Repository.effectiveCents(t)
    TxnType.INCOME -> Repository.effectiveCents(t)
    TxnType.TRANSFER -> t.amountCents
    TxnType.LEND_BORROW -> if (t.direction == Direction.LEND) -t.amountCents else t.amountCents
}

// M10：账单金额颜色统一走 CatStyle.kt 的共享函数 amountColor(t)（不计入收支 → 灰色）。

/**
 * 账本切换（首页左上角账本名 / ∨）：选中即切换，首页账本名、结余与账单列表都会重新按新账本查，
 * 选择会持久化（重启后仍是这个账本）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerSwitchSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val ledgers by vm.ledgers.collectAsState()
    val currentId by vm.currentLedgerId.collectAsState()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CardWhite
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Text("切换账本", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text("各账本的账单、账户、分类与周期规则完全隔离", fontSize = 11.5.sp, color = Sub)
            Spacer(Modifier.height(10.dp))
            ledgers.forEach { l ->
                val on = l.id == currentId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (on) PrimaryBg else Color.Transparent)
                        .clickable {
                            vm.switchLedger(l.id)
                            close()
                        }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        l.name,
                        fontSize = 14.5.sp,
                        color = if (on) Primary else Sub2,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                    )
                    Spacer(Modifier.weight(1f))
                    if (on) {
                        Icon(Icons.Rounded.Check, null, tint = Primary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
