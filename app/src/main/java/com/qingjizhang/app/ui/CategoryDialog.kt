package com.qingjizhang.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.ui.theme.*

/**
 * 分类的新建 / 修改对话框（N1）。
 * 一级分类可以在这里选预设图标；不选就按现有数量自动分配一个，"—" 表示不显示图标。
 *
 * F19：点「保存」后立即收起弹窗（回调 onConfirm 之后再 onDismiss），回到分类列表，
 * 列表会随 Room Flow 立刻显示新名称 / 新图标；同时用 submitted 防抖，
 * 避免按钮在收起前的连点被写入两次。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryDialog(
    title: String,
    initialName: String,
    initialIconKey: String,
    withIcon: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, iconKey: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var iconKey by remember { mutableStateOf(initialIconKey) }
    // 已经提交过就不再响应点击：防止一次操作写入两条
    var submitted by remember { mutableStateOf(false) }
    val canSave = name.isNotBlank() && !submitted

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    submitted = true
                    onConfirm(name.trim(), iconKey)
                    // 保存后自动收起：既有的成功提示（vm 的 _snack）此时才不会被弹窗挡住
                    onDismiss()
                }
            ) {
                Text(
                    "保存",
                    color = if (canSave) Primary else Muted,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Sub) }
        },
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分类名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (withIcon) {
                    Spacer(Modifier.height(14.dp))
                    Text("图标（不选则自动分配）", fontSize = 12.sp, color = Sub)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        categoryIconOptions.forEach { (key, icon) ->
                            val on = iconKey == key
                            Surface(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .clickable { iconKey = if (on) "" else key },
                                shape = RoundedCornerShape(11.dp),
                                color = if (on) PrimaryBg else FieldBg,
                                border = if (on) BorderStroke(1.4.dp, Primary) else null
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = if (on) Primary else Sub2,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("最后一个「—」表示不显示图标", fontSize = 10.5.sp, color = Sub)
                }
            }
        }
    )
}
