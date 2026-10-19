package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.ui.components.LocalKeyHaptic
import com.qingjizhang.app.ui.components.TextInputSheet
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.saveTag
import kotlinx.coroutines.launch

/**
 * 标签选择 / 快捷新建（N38）：记一笔底部的「标签」胶囊点击后弹它。
 *
 * - 多选：一笔账单可以带多个标签，选中项品牌色实心高亮；
 * - 快捷新建：点「新建标签」走公共文本输入窗口 [TextInputSheet]（M5 组件，不自写键盘逻辑），
 *   建好后自动选中；输入同名标签时直接复用已有标签、不重复建；
 * - 底部「完成」确认 /「清空」全部去掉 /「取消」不改动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagPickerDialog(
    vm: AppViewModel,
    selectedIds: List<Long>,
    onConfirm: (List<Long>) -> Unit,
    onDismiss: () -> Unit
) {
    val tags by vm.tags.collectAsState()
    val scope = rememberCoroutineScope()
    val haptic = LocalKeyHaptic.current
    // 草稿态：确认前不写回记一笔面板，取消即丢弃
    var selected by remember { mutableStateOf(selectedIds.toSet()) }
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        TextInputSheet(
            title = "新建标签",
            initial = "",
            placeholder = "标签名称，如：出差",
            singleLine = true,
            onCancel = { creating = false },
            onConfirm = { name ->
                creating = false
                scope.launch {
                    val id = vm.saveTag(name)
                    if (id > 0L) selected = selected + id
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = { Text("选择标签", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "可多选；也可以在下面快捷新建。标签只保存在本机，不联网。",
                    fontSize = 11.5.sp,
                    color = Sub,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))

                if (tags.isEmpty()) {
                    Text("还没有标签，点下面的「新建标签」添加", fontSize = 13.sp, color = Sub)
                    Spacer(Modifier.height(10.dp))
                } else {
                    tags.forEach { t ->
                        val on = t.id in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (on) PrimaryBg else FieldBg)
                                .clickable {
                                    haptic()
                                    selected = if (on) selected - t.id else selected + t.id
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Label,
                                contentDescription = null,
                                tint = if (on) Primary else Chevron,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                t.name,
                                fontSize = 14.sp,
                                color = if (on) Primary else Ink,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            if (on) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = "已选",
                                    tint = Primary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Surface(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .clickable { haptic(); creating = true },
                    shape = RoundedCornerShape(11.dp),
                    color = FieldBgAlt
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("新建标签", fontSize = 13.5.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { haptic(); onConfirm(selected.toList()) }) {
                Text("完成", color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { haptic(); selected = emptySet() }) {
                        Text("清空", color = Sub, fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消", color = Sub) }
            }
        }
    )
}
