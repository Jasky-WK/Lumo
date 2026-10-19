package com.qingjizhang.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.LocalTime

/**
 * 账单导入导出（CSV）。
 * 导出带 UTF-8 BOM，Excel 直接双击打开不乱码。
 */
object CsvManager {

    /** 导出列（导入模板同款） */
    val HEADER = listOf("日期", "时间", "类型", "大类", "二级分类", "账户", "金额", "备注")

    data class ImportResult(val success: Int, val failed: Int, val message: String)

    private fun csvEscape(v: String): String =
        if (v.contains(',') || v.contains('"') || v.contains('\n')) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else v

    private fun row(cells: List<String>): String = cells.joinToString(",") { csvEscape(it) }

    /** 生成导出内容（不含 BOM） */
    fun buildCsv(
        txns: List<Txn>,
        accountName: (Long) -> String,
        categoryName: (Long) -> String,
        parentName: (Long) -> String
    ): String {
        val sb = StringBuilder()
        sb.append('\uFEFF') // BOM
        sb.append(row(HEADER)).append("\r\n")
        txns.sortedBy { it.dateMillis }.forEach { t ->
            val typeText = when (t.type) {
                TxnType.EXPENSE -> "支出"
                TxnType.INCOME -> "收入"
                TxnType.TRANSFER -> "互转"
                TxnType.LEND_BORROW -> if (t.direction == Direction.BORROW) "借入" else "借出"
            }
            val cat = if (t.type == TxnType.TRANSFER) "账户互转"
            else if (t.type == TxnType.LEND_BORROW) "借贷"
            else parentName(t.categoryId)
            val sub = if (t.type == TxnType.EXPENSE || t.type == TxnType.INCOME) categoryName(t.categoryId) else ""
            val acc = when (t.type) {
                TxnType.TRANSFER ->
                    accountName(t.accountId) + " → " + accountName(t.transferToAccountId)
                // 借贷同样写成「资金账户 → 借贷账户」，导出再导入不会丢一端
                TxnType.LEND_BORROW -> {
                    val (from, to) = Repository.lendEndpoints(t)
                    accountName(from) + " → " + accountName(to)
                }
                else -> accountName(t.accountId)
            }
            sb.append(
                row(
                    listOf(
                        DateFmt.isoDate(t.dateMillis),
                        DateFmt.time(t.dateMillis),
                        typeText,
                        cat,
                        sub,
                        acc,
                        Money.text(t.amountCents),
                        t.note
                    )
                )
            ).append("\r\n")
        }
        return sb.toString()
    }

    /** 导入模板（含示例行） */
    fun buildTemplate(): String {
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append(row(HEADER)).append("\r\n")
        sb.append(row(listOf("2026-09-21", "12:30", "支出", "餐饮美食", "午餐", "支付宝", "38.00", "公司楼下"))).append("\r\n")
        sb.append(row(listOf("2026-09-21", "09:00", "收入", "工资薪资", "月薪", "储蓄卡", "12000.00", "9 月工资"))).append("\r\n")
        sb.append(row(listOf("2026-09-20", "20:00", "互转", "账户互转", "", "支付宝", "500.00", "转到储蓄卡"))).append("\r\n")
        return sb.toString()
    }

    /** 保存文本到「下载」目录 */
    suspend fun saveToDownloads(context: Context, name: String, content: String): String =
        saveToDownloads(context, name, content.toByteArray(Charsets.UTF_8), "text/csv")

