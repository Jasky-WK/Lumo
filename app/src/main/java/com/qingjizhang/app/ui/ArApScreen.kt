package com.qingjizhang.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.Account
import com.qingjizhang.app.data.ArAp
import com.qingjizhang.app.data.ArApDirection
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.deleteArAp

/**
 * 应收应付列表（N44 / N45 修正 2 / 修正 3）。
 *
 * 首页右上角「记账本」图案进入的就是这里（修正 1：临时预算规划已完全下线）。
 * - 顶部「应收款 / 应付款」两个标签切换；
 * - 列表按**月份分组**（组头可整组勾选、显示该组待收 / 待付合计）；
 * - 底部固定操作条：全选 + 已选合计 + 「收款 / 付款」按钮（多选后合并结算）；
 * - **点某一条记录 = 进入详情**（F53）：详情里可编辑、发起结算、小号胶囊销账；
 * - 结算完成后该记录从列表移除（修正 3），只在主页账单流保留生成的账单。
 */
@Composable
fun ArApScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onAddEntry: (ArApDirection) -> Unit,
    /** F53：编辑某条记录（打开记一笔界面） */
    onEditEntry: (ArAp) -> Unit
) {
    val arAps by vm.arAps.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val receivable = tab == 0
    val direction = if (receivable) ArApDirection.RECEIVABLE else ArApDirection.PAYABLE
    val verb = if (receivable) "收款" else "付款"

    // 多选：保存已勾选的记录 id（同方向，切标签时清空）
    val selected = remember { mutableStateListOf<Long>() }
    LaunchedEffect(tab) { selected.clear() }

    // 结算目标：单条点击 = 一条；底部「收款 / 付款」= 已勾选的若干条
    var settleTarget by remember { mutableStateOf<List<ArAp>?>(null) }
    var deleteTarget by remember { mutableStateOf<ArAp?>(null) }
    // F53：详情页只存 id，编辑 / 结算后自动拿到最新那条记录
    var detailId by remember { mutableStateOf<Long?>(null) }

    val list = arAps.filter { it.direction == direction }
    val catById = remember(categories) { categories.associateBy { it.id } }
    val accById = remember(accounts) { accounts.associateBy { it.id } }
    val selectedItems = list.filter { it.id in selected }
    val selectedTotal = selectedItems.sumOf { it.remainCents }

    if (deleteTarget != null) {
        val item = deleteTarget!!
        ConfirmDialog(
            title = "删除这条" + (if (receivable) "应收款" else "应付款") + "？",
            text = "只删除这条备忘记录，不影响任何账单与余额。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                vm.deleteArAp(item)
                selected.remove(item.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }

    // 记录被结算 / 销账删掉后自动退回列表
    val detailItem = detailId?.let { id -> arAps.firstOrNull { it.id == id } }
    LaunchedEffect(detailId, detailItem) {
        if (detailId != null && detailItem == null) detailId = null
    }

    // 结算界面优先于详情：详情里点「收款 / 付款」时要在它上面盖一层，关闭后自然回到详情
    settleTarget?.let { target ->
        ArApSettleScreen(
            vm = vm,
            items = target,
            accounts = accounts,
            onBack = { settleTarget = null },
            onDone = {
                selected.removeAll(target.map { it.id }.toSet())
                settleTarget = null
            }
        )
        return
    }

    // F53：详情页（编辑金额/账户/类型、进收款付款、小号销账胶囊）
    detailItem?.let { detail ->
        ArApDetailScreen(
            vm = vm,
            item = detail,
            categoryName = catById[detail.categoryId]?.name,
            accountName = accById[detail.accountId]?.let { accountLabel(it) },
            onBack = { detailId = null },
            onEdit = { onEditEntry(detail) },
            onSettle = { settleTarget = listOf(detail) },
            onRemoved = { detailId = null }
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(
            title = "应收应付",
            onBack = onBack,
            actions = { IconAction(Icons.Rounded.Add, { onAddEntry(direction) }) }
        )
        // N48：应收应付总额（待收合计 / 待付合计）。与列表同一数据源（vm.arAps 实时聚合），
        // 新增记录自动加、结算删除后自动减，点一格还能直接切到对应标签
        val receivableList = arAps.filter { it.direction == ArApDirection.RECEIVABLE }
        val payableList = arAps.filter { it.direction == ArApDirection.PAYABLE }
        CardBox(modifier = Modifier.padding(horizontal = 14.dp)) {
            Row(
                Modifier.padding(vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TotalCell(
                    label = "应收款总额",
                    cents = receivableList.sumOf { it.remainCents },
                    count = receivableList.size,
                    color = Income,
                    modifier = Modifier.weight(1f)
                ) { tab = 0 }
                Box(
                    Modifier
                        .width(1.dp)
                        .height(30.dp)
                        .background(Line)
                )
                TotalCell(
                    label = "应付款总额",
                    cents = payableList.sumOf { it.remainCents },
                    count = payableList.size,
                    color = Expense,
                    modifier = Modifier.weight(1f)
                ) { tab = 1 }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.padding(horizontal = 14.dp)) {
            SegTabs(listOf("应收款", "应付款"), selectedIndex = tab, onSelect = { tab = it })
        }
        Spacer(Modifier.height(10.dp))

        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                EmptyState(
                    icon = Icons.Rounded.Inbox,
                    title = "还没有" + (if (receivable) "应收款" else "应付款"),
                    hint = "点右上角 ＋ 记一笔；结算后记录会自动移出这里"
                )
            }
        } else {
            // 按月份分组（列表已按记录时间倒序，分组顺序天然从新到旧）
            val groups = remember(list) {
                list.groupBy { DateFmt.monthKey(it.dateMillis) }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                groups.forEach { (month, monthItems) ->
                    item(key = "h-$month") {
                        MonthHeader(
                            month = month,
                            totalCents = monthItems.sumOf { it.remainCents },
                            checked = monthItems.all { it.id in selected },
                            onToggleAll = {
                                val ids = monthItems.map { it.id }
                                if (ids.all { it in selected }) selected.removeAll(ids.toSet())
                                else ids.forEach { if (it !in selected) selected.add(it) }
                            }
                        )
                    }
                    items(monthItems, key = { it.id }) { item ->
                        CardBox(radius = 14.dp) {
                            ArApRow(
                                item = item,
                                receivable = receivable,
                                title = catById[item.categoryId]?.name
                                    ?: item.counterparty.ifBlank {
                                        if (receivable) "应收款" else "应付款"
                                    },
                                accountName = accById[item.accountId]?.let { acc -> accountLabel(acc) } ?: "",
                                checked = item.id in selected,
                                onCheck = {
                                    if (item.id in selected) selected.remove(item.id)
                                    else selected.add(item.id)
                                },
                                onClick = {
                                    if (selected.isNotEmpty()) {
                                        if (item.id in selected) selected.remove(item.id)
                                        else selected.add(item.id)
                                    } else {
                                        // F53：点记录先进详情（详情里可编辑 / 结算 / 销账）
                                        detailId = item.id
                                    }
                                },
                                onLongClick = { deleteTarget = item }
                            )
                        }
                    }
                }
            }
            // 底部操作条（全选 / 合计 / 收款付款）
            Surface(color = CardWhite, shadowElevation = 8.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = list.isNotEmpty() && selected.size == list.size,
                        onCheckedChange = {
                            if (selected.size == list.size) selected.clear()
                            else {
                                selected.clear()
                                selected.addAll(list.map { it.id })
                            }
                        }
                    )
                    Text("全选", fontSize = 13.5.sp, color = Ink)
                    Spacer(Modifier.weight(1f))
                    AmountText(selectedTotal, color = if (receivable) Income else Expense, fontSize = 16.sp)
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { settleTarget = selectedItems },
                        enabled = selectedItems.isNotEmpty(),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text(verb, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** 账户展示名：类型 / 名称（与参考图一致） */
internal fun accountLabel(acc: Account): String =
    if (acc.type.isBlank()) acc.name else acc.type + "/" + acc.name

/** 剩余未收 / 未付金额（分批结算后只算剩下的部分） */
private val ArAp.remainCents: Long
    get() = (amountCents - settledCents).coerceAtLeast(0L)

/** N48：应收 / 应付总额单元格（待收 / 待付合计 + 笔数） */
@Composable
private fun TotalCell(
    label: String,
    cents: Long,
    count: Int,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 11.5.sp, color = Sub2)
        Spacer(Modifier.height(5.dp))
        AmountText(cents, color = color, fontSize = 17.sp)
        Spacer(Modifier.height(3.dp))
        Text("$count 笔", fontSize = 11.sp, color = Sub2)
    }
}

/** 月份分组头：整组勾选 + 月份 + 该组合计 */
@Composable
private fun MonthHeader(
    month: String,
    totalCents: Long,
    checked: Boolean,
    onToggleAll: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(FieldBg)
            .clickable { onToggleAll() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggleAll() })
        Text(month, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.weight(1f))
        AmountText(totalCents, fontSize = 14.sp, color = Ink)
    }
}

/** 单条应收 / 应付记录：分类名 + 记录时间/备注，右侧金额 + 账户 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArApRow(
    item: ArAp,
    receivable: Boolean,
    title: String,
    accountName: String,
    checked: Boolean,
    onCheck: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val accent = if (receivable) Income else Expense
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onCheck() })
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 12.dp)
        ) {
            Text(
                title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                DateFmt.isoDateTime(item.dateMillis) +
                    (if (item.note.isNotBlank()) " " + item.note else ""),
                fontSize = 11.5.sp,
                color = Sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            AmountText(item.remainCents, color = accent, fontSize = 15.5.sp)
            if (accountName.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(accountName, fontSize = 11.sp, color = Sub2, maxLines = 1)
            }
            if (item.settledCents > 0L) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "已" + (if (receivable) "收" else "付") + " " + Money.withSymbol(item.settledCents),
                    fontSize = 10.5.sp,
                    color = Orange
                )
            }
        }
    }
}
