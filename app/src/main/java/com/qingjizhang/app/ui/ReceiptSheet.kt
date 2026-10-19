package com.qingjizhang.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qingjizhang.app.data.Repository
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.data.TxnType
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.ImageExport
import com.qingjizhang.app.util.ImageSaveResult
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.util.ReceiptData
import com.qingjizhang.app.util.ReceiptImage
import com.qingjizhang.app.util.ReceiptRow
import com.qingjizhang.app.vm.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 小票时间范围 */
private val receiptRanges = listOf("本月", "上月", "今年")
/** 小票口径 */
private val receiptFilters = listOf("显示全部", "只看支出", "只看收入")

/**
 * 小票模式（N6）：首页长按「本月结余」卡弹出。
 * 版式对齐参考图：三个筛选胶囊 + 标题 + 小票日期 / 单号 + 分类明细 + 合计 + 条码。
 */
@Composable
fun ReceiptSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val txns by vm.txns.collectAsState()
    val categories by vm.categories.collectAsState()
    val ledger by vm.currentLedger.collectAsState()
    // N20：小票是统计口径的汇总，不计入净资产的账户不参与
    val statsExcluded by vm.statsExcludedAccountIds.collectAsState()

    var rangeIdx by remember { mutableIntStateOf(0) }
    var filterIdx by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    // 单号与日期在打开小票的这一刻固定下来
    val now = remember { java.time.LocalDateTime.now() }
    val no = remember { now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) }
    val dateTime = remember { now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }

    val data = remember(txns, categories, ledger, rangeIdx, filterIdx, statsExcluded) {
        buildReceipt(
            ledgerName = ledger?.name ?: "账本",
            rangeIdx = rangeIdx,
            filterIdx = filterIdx,
            txns = txns,
            categoryNames = categories.associate { it.id to it.name },
            parentOf = categories.associate { it.id to it.parentId },
            dateTime = dateTime,
            no = no,
            excludedAccountIds = statsExcluded
        )
    }

    /** 画小票 → 写进系统「图片」目录，成功 / 失败都给一句能看懂的话 */
    fun saveReceiptImage() {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ReceiptImage.save(context, ReceiptImage.render(context, data))
                }.getOrElse { e ->
                    ImageSaveResult(false, "", e.message ?: e.javaClass.simpleName)
                }
            }
            busy = false
            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
        }
    }

    // Android 10 以下写相册要先申请存储权限，拒绝了就明确告诉用户为什么没保存成
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) saveReceiptImage()
        else Toast.makeText(context, "保存失败：未获得存储权限", Toast.LENGTH_LONG).show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(18.dp))
                .background(CardWhite)
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            // 账本 / 范围 / 口径
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Pill(data.ledgerName, false) {}
                Pill(receiptRanges[rangeIdx], true) {
                    rangeIdx = (rangeIdx + 1) % receiptRanges.size
                }
                Pill(receiptFilters[filterIdx], true) {
                    filterIdx = (filterIdx + 1) % receiptFilters.size
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "Lumo",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "小票",
                fontSize = 15.sp,
                color = Ink,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(14.dp))
            DashedLine()
            Spacer(Modifier.height(12.dp))
            ReceiptField("小票日期：", data.dateTime)
            Spacer(Modifier.height(7.dp))
            ReceiptField("小票单号：", "No." + data.no)
            Spacer(Modifier.height(10.dp))
            DashedLine()
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth()) {
                Text("分类", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Sub, modifier = Modifier.weight(1f))
                Text(
                    "笔数",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Sub,
                    modifier = Modifier.width(56.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    "金额",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Sub,
                    modifier = Modifier.width(96.dp),
                    textAlign = TextAlign.End
                )
            }
            Spacer(Modifier.height(6.dp))

            if (data.rows.isEmpty()) {
                Text(
                    "暂无数据",
                    fontSize = 12.5.sp,
                    color = Sub,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 22.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                data.rows.take(16).forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(r.label, fontSize = 13.5.sp, color = Ink, modifier = Modifier.weight(1f))
                        Text(
                            r.count.toString(),
                            fontSize = 13.5.sp,
                            color = Ink,
                            modifier = Modifier.width(56.dp),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "¥" + Money.text(r.cents),
                            fontSize = 13.5.sp,
                            color = Ink,
                            modifier = Modifier.width(96.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }
                if (data.rows.size > 16) {
                    Text(
                        "其他 ${data.rows.size - 16} 类",
                        fontSize = 12.sp,
                        color = Sub,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            DashedLine()
            Spacer(Modifier.height(12.dp))
            ReceiptField("记账笔数：", data.txnCount.toString())
            Spacer(Modifier.height(7.dp))
            ReceiptField("支出合计：", "¥" + Money.text(data.expenseCents))
            Spacer(Modifier.height(7.dp))
            ReceiptField("收入合计：", "¥" + Money.text(data.incomeCents))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("结余：", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.weight(1f))
                Text(
                    "¥" + Money.text(data.balanceCents),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
            }

            Spacer(Modifier.height(16.dp))
            Barcode(data.no)
            Spacer(Modifier.height(6.dp))
            Text(
                "*" + data.no + "*",
                fontSize = 11.5.sp,
                color = Ink,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(18.dp))
            Text(
                "小票在本机生成，保存或分享出去可用来跟家人 / 同事核对账目。" +
                    receiptRanges[rangeIdx] + " · " + receiptFilters[filterIdx] + " · 共 ${data.txnCount} 笔",
                fontSize = 11.sp,
                color = Sub,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReceiptButton("保存图片", Icons.Rounded.Download, !busy) {
                    if (ImageExport.needsStoragePermission(context)) {
                        storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        saveReceiptImage()
                    }
                }
                ReceiptButton("分享", Icons.Rounded.Share, !busy) {
                    busy = true
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            runCatching { ReceiptImage.render(context, data) }.getOrNull()
                        }
                        busy = false
                        if (file != null) ReceiptImage.share(context, file)
                        else Toast.makeText(context, "生成小票图片失败", Toast.LENGTH_SHORT).show()
                    }
                }
                ReceiptButton("关闭", Icons.Rounded.Close, true) { onDismiss() }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun Pill(text: String, clickable: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(FieldBg)
            .then(if (clickable) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 11.5.sp, color = if (clickable) Ink else Sub2)
        if (clickable) {
            Spacer(Modifier.width(3.dp))
            Icon(Icons.Rounded.ExpandMore, null, tint = Sub, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun ReceiptField(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.5.sp, color = Ink)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
}

@Composable
private fun DashedLine() {
    val color = Line
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
    ) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height.coerceAtLeast(1f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f), 0f),
            cap = StrokeCap.Round
        )
    }
}

/** 条码：用单号稳定生成，不联网、纯本地画 */
@Composable
private fun Barcode(no: String) {
    val ink = Ink
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        val widths = ArrayList<Float>()
        no.forEachIndexed { i, ch ->
            val v = (ch.code * (i + 3)) % 7
            widths.add(1f + v * 0.5f)
            widths.add(1f + ((v + i) % 4) * 0.6f)
        }
        val unit = size.width / widths.sum()
        var x = 0f
        widths.forEachIndexed { i, w ->
            val bw = w * unit
            if (i % 2 == 0) drawRect(color = ink, topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size(bw, size.height))
            x += bw
        }
    }
}

@Composable
private fun RowScope.ReceiptButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(13.dp))
            .background(if (enabled) PrimaryBg else FieldBg)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (enabled) Primary else Sub, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Primary else Sub
        )
    }
}

