package com.qingjizhang.app.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.qingjizhang.app.data.Seed
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.addLedger
import com.qingjizhang.app.vm.beginCsvImport
import com.qingjizhang.app.vm.createDemoLedger
import com.qingjizhang.app.vm.deleteLedger
import com.qingjizhang.app.vm.downloadTemplate
import com.qingjizhang.app.vm.exportCsv
import com.qingjizhang.app.vm.exportExcel
import com.qingjizhang.app.vm.openTxnList
import com.qingjizhang.app.vm.renameLedger
import com.qingjizhang.app.vm.setBudgetRemindEnabled
import com.qingjizhang.app.vm.setKeySound
import com.qingjizhang.app.vm.setNoteMemoryEnabled
import com.qingjizhang.app.vm.iaMoveHintPending
import com.qingjizhang.app.vm.markIaMoveHintShown
import com.qingjizhang.app.vm.setReminderEnabled
import com.qingjizhang.app.vm.setThemeColor
import com.qingjizhang.app.vm.setThemeMode
import com.qingjizhang.app.vm.setVibrateFeedback
import com.qingjizhang.app.vm.switchLedger
import com.qingjizhang.app.vm.toast
import com.qingjizhang.app.vm.trashAllTxns

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenLedgers: () -> Unit,
    onOpenRecycle: () -> Unit,
    onOpenImportPreview: () -> Unit,
    onOpenHelp: () -> Unit,
    /** N54：从标签管理点标签后跳到「该标签的账单列表」 */
    onOpenTxnList: () -> Unit = {},
    /** v2.7.0：二级页的区段 key（null = 设置首页菜单） */
    section: String? = null,
    /** 菜单点某一组 → 打开该组的二级页 */
    onOpenSection: (String) -> Unit = {}
) {
    val ledger by vm.currentLedger.collectAsState()
    val ledgers by vm.ledgers.collectAsState()
    val rules by vm.rules.collectAsState()
    val categories by vm.categories.collectAsState()
    val themeColor by vm.themeColor.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val noteMemoryOn by vm.noteMemoryEnabled.collectAsState()
    val vibrateOn by vm.vibrateFeedback.collectAsState()
    // N39：按键音（与振动反馈相互独立）
    val soundOn by vm.keySound.collectAsState()
    // P1：新增设置项的状态
    val warnPercent by vm.warnPercent.collectAsState()
    val suggestOn by vm.suggestEnabled.collectAsState()
    val reduceEffects by vm.reduceEffects.collectAsState()
    // 标签（N38）：记账组里的「标签管理」入口
    val tags by vm.tags.collectAsState()
    val context = LocalContext.current

    // CSV 账单导入：选完文件先到预览页确认列与逐行结果，再入库
    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.beginCsvImport(uri)
            onOpenImportPreview()
        }
    }
    // 周期提醒：Android 13+ 需要通知权限，先申请再开启
    val reminderOn by vm.reminderEnabled.collectAsState()
    val notifPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        vm.setReminderEnabled(granted)
        if (!granted) vm.toast("没有通知权限，发不出提醒；可在系统设置里手动打开")
    }
    // N57：预算超支提醒（独立申请通知权限，不与周期提醒互相顶掉）
    val budgetRemindOn by vm.budgetRemindEnabled.collectAsState()
    val notifPermissionBudget = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        vm.setBudgetRemindEnabled(granted)
        if (!granted) vm.toast("没有通知权限，发不出提醒；可在系统设置里手动打开")
    }
    val backups by vm.backups.collectAsState()
    val lastBackup by vm.lastBackupText.collectAsState()
    val allTxns by vm.txns.collectAsState()
    val txnCount = allTxns.size

    var showBudget by remember { mutableStateOf(false) }
    // N33：删除全部账单分两步 —— 先红色危险警示（含「去备份」入口），再二次确认才执行
    var showClear by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    // N38：标签管理面板（新建 / 重命名 / 删除）
    var showTagManager by remember { mutableStateOf(false) }

    if (showTagManager) {
        TagManagerSheet(
            vm = vm,
            onDismiss = { showTagManager = false },
            // N54：点标签主体 → 打开「标签账单页」，只显示带这个标签的账单
            onOpenTag = { t ->
                vm.openTxnList(TxnListSpecs.tag(t.name))
                showTagManager = false
                onOpenTxnList()
            }
        )
    }

    if (showBudget) {
        InputDialog(
            title = "设置月度预算",
            initial = if ((ledger?.monthlyBudgetCents ?: 0L) > 0L) Money.text(ledger!!.monthlyBudgetCents) else "",
            label = "预算金额（元），留空表示不设预算",
            numberOnly = true,
            onConfirm = { text ->
                val cents = if (text.isBlank()) 0L else (Money.parse(text) ?: 0L)
                vm.setBudget(cents)
            },
            onDismiss = { showBudget = false }
        )
    }
    // 第一步：红色危险警示 + 强制提醒先备份，并提供「去备份」直达备份页
    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Expense) },
            title = {
                Text("删除全部账单？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Expense)
            },
            text = {
                Column {
                    Text(
                        "这是危险操作：当前账本的全部 $txnCount 笔账单都会被删除。",
                        fontSize = 13.5.sp,
                        color = Ink,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "账单会先进入回收站、保留 30 天，期间可以随时还原；超过 30 天自动彻底删除。" +
                            "删除后各账户余额会回退为初始余额。",
                        fontSize = 12.5.sp,
                        color = Sub2,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "强烈建议先做一次完整备份：备份文件保存在手机本地，可随时恢复。",
                        fontSize = 12.5.sp,
                        color = Expense,
                        lineHeight = 19.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    showClearConfirm = true
                }) {
                    Text("继续删除", color = Expense, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        showClear = false
                        onOpenBackup()
                    }) {
                        Text("去备份", color = Primary, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { showClear = false }) { Text("取消", color = Sub) }
                }
            }
        )
    }
    // 第二步：二次确认后才真正执行（危险确认按钮用 Expense 红色）
    if (showClearConfirm) {
        ConfirmDialog(
            title = "再次确认：删除全部账单？",
            text = "当前账本共 $txnCount 笔账单将全部移入回收站（保留 30 天，可随时还原）。" +
                "确认后各账户余额会回退为初始余额。此操作请谨慎。",
            confirmText = "确认删除",
            danger = true,
            onConfirm = { vm.trashAllTxns() },
            onDismiss = { showClearConfirm = false }
        )
    }

    // P1 IA 重组：设置项改为「数据驱动的行」——顶部搜索才能直接过滤；
    // 分组从功能域改成用户场景：记账偏好（开关型）/ 账本与分类（入口型）/ 数据（任务型）/ 外观 / 关于
    var settingsQuery by remember { mutableStateOf("") }
    var showIaHint by remember { mutableStateOf(vm.iaMoveHintPending()) }
    var showWarnLine by remember { mutableStateOf(false) }
    // v2.8.0 高级功能开关
    val advArAp by vm.advArAp.collectAsState()
    val advLend by vm.advLend.collectAsState()
    val advCustomBudget by vm.advCustomBudget.collectAsState()
    val advTags by vm.advTags.collectAsState()
    val advReceipt by vm.advReceipt.collectAsState()
    val advQuickEntry by vm.advQuickEntry.collectAsState()
    val autoBackupOn by vm.autoBackupOn.collectAsState()

    val settingsItems = listOf(
        // ---------------- 高级功能（v2.8.0）：默认全开，只有一句话记账默认关 ----------------
        SettingItem("高级功能", "一句话记账（Beta）", "一句话 快速 语音 beta 输入") {
            AdvSwitchRow(
                label = "一句话记账（Beta）",
                desc = "打开后，记一笔加号上方会出现一个小圆按钮，可以说「午饭 25 支付宝」直接记",
                checked = advQuickEntry,
                onChange = { vm.setAdv("adv_quick_entry", it) }
            )
        },
        SettingItem("高级功能", "应收应付", "应收 应付 备忘 ar ap") {
            AdvSwitchRow("应收应付", "关掉后隐藏左侧栏与总览的入口", advArAp) { vm.setAdv("adv_arap", it) }
        },
        SettingItem("高级功能", "借贷汇总", "借出 借入 待还 待收") {
            AdvSwitchRow("借贷汇总", "关掉后隐藏总览的借贷卡片", advLend) { vm.setAdv("adv_lend", it) }
        },
        SettingItem("高级功能", "自定义预算", "分类预算 专项预算") {
            AdvSwitchRow("自定义预算", "关掉后隐藏设置与总览的自定义预算", advCustomBudget) { vm.setAdv("adv_custom_budget", it) }
        },
        SettingItem("高级功能", "标签", "标签 tag 标记") {
            AdvSwitchRow("标签", "关掉后记一笔里不再显示标签行", advTags) { vm.setAdv("adv_tags", it) }
        },
        SettingItem("高级功能", "小票附件", "小票 照片 图片 凭证") {
            AdvSwitchRow("小票附件", "关掉后账单详情里不再显示小票", advReceipt) { vm.setAdv("adv_receipt", it) }
        },
        // ---------------- 记账偏好（开关 / 数值，天天用） ----------------
        SettingItem("记账偏好", "月度预算", "预算 金额 上限 flag") {
            SettingRow(
                label = "月度预算",
                value = if ((ledger?.monthlyBudgetCents ?: 0L) > 0L)
                    "¥" + Money.text(ledger!!.monthlyBudgetCents) else "未设置",
                icon = Icons.Rounded.Flag,
                iconTint = Teal,
                onClick = { showBudget = true }
            )
        },
        SettingItem("记账偏好", "预算预警线", "预算 预警 提醒 80 90 到线") {
            SettingRow(
                label = "预算预警线",
                value = if (warnPercent >= 100) "不预警（只在超支时提醒）" else "预算的 $warnPercent%",
                icon = Icons.Rounded.Speed,
                iconTint = Orange,
                onClick = { showWarnLine = true }
            )
        },
        SettingItem("记账偏好", "预算超支提醒", "预算 超支 通知 提醒") {
            SettingRow(
                label = "预算超支提醒",
                value = if (budgetRemindOn) "已开启 · 到线 / 超支时通知" else "未开启",
                icon = Icons.Rounded.Savings,
                iconTint = Income,
                onClick = {
                    val ctx = context
                    val needPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            ctx, android.Manifest.permission.POST_NOTIFICATIONS
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    when {
                        budgetRemindOn -> vm.setBudgetRemindEnabled(false)
                        needPermission -> notifPermissionBudget.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        else -> vm.setBudgetRemindEnabled(true)
                    }
                }
            )
        },
        SettingItem("记账偏好", "周期提醒", "周期 到期 通知 自动") {
            SettingRow(
                label = "周期提醒",
                value = if (reminderOn) "已开启 · 到期前通知" else "未开启",
                icon = Icons.Rounded.NotificationsActive,
                iconTint = Orange,
                onClick = {
                    val ctx = context
                    val needPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            ctx, android.Manifest.permission.POST_NOTIFICATIONS
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    when {
                        reminderOn -> vm.setReminderEnabled(false)
                        needPermission -> notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        else -> vm.setReminderEnabled(true)
                    }
                }
            )
        },
        SettingItem("记账偏好", "记账建议", "建议 预测 智能 推荐 分类") {
            SettingRow(
                label = "记账建议",
                value = if (suggestOn) "已开启 · 按历史习惯推荐分类与账户" else "未开启",
                icon = Icons.Rounded.AutoAwesome,
                iconTint = Purple,
                onClick = { vm.setSuggestEnabled(!suggestOn) }
            )
        },
        SettingItem("记账偏好", "备注记忆", "备注 联想 记忆") {
            SettingRow(
                label = "备注记忆",
                value = if (noteMemoryOn) "已开启 · 记过的备注可联想" else "未开启",
                icon = Icons.Rounded.History,
                iconTint = Sky,
                onClick = { vm.setNoteMemoryEnabled(!noteMemoryOn) }
            )
        },
        SettingItem("记账偏好", "振动反馈", "振动 触感 按键") {
            SettingRow(
                label = "振动反馈",
                value = if (vibrateOn) "已开启 · 按键与选择分类轻振一下" else "未开启",
                icon = Icons.Rounded.Vibration,
                iconTint = Teal,
                onClick = { vm.setVibrateFeedback(!vibrateOn) }
            )
        },
        SettingItem("记账偏好", "按键音", "按键 音效 声音") {
            SettingRow(
                label = "按键音",
                value = if (soundOn) "已开启 · 按键与选择分类轻响，静音时也响" else "未开启",
                icon = Icons.Rounded.MusicNote,
                iconTint = Purple,
                onClick = { vm.setKeySound(!soundOn) }
            )
        },

        // ---------------- 账本与分类（页面入口） ----------------
        SettingItem("账本与分类", "当前账本", "账本 切换 当前") {
            SettingRow(
                label = "当前账本",
                value = ledger?.name ?: "—",
                icon = Icons.Rounded.MenuBook,
                onClick = onOpenLedgers
            )
        },
        SettingItem("账本与分类", "账本管理", "账本 新建 重命名 删除") {
            SettingRow(
                label = "账本管理",
                value = "${ledgers.size} 个",
                icon = Icons.Rounded.LibraryBooks,
                iconTint = Purple,
                onClick = onOpenLedgers
            )
        },
        SettingItem("账本与分类", "分类管理", "分类 一级 二级 图标") {
            SettingRow(
                label = "分类管理",
                value = "${categories.count { it.parentId == 0L }} 个一级",
                icon = Icons.Rounded.Category,
                iconTint = Amber,
                onClick = onOpenCategories
            )
        },
        SettingItem("账本与分类", "标签管理", "标签 tag 多标签") {
            SettingRow(
                label = "标签管理",
                value = if (tags.isEmpty()) "未设置" else "${tags.size} 个标签",
                icon = Icons.Rounded.Label,
                iconTint = Pink,
                onClick = { showTagManager = true }
            )
        },
        SettingItem("账本与分类", "周期记账", "周期 规则 每月 自动记账") {
            SettingRow(
                label = "周期记账",
                value = "${rules.size} 条规则",
                icon = Icons.Rounded.Autorenew,
                onClick = onOpenRecurring
            )
        },

        // ---------------- 数据（任务型；从抽屉搬过来） ----------------
        SettingItem("数据", "生成示例账本", "示例 演示 demo 体验 新手") {
            SettingRow(
                label = "生成示例账本",
                value = "独立账本 · 可整本删除",
                icon = Icons.Rounded.Star,
                iconTint = Amber,
                onClick = { vm.createDemoLedger() }
            )
        },
        SettingItem("数据", "每月自动备份", "自动备份 提醒 保留 三份 安全") {
            AdvSwitchRow(
                label = "每月自动备份",
                desc = "每月打开 App 时自动备份一次，存到 Documents/Lumo/自动备份；满 3 份提醒你删最旧的",
                checked = autoBackupOn,
                onChange = { vm.setAdv("auto_backup_on", it) }
            )
        },
        SettingItem("数据", "数据备份与恢复", "备份 恢复 bak 导入 导出") {
            SettingRow(
                label = "数据备份与恢复",
                value = if (backups.isEmpty()) "尚未备份" else lastBackup,
                icon = Icons.Rounded.Restore,
                iconTint = Purple,
                onClick = onOpenBackup
            )
        },
        SettingItem("数据", "回收站", "回收站 还原 彻底删除") {
            SettingRow(
                label = "回收站",
                value = "删除的账单 / 账户 / 账本保留 30 天",
                icon = Icons.Rounded.DeleteOutline,
                iconTint = Expense,
                onClick = onOpenRecycle
            )
        },
        SettingItem("数据", "导入账单 CSV", "导入 csv 账单 模板") {
            SettingRow(
                label = "导入账单 CSV",
                value = "从下载的模板文件导入",
                icon = Icons.Rounded.Download,
                iconTint = Teal,
                onClick = { importPicker.launch(arrayOf("*/*")) }
            )
        },
        SettingItem("数据", "导出账单为 CSV", "导出 csv excel 表格") {
            SettingRow(
                label = "导出账单为 CSV",
                value = "Excel 可直接打开",
                icon = Icons.Rounded.Upload,
                iconTint = Teal,
                onClick = { vm.exportCsv() }
            )
        },
        SettingItem("数据", "导出账单为 Excel", "导出 xlsx excel 求和") {
            SettingRow(
                label = "导出账单为 Excel",
                value = ".xlsx · 金额可求和",
                icon = Icons.Rounded.TableChart,
                iconTint = Income,
                onClick = { vm.exportExcel() }
            )
        },
        SettingItem("数据", "下载导入模板", "模板 csv 下载") {
            SettingRow(
                label = "下载导入模板",
                value = "CSV",
                icon = Icons.Rounded.Download,
                iconTint = Primary,
                onClick = { vm.downloadTemplate() }
            )
        },
        SettingItem("数据", "删除全部账单", "删除 清空 危险 全部账单") {
            SettingRow(
                label = "删除全部账单",
                value = "危险操作 · 先备份",
                icon = Icons.Rounded.DeleteForever,
                onClick = { showClear = true },
                danger = true
            )
        },

        // ---------------- 外观 ----------------
        SettingItem("外观", "主题色", "主题 颜色 配色 蓝 绿 白") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("主题色", fontSize = 14.sp, color = Ink)
                Spacer(Modifier.weight(1f))
                ThemeColor.values().forEach { c ->
                    val on = c == themeColor
                    Box(
                        Modifier
                            .padding(start = 10.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(themeSwatch(c))
                            .clickable { vm.setThemeColor(c) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (on) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = c.label,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        },
        SettingItem("外观", "深浅模式", "深色 浅色 跟随系统 夜间") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("深浅模式", fontSize = 14.sp, color = Ink)
                Spacer(Modifier.weight(1f))
                ThemeMode.values().forEach { m ->
                    val on = m == themeMode
                    Text(
                        m.label,
                        fontSize = 12.5.sp,
                        color = if (on) Primary else Sub,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (on) PrimaryBg else FieldBg)
                            .clickable { vm.setThemeMode(m) }
                            .padding(horizontal = 11.dp, vertical = 6.dp)
                    )
                }
            }
        },
        SettingItem("外观", "减弱视觉效果", "毛玻璃 玻璃 动效 性能 卡顿 减弱") {
            SettingRow(
                label = "减弱视觉效果",
                value = if (reduceEffects) "已开启 · 毛玻璃换成纯色卡片" else "未开启 · 玻璃质感",
                icon = Icons.Rounded.BlurOn,
                iconTint = Sky,
                onClick = { vm.setReduceEffects(!reduceEffects) }
            )
        },
        SettingItem("外观", "当前外观", "外观 主题 汇总") {
            SettingRow(
                label = "当前外观",
                value = themeColor.label + "色 · " + themeMode.label +
                    (if (reduceEffects) " · 减弱效果" else ""),
                icon = Icons.Rounded.Palette,
                iconTint = Primary
            )
        },

        // ---------------- 关于 ----------------
        SettingItem("关于", "帮助与反馈", "帮助 反馈 常见问题 更新日志") {
            SettingRow(
                label = "帮助与反馈",
                value = "常见问题 + 更新日志 + 本地数据说明",
                icon = Icons.Rounded.HelpOutline,
                iconTint = Primary,
                onClick = onOpenHelp
            )
        },
        SettingItem("关于", "隐私说明", "隐私 本地 不联网 权限 安全") {
            SettingRow(
                label = "隐私说明",
                value = "纯本地 · 不联网 · 无网络权限",
                icon = Icons.Rounded.Security,
                iconTint = Income,
                onClick = onOpenHelp
            )
        },
        SettingItem("关于", "关于Lumo", "版本 关于 更新") {
            SettingRow(
                label = "关于Lumo",
                value = "v" + com.qingjizhang.app.BuildConfig.VERSION_NAME + " · 本地数据说明",
                icon = Icons.Rounded.Info,
                iconTint = Sub,
                onClick = onOpenHelp
            )
        }
    )

    val currentSection = SETTINGS_SECTIONS.firstOrNull { it.key == section }

    // 预算预警线选择（80 / 90 / 不预警）
    if (showWarnLine) {
        AlertDialog(
            onDismissRequest = { showWarnLine = false },
            title = { Text("预算预警线", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "账单花到预算的这个比例时先提醒你一次（真的超支还会再提醒一次）。" +
                            "每个自然月最多各一条。",
                        fontSize = 13.sp,
                        color = Sub2,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    listOf(80, 90, 100).forEach { p ->
                        val on = p == warnPercent
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    vm.setWarnPercent(p)
                                    showWarnLine = false
                                }
                                .padding(horizontal = 6.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (p == 100) "不预警（只在超支时提醒）" else "预算的 $p%",
                                fontSize = 14.sp,
                                color = if (on) Primary else Ink,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                            )
                            Spacer(Modifier.weight(1f))
                            if (on) Icon(Icons.Rounded.Check, null, tint = Primary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWarnLine = false }) { Text("关闭", color = Sub) }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        BackTopBar(currentSection?.title ?: "设置", onBack)
        Column(Modifier.padding(horizontal = 14.dp)) {

            // 搜索只在设置首页出现（二级页里没必要，组内就几行）
            if (currentSection == null) Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 12.dp),
                shape = RoundedCornerShape(14.dp),
                color = CardWhite,
                shadowElevation = 1.dp
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Sub, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = settingsQuery,
                        onValueChange = { settingsQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = Ink),
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 11.dp),
                        decorationBox = { inner ->
                            if (settingsQuery.isEmpty()) {
                                Text("搜索设置（导出 / 预算 / 主题 …）", fontSize = 13.5.sp, color = Muted)
                            }
                            inner()
                        }
                    )
                    if (settingsQuery.isNotEmpty()) {
                        IconAction(Icons.Rounded.Close, { settingsQuery = "" }, tint = Sub, size = 16.dp)
                    }
                }
            }

            // P1：改版一次性引导（导入导出与回收站从抽屉搬到这里）
            if (showIaHint) {
                TipBanner(
                    text = "导入导出、回收站、下载模板已收纳到下面「数据」组；左侧栏精简为 7 项，找东西更快。",
                    icon = Icons.Rounded.MoveDown
                )
                Text(
                    "知道了",
                    fontSize = 12.5.sp,
                    color = Primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable {
                            vm.markIaMoveHintShown()
                            showIaHint = false
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Spacer(Modifier.height(8.dp))
            }

            val query = settingsQuery.trim()

            if (currentSection == null) {
                // ---------- 设置首页：只有 5 个入口（28 行 → 8 行）----------
                if (query.isEmpty()) {
                    SettingsGroupTitle("全部设置")
                    SettingsGroup {
                        SETTINGS_SECTIONS.forEachIndexed { index, sec ->
                            if (index > 0) RowDivider()
                            SettingRow(
                                label = sec.title,
                                value = sec.summary,
                                icon = sec.icon,
                                iconTint = sec.tint,
                                onClick = { onOpenSection(sec.key) }
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "共 ${settingsItems.size} 项设置，分成 5 组；直接搜索也可以",
                        fontSize = 11.5.sp,
                        color = Muted2,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Spacer(Modifier.height(30.dp))
                } else {
                    val results = settingsItems.filter {
                        it.label.contains(query, ignoreCase = true) ||
                            it.keywords.contains(query, ignoreCase = true)
                    }
                    if (results.isEmpty()) {
                        CardBox {
                            Text(
                                "没有匹配的设置项",
                                fontSize = 13.sp,
                                color = Sub,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        // 搜索结果：按组展示，点任意一条直接进对应二级页
                        results.groupBy { it.group }.forEach { (group, list) ->
                            SettingsGroupTitle(group)
                            SettingsGroup {
                                list.forEachIndexed { index, item ->
                                    if (index > 0) RowDivider()
                                    SettingRow(
                                        label = item.label,
                                        value = "去$group 里看 ›",
                                        onClick = {
                                            SETTINGS_SECTIONS.firstOrNull { it.title == group }
                                                ?.let { onOpenSection(it.key) }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(30.dp))
                }
            } else {
            val shown = settingsItems.filter { it.group == currentSection.title }
            if (shown.isEmpty()) {
                CardBox {
                    Text(
                        "这组里暂时没有设置项",
                        fontSize = 13.sp,
                        color = Sub,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
            // 同一组的行装进同一张卡片，行与行之间加分隔线（与改造前视觉一致）
            shown.groupBy { it.group }.forEach { (group, list) ->
                SettingsGroupTitle(group)
                SettingsGroup {
                    list.forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        item.render()
                    }
                }
                // 自定义预算属于「记账偏好」的一部分，跟在它后面；
                // 搜索时不显示（它不是一条设置行，混在筛选结果里会让人困惑）
                if (group == "记账偏好" && query.isEmpty()) CustomBudgetSection(vm)
            }

            Spacer(Modifier.height(30.dp))
            }
        }
    }
}

/** 设置页里主题色的色点（用固定的主色常量，切换后一眼能看出差别） */
private fun themeSwatch(c: ThemeColor): Color = when (c) {
    ThemeColor.BLUE -> BrandBlue
    ThemeColor.GREEN -> Income
    ThemeColor.WHITE -> Color(0xFF5A6472)
}

@Composable
fun SettingsGroupTitle(text: String) {
    Text(
        text,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Bold,
        color = Muted2,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    CardBox { Column(content = content) }
}

@Composable
fun LedgersScreen(
    vm: AppViewModel,
    onBack: () -> Unit
) {
    val ledgers by vm.ledgers.collectAsState()
    val current by vm.currentLedgerId.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<com.qingjizhang.app.data.Ledger?>(null) }
    var delete by remember { mutableStateOf<com.qingjizhang.app.data.Ledger?>(null) }

    if (showAdd) {
        InputDialog(
            title = "新建账本",
            initial = "",
            label = "账本名称",
            onConfirm = { if (it.isNotBlank()) vm.addLedger(it) },
            onDismiss = { showAdd = false }
        )
    }
    rename?.let { l ->
        InputDialog(
            title = "重命名账本",
            initial = l.name,
            label = "账本名称",
            onConfirm = { if (it.isNotBlank()) vm.renameLedger(l, it) },
            onDismiss = { rename = null }
        )
    }
    delete?.let { l ->
        ConfirmDialog(
            title = "删除账本「${l.name}」？",
            text = "账本会移入回收站（保留 30 天，可随时还原），它下面的账单、账户、" +
                "分类与周期规则都暂时保留；在回收站里彻底删除时才会一起清空。其他账本不受影响。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteLedger(l) },
            onDismiss = { delete = null }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        BackTopBar(
            title = "账本管理",
            onBack = onBack,
            actions = { IconAction(Icons.Rounded.Add, { showAdd = true }) }
        )
        Column(Modifier.padding(horizontal = 14.dp)) {
            // M12：这一条「修复说明」已移到「帮助与反馈 → 更新记录」里，账本管理页不再显示
            TipBanner(
                text = "不同账本的账单、账户、分类与周期规则完全隔离，适合把日常开销和家庭账分开记。",
                icon = Icons.Rounded.Info
            )
            Spacer(Modifier.height(14.dp))
            CardBox {
                Column {
                    ledgers.forEach { l ->
                        val isCurrent = l.id == current
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.switchLedger(l.id) }
                                .padding(horizontal = 15.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconBadge(
                                if (isCurrent) Icons.Rounded.CheckCircle else Icons.Rounded.MenuBook,
                                if (isCurrent) Primary else Sub,
                                size = 32.dp, iconSize = 17.dp
                            )
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    l.name,
                                    fontSize = 14.5.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) Primary else Ink
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "预设 ${Seed.presetTopLevelCount} 个一级分类 · " +
                                        "${Seed.presetSubCount} 个二级分类 · ${Seed.presetAccountCount} 个账户",
                                    fontSize = 11.sp,
                                    color = Sub
                                )
                            }
                            IconAction(Icons.Rounded.Edit, { rename = l }, tint = Sub, size = 18.dp)
                            IconAction(Icons.Rounded.Delete, { delete = l }, tint = Expense, size = 18.dp)
                        }
                        if (l != ledgers.last()) RowDivider()
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            CardBox {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showAdd = true }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Add, null, tint = Primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建账本", fontSize = 13.5.sp, color = Primary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/**
 * P1 IA 重组：设置项的数据模型。
 * 把每一行声明成「分组 + 标题 + 关键词 + 渲染块」，顶部搜索框才能按关键词过滤；
 * keywords 里放同义词（如"导出"≈"excel"），用户想怎么搜都能搜到。
 */
private data class SettingItem(
    val group: String,
    val label: String,
    val keywords: String,
    val render: @Composable () -> Unit
)

/** 设置首页的 5 个入口（key 用于路由：settings/{key}） */
private data class SettingsSection(
    val key: String,
    val title: String,
    val summary: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: Color
)

private val SETTINGS_SECTIONS = listOf(
    SettingsSection("prefs", "记账偏好", "预算、提醒、记账建议、输入手感", Icons.Rounded.Tune, Teal),
    SettingsSection("books", "账本与分类", "账本、分类、标签、周期记账", Icons.Rounded.LibraryBooks, Purple),
    SettingsSection("data", "数据", "备份恢复、导入导出、回收站", Icons.Rounded.Restore, BrandBlue),
    SettingsSection("look", "外观", "主题色、深浅模式、减弱视觉效果", Icons.Rounded.Palette, Sky),
    SettingsSection("about", "关于", "帮助、隐私、更新日志、版本", Icons.Rounded.Info, NeutralGray),
    SettingsSection("adv", "高级功能", "把不常用的功能收起来", Icons.Rounded.Tune, Teal)
)

/** 高级功能开关行（带一句说明，告诉用户关掉会少什么） */
@Composable
private fun AdvSwitchRow(
    label: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = Ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(desc, fontSize = 11.5.sp, color = Sub)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
