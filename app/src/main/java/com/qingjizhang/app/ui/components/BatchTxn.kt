package com.qingjizhang.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.vector.ImageVector
import com.qingjizhang.app.data.Account
import com.qingjizhang.app.data.Category
import com.qingjizhang.app.data.Ledger
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.ui.theme.CardWhite
import com.qingjizhang.app.ui.theme.Chevron
import com.qingjizhang.app.ui.theme.Expense
import com.qingjizhang.app.ui.theme.FieldBg
import com.qingjizhang.app.ui.theme.FieldBgAlt
import com.qingjizhang.app.ui.theme.Income
import com.qingjizhang.app.ui.theme.Ink
import com.qingjizhang.app.ui.theme.Muted
import com.qingjizhang.app.ui.theme.Primary
import com.qingjizhang.app.ui.theme.PrimaryBg
import com.qingjizhang.app.ui.theme.Sub
import com.qingjizhang.app.ui.theme.Sub2
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.changeTxnsAccount
import com.qingjizhang.app.vm.changeTxnsCategory
import com.qingjizhang.app.vm.changeTxnsRefund
import com.qingjizhang.app.vm.changeTxnsStats
import com.qingjizhang.app.vm.deleteTxns
import com.qingjizhang.app.vm.moveTxnsToLedger
import com.qingjizhang.app.vm.settleLendsBatch

/**
 * 多选状态（N28）。
 * 勾选集合用 `Set<Long>`，行组件通过 [selected] 读取自己的选中态，
 * 配合行内 `derivedStateOf` 做到「只有选中态真的变了的那一行才重组」。
 */
@Stable
class TxnMultiSelectState {
    var active by mutableStateOf(false)
        private set

    /** 当前勾选的账单 id */
    val selected = mutableStateOf<Set<Long>>(emptySet())

    /** 长按进入多选并选中这一条 */
    fun start(id: Long) {
        active = true
        selected.value = selected.value + id
    }

    fun toggle(id: Long) {
        val s = selected.value
        selected.value = if (id in s) s - id else s + id
    }

    fun setAll(ids: Collection<Long>) {
        selected.value = ids.toSet()
    }

    fun clear() {
        selected.value = emptySet()
    }

    fun exit() {
        active = false
        selected.value = emptySet()
    }
}

/**
 * 账单行：非多选模式下就是原来的左滑行（支持长按进入多选）；
 * 多选模式下点按 = 勾选、行内显示选中标记，不再左滑、不再进详情。
 */
@Composable
fun TxnSelectableRow(
    state: TxnMultiSelectState,
    id: Long,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** 左滑展开状态（F20）：由宿主列表统一托管，滚动 / 点别处自动收起 */
    swipeState: SwipeOpenState? = null,
    content: @Composable () -> Unit
) {
    if (state.active) {
        // 把「是否选中」收敛成一个布尔：其它行勾选变化时，本行的布尔没变就不会重组
        val checked by remember(id) { derivedStateOf { id in state.selected.value } }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (checked) PrimaryBg else CardWhite)
                .clickable { state.toggle(id) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (checked) Primary else Chevron,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(21.dp)
            )
            Box(Modifier.weight(1f)) { content() }
        }
    } else {
        SwipeRow(
            onEdit = onEdit,
            onDelete = onDelete,
            modifier = modifier,
            expanded = swipeState?.isOpen(id) == true,
            anyExpanded = swipeState?.id != null,
            onExpand = { swipeState?.setOpen(id, true) },
            onCollapse = { swipeState?.close() },
            onTap = onTap,
            onLongPress = onLongPress
        ) { content() }
    }
}

