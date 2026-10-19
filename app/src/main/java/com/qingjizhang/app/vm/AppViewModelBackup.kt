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
 * 备份恢复 / CSV 导入导出命令（P2-1 拆出）
 */

    // ---------------- 备份 / 恢复 ----------------

internal suspend fun AppViewModel.refreshBackups() {
        val list = withContext(Dispatchers.IO) {
            BackupManager.list(app).map {
                BackupUi(it.name, it.sizeText, it.timeText, it.file)
            }
        }
        _backups.value = list
        _lastBackupText.value = list.firstOrNull()?.timeText ?: "尚未备份"
    }

fun AppViewModel.backupNow() {
        scope.launch {
            _busy.value = true
            runCatching {
                val f = BackupManager.createBackup(app)
                refreshBackups()
                _snack.emit("备份完成：${f.name}")
            }.onFailure { _snack.emit("备份失败：${it.message}") }
            _busy.value = false
        }
    }

fun AppViewModel.restoreFrom(file: File) {
        scope.launch {
            _busy.value = true
            runCatching {
                BackupManager.restore(app, file)
                reloadAfterRestore()
                _snack.emit("已从备份恢复，恢复前的数据已存为快照")
            }.onFailure { _snack.emit("恢复失败：${it.message}") }
            _busy.value = false
        }
    }

fun AppViewModel.restoreFromUri(uri: Uri) {
        scope.launch {
            _busy.value = true
            runCatching {
                BackupManager.restoreFromUri(app, uri)
                reloadAfterRestore()
                _snack.emit("已从文件恢复，恢复前的数据已存为快照")
            }.onFailure { _snack.emit("恢复失败：${it.message}") }
            _busy.value = false
        }
    }

fun AppViewModel.deleteBackup(file: File) {
        scope.launch {
            runCatching {
                BackupManager.delete(file)
                refreshBackups()
                _snack.emit("已删除备份文件")
            }.onFailure { _snack.emit("删除失败：${it.message}") }
        }
    }

    // ---------------- 每月自动备份（v2.9.0） ----------------

    /** 刷新公开目录里的自动备份列表 */
    internal suspend fun AppViewModel.refreshAutoBackups() {
        val list = withContext(Dispatchers.IO) { AutoBackup.list(app) }
        _autoBackups.value = list
    }

    /**
     * 启动时检查：开关打开 + 距上次 ≥30 天 + 当前账本有账单 → 静默生成一份自动备份。
     *
     * 设计取舍：
     * - 不引入 WorkManager / AlarmManager 后台轮询：记账 App 用户一个月内必然会打开，
     *   启动时检查最可靠，也不受国产 ROM 杀后台影响；
     * - 失败静默（后台任务不打扰用户）；成功也不弹窗，只在备份页可见；
     * - 生成前若已有 ≥3 份，先发通知提醒删最旧的（不自动删）。
     */
    internal suspend fun AppViewModel.maybeAutoBackup() {
        if (!prefs.getBoolean("auto_backup_on", true)) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_auto_backup_at", 0L)
        val month = 30L * 24 * 60 * 60 * 1000
        if (last > 0L && now - last < month) return
        // 一笔账都没有的库不产生备份（没意义，还可能把空库覆盖成「备份」）
        val ledgerId = _currentLedgerId.value
        val count = withContext(Dispatchers.IO) {
            runCatching { AppDatabase.get(app).txnDao().countLive(ledgerId) }.getOrDefault(0)
        }
        if (count <= 0) return
        runCatching {
            val existing = withContext(Dispatchers.IO) { AutoBackup.list(app) }
            if (existing.size >= AutoBackup.KEEP_LIMIT) {
                AutoBackupReminder.notifyOldBackup(app, existing.size, existing.last().name)
            }
            val json = BackupManager.fullBackupJson(app)
            val ok = AutoBackup.save(app, AutoBackup.fileName(), json.toByteArray(Charsets.UTF_8))
            if (ok) {
                prefs.edit().putLong("last_auto_backup_at", now).apply()
                _autoBackups.value = withContext(Dispatchers.IO) { AutoBackup.list(app) }
            }
        }
    }

    /** 手动触发一次自动备份（备份页「立即备份」按钮用） */
    fun AppViewModel.autoBackupNow() {
        scope.launch {
            val json = runCatching { BackupManager.fullBackupJson(app) }.getOrNull()
            if (json == null) {
                _snack.emit("备份失败，无法读取数据库")
                return@launch
            }
            val existing = withContext(Dispatchers.IO) { AutoBackup.list(app) }
            if (existing.size >= AutoBackup.KEEP_LIMIT) {
                AutoBackupReminder.notifyOldBackup(app, existing.size, existing.last().name)
            }
            val ok = AutoBackup.save(app, AutoBackup.fileName(), json.toByteArray(Charsets.UTF_8))
            if (ok) {
                prefs.edit().putLong("last_auto_backup_at", System.currentTimeMillis()).apply()
                refreshAutoBackups()
                _snack.emit("自动备份已保存到 Documents/Lumo/自动备份")
            } else {
                _snack.emit("自动备份写入失败，请检查存储空间")
            }
        }
    }

    /** 删除一份自动备份；返回是否成功（界面删除后要重新读列表，所以做成 suspend） */
    internal suspend fun AppViewModel.deleteAutoBackup(entry: AutoBackup.Entry): Boolean {
        val ok = AutoBackup.delete(app, entry)
        refreshAutoBackups()
        _snack.emit(if (ok) "已删除 ${entry.name}" else "删除失败")
        return ok
    }

    /** 自动备份的恢复：先复制到缓存再交给现有的「预览 → 校验 → 覆盖导入」流程 */
    suspend fun AppViewModel.prepareAutoBackupRestore(entry: AutoBackup.Entry): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = AutoBackup.readText(app, entry)
                val tmp = File(app.cacheDir, "auto-restore-" + entry.name)
                tmp.writeText(text, Charsets.UTF_8)
                tmp
            }.getOrNull()
        }

