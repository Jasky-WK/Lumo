package com.qingjizhang.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.BuildConfig
import com.qingjizhang.app.data.Changelog
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.toast
import java.io.File

/**
 * 帮助与反馈（O1）：常见问题 + 更新日志 + 本地数据说明（N29）。
 * 全部为本地静态内容，不需要联网。
 */
@Composable
fun HelpScreen(vm: AppViewModel, onBack: () -> Unit) {
    var showLog by remember { mutableStateOf(false) }
    val expandFaq = remember { mutableStateMapOf<Int, Boolean>() }
    val context = LocalContext.current

    // 备份 / 日志目录都写在本机（优先「下载」，App 私有目录兜底），这里只读地展示概况
    var dirRev by remember { mutableIntStateOf(0) }
    val extDir = context.getExternalFilesDir(null)
    val backupDirs = buildList {
        if (extDir != null) add(File(extDir, "backups"))
        add(File(context.filesDir, "backups"))
    }
    val logDirs = buildList {
        if (extDir != null) add(File(extDir, "Lumo日志"))
        add(File(context.filesDir, "Lumo日志"))
    }
    val backupStat = remember(dirRev) { scanDirs(backupDirs) }
    val logStat = remember(dirRev) { scanDirs(logDirs) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        BackTopBar("帮助与反馈", onBack)
        Column(Modifier.padding(horizontal = 14.dp)) {

            // 版本卡
            CardBox {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Book, Primary, size = 42.dp, iconSize = 21.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Lumo", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "版本 v" + BuildConfig.VERSION_NAME + " · Light up your wealth",
                                fontSize = 11.5.sp,
                                color = Sub
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        SmallButton("更新日志", Icons.Rounded.History) { showLog = !showLog }
                        SmallButton("反馈建议", Icons.Rounded.ChatBubbleOutline) {
                            vm.toast("Bug 反馈请到酷安找「悲伤的乔治」；数据都在本机，反馈前建议先备份")
                        }
                    }
                }
            }

            // 作者与反馈（N32）：一直免费 + 反馈渠道，放在最显眼的位置
            SectionTitle("作者与反馈", trailing = "一直免费 · 不靠它收费")
            CardBox {
                Column {
                    InfoRow(
                        Icons.Rounded.FavoriteBorder,
                        Expense,
                        "作者另有工作维持生活，这个 App 一直免费",
                        "不收费、无内购、不接广告，也不靠这个软件赚钱；作者靠其他工作维持生活，" +
                            "写它只是因为自己记账要用。觉得好用，帮忙说一声就足够了。"
                    )
                    RowDivider()
                    InfoRow(
                        Icons.Rounded.ChatBubbleOutline,
                        Primary,
                        "Bug 反馈：酷安 ID「悲伤的乔治」",
                        "发现闪退、数据不对、文案有错或想加功能，到酷安搜索 / 私信「悲伤的乔治」即可；" +
                            "反馈时请顺手写上版本号与复现步骤，定位会快很多。"
                    )
                    RowDivider()
                    InfoRow(
                        Icons.Rounded.CloudOff,
                        Teal,
                        "Light up your wealth",
                        "App 不申请 INTERNET，账单只存在这台手机，反馈也只能由你手动发出去" +
                            "（细节见下方「关于与隐私」）。"
                    )
                }
            }

            // 更新日志：每个版本占一行，点开才展开条目（原来 40 个版本一次性铺满整屏）
            val expandedVersions = remember { mutableStateMapOf<String, Boolean>() }
            if (showLog) {
                // M12：原「账本管理」页的修复提示不单独列出，已并入下面 v1.6.0 的条目里
                SectionTitle("更新日志", trailing = "共 ${Changelog.entries.size} 个版本")
                CardBox {
                    Column {
                        Changelog.entries.forEachIndexed { i, e ->
                            if (i > 0) RowDivider()
                            val expanded = expandedVersions[e.version] == true
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedVersions[e.version] = !expanded }
                                    .padding(horizontal = 15.dp, vertical = 13.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "v" + e.version,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Ink
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        e.status,
                                        fontSize = 10.5.sp,
                                        color = if (e.status.startsWith("已打包")) Income else Sub,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (e.status.startsWith("已打包")) FieldBg else FieldBg)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text("${e.items.size} 条", fontSize = 11.sp, color = Sub)
                                    Icon(
                                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                        contentDescription = if (expanded) "收起" else "展开",
                                        tint = Sub,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                if (expanded) {
                                Spacer(Modifier.height(6.dp))
                                e.items.forEach { item ->
                                    Row(Modifier.padding(top = 4.dp)) {
                                        Box(
                                            Modifier
                                                .padding(top = 6.dp, end = 7.dp)
                                                .size(4.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(Primary)
                                        )
                                        Text(
                                            item,
                                            fontSize = 12.5.sp,
                                            color = Sub2,
                                            lineHeight = 19.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                }
                            }
                        }
                    }
                }
            }

            // 常见问题
            SectionTitle("常见问题", trailing = "点一条展开")
            CardBox {
                Column {
                    Changelog.faqs.forEachIndexed { i, qa ->
                        if (i > 0) RowDivider()
                        val open = expandFaq[i] == true
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { expandFaq[i] = !open }
                                .padding(horizontal = 15.dp, vertical = 13.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    qa.first,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Ink,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    tint = Sub,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            if (open) {
                                Spacer(Modifier.height(7.dp))
                                Text(qa.second, fontSize = 12.5.sp, color = Sub2, lineHeight = 20.sp)
                            }
                        }
                    }
                }
            }

            // 关于与隐私（N29）：无网络权限 / 数据只在本机 / 卸载前先备份 / 目录入口
            SectionTitle("关于与隐私", trailing = "全部在本机 · 不联网")
            CardBox {
                Column {
                    InfoRow(
                        Icons.Rounded.CloudOff,
                        Primary,
                        "没有网络权限",
                        "App 只声明了「存储读取」（Android 9 及以下）与「通知」两项权限，没有 INTERNET，" +
                            "数据没有任何出口，也不可能上传"
                    )
                    RowDivider()
                    InfoRow(
                        Icons.Rounded.PhoneAndroid,
                        Teal,
                        "数据只存在这台手机",
                        "账单、账户、分类、账本都存在本机数据库（SQLite），主题与偏好存在本机 " +
                            "SharedPreferences；没有账号、不用登录、没有云同步"
                    )
                    RowDivider()
                    InfoRow(
                        Icons.Rounded.NotificationsNone,
                        Orange,
                        "提醒也是本地的",
                        "周期提醒由系统闹钟（AlarmManager）在到点时触发，不依赖任何服务器"
                    )
                    RowDivider()
                    InfoRow(
                        Icons.Rounded.Backup,
                        Purple,
                        "卸载前请先导出备份",
                        "卸载 App 会连同本机数据库一起删除，无法找回；换机或卸载前先导出一次完整备份"
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            TipBanner(
                text = "准备卸载或换手机？请先到「设置 → 数据备份与恢复」导出一次完整备份（.bak 文件），再卸载。" +
                    "备份文件可以拷到电脑或另一台手机，用它就能完整恢复。",
                icon = Icons.Rounded.Warning,
                bg = WarnBg,
                fg = WarnFg
            )

            // 本机目录入口：备份与日志写在哪、有多少、怎么打开
            SectionTitle("本机备份 / 日志目录", trailing = "点下面按钮打开")
            CardBox {
                Column {
                    DirRow(
                        Icons.Rounded.Folder,
                        "备份文件",
                        "App 私有 backups/ · 另存一份在「下载/Lumo备份」",
                        backupStat
                    )
                    RowDivider()
                    DirRow(
                        Icons.Rounded.Description,
                        "自检 / 崩溃日志",
                        "「下载/Lumo日志」· App 私有 Lumo日志/",
                        logStat
                    )
                    RowDivider()
                    DirAction("打开系统「下载」目录", Icons.Rounded.FolderOpen) {
                        if (!openDownloadsDir(context)) {
                            vm.toast("这台设备的文件管理器不支持直接打开目录，请手动打开手机「下载」文件夹")
                        }
                    }
                    RowDivider()
                    DirAction("刷新目录内容", Icons.Rounded.Refresh) { dirRev++ }
                    Text(
                        "备份与日志都写在本机：优先写到手机「下载」目录，App 私有目录再留一份兜底；" +
                            "全程不联网、不上传。",
                        fontSize = 11.sp,
                        color = Sub,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp)
                    )
                }
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SmallButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = PrimaryBg,
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = Primary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Primary)
        }
    }
}

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    desc: String
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon, tint, size = 30.dp, iconSize = 16.dp, radius = 10.dp)
        Spacer(Modifier.width(11.dp))
        Column {
            Text(title, fontSize = 13.5.sp, color = Ink)
            Spacer(Modifier.height(3.dp))
            Text(desc, fontSize = 11.5.sp, color = Sub, lineHeight = 17.sp)
        }
    }
}

