package com.qingjizhang.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.TransferKindEntity
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.addTransferKind
import com.qingjizhang.app.vm.deleteTransferKind
import com.qingjizhang.app.vm.renameTransferKind
import com.qingjizhang.app.vm.transferKindUsage
import kotlinx.coroutines.launch

/**
 * 互转类型管理面板（N26）：对当前账本的互转胶囊做新建 / 重命名 / 删除。
 * 删除时若该类型下已有账单，必须先选一个迁移目标，历史流水的标记不会丢。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransferKindManagerSheet(
    vm: AppViewModel,
    onDismiss: () -> Unit
) {
    val kinds by vm.transferKinds.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TransferKindEntity?>(null) }
    var deleting by remember { mutableStateOf<TransferKindEntity?>(null) }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    if (creating) {
        InputDialog(
            title = "新建互转类型",
            initial = "",
            label = "类型名称，如：取现",
            onConfirm = { vm.addTransferKind(it) },
            onDismiss = { creating = false }
        )
    }
    renaming?.let { k ->
        InputDialog(
            title = "重命名互转类型",
            initial = k.name,
            label = "类型名称",
            onConfirm = { vm.renameTransferKind(k, it) },
            onDismiss = { renaming = null }
        )
    }
    deleting?.let { k ->
        val used = vm.transferKindUsage(k.name)
        if (used == 0) {
            ConfirmDialog(
                title = "删除「${k.name}」？",
                text = "该类型下没有账单，删除后不会影响任何流水。",
                confirmText = "删除",
                danger = true,
                onConfirm = {
                    vm.deleteTransferKind(k, kinds.firstOrNull { it.id != k.id }?.name ?: k.name)
                },
                onDismiss = { deleting = null }
            )
        } else {
            MigrateTransferKindDialog(
                kind = k,
                usage = used,
                targets = kinds.filter { it.id != k.id },
                countOf = { name -> vm.transferKindUsage(name) },
                onPick = { target ->
                    vm.deleteTransferKind(k, target.name)
                    deleting = null
                },
                onDismiss = { deleting = null }
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CardWhite
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Text("管理互转类型", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "互转类型按账本独立，可新建 / 重命名 / 删除；改成其它名字或删除时，已用过它的流水会一起迁到目标类型，标记不会丢。",
                fontSize = 11.5.sp,
                color = Sub,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(10.dp))

            kinds.forEachIndexed { i, k ->
                val count = vm.transferKindUsage(k.name)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBadge(Icons.Rounded.SwapHoriz, Primary, size = 34.dp, iconSize = 18.dp, radius = 11.dp)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(k.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (count == 0) "暂无流水" else "$count 笔流水",
                            fontSize = 11.sp,
                            color = Sub
                        )
                    }
                    IconAction(Icons.Rounded.Edit, { renaming = k }, tint = Sub, size = 18.dp)
                    IconAction(Icons.Rounded.Delete, { deleting = k }, tint = Expense, size = 18.dp)
                }
                if (i < kinds.lastIndex) RowDivider()
            }

            if (kinds.isEmpty()) {
                EmptyState(Icons.Rounded.SwapHoriz, "还没有互转类型", "点下面的「新建类型」添加")
            }

            Spacer(Modifier.height(12.dp))
            Surface(
                Modifier
                    .fillMaxWidth()
                    .clickable { creating = true },
                shape = RoundedCornerShape(13.dp),
                color = PrimaryBg
            ) {
                Row(
                    Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Add, null, tint = Primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建类型", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Primary)
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                Modifier
                    .fillMaxWidth()
                    .clickable { close() },
                shape = RoundedCornerShape(13.dp),
                color = FieldBg
            ) {
                Text(
                    "完成",
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Sub2,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
    }
}

/**
 * 删除互转类型但已有流水：先让用户挑一个迁移目标，
 * 点选即等于确认（历史流水整体迁过去，再删类型）。
 */
@Composable
private fun MigrateTransferKindDialog(
    kind: TransferKindEntity,
    usage: Int,
    targets: List<TransferKindEntity>,
    countOf: (String) -> Int,
    onPick: (TransferKindEntity) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除「${kind.name}」？", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "已有 $usage 笔流水用过这个类型，请选择把它们迁到：",
                    fontSize = 13.5.sp,
                    color = Sub2,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(8.dp))
                targets.forEach { t ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .clickable { onPick(t) },
                        shape = RoundedCornerShape(11.dp),
                        color = FieldBg
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                t.name,
                                fontSize = 13.5.sp,
                                color = Ink,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Spacer(Modifier.weight(1f))
                            Text("已有 ${countOf(t.name)} 笔", fontSize = 11.sp, color = Sub)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "流水只是换个类型标记，金额与账户余额不受影响。",
                    fontSize = 11.sp,
                    color = Sub,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Sub) }
        }
    )
}
