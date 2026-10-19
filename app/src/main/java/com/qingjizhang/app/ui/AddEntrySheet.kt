package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.*
import com.qingjizhang.app.data.Direction
import com.qingjizhang.app.ui.components.IconBadge
import com.qingjizhang.app.ui.components.LocalKeyHaptic
import com.qingjizhang.app.ui.components.LocalKeySound
import com.qingjizhang.app.ui.components.RowDivider
import com.qingjizhang.app.ui.components.TextBadge
import com.qingjizhang.app.ui.components.TextInputSheet
import com.qingjizhang.app.ui.components.rememberKeyHaptics
import com.qingjizhang.app.ui.components.rememberKeySound
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.KeySound
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.ensureLoanAccount
import com.qingjizhang.app.vm.rememberAccountFor
import com.qingjizhang.app.vm.saveArAp
import com.qingjizhang.app.vm.saveTxn
import com.qingjizhang.app.vm.saveTxnAndContinue
import com.qingjizhang.app.vm.setNoteMemoryEnabled
import com.qingjizhang.app.vm.suggestionFor
import com.qingjizhang.app.vm.tagIdsOfTxn
import com.qingjizhang.app.vm.toast
import com.qingjizhang.app.vm.updateArAp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class AddSheetRequest(
    val editing: Txn? = null,
    val initialType: TxnType = TxnType.EXPENSE,
    /** 预选分类（N34：桌面「模板记账」小组件点某个模板直接带进来）；0 = 不指定 */
    val initialCategoryId: Long = 0L,
    /**
     * 预置为应收款 / 应付款（N46 / 修正 2）：从应收应付列表点「＋」进来时带上，
     * 页面与记一笔完全一致，只是保存时写进应收应付表而不是账单表。
     */
    val initialArAp: ArApDirection? = null,
    /** v2.8.0 一句话记账：预填金额（分）/ 备注 / 分类 / 账户 / 日期，0 = 不预填 */
    val initAmountCents: Long = 0L,
    val initNote: String = "",
    val initCategoryId: Long = 0L,
    val initAccountId: Long = 0L,
    val initDateMillis: Long = 0L,
    /** F53：编辑一条已有的应收 / 应付记录（复用记一笔界面，保存走更新而不是新增） */
    val editingArAp: ArAp? = null
)

/** M5：记一笔里需要文本输入窗口的三个字段，统一走同一个公共组件 */
private enum class TextFieldKind { NOTE, COUNTERPARTY, REFUND, FEE, DISCOUNT }

private val TABS = listOf(TxnType.EXPENSE, TxnType.INCOME, TxnType.TRANSFER, TxnType.LEND_BORROW)

/** N40：手续费 / 优惠输入窗口里的常用金额候选（点一下直接填入，也可以手输任意值） */
private val QUICK_FEE = listOf("0.5", "1", "2", "5", "10", "20")
private val QUICK_DISCOUNT = listOf("1", "2", "5", "10", "20", "50")