/** 底部多选操作栏：全选 / 取消、退出，以及各项批量操作 */
@Composable
fun TxnBatchBar(
    selectedCount: Int,
    allSelected: Boolean,
    modifier: Modifier = Modifier,
    onToggleSelectAll: () -> Unit,
    onExit: () -> Unit,
    onDelete: () -> Unit,
    onMoveLedger: () -> Unit,
    onCategory: () -> Unit,
    onAccount: () -> Unit,
    onStats: () -> Unit,
    onRefund: () -> Unit,
    /** N55：借贷列表多选时额外提供「批量收回」；其它列表传 null 不显示 */
    onSettleLend: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CardWhite,
        shadowElevation = 14.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已选 $selectedCount 笔",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
                Spacer(Modifier.weight(1f))
                TextAction(if (allSelected) "取消全选" else "全选", onToggleSelectAll)
                Spacer(Modifier.width(6.dp))
                TextAction("退出", onExit)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BarChip("删除", Icons.Rounded.DeleteOutline, Expense, onDelete)
                // N55：借贷列表才有批量收回
                if (onSettleLend != null) {
                    BarChip("批量收回", Icons.Rounded.CallReceived, Income, onSettleLend)
                }
                BarChip("移动账本", Icons.Rounded.SwapHoriz, Primary, onMoveLedger)
                BarChip("修改分类", Icons.Rounded.Category, Primary, onCategory)
                BarChip("修改账户", Icons.Rounded.AccountBalanceWallet, Primary, onAccount)
                BarChip("统计口径", Icons.Rounded.Tune, Primary, onStats)
                BarChip("报销/退款", Icons.Rounded.Receipt, Primary, onRefund)
            }
        }
    }
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 13.sp,
        color = Primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun BarChip(text: String, icon: ImageVector, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = FieldBg,
        modifier = Modifier
            .padding(end = 8.dp)
            .clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, fontSize = 12.5.sp, color = Sub2, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 通用选择弹窗：目标账本 / 分类 / 账户都复用它 */
@Composable
fun BatchPickDialog(
    title: String,
    subtitle: String,
    options: List<Pair<Long, String>>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Sub) } },
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(subtitle, fontSize = 11.5.sp, color = Sub, lineHeight = 17.sp)
                Spacer(Modifier.height(10.dp))
                if (options.isEmpty()) {
                    Text("没有可选的项", fontSize = 13.sp, color = Sub2)
                } else {
                    options.forEach { (id, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(id) }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, fontSize = 14.sp, color = Ink, modifier = Modifier.weight(1f))
                            Icon(
                                Icons.Rounded.KeyboardArrowRight,
                                null,
                                tint = Chevron,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    )
}

/** 批量设置统计口径：两个开关分别对应「不计入收支 / 不计入预算」 */
@Composable
fun BatchStatsDialog(
    count: Int,
    onApply: (excludeFromStats: Boolean, excludeFromBudget: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var excludeStats by remember { mutableStateOf(false) }
    var excludeBudget by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        confirmButton = {
            TextButton(onClick = { onApply(excludeStats, excludeBudget); onDismiss() }) {
                Text("应用到 $count 笔", color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Sub) } },
        title = { Text("批量设置统计口径", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("两项会按下面的选择整体写入所选账单。", fontSize = 11.5.sp, color = Sub)
                Spacer(Modifier.height(12.dp))
                StatRow("不计入收支", "不参与收入 / 支出统计，但仍然改变账户余额", excludeStats) {
                    excludeStats = it
                }
                Spacer(Modifier.height(10.dp))
                StatRow("不计入预算", "当月预算的已用 / 剩余不算它", excludeBudget) { excludeBudget = it }
            }
        }
    )
}

@Composable
private fun StatRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, color = Ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(desc, fontSize = 11.sp, color = Sub, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        ToggleSwitch(checked = checked, onChange = onChange)
    }
}

/** 批量设置报销 / 退款额（元）；退款额超过单笔金额时由保存逻辑逐笔封顶 */
@Composable
fun BatchRefundDialog(
    count: Int,
    onApply: (cents: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        confirmButton = {
            TextButton(onClick = { onApply(Money.parse(text.ifBlank { "0" }) ?: 0L); onDismiss() }) {
                Text("应用到 $count 笔", color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Sub) } },
        title = { Text("批量设置报销 / 退款", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("退款金额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "退款额不会超过每笔账单的金额（逐笔封顶）；支出 / 收入才支持退款，互转与借贷会自动跳过。",
                    fontSize = 11.5.sp,
                    color = Sub,
                    lineHeight = 17.sp
                )
            }
        }
    )
}

// ---------------- 多选批量操作宿主（首页 / 明细页 / 搜索页共用） ----------------

private const val BATCH_PICK_NONE = 0
private const val BATCH_PICK_LEDGER = 1
private const val BATCH_PICK_CATEGORY = 2
private const val BATCH_PICK_ACCOUNT = 3

/**
 * 多选模式的「底部操作栏 + 各批量弹窗」宿主（N28）。
 * 勾选集合只在这里读取，所以勾选时只有宿主重组，列表行不会跟着整表重建。
 *
 * @param allTxns 当前列表可见的全部账单（用于全选、反查选中的对象）
 */
