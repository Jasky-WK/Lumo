package com.qingjizhang.app.data

import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.XlsxWriter

/**
 * Excel（.xlsx）账单导出（N58）。
 *
 * 列与 CSV 导出完全一致（日期 / 时间 / 类型 / 大类 / 二级分类 / 账户 / 金额 / 备注），
 * 金额按「元」写数字单元格（Excel 里可以直接求和），表头加粗蓝底白字、首行冻结、
 * 列宽按内容估算（中文按 2 个字符宽）。零第三方依赖，见 [XlsxWriter]。
 */
object XlsxExporter {

    /** xlsx 的 MIME（写 MediaStore 用） */
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    fun build(
        txns: List<Txn>,
        accountName: (Long) -> String,
        categoryName: (Long) -> String,
        parentName: (Long) -> String
    ): ByteArray {
        val headers = listOf("日期", "时间", "类型", "大类", "二级分类", "账户", "金额", "备注")
        val rows = txns.sortedBy { it.dateMillis }.map { t ->
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
                TxnType.LEND_BORROW -> {
                    val (from, to) = Repository.lendEndpoints(t)
                    accountName(from) + " → " + accountName(to)
                }
                else -> accountName(t.accountId)
            }
            listOf(
                XlsxWriter.Cell.Text(DateFmt.isoDate(t.dateMillis)),
                XlsxWriter.Cell.Text(DateFmt.time(t.dateMillis)),
                XlsxWriter.Cell.Text(typeText),
                XlsxWriter.Cell.Text(cat),
                XlsxWriter.Cell.Text(sub),
                XlsxWriter.Cell.Text(acc),
                XlsxWriter.Cell.Num(t.amountCents / 100.0),
                XlsxWriter.Cell.Text(t.note)
            )
        }
        // 列宽：内容最大宽度 + 2（中文按 2 个字符宽），夹在 8~40 之间
        val columns = headers.mapIndexed { i, header ->
            var width = displayWidth(header)
            rows.forEach { row ->
                val text = (row[i] as? XlsxWriter.Cell.Text)?.value ?: ""
                width = maxOf(width, displayWidth(text))
            }
            XlsxWriter.Column(header, (width + 2).toFloat().coerceIn(8f, 40f))
        }
        return XlsxWriter.write(XlsxWriter.Sheet("账单", columns, rows))
    }

    /** 显示宽度：中文 / 全角按 2，其余按 1 */
    private fun displayWidth(s: String): Int {
        var w = 0
        s.forEach { w += if (it.code > 0x2E80) 2 else 1 }
        return w.coerceAtLeast(4)
    }
}
