package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.*
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.accountMonths
import com.qingjizhang.app.vm.balanceOfAccount
import com.qingjizhang.app.vm.deleteTxn

/**
 * 账户详情：先逐月流水总览，点某个月才展开该月的每一笔。
 * 不再展示「初始余额」——余额由「设置账户余额」维护并留痕在「漏记款」账单里。
 */
@Composable
fun AccountMonthlyScreen(
    vm: AppViewModel,
    accountId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenTxn: (Long) -> Unit
) {
    val accounts by vm.accounts.collectAsState()
    val allTxns by vm.txns.collectAsState()
    val categories by vm.categories.collectAsState()
    val account = accounts.firstOrNull { it.id == accountId }

    var expanded by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Txn?>(null) }
    var editing by remember { mutableStateOf<Txn?>(null) }

    val catById = remember(categories) { categories.associateBy { it.id } }
    val accById = remember(accounts) { accounts.associateBy { it.id } }
    // N38：标签名（txnId -> 标签），列表副标题里展示
    val tagMap by vm.txnTagMap.collectAsState()
    // F20：左滑展开项由列表托管，滚动时自动收起
    val listState = rememberLazyListState()
    val swipe = rememberSwipeOpenState(listState.isScrollInProgress)

    val months = remember(account, allTxns) {
        if (account == null) emptyList() else vm.accountMonths(account)
    }
    LaunchedEffect(months) {
        if (expanded.isEmpty() && months.isNotEmpty()) expanded = months.first().monthKey
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
    editing?.let { t ->
        AddEntrySheet(vm = vm, request = AddSheetRequest(editing = t), onDismiss = { editing = null })
    }

    if (account == null) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar("账户", onBack)
            EmptyState(Icons.Rounded.SearchOff, "这个账户已经不存在了")
        }
        return
    }

    val totalIncome = months.sumOf { it.incomeCents }
    val totalExpense = months.sumOf { it.expenseCents }
    val totalCount = months.sumOf { it.txns.size }
    val balance = vm.balanceOfAccount(account)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            BackTopBar(
                title = account.name,
                onBack = onBack,
                actions = {
                    IconAction(Icons.Rounded.Edit, onEdit, tint = Primary)
                }
            )
        }
        item {
            Column(Modifier.padding(horizontal = 14.dp)) {
                CardBox {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val badge = accountBadge(account.type, account.name)
                            if (badge != null) TextBadge(badge, accountColor(account.type), size = 22.dp, radius = 7.dp)
                            else IconBadge(accountIcon(account.type), accountColor(account.type), size = 22.dp, iconSize = 14.dp, radius = 7.dp)
                            Spacer(Modifier.width(7.dp))
                            Text(account.name, fontSize = 12.5.sp, color = Sub, fontWeight = FontWeight.SemiBold)
                            if (account.note.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    account.note,
                                    fontSize = 11.sp,
                                    color = Sub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        AmountText(balance, fontSize = 27.sp, color = Ink)
                        Spacer(Modifier.height(13.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Line)
                        )
                        Spacer(Modifier.height(12.dp))
                        Row {
                            HeadCell("累计收入", "+" + Money.text(totalIncome), Income, Modifier.weight(1f))
                            HeadCell("累计支出", "-" + Money.text(totalExpense), Expense, Modifier.weight(1f))
                            HeadCell("账单笔数", "$totalCount 笔", Ink, Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (months.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.ReceiptLong,
                    title = "这个账户还没有账单",
                    hint = "记一笔时选中这个账户就会出现在这里"
                )
            }
        } else {
            item {
                SectionTitle("逐月流水", trailing = "点月份展开每一笔")
            }
            items(months, key = { it.monthKey }) { m ->
                Column(Modifier.padding(horizontal = 14.dp)) {
                    CardBox(radius = 14.dp) {
                        Column {
                            MonthRow(
                                monthKey = m.monthKey,
                                income = m.incomeCents,
                                expense = m.expenseCents,
                                net = m.netCents,
                                endBalance = m.endBalanceCents,
                                opened = expanded == m.monthKey,
                                onClick = {
                                    expanded = if (expanded == m.monthKey) "" else m.monthKey
                                }
                            )
                            if (expanded == m.monthKey) {
                                // 展开哪个月就只为那个月分组排序，并缓存住（N16）
                                val byDay = remember(m.monthKey) {
                                    m.txns.groupBy { DateFmt.dateOf(it.dateMillis) }
                                        .toList()
                                        .sortedByDescending { it.first }
                                }
                                byDay.forEach { (day, list) ->
                                    DayHeader(day, list)
                                    list.forEach { t ->
                                        Box(Modifier.padding(bottom = 6.dp)) {
                                            SwipeRow(
                                                onEdit = { editing = t },
                                                onDelete = { pendingDelete = t },
                                                onTap = { onOpenTxn(t.id) },
                                                expanded = swipe.isOpen(t.id),
                                                anyExpanded = swipe.id != null,
                                                onExpand = { swipe.setOpen(t.id, true) },
                                                onCollapse = { swipe.close() }
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
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun HeadCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = Sub)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

@Composable
private fun MonthRow(
    monthKey: String,
    income: Long,
    expense: Long,
    net: Long,
    endBalance: Long,
    opened: Boolean,
    onClick: () -> Unit
) {
    val parts = monthKey.split("-")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(54.dp)) {
            Text(parts.getOrNull(0) ?: "", fontSize = 10.5.sp, color = Sub)
            Text(
                (parts.getOrNull(1) ?: "").trimStart('0') + " 月",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "收 " + Money.text(income) + " · 支 " + Money.text(expense),
                fontSize = 11.5.sp,
                color = Sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "净 " + (if (net > 0) "+" else "") + Money.text(net),
                fontSize = 10.5.sp,
                color = if (net < 0) Expense else Primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                Money.text(endBalance),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (endBalance < 0) Expense else Ink
            )
            Spacer(Modifier.height(3.dp))
            Text("月末余额", fontSize = 10.sp, color = Sub)
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            if (opened) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Sub,
            modifier = Modifier.size(15.dp)
        )
    }
}
