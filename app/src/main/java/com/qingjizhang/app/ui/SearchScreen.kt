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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.*
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.SearchHit
import com.qingjizhang.app.vm.buildHits
import com.qingjizhang.app.vm.deleteTxn
import com.qingjizhang.app.vm.openTxnList
import com.qingjizhang.app.vm.setSearchQuery
import com.qingjizhang.app.vm.tagUsage

/**
 * 首页右上角搜索：按金额 / 备注 / 类型 / 分类 / 账户搜已记的账。
 * 结果会标出命中原因（匹配金额、匹配备注…）。
 */
@Composable
fun SearchScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenTxn: (Long) -> Unit,
    /** N54：搜索命中标签后跳到「该标签的账单列表」 */
    onOpenTxnList: () -> Unit = {}
) {
    val query by vm.searchQuery.collectAsState()
    // N60：搜索结果改 SQL 下推（输入变化时按维度收窄，命中原因再在结果集上算）
    val searchResults by vm.searchResults.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()

    var input by remember { mutableStateOf(query) }
    val focus = remember { FocusRequester() }
    var pendingDelete by remember { mutableStateOf<Txn?>(null) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    // N28：长按搜索结果进入多选
    val multi = remember { TxnMultiSelectState() }
    // F20：左滑展开项由页面托管，滚动列表时自动收起
    val scrollState = rememberScrollState()
    val swipe = rememberSwipeOpenState(scrollState.isScrollInProgress)

    val catById = remember(categories) { categories.associateBy { it.id } }
    val accById = remember(accounts) { accounts.associateBy { it.id } }
    // N38：标签名（txnId -> 标签），搜索结果副标题里展示
    val tagMap by vm.txnTagMap.collectAsState()
    // N54：标签搜索——命中的标签单独列出来，点进去看该标签下的全部账单
    val tags by vm.tags.collectAsState()
    val tagHits = remember(input, tags) {
        if (input.isBlank()) emptyList()
        else tags.filter { it.name.contains(input.trim(), ignoreCase = true) }
    }

    // N60：SQL 已按关键词收窄，这里只补「命中原因」（纯内存，集合已经很小）
    val hits: List<SearchHit> = remember(searchResults, input) {
        if (input.isBlank()) emptyList() else vm.buildHits(searchResults, input)
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    pendingDelete?.let { t ->
        TxnDeleteDialog(
            summary = DateFmt.isoDate(t.dateMillis) + " · " + Money.withSymbol(t.amountCents),
            onConfirm = { keep ->
                vm.deleteTxn(t, keepBalance = keep)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
    editing?.let { t ->
        AddEntrySheet(vm = vm, request = AddSheetRequest(editing = t), onDismiss = { editing = null })
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        BackTopBar("搜索", onBack)
        Column(Modifier.padding(horizontal = 14.dp)) {

            // 搜索框
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = CardWhite,
                shadowElevation = 2.dp
            ) {
                Row(
                    Modifier.padding(horizontal = 13.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Sub, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp))
                    TextField(
                        value = input,
                        onValueChange = {
                            input = it
                            vm.setSearchQuery(it)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focus),
                        singleLine = true,
                        placeholder = {
                            Text("金额 / 备注 / 分类 / 账户 / 类型", fontSize = 14.sp, color = Muted)
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Ink,
                            unfocusedTextColor = Ink,
                            cursorColor = Primary
                        ),
                        textStyle = TextStyle(fontSize = 14.5.sp)
                    )
                    if (input.isNotEmpty()) {
                        IconAction(Icons.Rounded.Close, {
                            input = ""
                            vm.setSearchQuery("")
                        }, tint = Sub, size = 17.dp)
                    }
                }
            }

            // 关键词建议
            FlowRowChips(
                items = listOf("88", "晚餐", "打车", "牙膏", "支出", "收入", "互转", "借贷", "支付宝", "漏款"),
                onPick = {
                    input = it
                    vm.setSearchQuery(it)
                }
            )

            Spacer(Modifier.height(14.dp))

            // N54：命中的标签
            if (tagHits.isNotEmpty()) {
                Text("标签", fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(6.dp))
                CardBox {
                    Column {
                        tagHits.forEachIndexed { i, t ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.openTxnList(TxnListSpecs.tag(t.name))
                                        onOpenTxnList()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconBadge(Icons.Rounded.Label, Teal, size = 32.dp, iconSize = 17.dp)
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(t.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1)
                                    Spacer(Modifier.height(3.dp))
                                    Text("${vm.tagUsage(t)} 笔账单", fontSize = 11.sp, color = Sub)
                                }
                                Text("查看 ›", fontSize = 12.sp, color = Primary)
                            }
                            if (i != tagHits.lastIndex) RowDivider()
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            if (input.isBlank()) {
                CardBox {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = Chevron, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("输入金额、备注、分类、账户或类型", fontSize = 13.sp, color = Sub2)
                        Spacer(Modifier.height(6.dp))
                        Text("例如：88 / 晚餐 / 牙膏 / 支出 / 支付宝", fontSize = 12.sp, color = Sub)
                    }
                }
            } else if (hits.isEmpty() && tagHits.isEmpty()) {
                CardBox {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Rounded.SearchOff, null, tint = Chevron, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("没有找到符合条件的账单", fontSize = 13.sp, color = Sub2)
                        Spacer(Modifier.height(6.dp))
                        Text("换个关键词试试", fontSize = 12.sp, color = Sub)
                    }
                }
            } else {
                val net = hits.sumOf { it.txn.let { t -> displayAmount(t) } }
                CardBox {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("找到 ", fontSize = 12.sp, color = Sub)
                        Text("${hits.size}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text(" 笔", fontSize = 12.sp, color = Sub)
                        Spacer(Modifier.weight(1f))
                        Text("净额 ", fontSize = 12.sp, color = Sub)
                        AmountText(
                            net,
                            fontSize = 13.5.sp,
                            color = if (net < 0) Expense else Primary
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                // 命中结果的分组排序只在结果变化时算一次（N16）
                val byDay = remember(hits) {
                    hits.groupBy { DateFmt.dateOf(it.txn.dateMillis) }
                        .toList()
                        .sortedByDescending { it.first }
                }
                byDay.forEach { (day, list) ->
                    DayHeader(day, list.map { it.txn })
                    list.forEach { hit ->
                        Box(Modifier.padding(bottom = 6.dp)) {
                            TxnSelectableRow(
                                state = multi,
                                id = hit.txn.id,
                                onTap = { onOpenTxn(hit.txn.id) },
                                onLongPress = { multi.start(hit.txn.id) },
                                onEdit = { editing = hit.txn },
                                onDelete = { pendingDelete = hit.txn },
                                swipeState = swipe
                            ) {
                                TxnRowContent(
                                    title = txnTitle(hit.txn, catById),
                                    subtitle = DateFmt.friendly(hit.txn.dateMillis) +
                                        " · " + accountLabel(accById, hit.txn.accountId) +
                                        Repository.tagTrail(tagMap[hit.txn.id].orEmpty())
                                            .let { if (it.isEmpty()) "" else " · $it" } +
                                        " · 匹配" + hit.reasons.joinToString("、"),
                                    amountCents = displayAmount(hit.txn),
                                    amountColor = amountColor(hit.txn),
                                    bottomRight = txnBottomRight(hit.txn, accById),
                                    tag = txnTag(hit.txn),
                                    badge = { TxnBadge(hit.txn, catById) }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(if (multi.active) 190.dp else 30.dp))
        }
    }
        // N28：多选操作栏 + 批量弹窗（未进入多选时不渲染）
        TxnBatchHost(
            vm = vm,
            state = multi,
            allTxns = hits.map { it.txn },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(items: List<String>, onPick: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { k ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = FieldBg,
                modifier = Modifier.clickable { onPick(k) }
            ) {
                Text(
                    k,
                    fontSize = 12.sp,
                    color = Sub2,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                )
            }
        }
    }
}