    /**
     * 保存二进制到「下载」目录（Excel 导出用，N58）。
     * Android 10+ 走 MediaStore（不需要存储权限）；更早的版本写公共 Download 目录。
     */
    suspend fun saveToDownloads(context: Context, name: String, bytes: ByteArray, mime: String): String =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + BackupManager.FOLDER
                    )
                }
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = context.contentResolver.insert(collection, values)
                    ?: error("无法写入下载目录")
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                "下载/" + BackupManager.FOLDER + "/" + name
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    BackupManager.FOLDER
                )
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, name)).use { it.write(bytes) }
                "下载/" + BackupManager.FOLDER + "/" + name
            }
        }

    /** 读取 CSV 文本（自动识别 UTF-8 / GBK 编码） */
    suspend fun readText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取所选文件")
        decodeText(bytes)
    }

    /** 文件名（显示在导入预览页顶部） */
    fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "导入文件.csv"
    }

    /**
     * 解码 CSV 字节。Excel / WPS 在中文环境下常把文件存成 GBK（ANSI），
     * 直接按 UTF-8 读会整片乱码导致表头与"支出/收入"都认不出来，所以这里做兼容。
     */
    fun decodeText(bytes: ByteArray): String {
        val hasBom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val body = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
        if (hasBom) return String(body, Charsets.UTF_8)
        // 先严格按 UTF-8 解，失败再按 GB18030（GBK 超集）
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        }.getOrElse {
            runCatching { String(body, charset("GB18030")) }.getOrElse { String(body, Charsets.UTF_8) }
        }
    }

    /** 自动识别分隔符：逗号 / 分号 / 制表符（不同区域设置的 Excel 会存成不同分隔符） */
    fun detectDelimiter(text: String): Char {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        val best = listOf(',', ';', '\t', '，')
            .maxByOrNull { c -> line.count { it == c } }
        return if (best != null && line.count { it == best } > 0) best else ','
    }

    private fun normalizeDigits(s: String): String = s.map { ch ->
        if (ch.code in 0xFF10..0xFF19) (ch.code - 0xFF10 + '0'.code).toChar() else ch
    }.joinToString("")

    /** 宽松解析日期：兼容 2026-09-21 / 2026/9/21 / 2026.9.21 / 2026年9月21日 / 2026-09-21 12:30 */
    fun parseDate(raw: String): LocalDate? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        s = normalizeDigits(s).substringBefore(' ').substringBefore('T').substringBefore('，')
        s = s.replace("年", "-").replace("月", "-").replace("日", "").replace("/", "-").replace(".", "-")
        val parts = s.split('-').filter { it.isNotBlank() }
        if (parts.size != 3) return null
        return runCatching { LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()) }.getOrNull()
    }

    /** 宽松解析时间：兼容 12:30 / 9:05 / 12:30:00 / 1230；空值返回 null（导入时按 00:00 处理） */
    fun parseTime(raw: String): LocalTime? {
        val s = normalizeDigits(raw.trim())
        if (s.isEmpty()) return null
        return runCatching {
            when {
                s.contains(':') || s.contains('：') -> {
                    val p = s.replace('：', ':').split(':')
                    LocalTime.of(p[0].toInt(), p.getOrNull(1)?.toInt() ?: 0, p.getOrNull(2)?.toInt() ?: 0)
                }
                s.length == 4 && s.all { it.isDigit() } ->
                    LocalTime.of(s.substring(0, 2).toInt(), s.substring(2, 4).toInt())
                else -> null
            }
        }.getOrNull()
    }

    /** 宽松解析金额：兼容 ¥38 / 1,200.00 / -38 / (38) / 38 元 / 全角数字；返回 (分, 是否为负) */
    fun parseAmount(raw: String): Pair<Long, Boolean>? {
        var s = normalizeDigits(raw.trim())
            .replace("¥", "").replace("￥", "").replace("$", "")
            .replace(",", "").replace("，", "").replace("元", "")
            .replace(" ", "").replace("\u00A0", "")
        if (s.isEmpty() || s == ".") return null
        val negative = s.startsWith("-") || s.startsWith("−") || (s.startsWith("(") && s.endsWith(")"))
        s = s.removePrefix("-").removePrefix("−").removePrefix("(").removeSuffix(")")
        val d = s.toDoubleOrNull() ?: return null
        if (d.isNaN() || d.isInfinite()) return null
        return Math.round(d * 100) to negative
    }

    // ---------------- M2：导入预览 + 逐行手动映射 ----------------

    /** 可映射的字段 */
    enum class Field(val label: String) {
        DATE("日期"), TIME("时间"), TYPE("类型"),
        CATEGORY("大类"), SUB("二级分类"), ACCOUNT("账户"),
        AMOUNT("金额"), NOTE("备注")
    }

    /** 单行预览：解析出来的关键字段 + 这一行为什么导不进来 */
    data class RowPreview(
        val lineNo: Int,
        val dateText: String,
        val typeText: String,
        val catText: String,
        val subText: String,
        val accText: String,
        val amountText: String,
        val noteText: String,
        val error: String?
    ) {
        val ok: Boolean get() = error == null
    }

    /** 导入草稿：先看再导，中途可以改列映射、可以逐行跳过 */
    data class Draft(
        val fileName: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        val hasHeader: Boolean,
        val delimiter: Char,
        val mapping: Map<Field, Int>,
        val excluded: Set<Int>,
        val preview: List<RowPreview>
    ) {
        val includedCount: Int get() = preview.filterIndexed { i, p -> p.ok && i !in excluded }.size
    }

    private val knownHeaderNames = listOf(
        "日期", "时间", "类型", "大类", "一级分类", "二级分类", "小类", "账户", "金额", "备注"
    )

    /** 表头里能认出 3 个以上列名，就按列名定位；否则认为文件没有表头 */
    fun looksLikeHeader(firstRow: List<String>): Boolean =
        firstRow.count { it.trim() in knownHeaderNames } >= 3

    /** 自动猜测列映射 */
    fun autoMap(headers: List<String>, hasHeader: Boolean): Map<Field, Int> {
        fun pick(vararg names: String, fallback: Int): Int {
            if (hasHeader) {
                names.forEach { n ->
                    val p = headers.indexOfFirst { it.trim() == n }
                    if (p >= 0) return p
                }
                return -1
            }
            return if (fallback < headers.size) fallback else -1
        }
        return mapOf(
            Field.DATE to pick("日期", fallback = 0),
            Field.TIME to pick("时间", fallback = 1),
            Field.TYPE to pick("类型", fallback = 2),
            Field.CATEGORY to pick("大类", "一级分类", fallback = 3),
            Field.SUB to pick("二级分类", "小类", fallback = 4),
            Field.ACCOUNT to pick("账户", fallback = 5),
            Field.AMOUNT to pick("金额", fallback = 6),
            Field.NOTE to pick("备注", fallback = 7)
        )
    }

    /** 按映射取某一列的值；-1 表示这个字段没有对应列 */
    fun cell(row: List<String>, mapping: Map<Field, Int>, field: Field): String {
        val i = mapping[field] ?: -1
        return if (i < 0) "" else row.getOrNull(i)?.trim().orEmpty()
    }

    /** 逐行解析预览（只做校验，不写库） */
    fun previewRows(
        rows: List<List<String>>,
        mapping: Map<Field, Int>,
        hasHeader: Boolean
    ): List<RowPreview> = rows.mapIndexed { i, r ->
        val cellOf = { f: Field -> cell(r, mapping, f) }
        val amountText = cellOf(Field.AMOUNT)
        val dateText = cellOf(Field.DATE)
        val typeText = cellOf(Field.TYPE)
        val amount = parseAmount(amountText)
        val error = when {
            amountText.isBlank() -> "金额为空"
            amount == null -> "金额「$amountText」读不出来"
            amount.first <= 0L -> "金额必须大于 0"
            dateText.isBlank() -> "日期为空"
            parseDate(dateText) == null -> "日期「$dateText」读不出来（建议写成 2026-09-21）"
            parseTime(cellOf(Field.TIME)) == null && cellOf(Field.TIME).isNotBlank() ->
                "时间「${cellOf(Field.TIME)}」读不出来"
            typeText.isNotBlank() && !isKnownType(typeText) ->
                "类型「$typeText」不认识（填 支出 / 收入 / 互转 / 借出 / 借入）"
            else -> null
        }
        RowPreview(
            lineNo = i + if (hasHeader) 2 else 1,
            dateText = dateText,
            typeText = typeText.ifBlank { if (amount?.second == true) "支出" else "收入" },
            catText = cellOf(Field.CATEGORY),
            subText = cellOf(Field.SUB),
            accText = cellOf(Field.ACCOUNT),
            amountText = amountText,
            noteText = cellOf(Field.NOTE),
            error = error
        )
    }

    fun isKnownType(text: String): Boolean = when (text) {
        "支出", "expense", "Expense", "收入", "income", "Income",
        "互转", "转账", "transfer", "借贷", "借出", "借入", "lend" -> true
        else -> false
    }

    /** 解析 CSV（支持双引号转义与自定义分隔符） */
    fun parseCsv(text: String, delimiter: Char = ','): List<List<String>> {
        val clean = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < clean.length && clean[i + 1] == '"') {
                            sb.append('"'); i++
                        } else inQuotes = false
                    } else sb.append(c)
                }
                c == '"' -> inQuotes = true
                c == delimiter -> {
                    current += sb.toString(); sb.clear()
                }
                c == '\r' -> Unit
                c == '\n' -> {
                    current += sb.toString(); sb.clear()
                    if (current.any { it.isNotBlank() }) rows += current.toList()
                    current.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || current.isNotEmpty()) {
            current += sb.toString()
            if (current.any { it.isNotBlank() }) rows += current.toList()
        }
        return rows
    }
}
