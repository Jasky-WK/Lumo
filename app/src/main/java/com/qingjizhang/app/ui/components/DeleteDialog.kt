package com.qingjizhang.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.ui.theme.*

/**
 * 删除账单：先问一句「账户余额怎么处理」，再进回收站。
 */
@Composable
fun TxnDeleteDialog(
    summary: String,
    onDismiss: () -> Unit,
    onConfirm: (keepBalance: Boolean) -> Unit
) {
    var keepBalance by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // P1 液态玻璃：对话框用半透明玻璃底 + 高光边（「减弱视觉效果」时自动退化为不透明）
        containerColor = glass.tint,
        shape = GlassShapes.Dialog,
        modifier = if (glass.decorated) Modifier.border(1.dp, glass.border, GlassShapes.Dialog) else Modifier,
        confirmButton = {
            Button(
                onClick = { onConfirm(keepBalance) },
                colors = ButtonDefaults.buttonColors(containerColor = Expense)
            ) {
                Text("确认删除", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Primary) }
        },
        title = { Text("删除这笔账单？", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (summary.isNotBlank()) {
                    Text(summary, fontSize = 13.sp, color = Sub)
                }
                Spacer(Modifier.height(12.dp))
                Text("删除后账户余额怎么处理？", fontSize = 12.5.sp, color = Sub)
                Spacer(Modifier.height(8.dp))
                DeleteOption(
                    selected = !keepBalance,
                    title = "余额跟着动（推荐）",
                    desc = "删除后账户余额自动回滚，就像这笔账没记过。",
                    onClick = { keepBalance = false }
                )
                Spacer(Modifier.height(8.dp))
                DeleteOption(
                    selected = keepBalance,
                    title = "余额保持不变",
                    desc = "记录照删（收支统计里不再出现），同时补一笔「不计收支」的漏记款 · 余额调整，把余额补回现在的数值。",
                    onClick = { keepBalance = true }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "删除的记录会进「回收站」，保留 30 天，期间可以随时还原。",
                    fontSize = 11.5.sp,
                    color = Sub,
                    lineHeight = 16.sp
                )
            }
        }
    )
}

@Composable
private fun DeleteOption(
    selected: Boolean,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) PrimaryBg else FieldBgAlt,
        border = if (selected) BorderStroke(1.4.dp, Primary) else null
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text(desc, fontSize = 11.5.sp, color = Sub, lineHeight = 16.sp)
        }
    }
}
