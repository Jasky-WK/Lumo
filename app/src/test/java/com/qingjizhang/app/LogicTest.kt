package com.qingjizhang.app

import com.qingjizhang.app.data.Account
import com.qingjizhang.app.data.Budget
import com.qingjizhang.app.data.BudgetDirection
import com.qingjizhang.app.data.CsvManager
import com.qingjizhang.app.data.Direction
import com.qingjizhang.app.data.EndMode
import com.qingjizhang.app.data.Freq
import com.qingjizhang.app.data.RecurringEngine
import com.qingjizhang.app.data.RecurringReminder
import com.qingjizhang.app.data.RecurringRule
import com.qingjizhang.app.data.Repository
import com.qingjizhang.app.data.Seed
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.data.TxnType
import com.qingjizhang.app.ui.evalExpression
import com.qingjizhang.app.ui.keypadAmount
import com.qingjizhang.app.ui.components.TxnSort
import com.qingjizhang.app.ui.components.sortTxns
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.util.XlsxWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 纯逻辑单测（JVM，不需要设备）：
 * 覆盖金额换算、周期账单日期推算、CSV 解析这三块最容易算错的地方。
 */
class LogicTest {

    // ---------------- 金额 ----------------

    @Test
    fun money_text_groups_and_keeps_two_decimals() {
        assertEquals("1,234.56", Money.text(123456))
        assertEquals("0.00", Money.text(0))
        assertEquals("0.05", Money.text(5))
        assertEquals("¥12,000.00", Money.withSymbol(1200000))
        assertEquals("-1.00", Money.text(-100))
    }

    @Test
    fun money_parse_round_trips() {
        assertEquals(123456L, Money.parse("1234.56"))
        assertEquals(123456L, Money.parse("1,234.56"))
        assertEquals(123456L, Money.parse("¥1234.56"))
        assertEquals(1200000L, Money.parse("12000"))
        assertEquals(5L, Money.parse("0.05"))
    }

