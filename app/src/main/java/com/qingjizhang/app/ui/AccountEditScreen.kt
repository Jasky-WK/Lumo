package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.Account
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.addAccountType
import com.qingjizhang.app.vm.adjustAccountBalance
import com.qingjizhang.app.vm.balanceOfAccount
import com.qingjizhang.app.vm.deleteAccount
import com.qingjizhang.app.vm.saveAccount
import com.qingjizhang.app.vm.toast
import com.qingjizhang.app.vm.txnCountOfAccount

/**
 * 编辑账户：
 * - 账户备注（浅灰小字显示在名称后面）
 * - 账户类型
 * - 适用于哪些账本（可多选）
 * - 是否计入净资产 / 是否隐藏
 * - 设置账户余额（N31）：确认弹窗底部可选「是否生成账单记录」，默认关闭 ——
 *   关闭时只调整余额（不生成任何账单），开启时按差额生成一笔「漏记款 · 余额调整」账单
 */
@Composable
fun AccountEditScreen(
    vm: AppViewModel,
    accountId: Long,
    onBack: () -> Unit
) {
    val accounts by vm.accounts.collectAsState()
    val ledgers by vm.ledgers.collectAsState()
    val ledgerId by vm.currentLedgerId.collectAsState()
    // 账户类型（N19）：按账本独立，这里读当前账本的类型列表
    val typeList by vm.accountTypes.collectAsState()
    val isNew = accountId <= 0L
    val existing = accounts.firstOrNull { it.id == accountId }
    val account = existing ?: Account(ledgerId = ledgerId, name = "", sortOrder = accounts.size)

    var name by remember(account) { mutableStateOf(if (isNew) "" else account.name) }
    var note by remember(account) { mutableStateOf(if (isNew) "" else account.note) }
    var type by remember(account) { mutableStateOf(if (isNew) "" else account.type) }
    var inNetWorth by remember(account) { mutableStateOf(if (isNew) true else account.countInNetWorth) }
    var hidden by remember(account) { mutableStateOf(if (isNew) false else account.hidden) }
    var chosenLedgers by remember(account) {
        mutableStateOf(if (isNew) setOf(ledgerId) else account.ledgerIds.toSet())
    }
    var newBalanceText by remember(account) { mutableStateOf("") }
    // N24：新建账户时可直接填初始余额（默认 0），创建后余额 = 初始余额 + 流水净影响
    var initialBalanceText by remember(account) { mutableStateOf("") }
    var adjustNote by remember(account) { mutableStateOf("余额调整") }
    // N31：调整余额的确认弹窗 + 「是否生成账单记录」开关（需求要求默认关闭）
    var showAdjustConfirm by remember { mutableStateOf(false) }
    var generateTxnOnAdjust by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showNewType by remember { mutableStateOf(false) }
    var showTypeManager by remember { mutableStateOf(false) }
    // N52：信用卡还款日 / 还款前一天提醒（非信用卡账户不显示、也不参与保存）
    var creditDueDay by remember(account) { mutableStateOf(if (isNew) 0 else account.creditDueDay) }
    var creditRemind by remember(account) { mutableStateOf(if (isNew) false else account.creditRemind) }
    var showDueDayPicker by remember { mutableStateOf(false) }

    if (showDueDayPicker) {
        DueDayPickerDialog(
            selected = creditDueDay,
            onPick = { creditDueDay = it; showDueDayPicker = false },
            onDismiss = { showDueDayPicker = false }
        )
    }

    // 新建账户时默认落在第一个类型上（类型列表是异步来的，晚到也能补上）
    LaunchedEffect(typeList) {
        if (type.isBlank() && typeList.isNotEmpty()) type = typeList.first().name
    }

    // 可选类型名：当前类型不在列表里时也保留，避免编辑已有账户时被强行改类型
    val typeNames = typeList.map { it.name }.let { names ->
        if (type.isNotBlank() && type !in names) names + type else names
    }

    if (!isNew && existing == null) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar("账户", onBack)
            EmptyState(Icons.Rounded.SearchOff, "这个账户已经不存在了")
        }
        return
    }

    val currentBalance = vm.balanceOfAccount(account)
    val parsedBalance = newBalanceText.trim().replace(",", "").replace("¥", "")
        .toDoubleOrNull()?.let { Math.round(it * 100) }
    val diff = parsedBalance?.let { it - currentBalance }
    // N24：初始余额（元 → 分）。留空 / 非法输入按 0 处理
    val initialBalanceCents = initialBalanceText.trim().replace(",", "").replace("¥", "")
        .toDoubleOrNull()?.let { Math.round(it * 100) } ?: 0L

    if (showDelete) {
        // N35：删除前显示该账户下的账单数量，让用户选「保留账单」还是「删除账单」
        DeleteAccountDialog(
            accountName = account.name,
            txnCount = vm.txnCountOfAccount(account),
            onKeepTxns = {
                vm.deleteAccount(account, keepTxns = true)
                onBack()
            },
            onDeleteTxns = {
                vm.deleteAccount(account, keepTxns = false)
                onBack()
            },
            onDismiss = { showDelete = false }
        )
    }

    // N31：余额调整的确认弹窗，底部是「是否生成账单记录」开关（默认关闭）
    if (showAdjustConfirm && parsedBalance != null && diff != null && diff != 0L) {
        val up = diff > 0
        AlertDialog(
            onDismissRequest = { showAdjustConfirm = false },
            title = { Text("确认调整余额？", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "「${account.name}」 " + Money.withSymbol(currentBalance) +
                            " → " + Money.withSymbol(parsedBalance),
                        fontSize = 13.5.sp,
                        color = Ink,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "差额 " + (if (up) "+" else "-") + Money.withSymbol(Math.abs(diff)),
                        fontSize = 12.5.sp,
                        color = if (up) Income else Expense,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("是否生成账单记录", fontSize = 14.sp, color = Ink)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                if (generateTxnOnAdjust)
                                    "按差额记一笔「漏记款 · 余额调整」" + (if (up) "收入" else "支出") +
                                        "，计入报表与统计"
                                else
                                    "只调整余额，不生成任何账单，不影响报表 / 统计 / 预算",
                                fontSize = 11.sp,
                                color = Sub,
                                lineHeight = 16.sp
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        ToggleSwitch(checked = generateTxnOnAdjust, onChange = { generateTxnOnAdjust = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.adjustAccountBalance(account, parsedBalance, adjustNote, generateTxnOnAdjust)
                    newBalanceText = ""
                    showAdjustConfirm = false
                }) {
                    Text("确认调整", color = Primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdjustConfirm = false }) { Text("取消", color = Sub) }
            }
        )
    }

    if (showNewType) {
        InputDialog(
            title = "新建账户类型",
            initial = "",
            label = "类型名称",
            onConfirm = {
                val n = it.trim()
                if (n.isNotEmpty()) {
                    vm.addAccountType(n)
                    // 建好直接选中，省得再点一次
                    type = n
                }
            },
            onDismiss = { showNewType = false }
        )
    }

    if (showTypeManager) {
        AccountTypeManagerSheet(vm) { showTypeManager = false }
    }

    Column(
        Modifier
            .fillMaxSize()
            // 键盘弹出时整体让出键盘高度，配合 verticalScroll 保证「设置账户余额」等输入框不被挡住
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        BackTopBar(
            title = if (isNew) "添加账户" else "编辑账户",
            onBack = onBack,
            actions = {
                if (!isNew) {
                    IconAction(Icons.Rounded.DeleteOutline, { showDelete = true }, tint = Expense)
                }
            }
        )
        Column(Modifier.padding(horizontal = 14.dp)) {

            // 名称 + 备注预览
            CardBox {
                Column(Modifier.padding(16.dp)) {
                    Text("账户名称", fontSize = 11.5.sp, color = Sub, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.weight(1f, fill = false),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = Primary
                            )
                        )
                        if (note.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                note,
                                fontSize = 11.sp,
                                color = Sub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "备注会以浅灰小字显示在账户名后面，不影响列表阅读",
                        fontSize = 11.sp,
                        color = Sub
                    )
                }
            }

            SectionTitle("账户备注")
            SettingsGroup {
                ListInputRow(
                    label = "备注",
                    hint = "选填，如「工资卡」「已停用」",
                    value = note,
                    placeholder = "未填写",
                    onChange = { note = it }
                )
            }

            SectionTitle("账户类型")
            CardBox {
                Column(Modifier.padding(12.dp)) {
                    typeNames.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            row.forEach { t ->
                                val on = t == type
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 6.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { type = t },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (on) Primary else FieldBg
                                ) {
                                    Text(
                                        t,
                                        fontSize = 12.sp,
                                        color = if (on) Color.White else Sub2,
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(vertical = 9.dp)
                                    )
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .clickable { showNewType = true }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Add, null, tint = Primary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("新建类型", fontSize = 12.5.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .clickable { showTypeManager = true }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("管理类型", fontSize = 12.5.sp, color = Sub2)
                            Icon(
                                Icons.Rounded.KeyboardArrowRight,
                                null,
                                tint = Chevron,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "类型按账本独立，可自定义；没有账户的类型在账户页不显示",
                        fontSize = 11.sp,
                        color = Sub,
                        lineHeight = 16.sp
                    )
                }
            }

            // N52：只有信用卡类型的账户才显示还款日与提醒
            if (type.contains("信用")) {
                SectionTitle("信用卡还款")
                SettingsGroup {
                    SettingRow(
                        label = "每月还款日",
                        value = if (creditDueDay in 1..31) "$creditDueDay 号" else "未设置",
                        onClick = { showDueDayPicker = true }
                    )
                    RowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 15.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("还款日前一天提醒", fontSize = 14.sp, color = Ink)
                        Spacer(Modifier.weight(1f))
                        ToggleSwitch(checked = creditRemind, onChange = { creditRemind = it })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "开启后会在还款日的前一天早上 9 点发一条本地通知（不联网）；" +
                        "还款日遇到当月没有的日期（如 31 号）按当月最后一天算",
                    fontSize = 11.sp,
                    color = Sub,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            SectionTitle("适用于哪些账本", trailing = "可多选")
            CardBox {
                Column(Modifier.padding(12.dp)) {
                    ledgers.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            row.forEach { l ->
                                val on = chosenLedgers.contains(l.id)
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .clickable {
                                            chosenLedgers = if (on) chosenLedgers - l.id
                                            else chosenLedgers + l.id
                                        },
                                    shape = RoundedCornerShape(11.dp),
                                    color = if (on) Primary else FieldBg
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (on) Color.White else Chevron,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(7.dp))
                                        Text(
                                            l.name,
                                            fontSize = 12.5.sp,
                                            color = if (on) Color.White else Sub2,
                                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "勾选的账本里都能选到这个账户，账单与余额按账本各自统计",
                        fontSize = 11.sp,
                        color = Sub,
                        lineHeight = 16.sp
                    )
                }
            }

            // N24：新建账户时可直接填初始余额（默认 0），创建后余额立即按它生效
            if (isNew) {
                SectionTitle("初始余额")
                CardBox {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = initialBalanceText,
                            onValueChange = { initialBalanceText = it },
                            label = { Text("初始余额（元）") },
                            placeholder = { Text("默认 0.00") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "创建后账户余额 = 初始余额 + 之后的流水；不填默认为 0，也可以填负数（如欠款）。",
                            fontSize = 11.5.sp,
                            color = Sub,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            SectionTitle("统计与显示")
            SettingsGroup {
                SwitchRow(
                    title = "计入净资产",
                    subtitle = "关闭后这个账户的余额不计入净资产与总资产",
                    checked = inNetWorth,
                    onChange = { inNetWorth = it }
                )
                RowDivider()
                SwitchRow(
                    title = "隐藏账户",
                    subtitle = "隐藏后账户列表默认看不到；在账户页长按总览卡可临时显示",
                    checked = hidden,
                    onChange = { hidden = it }
                )
            }

            // 设置余额（新账户先创建，再设余额）
            if (!isNew) {
                SectionTitle("设置账户余额")
                CardBox {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("当前余额", fontSize = 12.5.sp, color = Sub)
                            Spacer(Modifier.width(6.dp))
                            AmountText(currentBalance, fontSize = 20.sp, color = Ink)
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = newBalanceText,
                            onValueChange = { newBalanceText = it },
                            label = { Text("改为实际余额（元）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (diff != null && diff != 0L) {
                            Spacer(Modifier.height(10.dp))
                            val up = diff > 0
                            TipBanner(
                                text = "差额 " + (if (up) "+" else "-") + Money.withSymbol(Math.abs(diff)) +
                                    "；默认只调整余额、不生成账单，也可在下一步选择生成「余额调整」账单",
                                icon = Icons.Rounded.ErrorOutline,
                                bg = WarnBg,
                                fg = Color(0xFFA9725A)
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = adjustNote,
                                onValueChange = { adjustNote = it },
                                label = { Text("生成账单时的备注（默认关闭生成）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    // 先弹确认弹窗，由用户在弹窗里决定是否生成账单
                                    generateTxnOnAdjust = false
                                    showAdjustConfirm = true
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = Primary
                            ) {
                                Text(
                                    "保存余额调整",
                                    textAlign = TextAlign.Center,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = OnPrimary,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                        } else {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "默认只把余额改成实际数字、不生成账单；想留一笔「余额调整」记录，可在确认弹窗里打开开关。",
                                fontSize = 11.5.sp,
                                color = Sub,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row {
                Surface(
                    modifier = Modifier.weight(1f).clickable { onBack() },
                    shape = RoundedCornerShape(14.dp),
                    color = CardWhite
                ) {
                    Text(
                        "取消",
                        textAlign = TextAlign.Center,
                        fontSize = 14.5.sp,
                        color = Sub2,
                        modifier = Modifier.padding(vertical = 13.dp)
                    )
                }
                Spacer(Modifier.width(11.dp))
                Surface(
                    modifier = Modifier.weight(1f).clickable {
                        if (name.isBlank()) {
                            vm.toast("请填写账户名称")
                        } else {
                            vm.saveAccount(
                                account.copy(
                                    name = name.trim(),
                                    note = note.trim(),
                                    type = type,
                                    countInNetWorth = inNetWorth,
                                    hidden = hidden,
                                    // N52：只有信用卡才保存还款日与提醒，其它类型一律清零
                                    creditDueDay = if (type.contains("信用")) creditDueDay else 0,
                                    creditRemind = if (type.contains("信用")) creditRemind else false,
                                    ledgerIdsCsv = if (chosenLedgers.isEmpty()) {
                                        account.ledgerId.toString()
                                    } else chosenLedgers.joinToString(","),
                                    // N24：新建时用填写的初始余额；编辑时保持原值不变
                                    initialBalanceCents = if (isNew) initialBalanceCents else account.initialBalanceCents
                                ),
                                isNew = isNew
                            )
                            onBack()
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = Primary
                ) {
                    Text(
                        if (isNew) "创建账户" else "保存账户",
                        textAlign = TextAlign.Center,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(vertical = 13.dp)
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun ListInputRow(
    label: String,
    hint: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(64.dp)) {
            Text(label, fontSize = 14.sp, color = Sub2)
        }
        TextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, fontSize = 13.sp, color = Muted) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            supportingText = { Text(hint, fontSize = 10.5.sp, color = Sub) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Ink,
                unfocusedTextColor = Ink,
                cursorColor = Primary
            ),
            textStyle = TextStyle(fontSize = 13.5.sp, textAlign = TextAlign.End)
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = Ink)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, fontSize = 11.sp, color = Sub, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        ToggleSwitch(checked = checked, onChange = onChange)
    }
}

/** N52：选择信用卡每月还款日（1~31，可取消设置） */
@Composable
private fun DueDayPickerDialog(
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        shape = RoundedCornerShape(18.dp),
        title = { Text("每月还款日", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPick(0) }
                        .padding(horizontal = 6.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "不设置",
                        fontSize = 14.sp,
                        fontWeight = if (selected !in 1..31) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected !in 1..31) Primary else Ink
                    )
                }
                RowDivider()
                (1..31).forEach { day ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(day) }
                            .padding(horizontal = 6.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$day 号",
                            fontSize = 14.sp,
                            fontWeight = if (selected == day) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected == day) Primary else Ink
                        )
                        Spacer(Modifier.weight(1f))
                        if (selected == day) Text("已选", fontSize = 11.5.sp, color = Primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消", color = Sub) } }
    )
}
