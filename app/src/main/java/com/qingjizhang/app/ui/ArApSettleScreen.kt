package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.Account
import com.qingjizhang.app.data.ArAp
import com.qingjizhang.app.data.ArApDirection
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.KeySound
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.settleArAps
import com.qingjizhang.app.vm.writeOffArAps
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 收款 / 付款结算界面（N45 修正 2，参照「收款界面 / 付款界面」参考图）。
 *
 * 从应收应付列表点某条记录（或勾选多条后点「收款 / 付款」）进入。
 * - 顶部大号金额（可改，用于分批收款 / 抹零，差额与原因写进生成的账单备注）；
 * - 收款账户 / 收款日期 / 收款时间三行，点开分别是账户选择、日期、时间；
 * - 多选时提供「合并为一笔收款明细」开关：勾选 = 合成一笔账单，不勾 = 每笔各生成一笔；
 * - 底部数字键盘 + 确定。确定后在同一个事务里生成真实账单（进入主页账单流、计入余额）
 *   并把已结清的记录从应收应付列表移除（修正 3）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArApSettleScreen(
    vm: AppViewModel,
    items: List<ArAp>,
    accounts: List<Account>,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val receivable = items.firstOrNull()?.direction != ArApDirection.PAYABLE
    val verb = if (receivable) "收款" else "付款"
    val total = items.sumOf { (it.amountCents - it.settledCents).coerceAtLeast(0L) }

    // 结算界面是列表页上盖的一层全屏：系统返回键先关它，而不是直接退出到首页
    BackHandler { onBack() }

    // N37 / N39：键盘沿用记账键盘的按键音与振动反馈开关
    val soundOn by vm.keySound.collectAsState()
    val ding = rememberKeySound(soundOn)
    val vibrateOn by vm.vibrateFeedback.collectAsState()
    val tap = rememberKeyHaptics(vibrateOn)

    var amountText by remember { mutableStateOf(Money.text(total)) }
    var reason by remember { mutableStateOf("") }
    var merge by remember { mutableStateOf(true) }
    var dateTime by remember { mutableStateOf(LocalDateTime.now()) }
    val selectable = remember(accounts) { accounts.filter { !it.hidden } }
    var accountId by remember(selectable) {
        mutableStateOf(
            items.firstOrNull { it.accountId != 0L }?.accountId
                ?.takeIf { id -> selectable.any { it.id == id } }
                ?: selectable.firstOrNull()?.id ?: 0L
        )
    }

    var showAccountPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    // F53：部分收 / 付款之后主动询问是否放弃剩余余额（销账）
    var showWriteOff by remember { mutableStateOf(false) }
    var writeOffLeft by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val cents = Money.parse(amountText.ifBlank { "0" }) ?: 0L
    val multi = items.size > 1
    val effectiveCents = if (multi && !merge) total else cents
    val account = selectable.firstOrNull { it.id == accountId }
    val canConfirm = effectiveCents > 0L && accountId != 0L && !busy

    if (showWriteOff) {
        ConfirmDialog(
            title = "放弃剩余余额？",
            text = "本次" + verb + "后还剩 " + Money.withSymbol(writeOffLeft) + "没有" +
                (if (receivable) "收" else "付") + "。放弃后这条记录会从应收应付列表移除，" +
                "不会再生成账单；已经" + verb + "的部分不受影响。",
            confirmText = "放弃剩余（销账）",
            danger = true,
            onConfirm = {
                showWriteOff = false
                vm.writeOffArAps(items)
                onDone()
            },
            onDismiss = {
                // 不放弃就保留剩余金额，记录继续留在列表里
                showWriteOff = false
                onDone()
            }
        )
    }

    if (showAccountPicker) {
        AccountPickDialog(
            accounts = selectable,
            selectedId = accountId,
            onPick = { accountId = it; showAccountPicker = false },
            onDismiss = { showAccountPicker = false }
        )
    }
    if (showDatePicker) {
        DatePickDialog(
            initial = dateTime.toLocalDate(),
            onConfirm = { dateTime = LocalDateTime.of(it, dateTime.toLocalTime()); showDatePicker = false },
            onDismiss = { showDatePicker = false }
        )
    }
    if (showTimePicker) {
        TimePickDialog(
            initial = dateTime.toLocalTime(),
            onConfirm = { dateTime = LocalDateTime.of(dateTime.toLocalDate(), it); showTimePicker = false },
            onDismiss = { showTimePicker = false }
        )
    }

    /** 键盘按键：数字 / 小数点 / 退格 / 清空 */
    fun press(key: String) {
        ding(KeySound.Scene.AMOUNT)
        tap()
        when (key) {
            "⌫" -> amountText = amountText.dropLast(1)
            "清空" -> amountText = ""
            "." -> if (!amountText.contains('.')) {
                amountText = (if (amountText.isBlank()) "0" else amountText) + "."
            }
            else -> {
                val dot = amountText.indexOf('.')
                val overDecimal = dot >= 0 && amountText.length - dot > 2
                if (!overDecimal && amountText.length < 12) {
                    amountText = if (amountText == "0") key else amountText + key
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalKeyHaptic provides tap,
        LocalKeySound provides ding
    ) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(title = verb, onBack = onBack)

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${verb}金额",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    color = Sub2
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (amountText.isBlank()) "0.00" else amountText,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (multi && !merge) Sub else Ink
                )
                if (multi) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "共 ${items.size} 笔 · " + (if (receivable) "应收" else "应付") +
                            "合计 " + Money.withSymbol(total),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        color = Sub2
                    )
                }
                Spacer(Modifier.height(18.dp))

                // 账户 / 日期 / 时间
                CardBox(modifier = Modifier.padding(horizontal = 14.dp)) {
                    SettleRow("${verb}账户", account?.let { accountLabel(it) } ?: "请选择") {
                        showAccountPicker = true
                    }
                    RowDivider()
                    SettleRow(if (receivable) "收款日期" else "付款日期", DateFmt.isoDate(DateFmt.toMillis(dateTime))) {
                        showDatePicker = true
                    }
                    RowDivider()
                    SettleRow(if (receivable) "收款时间" else "付款时间", DateFmt.time(DateFmt.toMillis(dateTime))) {
                        showTimePicker = true
                    }
                }
                Spacer(Modifier.height(14.dp))

                if (multi) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = merge, onCheckedChange = { merge = it })
                        Text("合并为一笔$verb" + "明细", fontSize = 13.5.sp, color = Ink)
                    }
                }
                // 金额与合计不一致（抹零 / 分批收付）时，把差额原因写进账单备注
                if (merge && cents != total) {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("差额原因（写进账单备注）") },
                        placeholder = { Text("如：抹零 / 部分先收") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                    Text(
                        if (cents < total) {
                            // 少收 / 少付：按时间先后分批结清，剩余部分可以再结算，或者直接销账
                            "少于合计 " + Money.withSymbol(total - cents) + "：按记录时间先后分批结清，" +
                                "剩余仍" + (if (receivable) "待收" else "待付") + "，之后可继续结算或销账"
                        } else {
                            // 多收 / 多付：多出来的部分会一并计入这笔账单
                            "多于合计 " + Money.withSymbol(cents - total) + "：多出的部分会一并计入这笔账单"
                        },
                        fontSize = 11.5.sp,
                        color = Orange,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
                    )
                }
                if (selectable.isEmpty()) {
                    Text(
                        "本账本还没有账户，请先在「账户」页创建",
                        fontSize = 12.sp,
                        color = WarnFg,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // 数字键盘 + 确定
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(FieldBgAlt)
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                listOf(
                    listOf("1", "2", "3", "⌫"),
                    listOf("4", "5", "6", "清空"),
                    listOf("7", "8", "9", ".")
                ).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { key ->
                            KeypadButton(key, Modifier.weight(1f)) { press(key) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    KeypadButton("0", Modifier.weight(1f)) { press("0") }
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        if (!canConfirm) return@Button
                        busy = true
                        ding(KeySound.Scene.SAVE)
                        tap()
                        scope.launch {
                            val ok = vm.settleArAps(
                                items = items,
                                accountId = accountId,
                                actualCents = effectiveCents,
                                atMillis = DateFmt.toMillis(dateTime),
                                reason = reason,
                                merge = merge
                            )
                            busy = false
                            if (!ok) return@launch
                            // F53：没结清的（少收 / 少付）主动问一句要不要放弃剩余余额
                            val left = if (merge) (total - effectiveCents).coerceAtLeast(0L) else 0L
                            if (left > 0L) {
                                writeOffLeft = left
                                showWriteOff = true
                            } else {
                                onDone()
                            }
                        }
                    },
                    enabled = canConfirm,
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("确定", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 结算页的一行：左侧标签，右侧值 + 箭头 */
@Composable
private fun SettleRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = Ink)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = Sub, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Chevron,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 数字键盘按钮 */
@Composable
private fun KeypadButton(key: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val isBackspace = key == "⌫"
    Box(
        modifier = modifier
            .padding(3.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isBackspace) PrimaryBg else CardWhite)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isBackspace) {
            Icon(Icons.Rounded.Backspace, contentDescription = "退格", tint = Primary, modifier = Modifier.size(20.dp))
        } else {
            Text(key, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        }
    }
}

/** 收款 / 付款账户选择 */
@Composable
private fun AccountPickDialog(
    accounts: List<Account>,
    selectedId: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        shape = RoundedCornerShape(18.dp),
        title = { Text("选择账户", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                accounts.forEachIndexed { index, acc ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(acc.id) }
                            .padding(horizontal = 6.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            accountLabel(acc),
                            fontSize = 14.sp,
                            fontWeight = if (acc.id == selectedId) FontWeight.Bold else FontWeight.Normal,
                            color = if (acc.id == selectedId) Primary else Ink
                        )
                        Spacer(Modifier.weight(1f))
                        if (acc.id == selectedId) {
                            Text("已选", fontSize = 11.5.sp, color = Primary)
                        }
                    }
                    if (index != accounts.lastIndex) RowDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Sub) }
        }
    )
}

/** 日期选择（只改日期，时间保持不变） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickDialog(
    initial: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val zone = java.time.ZoneId.systemDefault()
    val state = rememberDatePickerState(initialSelectedDateMillis = DateFmt.toMillis(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = DatePickerDefaults.colors(containerColor = CardWhite),
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis
                    ?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                    ?: initial
                onConfirm(picked)
            }) { Text("确定", color = Primary, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Sub) } }
    ) {
        DatePicker(state = state, colors = DatePickerDefaults.colors(containerColor = CardWhite))
    }
}

/** 时间选择（只改时分，日期保持不变） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = { Text("选择时间", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        timeSelectorSelectedContainerColor = Primary,
                        timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
                        timeSelectorUnselectedContainerColor = PrimaryBg
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                Text("确定", color = Primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Sub) } }
    )
}
