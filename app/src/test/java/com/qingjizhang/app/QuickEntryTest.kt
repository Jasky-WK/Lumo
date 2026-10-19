package com.qingjizhang.app

import com.qingjizhang.app.data.Account
import com.qingjizhang.app.data.Category
import com.qingjizhang.app.data.CategoryType
import com.qingjizhang.app.data.QuickEntry
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.data.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 一句话记账（v2.8.0 Beta）解析器单测：金额 / 分类 / 账户 / 时间 / 类型 / 备注。
 * v2.9.0 起追加覆盖：同义词扩容、理财互转词只出金额、¥ 前缀、历史搭配兜底。
 * 全部纯函数，不需要 Android 运行时。
 */
class QuickEntryTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 17)

    private fun cat(id: Long, name: String, parentId: Long = 0L, income: Boolean = false, hidden: Boolean = false) =
        Category(
            id = id, ledgerId = 1L, name = name,
            type = if (income) CategoryType.INCOME else CategoryType.EXPENSE,
            parentId = parentId,
            hidden = hidden
        )

    private fun acc(id: Long, name: String) =
        Account(id = id, ledgerId = 1L, name = name, initialBalanceCents = 0L)

    private val cats = listOf(
        cat(1, "餐饮"), cat(2, "交通"), cat(3, "居家"), cat(4, "购物"),
        cat(5, "人情"), cat(6, "工资", income = true), cat(7, "其他收入", income = true),
        cat(8, "午饭", parentId = 1), cat(9, "打车", parentId = 2),
        cat(14, "娱乐"), cat(15, "医疗"), cat(16, "学习"), cat(17, "通讯")
    )
    private val accounts = listOf(
        acc(10, "支付宝"), acc(11, "微信钱包"), acc(12, "工商银行"), acc(13, "现金")
    )

    private fun parse(text: String, history: List<Txn> = emptyList()) =
        QuickEntry.parse(text, cats, accounts, today, history)

    /** 造一笔历史账：daysAgo 相对 today，deleted = 已进回收站 */
    private fun txn(
        id: Long,
        note: String,
        categoryId: Long = 0L,
        accountId: Long = 0L,
        expense: Boolean = true,
        daysAgo: Long = 10L,
        deleted: Boolean = false
    ) = Txn(
        id = id,
        ledgerId = 1L,
        accountId = accountId,
        categoryId = categoryId,
        type = if (expense) TxnType.EXPENSE else TxnType.INCOME,
        amountCents = 1000L,
        note = note,
        dateMillis = today.minusDays(daysAgo).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        deletedAt = if (deleted) 1L else 0L
    )

    @Test fun amount_arabic() {
        assertEquals(2500L, parse("午饭 25 支付宝")!!.amountCents)
    }

    @Test fun amount_decimal() {
        assertEquals(1850L, parse("打车 18.5 微信")!!.amountCents)
    }

    @Test fun amount_chinese() {
        assertEquals(2550L, parse("咖啡二十五块五")!!.amountCents)
    }

    @Test fun amount_chineseWhole() {
        // 三千二 = 3200 元 = 320000 分（尾数按最后一个量级的十分之一缩放）
        assertEquals(320000L, parse("房租 三千二")!!.amountCents)
    }

    @Test fun amount_wan() {
        assertEquals(960000L, parse("收到工资 9600 工商")!!.amountCents)
    }

    @Test fun noAmount_returnsNull() {
        assertNull(parse("午饭 支付宝"))
    }

    @Test fun type_and_category() {
        val d = parse("午饭 25 支付宝")!!
        assertEquals(TxnType.EXPENSE, d.type)
        // 账本里有「午饭」这个子分类，所以应该命中它而不是笼统的「餐饮」
        assertEquals("午饭", d.categoryName)
        assertEquals(10L, d.accountId)
        assertEquals(1L, d.parentId)
        assertEquals(8L, d.childId)
    }

    @Test fun type_and_category_fallbackToParent() {
        // 「咖啡」在账本里没有对应子分类 → 退回一级分类「餐饮」
        val d = parse("咖啡 25 支付宝")!!
        assertEquals("餐饮", d.categoryName)
        assertEquals(1L, d.parentId)
        assertEquals(0L, d.childId)
    }

    @Test fun income_keywords() {
        val d = parse("收到工资 9600 工商")!!
        assertEquals(TxnType.INCOME, d.type)
        assertEquals("工资", d.categoryName)
        assertEquals(12L, d.accountId)
    }

    @Test fun account_alias() {
        // 「微信」应该命中「微信钱包」
        assertEquals(11L, parse("打车 18.5 微信 昨天")!!.accountId)
    }

    @Test fun time_relative() {
        assertEquals(today, parse("午饭 25 支付宝")!!.date)
        assertEquals(today.minusDays(1), parse("午饭 25 支付宝 昨天")!!.date)
        assertEquals(today.minusDays(2), parse("午饭 25 支付宝 前天")!!.date)
    }

    @Test fun time_dayOfMonth() {
        assertEquals(today.withDayOfMonth(3), parse("房租 3200 3号")!!.date)
    }

    @Test fun note_leftover() {
        val d = parse("和小王吃饭 128.5 支付宝")!!
        assertEquals("和小王", d.note)
    }

    @Test fun note_strips_time_word() {
        val d = parse("打车 18.5 微信 昨天")!!
        assertEquals("", d.note)
    }

    @Test fun unmatched_returnsEmpty_notGuess() {
        val d = parse("25")!!
        assertEquals(0L, d.parentId)
        assertEquals(0L, d.childId)
        assertEquals(0L, d.accountId)
    }

    @Test fun child_category_drill() {
        // 命中子分类「午饭」时，返回父 id + 子 id，方便预填两级分类
        val d = parse("午饭 25 支付宝")!!
        assertEquals(1L, d.parentId)
        assertEquals(8L, d.childId)
    }

    @Test fun matchedWords_reported() {
        val d = parse("打车 18.5 微信 昨天")!!
        assertTrue(d.matched.contains("打车"))
        assertTrue(d.matched.contains("昨天"))
    }

    // ---------- v2.9.0：同义词扩容（每个大类至少一条新说法） ----------

    @Test fun synonym_dining_expanded() {
        assertEquals("餐饮", parse("早茶 48 微信")!!.categoryName)
        assertEquals("餐饮", parse("盒饭 22 支付宝")!!.categoryName)
        assertEquals("餐饮", parse("炸鸡 35 微信")!!.categoryName)
        assertEquals("餐饮", parse("甜品 28 支付宝")!!.categoryName)
        assertEquals("餐饮", parse("饮料 15 微信")!!.categoryName)
        // 单字同义词：面 / 粉
        assertEquals("餐饮", parse("炒面 18 微信")!!.categoryName)
        assertEquals("餐饮", parse("螺蛳粉 30 支付宝")!!.categoryName)
    }

    @Test fun synonym_transport_expanded() {
        assertEquals("交通", parse("网约车 35 支付宝")!!.categoryName)
        assertEquals("交通", parse("过路费 15 微信")!!.categoryName)
        assertEquals("交通", parse("充电 50 支付宝")!!.categoryName)
        assertEquals("交通", parse("罚款 200 微信")!!.categoryName)
        // 「共享单车」比旧词「单车」长，同组取长
        assertEquals("交通", parse("共享单车 3 支付宝")!!.categoryName)
    }

    @Test fun synonym_shopping_expanded() {
        assertEquals("购物", parse("数码 2999 支付宝")!!.categoryName)
        assertEquals("购物", parse("美妆 260 微信")!!.categoryName)
        assertEquals("购物", parse("宠物 180 支付宝")!!.categoryName)
        assertEquals("购物", parse("母婴 500 微信")!!.categoryName)
        assertEquals("购物", parse("家具 1200 支付宝")!!.categoryName)
        assertEquals("购物", parse("家电 3500 微信")!!.categoryName)
    }

    @Test fun synonym_entertainment_expanded() {
        assertEquals("娱乐", parse("旅游 3000 支付宝")!!.categoryName)
        assertEquals("娱乐", parse("酒店 460 微信")!!.categoryName)
        assertEquals("娱乐", parse("民宿 380 支付宝")!!.categoryName)
        assertEquals("娱乐", parse("手游 98 微信")!!.categoryName)
        assertEquals("娱乐", parse("视频会员 25 支付宝")!!.categoryName)
        assertEquals("娱乐", parse("演唱会 680 微信")!!.categoryName)
    }

    @Test fun synonym_medical_expanded() {
        assertEquals("医疗", parse("口腔 320 微信")!!.categoryName)
        assertEquals("医疗", parse("疫苗 158 支付宝")!!.categoryName)
        assertEquals("医疗", parse("保健品 240 微信")!!.categoryName)
        assertEquals("医疗", parse("眼镜 599 支付宝")!!.categoryName)
    }

    @Test fun synonym_learning_expanded() {
        assertEquals("学习", parse("网课 199 支付宝")!!.categoryName)
        assertEquals("学习", parse("考研 500 微信")!!.categoryName)
        assertEquals("学习", parse("考证 300 支付宝")!!.categoryName)
        assertEquals("学习", parse("家教 200 微信")!!.categoryName)
        // 「电子书」比旧词「书」长，同组取长
        assertEquals("学习", parse("电子书 39 支付宝")!!.categoryName)
    }

    @Test fun synonym_favor_expanded() {
        assertEquals("人情", parse("生日 288 微信")!!.categoryName)
        assertEquals("人情", parse("婚礼 600 现金")!!.categoryName)
        assertEquals("人情", parse("丧礼 500 现金")!!.categoryName)
    }

    @Test fun synonym_home_expanded() {
        assertEquals("居家", parse("车位 350 微信")!!.categoryName)
        assertEquals("居家", parse("保洁 120 支付宝")!!.categoryName)
        assertEquals("居家", parse("维修 400 微信")!!.categoryName)
    }

    @Test fun synonym_telecom_expanded() {
        assertEquals("通讯", parse("套餐 59 支付宝")!!.categoryName)
        // 「宽带费」归通讯：跨组取最长命中词，不能被居家的「宽带」抢先
        assertEquals("通讯", parse("宽带费 100 微信")!!.categoryName)
    }

    // ---------- v2.9.0：理财互转词只出金额不出分类 ----------

    @Test fun transferWords_amountOnly_noCategory() {
        // 理财 / 基金 / 股票 / 定投 / 余额宝 / 买入 是互转不是支出：只认金额，分类留空
        for (w in listOf("理财", "基金", "股票", "定投", "买入", "余额宝")) {
            val d = QuickEntry.parse("$w 1000 支付宝", cats, accounts, today)!!
            assertEquals(w, 100000L, d.amountCents)
            assertEquals(w, "", d.categoryName)
            assertEquals(w, 0L, d.parentId)
            assertEquals(w, 0L, d.childId)
            // 屏蔽只针对分类；账户识别不受影响（不会把互转误导成支出）
            assertEquals(w, 10L, d.accountId)
        }
    }

    @Test fun transferWords_incomeStillDetected() {
        // 「分红」是收入提示词，但输入含「基金」→ 类型按收入走，分类仍然留空
        val d = parse("基金分红 800 工商")!!
        assertEquals(TxnType.INCOME, d.type)
        assertEquals("", d.categoryName)
        assertEquals(80000L, d.amountCents)
        assertEquals(12L, d.accountId)
    }

    // ---------- v2.9.0：¥ / ￥ 前缀 ----------

    @Test fun yenPrefix_amountAndStrippedFromNote() {
        // ¥ / ￥ 前缀：金额照认，符号从备注里剔除
        val d1 = parse("¥25 午饭 支付宝")!!
        assertEquals(2500L, d1.amountCents)
        assertEquals("午饭", d1.categoryName)
        assertFalse(d1.note.contains("¥"))
        assertEquals("", d1.note)
        val d2 = parse("￥18.5 打车 微信")!!
        assertEquals(1850L, d2.amountCents)
        assertEquals("", d2.note)
        // 前缀 + 中文数字也能认
        val d3 = parse("奶茶 ¥二十六 微信")!!
        assertEquals(2600L, d3.amountCents)
        assertEquals("餐饮", d3.categoryName)
        assertEquals("", d3.note)
    }

    @Test fun yenPrefix_noAmount_returnsNull() {
        // 只有货币符号没有数字 → 仍然解析失败，交回用户手填
        assertNull(parse("¥ 支付宝"))
        assertNull(parse("午饭 ￥"))
    }

    // ---------- v2.9.0：历史搭配兜底 ----------

    @Test fun history_fallback_category_adoptedWhenSeenTwice() {
        val h = listOf(
            txn(1, "星巴克", categoryId = 1, accountId = 10, daysAgo = 3),
            txn(2, "星巴克 大杯", categoryId = 1, accountId = 10, daysAgo = 20)
        )
        val d = parse("星巴克 32 微信", h)!!
        assertEquals("餐饮", d.categoryName)
        assertEquals(1L, d.parentId)
        assertEquals(0L, d.childId)
        // 兜底命中的要标注来源，供 UI 黄条展示
        assertTrue(d.matched.contains("上次这么记"))
        // 兜底词来自用户输入本身，保留在备注里
        assertTrue(d.note.contains("星巴克"))
    }

    @Test fun history_fallback_category_notAdoptedWhenSeenOnce() {
        // 只出现过 1 次，不够支持数，不猜
        val h = listOf(txn(1, "星巴克", categoryId = 1, accountId = 10))
        val d = parse("星巴克 32 微信", h)!!
        assertEquals("", d.categoryName)
        assertEquals(0L, d.parentId)
        assertFalse(d.matched.contains("上次这么记"))
    }

    @Test fun history_fallback_category_notAdoptedWhenMixed() {
        // 同一个词两次指向不同分类，不敢猜
        val h = listOf(
            txn(1, "团建", categoryId = 1, accountId = 10),
            txn(2, "团建", categoryId = 2, accountId = 10)
        )
        val d = parse("团建 300 微信", h)!!
        assertEquals("", d.categoryName)
    }

    @Test fun history_fallback_childCategory() {
        // 历史里「楼下馆子」一直记成子分类「午饭」→ 兜底要带出父子两级
        val h = listOf(
            txn(1, "楼下馆子", categoryId = 8, accountId = 10),
            txn(2, "楼下馆子", categoryId = 8, accountId = 10, daysAgo = 30)
        )
        val d = parse("楼下馆子 30 支付宝", h)!!
        assertEquals("午饭", d.categoryName)
        assertEquals(1L, d.parentId)
        assertEquals(8L, d.childId)
    }

    @Test fun history_fallback_hiddenCategoryIgnored() {
        // 隐藏分类在记一笔里选不到，兜底也不采用
        val catsWithHidden = cats + cat(18, "隐藏类", parentId = 1, hidden = true)
        val h = listOf(
            txn(1, "私房钱", categoryId = 18, accountId = 10),
            txn(2, "私房钱", categoryId = 18, accountId = 10, daysAgo = 40)
        )
        val d = QuickEntry.parse("私房钱 200 微信", catsWithHidden, accounts, today, h)!!
        assertEquals("", d.categoryName)
        assertEquals(0L, d.parentId)
        assertFalse(d.matched.contains("上次这么记"))
    }

    @Test fun history_fallback_account_adopted() {
        // 同一个备注词两次都从「现金」出 → 账户也兜底；历史里没分类，分类仍然不猜
        val h = listOf(
            txn(1, "撸猫", accountId = 13, daysAgo = 2),
            txn(2, "撸猫 零食", accountId = 13, daysAgo = 15)
        )
        val d = parse("撸猫 66", h)!!
        assertEquals(13L, d.accountId)
        assertEquals("现金", d.accountName)
        assertEquals("", d.categoryName)
        assertTrue(d.matched.contains("上次这么记"))
    }

    @Test fun history_fallback_account_notAdoptedWhenMixed() {
        // 同一个词两次从不同账户出，不敢猜
        val h = listOf(
            txn(1, "撸猫", accountId = 10),
            txn(2, "撸猫", accountId = 11)
        )
        val d = parse("撸猫 66", h)!!
        assertEquals(0L, d.accountId)
        assertFalse(d.matched.contains("上次这么记"))
    }

    @Test fun history_fallback_staleIgnored() {
        // 超过 180 天的老账不参与兜底
        val h = listOf(
            txn(1, "星巴克", categoryId = 1, accountId = 10, daysAgo = 200),
            txn(2, "星巴克", categoryId = 1, accountId = 10, daysAgo = 181)
        )
        val d = parse("星巴克 32 微信", h)!!
        assertEquals("", d.categoryName)
    }

    @Test fun history_fallback_boundaryDayIncluded() {
        // 正好 180 天前（含）仍算「近 180 天」
        val h = listOf(
            txn(1, "星巴克", categoryId = 1, accountId = 10, daysAgo = 180),
            txn(2, "星巴克", categoryId = 1, accountId = 10, daysAgo = 100)
        )
        val d = parse("星巴克 32 微信", h)!!
        assertEquals("餐饮", d.categoryName)
    }

    @Test fun history_fallback_deletedIgnored() {
        // 一笔已删 + 一笔有效 = 只算 1 次，不采用
        val h = listOf(
            txn(1, "星巴克", categoryId = 1, accountId = 10, deleted = true),
            txn(2, "星巴克", categoryId = 1, accountId = 10, daysAgo = 30)
        )
        val d = parse("星巴克 32 微信", h)!!
        assertEquals("", d.categoryName)
    }

    @Test fun history_fallback_typeMismatchIgnored() {
        // 输入按「奖金」判为收入，历史里同备注的支出账不参与收入兜底
        val h = listOf(
            txn(1, "奖金", categoryId = 2, accountId = 10, expense = true),
            txn(2, "奖金", categoryId = 2, accountId = 10, expense = true, daysAgo = 30)
        )
        val d = parse("奖金 800 工商", h)!!
        assertEquals(TxnType.INCOME, d.type)
        assertEquals("", d.categoryName)
    }

    @Test fun history_fallback_highestFrequencyWins() {
        // 两个历史词都出现在输入里时，频率高的优先
        val h = listOf(
            txn(1, "拿铁", categoryId = 1, accountId = 10),
            txn(2, "拿铁", categoryId = 1, accountId = 10, daysAgo = 5),
            txn(3, "拿铁", categoryId = 1, accountId = 10, daysAgo = 8),
            txn(4, "公司", categoryId = 2, accountId = 10, daysAgo = 2),
            txn(5, "公司", categoryId = 2, accountId = 10, daysAgo = 9)
        )
        val d = parse("公司拿铁 30 微信", h)!!
        assertEquals("餐饮", d.categoryName)
    }

    @Test fun history_fallback_emptyHistory_noChange() {
        // 默认不传 history：行为与 v2.8.0 一致（现有调用方不用改）
        val d = parse("星巴克 32 微信")!!
        assertEquals("", d.categoryName)
        assertFalse(d.matched.contains("上次这么记"))
    }

    // ---------- 中文数字回归 ----------

    @Test fun amount_chinese_wanWithTail() {
        // 一万二 = 12000 元 = 1200000 分（尾数按万级的十分之一缩放）
        assertEquals(1200000L, parse("房租 一万二")!!.amountCents)
    }
}
