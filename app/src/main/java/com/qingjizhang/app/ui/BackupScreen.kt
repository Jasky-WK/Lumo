package com.qingjizhang.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.qingjizhang.app.data.AutoBackup
import com.qingjizhang.app.data.BackupManager
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.BackupUi
import com.qingjizhang.app.vm.autoBackupNow
import com.qingjizhang.app.vm.backupNow
import com.qingjizhang.app.vm.deleteAutoBackup
import com.qingjizhang.app.vm.deleteBackup
import com.qingjizhang.app.vm.prepareAutoBackupRestore
import com.qingjizhang.app.vm.restoreFrom
import com.qingjizhang.app.vm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 数据备份与恢复（N23）。
 *
 * 两套东西同时在：
 * - **完整备份（.bak，推荐）**：全库数据 + 全部设置打包成一个 JSON 文件，换新手机 / 重装后导入
 *   可以完美复原（账户配置、借贷双端、还款关联、回收站、设置都在里面）。
 * - **数据库快照（旧版 .zip）**：保留原有的「数据库文件打包」能力，老用户手上已有的 zip 还能用。
 *
 * 「导出账单 CSV」属于数据交换格式（在「导入导出 CSV」页），导入后无法复原账户状态，不能当备份用。
 */
@Composable
fun BackupScreen(
    vm: AppViewModel,
    onBack: () -> Unit
) {
    val backups by vm.backups.collectAsState()
    val lastBackup by vm.lastBackupText.collectAsState()
    val busy by vm.busy.collectAsState()
    val autoOn by vm.autoBackupOn.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fulls by remember { mutableStateOf<List<BackupManager.FullBackupFile>>(emptyList()) }
    var autos by remember { mutableStateOf<List<AutoBackup.Entry>>(emptyList()) }
    var localBusy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<BackupManager.FullBackupPreview?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var result by remember { mutableStateOf<BackupManager.FullImportResult?>(null) }
    var restoreTarget by remember { mutableStateOf<BackupUi?>(null) }
    var deleteTarget by remember { mutableStateOf<BackupUi?>(null) }
    var deleteFull by remember { mutableStateOf<BackupManager.FullBackupFile?>(null) }
    var deleteAuto by remember { mutableStateOf<AutoBackup.Entry?>(null) }

    suspend fun loadFulls(): List<BackupManager.FullBackupFile> =
        withContext(Dispatchers.IO) { BackupManager.listFull(context) }

    suspend fun loadAutos(): List<AutoBackup.Entry> =
        withContext(Dispatchers.IO) { AutoBackup.list(context) }

    LaunchedEffect(Unit) {
        fulls = loadFulls()
        autos = loadAutos()
    }

    /** 恢复自动备份：先复制到缓存，再进现有的「预览 → 校验 → 覆盖导入」流程 */
    fun openAutoPreview(entry: AutoBackup.Entry) {
        scope.launch {
            localBusy = true
            val tmp = vm.prepareAutoBackupRestore(entry)
            if (tmp == null) {
                vm.toast("无法读取自动备份：" + entry.name)
            } else {
                runCatching { BackupManager.inspectFullBackup(tmp) }
                    .onSuccess { p ->
                        preview = p
                        pendingFile = tmp
                    }
                    .onFailure { vm.toast("备份文件读取失败：" + (it.message ?: "格式不正确")) }
            }
            localBusy = false
        }
    }

    /** 选文件 → 先预览，确认后再覆盖导入 */
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                localBusy = true
                runCatching { BackupManager.inspectFullBackupFromUri(context, uri) }
                    .onSuccess { (p, f) ->
                        preview = p
                        pendingFile = f
                    }
                    .onFailure { vm.toast("备份文件读取失败：" + (it.message ?: "格式不正确")) }
                localBusy = false
            }
        }
    }

    /** 打开备份列表里的某一份：先预览 */
    fun openPreview(file: File) {
        scope.launch {
            localBusy = true
            runCatching { BackupManager.inspectFullBackup(file) }
                .onSuccess { p ->
                    preview = p
                    pendingFile = file
                }
                .onFailure { vm.toast("备份文件读取失败：" + (it.message ?: "格式不正确")) }
            localBusy = false
        }
    }

    fun exportFull() {
        scope.launch {
            localBusy = true
            runCatching { BackupManager.createFullBackup(context) }
                .onSuccess { f ->
                    fulls = loadFulls()
                    vm.toast("完整备份已导出：" + f.name)
                }
                .onFailure { vm.toast("导出失败：" + (it.message ?: "")) }
            localBusy = false
        }
    }

    fun importNow() {
        val f = pendingFile ?: return
        scope.launch {
            localBusy = true
            val r = BackupManager.importFullBackup(context, f)
            result = r
            preview = null
            pendingFile = null
            localBusy = false
            fulls = loadFulls()
        }
    }

    preview?.let { p ->
        FullBackupPreviewDialog(
            preview = p,
            onConfirm = { importNow() },
            onDismiss = { preview = null; pendingFile = null }
        )
    }

    result?.let { r ->
        FullImportResultDialog(
            result = r,
            onRestart = { restartApp(context) },
            onDismiss = { result = null }
        )
    }

    restoreTarget?.let { b ->
        ConfirmDialog(
            title = "恢复这份数据库快照？",
            text = "${b.name}\n\n当前账本的数据会被这份快照覆盖。恢复前会自动给当前数据存一份快照，可以再退回来。",
            confirmText = "确认恢复",
            danger = true,
            onConfirm = { vm.restoreFrom(b.file) },
            onDismiss = { restoreTarget = null }
        )
    }
    deleteTarget?.let { b ->
        ConfirmDialog(
            title = "删除快照文件？",
            text = b.name,
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteBackup(b.file) },
            onDismiss = { deleteTarget = null }
        )
    }
    deleteFull?.let { f ->
        ConfirmDialog(
            title = "删除完整备份？",
            text = f.name,
            confirmText = "删除",
            danger = true,
            onConfirm = {
                scope.launch {
                    BackupManager.delete(f.file)
                    fulls = loadFulls()
                    vm.toast("已删除备份文件")
                }
            },
            onDismiss = { deleteFull = null }
        )
    }

    deleteAuto?.let { e ->
        ConfirmDialog(
            title = "删除这份自动备份？",
            text = e.name + "\n\n删除后无法找回。如果这是最旧的备份，删掉它就能给下个月的自动备份腾出空间。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                scope.launch {
                    vm.deleteAutoBackup(e)
                    autos = loadAutos()
                    deleteAuto = null
                }
            },
            onDismiss = { deleteAuto = null }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        BackTopBar(
            title = "数据备份与恢复",
            onBack = onBack,
            actions = {
                if (busy || localBusy) {
                    Text("处理中…", fontSize = 12.sp, color = Sub, modifier = Modifier.padding(end = 10.dp))
                }
            }
        )
        Column(Modifier.padding(horizontal = 14.dp)) {
            TipBanner(
                text = "完整备份会把全部账本、账户（含类型 / 是否计入净资产 / 隐藏 / 排序）、账单（含借贷与还款关联、报销退款、回收站）和所有设置打包成一个文件，换新手机或重装后导入即可完美复原。全程不上传云端、不需要账号。",
                icon = Icons.Rounded.Security
            )
            Spacer(Modifier.height(14.dp))

            SectionTitle("完整备份", trailing = if (fulls.isEmpty()) null else "${fulls.size} 份")
            Row {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = !busy && !localBusy) { exportFull() },
                    shape = RoundedCornerShape(14.dp),
                    color = Primary
                ) {
                    Text(
                        if (localBusy) "处理中…" else "导出完整备份",
                        modifier = Modifier.padding(vertical = 13.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(11.dp))
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = !busy && !localBusy) { picker.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(14.dp),
                    color = CardWhite
                ) {
                    Text(
                        "导入完整备份",
                        modifier = Modifier.padding(vertical = 13.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                }
            }

            if (fulls.isEmpty()) {
                Spacer(Modifier.height(11.dp))
                CardBox {
                    EmptyState(
                        icon = Icons.Rounded.Archive,
                        title = "还没有完整备份",
                        hint = "点上面的「导出完整备份」生成第一份"
                    )
                }
            } else {
                Spacer(Modifier.height(11.dp))
                CardBox {
                    Column {
                        fulls.forEach { f ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconBadge(Icons.Rounded.Save, Primary, size = 34.dp, iconSize = 17.dp, radius = 11.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        f.name,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${f.timeText} · ${f.sizeText}",
                                        fontSize = 11.sp,
                                        color = Sub,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = PrimaryBg,
                                    modifier = Modifier.clickable(enabled = !busy && !localBusy) { openPreview(f.file) }
                                ) {
                                    Text(
                                        "导入",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Primary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                IconAction(Icons.Rounded.Share, {
                                    shareBackup(context, f.file, "application/json")
                                }, tint = Sub, size = 18.dp)
                                IconAction(Icons.Rounded.Delete, { deleteFull = f }, tint = Sub, size = 18.dp)
                            }
                            if (f != fulls.last()) RowDivider()
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "导入是「覆盖式」的：确认后会先给当前数据留档到「下载/Lumo日志」，再整体写入；" +
                    "写入与校验都在一个事务里完成，中途任何一步失败都会整体回滚，不会留下半份数据。",
                fontSize = 11.5.sp,
                color = Sub,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // ---------------- v2.9.0 每月自动备份 ----------------
            SectionTitle(
                "自动备份",
                trailing = if (autos.isEmpty()) "每月一次" else "${autos.size} 份 · 最多留 3 份"
            )
            CardBox {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("每月自动备份", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "存到 Documents/Lumo/自动备份 · 保留 3 份，多了会提醒你删",
                                fontSize = 11.sp,
                                color = Sub,
                                lineHeight = 16.sp
                            )
                        }
                        ToggleSwitch(checked = autoOn, onChange = { vm.setAdv("auto_backup_on", it) })
                    }
                    RowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (autos.isEmpty()) "还没有自动备份" else "上次：" + autos.first().timeText,
                            fontSize = 12.sp,
                            color = Sub,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = PrimaryBg,
                            modifier = Modifier.clickable(enabled = !localBusy) { vm.autoBackupNow() }
                        ) {
                            Text(
                                "立即备份",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(11.dp))
            if (autos.isEmpty()) {
                CardBox {
                    EmptyState(
                        icon = Icons.Rounded.Archive,
                        title = "还没有自动备份",
                        hint = "开关打开后，每月打开 App 时会自动备份一次"
                    )
                }
            } else {
                CardBox {
                    Column {
                        autos.forEach { e ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconBadge(Icons.Rounded.Save, Teal, size = 34.dp, iconSize = 17.dp, radius = 11.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        e.name,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${e.timeText} · ${e.sizeText}",
                                        fontSize = 11.sp,
                                        color = Sub,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = PrimaryBg,
                                    modifier = Modifier.clickable(enabled = !localBusy) { openAutoPreview(e) }
                                ) {
                                    Text(
                                        "恢复",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Primary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                IconAction(Icons.Rounded.Delete, { deleteAuto = e }, tint = Sub, size = 18.dp)
                            }
                            if (e != autos.last()) RowDivider()
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "自动备份与上面的手动备份是同一份格式，恢复时同样先预览、再校验、失败整体回滚。" +
                    "最旧的自动备份不会自动删除——App 只会提醒，删不删由你决定。",
                fontSize = 11.5.sp,
                color = Sub,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(12.dp))
            SectionTitle("数据库快照（旧版）")
            CardBox {
                SettingsGroup {
                    SettingRow(
                        label = "上次快照",
                        value = if (backups.isEmpty()) "尚未备份" else lastBackup,
                        icon = Icons.Rounded.AccessTime,
                        iconTint = Teal,
                        onClick = null
                    )
                    RowDivider()
                    SettingRow(
                        label = "保存位置",
                        value = "下载/Lumo备份",
                        icon = Icons.Rounded.Folder,
                        iconTint = Purple,
                        onClick = null
                    )
                }
            }
            Spacer(Modifier.height(11.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy && !localBusy) { vm.backupNow() },
                shape = RoundedCornerShape(14.dp),
                color = CardWhite
            ) {
                Text(
                    if (busy) "处理中…" else "立即快照（仅数据库文件）",
                    modifier = Modifier.padding(vertical = 13.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primary
                )
            }
            Spacer(Modifier.height(11.dp))
            if (backups.isEmpty()) {
                CardBox {
                    EmptyState(
                        icon = Icons.Rounded.Archive,
                        title = "还没有数据库快照",
                        hint = "旧版备份只有数据库文件，不含设置；建议改用上面的完整备份"
                    )
                }
            } else {
                CardBox {
                    Column {
                        backups.forEach { b ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconBadge(Icons.Rounded.Archive, Sub, size = 34.dp, iconSize = 17.dp, radius = 11.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        b.name,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${b.timeText} · ${b.sizeText}",
                                        fontSize = 11.sp,
                                        color = Sub,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = PrimaryBg,
                                    modifier = Modifier.clickable(enabled = !busy) { restoreTarget = b }
                                ) {
                                    Text(
                                        "恢复",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Primary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                IconAction(Icons.Rounded.Share, {
                                    shareBackup(context, b.file, "application/zip")
                                }, tint = Sub, size = 18.dp)
                                IconAction(Icons.Rounded.Delete, { deleteTarget = b }, tint = Sub, size = 18.dp)
                            }
                            if (b != backups.last()) RowDivider()
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "说明：账单 CSV 属于数据交换格式（在「导入导出 CSV」页），它不会保留账户配置与借贷明细，" +
                    "导入后可能出现「未知账户」，因此不能当备份用。需要「换机完全复原」请用上面的完整备份。",
                fontSize = 11.5.sp,
                color = Sub,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** 导入前预览：签名信息 + 内容统计 + 设置概要，确认后才覆盖 */
@Composable
private fun FullBackupPreviewDialog(
    preview: BackupManager.FullBackupPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入这份完整备份？", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                PreviewLine("备份文件", preview.fileName)
                PreviewLine("生成时间", preview.createdAtText)
                PreviewLine("来源版本", "App ${preview.appVersion} · 库结构 v${preview.schemaVersion} · 格式 v${preview.formatVersion}")
                Spacer(Modifier.height(8.dp))
                PreviewLine("账本", "${preview.ledgers} 个")
                PreviewLine("账户", "${preview.accounts} 个（含账户类型 ${preview.accountTypes} 条）")
                PreviewLine("分类", "${preview.categories} 个")
                PreviewLine("账单", "${preview.txns} 笔，其中借贷 ${preview.lends} 笔、还款流水 ${preview.settlements} 笔")
                PreviewLine("周期规则", "${preview.rules} 条")
                PreviewLine("回收站", "账单 ${preview.trashTxns} 笔 · 账户 ${preview.trashAccounts} 个")
                Spacer(Modifier.height(8.dp))
                preview.settings.forEach { PreviewLine("设置", it) }
                Spacer(Modifier.height(8.dp))
                PreviewLine(
                    "数据表",
                    preview.tableRows.joinToString("、") { "${it.first} ${it.second} 行" }
                )
                if (preview.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    preview.warnings.forEach { WarningLine(it) }
                }
                Spacer(Modifier.height(10.dp))
                WarningLine("覆盖导入：当前设备上的账本、账户、账单、周期规则与设置都会被这份备份替换（导入前会自动留档一份当前数据，可人工退回）。")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("覆盖导入", color = Expense, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Sub) }
        }
    )
}

/** 导入结果：成功给明细 + 重启入口，失败说明原因并强调已回滚 */
@Composable
private fun FullImportResultDialog(
    result: BackupManager.FullImportResult,
    onRestart: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (result.success) "导入完成" else "导入失败",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (result.success) Income else Expense
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (result.success) {
                    PreviewLine("写入", result.tableRows.joinToString("、") { "${it.first} ${it.second} 行" })
                    if (result.filledColumns.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        PreviewLine(
                            "补齐默认值",
                            "备份来自更早的库结构，这些列已按默认值补齐：" +
                                result.filledColumns.take(12).joinToString("、") +
                                (if (result.filledColumns.size > 12) " 等 ${result.filledColumns.size} 列" else "")
                        )
                    }
                    if (result.droppedColumns.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        PreviewLine(
                            "忽略列",
                            "备份里有当前版本没有的列，已忽略：" + result.droppedColumns.take(12).joinToString("、")
                        )
                    }
                    result.warnings.forEach {
                        Spacer(Modifier.height(6.dp))
                        WarningLine(it)
                    }
                    if (result.safetyFiles.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        PreviewLine("导入前留档", result.safetyFiles.joinToString("、"))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "数据已经写好了。如果界面上的账本不是刚导入的那一份，点「重启应用」就能完全刷新。",
                        fontSize = 12.5.sp,
                        color = Sub2,
                        lineHeight = 18.sp
                    )
                } else {
                    Text(
                        result.error ?: "未知错误",
                        fontSize = 13.sp,
                        color = Expense,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "当前数据没有被修改（写入与校验在同一个事务里，失败已整体回滚）。",
                        fontSize = 12.5.sp,
                        color = Sub2,
                        lineHeight = 18.sp
                    )
                    if (result.safetyFiles.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        PreviewLine("导入前留档", result.safetyFiles.joinToString("、"))
                    }
                }
            }
        },
        confirmButton = {
            if (result.success) {
                TextButton(onClick = { onDismiss(); onRestart() }) {
                    Text("重启应用", color = Primary, fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("知道了", color = Primary, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = if (result.success) {
            { TextButton(onClick = onDismiss) { Text("稍后", color = Sub) } }
        } else null
    )
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label：", fontSize = 12.sp, color = Sub, modifier = Modifier.width(66.dp))
        Text(value, fontSize = 12.sp, color = Sub2, lineHeight = 17.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WarningLine(text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Rounded.Warning,
            contentDescription = null,
            tint = WarnIcon,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = WarnFg, lineHeight = 17.sp, modifier = Modifier.weight(1f))
    }
}

/** 重启进程：导入后让 ViewModel / 各页面用新数据重新初始化，最干净 */
private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        Runtime.getRuntime().exit(0)
    }, 300)
}

private fun shareBackup(context: Context, file: File, mime: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享备份文件"))
    }
}