/**
 * 记一笔面板（N14 重布局）：
 * 从上到下 = 类型 Tab →「分类 + 金额」一行 → 可展开的紧凑分类胶囊 → 付款账户一行 →
 * 备注一行 → 属性胶囊行（日期 / 时间 / 退款 / 口径）→ 固定在底部的记账键盘。
 * 上部内容区 weight(1f) + 滚动，键盘固定高度贴底，所以「选分类 → 输金额 → 保存」不需要下滑。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun AddEntrySheet(
    vm: com.qingjizhang.app.vm.AppViewModel,
    request: AddSheetRequest,
    onDismiss: () -> Unit
) {
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val ledgerId by vm.currentLedgerId.collectAsState()
    // 账户选择器按类型分组，分组顺序跟账户页同一份类型列表（N12 / N19）
    val accountTypes by vm.accountTypes.collectAsState()
    // 账户选择器要显示真实余额：复用账户页同一个实时聚合结果（初始余额 + 流水净影响），
    // 记账后余额会自动跟着变化，不另算一套口径
    val balances by vm.balances.collectAsState()
    val balanceOf = remember(balances) { balances.associate { it.account.id to it.balanceCents } }
    // 备注记忆（N22）：候选来自当前账本的历史备注，开关默认开启
    val noteMemoryOn by vm.noteMemoryEnabled.collectAsState()
    val noteHistory by vm.noteHistory.collectAsState()
    // 互转类型胶囊（N26）：与账户类型同构，按账本独立
    val transferKinds by vm.transferKinds.collectAsState()
    // N37：振动反馈（设置里可关）。只在记账键盘按键、切换分类/账户这类关键操作上轻振一下
    val vibrateOn by vm.vibrateFeedback.collectAsState()
    val tap = rememberKeyHaptics(vibrateOn)
    // N39：按键音（设置里可关，与振动相互独立）。走媒体流，手机静音也照常响
    val soundOn by vm.keySound.collectAsState()
    val ding = rememberKeySound(soundOn)
    val scope = rememberCoroutineScope()

    // 编辑已有账单时是「改」，点「再记」保存完这一笔后切成连续新增，所以用可变状态
    var editing by remember { mutableStateOf(request.editing) }
    // F53：编辑已有的应收 / 应付记录（与编辑账单共用下面这套状态初始化）
    val editingArAp = request.editingArAp

    var type by remember {
        mutableStateOf(
            request.editing?.type
                ?: editingArAp?.let {
                    if (it.direction == ArApDirection.RECEIVABLE) TxnType.INCOME else TxnType.EXPENSE
                }
                ?: if (request.initialArAp == ArApDirection.RECEIVABLE) TxnType.INCOME
                else if (request.initialArAp == ArApDirection.PAYABLE) TxnType.EXPENSE
                else request.initialType
        )
    }
    val initAmountText = request.editing?.let { Money.text(it.amountCents) }
        ?: editingArAp?.let { Money.text(it.amountCents) }
        ?: ""
    var amountText by remember {
        mutableStateOf(
            if (request.initAmountCents > 0L) Money.text(request.initAmountCents) else initAmountText
        )
    }
    // F18：修改已有账单时，金额框会带出旧金额，用户想直接输新数字却得先手动删干净。
    // 这个标记表示「旧金额待整体替换」：进入编辑态且金额非空时置 true，
    // 首次点记账键盘的数字键（含小数点）就用新数字整体替换旧值并清标记，之后按数字正常追加。
    // 用户没点数字键（退格、切分类、切类型、开关计算器、保存等）也会把标记清掉，旧金额原样保留。
    var amountReplacePending by remember { mutableStateOf(initAmountText.isNotBlank()) }
    var dateTime by remember {
        mutableStateOf(
            request.editing?.let { DateFmt.dateTimeOf(it.dateMillis) }
                ?: editingArAp?.let { DateFmt.dateTimeOf(it.dateMillis) }
                ?: LocalDateTime.now()
        )
    }
    var note by remember {
        mutableStateOf(request.editing?.note ?: editingArAp?.note ?: request.initNote)
    }
    var counterparty by remember { mutableStateOf(request.editing?.counterparty ?: "") }
    var direction by remember { mutableStateOf(request.editing?.direction ?: Direction.LEND) }
    // v2.8.0：一句话记账预填的账户（没识别到就还是 0，由用户选）
    var accountId by remember {
        mutableStateOf(request.editing?.accountId ?: editingArAp?.accountId ?: request.initAccountId)
    }
    var toAccountId by remember { mutableStateOf(request.editing?.transferToAccountId ?: 0L) }
    // 借贷的另一端：借贷账户（借出记应收，借入记负债）
    var loanAccountId by remember {
        mutableStateOf(
            request.editing?.takeIf { it.type == TxnType.LEND_BORROW }?.transferToAccountId ?: 0L
        )
    }
    var parentId by remember { mutableStateOf(0L) }
    var childId by remember { mutableStateOf(0L) }
    // v2.8.0：一句话记账预填的分类（initCategoryId 可能是父分类也可能是子分类）
    LaunchedEffect(request) {
        if (request.editing == null && request.editingArAp == null && request.initCategoryId > 0L) {
            val c = categories.firstOrNull { it.id == request.initCategoryId }
            if (c != null) {
                if (c.parentId == 0L) {
                    parentId = c.id
                    childId = categories.firstOrNull { it.parentId == c.id && !it.hidden }?.id ?: 0L
                } else {
                    parentId = c.parentId
                    childId = c.id
                }
            }
        }
    }
    // 统计口径：不计入收支（只改余额、不进统计）/ 不计入预算
    var excludeStats by remember { mutableStateOf(request.editing?.excludeFromStats ?: false) }
    var excludeBudget by remember { mutableStateOf(request.editing?.excludeFromBudget ?: false) }
    // 报销 / 退款（实计金额 = 原价 − 报销退款），只有支出 / 收入用得上
    var refundText by remember {
        mutableStateOf(
            request.editing?.takeIf { it.refundCents > 0L }?.let { Money.text(it.refundCents) } ?: ""
        )
    }
    // 手续费 / 优惠（N40）：实计金额 = 原价 − 报销退款 − 优惠 + 手续费，只有支出 / 收入用得上
    var feeText by remember {
        mutableStateOf(
            request.editing?.takeIf { it.feeCents > 0L }?.let { Money.text(it.feeCents) } ?: ""
        )
    }
    var discountText by remember {
        mutableStateOf(
            request.editing?.takeIf { it.discountCents > 0L }?.let { Money.text(it.discountCents) } ?: ""
        )
    }
    // 计算器：可以帮用户算好再填入金额
    var calcMode by remember { mutableStateOf(false) }
    var calcExpr by remember { mutableStateOf("") }
    // 文本输入统一走公共组件（M5）：这里只记「当前开的是哪个字段」，草稿由组件自己持有
    var textField by remember { mutableStateOf<TextFieldKind?>(null) }
    // 唯一的「文本输入中」状态源：记账键盘的显隐只由它驱动（F13）
    var textInputActive by remember { mutableStateOf(false) }
    // 备注窗口右上角齿轮：备注相关设置（目前是「备注记忆」开关）
    var showNoteSettings by remember { mutableStateOf(false) }
    // 分类胶囊默认展开（记一笔的第一步通常就是选分类），点分类行可收起腾出空间
    var catsExpanded by remember { mutableStateOf(true) }
    // 互转子类型（N26）：互转 / 还款 / 理财买入 / 理财赎回，或用户自定义
    var transferKind by remember { mutableStateOf(request.editing?.transferKind ?: TransferKind.COMMON) }
    var showKindManager by remember { mutableStateOf(false) }
    // 标签（N38）：当前账单选中的标签 id（可多个），及标签选择界面开关
    var tagIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    // v2.8.0：标签开关（关掉后不显示标签行）
    val advTags by vm.advTags.collectAsState()
    // F51：应收 / 应付胶囊三档——不选中 / 应收款 / 应付款。
    // 保存走备忘表（receivables），不写账单表 → 不进主页账单流、不计余额
    var arApMode by remember { mutableStateOf(editingArAp?.direction ?: request.initialArAp) }
    /**
     * 应收 / 应付只对应收入 / 支出（别人欠我 = 收入侧、我欠别人 = 支出侧），
     * 所以选了应收 / 应付款时隐藏互转 / 借贷两个 Tab，避免出现语义矛盾的组合。
     */
    val visibleTabs = if (arApMode != null) listOf(TxnType.EXPENSE, TxnType.INCOME) else TABS
    // F51：胶囊与收支方向始终保持一致——应收款 ↔ 收入、应付款 ↔ 支出。
    // 胶囊点选时会主动切收支 Tab，用户手动切收支 Tab 时也把胶囊跟过去，两个方向都不会互相打架。
    LaunchedEffect(type) {
        if (arApMode != null) {
            val want = if (type == TxnType.INCOME) ArApDirection.RECEIVABLE else ArApDirection.PAYABLE
            if (arApMode != want) arApMode = want
        }
    }
    var showTagPicker by remember { mutableStateOf(false) }
    // 日期 / 时间 / 口径三个小弹窗（退款改成走公共文本输入窗口了）
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showCaliber by remember { mutableStateOf(false) }

    // P0-1：记账建议——用户手动动过分类 / 已采用过建议，本笔就不再提示
    var catTouched by remember { mutableStateOf(false) }
    var suggestionUsed by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val softwareKeyboard = LocalSoftwareKeyboardController.current

    /** 结束文字输入：清焦点 + 收起系统输入法（点日期 / 退款这类胶囊前先收干净） */
    fun exitTextInput() {
        focusManager.clearFocus()
        softwareKeyboard?.hide()
    }

    /**
     * 打开某个文本输入窗口前，先把这块面板自己的输入法状态收干净；
     * 真正的草稿、回填、IME 垫高都交给 [TextInputSheet]（M5 公共组件）。
     */
    fun openTextField(kind: TextFieldKind) {
        exitTextInput()
        textField = kind
    }

    /**
     * 备注记忆候选（N22）：按备注窗口里的实时输入匹配（只要包含关键词就算），
     * 去重后按「最近使用 → 使用次数」取前 5 条；输入为空或关掉备注记忆时不出现。
     */
    fun noteCandidatesOf(query: String): List<String> {
        if (!noteMemoryOn || query.isBlank()) return emptyList()
        return noteHistory.filter { it != query && it.contains(query, ignoreCase = true) }.take(5)
    }

    // 记一笔面板自己读到的输入法高度，交给文本输入窗口当兜底通道（F12）
    val hostImeHeight = with(LocalDensity.current) {
        WindowInsets.ime.getBottom(this).toDp()
    }

    LaunchedEffect(accounts) {
        if (accountId == 0L) accountId = accounts.firstOrNull()?.id ?: 0L
        if (toAccountId == 0L) {
            toAccountId = accounts.firstOrNull { it.id != accountId }?.id ?: accountId
        }
    }

    // 借贷必须有借贷账户：优先沿用编辑时的那一个，否则取账本里的借贷账户，都没有就自动建一个
    LaunchedEffect(accounts, type) {
        if (type != TxnType.LEND_BORROW) return@LaunchedEffect
        val loans = accounts.filter { it.type == AccountType.LEND }
        if (loanAccountId != 0L && loans.any { it.id == loanAccountId }) return@LaunchedEffect
        loanAccountId = loans.firstOrNull()?.id ?: vm.ensureLoanAccount()
    }

    // N34：模板记账小组件带进来的分类只应用一次
    var initialCatApplied by remember { mutableStateOf(false) }

    LaunchedEffect(categories, type) {
        if (type != TxnType.EXPENSE && type != TxnType.INCOME) return@LaunchedEffect
        val catType = if (type == TxnType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
        // 默认落到第一个「未隐藏」的分类上（N27）
        val level1 = categories.filter { it.type == catType && it.parentId == 0L && !it.hidden }
        // N34：桌面「模板记账」小组件指定了分类 -> 直接选中它（同时把收支 Tab 切到对应口径）。
        // 只应用一次：之后用户自己改分类 / 切收支 Tab 不再被拉回来
        val picked = categories.firstOrNull { it.id == request.initialCategoryId }
        if (!initialCatApplied && picked != null && !picked.hidden) {
            initialCatApplied = true
            if (picked.type == CategoryType.INCOME && type != TxnType.INCOME) {
                type = TxnType.INCOME
            } else if (picked.type == CategoryType.EXPENSE && type != TxnType.EXPENSE) {
                type = TxnType.EXPENSE
            }
            parentId = if (picked.parentId == 0L) picked.id else picked.parentId
            childId = if (picked.parentId == 0L) 0L else picked.id
            return@LaunchedEffect
        }
        val editCatId = request.editing?.categoryId ?: request.editingArAp?.categoryId ?: 0L
        val editCat = categories.firstOrNull { it.id == editCatId }
        if (editCat != null && editCat.type == catType) {
            parentId = if (editCat.parentId == 0L) editCat.id else editCat.parentId
            childId = if (editCat.parentId == 0L) 0L else editCat.id
            return@LaunchedEffect
        }
        if (parentId == 0L || level1.none { it.id == parentId }) {
            val first = level1.firstOrNull()
            parentId = first?.id ?: 0L
            childId = categories.firstOrNull { it.parentId == parentId }?.id ?: 0L
        }
    }

    // P0-1：切收支类型后重新允许给建议（上一个类型的手动选择 / 已采用过的建议都不该影响新类型）
    LaunchedEffect(type) {
        catTouched = false
        suggestionUsed = false
    }

    // 账户自动带出（N4）：切到某个分类时，默认选这个分类上次用过的账户（编辑已有账单时不覆盖）
    LaunchedEffect(parentId, childId, type) {
        if (type != TxnType.EXPENSE && type != TxnType.INCOME) return@LaunchedEffect
        if (editing != null || request.editingArAp != null) return@LaunchedEffect
        val catId = if (childId != 0L) childId else parentId
        val remembered = vm.rememberAccountFor(catId)
        if (remembered != 0L && accounts.any { it.id == remembered }) accountId = remembered
    }

    // 编辑已有账单时回显它已选的标签（N38）
    LaunchedEffect(request.editing?.id) {
        val id = request.editing?.id ?: 0L
        if (id > 0L) tagIds = vm.tagIdsOfTxn(id)
    }

    val catType = if (type == TxnType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
    // 隐藏分类（N27）不出现在可选列表里；历史账单照旧（统计口径不变），随时可取消隐藏
    val level1 = categories.filter { it.type == catType && it.parentId == 0L && !it.hidden }
    val level2 = categories.filter { it.parentId == parentId && parentId != 0L && !it.hidden }
    /** 当前选中的分类（二级优先），分类行与图标都取它 */
    val selectedCat =
        if (childId != 0L) level2.firstOrNull { it.id == childId } else level1.firstOrNull { it.id == parentId }
    val cents = Money.parse(if (amountText.isBlank()) "0" else amountText) ?: 0L
    /** 只有收入 / 支出才涉及收支与预算口径 */
    val isIncomeType = type == TxnType.EXPENSE || type == TxnType.INCOME
    /** 报销 / 退款金额（不超过原价） */
    val refundCents = if (isIncomeType) {
        (Money.parse(refundText.ifBlank { "0" }) ?: 0L).coerceIn(0L, cents)
    } else 0L
    /** 手续费（N40）：不封顶，实际支出要加上它 */
    val feeCents =
        if (isIncomeType) (Money.parse(feeText.ifBlank { "0" }) ?: 0L).coerceAtLeast(0L) else 0L
    /** 优惠（N40）：不超过原价，实际支出要减去它 */
    val discountCents = if (isIncomeType) {
        (Money.parse(discountText.ifBlank { "0" }) ?: 0L).coerceIn(0L, cents)
    } else 0L
    /** 实际金额（N40 口径，与 Repository.effectiveCents 一致）：金额 − 报销退款 − 优惠 + 手续费 */
    val actualCents = (cents - refundCents - discountCents + feeCents).coerceAtLeast(0L)

    /**
     * P0-1：记账建议。只在「新建 + 收入/支出 + 用户没自己选过分类 + 没采用过建议」时算，
     * 输入变化（备注 / 金额）会重算；建议只提示、不自动改数据。
     */
    val suggestion = remember(type, note, amountText, catTouched, suggestionUsed, editing, arApMode) {
        if (editing != null || request.editingArAp != null || catTouched || suggestionUsed) {
            null
        } else {
            vm.suggestionFor(type, note, cents)
        }
    }

    val catLabel = when (type) {
        TxnType.EXPENSE, TxnType.INCOME -> selectedCat?.name ?: "请选择分类"
        TxnType.TRANSFER -> "账户互转"
        TxnType.LEND_BORROW -> if (direction == Direction.LEND) "借出" else "借入"
    }
    val catIcon = when (type) {
        TxnType.TRANSFER -> Icons.Rounded.SwapHoriz
        TxnType.LEND_BORROW ->
            if (direction == Direction.LEND) Icons.Rounded.UploadFile else Icons.Rounded.Download
        else -> categoryIcon(selectedCat) ?: Icons.Rounded.Label
    }
    val catColor = when (type) {
        TxnType.TRANSFER -> Primary
        TxnType.LEND_BORROW -> if (direction == Direction.LEND) Expense else Income
        else -> categoryColor(selectedCat)
    }
    // 金额配色：支出红、收入绿，互转走中性色
    val amountColor = when (type) {
        TxnType.INCOME -> Income
        TxnType.EXPENSE -> Expense
        TxnType.LEND_BORROW -> if (direction == Direction.LEND) Expense else Income
        else -> Ink
    }

    val today = LocalDate.now()
    val pickedDate = dateTime.toLocalDate()
    val weekText = "周" + "一二三四五六日"[pickedDate.dayOfWeek.value - 1]
    val dateChipText = when {
        pickedDate == today -> "今天 $weekText"
        pickedDate == today.minusDays(1) -> "昨天 $weekText"
        pickedDate.year == today.year -> "${pickedDate.monthValue}月${pickedDate.dayOfMonth}日 $weekText"
        else -> "${pickedDate.year}年${pickedDate.monthValue}月${pickedDate.dayOfMonth}日"
    }
    val timeChipText = DateFmt.time(DateFmt.toMillis(dateTime))
    /** 口径胶囊文案：两个开关两两组合，保留「两个都排除」 */
    val caliberLabel = when {
        excludeStats && excludeBudget -> "不计收支和预算"
        excludeStats -> "不计入收支"
        excludeBudget -> "不计入预算"
        else -> "计入收支和预算"
    }
    /** 标签胶囊文案（N38）：未选显示「标签」，已选显示个数（具体名称在标签选择界面里看） */
    val tagChipText = if (tagIds.isEmpty()) "标签" else "标签 · ${tagIds.size}"

    /** 要保存的金额：计算器模式下先把算式算成金额（与「填入金额」同一套算法） */
    fun amountToSave(): Long =
        if (calcMode && calcExpr.isNotBlank()) Money.parse(trimNumber(evalExpression(calcExpr))) ?: 0L
        else cents

    /** 校验规则（保存 / 再记共用）：金额大于 0，且账户齐备 */
    fun valid(amountCents: Long): Boolean = amountCents > 0L && accountId != 0L &&
        (type != TxnType.TRANSFER || (toAccountId != 0L && toAccountId != accountId)) &&
        (type != TxnType.LEND_BORROW || (loanAccountId != 0L && loanAccountId != accountId))

    /** 组装要落库的账单（保存 / 再记共用同一套字段规则） */
    fun buildTxn(amountCents: Long): Txn = Txn(
        id = editing?.id ?: 0L,
        ledgerId = ledgerId,
        accountId = accountId,
        transferToAccountId = when (type) {
            TxnType.TRANSFER -> toAccountId
            TxnType.LEND_BORROW -> loanAccountId
            else -> 0L
        },
        categoryId = if (isIncomeType) {
            if (childId != 0L) childId else parentId
        } else 0L,
        type = type,
        direction = if (type == TxnType.LEND_BORROW) direction else Direction.NONE,
        amountCents = amountCents,
        counterparty = if (type == TxnType.LEND_BORROW) counterparty else "",
        note = note,
        dateMillis = DateFmt.toMillis(dateTime),
        createdAt = editing?.createdAt ?: System.currentTimeMillis(),
        // 只有收入 / 支出才有收支与预算口径；互转、借贷本来就不计收支
        excludeFromStats = isIncomeType && excludeStats,
        excludeFromBudget = isIncomeType && excludeBudget,
        refundCents = if (isIncomeType) {
            (Money.parse(refundText.ifBlank { "0" }) ?: 0L).coerceIn(0L, amountCents)
        } else 0L,
        // N40：手续费 / 优惠，与「实际金额」同一套口径（金额 − 退款 − 优惠 + 手续费）
        feeCents = if (isIncomeType) {
            (Money.parse(feeText.ifBlank { "0" }) ?: 0L).coerceAtLeast(0L)
        } else 0L,
        discountCents = if (isIncomeType) {
            (Money.parse(discountText.ifBlank { "0" }) ?: 0L).coerceIn(0L, amountCents)
        } else 0L,
        // 互转子类型（N26）：只有互转才有；其它类型恒为「互转」，保证列非空且语义固定
        transferKind = if (type == TxnType.TRANSFER) transferKind else TransferKind.COMMON
    )

    /** 新建一条应收 / 应付备忘（编辑已有备忘走另一条更新分支） */
    val asArAp = arApMode != null && editing == null && editingArAp == null

    /**
     * F47 / F51 / F53：组装一条应收 / 应付备忘。
     * 方向与收支方向一致（应收款 ↔ 收入、应付款 ↔ 支出）；编辑时保留 id、已收金额与状态。
     * 只有金额是必填，账户可以先不选（收款 / 付款时在结算界面里再选收款账户）。
     */
    fun buildArAp(amountCents: Long): ArAp {
        val src = editingArAp
        return ArAp(
            id = src?.id ?: 0L,
            ledgerId = src?.ledgerId ?: 0L,
            direction = arApMode
                ?: if (type == TxnType.INCOME) ArApDirection.RECEIVABLE else ArApDirection.PAYABLE,
            status = src?.status ?: ArApStatus.PENDING,
            amountCents = amountCents,
            categoryId = if (childId != 0L) childId else parentId,
            accountId = accountId,
            counterparty = src?.counterparty ?: "",
            note = note,
            dateMillis = DateFmt.toMillis(dateTime),
            settledAt = src?.settledAt ?: 0L,
            settledTxnId = src?.settledTxnId ?: 0L,
            settledCents = src?.settledCents ?: 0L,
            createdAt = src?.createdAt ?: System.currentTimeMillis()
        )
    }

    /** 保存并关闭面板 */
    fun doSave() {
        val amount = amountToSave()
        // F53：编辑已有应收 / 应付——校验金额后原样改回去（已收金额 / 状态都保留）
        if (editingArAp != null) {
            if (amount <= 0L) {
                vm.toast("请先填写金额")
                return
            }
            amountReplacePending = false
            scope.launch { if (vm.updateArAp(buildArAp(amount))) onDismiss() }
            return
        }
        // F47：应收 / 应付只校验金额；等写库成功再关面板，失败留在面板上并给出提示，
        // 不会再出现「点保存没反应／记录不见了」的情况
        if (asArAp) {
            if (amount <= 0L) {
                vm.toast("请先填写金额")
                return
            }
            amountReplacePending = false
            scope.launch {
                if (vm.saveArAp(buildArAp(amount))) onDismiss()
            }
            return
        }
        if (!valid(amount)) {
            vm.toast("请先填写金额并选择账户")
            return
        }
        // F18：保存即结束这次编辑，清掉「整体替换」标记
        amountReplacePending = false
        vm.saveTxn(buildTxn(amount), editing == null, tagIds)
        onDismiss()
    }

    /**
     * 再记：先把这一笔真正存进库（同一套校验与写库逻辑），成功后再清空面板继续录下一笔。
     * 保存成功后金额被清空，重复点「再记」不会被判成有效金额，所以不会重复保存同一笔。
     */
    fun doSaveAndNext() {
        val amount = amountToSave()
        // F53：编辑已有应收 / 应付时没有「再记」，直接按保存处理
        if (editingArAp != null) {
            doSave()
            return
        }
        // F47 / N47：应收 / 应付同样支持连续记（只校验金额）；写库成功后再清空面板继续下一笔。
        // 「应收 / 应付」模式保持不变，避免下一笔被误存成正式账单。
        if (asArAp) {
            if (amount <= 0L) {
                vm.toast("请先填写金额")
                return
            }
            val item = buildArAp(amount)
            scope.launch {
                if (!vm.saveArAp(item)) return@launch
                editing = null
                amountText = ""
                amountReplacePending = false
                note = ""
                textField = null
                refundText = ""
                feeText = ""
                discountText = ""
                excludeStats = false
                excludeBudget = false
                tagIds = emptyList()
                calcMode = false
                calcExpr = ""
                dateTime = LocalDateTime.now()
                exitTextInput()
            }
            return
        }
        if (!valid(amount)) {
            vm.toast("请先填写金额并选择账户")
            return
        }
        scope.launch {
            if (vm.saveTxnAndContinue(buildTxn(amount), editing == null, tagIds)) {
                // 只重置「这一笔的内容」；类型、分类、账户、时间上下文都留着，连续记账不用重选
                editing = null
                amountText = ""
                // F18：再记后是全新一笔，清掉「整体替换」标记
                amountReplacePending = false
                note = ""
                textField = null
                refundText = ""
                feeText = ""
                discountText = ""
                excludeStats = false
                excludeBudget = false
                // N38：标签属于「这一笔的内容」，再记时不带到下一笔
                tagIds = emptyList()
                calcMode = false
                calcExpr = ""
                // 下一笔默认回到今天（时间用当前时刻），避免连续记账时日期停留在上一次
                dateTime = LocalDateTime.now()
                exitTextInput()
            }
        }
    }

    ModalBottomSheet(
        // v2.5.1：面板必须不透明——之前用半透明玻璃底，背后账单列表的文字全透上来，非常乱。
        // 玻璃质感只保留在底栏（真模糊）与浮层边缘高光上。
        containerColor = CardWhite,
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Track)
            )
        }
    ) {
        // N37：记账键盘按键、切换分类 / 账户的轻振反馈统一从这里往下发；
        // 设置里关掉后 tap 就是空实现，等于全关
        CompositionLocalProvider(
            LocalKeyHaptic provides tap,
            LocalKeySound provides ding
        ) {
        // 这块面板自己不再处理输入法 inset（F13）：文本输入全部走独立窗口（M5），
        // 记账键盘不会因为系统键盘出现而被顶起或重建，也就没有随之而来的卡顿
        Column(Modifier.fillMaxHeight(0.93f)) {
            // 1) 类型 Tab 固定在面板顶部
            Row(Modifier.fillMaxWidth()) {
                visibleTabs.forEach { t ->
                    val on = t == type
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                // F18：切换收支类型即放弃「整体替换旧金额」的语义
                                amountReplacePending = false
                                tap()
                                type = t
                            }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            typeLabel(t),
                            fontSize = 14.5.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) Primary else Sub
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .size(width = 26.dp, height = 3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (on) Primary else Color.Transparent)
                        )
                    }
                }
            }
            HorizontalDivider(color = Line)

            // 2) 内容区：等分剩余高度并滚动，键盘永远在它下面
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                // P0-1：记账建议——按「备注关键词 / 时段 / 金额档」从本账本历史里挑分类 + 账户，
                // 只提示不自动改（用户点一下才采用），手动动过分类后本笔不再提示
                // P0-1（v2.5.1 收紧）：建议做成一颗小胶囊——只占一行、宽度随内容，
                // 点整颗就采用（不再有「采用」按钮占地方）。它只是建议，不改用户已选的值。
                suggestion?.let { s ->
                    val catName = categories.firstOrNull { it.id == s.categoryId }?.name ?: return@let
                    val accName = accounts.firstOrNull { it.id == s.accountId }?.name.orEmpty()
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(PrimaryBg)
                                .clickable {
                                    val cat = categories.firstOrNull { it.id == s.categoryId }
                                    if (cat != null) {
                                        if (cat.parentId == 0L) {
                                            parentId = cat.id
                                            childId = categories
                                                .firstOrNull { it.parentId == cat.id && !it.hidden }?.id ?: 0L
                                        } else {
                                            parentId = cat.parentId
                                            childId = cat.id
                                        }
                                    }
                                    if (accounts.any { it.id == s.accountId }) accountId = s.accountId
                                    suggestionUsed = true
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                null,
                                tint = Primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                catName + if (accName.isNotEmpty()) " · $accName" else "",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Primary,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                s.reason,
                                fontSize = 10.sp,
                                color = Sub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 分类 + 金额同一行：左侧分类（点它展开 / 收起分类胶囊），右侧大号金额
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = isIncomeType) { catsExpanded = !catsExpanded }
                            .padding(vertical = 3.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconBadge(catIcon, catColor, size = 32.dp, iconSize = 17.dp, radius = 11.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            catLabel,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 130.dp)
                        )
                        if (isIncomeType) {
                            Icon(
                                if (catsExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                tint = Chevron,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // 点金额区 = 切回金额输入：先收起系统输入法，记账键盘继续接管
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { exitTextInput() }
                            .padding(vertical = 2.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text("¥", fontSize = 17.sp, color = amountColor, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(2.dp))
                        Text(
                            if (amountText.isBlank()) "0.00" else amountText,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = amountColor,
                            maxLines = 1
                        )
                    }
                }
                HorizontalDivider(color = Line)

                when (type) {
                    TxnType.EXPENSE, TxnType.INCOME -> {
                        // 紧凑分类胶囊：一级带小图标、二级只有名称，选中 = 品牌色实心
                        if (catsExpanded) {
                            Spacer(Modifier.height(10.dp))
                            CategoryChips(level1, parentId, showIcon = true) { id ->
                                // F18：切换分类也放弃「整体替换旧金额」的语义
                                amountReplacePending = false
                                parentId = id
                                childId = categories.firstOrNull { it.parentId == id }?.id ?: 0L
                                catTouched = true   // P0-1：用户自己选了分类，本笔不再提示建议
                            }
                            if (level2.isNotEmpty()) {
                                Spacer(Modifier.height(7.dp))
                                CategoryChips(level2, childId, showIcon = false) {
                                    amountReplacePending = false
                                    childId = it
                                    catTouched = true   // P0-1：同上
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        CompactGroup {
                            AccountPickRow(
                                label = if (type == TxnType.INCOME) "收款账户" else "付款账户",
                                accounts = accounts,
                                typeList = accountTypes,
                                selectedId = accountId,
                                balanceOf = balanceOf
                            ) { accountId = it.id }
                        }

                        // v2.9.1：支出超过该账户余额时提前提示（不阻止保存，允许透支后补）
                        val pickedAccount = accounts.firstOrNull { it.id == accountId }
                        val overHint = EntryChecks.balanceHint(
                            isExpense = type == TxnType.EXPENSE,
                            isCredit = pickedAccount?.isCreditCard == true,
                            balanceCents = balanceOf[accountId] ?: 0L,
                            amountCents = actualCents
                        )
                        if (overHint != null) {
                            Text(
                                overHint,
                                fontSize = 11.sp,
                                color = WarnFg,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 5.dp)
                            )
                        }

                        NoteRow(note, "备注...") { openTextField(TextFieldKind.NOTE) }

                        AttrChipRow {
                            AttrChip(dateChipText) { exitTextInput(); showDatePicker = true }
                            AttrChip(timeChipText) { exitTextInput(); showTimePicker = true }
                            AttrChip(
                                text = if (refundCents > 0L) "退款 ¥" + Money.text(refundCents) else "无退款",
                                highlighted = refundCents > 0L
                            ) { openTextField(TextFieldKind.REFUND) }
                            // 手续费 / 优惠（N40）：都能点开金额输入（带常用金额快捷选择）
                            AttrChip(
                                text = if (feeCents > 0L) "手续费 ¥" + Money.text(feeCents) else "手续费",
                                highlighted = feeCents > 0L
                            ) { openTextField(TextFieldKind.FEE) }
                            AttrChip(
                                text = if (discountCents > 0L) "优惠 ¥" + Money.text(discountCents) else "优惠",
                                highlighted = discountCents > 0L
                            ) { openTextField(TextFieldKind.DISCOUNT) }
                            AttrChip(caliberLabel, highlighted = excludeStats || excludeBudget) {
                                exitTextInput(); showCaliber = true
                            }
                            // 标签（N38）：可为这笔账单选一个或多个标签
                            if (advTags) AttrChip(tagChipText, highlighted = tagIds.isNotEmpty()) {
                                exitTextInput(); showTagPicker = true
                            }
                            // F51：应收 / 应付胶囊，三档切换——不选中 / 应收款 / 应付款。
                            // 选应收款 → 收支自动切收入；选应付款 → 自动切支出；切回不选中由用户自己选收支
                            AttrChip(
                                text = when (arApMode) {
                                    ArApDirection.RECEIVABLE -> "应收款"
                                    ArApDirection.PAYABLE -> "应付款"
                                    null -> "应收 / 应付"
                                },
                                highlighted = arApMode != null
                            ) {
                                exitTextInput()
                                val next = when (arApMode) {
                                    null -> ArApDirection.RECEIVABLE
                                    ArApDirection.RECEIVABLE -> ArApDirection.PAYABLE
                                    ArApDirection.PAYABLE -> null
                                }
                                arApMode = next
                                if (next != null) {
                                    type = if (next == ArApDirection.RECEIVABLE) TxnType.INCOME
                                    else TxnType.EXPENSE
                                }
                            }
                        }

                        if (refundCents > 0L || feeCents > 0L || discountCents > 0L) {
                            Text(
                                buildString {
                                    append("实际 ¥")
                                    append(Money.text(actualCents))
                                    append(" = 原价 ¥")
                                    append(Money.text(cents))
                                    if (refundCents > 0L) {
                                        append(" − 报销/退款 ¥")
                                        append(Money.text(refundCents))
                                    }
                                    if (discountCents > 0L) {
                                        append(" − 优惠 ¥")
                                        append(Money.text(discountCents))
                                    }
                                    if (feeCents > 0L) {
                                        append(" + 手续费 ¥")
                                        append(Money.text(feeCents))
                                    }
                                    append("；账户余额与收支统计都按实计算")
                                },
                                fontSize = 11.sp,
                                color = Sub,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                            )
                        }
                    }

                    TxnType.TRANSFER -> {
                        Spacer(Modifier.height(10.dp))
                        // 互转类型胶囊（N26）：互转 / 还款 / 理财买入 / 理财赎回 + 自定义，
                        // 末位「管理」进入增删改（改名、删除都会带着历史账单一起迁移）
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            transferKinds.forEach { k ->
                                val on = k.name == transferKind
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(15.dp))
                                        .clickable { transferKind = k.name },
                                    shape = RoundedCornerShape(15.dp),
                                    color = if (on) Primary else FieldBg
                                ) {
                                    Text(
                                        k.name,
                                        fontSize = 12.sp,
                                        color = if (on) MaterialTheme.colorScheme.onPrimary else Sub2,
                                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                    )
                                }
                            }
                            AttrChip("管理类型") { showKindManager = true }
                        }
                        Spacer(Modifier.height(10.dp))
                        // 互转不影响总资产：只换账户，不计收支；中间的按钮一键对调转出 / 转入
                        CompactGroup {
                            AccountPickRow("转出账户", accounts, accountTypes, accountId, balanceOf) {
                                accountId = it.id
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    Modifier
                                        .clip(CircleShape)
                                        .clickable {
                                            // 上下箭头：一键调换转出 / 转入（金额与其它字段都不动）
                                            val from = accountId
                                            accountId = toAccountId
                                            toAccountId = from
                                        }
                                        .padding(horizontal = 14.dp, vertical = 4.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Rounded.SwapVert,
                                            contentDescription = "调换转出转入",
                                            tint = Primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("调换", fontSize = 11.5.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            // v2.9.1：转入账户里把「转出账户」置灰，从源头避免选成同一个账户
                            AccountPickRow(
                                label = "转入账户",
                                accounts = accounts,
                                typeList = accountTypes,
                                selectedId = toAccountId,
                                balanceOf = balanceOf,
                                disabledId = accountId
                            ) {
                                toAccountId = it.id
                            }
                        }
                        NoteRow(note, "备注...") { openTextField(TextFieldKind.NOTE) }
                        AttrChipRow {
                            AttrChip(dateChipText) { exitTextInput(); showDatePicker = true }
                            AttrChip(timeChipText) { exitTextInput(); showTimePicker = true }
                            // 标签（N38）：互转 / 借贷账单同样可以打标签
                            if (advTags) AttrChip(tagChipText, highlighted = tagIds.isNotEmpty()) {
                                exitTextInput(); showTagPicker = true
                            }
                        }
                    }

                    TxnType.LEND_BORROW -> {
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            listOf(Direction.LEND to "借出", Direction.BORROW to "借入").forEach { (d, label) ->
                                val on = direction == d
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = if (d == Direction.LEND) 10.dp else 0.dp)
                                        .clickable { direction = d },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (on) (if (d == Direction.LEND) Expense else Income) else FieldBg
                                ) {
                                    Text(
                                        label,
                                        modifier = Modifier.padding(vertical = 10.dp),
                                        textAlign = TextAlign.Center,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (on) MaterialTheme.colorScheme.onPrimary else Sub2
                                    )
                                }
                            }
                        }
                        Text(
                            if (direction == Direction.LEND)
                                "借出：资金账户 -金额，借贷账户 +金额（记应收 · 别人欠我）；不计入收支"
                            else
                                "借入：资金账户 +金额，借贷账户 -金额（记负债 · 我欠别人）；不计入收支",
                            fontSize = 11.sp,
                            color = Sub2,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        CompactGroup {
                            AccountPickRow(
                                if (direction == Direction.LEND) "资金账户（扣款）" else "资金账户（到账）",
                                accounts.filter { it.type != AccountType.LEND },
                                accountTypes,
                                accountId,
                                balanceOf
                            ) { accountId = it.id }
                            AccountPickRow(
                                "借贷账户",
                                accounts.filter { it.type == AccountType.LEND },
                                accountTypes,
                                loanAccountId,
                                balanceOf
                            ) { loanAccountId = it.id }
                        }
                        NoteRow(counterparty, "对方（选填），如：张三") {
                            openTextField(TextFieldKind.COUNTERPARTY)
                        }
                        NoteRow(note, "备注...") { openTextField(TextFieldKind.NOTE) }
                        AttrChipRow {
                            AttrChip(dateChipText) { exitTextInput(); showDatePicker = true }
                            AttrChip(timeChipText) { exitTextInput(); showTimePicker = true }
                            // 标签（N38）：互转 / 借贷账单同样可以打标签
                            if (advTags) AttrChip(tagChipText, highlighted = tagIds.isNotEmpty()) {
                                exitTextInput(); showTagPicker = true
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 3) 记账键盘：显隐只由「文本输入中」这一个状态源驱动（F13）——
            //    文本输入窗口开着时完全不渲染、不参与布局；关掉后立刻恢复常驻
            if (!textInputActive) {
                Keypad(
                    calcMode = calcMode,
                    calcExpr = calcExpr,
                    // F18：数字键走 keypadAmount —— 编辑态首次按数字 / 小数点整体替换旧金额，
                    // 退格仍是删最后一位；其余情况完全等同于原来的 applyKey
                    onDigit = {
                        val (next, pending) = keypadAmount(amountText, it, amountReplacePending)
                        amountText = next
                        amountReplacePending = pending
                    },
                    onCalcDigit = { d ->
                        val tail = calcExpr.split(Regex("[+\\-*/]")).lastOrNull() ?: ""
                        calcExpr = when {
                            d == "." && tail.contains(".") -> calcExpr
                            tail == "0" && d != "." -> calcExpr.dropLast(1) + d
                            else -> calcExpr + d
                        }
                    },
                    onCalcOp = { op ->
                        var c = if (calcExpr.isEmpty()) "0" else calcExpr
                        if (c.last() in "+-*/") c = c.dropLast(1)
                        calcExpr = c + op
                    },
                    onCalcDel = { calcExpr = calcExpr.dropLast(1) },
                    onCalcEqual = { calcExpr = trimNumber(evalExpression(calcExpr)) },
                    onToggleCalc = {
                        // F18：开关计算器不碰金额，也清掉「整体替换」标记，避免之后误替换
                        amountReplacePending = false
                        calcMode = !calcMode
                        if (!calcMode) calcExpr = ""
                    },
                    onSave = { doSave() },
                    onSaveAndNext = { doSaveAndNext() },
                    onBeforeKey = { exitTextInput() }
                )
            }
        }
        }

        // 文本输入统一走公共组件（M5）：备注 / 借贷对方 / 报销退款 都是同一个独立窗口，
        // 覆盖在记一笔面板之上，自带系统输入法并按键盘高度垫高输入区；
        // 组件在打开期间会把 textInputActive 置 true —— 记账键盘的显隐只由它驱动（F13）。
        when (textField) {
            TextFieldKind.NOTE -> TextInputSheet(
                title = "备注",
                initial = note,
                placeholder = "",
                singleLine = false,
                candidateProvider = { q -> noteCandidatesOf(q) },
                onOpenSettings = { showNoteSettings = true },
                hostImeHeight = hostImeHeight,
                onActiveChange = { textInputActive = it },
                onCancel = { textField = null },
                onConfirm = { note = it; textField = null }
            )

            TextFieldKind.COUNTERPARTY -> TextInputSheet(
                title = "对方名称",
                initial = counterparty,
                placeholder = "如：张三（选填）",
                singleLine = true,
                hostImeHeight = hostImeHeight,
                onActiveChange = { textInputActive = it },
                onCancel = { textField = null },
                onConfirm = { counterparty = it; textField = null }
            )

            TextFieldKind.REFUND -> TextInputSheet(
                title = "报销 / 退款",
                initial = refundText,
                placeholder = "金额（留空表示没有退款）",
                singleLine = true,
                numberOnly = true,
                hostImeHeight = hostImeHeight,
                onActiveChange = { textInputActive = it },
                onCancel = { textField = null },
                onConfirm = { refundText = it; textField = null }
            )

            // N40：手续费 / 优惠。复用公共输入窗口的「候选」区域做常用金额快捷选择，
            // 点候选即填入该金额，也可以直接手输任意数值。
            TextFieldKind.FEE -> TextInputSheet(
                title = "手续费",
                initial = feeText,
                placeholder = "金额（如转账手续费，计入实际支出）",
                singleLine = true,
                numberOnly = true,
                candidateProvider = { QUICK_FEE },
                hostImeHeight = hostImeHeight,
                onActiveChange = { textInputActive = it },
                onCancel = { textField = null },
                onConfirm = { feeText = it; textField = null }
            )

            TextFieldKind.DISCOUNT -> TextInputSheet(
                title = "优惠",
                initial = discountText,
                placeholder = "金额（如满减 / 折扣，从实际支出里减去）",
                singleLine = true,
                numberOnly = true,
                candidateProvider = { QUICK_DISCOUNT },
                hostImeHeight = hostImeHeight,
                onActiveChange = { textInputActive = it },
                onCancel = { textField = null },
                onConfirm = { discountText = it; textField = null }
            )

            null -> Unit
        }

        // 互转类型管理（N26）：新建 / 改名 / 删除，删除时历史账单必须先选迁移目标
        if (showKindManager) {
            TransferKindManagerSheet(vm = vm, onDismiss = { showKindManager = false })
        }

        // 标签选择 / 快捷新建（N38）：可多选，也可直接新建后自动选中
        if (showTagPicker) {
            TagPickerDialog(
                vm = vm,
                selectedIds = tagIds,
                onConfirm = {
                    tagIds = it
                    showTagPicker = false
                },
                onDismiss = { showTagPicker = false }
            )
        }

        // 备注窗口右上角齿轮（M4 预留入口）：备注相关设置，目前只有「备注记忆」开关
        if (showNoteSettings) {
            AlertDialog(
                onDismissRequest = { showNoteSettings = false },
                containerColor = CardWhite,
                title = { Text("备注设置", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    SwitchRow(
                        label = "备注记忆",
                        hint = "记住这个账本里记过的备注，输入时自动联想（去重、最多 5 条）",
                        checked = noteMemoryOn
                    ) { vm.setNoteMemoryEnabled(!noteMemoryOn) }
                },
                confirmButton = {
                    TextButton(onClick = { showNoteSettings = false }) {
                        Text("完成", color = Primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }

        // 日期 / 时间分开选（胶囊各管一段），先选日期再选时间也可以随时单独改
        if (showDatePicker) {
            DateOnlyPickerDialog(
                initial = pickedDate,
                onDismiss = { showDatePicker = false },
                onConfirm = { d ->
                    dateTime = d.atTime(dateTime.hour, dateTime.minute)
                    showDatePicker = false
                }
            )
        }
        if (showTimePicker) {
            TimeOnlyPickerDialog(
                initial = dateTime.toLocalTime(),
                onDismiss = { showTimePicker = false },
                onConfirm = { t ->
                    dateTime = dateTime.toLocalDate().atTime(t)
                    showTimePicker = false
                }
            )
        }
        if (showCaliber) {
            CaliberDialog(
                excludeStats = excludeStats,
                excludeBudget = excludeBudget,
                onToggleStats = { excludeStats = !excludeStats },
                onToggleBudget = { excludeBudget = !excludeBudget },
                onDismiss = { showCaliber = false }
            )
        }
    }
}

private fun applyKey(current: String, key: String): String {
    var v = current
    when (key) {
        "del" -> v = if (v.isEmpty()) "" else v.dropLast(1)
        "." -> if (!v.contains(".")) v = if (v.isEmpty()) "0." else "$v."
        "00" -> if (v.isNotEmpty() && v != "0") v += "00"
        else -> v = if (v == "0") key else v + key
    }
    val dot = v.indexOf('.')
    if (dot >= 0 && v.length - dot > 3) v = v.substring(0, dot + 3)
    if (v.replace(".", "").length > 9) return current
    return v
}

/**
 * F18：记账键盘数字键的金额输入。
 * [replacePending] = true 表示「正在修改旧账单、旧金额待整体替换」：
 *  - 首次按数字 / 小数点 → 直接用新数字起头（小数点从 "0." 开始），并清掉标记；
 *  - 按退格 → 仍是「删掉最后一位」的原有语义，同时清掉标记，不做整体替换。
 * [replacePending] = false 时完全走原有的 [applyKey]，行为与改动前一致。
 * 返回（新的金额文本, 新的待替换标记）。
 */
internal fun keypadAmount(current: String, key: String, replacePending: Boolean): Pair<String, Boolean> {
    if (replacePending) {
        return when {
            key == "del" -> applyKey(current, key) to false
            key == "." -> "0." to false
            key.length == 1 && key[0].isDigit() -> key to false
            else -> applyKey(current, key) to false
        }
    }
    return applyKey(current, key) to false
}

/** 灰底分组容器（账户 / 时间这类整行可点的字段用它，和设置页视觉一致） */
@Composable
private fun CompactGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = FieldBgAlt
    ) {
        Column(Modifier.padding(horizontal = 12.dp), content = content)
    }
}

/**
 * 紧凑分类胶囊：一级带小图标、二级只显示名称，一行约 4 个；
 * 选中项用品牌色实心高亮，与工程既有配色一致。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(
    list: List<Category>,
    selectedId: Long,
    showIcon: Boolean,
    onPick: (Long) -> Unit
) {
    // N37：选分类时轻振一下（开关关闭时是空实现）
    val haptic = LocalKeyHaptic.current
    // N39：选分类时轻响一下
    val sound = LocalKeySound.current
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        list.forEach { c ->
            val on = c.id == selectedId
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(15.dp))
                    .clickable {
                        haptic()
                        sound(KeySound.Scene.CATEGORY)
                        onPick(c.id)
                    },
                shape = RoundedCornerShape(15.dp),
                color = if (on) Primary else FieldBg
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showIcon) {
                        Icon(
                            categoryIcon(c) ?: Icons.Rounded.Circle,
                            contentDescription = null,
                            tint = if (on) MaterialTheme.colorScheme.onPrimary else categoryColor(c),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        c.name,
                        fontSize = 11.5.sp,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else Sub2,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
        if (list.isEmpty()) {
            Text("还没有分类，请到「设置 → 分类管理」添加", fontSize = 12.5.sp, color = Sub)
        }
    }
}

/**
 * 账户一行：左侧名字、右侧当前账户（类型图标 + 名称）+ 箭头，整行可点弹出账户选择器。
 * 原来「账户」「时间」各占一行的纵向排布在这里压成了一行。
 */
@Composable
private fun AccountPickRow(
    label: String,
    accounts: List<Account>,
    typeList: List<AccountTypeEntity>,
    selectedId: Long,
    /** 账户 id -> 当前余额（分），与账户页同一份实时聚合结果 */
    balanceOf: Map<Long, Long>,
    /** v2.9.1：选择器里不可选的账户（互转时转出账户在「转入账户」里置灰） */
    disabledId: Long = 0L,
    onPick: (Account) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = accounts.firstOrNull { it.id == selectedId }
    // N37：选账户时轻振一下
    val haptic = LocalKeyHaptic.current
    // N39：选账户时轻响一下
    val sound = LocalKeySound.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                haptic()
                sound(KeySound.Scene.CATEGORY)
                expanded = true
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = Sub2)
        Spacer(Modifier.weight(1f))
        current?.let {
            val badge = accountBadge(it.type)
            if (badge != null) {
                TextBadge(badge, accountColor(it.type), size = 22.dp, radius = 7.dp)
            } else {
                IconBadge(accountIcon(it.type), accountColor(it.type), size = 22.dp, iconSize = 13.dp, radius = 7.dp)
            }
            Spacer(Modifier.width(6.dp))
        }
        Text(
            current?.name ?: "请选择",
            fontSize = 13.5.sp,
            color = Ink,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(Icons.Rounded.KeyboardArrowRight, null, tint = Chevron, modifier = Modifier.size(18.dp))
    }
    AccountPickerDialog(
        show = expanded,
        accounts = accounts,
        typeList = typeList,
        selectedId = selectedId,
        balanceOf = balanceOf,
        onDismiss = { expanded = false },
        onPick = { onPick(it); expanded = false },
        disabledId = disabledId
    )
}

/**
 * 文本字段行（M5）：只负责显示当前值，点整行弹出公共文本输入窗口。
 * 备注 / 借贷对方都用它，样式与交互完全一致，也不和金额抢同一个输入区。
 */
@Composable
private fun NoteRow(value: String, placeholder: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(FieldBg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Edit, contentDescription = null, tint = Chevron, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            value.ifBlank { placeholder },
            fontSize = 13.5.sp,
            color = if (value.isBlank()) Muted else Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Chevron,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** 属性胶囊行：允许换行成两行，日期 / 时间 / 退款 / 口径都收在这里，默认不抢主视线 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttrChipRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun AttrChip(text: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(15.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(15.dp),
        color = if (highlighted) PrimaryBg else FieldBg
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (highlighted) Primary else Sub2,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * 统计口径面板：两个独立开关（不做三选一，否则会丢掉「两个都排除」的组合）。
 */
@Composable
private fun CaliberDialog(
    excludeStats: Boolean,
    excludeBudget: Boolean,
    onToggleStats: () -> Unit,
    onToggleBudget: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = { Text("统计口径", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                SwitchRow(
                    label = "不计入收支",
                    hint = "不进收入 / 支出统计，但账户余额照常变化",
                    checked = excludeStats
                ) { onToggleStats() }
                RowDivider()
                SwitchRow(
                    label = "不计入预算",
                    hint = "照常计入收支，但当月预算进度不含它",
                    checked = excludeBudget
                ) { onToggleBudget() }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = Primary, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = Sub2)
            if (hint.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(hint, fontSize = 11.sp, color = Muted2, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(checkedTrackColor = Primary)
        )
    }
}

/**
 * v2.9.1 打磨项 ③：记一笔的即时校验。
 *
 * 原来所有校验都堆在点「保存」时给一句 toast，用户要填完才发现问题。
 * 这里把判断抽成纯函数，面板在输入过程中就能实时给出提示；
 * **提示是非阻断的**——例如余额不足仍然允许保存（允许透支、后补），
 * 只是提前告知，不打断记账流程。
 */
object EntryChecks {

    /**
     * 互转的转入账户与转出账户是否撞同一个。
     * 撞了在这个界面里没有任何意义（账户余额不变），所以选择器里直接置灰不可选。
     */
    fun isSameAccountForTransfer(fromId: Long, toId: Long): Boolean =
        fromId != 0L && toId != 0L && fromId == toId

    /**
     * 支出金额超过账户余额时给一句提示（**不阻止保存**）。
     *
     * @param isCredit   信用卡账户允许为负（本月消费下月还），不做提示
     * @param isExpense  只有支出才检查；收入 / 互转 / 借贷不检查
     * @param balanceCents 账户当前余额（分）
     * @param amountCents  本笔金额（分，按实计口径）
     * @return 需要展示的提示文案；null 表示无需提示
     */
    fun balanceHint(
        isExpense: Boolean,
        isCredit: Boolean,
        balanceCents: Long,
        amountCents: Long
    ): String? {
        if (!isExpense || isCredit) return null
        if (amountCents <= 0L) return null
        if (balanceCents <= 0L) return null
        if (amountCents <= balanceCents) return null
        return "超过该账户余额（当前 ¥" + com.qingjizhang.app.util.Money.text(balanceCents) +
            "），仍可保存——余额会变成负数"
    }

    /**
     * 借贷时资金账户与借贷账户不能相同（同一账户自己借给自己没有意义）。
     */
    fun isSameAccountForLend(fundId: Long, loanId: Long): Boolean =
        fundId != 0L && loanId != 0L && fundId == loanId
}

/**
 * 账户选择器（N12）：按账户类型分组，分组顺序与分组名都跟着当前账本的类型列表走；
 * 没有账户的类型不显示；组内按 sortOrder、其次 id 排序；类型列表之外的账户追加在末尾，保证不丢账户。
 *
 * v2.9.1：新增 [disabledId] —— 互转的「转入账户」选择器把转出账户置灰，从源头避免选错。
 */
@Composable
private fun AccountPickerDialog(
    show: Boolean,
    accounts: List<Account>,
    /** 当前账本的账户类型列表，口径与账户页一致 */
    typeList: List<AccountTypeEntity>,
    selectedId: Long,
    /** 账户 id -> 当前余额（分） */
    balanceOf: Map<Long, Long>,
    onDismiss: () -> Unit,
    onPick: (Account) -> Unit,
    /** v2.9.1：不可选的账户（置灰 + 显示原因），0 = 全部可选 */
    disabledId: Long = 0L
) {
    if (!show) return
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val typeNames = typeList.map { it.name }
    val groups = (typeNames + accounts.map { it.type }.filter { it !in typeNames }.distinct())
        .map { name ->
            name to accounts.filter { it.type == name }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        }
        // 空分组隐藏
        .filter { it.second.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = Primary) }
        },
        title = { Text("选择账户", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                groups.forEach { (group, items) ->
                    val open = collapsed[group] != true
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { collapsed[group] = open }
                            .padding(top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(group, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Sub)
                        Spacer(Modifier.width(4.dp))
                        Text("(${items.size})", fontSize = 11.sp, color = Muted2)
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (open) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Chevron,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (open) {
                        items.forEach { a ->
                            val on = a.id == selectedId
                            // v2.9.1：不可选的账户置灰并说明原因（如互转时不能选同一个账户）
                            val disabled = disabledId != 0L && a.id == disabledId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (on) PrimaryBg else Color.Transparent)
                                    .clickable(enabled = !disabled) { if (!disabled) onPick(a) }
                                    .padding(horizontal = 6.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val badge = accountBadge(a.type)
                                if (badge != null) TextBadge(badge, accountColor(a.type), size = 34.dp)
                                else IconBadge(accountIcon(a.type), accountColor(a.type), size = 34.dp)
                                Spacer(Modifier.width(11.dp))
                                Text(
                                    a.name,
                                    fontSize = 14.sp,
                                    color = when {
                                        disabled -> Muted
                                        on -> Primary
                                        else -> Ink
                                    },
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.weight(1f))
                                if (disabled) {
                                    Text("转出账户，不能选", fontSize = 11.sp, color = Muted)
                                } else {
                                    // 显示真实当前余额（与账户页一致），不再是初始余额
                                    Text(
                                        Money.withSymbol(balanceOf[a.id] ?: 0L),
                                        fontSize = 11.5.sp,
                                        color = Sub
                                    )
                                }
                            }
                        }
                    }
                }
                if (groups.isEmpty()) {
                    Text("还没有账户，请先到「账户」页添加", fontSize = 13.sp, color = Sub)
                }
            }
        }
    )
}

/**
 * 日期选择（只改日期，时间保持不变）：配色跟随主题品牌色，支持跨月跨年补记历史。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateOnlyPickerDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit
) {
    val zone = java.time.ZoneId.systemDefault()
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = DateFmt.toMillis(initial)
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = DatePickerDefaults.colors(containerColor = CardWhite),
        confirmButton = {
            TextButton(onClick = {
                val picked = dateState.selectedDateMillis
                    ?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                    ?: initial
                onConfirm(picked)
            }) {
                Text("确定", color = Primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Sub) }
        }
    ) {
        DatePicker(
            state = dateState,
            showModeToggle = false,
            colors = DatePickerDefaults.colors(
                containerColor = CardWhite,
                selectedDayContainerColor = Primary,
                todayDateBorderColor = Primary,
                todayContentColor = Primary
            )
        )
    }
}

/** 时间选择（只改时分，日期保持不变） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeOnlyPickerDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit
) {
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(timeState.hour, timeState.minute)) }) {
                Text("确定", color = Primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Sub) }
        },
        title = { Text("选择时间", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(
                    state = timeState,
                    colors = TimePickerDefaults.colors(
                        timeSelectorSelectedContainerColor = Primary,
                        timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
                        timeSelectorUnselectedContainerColor = PrimaryBg
                    )
                )
            }
        }
    )
}

/** 金额保留两位小数的字符串（去掉多余的 0） */
private fun trimNumber(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "0"
    val rounded = Math.round(v * 100) / 100.0
    return if (rounded == Math.floor(rounded)) rounded.toLong().toString()
    else rounded.toString()
}

/** 计算器求值：先乘除后加减，支持小数与 ± */
internal fun evalExpression(expr: String): Double {
    val clean = expr.replace("×", "*").replace("÷", "/").replace("−", "-")
        .filter { it.isDigit() || it == '.' || it == '+' || it == '-' || it == '*' || it == '/' }
        .trimEnd('+', '-', '*', '/', '.')
    if (clean.isBlank()) return 0.0
    val tokens = Regex("(\\d+\\.?\\d*)|[+\\-*/]").findAll(clean).map { it.value }.toList()
    if (tokens.isEmpty()) return 0.0
    val nums = mutableListOf<Double>()
    val ops = mutableListOf<String>()
    var expectNum = true
    tokens.forEach { tk ->
        if (tk[0].isDigit() || tk[0] == '.') {
            nums += tk.toDoubleOrNull() ?: 0.0
            expectNum = false
        } else if (!expectNum) {
            ops += tk
            expectNum = true
        }
    }
    if (nums.isEmpty()) return 0.0
    if (nums.size == 1) return nums[0]
    val n = mutableListOf(nums[0])
    val o = mutableListOf<String>()
    for (i in ops.indices) {
        val b = nums.getOrNull(i + 1) ?: break
        if (ops[i] == "*" || ops[i] == "/") {
            val a = n.removeAt(n.size - 1)
            n += if (ops[i] == "*") a * b else if (b == 0.0) 0.0 else a / b
        } else {
            o += ops[i]
            n += b
        }
    }
    var r = n[0]
    for (i in o.indices) {
        val b = n.getOrNull(i + 1) ?: break
        r = if (o[i] == "+") r + b else r - b
    }
    return r
}

/**
 * 记账键盘：左侧数字键区 + 右侧一竖列 ⌫ / 再记 / 保存。
 * 「保存」常驻（品牌色实心、更高），进计算器模式也一直在，保证任何情况下都能保存。
 * [onBeforeKey] 在每次按键前复位文字输入状态（清焦点 + 收系统输入法），让记账键盘始终可用。
 */
@Composable
private fun Keypad(
    calcMode: Boolean,
    calcExpr: String,
    onDigit: (String) -> Unit,
    onCalcDigit: (String) -> Unit,
    onCalcOp: (String) -> Unit,
    onCalcDel: () -> Unit,
    onCalcEqual: () -> Unit,
    onToggleCalc: () -> Unit,
    onSave: () -> Unit,
    onSaveAndNext: () -> Unit,
    onBeforeKey: () -> Unit
) {
    Surface(color = FieldBg) {
        Column {
            if (calcMode) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        calcExpr.ifBlank { "输入算式，例如 12.5+3×2" }.replace("*", "×").replace("/", "÷"),
                        fontSize = 12.5.sp,
                        color = if (calcExpr.isBlank()) Muted else Sub2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "= " + Money.text(Math.round(evalExpression(calcExpr) * 100)),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                    Spacer(Modifier.width(8.dp))
                    // 切回数字键盘（右列固定给 ⌫ / 再记 / 保存，所以放在这里）
                    Text(
                        "键盘",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(CardWhite)
                            .clickable {
                                onBeforeKey()
                                onToggleCalc()
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp).height(200.dp)) {
                if (calcMode) {
                    // 计算器键盘：4 列（数字 + 运算符 + =）
                    Column(Modifier.weight(3f).fillMaxHeight()) {
                        listOf(
                            listOf("7", "8", "9", "/"),
                            listOf("4", "5", "6", "*"),
                            listOf("1", "2", "3", "-"),
                            listOf("0", ".", "=", "+")
                        ).forEach { row ->
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                row.forEach { k ->
                                    when {
                                        k == "=" -> KeyButton(
                                            label = "=",
                                            opKey = true,
                                            modifier = Modifier.weight(1f).fillMaxHeight().padding(3.dp),
                                            onClick = { onBeforeKey(); onCalcEqual() }
                                        )
                                        k in listOf("/", "*", "-", "+") -> KeyButton(
                                            label = when (k) {
                                                "/" -> "÷"; "*" -> "×"; "-" -> "−"; else -> "+"
                                            },
                                            modifier = Modifier.weight(1f).fillMaxHeight().padding(3.dp),
                                            onClick = { onBeforeKey(); onCalcOp(k) },
                                            opKey = true
                                        )
                                        else -> KeyButton(
                                            label = k,
                                            modifier = Modifier.weight(1f).fillMaxHeight().padding(3.dp),
                                            onClick = { onBeforeKey(); onCalcDigit(k) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // 数字键盘：3 列 1-9、0、. 与功能键（计算器）
                    Column(Modifier.weight(3f).fillMaxHeight()) {
                        listOf(
                            listOf("1", "2", "3"),
                            listOf("4", "5", "6"),
                            listOf("7", "8", "9")
                        ).forEach { row ->
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                row.forEach { k ->
                                    KeyButton(
                                        label = k,
                                        modifier = Modifier.weight(1f).fillMaxHeight().padding(3.dp),
                                        onClick = { onBeforeKey(); onDigit(k) }
                                    )
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            listOf(".", "0").forEach { k ->
                                KeyButton(
                                    label = k,
                                    modifier = Modifier.weight(1f).fillMaxHeight().padding(3.dp),
                                    onClick = { onBeforeKey(); onDigit(k) }
                                )
                            }
                            KeyButton(
                                label = "计算器",
                                icon = Icons.Rounded.Calculate,
                                small = true,
                                modifier = Modifier.weight(1f).fillMaxHeight().padding(3.dp),
                                onClick = { onBeforeKey(); onToggleCalc() }
                            )
                        }
                    }
                }
                // 右列：删除 / 再记 / 保存（保存常驻且更高）
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    KeyButton(
                        label = null,
                        icon = Icons.Rounded.Backspace,
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(3.dp),
                        onClick = {
                            onBeforeKey()
                            if (calcMode) onCalcDel() else onDigit("del")
                        }
                    )
                    KeyButton(
                        label = "再记",
                        opKey = true,
                        soundScene = KeySound.Scene.SAVE,
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(3.dp),
                        onClick = { onBeforeKey(); onSaveAndNext() }
                    )
                    KeyButton(
                        label = "保存",
                        primary = true,
                        soundScene = KeySound.Scene.SAVE,
                        modifier = Modifier.fillMaxWidth().weight(2f).padding(3.dp),
                        onClick = { onBeforeKey(); onSave() }
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    small: Boolean = false,
    primary: Boolean = false,
    opKey: Boolean = false,
    /** N39：这个按键用哪段按键音，默认是「输入金额」 */
    soundScene: KeySound.Scene = KeySound.Scene.AMOUNT,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val bg = when {
        primary -> Primary
        opKey -> PrimaryBg
        else -> CardWhite
    }
    // N37：每个按键都轻振一下（开关关闭时是空实现）
    val haptic = LocalKeyHaptic.current
    // N39：每个按键都轻响一下
    val sound = LocalKeySound.current
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .clickable {
                haptic()
                sound(soundScene)
                onClick()
            },
        shape = RoundedCornerShape(11.dp),
        color = bg
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (primary) MaterialTheme.colorScheme.onPrimary else if (opKey) Primary else Sub2,
                        modifier = Modifier.size(17.dp)
                    )
                    if (label != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            label,
                            fontSize = 10.sp,
                            color = if (primary) MaterialTheme.colorScheme.onPrimary else Sub2,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                Text(
                    label ?: "",
                    fontSize = if (small) 13.sp else if (primary) 15.sp else 18.sp,
                    fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (primary) MaterialTheme.colorScheme.onPrimary else if (opKey) Primary else Ink,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
