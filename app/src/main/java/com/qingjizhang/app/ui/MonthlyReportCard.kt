package com.qingjizhang.app.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.qingjizhang.app.data.Account
import com.qingjizhang.app.data.Category
import com.qingjizhang.app.data.Repository
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.data.TxnType
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.data.CsvManager
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.util.ReportStats
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.toast
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 月度记账报告卡（P1-1）：把「这个月过得怎么样」浓缩成一张可以存下来、发出去的图。
 *
 * - 数据全部来自本账本账单，口径与统计页一致（不计收支 / 排除账户 / 实计金额）；
 * - 用 Compose 原生 GraphicsLayer 截图（不依赖任何第三方库），PNG 存到「下载」或直接分享；
 * - 图片写 cache/share/ 后再通过 FileProvider 分享 —— 只授权这一张图，不暴露目录。
 */
@Composable
fun MonthlyReportSheet(vm: AppViewModel, stats: ReportStats, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var busy by remember { mutableStateOf(false) }

    /**
     * 截当前卡片为 PNG 字节。v2.8.0 修 OOM：每次打开都会生成一张几 MB 的位图，
     * 压缩完立刻 recycle，不交给 GC 兜底。
     */
    suspend fun pngBytes(): ByteArray {
        val bmp = layer.toImageBitmap().asAndroidBitmap()
        return try {
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        } finally {
            runCatching { bmp.recycle() }
        }
    }

    fun save() {
        if (busy) return
        busy = true
        scope.launch {
            runCatching {
                val name = "Lumo-" + stats.title + ".png"
                val path = CsvManager.saveToDownloads(context, name, pngBytes(), "image/png")
                vm.toast("已保存到「下载」：$path")
            }.onFailure { vm.toast("保存失败：${it.message}") }
            busy = false
        }
    }

    fun share() {
        if (busy) return
        busy = true
        scope.launch {
            runCatching {
                val dir = File(context.cacheDir, "share").apply { mkdirs() }
                val file = File(dir, "monthly-report.png")
                file.writeBytes(pngBytes())
                val uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    file
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "分享本月报告"))
            }.onFailure { vm.toast("分享失败：${it.message}") }
            busy = false
        }
    }

    // 关掉平台默认宽度限制：窄屏（360dp）下默认对话框宽度会把 330dp 的卡片裁掉
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 要截图的内容：先 record 进 graphicsLayer，再正常绘制（所见即所得）
            Box(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 330.dp)
                    .drawWithContent {
                        layer.record(
                            density = this,
                            layoutDirection = this.layoutDirection,
                            size = IntSize(size.width.toInt(), size.height.toInt())
                        ) { this@drawWithContent.drawContent() }
                        drawContent()
                    }
            ) {
                ReportCard(
                    title = stats.title,
                    ledgerName = stats.ledgerName,
                    income = stats.incomeCents,
                    expense = stats.expenseCents,
                    lastIncome = stats.prevIncomeCents,
                    lastExpense = stats.prevExpenseCents,
                    topCats = stats.expenseCats.take(3).map { it.name to it.cents },
                    maxCents = stats.maxExpenseCents,
                    maxNote = stats.maxExpenseNote,
                    activeDays = stats.activeDays,
                    budgetCents = stats.budgetCents,
                    budgetUsed = stats.budgetUsedCents
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionPill("保存图片", Icons.Rounded.Download, Primary, { save() })
                Spacer(Modifier.width(10.dp))
                ActionPill("分享", Icons.Rounded.Share, Income, { share() })
                Spacer(Modifier.width(10.dp))
                ActionPill("关闭", Icons.Rounded.Close, Sub, onDismiss)
            }
        }
    }
}

@Composable
private fun ActionPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CardWhite)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 12.5.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- 卡片本体

@Composable
private fun ReportCard(
    title: String,
    ledgerName: String,
    income: Long,
    expense: Long,
    lastIncome: Long,
    lastExpense: Long,
    topCats: List<Pair<String, Long>>,
    maxCents: Long,
    maxNote: String,
    activeDays: Int,
    budgetCents: Long,
    budgetUsed: Long
) {
    val net = income - expense
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CardWhite)
            .padding(18.dp)
    ) {
        // 头部：品牌渐变条
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Primary, PrimaryLight)))
                .padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            Column {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(3.dp))
                Text(
                    "$ledgerName · Lumo月度小结",
                    fontSize = 11.5.sp,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("本月结余", fontSize = 11.5.sp, color = Sub)
        Spacer(Modifier.height(2.dp))
        Text(
            (if (net >= 0) "+" else "−") + Money.withSymbol(Math.abs(net)),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = if (net >= 0) Primary else Expense
        )

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            StatCell("支出", Money.withSymbol(expense), Expense, Modifier.weight(1f))
            Box(
                Modifier
                    .width(1.dp)
                    .height(34.dp)
                    .background(Line)
            )
            Spacer(Modifier.width(12.dp))
            StatCell("收入", Money.withSymbol(income), Income, Modifier.weight(1f))
        }

        // 环比
        val expenseDelta = deltaText(lastExpense, expense)
        val incomeDelta = deltaText(lastIncome, income)
        if (expenseDelta != null || incomeDelta != null) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                if (expenseDelta != null) {
                    Text("支出环比 $expenseDelta", fontSize = 11.5.sp, color = Sub, modifier = Modifier.weight(1f))
                }
                if (incomeDelta != null) {
                    Text("收入环比 $incomeDelta", fontSize = 11.5.sp, color = Sub)
                }
            }
        }

        if (topCats.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("花得最多的三件事", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(8.dp))
            val max = topCats.first().second.coerceAtLeast(1L)
            topCats.forEach { (name, cents) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontSize = 12.5.sp, color = Ink, modifier = Modifier.width(64.dp), maxLines = 1)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(9.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Track)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((cents.toFloat() / max).coerceIn(0.04f, 1f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(5.dp))
                                .background(Expense.copy(alpha = 0.85f))
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(Money.text(cents), fontSize = 12.sp, color = Sub2)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            StatCell("记账天数", "$activeDays 天", Primary, Modifier.weight(1f))
            if (maxCents > 0L) {
                StatCell(
                    "最大一笔",
                    Money.withSymbol(maxCents),
                    Sub2,
                    Modifier.weight(1f),
                    hint = maxNote.ifBlank { null }
                )
            }
        }

        if (budgetCents > 0L) {
            Spacer(Modifier.height(14.dp))
            val percent = (budgetUsed * 100 / budgetCents).toInt()
            Text(
                "预算执行 " + percent + "%（" + Money.withSymbol(budgetUsed) + " / " + Money.withSymbol(budgetCents) + "）",
                fontSize = 11.5.sp,
                color = if (budgetUsed > budgetCents) Expense else Sub
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Track)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((percent.toFloat() / 100f).coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (budgetUsed > budgetCents) Expense else Primary)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Light up your wealth",
            fontSize = 10.5.sp,
            color = Muted2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier,
    hint: String? = null
) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = Sub)
        Spacer(Modifier.height(3.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        if (!hint.isNullOrBlank()) {
            Text(hint, fontSize = 10.5.sp, color = Muted2, maxLines = 1)
        }
    }
}

private fun deltaText(last: Long, now: Long): String? {
    if (last <= 0L || now <= 0L) return null
    val pct = ((now - last) * 100.0 / last).toInt()
    if (pct == 0) return "持平"
    return (if (pct > 0) "↑" else "↓") + Math.abs(pct) + "%"
}
