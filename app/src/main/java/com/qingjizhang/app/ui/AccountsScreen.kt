package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.*
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.deleteAccount
import com.qingjizhang.app.vm.toggleHiddenAccounts
import com.qingjizhang.app.vm.txnCountOfAccount
import kotlinx.coroutines.launch

@Composable
fun AccountsScreen(
    vm: AppViewModel,
    onMenu: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    onEditAccount: (Long) -> Unit,
    onAddAccount: () -> Unit,
    onSortAccounts: () -> Unit
) {
    val balances by vm.balances.collectAsState()
    val visible by vm.visibleAccounts.collectAsState()
    val net by vm.netWorth.collectAsState()
    val showHidden by vm.showHiddenAccounts.collectAsState()
    val totalAccounts = vm.accounts.collectAsState().value
    // 账户类型（N19）：分组顺序、分组名都跟着当前账本的类型列表走
    val typeList by vm.accountTypes.collectAsState()

    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    var deleteTarget by remember { mutableStateOf<Account?>(null) }
    var showTypes by remember { mutableStateOf(false) }

    if (showTypes) {
        AccountTypeManagerSheet(vm) { showTypes = false }
    }

    deleteTarget?.let { acc ->
        // N35：删除前先显示该账户下的账单数量，让用户选「保留账单」还是「删除账单」
        DeleteAccountDialog(
            accountName = acc.name,
            txnCount = vm.txnCountOfAccount(acc),
            onKeepTxns = {
                vm.deleteAccount(acc, keepTxns = true)
                deleteTarget = null
            },
            onDeleteTxns = {
                vm.deleteAccount(acc, keepTxns = false)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    // F20：左滑展开项由本页列表统一托管 —— 列表一滚动、点别处或别的行滑开，就自动收起
    val swipe = rememberSwipeOpenState(scrollState.isScrollInProgress)

    // 顶栏固定不随内容滚动；长按标题回到顶部
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "账户",
            onMenu = onMenu,
            caret = true,
            onTitleLongPress = { scope.launch { scrollState.animateScrollTo(0) } },
            actions = {
                IconAction(Icons.Rounded.Add, onAddAccount)
                IconAction(Icons.Rounded.History, {})
                // 管理账户类型（N19）：新建 / 重命名 / 删除当前账本的类型
                IconAction(Icons.Rounded.Category, { showTypes = true })
                // 排序入口：打开独立的全屏排序页（F21，避免与本页手势互斥）
                IconAction(Icons.Rounded.FilterList, onSortAccounts)
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
            // 净资产卡：长按显示 / 隐藏隐藏账户
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(HeroGradient)
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { vm.toggleHiddenAccounts() })
                    }
            ) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 44.dp, y = (-56).dp)
                        .size(160.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.13f))
                )
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("净资产", fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.9f))
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            // v2.9.1 无障碍：这个图标是唯一的「隐藏账户是否显示」指示，必须朗读出来
                            contentDescription = if (showHidden) "隐藏账户已显示" else "隐藏账户已隐藏",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    AmountText(net.netCents, color = Color.White, fontSize = 34.sp)
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
                            Text("总资产", fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.85f))
                            Spacer(Modifier.height(3.dp))
                            Text(
                                Money.text(net.assetsCents),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("总负债", fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.85f))
                            Spacer(Modifier.height(3.dp))
                            Text(
                                Money.text(net.debtsCents),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            val hiddenCount = totalAccounts.count { it.hidden }
            Spacer(Modifier.height(9.dp))
            Text(
                buildString {
                    if (showHidden) {
                        append("已显示 ")
                        append(hiddenCount)
                        append(" 个隐藏账户 · 再次长按总览卡可隐藏")
                    } else {
                        append("长按上方总览卡可显示隐藏账户")
                        if (hiddenCount > 0) {
                            append("（")
                            append(hiddenCount)
                            append(" 个）")
                        }
                    }
                    if (net.excludedCount > 0) {
                        append(" · ")
                        append(net.excludedCount)
                        append(" 个账户未计入净资产")
                    }
                },
                fontSize = 11.sp,
                color = Sub,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            // 分组顺序按本账本的类型列表；类型列表之外的（例如跨账本共用的账户在别的账本被改名）
            // 追加在后面，保证不会有账户因为类型对不上而从账户页消失
            val typeNames = typeList.map { it.name }
            val sections = (typeNames + balances.map { it.account.type }.filter { it !in typeNames }.distinct())
                .map { name ->
                    name to balances.filter {
                        it.account.type == name && visible.any { v -> v.id == it.account.id }
                    }
                }
                // 需求：没有账户的类型在分组展示时默认隐藏
                .filter { it.second.isNotEmpty() }

            if (sections.isEmpty()) {
                EmptyState(
                    Icons.Rounded.AccountBalanceWallet,
                    "当前账本还没有账户",
                    if (totalAccounts.isNotEmpty()) "账户都被隐藏了，长按上方总览卡可显示"
                    else "点右上角的 ＋ 添加账户"
                )
            }

            sections.forEach { (group, items) ->
                val subtotal = items.sumOf { it.balanceCents }
                val isOpen = collapsed[group] != true
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { collapsed[group] = isOpen }
                        .padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(group, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
                    if (items.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Text("(${items.size})", fontSize = 11.5.sp, color = Sub)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(Money.text(subtotal), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Icon(
                        if (isOpen) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight,
                        null,
                        tint = Sub,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (isOpen) {
                    items.forEach { ab ->
                        Box(Modifier.padding(bottom = 8.dp)) {
                            SwipeRow(
                                onEdit = { onEditAccount(ab.account.id) },
                                onDelete = { deleteTarget = ab.account },
                                onTap = { onOpenAccount(ab.account.id) },
                                expanded = swipe.isOpen(ab.account.id),
                                anyExpanded = swipe.id != null,
                                onExpand = { swipe.setOpen(ab.account.id, true) },
                                onCollapse = { swipe.close() }
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val badge = accountBadge(ab.account.type, ab.account.name)
                                    if (badge != null) {
                                        TextBadge(badge, accountColor(ab.account.type))
                                    } else {
                                        IconBadge(accountIcon(ab.account.type), accountColor(ab.account.type))
                                    }
                                    Spacer(Modifier.width(11.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                ab.account.name,
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Ink,
                                                maxLines = 1
                                            )
                                            if (ab.account.note.isNotBlank()) {
                                                Spacer(Modifier.width(5.dp))
                                                Text(
                                                    ab.account.note,
                                                    fontSize = 11.sp,
                                                    color = Sub,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        if (ab.account.hidden || !ab.account.countInNetWorth) {
                                            Spacer(Modifier.height(3.dp))
                                            Row {
                                                if (ab.account.hidden) {
                                                    TagChip("已隐藏")
                                                    Spacer(Modifier.width(5.dp))
                                                }
                                                if (!ab.account.countInNetWorth) TagChip("不计净资产")
                                            }
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    AmountText(
                                        ab.balanceCents,
                                        fontSize = 15.5.sp,
                                        color = if (ab.balanceCents < 0) Expense else Ink
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(100.dp))
        }
        }
    }
}

@Composable
internal fun TagChip(text: String) {
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
