package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.CsvManager
import com.qingjizhang.app.ui.components.CardBox
import com.qingjizhang.app.ui.components.RowDivider
import com.qingjizhang.app.ui.components.SectionTitle
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.cancelCsvImport
import com.qingjizhang.app.vm.confirmCsvImport
import com.qingjizhang.app.vm.excludeCsvProblemRows
import com.qingjizhang.app.vm.includeAllCsvRows
import com.qingjizhang.app.vm.setCsvField
import com.qingjizhang.app.vm.toggleCsvRow

/**
 * CSV 导入预览（M2）：先看清列对应关系与逐行解析结果，确认后再入库。
 * 导入前不改动任何数据，随时可以取消。
 */
@Composable
fun CsvImportScreen(vm: AppViewModel, onBack: () -> Unit) {
    val draft by vm.csvDraft.collectAsState()
    val busy by vm.busy.collectAsState()
    val listState = rememberLazyListState()

    val d = draft
    if (d == null) {
        Column(Modifier.fillMaxSize()) {
            com.qingjizhang.app.ui.components.BackTopBar("导入账单", { onBack() })
            com.qingjizhang.app.ui.components.EmptyState(
                icon = Icons.Rounded.FileOpen,
                title = "没有待导入的文件",
                hint = "回到上一页重新选择 CSV 文件"
            )
        }
        return
    }

    val okCount = d.preview.filterIndexed { i, p -> p.ok && i !in d.excluded }.size
    val badCount = d.preview.count { !it.ok }
    val skippedCount = d.excluded.size

    Column(Modifier.fillMaxSize()) {
        com.qingjizhang.app.ui.components.BackTopBar(
            title = "导入预览",
            onBack = {
                vm.cancelCsvImport()
                onBack()
            }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)
        ) {
            item {
                Column {
                    SectionTitle("文件")
                    CardBox {
                        Column(Modifier.padding(14.dp)) {
                            Text(d.fileName, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "共 ${d.rows.size} 行数据 · 分隔符「${delimiterLabel(d.delimiter)}」 · " +
                                    if (d.hasHeader) "已识别表头，按列名自动对应" else "没识别到表头，按列顺序对应",
                                fontSize = 11.5.sp,
                                color = Sub,
                                lineHeight = 17.sp
                            )
                        }
                    }

                    SectionTitle("列对应关系", trailing = "不对就点右侧改一下")
                    CardBox {
                        Column {
                            CsvManager.Field.entries.forEachIndexed { i, f ->
                                if (i > 0) RowDivider()
                                ColumnMappingRow(
                                    field = f,
                                    headers = d.headers,
                                    current = d.mapping[f] ?: -1,
                                    headerRow = if (d.hasHeader) d.headers else null,
                                    sampleValue = sampleOf(d, f),
                                    onPick = { vm.setCsvField(f, it) }
                                )
                            }
                        }
                    }

                    SectionTitle(
                        "逐行预览",
                        trailing = "可导入 $okCount 笔" + (if (badCount > 0) " · 有问题 $badCount 笔" else "")
                    )
                    if (badCount > 0 || skippedCount > 0) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (badCount > 0) {
                                SmallChip("跳过有问题的 $badCount 行") { vm.excludeCsvProblemRows() }
                            }
                            if (skippedCount > 0) {
                                SmallChip("恢复已跳过的 $skippedCount 行") { vm.includeAllCsvRows() }
                            }
                        }
                    }
                }
            }

            items(d.preview.size, key = { it }) { idx ->
                val p = d.preview[idx]
                val skipped = idx in d.excluded
                CsvPreviewRow(
                    index = idx,
                    preview = p,
                    skipped = skipped,
                    onToggle = { vm.toggleCsvRow(idx) }
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }

        // 底部确认条
        Column(
            Modifier
                .fillMaxWidth()
                .background(CardWhite)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                "导入前不会改动任何数据；取消随时可以退出。",
                fontSize = 11.sp,
                color = Sub
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BottomButton("取消", PrimaryBg, Primary, true) {
                    vm.cancelCsvImport()
                    onBack()
                }
                BottomButton(
                    if (busy) "导入中…" else "确认导入 $okCount 笔",
                    if (okCount > 0 && !busy) Primary else FieldBg,
                    if (okCount > 0 && !busy) OnPrimary else Sub,
                    okCount > 0 && !busy
                ) {
                    vm.confirmCsvImport()
                    onBack()
                }
            }
        }
    }
}