internal suspend fun AppViewModel.reloadAfterRestore() {
        // 换一个数据库实例，所有 Flow 会重新订阅
        AppDatabase.closeInstance()
        val db = withContext(Dispatchers.IO) { AppDatabase.get(app) }
        dbFlow.value = db
        val first = db.ledgerDao().all().firstOrNull()
        _currentLedgerId.value = first?.id ?: 0L
        // P2-2：旧备份里没有 account_ledgers 行，恢复后按 CSV 重建一次，账户立刻可见
        AccountLedgerSync.sync(db.openHelper.writableDatabase)
        refreshBackups()
    }

    // ---------------- CSV ----------------

fun AppViewModel.exportCsv() {
        scope.launch {
            _busy.value = true
            runCatching {
                val list = txns.value
                if (list.isEmpty()) {
                    _snack.emit("当前账本还没有账单")
                    return@runCatching
                }
                val accs = accounts.value
                val cats = categories.value
                val content = CsvManager.buildCsv(
                    txns = list,
                    accountName = { id -> accs.firstOrNull { it.id == id }?.name ?: "未知账户" },
                    categoryName = { id -> cats.firstOrNull { it.id == id }?.name ?: "" },
                    parentName = { id ->
                        val c = cats.firstOrNull { it.id == id }
                        if (c == null) "" else {
                            if (c.parentId == 0L) c.name
                            else cats.firstOrNull { it.id == c.parentId }?.name ?: c.name
                        }
                    }
                )
                val name = "Lumo账单-" + DateFmt.isoDate(System.currentTimeMillis()) + ".csv"
                val path = CsvManager.saveToDownloads(app, name, content)
                _snack.emit("已导出到 $path")
            }.onFailure { _snack.emit("导出失败：${it.message}") }
            _busy.value = false
        }
    }

fun AppViewModel.downloadTemplate() {
        scope.launch {
            runCatching {
                val path = CsvManager.saveToDownloads(app, "Lumo导入模板.csv", CsvManager.buildTemplate())
                _snack.emit("模板已保存到 $path")
            }.onFailure { _snack.emit("保存失败：${it.message}") }
        }
    }

    // ---------------- Excel 导出（N58） ----------------

    /** 导出账单为 .xlsx（列与 CSV 一致，金额是数字单元格，Excel 可直接求和） */
fun AppViewModel.exportExcel() {
        scope.launch {
            _busy.value = true
            runCatching {
                val list = txns.value
                if (list.isEmpty()) {
                    _snack.emit("当前账本还没有账单")
                    return@runCatching
                }
                val accs = accounts.value
                val cats = categories.value
                val bytes = XlsxExporter.build(
                    txns = list,
                    accountName = { id -> accs.firstOrNull { it.id == id }?.name ?: "未知账户" },
                    categoryName = { id -> cats.firstOrNull { it.id == id }?.name ?: "" },
                    parentName = { id ->
                        val c = cats.firstOrNull { it.id == id }
                        if (c == null) "" else {
                            if (c.parentId == 0L) c.name
                            else cats.firstOrNull { it.id == c.parentId }?.name ?: c.name
                        }
                    }
                )
                val name = "Lumo账单-" + DateFmt.isoDate(System.currentTimeMillis()) + ".xlsx"
                val path = CsvManager.saveToDownloads(app, name, bytes, XlsxExporter.MIME)
                _snack.emit("已导出到 $path")
            }.onFailure { _snack.emit("导出失败：${it.message}") }
            _busy.value = false
        }
    }

    /** 选完文件先生成草稿，页面上给用户看一遍再决定怎么导 */