// ---------------- 本机目录概况（N29） ----------------

/** 目录概况：文件数 + 最新一个文件的名字 */
private data class DirStat(val count: Int, val latest: String)

private fun scanDirs(dirs: List<File>): DirStat {
    val files = dirs.flatMap { d -> d.listFiles()?.filter { it.isFile } ?: emptyList() }
        .sortedByDescending { it.lastModified() }
    return DirStat(files.size, files.firstOrNull()?.name ?: "")
}

/** 打开系统文件管理器的「下载」目录；设备上没有能处理目录 URI 的应用时返回 false */
private fun openDownloadsDir(context: Context): Boolean {
    val candidates = listOf(
        Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
        Uri.parse("content://com.android.externalstorage.documents/root/primary")
    )
    for (uri in candidates) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (runCatching { context.startActivity(intent) }.isSuccess) return true
    }
    return false
}

@Composable
private fun DirRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    path: String,
    stat: DirStat
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon, Primary, size = 30.dp, iconSize = 16.dp, radius = 10.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, color = Ink)
            Spacer(Modifier.height(3.dp))
            Text(path, fontSize = 11.sp, color = Sub, lineHeight = 16.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                if (stat.count == 0) "App 私有目录暂无文件"
                else "共 ${stat.count} 个文件 · 最新：${stat.latest}",
                fontSize = 11.sp,
                color = Sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DirAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Primary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, fontSize = 13.5.sp, color = Primary, fontWeight = FontWeight.SemiBold)
    }
}