@Composable
private fun ColumnMappingRow(
    field: CsvManager.Field,
    headers: List<String>,
    current: Int,
    headerRow: List<String>?,
    sampleValue: String,
    onPick: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(field.label, fontSize = 13.5.sp, color = Ink)
            Spacer(Modifier.height(3.dp))
            Text(
                if (sampleValue.isBlank()) "示例：（空）" else "示例：$sampleValue",
                fontSize = 11.sp,
                color = Sub,
                maxLines = 1
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (current < 0) "不导入" else (headers.getOrNull(current) ?: "第 ${current + 1} 列"),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (current < 0) Sub else Primary,
                maxLines = 1
            )
            Icon(Icons.Rounded.ExpandMore, null, tint = Sub, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("不导入这一列", fontSize = 13.sp) },
                onClick = {
                    onPick(-1)
                    expanded = false
                }
            )
            headers.forEachIndexed { i, h ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "第 ${i + 1} 列：$h",
                            fontSize = 13.sp,
                            color = if (i == current) Primary else Ink
                        )
                    },
                    onClick = {
                        onPick(i)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun CsvPreviewRow(index: Int, preview: CsvManager.RowPreview, skipped: Boolean, onToggle: () -> Unit) {
    val ok = preview.ok
    val tint = when {
        skipped -> Sub
        ok -> Income
        else -> Expense
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CardWhite)
            .clickable { onToggle() }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when {
                skipped -> Icons.Rounded.RemoveCircleOutline
                ok -> Icons.Rounded.CheckCircle
                else -> Icons.Rounded.ErrorOutline
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "第 ${preview.lineNo} 行 · " + summaryOf(preview),
                fontSize = 12.5.sp,
                color = if (skipped) Sub else Ink,
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (skipped) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2
            )
            Spacer(Modifier.height(3.dp))
            Text(
                when {
                    skipped -> "已跳过，点一下恢复导入"
                    ok -> "点一下可以跳过这一行"
                    else -> preview.error ?: "格式不对"
                },
                fontSize = 11.sp,
                color = if (!ok && !skipped) Expense else Sub,
                maxLines = 2
            )
        }
    }
}

private fun summaryOf(p: CsvManager.RowPreview): String {
    val cat = when {
        p.catText.isBlank() -> ""
        p.subText.isBlank() -> p.catText
        else -> p.catText + "/" + p.subText
    }
    val parts = mutableListOf(p.dateText.ifBlank { "—" }, p.typeText.ifBlank { "—" })
    if (cat.isNotBlank()) parts += cat
    parts += "¥" + p.amountText.ifBlank { "—" }
    if (p.accText.isNotBlank()) parts += p.accText
    return parts.joinToString(" · ")
}

private fun delimiterLabel(c: Char): String = when (c) {
    ',' -> "逗号"
    ';' -> "分号"
    '\t' -> "Tab"
    '，' -> "中文逗号"
    else -> c.toString()
}

/** 取第一行有值的那一列做示例，方便用户确认列对不对 */
private fun sampleOf(d: CsvManager.Draft, field: CsvManager.Field): String {
    val col = d.mapping[field] ?: -1
    if (col < 0) return ""
    return d.rows.asSequence().mapNotNull { it.getOrNull(col)?.trim() }
        .firstOrNull { it.isNotEmpty() } ?: ""
}

@Composable
private fun SmallChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 11.5.sp,
        color = Primary,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(PrimaryBg)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp)
    )
}

@Composable
private fun RowScope.BottomButton(text: String, bg: Color, fg: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(13.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}
