package com.qingjizhang.app

import com.qingjizhang.app.data.Category
import com.qingjizhang.app.data.CategoryType
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.data.TxnType
import com.qingjizhang.app.ui.matchesSpec
import com.qingjizhang.app.vm.TxnListSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.8.3 回归：通用明细页的下钻过滤谓词 [matchesSpec]。
 *
 * 曾经的 bug（v2.8.2 及以前）：分类通过条件写成 `spec.categoryId != 0L || ...`——
 * - 带了分类 id 的下钻（点「支出分类」某行）：第一个条件恒真，分类过滤被完全绕过
 * - 不带分类 id 的下钻（总览预算 / 逐月支出，categoryId = 0）：反而落入匹配分支，
 *   只放行「无分类」或「分类是一级分类」的账单——挂在二级分类下的支出整段消失，
 *   用户看到的就是「支出 tab 空的、全部 tab 只有互转」（2026-10-18 用户截图反馈）
 */
class TxnListSpecFilterTest {

    private val cats = listOf(
        Category(id = 1, ledgerId = 1, name = "餐饮", type = CategoryType.EXPENSE, parentId = 0),
        Category(id = 2, ledgerId = 1, name = "交通", type = CategoryType.EXPENSE, parentId = 0),
        Category(id = 8, ledgerId = 1, name = "午饭", type = CategoryType.EXPENSE, parentId = 1),
        Category(id = 9, ledgerId = 1, name = "打车", type = CategoryType.EXPENSE, parentId = 2)
    )
    private val catById = cats.associateBy { it.id }

    private val oct1 = 1_790_000_000_000L
    private val oct15 = oct1 + 14 * 86_400_000L
    private val nov1 = oct1 + 31 * 86_400_000L

    private fun txn(
        id: Long,
        type: TxnType,
        categoryId: Long = 0L,
        accountId: Long = 10L,
        date: Long = oct15
    ) = Txn(
        id = id, ledgerId = 1, accountId = accountId, categoryId = categoryId,
        type = type, amountCents = 1000, dateMillis = date
    )

    private val lunchOnChild = txn(1, TxnType.EXPENSE, categoryId = 8)   // 午饭（餐饮的二级）
    private val taxiOnChild = txn(2, TxnType.EXPENSE, categoryId = 9)    // 打车（交通的二级）
    private val foodOnParent = txn(3, TxnType.EXPENSE, categoryId = 1)   // 直接记在一级「餐饮」
    private val transfer = txn(4, TxnType.TRANSFER, categoryId = 0)      // 互转（无分类）
    private val income = txn(5, TxnType.INCOME, categoryId = 8)
    private val lend = txn(6, TxnType.LEND_BORROW, categoryId = 8)

    /** 总览下钻规格：本月 + 不带分类（预算卡 / 逐月支出趋势图点进来就是这个） */
    private val monthSpec = TxnListSpec(
        title = "本月账单", subtitle = "总览下钻", filter = 1,
        monthStart = oct1, monthEnd = nov1
    )

    @Test fun periodDrillDown_showsEverythingInRange() {
        // v2.8.2 这里二级分类的支出全部被漏掉，只剩互转
        assertTrue(matchesSpec(lunchOnChild, monthSpec, catById, emptyMap()))
        assertTrue(matchesSpec(taxiOnChild, monthSpec, catById, emptyMap()))
        assertTrue(matchesSpec(foodOnParent, monthSpec, catById, emptyMap()))
        assertTrue(matchesSpec(transfer, monthSpec, catById, emptyMap()))
        assertTrue(matchesSpec(income, monthSpec, catById, emptyMap()))
        assertTrue(matchesSpec(lend, monthSpec, catById, emptyMap()))
    }

    @Test fun periodDrillDown_respectsDateRange() {
        assertFalse(matchesSpec(txn(7, TxnType.EXPENSE, categoryId = 8, date = oct1 - 1), monthSpec, catById, emptyMap()))
        assertFalse(matchesSpec(txn(8, TxnType.EXPENSE, categoryId = 8, date = nov1), monthSpec, catById, emptyMap()))
    }

    @Test fun categoryById_includesChildCategories() {
        val spec = monthSpec.copy(categoryId = 1, categoryName = "餐饮")
        assertTrue(matchesSpec(lunchOnChild, spec, catById, emptyMap()))   // 午饭挂在餐饮下，要显示
        assertTrue(matchesSpec(foodOnParent, spec, catById, emptyMap()))  // 直接记餐饮，要显示
        assertFalse(matchesSpec(taxiOnChild, spec, catById, emptyMap()))  // 打车是交通的，不显示
        assertFalse(matchesSpec(transfer, spec, catById, emptyMap()))     // 无分类不混进来
    }

    @Test fun categoryByName_fallbackWhenIdUnknown() {
        val spec = monthSpec.copy(categoryId = 0, categoryName = "餐饮")
        assertTrue(matchesSpec(lunchOnChild, spec, catById, emptyMap()))
        assertTrue(matchesSpec(foodOnParent, spec, catById, emptyMap()))
        assertFalse(matchesSpec(taxiOnChild, spec, catById, emptyMap()))
        assertFalse(matchesSpec(transfer, spec, catById, emptyMap()))
    }

    @Test fun accountFilter_onlyThatAccount() {
        val spec = monthSpec.copy(accountId = 10)
        assertTrue(matchesSpec(lunchOnChild, spec, catById, emptyMap()))
        assertFalse(matchesSpec(txn(9, TxnType.EXPENSE, categoryId = 8, accountId = 11), spec, catById, emptyMap()))
    }

    @Test fun lendOnly_onlyLendBorrow() {
        val spec = monthSpec.copy(lendOnly = true)
        assertTrue(matchesSpec(lend, spec, catById, emptyMap()))
        assertFalse(matchesSpec(lunchOnChild, spec, catById, emptyMap()))
    }

    @Test fun tagFilter_onlyTagged() {
        val spec = monthSpec.copy(tagName = "出差")
        val tags = mapOf(1L to listOf("出差", "报销"), 2L to listOf("日常"))
        assertTrue(matchesSpec(lunchOnChild, spec, catById, tags))
        assertFalse(matchesSpec(taxiOnChild, spec, catById, tags))
        assertFalse(matchesSpec(transfer, spec, catById, tags)) // 没标签键的也不放行
    }

    @Test fun noFilter_atAll_showsEverything() {
        val spec = TxnListSpec(monthStart = oct1, monthEnd = nov1)
        assertTrue(matchesSpec(lunchOnChild, spec, catById, emptyMap()))
        assertTrue(matchesSpec(transfer, spec, catById, emptyMap()))
    }
}