/**
 * 组装小票数据：按时间范围 + 口径筛选，按一级分类聚合。
 * [excludedAccountIds]：「不计入净资产」的账户（N20），它们的流水不参与小票的任何汇总。
 */
private fun buildReceipt(
    ledgerName: String,
    rangeIdx: Int,
    filterIdx: Int,
    txns: List<Txn>,
    categoryNames: Map<Long, String>,
    parentOf: Map<Long, Long>,
    dateTime: String,
    no: String,
    excludedAccountIds: Set<Long>
): ReceiptData {
    val today = LocalDate.now()
    val (start, end, rangeLabel) = when (rangeIdx) {
        1 -> {
            val m = today.minusMonths(1)
            Triple(DateFmt.monthRange(m).first, DateFmt.monthRange(m).second, m.format(DateTimeFormatter.ofPattern("yyyy-MM")))
        }
        2 -> {
            val r = DateFmt.yearRange(today)
            Triple(r.first, r.second, today.year.toString() + " 年")
        }
        else -> {
            val r = DateFmt.monthRange(today)
            Triple(r.first, r.second, today.format(DateTimeFormatter.ofPattern("yyyy-MM")))
        }
    }
    // 时间范围 + 账户范围（N20）双重过滤：小票是汇总凭证，被排除账户的流水不进小票
    val inRange = txns.filter {
        it.dateMillis in start until end && Repository.inStatsAccountScope(it, excludedAccountIds)
    }

    fun matchFilter(t: Txn): Boolean = when (filterIdx) {
        1 -> t.type == TxnType.EXPENSE
        2 -> t.type == TxnType.INCOME
        else -> true
    }

    val shown = inRange.filter { matchFilter(it) }
    val counted = shown.filter { Repository.countsInStats(it) }

    val parentName = { id: Long ->
        val pid = parentOf[id] ?: 0L
        if (pid != 0L) categoryNames[pid] ?: "未分类" else categoryNames[id] ?: "未分类"
    }
    val grouped = linkedMapOf<String, Pair<Int, Long>>()
    counted.forEach { t ->
        val key = parentName(t.categoryId)
        val cur = grouped[key] ?: (0 to 0L)
        grouped[key] = (cur.first + 1) to (cur.second + Repository.effectiveCents(t))
    }
    val rows = grouped.map { ReceiptRow(it.key, it.value.first, it.value.second) }
        .sortedByDescending { it.cents }

    val expense = counted.filter { it.type == TxnType.EXPENSE }.sumOf { Repository.effectiveCents(it) }
    val income = counted.filter { it.type == TxnType.INCOME }.sumOf { Repository.effectiveCents(it) }

    return ReceiptData(
        ledgerName = ledgerName,
        rangeLabel = rangeLabel,
        filterLabel = receiptFilters[filterIdx],
        dateTime = dateTime,
        no = no,
        rows = rows,
        txnCount = shown.size,
        expenseCents = expense,
        incomeCents = income
    )
}
