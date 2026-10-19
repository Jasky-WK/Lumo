package com.qingjizhang.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.qingjizhang.app.data.AppDatabase
import com.qingjizhang.app.ui.AppRoot
import com.qingjizhang.app.ui.theme.BookkeepingTheme
import com.qingjizhang.app.util.BootLog
import com.qingjizhang.app.vm.AppViewModel

class MainActivity : ComponentActivity() {

    /** 从通知 / 桌面小组件进来的动作，交给 AppRoot 处理 */
    private val pendingAction = mutableStateOf<String?>(null)

    /** 桌面「模板记账」小组件带进来的分类 id（N34）：仅 action = quick_add 时有意义 */
    private val pendingCategoryId = mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        BootLog.log(this, "MainActivity.onCreate 开始")
        // Android 12+ 的系统启动屏：品牌色 + 居中图标
        val splash = installSplashScreen()
        // S1：库没就绪（升级首启要跑完整迁移链）就继续展示启动屏——用户看到完整品牌屏，
        // 而不是卡在半屏；最多等 3 秒，防止库打不开时把用户关在启动屏里
        val splashStart = android.os.SystemClock.elapsedRealtime()
        splash.setKeepOnScreenCondition {
            !AppDatabase.isReady &&
                android.os.SystemClock.elapsedRealtime() - splashStart < 3_000L
        }
        super.onCreate(savedInstanceState)
        BootLog.log(this, "installSplashScreen + super.onCreate 完成")
        enableEdgeToEdge()
        BootLog.log(this, "enableEdgeToEdge 完成")
        pendingAction.value = extractAction(intent)
        pendingCategoryId.value = extractCategoryId(intent)
        BootLog.log(this, "intent 动作：" + (pendingAction.value ?: "无"))
        setContent {
            BootLog.log(this, "setContent 开始组合")
            val vm: AppViewModel = viewModel()
            BootLog.log(this, "AppViewModel 创建完成")
            val themeMode by vm.themeMode.collectAsState()
            val themeColor by vm.themeColor.collectAsState()
            BookkeepingTheme(themeMode = themeMode, themeColor = themeColor) {
                BootLog.log(this, "主题已应用，准备组合 AppRoot")
                AppRoot(
                    vm = vm,
                    action = pendingAction.value,
                    actionCategoryId = pendingCategoryId.value,
                    onActionHandled = {
                        pendingAction.value = null
                        pendingCategoryId.value = 0L
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAction.value = extractAction(intent)
        pendingCategoryId.value = extractCategoryId(intent)
    }

    /**
     * 桌面小组件 / 通知 / 快捷方式进来的动作：
     * - `quick_add` = 「记一笔」小组件或桌面快捷方式（N42）；模板记账小组件还会带 `extra_category_id`（见 extractCategoryId）
     * - `open=recurring` = 周期提醒通知
     * - `open=budget` = 剩余预算小组件（点「未设置预算」时进设置页配预算）
     *
     * 注意：快捷方式（shortcuts.xml）里的 `<extra>` 只能写成字符串，
     * 所以这里除了布尔 extra，也接受字符串 "true"。
     */
    private fun extractAction(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.getBooleanExtra("quick_add", false) ||
            intent.getBooleanExtra("extra_open_add_entry", false) ||
            intent.getStringExtra("quick_add") == "true" ||
            intent.getStringExtra("extra_open_add_entry") == "true"
        ) {
            intent.removeExtra("quick_add")
            intent.removeExtra("extra_open_add_entry")
            return "quick_add"
        }
        when (intent.getStringExtra("open")) {
            "recurring" -> {
                intent.removeExtra("open")
                return "recurring"
            }
            "budget" -> {
                intent.removeExtra("open")
                return "budget"
            }
            // v2.9.0：自动备份的「旧备份提醒」通知
            "backup" -> {
                intent.removeExtra("open")
                return "backup"
            }
        }
        // N42：桌面快捷方式「本月明细」带的是 extra_open（字符串），直达本月账单明细页
        when (intent.getStringExtra("extra_open")) {
            "month_txns" -> {
                intent.removeExtra("extra_open")
                return "month_txns"
            }
        }
        return null
    }

    /** 模板记账小组件带来的分类 id（N34）；没有就是 0 */
    private fun extractCategoryId(intent: Intent?): Long {
        if (intent == null) return 0L
        val id = intent.getLongExtra("extra_category_id", 0L)
        intent.removeExtra("extra_category_id")
        return id
    }
}
