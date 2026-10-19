package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.Budget
import com.qingjizhang.app.data.BudgetDirection
import com.qingjizhang.app.ui.components.CardBox
import com.qingjizhang.app.ui.components.ConfirmDialog
import com.qingjizhang.app.ui.components.IconAction
import com.qingjizhang.app.ui.components.IconBadge
import com.qingjizhang.app.ui.components.RowDivider
import com.qingjizhang.app.ui.components.SectionTitle
import com.qingjizhang.app.ui.components.pct
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.BudgetProgress
import com.qingjizhang.app.vm.deleteBudget
import com.qingjizhang.app.vm.toggleBudgetEnabled

/**
 * 设置页里的「自定义预算」区块（N30）。
 *
 * 列表卡片给出：事项名、时间段、已用 / 预算、进度条、剩余或超支文案、暂停标记，
 * 每条都能编辑 / 暂停·启用 / 删除（删除先二次确认）。新建与编辑用底部面板。
 */
@Composable
internal fun CustomBudgetSection(vm: AppViewModel) {
    val progress by vm.budgetProgress.collectAsState()
    var editing by remember { mutableStateOf<Budget?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Budget?>(null) }

    if (creating) {
        BudgetEditSheet(vm = vm, editing = null, onDismiss = { creating = false })
    }
    editing?.let { b ->
        BudgetEditSheet(vm = vm, editing = b, onDismiss = { editing = null })
    }
    deleting?.let { b ->
        ConfirmDialog(
            title = "删除预算「${b.name}」？",
            text = "只删除这条预算定义，历史账单、账户余额与收支统计都不受影响。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteBudget(b) },
            onDismiss = { deleting = null }
        )
    }

    SettingsGroupTitle("自定义预算")
    if (progress.isEmpty()) {
        CardBox {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 15.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBadge(Icons.Rounded.Flag, Purple, size = 30.dp, iconSize = 17.dp, radius = 10.dp)
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text("还没有自定义预算", fontSize = 13.5.sp, color = Ink)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "可为「旅游基金」这类事项单独设时间段与金额上限",
                            fontSize = 11.sp,
                            color = Sub
                        )
                    }
                }
                RowDivider()
                AddBudgetRow { creating = true }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "时间段默认当前自然月，可自由改起止日期；预算与「月度预算」并存，互不影响。",
            fontSize = 11.sp,
            color = Sub,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    } else {
        progress.forEach { p ->
            CardBox(Modifier.padding(bottom = 8.dp)) {
                BudgetCard(
                    p = p,
                    onEdit = { editing = p.budget },
                    onToggle = { vm.toggleBudgetEnabled(p.budget) },
                    onDelete = { deleting = p.budget }
                )
            }
        }
        CardBox {
            AddBudgetRow { creating = true }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "与「月度预算」并存展示，互不冲突；删除预算只删定义，历史统计保留。",
            fontSize = 11.sp,
            color = Sub,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

/** 总览页的自定义预算进度（N30）：没有自定义预算时什么都不显示，不占空间 */
@Composable
internal fun CustomBudgetOverview(vm: AppViewModel) {
    val progress by vm.budgetProgress.collectAsState()
    if (progress.isEmpty()) return

    SectionTitle("自定义预算", trailing = "在设置里管理 ›")
    progress.forEach { p ->
        CardBox(Modifier.padding(bottom = 8.dp)) { BudgetCard(p = p, compact = true) }
    }
}

@Composable
private fun AddBudgetRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.Add, null, tint = Primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("新建自定义预算", fontSize = 13.5.sp, color = Primary, fontWeight = FontWeight.Bold)
    }
}

/**
 * 一条自定义预算的进度卡片。
 * [onEdit] / [onToggle] / [onDelete] 传 null 表示只展示（总览页用）。
 */
@Composable
internal fun BudgetCard(
    p: BudgetProgress,
    onEdit: (() -> Unit)? = null,
    onToggle: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    compact: Boolean = false
) {
    val b = p.budget
    val barColor = when {
        !b.enabled -> Muted
        p.overspent -> Expense
        else -> Primary
    }
    val rangeText = DateFmt.isoRange(DateFmt.dateOf(b.startMillis), DateFmt.dateOf(b.endMillis))
    val bindings = buildList {
        if (b.categoryId != 0L) add("分类")
        if (b.accountId != 0L) add("账户")
        if (b.keyword.isNotBlank()) add("关键词「${b.keyword}」")
    }

    Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                Icons.Rounded.Flag,
                if (b.enabled) Primary else Muted,
                size = 32.dp,
                iconSize = 17.dp,
                radius = 11.dp
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        b.name,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(6.dp))
                    BudgetTag(if (b.direction == BudgetDirection.INCOME) "收入口径" else "支出口径")
                    if (!b.enabled) {
                        Spacer(Modifier.width(5.dp))
                        BudgetTag("已暂停")
                    } else if (p.phase != 1) {
                        Spacer(Modifier.width(5.dp))
                        BudgetTag(p.phaseLabel)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    rangeText + (if (bindings.isEmpty()) "" else " · 限" + bindings.joinToString("、")),
                    fontSize = 11.sp,
                    color = Sub,
                    maxLines = 1
                )
            }
            if (!compact && onEdit != null) {
                IconAction(Icons.Rounded.Edit, onEdit, tint = Sub, size = 18.dp)
            }
            if (!compact && onDelete != null) {
                IconAction(Icons.Rounded.Delete, onDelete, tint = Expense, size = 18.dp)
            }
        }

        Spacer(Modifier.height(10.dp))
        BudgetBarLine(ratio = p.ratio, color = barColor)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已用 ¥" + Money.text(p.usedCents) + " / 预算 ¥" + Money.text(b.amountCents),
                fontSize = 11.5.sp,
                color = Sub2
            )
            Spacer(Modifier.weight(1f))
            Text(pct(p.usedCents, b.amountCents), fontSize = 11.5.sp, color = Sub2)
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    p.overspent -> "已超支 ¥" + Money.text(p.overspentCents)
                    else -> "剩余 ¥" + Money.text(p.remainingCents)
                },
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (p.overspent) Expense else Income
            )
            Spacer(Modifier.weight(1f))
            if (!compact && onToggle != null) {
                Text(
                    if (b.enabled) "暂停" else "启用",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (b.enabled) Sub2 else Primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onToggle() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/** 进度条：底轨 + 已用填充（超支用支出色，暂停用灰色） */
@Composable
private fun BudgetBarLine(ratio: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Track)
    ) {
        val r = ratio.coerceIn(0f, 1f)
        if (r > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(r)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun BudgetTag(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = FieldBg) {
        Text(
            text,
            fontSize = 10.sp,
            color = Sub,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
