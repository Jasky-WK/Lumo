package com.qingjizhang.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.Repository
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.data.TxnType
import com.qingjizhang.app.ui.theme.FieldBg
import com.qingjizhang.app.ui.theme.Ink
import com.qingjizhang.app.ui.theme.Primary
import kotlin.math.abs

/**
 * N36：报表明细页的排序方式（时间 / 金额，各两个方向）。
 *
 * 排序只作用于「当前明细的筛选结果」（当前分类 / 账户 + 明细起止时间 + 页内 Tab 口径），
 * 不读也不动全局账单列表，所以首页与其它报表的顺序不受影响。
 *
 * [flat] = true 表示这种排序下不按天分组：金额是跨天比较的，若仍按天分组，
 * 「金额从高到低」会退化成「每天各自从高到低」，反而不符合预期，因此金额排序改成平铺列表
 * （每行副标题本来就有日期，不会丢失时间信息）。
 */
enum class TxnSort(
    /** 弹层里的完整文案 */
    val label: String,
    /** 入口上显示的短文案（当前排序状态） */
    val short: String,
    /** 是否平铺展示（true = 金额排序，不按天分组） */
    val flat: Boolean
) {
    TIME_DESC("时间：最新优先", "时间最新", false),
    TIME_ASC("时间：最早优先", "时间最早", false),
    AMOUNT_DESC("金额：从高到低", "金额高→低", true),
    AMOUNT_ASC("金额：从低到高", "金额低→高", true)
}

/**
 * 金额排序的比较口径：支出 / 收入用「实计金额」（报销、退款后的真实额度）的绝对值，
 * 互转与借贷没有报销概念，直接用 amountCents。取绝对值是为了让支出 / 收入 / 互转 / 借贷
 * 在同一份明细里能横向比「额度大小」，而不是把支出当负数排到最后。
 */
fun txnSortCents(t: Txn): Long = when (t.type) {
    TxnType.EXPENSE, TxnType.INCOME -> abs(Repository.effectiveCents(t))
    else -> abs(t.amountCents)
}

/**
 * 纯排序函数：只重排传进来的列表（= 明细页当前的筛选结果），不读不写任何全局数据。
 * 同日 / 同额时用 id（金额排序用日期）兜底，保证顺序稳定，不会每次重组都跳。
 */
fun sortTxns(list: List<Txn>, sort: TxnSort): List<Txn> = when (sort) {
    TxnSort.TIME_DESC -> list.sortedWith(
        compareByDescending<Txn> { it.dateMillis }.thenByDescending { it.id }
    )
    TxnSort.TIME_ASC -> list.sortedWith(
        compareBy<Txn> { it.dateMillis }.thenBy { it.id }
    )
    TxnSort.AMOUNT_DESC -> list.sortedWith(
        compareByDescending<Txn> { txnSortCents(it) }.thenByDescending { it.dateMillis }
    )
    TxnSort.AMOUNT_ASC -> list.sortedWith(
        compareBy<Txn> { txnSortCents(it) }.thenByDescending { it.dateMillis }
    )
}

/**
 * 明细页的排序入口：按钮上直接显示当前排序状态，点开在四种排序里选一个。
 */
@Composable
fun TxnSortChip(
    current: TxnSort,
    onPick: (TxnSort) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(FieldBg)
                .clickable { open = true }
                .padding(start = 7.dp, end = 9.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.SwapVert,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(current.short, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Primary)
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(16.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TxnSort.values().forEach { s ->
                val on = s == current
                DropdownMenuItem(
                    text = {
                        Text(
                            s.label,
                            fontSize = 13.sp,
                            color = if (on) Primary else Ink,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    trailingIcon = {
                        if (on) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    onClick = {
                        onPick(s)
                        open = false
                    }
                )
            }
        }
    }
}
