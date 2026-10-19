package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.ArAp
import com.qingjizhang.app.data.ArApDirection
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.writeOffArAps

/**
 * 应收 / 应付详情（F53）。
 *
 * 从列表点某条记录进来：
 * - 展示金额（剩余 / 原始 / 已收）、类型、分类、账户、对方、记录时间、备注；
 * - 右上角「编辑」复用记一笔界面，可改金额、分类、账户、备注、类型；
 * - 底部主按钮进「收款 / 付款」结算界面；
 * - 次要位置放一个**小号「销账」胶囊**：放弃剩余未收 / 未付的差额（不生成账单、记录直接移除）。
 */
@Composable
fun ArApDetailScreen(
    vm: AppViewModel,
    item: ArAp,
    categoryName: String?,
    accountName: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onSettle: () -> Unit,
    onRemoved: () -> Unit
) {
    val receivable = item.direction == ArApDirection.RECEIVABLE
    val term = if (receivable) "应收款" else "应付款"
    val verb = if (receivable) "收款" else "付款"
    val remain = (item.amountCents - item.settledCents).coerceAtLeast(0L)
    var showWriteOff by remember { mutableStateOf(false) }

    if (showWriteOff) {
        ConfirmDialog(
            title = "销账确认",
            text = "将放弃剩余" + (if (receivable) "未收" else "未付") + " " + Money.withSymbol(remain) +
                "。该记录会从应收应付列表移除，且不会生成账单；已经结算的部分不受影响。",
            confirmText = "销账",
            danger = true,
            onConfirm = {
                showWriteOff = false
                vm.writeOffArAps(listOf(item))
                onRemoved()
            },
            onDismiss = { showWriteOff = false }
        )
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(
            title = "$term 详情",
            onBack = onBack,
            actions = { IconAction(Icons.Rounded.Edit, onEdit) }
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
        ) {
            // 金额区
            CardBox {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        (if (receivable) "待收" else "待付") + "金额",
                        fontSize = 12.sp,
                        color = Sub2
                    )
                    Spacer(Modifier.height(6.dp))
                    AmountText(remain, color = if (receivable) Income else Expense, fontSize = 32.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "原始 " + Money.withSymbol(item.amountCents) +
                            if (item.settledCents > 0L) {
                                " · 已" + (if (receivable) "收" else "付") + " " +
                                    Money.withSymbol(item.settledCents)
                            } else "",
                        fontSize = 11.5.sp,
                        color = Sub
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // 明细
            CardBox {
                DetailRow("类型", term)
                RowDivider()
                DetailRow("分类", categoryName ?: "未分类")
                RowDivider()
                DetailRow("账户", accountName?.takeIf { it.isNotBlank() } ?: "未指定")
                if (item.counterparty.isNotBlank()) {
                    RowDivider()
                    DetailRow("对方", item.counterparty)
                }
                RowDivider()
                DetailRow("记录时间", DateFmt.isoDateTime(item.dateMillis))
                RowDivider()
                DetailRow("备注", item.note.ifBlank { "无" })
            }
            Spacer(Modifier.height(14.dp))

            // F53：小号「销账」胶囊，放在次要位置，不占页面主体
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Expense.copy(alpha = 0.10f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = remain > 0L) { showWriteOff = true }
                ) {
                    Text(
                        "销账 · 放弃剩余",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (remain > 0L) Expense else Sub2,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        // 主操作：收款 / 付款
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Button(
                onClick = onSettle,
                enabled = remain > 0L,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Text(verb, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 详情页的一行：左标签右值 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.5.sp, color = Sub2)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 13.5.sp,
            color = Ink,
            maxLines = 2,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}