    @Test
    fun money_parse_rejects_garbage() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("."))
        assertNull(Money.parse("-5"))
    }

    @Test
    fun money_rounds_half_up_without_float_drift() {
        // 0.1 + 0.2 这类浮点问题不能出现在金额里
        assertEquals(30L, Money.parse("0.30"))
        assertEquals(1000L, Money.parse("10"))
        assertEquals(1999L, Money.parse("19.99"))
        assertEquals("19.99", Money.text(1999))
    }

    // ---------------- 周期记账 ----------------

    private fun rule(
        start: LocalDate,
        freq: Freq,
        interval: Int = 1,
        endMode: EndMode = EndMode.NEVER,
        endCount: Int = 0,
        lastGenerated: Long = 0L,
        generatedCount: Int = 0,
        enabled: Boolean = true
    ) = RecurringRule(
        ledgerId = 1L,
        name = "测试",
        type = TxnType.EXPENSE,
        amountCents = 10000,
        accountId = 1L,
        freq = freq,
        interval = interval,
        startMillis = DateFmt.toMillis(start.atTime(LocalTime.of(9, 0))),
        endMode = endMode,
        endCount = endCount,
        enabled = enabled,
        lastGeneratedMillis = lastGenerated,
        generatedCount = generatedCount
    )

    private fun endOfDay(d: LocalDate): Long = DateFmt.toMillis(d.atTime(23, 59, 59))

    @Test
    fun monthly_rule_keeps_month_end_anchor() {
        // 1/31 的月度规则不能逐月压缩成 28 号
        val r = rule(LocalDate.of(2026, 1, 31), Freq.MONTHLY)
        val due = RecurringEngine.dueDates(r, DateFmt.toMillis(LocalDate.of(2026, 5, 1)))
        val days = due.map { DateFmt.isoDate(it) }
        assertEquals(
            listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30"),
            days
        )
    }

    @Test
    fun monthly_rule_generates_each_month_once() {
        val r = rule(LocalDate.of(2026, 9, 15), Freq.MONTHLY)
        // now = 12/31，应生成 9/15、10/15、11/15、12/15 共 4 期
        val due = RecurringEngine.dueDates(r, DateFmt.toMillis(LocalDate.of(2026, 12, 31)))
        assertEquals(4, due.size)
        assertEquals("2026-09-15", DateFmt.isoDate(due.first()))
        assertEquals("2026-12-15", DateFmt.isoDate(due.last()))
    }

    @Test
    fun due_dates_skip_already_generated() {
        val r = rule(
            start = LocalDate.of(2026, 9, 15),
            freq = Freq.MONTHLY,
            lastGenerated = DateFmt.toMillis(LocalDate.of(2026, 10, 15).atTime(LocalTime.of(9, 0))),
            generatedCount = 2
        )
        val due = RecurringEngine.dueDates(r, DateFmt.toMillis(LocalDate.of(2026, 12, 31)))
        assertEquals(listOf("2026-11-15", "2026-12-15"), due.map { DateFmt.isoDate(it) })
    }

    @Test
    fun interval_two_months() {
        val r = rule(LocalDate.of(2026, 1, 10), Freq.MONTHLY, interval = 2)
        val due = RecurringEngine.dueDates(r, DateFmt.toMillis(LocalDate.of(2026, 7, 1)))
        assertEquals(
            listOf("2026-01-10", "2026-03-10", "2026-05-10"),
            due.map { DateFmt.isoDate(it) }
        )
    }

    @Test
    fun weekly_rule_hits_same_weekday() {
        val r = rule(LocalDate.of(2026, 9, 21), Freq.WEEKLY) // 周一
        val due = RecurringEngine.dueDates(r, endOfDay(LocalDate.of(2026, 10, 12)))
        assertEquals(
            listOf("2026-09-21", "2026-09-28", "2026-10-05", "2026-10-12"),
            due.map { DateFmt.isoDate(it) }
        )
    }

    @Test
    fun future_occurrence_is_not_generated_early() {
        // 规则时刻是 09:00，当天 0 点时不该提前生成
        val r = rule(LocalDate.of(2026, 9, 21), Freq.WEEKLY)
        val due = RecurringEngine.dueDates(r, DateFmt.toMillis(LocalDate.of(2026, 10, 12)))
        assertEquals(listOf("2026-09-21", "2026-09-28", "2026-10-05"), due.map { DateFmt.isoDate(it) })
    }

    @Test
    fun disabled_rule_generates_nothing() {
        val r = rule(LocalDate.of(2026, 1, 1), Freq.DAILY, enabled = false)
        assertTrue(RecurringEngine.dueDates(r, DateFmt.toMillis(LocalDate.of(2026, 12, 31))).isEmpty())
    }

    @Test
    fun end_by_count_stops() {
        val r = rule(
            start = LocalDate.of(2026, 1, 1),
            freq = Freq.MONTHLY,
            endMode = EndMode.COUNT,
            endCount = 3
        )
        val due = RecurringEngine.dueDates(r, DateFmt.toMillis(LocalDate.of(2026, 12, 31)))
        assertEquals(3, due.size)
    }

    @Test
    fun end_by_date_stops() {
        // 结束日期是含当天的：3/1 那期应该被生成
        val r = rule(
            start = LocalDate.of(2026, 1, 1),
            freq = Freq.MONTHLY,
            endMode = EndMode.UNTIL
        ).copy(endMillis = DateFmt.toMillis(LocalDate.of(2026, 3, 1)))
        val due = RecurringEngine.dueDates(r, endOfDay(LocalDate.of(2026, 12, 31)))
        assertEquals(listOf("2026-01-01", "2026-02-01", "2026-03-01"), due.map { DateFmt.isoDate(it) })
    }

    @Test
    fun next_run_is_after_now() {
        val r = rule(LocalDate.of(2026, 9, 15), Freq.MONTHLY)
        val next = RecurringEngine.nextRun(r, DateFmt.toMillis(LocalDate.of(2026, 9, 20)))
        assertEquals("2026-10-15", DateFmt.isoDate(next!!))
    }

    @Test
    fun next_run_null_when_finished() {
        val r = rule(
            start = LocalDate.of(2026, 1, 1),
            freq = Freq.MONTHLY,
            endMode = EndMode.COUNT,
            endCount = 2,
            generatedCount = 2
        )
        assertNull(RecurringEngine.nextRun(r, DateFmt.toMillis(LocalDate.of(2026, 12, 31))))
    }

    @Test
    fun times_per_year() {
        assertEquals(365, RecurringEngine.timesPerYear(rule(LocalDate.of(2026, 1, 1), Freq.DAILY)))
        assertEquals(52, RecurringEngine.timesPerYear(rule(LocalDate.of(2026, 1, 1), Freq.WEEKLY)))
        assertEquals(12, RecurringEngine.timesPerYear(rule(LocalDate.of(2026, 1, 1), Freq.MONTHLY)))
        assertEquals(1, RecurringEngine.timesPerYear(rule(LocalDate.of(2026, 1, 1), Freq.YEARLY)))
        assertEquals(6, RecurringEngine.timesPerYear(rule(LocalDate.of(2026, 1, 1), Freq.MONTHLY, interval = 2)))
    }

    // ---------------- CSV ----------------

    @Test
    fun csv_parse_handles_quotes_and_commas() {
        val text = "a,b,c\n1,\"x,y\",\"he said \"\"hi\"\"\"\n"
        val rows = CsvManager.parseCsv(text)
        assertEquals(2, rows.size)
        assertEquals(listOf("1", "x,y", "he said \"hi\""), rows[1])
    }

    @Test
    fun csv_parse_ignores_blank_lines_and_bom() {
        val text = "\uFEFF日期,金额\r\n\r\n2026-09-21,38.00\r\n"
        val rows = CsvManager.parseCsv(text)
        assertEquals(2, rows.size)
        assertEquals(listOf("日期", "金额"), rows[0])
        assertEquals(listOf("2026-09-21", "38.00"), rows[1])
    }

    @Test
    fun csv_template_matches_export_header() {
        val template = CsvManager.buildTemplate()
        val rows = CsvManager.parseCsv(template)
        assertEquals(CsvManager.HEADER, rows[0])
        // 模板必须带 BOM，Excel 打开才不乱码
        assertTrue(template.startsWith("\uFEFF"))
        // 模板里的示例行应能被解析成 8 列
        assertEquals(8, rows[1].size)
    }

    // ---------------- CSV 导入预览与列映射（M2） ----------------

    @Test
    fun csv_header_detection_and_auto_map_by_name() {
        // 列顺序被打乱，也要按列名对上
        val header = listOf("备注", "金额", "日期", "类型", "大类", "二级分类", "账户")
        assertTrue(CsvManager.looksLikeHeader(header))
        val map = CsvManager.autoMap(header, hasHeader = true)
        assertEquals(2, map[CsvManager.Field.DATE])
        assertEquals(1, map[CsvManager.Field.AMOUNT])
        assertEquals(0, map[CsvManager.Field.NOTE])
        assertEquals(4, map[CsvManager.Field.CATEGORY])
        assertEquals(6, map[CsvManager.Field.ACCOUNT])
    }

    @Test
    fun csv_without_header_falls_back_to_position() {
        val header = listOf("第 1 列", "第 2 列", "第 3 列")
        assertTrue(!CsvManager.looksLikeHeader(header))
        val map = CsvManager.autoMap(header, hasHeader = false)
        assertEquals(0, map[CsvManager.Field.DATE])
        assertEquals(1, map[CsvManager.Field.TIME])
        assertEquals(2, map[CsvManager.Field.TYPE])
        // 只有 3 列时，没有的字段应为「不导入」而不是越界的下标
        assertEquals(-1, map[CsvManager.Field.ACCOUNT])
        assertEquals(-1, map[CsvManager.Field.AMOUNT])
    }

    @Test
    fun csv_preview_reports_reason_per_line() {
        val headers = listOf("日期", "时间", "类型", "大类", "二级分类", "账户", "金额", "备注")
        val map = CsvManager.autoMap(headers, hasHeader = true)
        val rows = listOf(
            listOf("2026-09-21", "12:30", "支出", "餐饮美食", "午餐", "支付宝", "38.00", "午饭"),
            listOf("2026/9/22", "", "收入", "工资薪资", "月薪", "储蓄卡", "¥12,000.00", "9 月"),
            listOf("不是日期", "", "支出", "餐饮美食", "午餐", "支付宝", "38.00", ""),
            listOf("2026-09-23", "", "买买买", "购物消费", "衣服", "支付宝", "88.00", "")
        )
        val preview = CsvManager.previewRows(rows, map, hasHeader = true)
        assertEquals(4, preview.size)
        // 前两行可导入，第二行的宽松格式（斜杠日期、¥ 与千分位）也要认出来
        assertTrue(preview[0].ok)
        assertTrue(preview[1].ok)
        assertEquals("收入", preview[1].typeText)
        // 第三行日期读不出来，第四行类型不认识；行号从表头下一行（第 2 行）开始算
        assertTrue(!preview[2].ok)
        assertTrue(preview[2].error!!.contains("日期"))
        assertEquals(4, preview[2].lineNo)
        assertTrue(!preview[3].ok)
        assertTrue(preview[3].error!!.contains("类型"))
    }

    @Test
    fun csv_preview_guesses_type_when_blank() {
        val headers = listOf("日期", "金额", "类型")
        val map = CsvManager.autoMap(headers, hasHeader = true)
        val preview = CsvManager.previewRows(
            listOf(listOf("2026-09-21", "-38.00", ""), listOf("2026-09-21", "12000.00", "")),
            map,
            hasHeader = true
        )
        // 类型空着时按金额正负猜，避免整行白丢
        assertEquals("支出", preview[0].typeText)
        assertEquals("收入", preview[1].typeText)
    }

    // ---------------- 借贷还款（N7） ----------------

    private fun lendTxn(
        id: Long,
        direction: Direction,
        cents: Long,
        settled: Long = 0L
    ) = Txn(
        id = id,
        ledgerId = 1L,
        accountId = 10L,
        transferToAccountId = 20L,
        type = TxnType.LEND_BORROW,
        direction = direction,
        amountCents = cents,
        dateMillis = 1_700_000_000_000L,
        settledTxnId = settled
    )

    @Test
    fun lend_outstanding_after_partial_repay() {
        // 借出 1000，收回 400 → 还剩 600 待收回
        val original = lendTxn(1L, Direction.LEND, 100_000L)
        val repay = lendTxn(2L, Direction.BORROW, 40_000L, settled = 1L)
        val txns = listOf(original, repay)

        assertTrue(Repository.isSettlement(repay))
        assertTrue(!Repository.isSettlement(original))
        assertEquals(40_000L, Repository.settledCentsOf(txns, 1L))
        assertEquals(60_000L, Repository.outstandingCents(txns, original))
        // 还款流水本身没有「未结金额」
        assertEquals(0L, Repository.outstandingCents(txns, repay))
        assertEquals(listOf(2L), Repository.settlementsOf(txns, 1L).map { it.id })
    }

    @Test
    fun lend_totals_exclude_settled_part() {
        // 借出 1000 收 400，借入 300 还 100 → 待收回 600、待归还 200
        val txns = listOf(
            lendTxn(1L, Direction.LEND, 100_000L),
            lendTxn(2L, Direction.BORROW, 40_000L, settled = 1L),
            lendTxn(3L, Direction.BORROW, 30_000L),
            lendTxn(4L, Direction.LEND, 10_000L, settled = 3L)
        )
        val (receivable, payable) = Repository.lendTotals(txns)
        assertEquals(60_000L, receivable)
        assertEquals(20_000L, payable)
    }

    @Test
    fun lend_totals_never_negative() {
        // 多还了也不会出现负数余额
        val txns = listOf(
            lendTxn(1L, Direction.LEND, 10_000L),
            lendTxn(2L, Direction.BORROW, 30_000L, settled = 1L)
        )
        val (receivable, payable) = Repository.lendTotals(txns)
        assertEquals(0L, receivable)
        assertEquals(0L, payable)
    }

    // ---------------- 周期提醒（N8） ----------------

    @Test
    fun reminder_is_ahead_of_next_run() {
        val start = DateFmt.toMillis(LocalDate.of(2026, 1, 10).atTime(LocalTime.of(9, 0)))
        val rule = RecurringRule(
            id = 1L,
            ledgerId = 1L,
            name = "房租",
            amountCents = 200_000L,
            accountId = 1L,
            freq = Freq.MONTHLY,
            startMillis = start,
            notifyAheadDays = 2
        )
        val now = DateFmt.toMillis(LocalDate.of(2026, 3, 1).atTime(LocalTime.of(8, 0)))
        val at = RecurringReminder.nextReminderAt(listOf(rule), now)
        // 下一期是 3/10，提前 2 天 → 3/8 09:00
        assertEquals("2026-03-08", DateFmt.isoDate(at!!))
    }

    @Test
    fun reminder_ignores_rules_without_alert() {
        val start = DateFmt.toMillis(LocalDate.of(2026, 1, 10).atTime(LocalTime.of(9, 0)))
        val rule = RecurringRule(
            id = 1L,
            ledgerId = 1L,
            name = "房租",
            amountCents = 200_000L,
            accountId = 1L,
            freq = Freq.MONTHLY,
            startMillis = start,
            notifyAheadDays = 0
        )
        assertNull(RecurringReminder.nextReminderAt(listOf(rule), start))
    }

    // ---------------- 计算器 ----------------

    @Test
    fun calculator_follows_precedence() {
        assertEquals(37.5, evalExpression("12.5*3"), 0.001)
        assertEquals(4.0, evalExpression("8/2"), 0.001)
        assertEquals(7.0, evalExpression("1+2*3"), 0.001)
        assertEquals(2.5, evalExpression("10-5*1.5"), 0.001)
        assertEquals(1.5, evalExpression("0.5+1"), 0.001)
    }

    @Test
    fun calculator_handles_partial_input() {
        // 用户输入到一半时不应该崩，直接算出当前可算的部分
        assertEquals(12.0, evalExpression("12."), 0.001)
        assertEquals(12.0, evalExpression("12+"), 0.001)
        assertEquals(0.0, evalExpression(""), 0.001)
        assertEquals(0.0, evalExpression("+"), 0.001)
        assertEquals(5.0, evalExpression("5"), 0.001)
    }

    @Test
    fun calculator_division_by_zero_is_safe() {
        assertEquals(0.0, evalExpression("5/0"), 0.001)
    }

    @Test
    fun calculator_ignores_illegal_chars() {
        assertEquals(7.0, evalExpression("1+2*3abc"), 0.001)
    }

    // ---------------- 记账键盘：编辑账单时数字键整体替换（F18） ----------------

    @Test
    fun keypad_replaces_old_amount_once_then_appends() {
        // 旧金额 1234：首次按 5 整体替换成 5，再按 6 正常追加成 56
        val first = keypadAmount("1234", "5", replacePending = true)
        assertEquals("5" to false, first)
        assertEquals("56" to false, keypadAmount(first.first, "6", first.second))
        // 小数点同样算「输了一个新数字」：从 0. 起，而不是拼到旧值后面
        assertEquals("0." to false, keypadAmount("1234", ".", replacePending = true))
        // 没进入替换态时维持原有追加语义
        assertEquals("12345" to false, keypadAmount("1234", "5", replacePending = false))
    }

    @Test
    fun keypad_backspace_keeps_delete_semantics() {
        // 替换语义未触发时，退格仍是删掉最后一位，并清掉标记
        assertEquals("123" to false, keypadAmount("1234", "del", replacePending = true))
        assertEquals("12." to false, keypadAmount("12.3", "del", replacePending = false))
    }

    // ---------------- 账户：多账本适用 ----------------

    private fun account(csv: String, ledgerId: Long = 1L) = Account(
        ledgerId = ledgerId,
        name = "测试账户",
        ledgerIdsCsv = csv
    )

    @Test
    fun account_applies_to_multiple_ledgers() {
        val a = account("1,2,3")
        assertTrue(a.appliesTo(1L))
        assertTrue(a.appliesTo(3L))
        assertTrue(!a.appliesTo(4L))
        assertEquals(listOf(1L, 2L, 3L), a.ledgerIds)
    }

    @Test
    fun account_without_csv_falls_back_to_own_ledger() {
        // 老数据（迁移前）csv 为空，应只适用于自己所属的账本
        val a = account("", ledgerId = 7L)
        assertEquals(listOf(7L), a.ledgerIds)
        assertTrue(a.appliesTo(7L))
        assertTrue(!a.appliesTo(1L))
    }

    // ---------------- 预设分类（新账本也要有） ----------------

    @Test
    fun presets_cover_expense_and_income() {
        // 9 个支出一级（含漏记款）+ 6 个收入一级（含漏记款）
        assertEquals(15, Seed.presetTopLevelCount)
        assertEquals(51, Seed.presetSubCount)
        assertEquals(7, Seed.presetAccountCount)
    }

    // ---------------- 借贷：资金账户 ↔ 借贷账户（不再凭空多钱） ----------------

    private fun lendTxn(direction: Direction, amountCents: Long) = Txn(
        ledgerId = 1L,
        accountId = 10L,               // 资金账户（如支付宝）
        transferToAccountId = 20L,     // 借贷账户
        type = TxnType.LEND_BORROW,
        direction = direction,
        amountCents = amountCents,
        dateMillis = 0L
    )

    @Test
    fun borrow_moves_money_from_loan_account_to_fund_account() {
        // 借入 10 元：资金账户 +10（到账），借贷账户 -10（负债）
        val delta = Repository.accountDeltas(listOf(lendTxn(Direction.BORROW, 1000L)))
        assertEquals(1000L, delta[10L] ?: 0L)
        assertEquals(-1000L, delta[20L] ?: 0L)
    }

    @Test
    fun lend_moves_money_from_fund_account_to_loan_account() {
        // 借出 1200 元：资金账户 -1200，借贷账户 +1200（应收）
        val delta = Repository.accountDeltas(listOf(lendTxn(Direction.LEND, 120000L)))
        assertEquals(-120000L, delta[10L] ?: 0L)
        assertEquals(120000L, delta[20L] ?: 0L)
    }

    @Test
    fun lend_endpoints_follow_direction() {
        assertEquals(10L to 20L, Repository.lendEndpoints(lendTxn(Direction.LEND, 100L)))
        assertEquals(20L to 10L, Repository.lendEndpoints(lendTxn(Direction.BORROW, 100L)))
    }

    @Test
    fun borrow_never_creates_money_out_of_nowhere() {
        // 借入 10 元 + 借出 1200 元：所有账户净影响之和必须为 0（钱只在账户之间流动）
        val txns = listOf(
            lendTxn(Direction.BORROW, 1000L),
            lendTxn(Direction.LEND, 120000L)
        )
        val delta = Repository.accountDeltas(txns)
        assertEquals(0L, delta.values.sum())
        // 借贷账户净额 = 借出 1200 - 借入 10 = 1190（应收净额）
        assertEquals(119000L, delta[20L] ?: 0L)
        // 资金账户净额 = 借入 10 - 借出 1200 = -1190
        assertEquals(-119000L, delta[10L] ?: 0L)
        // 借贷不计入收入 / 支出
        assertTrue(txns.none { Repository.affectsIncomeExpense(it) })
    }

    // ---------------- 统计口径：不计收支 / 不计预算 ----------------

    private fun txn(
        type: TxnType,
        excludeStats: Boolean = false,
        excludeBudget: Boolean = false
    ) = Txn(
        ledgerId = 1L,
        accountId = 1L,
        type = type,
        amountCents = 1000L,
        dateMillis = 0L,
        excludeFromStats = excludeStats,
        excludeFromBudget = excludeBudget
    )

    @Test
    fun excluded_from_stats_still_changes_account_balance() {
        val t = txn(TxnType.EXPENSE, excludeStats = true)
        // 不进收支统计
        assertTrue(!Repository.countsInStats(t))
        // 但账户余额照常变化
        assertEquals(-1000L, Repository.accountDeltas(listOf(t))[1L] ?: 0L)
    }

    @Test
    fun excluded_from_budget_only_affects_budget() {
        val t = txn(TxnType.EXPENSE, excludeBudget = true)
        assertTrue(Repository.countsInStats(t))     // 收支统计照算
        assertTrue(!Repository.countsInBudget(t))   // 预算不算
    }

    @Test
    fun transfer_and_lend_never_count_in_stats_or_budget() {
        val tr = txn(TxnType.TRANSFER)
        val ld = txn(TxnType.LEND_BORROW)
        assertTrue(!Repository.countsInStats(tr) && !Repository.countsInBudget(tr))
        assertTrue(!Repository.countsInStats(ld) && !Repository.countsInBudget(ld))
    }

    @Test
    fun income_never_counts_in_budget() {
        assertTrue(!Repository.countsInBudget(txn(TxnType.INCOME)))
        assertTrue(Repository.countsInStats(txn(TxnType.INCOME)))
    }

    // ---------------- CSV 导入：编码 / 分隔符 / 日期与金额容错 ----------------

    @Test
    fun csv_import_accepts_excel_style_dates() {
        assertEquals(LocalDate.of(2026, 9, 21), CsvManager.parseDate("2026-09-21"))
        assertEquals(LocalDate.of(2026, 9, 21), CsvManager.parseDate("2026/9/21"))
        assertEquals(LocalDate.of(2026, 9, 21), CsvManager.parseDate("2026.09.21"))
        assertEquals(LocalDate.of(2026, 9, 21), CsvManager.parseDate("2026年9月21日"))
        assertEquals(LocalDate.of(2026, 9, 21), CsvManager.parseDate("2026-09-21 12:30"))
        assertNull(CsvManager.parseDate("9/21/2026"))
    }

    @Test
    fun csv_import_accepts_loose_times() {
        assertEquals(LocalTime.of(12, 30), CsvManager.parseTime("12:30"))
        assertEquals(LocalTime.of(9, 5), CsvManager.parseTime("9:05"))
        assertEquals(LocalTime.of(12, 30, 15), CsvManager.parseTime("12:30:15"))
        assertNull(CsvManager.parseTime(""))
    }

    @Test
    fun csv_import_accepts_loose_amounts() {
        assertEquals(3800L to false, CsvManager.parseAmount("38.00"))
        assertEquals(120000L to false, CsvManager.parseAmount("¥1,200.00"))
        assertEquals(3800L to true, CsvManager.parseAmount("-38"))
        assertEquals(3800L to true, CsvManager.parseAmount("(38)"))
        assertEquals(3800L to false, CsvManager.parseAmount("３８"))   // 全角数字
        assertNull(CsvManager.parseAmount("支付宝"))
    }

    @Test
    fun csv_import_detects_delimiter() {
        assertEquals(',', CsvManager.detectDelimiter("日期,时间,类型\n2026-09-21,12:30,支出"))
        assertEquals(';', CsvManager.detectDelimiter("日期;时间;类型\n2026-09-21;12:30;支出"))
        assertEquals('\t', CsvManager.detectDelimiter("日期\t时间\t类型\n2026-09-21\t12:30\t支出"))
    }

    @Test
    fun csv_import_decodes_gbk_from_excel() {
        // Excel 在中文环境常存成 GBK（ANSI），按 UTF-8 读会乱码，必须能自动回退
        val raw = "日期,时间,类型,大类,账户,金额,备注"
        val bytes = raw.toByteArray(charset("GB18030"))
        assertEquals(raw, CsvManager.decodeText(bytes))
    }

    @Test
    fun csv_import_parses_semicolon_file_with_quoted_amount() {
        val text = "日期;时间;类型;大类;账户;金额;备注\n2026-09-21;12:30;支出;餐饮美食;支付宝;\"1,200.00\";和同事吃饭"
        val rows = CsvManager.parseCsv(text, CsvManager.detectDelimiter(text))
        assertEquals(2, rows.size)
        assertEquals("1,200.00", rows[1][5])
        assertEquals(120000L to false, CsvManager.parseAmount(rows[1][5]))
    }

    // ---------------- 收支日历：某天的汇总 ----------------

    @Test
    fun csv_export_keeps_both_sides_of_a_lend() {
        // 借入导出成「借贷账户 → 资金账户」，导出再导入不会丢一端
        val csv = CsvManager.buildCsv(
            txns = listOf(lendTxn(Direction.BORROW, 10000L)),
            accountName = { id -> if (id == 10L) "支付宝" else "借贷账户" },
            categoryName = { "" },
            parentName = { "" }
        )
        assertTrue(csv.contains("借入"))
        assertTrue(csv.contains("借贷账户 → 支付宝"))
    }

    @Test
    fun day_range_of_a_single_day_covers_only_that_day() {
        // 日历点某天进明细时，时间范围必须是 [当天 0:00, 次日 0:00)
        val day = LocalDate.of(2026, 9, 20)
        val spec = com.qingjizhang.app.ui.TxnListSpecs.day(day)
        assertTrue(spec.monthStart > 0L)
        assertEquals(DateFmt.toMillis(day.plusDays(1)), spec.monthEnd)
        assertEquals("2026-09-20 账单", spec.title)
    }

    // ---------------- 自定义预算（N30） ----------------

    private fun budget(
        direction: Int = BudgetDirection.EXPENSE,
        categoryId: Long = 0L,
        accountId: Long = 0L,
        keyword: String = "",
        start: LocalDate = LocalDate.of(2026, 9, 20),
        end: LocalDate = LocalDate.of(2026, 10, 19),
        amountCents: Long = 300_000L
    ) = Budget(
        id = 1L,
        ledgerId = 1L,
        name = "旅游基金",
        startMillis = DateFmt.toMillis(start),
        endMillis = DateFmt.toMillis(end),
        amountCents = amountCents,
        direction = direction,
        categoryId = categoryId,
        accountId = accountId,
        keyword = keyword
    )

    private fun budgetTxn(
        id: Long,
        cents: Long,
        date: LocalDate,
        type: TxnType = TxnType.EXPENSE,
        categoryId: Long = 0L,
        accountId: Long = 1L,
        note: String = "",
        counterparty: String = "",
        refundCents: Long = 0L,
        excludeBudget: Boolean = false
    ) = Txn(
        id = id,
        ledgerId = 1L,
        accountId = accountId,
        categoryId = categoryId,
        type = type,
        amountCents = cents,
        dateMillis = DateFmt.toMillis(date),
        counterparty = counterparty,
        note = note,
        refundCents = refundCents,
        excludeFromBudget = excludeBudget
    )

    @Test
    fun custom_budget_range_covers_both_ends() {
        val b = budget() // 2026-09-20 ~ 2026-10-19
        assertTrue(Repository.inBudgetRange(b, DateFmt.toMillis(LocalDate.of(2026, 9, 20))))
        assertTrue(Repository.inBudgetRange(b, DateFmt.toMillis(LocalDate.of(2026, 10, 19))))
        assertTrue(!Repository.inBudgetRange(b, DateFmt.toMillis(LocalDate.of(2026, 9, 19))))
        assertTrue(!Repository.inBudgetRange(b, DateFmt.toMillis(LocalDate.of(2026, 10, 20))))
        // 未开始 / 进行中 / 已结束
        assertEquals(0, Repository.budgetPhase(b, LocalDate.of(2026, 9, 19)))
        assertEquals(1, Repository.budgetPhase(b, LocalDate.of(2026, 9, 20)))
        assertEquals(1, Repository.budgetPhase(b, LocalDate.of(2026, 10, 19)))
        assertEquals(2, Repository.budgetPhase(b, LocalDate.of(2026, 10, 20)))
    }

    @Test
    fun custom_budget_matches_bindings_and_keyword() {
        // 支出 + 绑分类 9 + 绑账户 3 + 关键词 Travel
        val b = budget(categoryId = 9L, accountId = 3L, keyword = "Travel")
        val hit = budgetTxn(
            id = 1L, cents = 10_000L, date = LocalDate.of(2026, 9, 25),
            categoryId = 9L, accountId = 3L, note = "东京 travel 机票"
        )
        assertTrue(Repository.matchesBudget(b, hit))
        assertTrue(!Repository.matchesBudget(b, hit.copy(id = 2L, categoryId = 8L)))
        assertTrue(!Repository.matchesBudget(b, hit.copy(id = 3L, accountId = 4L)))
        assertTrue(!Repository.matchesBudget(b, hit.copy(id = 4L, note = "买菜")))
        assertTrue(!Repository.matchesBudget(b, hit.copy(id = 5L, dateMillis = DateFmt.toMillis(LocalDate.of(2026, 10, 20)))))
        // 默认支出口径：收入不算；收入口径的预算才认收入
        val income = hit.copy(id = 6L, type = TxnType.INCOME)
        assertTrue(!Repository.matchesBudget(b, income))
        assertTrue(Repository.matchesBudget(b.copy(direction = BudgetDirection.INCOME), income))
        // 关键词忽略大小写，也能匹配「对方」；空关键词表示不限
        assertTrue(Repository.matchesBudgetKeyword("travel", hit))
        assertTrue(Repository.matchesBudgetKeyword("东家", hit.copy(counterparty = "东家超市")))
        assertTrue(Repository.matchesBudgetKeyword("", hit))
    }

    @Test
    fun custom_budget_used_sums_effective_cents_and_skips_excluded() {
        val b = budget() // 2026-09-20 ~ 2026-10-19，预算 3000 元
        val txns = listOf(
            budgetTxn(1L, 100_000L, LocalDate.of(2026, 9, 21)),                       // 算：1000 元
            budgetTxn(2L, 50_000L, LocalDate.of(2026, 10, 19), refundCents = 20_000L), // 算：实计 300 元
            budgetTxn(3L, 80_000L, LocalDate.of(2026, 10, 20)),                       // 区间外，不算
            budgetTxn(4L, 60_000L, LocalDate.of(2026, 9, 30), excludeBudget = true),  // 不计入预算
            budgetTxn(5L, 70_000L, LocalDate.of(2026, 9, 25), accountId = 7L),        // 不计入净资产的账户
            budgetTxn(6L, 90_000L, LocalDate.of(2026, 9, 25), type = TxnType.INCOME)  // 支出口径不算
        )
        // N20：不计入净资产的账户（7）的流水不进预算统计
        assertEquals(130_000L, Repository.budgetUsedCents(b, txns, setOf(7L)))
        // 账户都在统计范围内时，第 5 笔也算进来
        assertEquals(200_000L, Repository.budgetUsedCents(b, txns))
    }

    // ---------------- 账户余额调整（N31） ----------------

    @Test
    fun balance_adjust_direction_follows_diff_sign() {
        // 差额 = 新余额 − 旧余额：正数收入、负数支出、0 不生成账单
        assertNull(Repository.balanceAdjustType(0L))
        assertEquals(TxnType.INCOME, Repository.balanceAdjustType(1L))
        assertEquals(TxnType.EXPENSE, Repository.balanceAdjustType(-1L))
        assertEquals(TxnType.INCOME, Repository.balanceAdjustType(12_345L))
        assertEquals(TxnType.EXPENSE, Repository.balanceAdjustType(-5_000L))
    }

    @Test
    fun balance_adjust_without_txn_keeps_target_balance() {
        // N31：关闭「生成账单」时，差额写进初始余额，不新增任何账单也已经是目标余额
        val account = Account(id = 1L, ledgerId = 1L, name = "支付宝", initialBalanceCents = 10_000L)
        val txns = listOf(
            Txn(
                id = 1L, ledgerId = 1L, accountId = 1L, type = TxnType.EXPENSE,
                amountCents = 3_000L, dateMillis = 0L
            )
        )
        // 余额 = 初始余额 + 流水净影响（与 Repository.balancesOf 同一口径）
        val current = account.initialBalanceCents + (Repository.accountDeltas(txns)[account.id] ?: 0L)
        assertEquals(7_000L, current) // 100 - 30 = 70 元

        val target = 12_000L
        val diff = target - current
        assertEquals(TxnType.INCOME, Repository.balanceAdjustType(diff))
        val adjusted = account.copy(initialBalanceCents = account.initialBalanceCents + diff)
        // 余额立即变成目标值，且流水条数没变（没有生成账单）
        val after = adjusted.initialBalanceCents + (Repository.accountDeltas(txns)[adjusted.id] ?: 0L)
        assertEquals(target, after)
        assertEquals(1, txns.size)
    }

    // ---------------- 明细排序（N36） ----------------

    private fun sortTxn(
        id: Long,
        cents: Long,
        date: LocalDate,
        type: TxnType = TxnType.EXPENSE,
        refundCents: Long = 0L
    ) = Txn(
        id = id,
        ledgerId = 1L,
        accountId = 1L,
        type = type,
        amountCents = cents,
        dateMillis = DateFmt.toMillis(date),
        refundCents = refundCents
    )

    @Test
    fun txn_sort_time_desc_and_asc_are_mirrors() {
        val list = listOf(
            sortTxn(1L, 1_000L, LocalDate.of(2026, 9, 20)),
            sortTxn(2L, 1_000L, LocalDate.of(2026, 9, 25)),
            sortTxn(3L, 1_000L, LocalDate.of(2026, 9, 21))
        )
        // 默认（时间最新优先）：25 号 → 21 号 → 20 号
        assertEquals(listOf(2L, 3L, 1L), sortTxns(list, TxnSort.TIME_DESC).map { it.id })
        // 时间最早优先：顺序完全相反
        assertEquals(listOf(1L, 3L, 2L), sortTxns(list, TxnSort.TIME_ASC).map { it.id })
        // 时间排序要保留按天分组，金额排序才平铺
        assertTrue(!TxnSort.TIME_DESC.flat && !TxnSort.TIME_ASC.flat)
        assertTrue(TxnSort.AMOUNT_DESC.flat && TxnSort.AMOUNT_ASC.flat)
    }

    @Test
    fun txn_sort_amount_uses_effective_cents_absolute_value() {
        // 300 元原价退了 100 → 实计 200 元；收入 500 元按 500 参与比较；支出 150 元按 150
        val refunded = sortTxn(1L, 30_000L, LocalDate.of(2026, 9, 20), refundCents = 10_000L)
        val income = sortTxn(2L, 50_000L, LocalDate.of(2026, 9, 18), type = TxnType.INCOME)
        val small = sortTxn(3L, 15_000L, LocalDate.of(2026, 9, 25))
        val list = listOf(refunded, income, small)
        // 从高到低：50000（收入）> 20000（实计）> 15000；从低到高反之
        assertEquals(listOf(2L, 1L, 3L), sortTxns(list, TxnSort.AMOUNT_DESC).map { it.id })
        assertEquals(listOf(3L, 1L, 2L), sortTxns(list, TxnSort.AMOUNT_ASC).map { it.id })
        // 只重排传入的这一份列表，原列表顺序不变（排序只作用在当前筛选结果上）
        assertEquals(listOf(1L, 2L, 3L), list.map { it.id })
    }

    // ---------------- 标签（N38） ----------------

    @Test
    fun tag_name_is_trimmed_and_blank_becomes_empty() {
        assertEquals("出差", Repository.normalizedTagName("  出差 "))
        assertEquals("", Repository.normalizedTagName("   "))
        // 只去首尾空白，中间的空格保留（用于「出差 报销」这类多词标签）
        assertEquals("出差 报销", Repository.normalizedTagName(" 出差 报销 "))
    }

    @Test
    fun tag_trail_shows_at_most_three_names_then_count() {
        assertEquals("", Repository.tagTrail(emptyList()))
        assertEquals("#咖啡", Repository.tagTrail(listOf("咖啡")))
        assertEquals("#a #b #c", Repository.tagTrail(listOf("a", "b", "c")))
        // 超过 3 个：前 3 个 + 「等 N 个」
        assertEquals("#a #b #c 等 4 个", Repository.tagTrail(listOf("a", "b", "c", "d")))
    }

    // ---------------- Excel 导出（N58） ----------------

    /** 读 zip 里某个部件的内容 */
    private fun zipEntry(bytes: ByteArray, name: String): String {
        val out = HashMap<String, String>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                out[e.name] = zip.readBytes().toString(Charsets.UTF_8)
                e = zip.nextEntry
            }
        }
        return out[name] ?: error("zip 里没有 $name，实际有：${out.keys}")
    }

    @Test
    fun xlsx_zip_contains_all_required_parts() {
        val bytes = XlsxWriter.write(
            XlsxWriter.Sheet(
                name = "账单",
                columns = listOf(XlsxWriter.Column("日期", 12f), XlsxWriter.Column("金额", 10f)),
                rows = listOf(
                    listOf(XlsxWriter.Cell.Text("2026-10-01"), XlsxWriter.Cell.Num(38.0))
                )
            )
        )
        // 6 个部件齐全（OOXML 最小集）
        listOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/styles.xml",
            "xl/worksheets/sheet1.xml"
        ).forEach { part -> zipEntry(bytes, part) }
    }

    @Test
    fun xlsx_writes_text_as_inline_str_and_number_as_numeric_cell() {
        val bytes = XlsxWriter.write(
            XlsxWriter.Sheet(
                name = "账单",
                columns = listOf(
                    XlsxWriter.Column("备注", 20f),
                    XlsxWriter.Column("金额", 10f)
                ),
                rows = listOf(
                    listOf(XlsxWriter.Cell.Text("a & b <c> \"d\""), XlsxWriter.Cell.Num(1234.5))
                )
            )
        )
        val sheet = zipEntry(bytes, "xl/worksheets/sheet1.xml")
        // 文本：inlineStr + XML 转义
        assertTrue(sheet.contains("t=\"inlineStr\""))
        assertTrue(sheet.contains("a &amp; b &lt;c&gt; &quot;d&quot;"))
        // 数字：数字单元格，两位小数
        assertTrue(sheet.contains("<v>1234.50</v>"))
        // 首行冻结 + 表头样式
        assertTrue(sheet.contains("state=\"frozen\""))
        assertTrue(sheet.contains("""<c r="A1" s="1" t="inlineStr">"""))
    }

    @Test
    fun xlsx_column_names_go_past_z() {
        // 27 列：最后一个应是 AA
        val columns = (0 until 27).map { XlsxWriter.Column("c$it", 8f) }
        val bytes = XlsxWriter.write(
            XlsxWriter.Sheet("账单", columns, emptyList())
        )
        val sheet = zipEntry(bytes, "xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("""<c r="AA1" s="1" t="inlineStr">"""))
    }

    /** 生成一个示例 xlsx 到 build 目录，便于人工用 Excel / WPS 打开验证 */
    @Test
    fun xlsx_dump_sample_for_manual_check() {
        val bytes = XlsxWriter.write(
            XlsxWriter.Sheet(
                name = "账单",
                columns = listOf(
                    XlsxWriter.Column("日期", 12f),
                    XlsxWriter.Column("时间", 8f),
                    XlsxWriter.Column("类型", 8f),
                    XlsxWriter.Column("大类", 14f),
                    XlsxWriter.Column("二级分类", 14f),
                    XlsxWriter.Column("账户", 16f),
                    XlsxWriter.Column("金额", 10f),
                    XlsxWriter.Column("备注", 24f)
                ),
                rows = listOf(
                    listOf(
                        XlsxWriter.Cell.Text("2026-10-01"), XlsxWriter.Cell.Text("12:30"),
                        XlsxWriter.Cell.Text("支出"), XlsxWriter.Cell.Text("餐饮美食"),
                        XlsxWriter.Cell.Text("午餐"), XlsxWriter.Cell.Text("支付宝"),
                        XlsxWriter.Cell.Num(38.0), XlsxWriter.Cell.Text("公司楼下 & 咖啡")
                    ),
                    listOf(
                        XlsxWriter.Cell.Text("2026-10-05"), XlsxWriter.Cell.Text("09:00"),
                        XlsxWriter.Cell.Text("收入"), XlsxWriter.Cell.Text("工资薪资"),
                        XlsxWriter.Cell.Text("月薪"), XlsxWriter.Cell.Text("储蓄卡"),
                        XlsxWriter.Cell.Num(12000.0), XlsxWriter.Cell.Text("")
                    )
                )
            )
        )
        val out = java.io.File("build/xlsx-sample.xlsx")
        out.writeBytes(bytes)
        assertTrue(out.exists() && out.length() > 1000)
    }
}
