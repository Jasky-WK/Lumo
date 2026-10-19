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
import com.qingjizhang.app.data.AccountTypeEntity
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.addAccountType
import com.qingjizhang.app.vm.deleteAccountType
import com.qingjizhang.app.vm.renameAccountType
import kotlinx.coroutines.launch

/**
 * 账户类型管理面板（N19）：对当前账本的类型做新建 / 重命名 / 删除。
 * 删除时若该类型下还有账户，必须先选一个迁移目标，账户不会静默丢失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountTypeManagerSheet(
    vm: AppViewModel,
    onDismiss: () -> Unit
) {
    val types by vm.accountTypes.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AccountTypeEntity?>(null) }
    var deleting by remember { mutableStateOf<AccountTypeEntity?>(null) }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    if (creating) {
        InputDialog(
            title = "新建账户类型",
            initial = "",
            label = "类型名称",
            onConfirm = { vm.addAccountType(it) },
            onDismiss = { creating = false }
        )
    }
    renaming?.let { t ->
        InputDialog(
            title = "重命名类型",
            initial = t.name,
            label = "类型名称",
            onConfirm = { vm.renameAccountType(t, it) },
            onDismiss = { renaming = null }
        )
    }
    deleting?.let { t ->
        val used = accounts.count { it.type == t.name }
        if (used == 0) {
            ConfirmDialog(
                title = "删除类型「${t.name}」？",
                text = "该类型下没有账户，删除后不会影响任何账户与账单。",
                confirmText = "删除",
                danger = true,
                // 没有账户要迁，随便指一个仍存在的类型即可（只是占位，不会改到任何账户）
                onConfirm = {
                    vm.deleteAccountType(t, types.firstOrNull { it.id != t.id }?.name ?: t.name)
                },
                onDismiss = { deleting = null }
            )
        } else {
            MigrateTypeDialog(
                type = t,
                accountCount = used,
                targets = types.filter { it.id != t.id },
                countOf = { name -> accounts.count { it.type == name } },
                onPick = { target ->
                    vm.deleteAccountType(t, target.name)
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
            Text("管理账户类型", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "类型按账本独立，可新建 / 重命名 / 删除；改名或删除后，其下的账户会自动归到新类型，账单不受影响。",
                fontSize = 11.5.sp,
                color = Sub,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(10.dp))

            types.forEachIndexed { i, t ->
                val count = accounts.count { it.type == t.name }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBadge(
                        accountIcon(t.name),
                        accountColor(t.name),
                        size = 34.dp,
                        iconSize = 18.dp,
                        radius = 11.dp
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (count == 0) "暂无账户（账户页不显示）" else "$count 个账户",
                            fontSize = 11.sp,
                            color = Sub
                        )
                    }
                    IconAction(Icons.Rounded.Edit, { renaming = t }, tint = Sub, size = 18.dp)
                    IconAction(Icons.Rounded.Delete, { deleting = t }, tint = Expense, size = 18.dp)
                }
                if (i < types.lastIndex) RowDivider()
            }

            if (types.isEmpty()) {
                EmptyState(Icons.Rounded.Category, "还没有账户类型", "点下面的「新建类型」添加")
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
 * 删除类型但该类型下还有账户：先让用户挑一个迁移目标，
 * 点选即等于确认（账户整体迁过去，再删类型）。
 */
@Composable
private fun MigrateTypeDialog(
    type: AccountTypeEntity,
    accountCount: Int,
    targets: List<AccountTypeEntity>,
    countOf: (String) -> Int,
    onPick: (AccountTypeEntity) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除类型「${type.name}」？", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "该类型下还有 $accountCount 个账户，请选择迁移到：",
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
                            Text("已有 ${countOf(t.name)} 个账户", fontSize = 11.sp, color = Sub)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "账户只是换个分组，余额与账单不受影响。",
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