@Composable
fun TxnBatchHost(
    vm: AppViewModel,
    state: TxnMultiSelectState,
    allTxns: List<Txn>,
    modifier: Modifier = Modifier,
    /** N55：借贷列表（收回 / 还款）时显示「批量收回」 */
    lendMode: Boolean = false
) {
    if (!state.active) return

    val ledgers by vm.ledgers.collectAsState()
    val categories by vm.categories.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val currentLedgerId by vm.currentLedgerId.collectAsState()

    var pick by remember { mutableStateOf(BATCH_PICK_NONE) }
    var showStats by remember { mutableStateOf(false) }
    var showRefund by remember { mutableStateOf(false) }
    var deleteTargets by remember { mutableStateOf<List<Txn>?>(null) }

    val selectedIds = state.selected.value
    fun selectedTxns(): List<Txn> = allTxns.filter { it.id in state.selected.value }

    TxnBatchBar(
        selectedCount = selectedIds.size,
        allSelected = allTxns.isNotEmpty() && selectedIds.size == allTxns.size,
        modifier = modifier,
        onToggleSelectAll = {
            if (allTxns.isNotEmpty() && state.selected.value.size == allTxns.size) state.clear()
            else state.setAll(allTxns.map { it.id })
        },
        onExit = { state.exit() },
        onDelete = { deleteTargets = selectedTxns() },
        onMoveLedger = { pick = BATCH_PICK_LEDGER },
        onCategory = { pick = BATCH_PICK_CATEGORY },
        onAccount = { pick = BATCH_PICK_ACCOUNT },
        onStats = { showStats = true },
        onRefund = { showRefund = true },
        onSettleLend = if (lendMode) {
            {
                vm.settleLendsBatch(selectedTxns())
                state.exit()
            }
        } else null
    )

    deleteTargets?.let { list ->
        BatchDeleteDialog(
            count = list.size,
            onConfirm = { keep ->
                vm.deleteTxns(list, keepBalance = keep)
                state.exit()
            },
            onDismiss = { deleteTargets = null }
        )
    }

    when (pick) {
        BATCH_PICK_LEDGER -> BatchPickDialog(
            title = "移动到哪个账本",
            subtitle = "会把这批账单整体移到目标账本",
            options = ledgers.filter { it.id != currentLedgerId }.map { it.id to it.name },
            onPick = { id ->
                ledgers.firstOrNull { it.id == id }?.let { vm.moveTxnsToLedger(selectedTxns(), it) }
                pick = BATCH_PICK_NONE
                state.exit()
            },
            onDismiss = { pick = BATCH_PICK_NONE }
        )
        BATCH_PICK_CATEGORY -> BatchPickDialog(
            title = "改成哪个分类",
            subtitle = "隐藏的分类不会出现在这里",
            options = categories.filter { !it.hidden }
                .map { it.id to (if (it.parentId == 0L) it.name else "　└ " + it.name) },
            onPick = { id ->
                categories.firstOrNull { it.id == id }?.let { vm.changeTxnsCategory(selectedTxns(), it) }
                pick = BATCH_PICK_NONE
                state.exit()
            },
            onDismiss = { pick = BATCH_PICK_NONE }
        )
        BATCH_PICK_ACCOUNT -> BatchPickDialog(
            title = "改成哪个账户",
            subtitle = "隐藏的账户不会出现在这里",
            options = accounts.filter { !it.hidden }.map { it.id to it.name },
            onPick = { id ->
                accounts.firstOrNull { it.id == id }?.let { vm.changeTxnsAccount(selectedTxns(), it) }
                pick = BATCH_PICK_NONE
                state.exit()
            },
            onDismiss = { pick = BATCH_PICK_NONE }
        )
    }

    if (showStats) {
        BatchStatsDialog(
            count = selectedIds.size,
            onApply = { stats, budget ->
                vm.changeTxnsStats(selectedTxns(), excludeFromStats = stats, excludeFromBudget = budget)
                state.exit()
            },
            onDismiss = { showStats = false }
        )
    }
    if (showRefund) {
        BatchRefundDialog(
            count = selectedIds.size,
            onApply = { cents ->
                vm.changeTxnsRefund(selectedTxns(), cents)
                state.exit()
            },
            onDismiss = { showRefund = false }
        )
    }
}

/** 批量删除：沿用单笔删除的同一套语义（余额跟着动 / 余额保持不变） */
@Composable
private fun BatchDeleteDialog(
    count: Int,
    onConfirm: (keepBalance: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var keepBalance by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        confirmButton = {
            TextButton(onClick = { onConfirm(keepBalance) }) {
                Text("确认删除", color = Expense, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Primary) } },
        title = { Text("删除这 $count 笔账单？", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("删除后账户余额怎么处理？", fontSize = 12.5.sp, color = Sub)
                Spacer(Modifier.height(8.dp))
                BatchDeleteOption(
                    selected = !keepBalance,
                    title = "余额跟着动（推荐）",
                    desc = "删除后账户余额自动回滚，就像这些账没记过。",
                    onClick = { keepBalance = false }
                )
                Spacer(Modifier.height(8.dp))
                BatchDeleteOption(
                    selected = keepBalance,
                    title = "余额保持不变",
                    desc = "记录照删，同时补一笔「不计收支」的余额调整，把余额补回现在的数值。",
                    onClick = { keepBalance = true }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "删除的记录会进「回收站」，保留 30 天，期间可以随时还原。",
                    fontSize = 11.5.sp,
                    color = Sub,
                    lineHeight = 16.sp
                )
            }
        }
    )
}

@Composable
private fun BatchDeleteOption(
    selected: Boolean,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) PrimaryBg else FieldBgAlt,
        border = if (selected) BorderStroke(1.4.dp, Primary) else null
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text(desc, fontSize = 11.5.sp, color = Sub, lineHeight = 16.sp)
        }
    }
}
