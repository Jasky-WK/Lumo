package com.qingjizhang.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import com.qingjizhang.app.data.Account
import com.qingjizhang.app.data.Direction
import com.qingjizhang.app.data.Ledger
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.data.TxnType
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.clearTrash
import com.qingjizhang.app.vm.purgeAccount
import com.qingjizhang.app.vm.purgeLedger
import com.qingjizhang.app.vm.purgeTxn
import com.qingjizhang.app.vm.restoreAccount
import com.qingjizhang.app.vm.restoreLedger
import com.qingjizhang.app.vm.restoreTxn

/**
 * 回收站：删除的账单、账户与账本先放这里，保留 30 天，期间可还原或彻底删除。
 */
@Composable
fun RecycleBinScreen(
    vm: AppViewModel,
    onBack: () -> Unit
) {
    val txns by vm.trashTxns.collectAsState()
    val accounts by vm.trashAccounts.collectAsState()
    val ledgers by vm.trashLedgers.collectAsState()
    val allAccounts by vm.accounts.collectAsState()

    var purgeTxn by remember { mutableStateOf<Txn?>(null) }
    var purgeAccount by remember { mutableStateOf<Account?>(null) }
    var purgeLedger by remember { mutableStateOf<Ledger?>(null) }
    var clearAll by remember { mutableStateOf(false) }

    purgeTxn?.let { t ->
        ConfirmDialog(
            title = "彻底删除这笔账单？",
            text = "彻底删除后无法再还原。",
            confirmText = "彻底删除",
            danger = true,
            onConfirm = { vm.purgeTxn(t); purgeTxn = null },
            onDismiss = { purgeTxn = null }
        )
    }
    purgeAccount?.let { a ->
        ConfirmDialog(
            title = "彻底删除这个账户？",
            text = "它名下已在回收站的账单会一起被彻底删除，无法还原。",
            confirmText = "彻底删除",
            danger = true,
            onConfirm = { vm.purgeAccount(a); purgeAccount = null },
            onDismiss = { purgeAccount = null }
        )
    }
    // N59：彻底删除账本 = 级联清空该账本所有数据（比软删除危险得多，单独确认）
    purgeLedger?.let { l ->
        ConfirmDialog(
            title = "彻底删除账本「${l.name}」？",
            text = "该账本下的全部账单、账户、分类与周期规则都会被彻底删除，无法还原。" +
                "只想暂时不用这个账本的话，选「还原」就行。",
            confirmText = "彻底删除",
            danger = true,
            onConfirm = { vm.purgeLedger(l); purgeLedger = null },
            onDismiss = { purgeLedger = null }
        )
    }
    if (clearAll) {
        ConfirmDialog(
            title = "清空回收站？",
            text = "回收站里的 ${txns.size} 笔账单、${accounts.size} 个账户与 ${ledgers.size} 个账本" +
                "会被彻底删除，无法还原。",
            confirmText = "清空",
            danger = true,
            onConfirm = { vm.clearTrash(); clearAll = false },
            onDismiss = { clearAll = false }
        )
    }

    val accById = remember(allAccounts) { allAccounts.associateBy { it.id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item { BackTopBar(title = "回收站", onBack = onBack) }

        item {
            Column(Modifier.padding(horizontal = 14.dp)) {
                TipBanner(
                    text = "删除的账单、账户与账本会先放进回收站，保留 30 天，期间可以随时还原；" +
                        "超过 30 天自动彻底删除。回收站里的内容不参与余额、统计与预算。",
                    icon = Icons.Rounded.DeleteOutline
                )
                Spacer(Modifier.height(14.dp))
            }
        }

        item {
            Column(Modifier.padding(horizontal = 14.dp)) {
                SectionTitle("账单（${txns.size}）")
            }
        }
        if (txns.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    CardBox {
                        Text(
                            "回收站里没有账单",
                            fontSize = 12.5.sp,
                            color = Sub,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            // 账单与账户在同一个 LazyColumn 里，两者 id 各自自增、会撞号（账单 3 + 账户 3），
            // 原来都用 it.id 当 key 会直接抛 "Key already used" 崩掉（2.2.1 修复），
            // 所以这里给两类条目各自加前缀，保证 key 全局唯一
            items(txns, key = { "txn-${it.id}" }) { t ->
                Column(Modifier.padding(horizontal = 14.dp)) {
                    CardBox(Modifier.padding(bottom = 8.dp)) {
                        TrashRow(
                            icon = txnIcon(t),
                            tint = txnTint(t),
                            title = txnTitle(t),
                            subtitle = DateFmt.isoDate(t.dateMillis) + " · " +
                                (accById[t.accountId]?.name ?: "已删除账户"),
                            amountText = txnAmountText(t),
                            amountColor = txnAmountColor(t),
                            daysLeft = daysLeft(t.deletedAt),
                            onRestore = { vm.restoreTxn(t) },
                            onPurge = { purgeTxn = t }
                        )
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 14.dp)) {
                Spacer(Modifier.height(8.dp))
                SectionTitle("账户（${accounts.size}）")
            }
        }
        if (accounts.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    CardBox {
                        Text(
                            "回收站里没有账户",
                            fontSize = 12.5.sp,
                            color = Sub,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(accounts, key = { "acc-${it.id}" }) { a ->
                Column(Modifier.padding(horizontal = 14.dp)) {
                    CardBox(Modifier.padding(bottom = 8.dp)) {
                        TrashRow(
                            icon = accountIcon(a.type),
                            tint = accountColor(a.type),
                            title = a.name,
                            subtitle = "账户 · 名下账单一起还原",
                            amountText = Money.text(a.initialBalanceCents),
                            amountColor = Sub,
                            daysLeft = daysLeft(a.deletedAt),
                            onRestore = { vm.restoreAccount(a) },
                            onPurge = { purgeAccount = a }
                        )
                    }
                }
            }
        }

        // N59：账本（软删除的，数据都还在）
        item {
            Column(Modifier.padding(horizontal = 14.dp)) {
                Spacer(Modifier.height(8.dp))
                SectionTitle("账本（${ledgers.size}）")
            }
        }
        if (ledgers.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    CardBox {
                        Text(
                            "回收站里没有账本",
                            fontSize = 12.5.sp,
                            color = Sub,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(ledgers, key = { "ledger-${it.id}" }) { l ->
                Column(Modifier.padding(horizontal = 14.dp)) {
                    CardBox(Modifier.padding(bottom = 8.dp)) {
                        TrashRow(
                            icon = Icons.Rounded.MenuBook,
                            tint = Purple,
                            title = l.name,
                            subtitle = "账本 · 账单 / 账户 / 分类都还在",
                            amountText = "还原后照常使用",
                            amountColor = Sub,
                            daysLeft = daysLeft(l.deletedAt),
                            onRestore = { vm.restoreLedger(l) },
                            onPurge = { purgeLedger = l }
                        )
                    }
                }
            }
        }

        if (txns.isNotEmpty() || accounts.isNotEmpty() || ledgers.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { clearAll = true },
                        shape = RoundedCornerShape(14.dp),
                        color = Expense.copy(alpha = 0.08f)
                    ) {
                        Text(
                            "清空回收站",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Expense,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/** 剩余可还原天数 */
private fun daysLeft(deletedAt: Long): Int {
    val passed = (System.currentTimeMillis() - deletedAt) / (24L * 60 * 60 * 1000)
    return (30 - passed).coerceIn(0L, 30L).toInt()
}

private fun txnIcon(t: Txn) = when (t.type) {
    TxnType.INCOME -> Icons.Rounded.ArrowDownward
    TxnType.TRANSFER -> Icons.Rounded.SwapHoriz
    TxnType.LEND_BORROW -> Icons.Rounded.Savings
    else -> Icons.Rounded.ArrowUpward
}

@Composable
private fun txnTint(t: Txn) = when (t.type) {
    TxnType.INCOME -> Income
    TxnType.TRANSFER -> Primary
    TxnType.LEND_BORROW -> Purple
    else -> Expense
}

private fun txnTitle(t: Txn): String = when (t.type) {
    TxnType.EXPENSE -> "支出"
    TxnType.INCOME -> "收入"
    TxnType.TRANSFER -> "账户互转"
    TxnType.LEND_BORROW -> if (t.direction == Direction.LEND) "借出" else "借入"
}

private fun txnAmountText(t: Txn): String =
    (if (t.type == TxnType.INCOME) "+" else "-") + Money.text(t.amountCents)

// M10：不计入收支的账单金额用灰色，避免误导成已计入统计；
// 这里保留回收站原有的「收入绿 / 其余红」映射（互转在本页显示为红，与列表页的中性色不同），只前置灰色判断
private fun txnAmountColor(t: Txn) =
    if (t.excludeFromStats) NeutralGray
    else when (t.type) {
        TxnType.INCOME -> Income
        else -> Expense
    }

@Composable
private fun TrashRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    amountText: String,
    amountColor: Color,
    daysLeft: Int,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon, tint, 36.dp, 18.dp, 12.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Text(amountText, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = amountColor)
            }
            Spacer(Modifier.height(3.dp))
            Text(subtitle, fontSize = 11.sp, color = Sub, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text("还有 $daysLeft 天可还原", fontSize = 11.sp, color = Sub)
        }
        Spacer(Modifier.width(8.dp))
        TinyButton("还原", Primary, onRestore)
        Spacer(Modifier.width(6.dp))
        TinyButton("彻底删除", Expense, onPurge)
    }
}

@Composable
private fun TinyButton(text: String, color: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(9.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}
