package com.qingjizhang.app.data

import com.qingjizhang.app.util.DateFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Random

/**
 * v2.9.0：一键生成「示例账本」。
 *
 * 设计要点：
 * - **独立账本**（不污染用户自己的账），用户在账本管理里整本删除即可清理；
 * - 全部走 DAO / 预设播种（[Seed.initLedgerPresets]），不手写 SQL，保证 account_ledgers 等关系表同步；
 * - 数据固定随机种子（20261018），每次生成的内容一致，方便截图与排查；
 * - 覆盖：两级分类支出、收入、互转（含手续费）、借贷、报销退款、月度房租/水电/工资、预算；
 * - 约 80 笔近 90 天的账，足够让总览 / 报表 / 日历三页同时有内容。
 */
object DemoData {

    const val LEDGER_NAME = "示例账本"
    const val BUDGET_CENTS = 500_000L // ¥5,000

    suspend fun create(db: AppDatabase): Long = withContext(Dispatchers.IO) {
        // 已存在就不重复生成
        val existing = db.ledgerDao().all().firstOrNull { it.name == LEDGER_NAME && it.deletedAt == 0L }
        if (existing != null) return@withContext existing.id

        val ledgerId = db.ledgerDao().insert(
            Ledger(name = LEDGER_NAME, isDefault = false, monthlyBudgetCents = BUDGET_CENTS)
        )
        // 预设分类 / 账户类型 / 互转类型 / 默认账户（与「新建账本」完全一致）
        Seed.initLedgerPresets(db, ledgerId)

        val accounts = db.accountDao().byLedger(ledgerId).associateBy { it.name }
        fun acc(name: String, fallback: String = "支付宝"): Long =
            (accounts[name] ?: accounts[fallback] ?: accounts.values.first()).id

        val cats = db.categoryDao().byLedger(ledgerId)
        val parents = cats.filter { it.parentId == 0L }
        fun cat(parent: String, child: String? = null): Long {
            val p = parents.firstOrNull { it.name == parent } ?: return 0L
            if (child == null) return p.id
            return cats.firstOrNull { it.parentId == p.id && it.name == child }?.id ?: p.id
        }

        val today = LocalDate.now()
        val rnd = Random(20261018L)
        val list = mutableListOf<Txn>()

        fun ts(daysAgo: Int): Long {
            val hour = 7 + rnd.nextInt(15)
            return DateFmt.toMillis(today.minusDays(daysAgo.toLong())) + hour * 3_600_000L
        }

        fun expense(
            daysAgo: Int,
            cents: Long,
            parent: String,
            child: String?,
            account: String,
            note: String,
            refund: Long = 0L,
            fee: Long = 0L,
            discount: Long = 0L
        ) {
            list += Txn(
                ledgerId = ledgerId,
                accountId = acc(account),
                categoryId = cat(parent, child),
                type = TxnType.EXPENSE,
                amountCents = cents,
                note = note,
                dateMillis = ts(daysAgo),
                refundCents = refund,
                feeCents = fee,
                discountCents = discount
            )
        }

        fun income(daysAgo: Int, cents: Long, parent: String, child: String?, note: String) {
            list += Txn(
                ledgerId = ledgerId,
                accountId = acc("储蓄卡"),
                categoryId = cat(parent, child),
                type = TxnType.INCOME,
                amountCents = cents,
                note = note,
                dateMillis = ts(daysAgo)
            )
        }

        // ---------- 日常三餐 / 咖啡 / 交通：近 60 天 ----------
        for (d in 0 until 60) {
            if (d % 2 == 0) expense(d, (8 + rnd.nextInt(12)) * 100L, "餐饮美食", "早餐", "支付宝", "早餐")
            if (rnd.nextInt(10) < 7) {
                expense(d, (18 + rnd.nextInt(25)) * 100L, "餐饮美食", "午餐", "支付宝", "午饭")
            }
            if (rnd.nextInt(10) < 6) {
                expense(d, (20 + rnd.nextInt(40)) * 100L, "餐饮美食", "晚餐", if (rnd.nextBoolean()) "微信钱包" else "支付宝", "晚饭")
            }
            if (d % 3 == 0) {
                expense(d, (15 + rnd.nextInt(18)) * 100L, "餐饮美食", "咖啡奶茶", "微信钱包", "咖啡")
            }
            if (d % 4 == 1) {
                expense(d, listOf(300L, 400L, 1500L, 2800L)[rnd.nextInt(4)], "交通出行", "公交地铁", "支付宝", "地铁")
            }
            if (d % 9 == 0) {
                expense(d, (18 + rnd.nextInt(45)) * 100L, "交通出行", "打车", "微信钱包", "打车")
            }
            if (d % 6 == 2) {
                expense(d, (30 + rnd.nextInt(90)) * 100L, "购物消费", "生活用品", "支付宝", "日用品")
            }
            if (d % 11 == 3) {
                expense(d, (35 + rnd.nextInt(120)) * 100L, "休闲娱乐", "运动健身", "支付宝", "健身")
            }
            if (d % 13 == 5) {
                expense(d, (45 + rnd.nextInt(200)) * 100L, "休闲娱乐", "电影演出", "支付宝", "电影")
            }
            if (d % 15 == 7) {
                expense(d, (60 + rnd.nextInt(300)) * 100L, "购物消费", "服饰鞋包", "支付宝", "衣服")
            }
        }

        // ---------- 月度固定：房租 / 水电 / 通讯 / 工资 ----------
        for (m in 0..2) {
            val base = today.minusMonths(m.toLong())
            val rentDays = (java.time.temporal.ChronoUnit.DAYS.between(base.withDayOfMonth(1), today)).toInt().coerceAtLeast(0)
            expense(rentDays, 350_000L, "居家生活", "房租物业", "储蓄卡", "房租")
            expense(rentDays + 2, (120 + rnd.nextInt(140)) * 100L, "居家生活", "水电燃气", "微信钱包", "水电费")
            expense(rentDays + 3, 15_900L, "居家生活", "通讯网络", "支付宝", "话费套餐")
            income(rentDays + 5, 1_200_000L, "工资薪资", "月薪", "工资到账")
        }

        // ---------- 带口径的账单：退款 / 手续费 / 优惠 ----------
        expense(
            6, 6_800L, "餐饮美食", "外卖", "支付宝", "外卖聚餐",
            refund = 1_000L, discount = 500L
        )
        expense(9, 3_200L, "购买异常", null, "支付宝", "测试分类留空") // cat 找不到 → 0（无分类）

        // ---------- 互转（含手续费）----------
        list += Txn(
            ledgerId = ledgerId,
            accountId = acc("支付宝"),
            transferToAccountId = acc("微信钱包"),
            type = TxnType.TRANSFER,
            amountCents = 50_000L,
            feeCents = 100L,
            note = "转到微信",
            dateMillis = ts(12)
        )
        list += Txn(
            ledgerId = ledgerId,
            accountId = acc("储蓄卡"),
            transferToAccountId = acc("支付宝"),
            type = TxnType.TRANSFER,
            amountCents = 200_000L,
            note = "充值支付宝",
            dateMillis = ts(20)
        )

        // ---------- 借贷：借出 2000 ----------
        list += Txn(
            ledgerId = ledgerId,
            accountId = acc("储蓄卡"),
            transferToAccountId = acc("借贷账户"),
            type = TxnType.LEND_BORROW,
            direction = Direction.LEND,
            amountCents = 200_000L,
            counterparty = "小王",
            note = "借给小王",
            dateMillis = ts(15)
        )

        // ---------- 其他收入 ----------
        income(25, 200_000L, "奖金红包", "红包", "过节红包")
        income(40, 8_600L, "理财收益", "余额宝", "余额宝收益")
        income(55, 120_000L, "兼职外快", "接单", "周末接单")

        db.txnDao().insertAll(list)

        // 月度预算
        db.ledgerDao().update(
            db.ledgerDao().all().first { it.id == ledgerId }.copy(monthlyBudgetCents = BUDGET_CENTS)
        )

        ledgerId
    }
}
