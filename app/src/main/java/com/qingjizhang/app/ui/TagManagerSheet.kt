package com.qingjizhang.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.TagEntity
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.deleteTag
import com.qingjizhang.app.vm.renameTag
import com.qingjizhang.app.vm.saveTag
import com.qingjizhang.app.vm.tagUsage
import kotlinx.coroutines.launch

/**
 * 标签管理面板（N38）：对当前账本的标签做新建 / 重命名 / 删除。
 * 样式与「互转类型管理」（TransferKindManagerSheet）保持一致。
 * 删除只影响账单与标签的关联，账单本身、金额与统计都不受影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagManagerSheet(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    /** N54：点某个标签查看它名下的全部账单 */
    onOpenTag: (TagEntity) -> Unit = {}
) {
    val tags by vm.tags.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TagEntity?>(null) }
    var deleting by remember { mutableStateOf<TagEntity?>(null) }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    if (creating) {
        InputDialog(
            title = "新建标签",
            initial = "",
            label = "标签名称，如：出差",
            onConfirm = { name -> scope.launch { vm.saveTag(name) } },
            onDismiss = { creating = false }
        )
    }
    renaming?.let { t ->
        InputDialog(
            title = "重命名标签",
            initial = t.name,
            label = "标签名称",
            onConfirm = { vm.renameTag(t, it) },
            onDismiss = { renaming = null }
        )
    }
    deleting?.let { t ->
        val used = vm.tagUsage(t)
        ConfirmDialog(
            title = "删除标签「${t.name}」？",
            text = if (used == 0) {
                "该标签还没有用在任何账单上，删除后不会影响任何账单。"
            } else {
                "已有 $used 笔账单用了这个标签。删除后这些账单会同时移除该标签；" +
                    "账单本身、金额与收支统计都不受影响。"
            },
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteTag(t) },
            onDismiss = { deleting = null }
        )
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
            Text("标签管理", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "标签按账本独立，可新建 / 重命名 / 删除。记一笔时可为每笔账单选择多个标签；" +
                    "删除标签只会移除账单上的该标签，账单本身不受影响。",
                fontSize = 11.5.sp,
                color = Sub,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(10.dp))

            tags.forEachIndexed { i, t ->
                val count = vm.tagUsage(t)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // N54：点标签主体（图标 + 名称）查看该标签下的全部账单
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = count > 0) {
                                close()
                                onOpenTag(t)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconBadge(Icons.Rounded.Label, Teal, size = 34.dp, iconSize = 18.dp, radius = 11.dp)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                if (count == 0) "暂无账单" else "$count 笔账单 · 点击查看",
                                fontSize = 11.sp,
                                color = Sub
                            )
                        }
                    }
                    IconAction(Icons.Rounded.Edit, { renaming = t }, tint = Sub, size = 18.dp)
                    IconAction(Icons.Rounded.Delete, { deleting = t }, tint = Expense, size = 18.dp)
                }
                if (i < tags.lastIndex) RowDivider()
            }

            if (tags.isEmpty()) {
                EmptyState(Icons.Rounded.Label, "还没有标签", "点下面的「新建标签」添加")
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
                    Text("新建标签", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Primary)
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
