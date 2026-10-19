package com.qingjizhang.app

import android.app.Application
import com.qingjizhang.app.data.AccountLedgerSync
import com.qingjizhang.app.data.AppDatabase
import com.qingjizhang.app.data.DbGuard
import com.qingjizhang.app.data.RecurringEngine
import com.qingjizhang.app.data.Seed
import com.qingjizhang.app.util.BootLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：纯本地记账，不联网、无账号、无网络权限。
 *
 * 启动时做三件事（顺序不变，全部在后台线程）：
 * 1. 打开数据库后先做一次完整性自检（N25）：发现问题把现场留档到「下载/Lumo日志」，
 *    但**不阻塞、不崩溃**，照常继续启动
 * 2. 首次运行写入默认账本 / 分类 / 账户
 * 3. 周期记账补齐（上次打开到现在所有该生成的账单）
 */
class BookkeepingApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        BootLog.init(this)
        BootLog.installCrashHandler(this)
        BootLog.log(this, "Application.onCreate 开始")
        super.onCreate()
        BootLog.log(this, "Application.onCreate 完成（super 已调用）")
        appScope.launch {
            runCatching {
                BootLog.log(this@BookkeepingApp, "开始打开数据库")
                val db = AppDatabase.get(this@BookkeepingApp)
                BootLog.log(this@BookkeepingApp, "数据库已打开")
                // N25：启动自检（异步、不拖慢首帧；自检失败也只留档 + 打点，继续跑）
                val check = DbGuard.checkOnStartup(this@BookkeepingApp, db)
                BootLog.log(this@BookkeepingApp, "数据库自检：" + check.summary)
                // P2-2：把 account.ledgerIdsCsv 幂等同步进 account_ledgers 关系表
                // （老库升级 / 从旧备份恢复后，关系表都能在这里补齐）
                AccountLedgerSync.sync(db.openHelper.writableDatabase)
                Seed.initIfEmpty(db)
                BootLog.log(this@BookkeepingApp, "预设数据已确保")
                RecurringEngine.catchUp(db)
                BootLog.log(this@BookkeepingApp, "周期记账补齐完成")
            }.onFailure {
                BootLog.log(this@BookkeepingApp, "后台初始化失败：" + it.javaClass.name + " / " + it.message)
                // 连数据库都打不开（文件损坏等）：同样把现场留档，App 不静默崩溃
                runCatching { DbGuard.dumpFiles(this@BookkeepingApp, "数据库异常") }
            }
        }
    }
}