fun AppViewModel.beginCsvImport(uri: Uri) {
        scope.launch {
            _busy.value = true
            runCatching {
                val text = CsvManager.readText(app, uri)
                // 分隔符与编码都做兼容：Excel / WPS 可能存成 GBK、也可能用分号或 Tab 分隔
                val delimiter = CsvManager.detectDelimiter(text)
                val all = CsvManager.parseCsv(text, delimiter)
                if (all.isEmpty()) throw IllegalArgumentException("文件是空的，没有读到任何内容")
                val first = all.first().map { it.trim() }
                val hasHeader = CsvManager.looksLikeHeader(first)
                val headers = if (hasHeader) first else first.indices.map { "第 ${it + 1} 列" }
                val rows = if (hasHeader) all.drop(1) else all
                if (rows.isEmpty()) throw IllegalArgumentException("文件里只有表头，没有数据行")
                val mapping = CsvManager.autoMap(headers, hasHeader)
                _csvDraft.value = CsvManager.Draft(
                    fileName = CsvManager.displayName(app, uri),
                    headers = headers,
                    rows = rows,
                    hasHeader = hasHeader,
                    delimiter = delimiter,
                    mapping = mapping,
                    excluded = emptySet(),
                    preview = CsvManager.previewRows(rows, mapping, hasHeader)
                )
            }.onFailure { _snack.emit("读取失败：${it.message}") }
            _busy.value = false
        }
    }

fun AppViewModel.cancelCsvImport() {
        _csvDraft.value = null
    }

    /** 手动指定某一列是什么（col = -1 表示不导入这一列） */
fun AppViewModel.setCsvField(field: CsvManager.Field, col: Int) {
        val d = _csvDraft.value ?: return
        val mapping = d.mapping.toMutableMap().apply { put(field, col) }
        _csvDraft.value = d.copy(
            mapping = mapping,
            preview = CsvManager.previewRows(d.rows, mapping, d.hasHeader)
        )
    }

    /** 逐行勾选：这一行导不导 */
fun AppViewModel.toggleCsvRow(index: Int) {
        val d = _csvDraft.value ?: return
        val next = d.excluded.toMutableSet()
        if (!next.remove(index)) next.add(index)
        _csvDraft.value = d.copy(excluded = next)
    }

    /** 一键跳过所有解析不了的行 */
fun AppViewModel.excludeCsvProblemRows() {
        val d = _csvDraft.value ?: return
        val bad = d.preview.indices.filter { !d.preview[it].ok }.toSet()
        _csvDraft.value = d.copy(excluded = d.excluded + bad)
    }

    /** 全部恢复导入 */
fun AppViewModel.includeAllCsvRows() {
        val d = _csvDraft.value ?: return
        _csvDraft.value = d.copy(excluded = emptySet())
    }

    /** 确认导入：按当前映射与勾选状态落库 */
