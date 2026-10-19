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
import com.qingjizhang.app.data.*
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.catchUpNow
import com.qingjizhang.app.vm.deleteRule
import com.qingjizhang.app.vm.saveRule
import com.qingjizhang.app.vm.toast
import com.qingjizhang.app.vm.toggleRule
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
fun RecurringListScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onEdit: (Long?) -> Unit
) {
    val rules by vm.rules.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val pending by vm.pendingRecurring.collectAsState()
    var deleteTarget by remember { mutableStateOf<RecurringRule?>(null) }
    val now = System.currentTimeMillis()
    // F20：左滑展开项由页面托管，滚动时自动收起
    val scrollState = rememberScrollState()
    val swipe = rememberSwipeOpenState(scrollState.isScrollInProgress)

    deleteTarget?.let { r ->
        ConfirmDialog(
            title = "删除规则「${r.name}」？",
            text = "已由这条规则生成的账单会一并删除，之后不再自动记账。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteRule(r) },
            onDismiss = { deleteTarget = null }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        BackTopBar(
            title = "周期记账",
            onBack = onBack,
            actions = { IconAction(Icons.Rounded.Add, { onEdit(null) }) }
        )
        Column(Modifier.padding(horizontal = 14.dp)) {
            TipBanner(
                text = "本 App 不联网、也没有后台常驻服务，所以周期账单在每次打开 App 时自动补齐（不会漏记）。",
                icon = Icons.Rounded.Autorenew
            )
            if (pending > 0) {
                Spacer(Modifier.height(10.dp))
                TipBanner(
                    text = "本月有 $pending 笔周期账单待生成",
                    icon = Icons.Rounded.ErrorOutline,
                    bg = WarnBg,
                    fg = WarnFg,
                    action = {
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = WarnIcon,
                            modifier = Modifier.clickable { vm.catchUpNow() }
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

            Spacer(Modifier.height(14.dp))
            if (rules.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.Autorenew,
                    title = "还没有周期记账规则",
                    hint = "房租、工资、会员费这类固定支出，设一次就会自动记账",
                    action = {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Primary,
                            modifier = Modifier.clickable { onEdit(null) }
                        ) {
                            Text(
                                "新建规则",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp)
                            )
                        }
                    }
                )
            } else {
                rules.forEach { r ->
                    val acc = accounts.firstOrNull { it.id == r.accountId }
                    val cat = categories.firstOrNull { it.id == r.categoryId }
                    val style = catStyle(cat?.name)
                    val next = if (r.enabled) RecurringEngine.nextRun(r, now) else null
                    Box(Modifier.padding(bottom = 8.dp)) {
                        SwipeRow(
                            onEdit = { onEdit(r.id) },
                            onDelete = { deleteTarget = r },
                            onTap = { onEdit(r.id) },
                            expanded = swipe.isOpen(r.id),
                            anyExpanded = swipe.id != null,
                            onExpand = { swipe.setOpen(r.id, true) },
                            onCollapse = { swipe.close() }
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconBadge(style.icon, style.color)
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            r.name,
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Ink,
                                            maxLines = 1
                                        )
                                        if (!r.enabled) {
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = FieldBg
                                            ) {
                                                Text(
                                                    "已暂停",
                                                    fontSize = 10.sp,
                                                    color = Sub,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        RecurringEngine.describe(r) + " · " + (cat?.name ?: typeLabel(r.type)) +
                                            (if (acc != null) " · " + acc.name else ""),
                                        fontSize = 11.5.sp,
                                        color = Sub,
                                        maxLines = 1
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(horizontalAlignment = Alignment.End) {
                                    AmountText(
                                        if (r.type == TxnType.INCOME) r.amountCents else -r.amountCents,
                                        fontSize = 15.sp,
                                        color = if (r.type == TxnType.INCOME) Income else Expense
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        if (next != null) "下次 " + DateFmt.isoDate(next).substring(5) else "已结束",
                                        fontSize = 10.5.sp,
                                        color = Sub
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                ToggleSwitch(checked = r.enabled, onChange = { vm.toggleRule(r, it) })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            CardBox {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(null) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Rounded.Add, null, tint = Primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建周期记账", fontSize = 13.5.sp, color = Primary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
fun RecurringEditScreen(
    vm: AppViewModel,
    ruleId: Long?,
    onBack: () -> Unit
) {
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val ledgerId by vm.currentLedgerId.collectAsState()
    val rules by vm.rules.collectAsState()
    val existing = rules.firstOrNull { it.id == (ruleId ?: 0L) }

    var type by remember { mutableStateOf(TxnType.EXPENSE) }
    var name by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf(0L) }
    var categoryId by remember { mutableStateOf(0L) }
    var freq by remember { mutableStateOf(Freq.MONTHLY) }
    var intervalText by remember { mutableStateOf("1") }
    var startDate by remember { mutableStateOf(LocalDate.now()) }
    var endMode by remember { mutableStateOf(EndMode.NEVER) }
    var endCountText by remember { mutableStateOf("12") }
    var notifyAhead by remember { mutableStateOf(1) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(existing) {
        val r = existing ?: return@LaunchedEffect
        type = r.type
        name = r.name
        amountText = Money.text(r.amountCents)
        accountId = r.accountId
        categoryId = r.categoryId
        freq = r.freq
        intervalText = r.interval.toString()
        startDate = DateFmt.dateOf(r.startMillis)
        endMode = r.endMode
        endCountText = if (r.endCount > 0) r.endCount.toString() else "12"
        notifyAhead = r.notifyAheadDays
        note = r.note
    }
    LaunchedEffect(accounts) {
        if (accountId == 0L) accountId = accounts.firstOrNull()?.id ?: 0L
    }
    LaunchedEffect(categories, type) {
        if (categoryId != 0L) return@LaunchedEffect
        val ct = if (type == TxnType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
        // 隐藏分类（N27）不当默认值
        categoryId = categories.firstOrNull { it.type == ct && it.parentId == 0L && !it.hidden }?.id ?: 0L
    }

    val catType = if (type == TxnType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
    // 隐藏分类不参与选择（N27），历史账单与统计口径不受影响
    val level1 = categories.filter { it.type == catType && it.parentId == 0L && !it.hidden }
    val selectedCat = categories.firstOrNull { it.id == categoryId }
    val amountCents = Money.parse(if (amountText.isBlank()) "0" else amountText) ?: 0L
    val interval = intervalText.toIntOrNull()?.coerceAtLeast(1) ?: 1

    // 规则预览
    val previewRule = RecurringRule(
        id = existing?.id ?: 0L,
        ledgerId = ledgerId,
        name = name.ifBlank { "未命名" },
        type = type,
        amountCents = amountCents.coerceAtLeast(1),
        accountId = accountId,
        categoryId = categoryId,
        note = note,
        freq = freq,
        interval = interval,
        startMillis = DateFmt.toMillis(LocalDateTime.of(startDate, LocalDateTime.now().toLocalTime())),
        endMode = endMode,
        endCount = endCountText.toIntOrNull() ?: 0,
        enabled = true,
        notifyAheadDays = notifyAhead,
        generatedCount = existing?.generatedCount ?: 0,
        lastGeneratedMillis = existing?.lastGeneratedMillis ?: 0L
    )
    val nextRun = RecurringEngine.nextRun(previewRule, System.currentTimeMillis())
    val perYear = RecurringEngine.timesPerYear(previewRule)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        BackTopBar(
            title = if (ruleId == null) "新建周期记账" else "编辑周期记账",
            onBack = onBack,
            actions = {
                Text(
                    "保存",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            if (amountCents <= 0L) {
                                vm.toast("请输入金额")
                            } else {
                                vm.saveRule(
                                    previewRule.copy(
                                        name = name.ifBlank {
                                            selectedCat?.name ?: typeLabel(previewRule.type)
                                        }
                                    ),
                                    isNew = ruleId == null
                                )
                                onBack()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        )
        Column(Modifier.padding(horizontal = 14.dp)) {
            SegTabs(listOf("支出", "收入", "互转"), when (type) {
                TxnType.EXPENSE -> 0
                TxnType.INCOME -> 1
                else -> 2
            }) {
                type = when (it) {
                    0 -> TxnType.EXPENSE
                    1 -> TxnType.INCOME
                    else -> TxnType.TRANSFER
                }
            }

            Spacer(Modifier.height(14.dp))
            CardBox {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            selectedCat?.name ?: typeLabel(type),
                            fontSize = 13.sp,
                            color = Sub2,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        Text("¥", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Spacer(Modifier.width(2.dp))
                        Text(
                            if (amountText.isBlank()) "0.00" else amountText,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("金额（元）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            SectionTitle("基本信息")
            CardBox {
                Column {
                    LabeledInput("名称", name, "如：房租", { name = it })
                    RowDivider()
                    AccountPickerRow(
                        label = "账户",
                        current = accounts.firstOrNull { it.id == accountId },
                        accounts = accounts,
                        onPick = { accountId = it.id }
                    )
                    if (type != TxnType.TRANSFER) {
                        RowDivider()
                        CategoryPickerRow(
                            label = "分类",
                            current = categories.firstOrNull { it.id == categoryId }?.name ?: "请选择",
                            list = level1,
                            children = { pid -> categories.filter { it.parentId == pid && !it.hidden } },
                            onPick = { categoryId = it }
                        )
                    }
                    RowDivider()
                    LabeledInput("备注", note, "选填", { note = it })
                }
            }

            SectionTitle("周期")
            CardBox {
                Column(Modifier.padding(14.dp)) {
                    SegSmall(
                        listOf("每天", "每周", "每月", "每年"),
                        listOf(Freq.DAILY, Freq.WEEKLY, Freq.MONTHLY, Freq.YEARLY).indexOf(freq)
                    ) { freq = listOf(Freq.DAILY, Freq.WEEKLY, Freq.MONTHLY, Freq.YEARLY)[it] }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(FieldBgAlt)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.CalendarMonth, null, tint = Primary, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            RecurringEngine.describe(previewRule),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink
                        )
                    }
                }
            }

            SectionTitle("更多设置")
            CardBox {
                Column {
                    LabeledInput("重复间隔", intervalText, "每 N 个周期执行一次", {
                        intervalText = it.filter { c -> c.isDigit() }.take(3)
                    }, numberOnly = true)
                    RowDivider()
                    DatePickerRow("开始日期", startDate) { startDate = it }
                    RowDivider()
                    LabeledInput("结束次数", endCountText, "达到次数后停止", {
                        endCountText = it.filter { c -> c.isDigit() }.take(3)
                    }, numberOnly = true)
                    RowDivider()
                    LabeledInput("提前提醒天数", notifyAhead.toString(), "0 表示不提醒；需在设置里打开「周期提醒」", {
                        notifyAhead = (it.filter { c -> c.isDigit() }.take(2).toIntOrNull() ?: 0)
                    }, numberOnly = true)
                }
            }

            SectionTitle("规则预览")
            CardBox {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "下次生成 " + (nextRun?.let { DateFmt.isoDateTime(it) } ?: "已结束"),
                        fontSize = 13.5.sp,
                        color = Ink
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "预计每年 $perYear 笔 · 合计 ¥" +
                            Money.text(amountCents.coerceAtLeast(0L) * perYear),
                        fontSize = 13.5.sp,
                        color = Ink
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "生成方式：打开 App 时自动补记（不联网、无后台常驻）",
                        fontSize = 11.5.sp,
                        color = Sub
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onBack() },
                    shape = RoundedCornerShape(14.dp),
                    color = CardWhite
                ) {
                    Text(
                        "取消",
                        modifier = Modifier.padding(vertical = 13.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 14.5.sp,
                        color = Sub2
                    )
                }
                Spacer(Modifier.width(11.dp))
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            if (amountCents <= 0L) {
                                vm.toast("请输入金额")
                            } else {
                                vm.saveRule(
                                    previewRule.copy(
                                        name = name.ifBlank {
                                            selectedCat?.name ?: typeLabel(previewRule.type)
                                        }
                                    ),
                                    isNew = ruleId == null
                                )
                                onBack()
                            }
                        },
                    shape = RoundedCornerShape(14.dp),
                    color = Primary
                ) {
                    Text(
                        "保存规则",
                        modifier = Modifier.padding(vertical = 13.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun LabeledInput(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    numberOnly: Boolean = false
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.5.sp, color = Sub2)
        Spacer(Modifier.width(12.dp))
        TextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, fontSize = 13.sp, color = Muted) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (numberOnly) androidx.compose.ui.text.input.KeyboardType.Number
                else androidx.compose.ui.text.input.KeyboardType.Text
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Ink,
                unfocusedTextColor = Ink,
                cursorColor = Primary
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp, textAlign = TextAlign.End)
        )
    }
}

@Composable
private fun AccountPickerRow(
    label: String,
    current: Account?,
    accounts: List<Account>,
    onPick: (Account) -> Unit
) {
    var show by remember { mutableStateOf(false) }
    PickerRow(label, current?.name ?: "请选择") { show = true }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = { TextButton(onClick = { show = false }) { Text("关闭", color = Primary) } },
            title = { Text("选择账户", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    accounts.forEach { a ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(a); show = false }
                                .padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val badge = accountBadge(a.type, a.name)
                            if (badge != null) TextBadge(badge, accountColor(a.type), size = 34.dp)
                            else IconBadge(accountIcon(a.type), accountColor(a.type), size = 34.dp)
                            Spacer(Modifier.width(11.dp))
                            Text(a.name, fontSize = 14.sp, color = Ink)
                            Spacer(Modifier.weight(1f))
                            // 账户的 type 就是类型名（自定义分组名）
                            Text(a.type, fontSize = 11.5.sp, color = Sub)
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun DatePickerRow(label: String, date: LocalDate, onPick: (LocalDate) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    PickerRow(label, date.toString()) {
        android.app.DatePickerDialog(
            context,
            { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth
        ).show()
    }
}

@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.5.sp, color = Sub2)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
}

@Composable
private fun CategoryPickerRow(
    label: String,
    current: String,
    list: List<Category>,
    children: (Long) -> List<Category>,
    onPick: (Long) -> Unit
) {
    var show by remember { mutableStateOf(false) }
    var expandedParent by remember { mutableStateOf(0L) }
    PickerRow(label, current) { show = true }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = { TextButton(onClick = { show = false }) { Text("关闭", color = Primary) } },
            title = { Text("选择分类", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    list.forEach { parent ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedParent = if (expandedParent == parent.id) 0L else parent.id
                                }
                                .padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconBadge(catStyle(parent.name).icon, catStyle(parent.name).color, size = 32.dp, iconSize = 17.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(parent.name, fontSize = 14.sp, color = Ink)
                            Spacer(Modifier.weight(1f))
                            Icon(
                                if (expandedParent == parent.id) Icons.Rounded.KeyboardArrowDown
                                else Icons.Rounded.KeyboardArrowRight,
                                null,
                                tint = Sub,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (expandedParent == parent.id) {
                            val kids = children(parent.id)
                            if (kids.isEmpty()) {
                                Text(
                                    "（暂无二级分类，将记到「${parent.name}」）",
                                    fontSize = 11.5.sp,
                                    color = Sub,
                                    modifier = Modifier.padding(start = 42.dp, bottom = 8.dp)
                                )
                            }
                            kids.chunked(3).forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(start = 42.dp, bottom = 8.dp)) {
                                    row.forEach { k ->
                                        Surface(
                                            modifier = Modifier
                                                .padding(end = 8.dp)
                                                .clip(RoundedCornerShape(13.dp))
                                                .clickable { onPick(k.id); show = false },
                                            shape = RoundedCornerShape(13.dp),
                                            color = FieldBg
                                        ) {
                                            Text(
                                                k.name,
                                                fontSize = 12.sp,
                                                color = Sub2,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}
