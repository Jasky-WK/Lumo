package com.qingjizhang.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.qingjizhang.app.data.*
import com.qingjizhang.app.data.Direction
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.util.ReceiptStore
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.addReceiptPhoto
import com.qingjizhang.app.vm.deleteReceiptPhoto
import com.qingjizhang.app.vm.deleteTxn
import com.qingjizhang.app.vm.openReceipts
import com.qingjizhang.app.vm.settleLend
import com.qingjizhang.app.vm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DetailScreen(
    vm: AppViewModel,
    txnId: Long,
    onBack: () -> Unit
) {
    val txns by vm.txns.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    // N38：这笔账单的标签（账单 id -> 标签名，一次性取回全账本）
    val tagMap by vm.txnTagMap.collectAsState()
    val txn = txns.firstOrNull { it.id == txnId }

    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showSettle by remember { mutableStateOf(false) }

    if (txn == null) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar("账单详情", onBack)
            EmptyState(Icons.Rounded.SearchOff, "这笔账单已经不存在了", "可能已被删除或恢复备份后覆盖")
        }
        return
    }

    val cat = categories.firstOrNull { it.id == txn.categoryId }
    val parent = categories.firstOrNull { it.id == cat?.parentId }
    val account = accounts.firstOrNull { it.id == txn.accountId }
    val toAccount = accounts.firstOrNull { it.id == txn.transferToAccountId }
    val styleName = when (txn.type) {
        TxnType.TRANSFER -> "互转"
        TxnType.LEND_BORROW -> "借贷"
        else -> parent?.name ?: cat?.name ?: ""
    }
    val style = catStyle(styleName)
    val title = when (txn.type) {
        TxnType.TRANSFER -> "账户互转"
        TxnType.LEND_BORROW -> lendTitle(txn)
        else -> cat?.name ?: parent?.name ?: "未分类"
    }
    val signed = when (txn.type) {
        TxnType.EXPENSE -> -txn.amountCents
        TxnType.INCOME -> txn.amountCents
        TxnType.TRANSFER -> txn.amountCents
        TxnType.LEND_BORROW -> if (txn.direction == Direction.LEND) -txn.amountCents else txn.amountCents
    }
    // M10：金额颜色统一走共享函数（不计入收支 → 灰色，其余保持红绿 / 中性）
    val amtColor = amountColor(txn)

    val isLend = txn.type == TxnType.LEND_BORROW
    val isSettle = Repository.isSettlement(txn)
    val isReceivable = txn.direction == Direction.LEND
    // 原始借贷：算出已收回 / 已还和还没结清的金额
    val settledCents = if (isLend && !isSettle) Repository.settledCentsOf(txns, txn.id) else 0L
    val outstanding = if (isLend && !isSettle) Repository.outstandingCents(txns, txn) else 0L
    val settlements = if (isLend && !isSettle) Repository.settlementsOf(txns, txn.id) else emptyList()
    val origin = if (isSettle) txns.firstOrNull { it.id == txn.settledTxnId } else null

    // ---------- N56：小票 / 凭证 ----------
    val context = LocalContext.current
    val receiptPhotos by vm.receiptPhotos.collectAsState()
    LaunchedEffect(txn.id) { vm.openReceipts(txn.id) }
    var showReceiptPicker by remember { mutableStateOf(false) }
    var viewerPath by remember { mutableStateOf<String?>(null) }
    var pendingDeletePhoto by remember { mutableStateOf<ReceiptPhoto?>(null) }
    var cameraOut by remember { mutableStateOf<Uri?>(null) }

    val takePicture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) {
            val path = ReceiptStore.commitCapture(
                context, txn.id, File(context.cacheDir, "share/receipt_capture.jpg")
            )
            if (path != null) vm.addReceiptPhoto(txn.id, path) else vm.toast("拍照失败，请重试")
        }
    }
    val pickFromGallery = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val path = ReceiptStore.importFromUri(context, txn.id, uri)
            if (path != null) vm.addReceiptPhoto(txn.id, path) else vm.toast("图片读取失败")
        }
    }
    fun launchCamera() {
        val file = ReceiptStore.newTempFile(context)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        cameraOut = uri
        takePicture.launch(uri)
    }

    if (showEdit) {
        AddEntrySheet(vm = vm, request = AddSheetRequest(editing = txn), onDismiss = { showEdit = false })
    }
    if (showSettle) {
        InputDialog(
            title = if (isReceivable) "记一笔收回" else "记一笔还款",
            initial = Money.text(outstanding),
            label = "金额（元），最多 ¥" + Money.text(outstanding),
            numberOnly = true,
            onConfirm = { text ->
                val cents = (Money.parse(text) ?: 0L).coerceIn(0L, outstanding)
                if (cents > 0L) vm.settleLend(txn, cents)
            },
            onDismiss = { showSettle = false }
        )
    }
    if (showDelete) {
        TxnDeleteDialog(
            summary = DateFmt.isoDate(txn.dateMillis) + " · " + Money.withSymbol(txn.amountCents),
            onConfirm = { keep ->
                vm.deleteTxn(txn, keepBalance = keep)
                onBack()
            },
            onDismiss = { showDelete = false }
        )
    }

    // N56：添加小票的方式（拍照 / 相册）
    if (showReceiptPicker) {
        AlertDialog(
            onDismissRequest = { showReceiptPicker = false },
            title = { Text("添加小票 / 凭证", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink) },
            text = {
                Text(
                    "拍照或从相册选择。照片只保存在本机（App 私有目录），不联网、不上传；" +
                        "卸载 App 会一并清除，完整备份里也不含照片文件。",
                    fontSize = 13.sp,
                    color = Sub,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showReceiptPicker = false
                    launchCamera()
                }) { Text("拍照", color = Primary, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showReceiptPicker = false
                    pickFromGallery.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) { Text("从相册选择", color = Primary) }
            }
        )
    }

    // N56：全屏查看（双指缩放 / 拖动），可当场删除
    viewerPath?.let { path ->
        ReceiptViewerDialog(
            path = path,
            onDismiss = { viewerPath = null },
            onDelete = {
                viewerPath = null
                receiptPhotos.firstOrNull { it.filePath == path }?.let { pendingDeletePhoto = it }
            }
        )
    }

    // N56：长按缩略图删除，先确认（文件与记录一起删，不可恢复）
    pendingDeletePhoto?.let { photo ->
        ConfirmDialog(
            title = "删除这张小票？",
            text = "照片文件会一起删掉，删除后不可恢复；账单本身不受影响。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                vm.deleteReceiptPhoto(photo)
                pendingDeletePhoto = null
            },
            onDismiss = { pendingDeletePhoto = null }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        BackTopBar(
            title = "账单详情",
            onBack = onBack,
            actions = {
                IconAction(Icons.Rounded.DeleteOutline, { showDelete = true }, tint = Expense)
            }
        )
        Column(Modifier.padding(horizontal = 14.dp)) {
            CardBox {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconBadge(style.icon, style.color, size = 54.dp, iconSize = 26.dp, radius = 18.dp)
                    Spacer(Modifier.height(11.dp))
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        listOfNotNull(
                            parent?.name,
                            account?.name,
                            if (txn.type == TxnType.TRANSFER) "→ " + (toAccount?.name ?: "?") else null
                        ).joinToString(" · "),
                        fontSize = 11.5.sp,
                        color = Sub
                    )
                    Spacer(Modifier.height(12.dp))
                    AmountText(signed, color = amtColor, fontSize = 30.sp)
                    // N40：有手续费 / 优惠时，标题金额下面补一行「实际金额」
                    if (txn.feeCents > 0L || txn.discountCents > 0L) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "实际 ¥" + Money.text(Repository.effectiveCents(txn)) +
                                "（金额 − 退款 − 优惠 + 手续费）",
                            fontSize = 11.5.sp,
                            color = Sub,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // N56：小票 / 凭证（拍照 / 相册，本地存储）
            SectionTitle(
                "小票 / 凭证",
                trailing = if (receiptPhotos.isEmpty()) null else "${receiptPhotos.size} 张"
            )
            CardBox {
                Column(Modifier.padding(15.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(receiptPhotos, key = { it.id }) { photo ->
                            ReceiptThumb(
                                path = photo.filePath,
                                onClick = { viewerPath = photo.filePath },
                                onLongClick = { pendingDeletePhoto = photo }
                            )
                        }
                        item {
                            Box(
                                Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(FieldBg)
                                    .clickable { showReceiptPicker = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Rounded.AddAPhoto,
                                        null,
                                        tint = Sub,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text("添加", fontSize = 10.5.sp, color = Sub)
                                }
                            }
                        }
                    }
                    if (receiptPhotos.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "拍下小票或凭证存到本机，查账时一眼对得上。",
                            fontSize = 11.sp,
                            color = Sub,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 借贷还款（N7）：原始借贷显示待收回 / 待归还并可记一笔；还款流水显示它针对哪笔借贷
            if (isLend && !isSettle) {
                CardBox {
                    Column(Modifier.padding(15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (isReceivable) "待收回" else "待归还",
                                fontSize = 13.sp,
                                color = Sub
                            )
                            Spacer(Modifier.weight(1f))
                            AmountText(
                                outstanding,
                                color = if (outstanding > 0L) (if (isReceivable) Expense else Income) else Income,
                                fontSize = 21.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "原金额 ¥" + Money.text(txn.amountCents) +
                                (if (settledCents > 0L)
                                    " · 已" + (if (isReceivable) "收回" else "归还") + " ¥" + Money.text(settledCents)
                                else ""),
                            fontSize = 11.5.sp,
                            color = Sub
                        )
                        Spacer(Modifier.height(12.dp))
                        if (outstanding > 0L) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showSettle = true },
                                shape = RoundedCornerShape(13.dp),
                                color = Primary
                            ) {
                                Text(
                                    if (isReceivable) "记一笔收回" else "记一笔还款",
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    textAlign = TextAlign.Center,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        } else {
                            Text(
                                "这笔借贷已经结清",
                                fontSize = 12.5.sp,
                                color = Income,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            CardBox {
                Column {
                    DetailRow(
                        "类型",
                        // 互转显示子类型（N26）：互转 / 还款 / 理财买入 / 理财赎回，或用户自定义
                        if (txn.type == TxnType.TRANSFER) txn.transferKind else typeLabel(txn.type)
                    )
                    if (txn.type == TxnType.LEND_BORROW) {
                        DetailRow(
                            "方向",
                            when {
                                isSettle -> if (isReceivable) "还出" else "收回"
                                isReceivable -> "借出"
                                else -> "借入"
                            }
                        )
                        if (origin != null) {
                            DetailRow(
                                "原借贷",
                                DateFmt.isoDate(origin.dateMillis) + " · " + Money.withSymbol(origin.amountCents)
                            )
                        }
                        DetailRow(
                            if (txn.direction == Direction.LEND) "资金账户（扣款）" else "资金账户（到账）",
                            account?.name ?: "—"
                        )
                        DetailRow("借贷账户", toAccount?.name ?: "—")
                        if (txn.counterparty.isNotBlank()) DetailRow("对方", txn.counterparty)
                    }
                    if (parent != null) DetailRow("一级分类", parent.name)
                    if (cat != null && cat.parentId != 0L) DetailRow("二级分类", cat.name)
                    if (txn.type != TxnType.LEND_BORROW) DetailRow("账户", account?.name ?: "—")
                    if (txn.type == TxnType.TRANSFER) DetailRow("转入账户", toAccount?.name ?: "—")
                    val statNote = buildList {
                        if (txn.excludeFromStats) add("不计入收支（账户余额照常变化）")
                        if (txn.excludeFromBudget) add("不计入预算")
                    }.joinToString("、")
                    if (statNote.isNotEmpty()) DetailRow("统计口径", statNote)
                    DetailRow("时间", DateFmt.isoDateTime(txn.dateMillis))
                    DetailRow("备注", txn.note.ifBlank { "—" })
                    // N40：优惠 / 手续费与实际金额（口径与列表、统计完全一致）
                    if (txn.discountCents > 0L) {
                        DetailRow("优惠", "− ¥" + Money.text(txn.discountCents))
                    }
                    if (txn.feeCents > 0L) {
                        DetailRow("手续费", "+ ¥" + Money.text(txn.feeCents))
                    }
                    if (txn.feeCents > 0L || txn.discountCents > 0L) {
                        DetailRow(
                            "实际金额",
                            buildString {
                                append("¥")
                                append(Money.text(Repository.effectiveCents(txn)))
                                append("（金额 ¥")
                                append(Money.text(txn.amountCents))
                                if (txn.refundCents > 0L) {
                                    append(" − 退款 ¥")
                                    append(Money.text(txn.refundCents))
                                }
                                if (txn.discountCents > 0L) {
                                    append(" − 优惠 ¥")
                                    append(Money.text(txn.discountCents))
                                }
                                if (txn.feeCents > 0L) {
                                    append(" + 手续费 ¥")
                                    append(Money.text(txn.feeCents))
                                }
                                append("）")
                            }
                        )
                    }
                    // N38：这笔账单的标签（没有标签时不显示这一行）
                    val tagNames = tagMap[txn.id].orEmpty()
                    if (tagNames.isNotEmpty()) {
                        DetailRow("标签", tagNames.joinToString(" ") { "#" + it })
                    }
                    if (txn.ruleId != 0L) DetailRow("来源", "周期记账自动生成")
                    DetailRow("创建时间", DateFmt.isoDateTime(txn.createdAt))
                }
            }

            // 原始借贷下方的每一笔收回 / 还出
            if (settlements.isNotEmpty()) {
                SectionTitle("还款流水", trailing = "共 ${settlements.size} 笔")
                CardBox {
                    Column {
                        settlements.forEachIndexed { i, s ->
                            if (i > 0) RowDivider()
                            DetailRow(
                                DateFmt.isoDate(s.dateMillis),
                                (if (isReceivable) "收回 " else "还出 ") + "¥" + Money.text(s.amountCents)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Row {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showDelete = true },
                    shape = RoundedCornerShape(14.dp),
                    color = CardWhite
                ) {
                    Text(
                        "删除",
                        modifier = Modifier.padding(vertical = 13.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Expense
                    )
                }
                Spacer(Modifier.width(11.dp))
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showEdit = true },
                    shape = RoundedCornerShape(14.dp),
                    color = Primary
                ) {
                    Text(
                        "修改",
                        modifier = Modifier.padding(vertical = 13.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.5.sp, color = Sub)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
}

/** N56：小票缩略图（点击看大图，长按删除）；按采样率解码，避免大图爆内存 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReceiptThumb(path: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { ReceiptStore.decodeSampled(path, 240, 240) }
            ?.asImageBitmap()
    }
    val image = bmp
    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(FieldBg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "小票照片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Rounded.BrokenImage,
                null,
                tint = Muted2,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** N56：全屏查看小票（双指缩放 / 单指拖动，1 倍时回中），底部可删除 */
@Composable
private fun ReceiptViewerDialog(path: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { ReceiptStore.decodeSampled(path, 1080, 1920) }
            ?.asImageBitmap()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val transformState = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                offset = if (scale <= 1f) Offset.Zero else offset + pan
            }
            val image = bmp
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "小票大图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(transformState)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            } else {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
            ) {
                Icon(Icons.Rounded.Close, "关闭", tint = Color.White)
            }
            TextButton(
                onClick = onDelete,
                modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp)
            ) {
                Icon(Icons.Rounded.DeleteOutline, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("删除这张", color = Color.White, fontSize = 13.5.sp)
            }
        }
    }
}