fun AppViewModel.confirmCsvImport() {
        val draft = _csvDraft.value ?: return
        scope.launch {
            _busy.value = true
            runCatching {
                val lid = _currentLedgerId.value
                val mapping = draft.mapping
                var ok = 0
                var bad = 0
                val errors = mutableListOf<String>()

                draft.rows.forEachIndexed { rowIndex, r ->
                    if (rowIndex in draft.excluded) return@forEachIndexed
                    val lineNo = rowIndex + if (draft.hasHeader) 2 else 1
                    fun cell(f: CsvManager.Field) = CsvManager.cell(r, mapping, f)
                    try {
                        val amountText = cell(CsvManager.Field.AMOUNT)
                        val amount = CsvManager.parseAmount(amountText)
                            ?: throw IllegalArgumentException("金额「$amountText」读不出来")
                        val cents = amount.first
                        if (cents <= 0) throw IllegalArgumentException("金额必须大于 0")
                        val date = CsvManager.parseDate(cell(CsvManager.Field.DATE))
                            ?: throw IllegalArgumentException("日期「${cell(CsvManager.Field.DATE)}」读不出来（建议写成 2026-09-21）")
                        val time = CsvManager.parseTime(cell(CsvManager.Field.TIME)) ?: LocalTime.of(0, 0)
                        val millis = DateFmt.toMillis(LocalDateTime.of(date, time))

                        val rawAcc = cell(CsvManager.Field.ACCOUNT)
                        val fromName = rawAcc.substringBefore("→").trim()
                        val toNamePart = if (rawAcc.contains("→")) rawAcc.substringAfter("→").trim() else ""
                        var accountId = ensureAccount(lid, fromName)

                        val typeText = cell(CsvManager.Field.TYPE)
                        val type = when (typeText) {
                            "支出", "expense", "Expense" -> TxnType.EXPENSE
                            "收入", "income", "Income" -> TxnType.INCOME
                            "互转", "转账", "transfer" -> TxnType.TRANSFER
                            "借贷", "借出", "借入", "lend" -> TxnType.LEND_BORROW
                            // 类型空着时按金额正负猜一个，避免整行白丢
                            "" -> if (amount.second) TxnType.EXPENSE else TxnType.INCOME
                            else -> throw IllegalArgumentException("类型「$typeText」不认识（填 支出 / 收入 / 互转 / 借出 / 借入）")
                        }
                        val direction = when (typeText) {
                            "借出" -> Direction.LEND
                            "借入" -> Direction.BORROW
                            else -> Direction.NONE
                        }
                        var categoryId = 0L
                        if (type == TxnType.EXPENSE || type == TxnType.INCOME) {
                            categoryId = ensureCategory(
                                lid,
                                if (type == TxnType.EXPENSE) CategoryType.EXPENSE else CategoryType.INCOME,
                                cell(CsvManager.Field.CATEGORY),
                                cell(CsvManager.Field.SUB)
                            )
                        }
                        var toAccountId = 0L
                        if (type == TxnType.TRANSFER) {
                            if (toNamePart.isNotEmpty()) toAccountId = ensureAccount(lid, toNamePart)
                        }
                        if (type == TxnType.LEND_BORROW) {
                            // 借贷一定落在「资金账户 ↔ 借贷账户」两端：
                            // 账户列写了「A → B」就按方向还原，只写一个账户时补上账本里的借贷账户
                            if (toNamePart.isNotEmpty()) {
                                val second = ensureAccount(lid, toNamePart)
                                if (direction == Direction.BORROW) {
                                    toAccountId = accountId
                                    accountId = second
                                } else {
                                    toAccountId = second
                                }
                            } else {
                                toAccountId = repo().accounts(lid).first()
                                    .firstOrNull { it.type == AccountType.LEND }?.id
                                    ?: ensureAccount(lid, "借贷账户")
                            }
                        }
                        val note = cell(CsvManager.Field.NOTE)
                        repo().addTxn(
                            Txn(
                                ledgerId = lid,
                                accountId = accountId,
                                transferToAccountId = toAccountId,
                                categoryId = categoryId,
                                type = type,
                                direction = direction,
                                amountCents = cents,
                                counterparty = if (type == TxnType.LEND_BORROW) note else "",
                                note = note,
                                dateMillis = millis
                            )
                        )
                        ok++
                    } catch (e: Exception) {
                        bad++
                        if (errors.size < 3) errors += "第 $lineNo 行：${e.message ?: "格式不对"}"
                    }
                }
                _importReport.value = ImportReport(ok, bad, errors)
                _snack.emit(
                    if (bad == 0) "导入完成：成功 $ok 笔"
                    else "导入完成：成功 $ok 笔，跳过 $bad 笔"
                )
                _csvDraft.value = null
            }.onFailure { _snack.emit("导入失败：${it.message}") }
            _busy.value = false
        }
    }

internal suspend fun AppViewModel.ensureAccount(ledgerId: Long, name: String): Long {
        val accs = repo().accounts(ledgerId).first()
        accs.firstOrNull { it.name == name }?.let { return it.id }
        return repo().addAccount(
            Account(
                ledgerId = ledgerId,
                name = name.ifBlank { "导入账户" },
                type = AccountType.OTHER,
                sortOrder = accs.size
            )
        )
    }

internal suspend fun AppViewModel.ensureCategory(
        ledgerId: Long,
        type: CategoryType,
        parentName: String,
        childName: String
    ): Long {
        val pName = parentName.ifBlank { "其他" }
        var cats = repo().categories(ledgerId).first()
        var parent = cats.firstOrNull { it.type == type && it.parentId == 0L && it.name == pName }
        if (parent == null) {
            val newId = repo().addCategory(
                Category(
                    ledgerId = ledgerId,
                    name = pName,
                    type = type,
                    isPreset = false,
                    sortOrder = cats.count { it.type == type && it.parentId == 0L },
                    parentId = 0L
                )
            )
            parent = Category(id = newId, ledgerId = ledgerId, name = pName, type = type, parentId = 0L)
        }
        val parentId = parent.id
        if (childName.isBlank()) return parentId
        cats = repo().categories(ledgerId).first()
        val child = cats.firstOrNull { it.type == type && it.parentId == parentId && it.name == childName }
        if (child != null) return child.id
        return repo().addCategory(
            Category(
                ledgerId = ledgerId,
                name = childName,
                type = type,
                isPreset = false,
                sortOrder = cats.count { it.parentId == parentId },
                parentId = parentId
            )
        )
    }
