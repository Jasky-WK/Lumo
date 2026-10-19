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
 * 搜索命令（SQL 下推 + 命中原因，P2-1 拆出）
 */

fun AppViewModel.setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    /** 搜索命中的类型（枚举名）：查询词是指定名称的子串时命中对应类型 */
internal fun AppViewModel.searchTypes(q: String): List<String> = buildList {
        if ("支出".contains(q)) add(TxnType.EXPENSE.name)
        if ("收入".contains(q)) add(TxnType.INCOME.name)
        if ("互转".contains(q)) add(TxnType.TRANSFER.name)
        if ("借贷".contains(q) || "借出".contains(q) || "借入".contains(q)) {
            add(TxnType.LEND_BORROW.name)
        }
    }

    /** LIKE 转义：% _ \ 三个通配符按 ESCAPE '\' 转掉，用户输入按字面匹配 */
internal fun AppViewModel.escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * 搜索结果补「命中原因」（金额 / 备注 / 对方 / 分类 / 大类 / 账户 / 类型）。
     * 结果集已被 SQL 收窄，这里只做纯内存判定，返回带原因的命中列表（按时间倒序）。
     */
fun AppViewModel.buildHits(txns: List<Txn>, q: String): List<SearchHit> {
        val query = q.trim()
        if (query.isEmpty()) return emptyList()
        val cats = categories.value
        val accs = accounts.value
        val numeric = query.replace("¥", "").replace(",", "").replace(" ", "")
            .toDoubleOrNull()?.let { Math.round(it * 100) }
        val typeNames = mapOf(
            TxnType.EXPENSE to "支出", TxnType.INCOME to "收入",
            TxnType.TRANSFER to "互转", TxnType.LEND_BORROW to "借贷"
        )

        return txns.mapNotNull { t ->
            val reasons = mutableListOf<String>()
            if (numeric != null && numeric > 0) {
                val amountText = Money.text(t.amountCents)
                if (amountText.contains(numeric.let { Money.text(it) }) ||
                    t.amountCents.toString() == numeric.toString()
                ) reasons += "金额"
            }
            if (t.note.isNotBlank() && t.note.contains(query)) reasons += "备注"
            if (t.counterparty.isNotBlank() && t.counterparty.contains(query)) reasons += "对方"

            val catName = cats.firstOrNull { it.id == t.categoryId }?.name
            val parentName = cats.firstOrNull { it.id == (cats.firstOrNull { c -> c.id == t.categoryId }?.parentId ?: 0L) }?.name
            if (catName != null && catName.contains(query)) reasons += "分类"
            if (parentName != null && parentName.contains(query)) reasons += "大类"

            val accName = accs.firstOrNull { it.id == t.accountId }?.name
            if (accName != null && accName.contains(query)) reasons += "账户"

            val typeName = typeNames[t.type] ?: ""
            if (typeName.contains(query)) reasons += "类型"
            if (t.type == TxnType.LEND_BORROW) {
                if ((if (t.direction == Direction.LEND) "借出" else "借入").contains(query)) reasons += "类型"
            }

            if (reasons.isEmpty()) null else SearchHit(t, reasons.distinct())
        }.sortedByDescending { it.txn.dateMillis }
    }
