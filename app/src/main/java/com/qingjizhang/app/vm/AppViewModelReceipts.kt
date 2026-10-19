package com.qingjizhang.app.vm

import android.net.Uri
import com.qingjizhang.app.data.*
import com.qingjizhang.app.ui.autoIconKeyFor
import com.qingjizhang.app.ui.theme.ThemeColor
import com.qingjizhang.app.ui.theme.ThemeMode
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.util.ReceiptStore
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.widget.WidgetRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 小票照片命令（P2-1 拆出）
 */

    /** 详情页进入时告诉 VM 要看哪笔账单的小票 */
fun AppViewModel.openReceipts(txnId: Long) {
        _receiptTxnId.value = txnId
    }

    // ---------------- 小票照片（N56） ----------------

    /** 拍 / 选完小票后入库（文件已由 ReceiptStore 落盘，这里只写路径） */
fun AppViewModel.addReceiptPhoto(txnId: Long, filePath: String) {
        scope.launch {
            runCatching { repo().addReceiptPhoto(txnId, filePath) }
                .onFailure { _snack.emit("保存小票失败：${it.message}") }
        }
    }

    /** 删一张小票：先删文件再删记录（文件删不掉也照旧删记录，不留删不掉的记录） */
fun AppViewModel.deleteReceiptPhoto(photo: ReceiptPhoto) {
        scope.launch {
            runCatching {
                ReceiptStore.deleteFile(photo.filePath)
                repo().deleteReceiptPhoto(photo)
            }.onFailure { _snack.emit("删除小票失败：${it.message}") }
        }
    }
