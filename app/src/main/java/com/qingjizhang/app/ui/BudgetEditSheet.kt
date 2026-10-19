package com.qingjizhang.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.Budget
import com.qingjizhang.app.data.BudgetDirection
import com.qingjizhang.app.data.Category
import com.qingjizhang.app.ui.components.CardBox
import com.qingjizhang.app.ui.components.IconBadge
import com.qingjizhang.app.ui.components.RowDivider
import com.qingjizhang.app.ui.components.SegSmall
import com.qingjizhang.app.ui.components.ToggleSwitch
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.saveBudget
import com.qingjizhang.app.vm.toast
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * 新建 / 编辑自定义预算面板（N30）。
 *
 * - 时间段默认「当前自然月」，并提供「本月 / 上月 / 最近 30 天」快捷；
 * - 事项可绑定分类 / 账户 / 关键词（都可选，不绑定即统计全部）；
 * - 口径可选支出（默认）/ 收入；
 * - [editing] 为 null 表示新建。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BudgetEditSheet(
    vm: AppViewModel,
    editing: Budget?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val ledgerId by vm.currentLedgerId.collectAsState()

    // 默认时间段 = 当前自然月（含首尾两天）
    val thisMonth = remember { DateFmt.monthRange(LocalDate.now()) }
    var name by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(DateFmt.dateOf(thisMonth.first)) }
    var endDate by remember { mutableStateOf(DateFmt.dateOf(thisMonth.second).minusDays(1)) }
    var amountText by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf(BudgetDirection.EXPENSE) }
    var categoryId by remember { mutableStateOf(0L) }
    var accountId by remember { mutableStateOf(0L) }
    var keyword by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var showsCategory by remember { mutableStateOf(false) }
    var showsAccount by remember { mutableStateOf(false) }

    LaunchedEffect(editing) {
        val e = editing ?: return@LaunchedEffect
        name = e.name
        startDate = DateFmt.dateOf(e.startMillis)
        endDate = DateFmt.dateOf(e.endMillis)
        amountText = Money.text(e.amountCents)
        direction = e.direction
        categoryId = e.categoryId
        accountId = e.accountId
        keyword = e.keyword
        enabled = e.enabled
    }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun save() {
        val cents = Money.parse(if (amountText.isBlank()) "0" else amountText) ?: 0L
        when {
            name.isBlank() -> vm.toast("请给这个预算事项起个名字")
            cents <= 0L -> vm.toast("请输入预算金额")
            endDate.isBefore(startDate) -> vm.toast("结束日期不能早于开始日期")
            else -> {
                val budget = Budget(
                    id = editing?.id ?: 0L,
                    ledgerId = editing?.ledgerId ?: ledgerId,
                    name = name.trim(),
                    startMillis = DateFmt.toMillis(startDate),
                    endMillis = DateFmt.toMillis(endDate),
                    amountCents = cents,
                    direction = direction,
                    categoryId = categoryId,
                    accountId = accountId,
                    keyword = keyword.trim(),
                    enabled = enabled,
                    createdAt = editing?.createdAt ?: System.currentTimeMillis()
                )
                vm.saveBudget(budget, isNew = editing == null)
                close()
            }
        }
    }

    val today = LocalDate.now()
    val lastMonth = today.minusMonths(1)
    val lastMonthRange = DateFmt.monthRange(lastMonth)
    val presets = listOf(
        "本月" to (DateFmt.dateOf(thisMonth.first) to DateFmt.dateOf(thisMonth.second).minusDays(1)),
        "上月" to (DateFmt.dateOf(lastMonthRange.first) to DateFmt.dateOf(lastMonthRange.second).minusDays(1)),
        "最近 30 天" to (today.minusDays(29) to today)
    )
    val dayCount = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1
    val catName = categories.firstOrNull { it.id == categoryId }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CardWhite
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Text(
                if (editing == null) "新建自定义预算" else "编辑自定义预算",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "为自定义事项设定时间段与金额上限；分类 / 账户 / 关键词都可以不绑定，不绑定就统计全部。",
                fontSize = 11.5.sp,
                color = Sub,
                lineHeight = 17.sp
            )

            Spacer(Modifier.height(12.dp))
            BudgetInputRow("事项名称", name, "如：旅游基金", { name = it })
            RowDivider()
            BudgetInputRow("预算金额", amountText, "元，如 3000", { amountText = it }, numberOnly = true)
            RowDivider()
            BudgetInputRow("关键词", keyword, "选填，匹配备注或对方", { keyword = it })

            Spacer(Modifier.height(14.dp))
            Text("时间段", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(6.dp))
            Row {
                presets.forEach { (label, range) ->
                    Surface(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                startDate = range.first
                                endDate = range.second
                            },
                        shape = RoundedCornerShape(20.dp),
                        color = PrimaryBg
                    ) {
                        Text(
                            label,
                            fontSize = 12.5.sp,
                            color = Primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            BudgetDateRow("开始日期", startDate) { startDate = it }
            RowDivider()
            BudgetDateRow("结束日期", endDate) { endDate = it }
            Spacer(Modifier.height(6.dp))
            Text(
                "含首尾两天，共 ${if (dayCount > 0) dayCount else 0} 天",
                fontSize = 11.5.sp,
                color = Sub
            )

            Spacer(Modifier.height(14.dp))
            Text("统计口径", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(6.dp))
            SegSmall(
                listOf("支出", "收入"),
                if (direction == BudgetDirection.INCOME) 1 else 0
            ) { direction = if (it == 1) BudgetDirection.INCOME else BudgetDirection.EXPENSE }

            Spacer(Modifier.height(14.dp))
            Text("绑定条件（可选）", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
            CardBox {
                Column {
                    BudgetPickerRow("绑定分类", catName?.let { catLabel(categories, it) } ?: "不限") {
                        showsCategory = true
                    }
                    RowDivider()
                    BudgetPickerRow(
                        "绑定账户",
                        accounts.firstOrNull { it.id == accountId }?.name ?: "不限"
                    ) { showsAccount = true }
                }
            }

            Spacer(Modifier.height(14.dp))
            CardBox {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 15.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启用这个预算", fontSize = 14.sp, color = Ink)
                        Spacer(Modifier.height(3.dp))
                        Text("关闭 = 暂停，定义与历史统计都保留", fontSize = 11.sp, color = Sub)
                    }
                    ToggleSwitch(checked = enabled, onChange = { enabled = it })
                }
            }

            Spacer(Modifier.height(16.dp))
            Row {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { close() },
                    shape = RoundedCornerShape(14.dp),
                    color = FieldBg
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
                        .clickable { save() },
                    shape = RoundedCornerShape(14.dp),
                    color = Primary
                ) {
                    Text(
                        "保存",
                        modifier = Modifier.padding(vertical = 13.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }

    // 选择弹窗放在面板外层（与账户类型面板同一套写法），避免与底部面板的手势抢焦点
    if (showsCategory) {
        BudgetOptionDialog(
            title = "绑定分类",
            emptyLabel = "不限（统计全部分类的账）",
            options = categories.filter { !it.hidden }.map { it.id to catLabel(categories, it) },
            current = categoryId,
            onPick = { categoryId = it; showsCategory = false },
            onDismiss = { showsCategory = false }
        )
    }
    if (showsAccount) {
        BudgetOptionDialog(
            title = "绑定账户",
            emptyLabel = "不限（统计全部账户的账）",
            options = accounts.map { it.id to it.name },
            current = accountId,
            onPick = { accountId = it; showsAccount = false },
            onDismiss = { showsAccount = false }
        )
    }
}

/** 分类展示名：二级分类带上所属一级，避免同名分不清 */
private fun catLabel(categories: List<Category>, c: Category): String =
    if (c.parentId == 0L) c.name
    else (categories.firstOrNull { it.id == c.parentId }?.name ?: "") + " / " + c.name

/** 「不限 / 选一个」的通用选择弹窗；[onPick] 传 0 表示不限 */
@Composable
private fun BudgetOptionDialog(
    title: String,
    emptyLabel: String,
    options: List<Pair<Long, String>>,
    current: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = Primary) } },
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OptionRow(emptyLabel, current == 0L) { onPick(0L) }
                options.forEach { (id, label) ->
                    OptionRow(label, current == id) { onPick(id) }
                }
                if (options.isEmpty()) {
                    Text(
                        "暂无可选项，可以先在「分类管理」里新建",
                        fontSize = 11.5.sp,
                        color = Sub,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            IconBadge(Icons.Rounded.Check, Primary, size = 26.dp, iconSize = 15.dp, radius = 9.dp)
        } else {
            Spacer(Modifier.width(26.dp))
        }
        Spacer(Modifier.width(9.dp))
        Text(
            label,
            fontSize = 14.sp,
            color = if (selected) Primary else Ink,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun BudgetInputRow(
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
                keyboardType = if (numberOnly) androidx.compose.ui.text.input.KeyboardType.Decimal
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
private fun BudgetPickerRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.5.sp, color = Sub2)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.KeyboardArrowRight, null, tint = Chevron, modifier = Modifier.size(17.dp))
    }
}

/** 日期选择行：用系统日期选择器（与周期记账同一套交互，不跨文件引用私有组件） */
@Composable
private fun BudgetDateRow(label: String, date: LocalDate, onPick: (LocalDate) -> Unit) {
    val context = LocalContext.current
    BudgetPickerRow(label, DateFmt.isoDate(DateFmt.toMillis(date))) {
        android.app.DatePickerDialog(
            context,
            { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth
        ).show()
    }
}
