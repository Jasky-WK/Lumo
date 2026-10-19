package com.qingjizhang.app.ui

import androidx.compose.foundation.layout.offset
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.foundation.Canvas
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.data.QuickEntry
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.util.KeySound
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.createDemoLedger
import com.qingjizhang.app.vm.dismissImportReport
import com.qingjizhang.app.vm.openTxnList
import com.qingjizhang.app.vm.purgeExpiredTrash
import com.qingjizhang.app.vm.reorderAccounts
import com.qingjizhang.app.vm.switchLedger
import com.qingjizhang.app.vm.toast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val ACCOUNTS = "accounts"
    const val OVERVIEW = "overview"
    const val SETTINGS = "settings"
    const val CATEGORIES = "categories"
    const val BACKUP = "backup"
    const val RECURRING = "recurring"
    const val LEDGERS = "ledgers"
    const val RECURRING_EDIT = "recurring_edit"
    const val DETAIL = "detail"
    const val SEARCH = "search"
    const val TXN_LIST = "txn_list"
    const val CALENDAR = "calendar"
    const val RECYCLE = "recycle"
    const val CSV_IMPORT = "csv_import"
    const val REPORTS = "reports"
    const val HELP = "help"
    const val ACCOUNT_MONTHLY = "account_monthly"
    const val ACCOUNT_EDIT = "account_edit"
    const val ACCOUNT_SORT = "account_sort"
    const val AR_AP = "ar_ap"
}

private val mainRoutes = setOf(Routes.HOME, Routes.ACCOUNTS, Routes.OVERVIEW)

@Composable
fun AppRoot(
    vm: AppViewModel,
    /** 从通知 / 桌面小组件进来的动作：quick_add（记一笔）、recurring（周期记账）、budget（预算设置） */
    action: String? = null,
    /** 模板记账小组件带进来的分类 id（N34）：action = quick_add 时用它预选分类 */
    actionCategoryId: Long = 0L,
    onActionHandled: () -> Unit = {}
) {

    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showChrome = route in mainRoutes

    val context = LocalContext.current

    // N39：记一笔的「＋」按钮也带按键音，与记一笔页内的按键共用同一个开关
    val keySoundOn by vm.keySound.collectAsState()
    val keyDing = rememberKeySound(keySoundOn)

    // 首页（或导航栈已空）再按返回：走系统默认行为退出 App，避免把 home 也弹出导致白屏
    BackHandler(enabled = route == null || route == Routes.HOME) {
        (context as? Activity)?.finish()
    }

    /** 二级页统一返回：栈里还有就出栈；万一栈空了也稳定回到首页，不出现白屏 */
    fun safeBack() {
        if (!nav.popBackStack()) {
            nav.navigate(Routes.HOME) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // P1 液态玻璃：底栏的模糊取样状态（haze）
    val hazeState = remember { HazeState() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // P1 液态玻璃：「减弱视觉效果」开关由设置页控制，注入后全 App 的玻璃面统一切换为不透明卡片
    val reduceEffects by vm.reduceEffects.collectAsState()

    // v2.9.0：全新安装的首启三屏引导（老用户 prefs 非空，直接跳过）
    val onboarded by vm.onboarded.collectAsState()
    if (!onboarded) {
        OnboardingScreen(
            onDone = { vm.finishOnboarding() },
            onTryDemo = {
                vm.createDemoLedger()
                vm.finishOnboarding()
            }
        )
        return
    }

    CompositionLocalProvider(LocalReduceEffects provides reduceEffects) {

    LaunchedEffect(Unit) {
        vm.snack.collect { snackbarHostState.showSnackbar(it) }
    }

    // 打开 App 时顺手清理回收站里超过 30 天的记录
    LaunchedEffect(Unit) { vm.purgeExpiredTrash() }

    // 首帧之后才启动后台监听（构造期启动会读到还没赋值的 StateFlow）
    LaunchedEffect(Unit) { vm.startBackgroundObservers() }

    // 自检版：确认首帧组合成功（写到「下载/Lumo日志」）
    LaunchedEffect(Unit) { com.qingjizhang.app.util.BootLog.log(context, "AppRoot 首帧组合成功") }

    var addSheet by remember { mutableStateOf<AddSheetRequest?>(null) }
    var lastType by remember { mutableStateOf(com.qingjizhang.app.data.TxnType.EXPENSE) }
    // F56：首页账单多选时收起悬浮加号，否则会盖住底部操作栏的「完成」
    var homeMultiActive by remember { mutableStateOf(false) }
    // v2.8.0：一句话记账（Beta，默认关，需在「设置 → 高级功能」打开）
    val advQuickEntry by vm.advQuickEntry.collectAsState()
    val advArApOn by vm.advArAp.collectAsState()
    var showQuickEntry by remember { mutableStateOf(false) }

    // 桌面小组件「记一笔」「模板记账」「剩余预算」/ 周期提醒通知 / 桌面快捷方式点进来时，直接落到对应界面。
    //
    // F22：这里必须先等 NavController 挂上导航图再跳转。冷启动（例如从桌面「本月明细」快捷方式
    // 直接进 App）的头几帧只显示启动画面、上面的 `return` 让 NavHost 还没参与组合，
    // 此时直接 navigate 会抛 IllegalArgumentException「Navigation graph has not been set for NavController」。
    // 等到有回退栈条目（= NavHost 已组合并挂图）时再执行，动作本身不会丢。
    LaunchedEffect(action, actionCategoryId) {
        val pending = action ?: return@LaunchedEffect
        if (nav.currentDestination == null) {
            nav.currentBackStackEntryFlow.first()
        }
        when (pending) {
            "quick_add" -> {
                addSheet = AddSheetRequest(initialCategoryId = actionCategoryId)
                onActionHandled()
            }
            "recurring" -> {
                nav.navigate(Routes.RECURRING) { launchSingleTop = true }
                onActionHandled()
            }
            "budget" -> {
                // 剩余预算小组件点「未设置预算」：进设置页配预算（月度预算与自定义预算都在那儿）
                nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
                onActionHandled()
            }
            // v2.9.0：自动备份提醒通知 → 备份管理页
            "backup" -> {
                nav.navigate(Routes.BACKUP) { launchSingleTop = true }
                onActionHandled()
            }
            // N42：桌面快捷方式「本月明细」→ 本月账单明细页
            "month_txns" -> {
                vm.openTxnList(TxnListSpecs.month(0, "本月 1 号以来"))
                nav.navigate(Routes.TXN_LIST) { launchSingleTop = true }
                onActionHandled()
            }
        }
    }

    // 导入结果：说清楚进来了几笔、哪几行没进来
    val importReport by vm.importReport.collectAsState()

    // 启动屏 / 深色模式是深底，状态栏图标要用白色；浅色主界面用深色图标
    LightStatusBar(light = !IsDarkTheme)


    fun go(routeName: String) {
        scope.launch { drawerState.close() }
        nav.navigate(routeName) { launchSingleTop = true }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                vm = vm,
                onNavigate = { go(it) }
            )
        }
    ) {
        // P1 液态玻璃：底栏改成「悬浮胶囊 + 真实背景模糊」（对齐参考视频）。
        // 关键：底栏不再是 Scaffold 的 bottomBar —— 内容区延伸到屏幕最底部，
        // 列表滚动时内容会从胶囊底下穿过，毛玻璃才有东西可模糊；
        // 末尾留白交给各页面自己的 contentPadding / 底部 Spacer。
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Bg,
            // 全 App 统一的自定义提示条：容器/文字都取主题语义色，深浅两套主题下都不会再出现系统默认的黑底。
            // 浅色下 = 深墨底 + 白字；深色下 = 浅底 + 深墨字，两种都保证对比度，不会「黑底黑字」。
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = RoundedCornerShape(14.dp),
                        containerColor = Ink,
                        contentColor = CardWhite,
                        actionColor = Primary,
                        // v2.5.2：底栏是覆盖层，提示条要抬到它上面，否则被玻璃胶囊挡住
                        modifier = Modifier.padding(
                            bottom = if (showChrome && !homeMultiActive) FLOATING_BAR_HEIGHT else 0.dp
                        )
                    )
                }
            },
            floatingActionButton = {
                if (showChrome && !homeMultiActive) {
                    // 整个「小圆 + 加号」一摞一起上移，避开悬浮底栏。
                    // ⚠️ 偏移必须加在 Column 上：只给加号加偏移的话，加号会向上滑压住
                    // 小圆（v2.8.2 修复，用户截图反馈两者重合）。
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.offset(y = -(FLOATING_BAR_HEIGHT + 6.dp))
                    ) {
                        // v2.8.0 一句话记账入口：半径为 FAB 一半的小圆，图案是一个圆圈，
                        // 位于加号正上方（开关关闭时不显示）
                        if (advQuickEntry) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(CardWhite)
                                    .border(1.dp, Primary.copy(alpha = 0.45f), CircleShape)
                                    .clickable { showQuickEntry = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(Modifier.size(13.dp)) {
                                    drawCircle(
                                        // Canvas 的 DrawScope 不是 Composable，取不到 Primary 这种
                                        // Composable 颜色，用常量品牌色
                                        color = BrandBlue,
                                        radius = size.minDimension / 2f - 1.dp.toPx(),
                                        style = Stroke(width = 1.5.dp.toPx())
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        FloatingActionButton(
                            onClick = {
                                // N39：加号点击沿用「记一笔」音效
                                keyDing(KeySound.Scene.ENTER)
                                addSheet = AddSheetRequest(initialType = lastType)
                            },
                            containerColor = Primary.copy(alpha = 0.94f),
                            contentColor = Color.White,
                            shape = CircleShape,
                            // P1 液态玻璃：FAB 加一圈高光边（「减弱视觉效果」时自动去掉）
                            modifier = Modifier.then(
                                if (glass.decorated) {
                                    Modifier.border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                                } else {
                                    Modifier
                                }
                            )
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "记一笔", modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // 底栏的模糊取样源（haze）：内容会从胶囊底下穿过
                    .hazeSource(hazeState)
            ) {
                NavHost(
                    navController = nav,
                    startDestination = Routes.HOME,
                    // M8：页面切换统一走快速过渡（默认是 700ms 渐隐，切页有明显等待感）
                    enterTransition = { Motion.enter },
                    exitTransition = { Motion.exit },
                    popEnterTransition = { Motion.popEnter },
                    popExitTransition = { Motion.popExit }
                ) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            vm = vm,
                            onMenu = { scope.launch { drawerState.open() } },
                            onAddEntry = { addSheet = AddSheetRequest() },
                            onOpenTxn = { nav.navigate(Routes.DETAIL + "/" + it) },
                            onOpenMonthTxns = {
                                vm.openTxnList(TxnListSpecs.month(0, "本月 1 号以来"))
                                nav.navigate(Routes.TXN_LIST)
                            },
                            onOpenSearch = { nav.navigate(Routes.SEARCH) },
                            onOpenRecurring = { nav.navigate(Routes.RECURRING) },
                            // N43 / 修正 1：右上角记账本图案 → 应收应付
                            onOpenArAp = { nav.navigate(Routes.AR_AP) },
                            // F56：多选时收起悬浮加号
                            onMultiActive = { homeMultiActive = it }
                        )
                    }
                    composable(Routes.AR_AP) {
                        // N43 / 修正 1：记账本图案直接进入应收应付（临时预算规划已下线）
                        ArApScreen(
                            vm = vm,
                            onBack = { safeBack() },
                            // 修正 2：新增应收 / 应付复用「记一笔」页面，只是保存进应收应付表
                            onAddEntry = { dir ->
                                addSheet = AddSheetRequest(initialArAp = dir)
                            },
                            // F53：编辑已有应收 / 应付，同样复用「记一笔」页面
                            onEditEntry = { item ->
                                addSheet = AddSheetRequest(editingArAp = item)
                            }
                        )
                    }
                    composable(Routes.SEARCH) {
                        SearchScreen(
                            vm = vm,
                            onBack = { safeBack() },
                            onOpenTxn = { nav.navigate(Routes.DETAIL + "/" + it) },
                            // N54：搜索命中标签 → 该标签的账单列表
                            onOpenTxnList = { nav.navigate(Routes.TXN_LIST) { launchSingleTop = true } }
                        )
                    }
                    composable(Routes.CALENDAR) {
                        CalendarScreen(
                            vm = vm,
                            onBack = { safeBack() },
                            onOpenDay = { date ->
                                vm.openTxnList(TxnListSpecs.day(date))
                                nav.navigate(Routes.TXN_LIST)
                            }
                        )
                    }
                    composable(Routes.TXN_LIST) {
                        TxnListScreen(
                            vm = vm,
                            onBack = { safeBack() },
                            onOpenTxn = { nav.navigate(Routes.DETAIL + "/" + it) }
                        )
                    }
                    composable(Routes.ACCOUNT_MONTHLY + "/{accountId}") { entry ->
                        val id = entry.arguments?.getString("accountId")?.toLongOrNull() ?: 0L
                        AccountMonthlyScreen(
                            vm = vm,
                            accountId = id,
                            onBack = { safeBack() },
                            onEdit = { nav.navigate(Routes.ACCOUNT_EDIT + "/" + id) },
                            onOpenTxn = { nav.navigate(Routes.DETAIL + "/" + it) }
                        )
                    }
                    composable(Routes.ACCOUNT_EDIT + "/{accountId}") { entry ->
                        val id = entry.arguments?.getString("accountId")?.toLongOrNull() ?: 0L
                        AccountEditScreen(
                            vm = vm,
                            accountId = id,
                            onBack = { safeBack() }
                        )
                    }
                    composable(Routes.ACCOUNT_SORT) {
                        val allAccounts by vm.accounts.collectAsState()
                        // A+A1：排序页按类型分组（顺序与账户页一致），所以要拿到本账本的类型列表
                        val typeList by vm.accountTypes.collectAsState()
                        val balances by vm.balances.collectAsState()
                        AccountSortScreen(
                            accounts = allAccounts,
                            accountTypes = typeList,
                            balanceOf = { id ->
                                balances.firstOrNull { it.account.id == id }?.balanceCents ?: 0L
                            },
                            onReorder = { vm.reorderAccounts(it) },
                            onBack = { safeBack() }
                        )
                    }
                    composable(Routes.ACCOUNTS) {
                        AccountsScreen(
                            vm = vm,
                            onMenu = { scope.launch { drawerState.open() } },
                            onOpenAccount = { nav.navigate(Routes.ACCOUNT_MONTHLY + "/" + it) },
                            onEditAccount = { nav.navigate(Routes.ACCOUNT_EDIT + "/" + it) },
                            onAddAccount = { nav.navigate(Routes.ACCOUNT_EDIT + "/0") },
                            onSortAccounts = { nav.navigate(Routes.ACCOUNT_SORT) }
                        )
                    }
                    composable(Routes.OVERVIEW) {
                        OverviewScreen(
                            vm = vm,
                            onMenu = { scope.launch { drawerState.open() } },
                            onOpenCalendar = { nav.navigate(Routes.CALENDAR) },
                            onOpenReports = { nav.navigate(Routes.REPORTS) { launchSingleTop = true } },
                            // 应收应付汇总表格点进去看明细
                            onOpenArAp = { nav.navigate(Routes.AR_AP) },
                            onDrillDown = { spec ->
                                vm.openTxnList(spec)
                                nav.navigate(Routes.TXN_LIST)
                            }
                        )
                    }
                    composable(Routes.REPORTS) {
                        ReportsScreen(vm = vm, onBack = { safeBack() })
                    }
                    composable(Routes.HELP) {
                        HelpScreen(vm = vm, onBack = { safeBack() })
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            section = null,
                            onOpenSection = { key ->
                                nav.navigate(Routes.SETTINGS + "/" + key) { launchSingleTop = true }
                            },
                            vm = vm,
                            onBack = { safeBack() },
                            onOpenCategories = { nav.navigate(Routes.CATEGORIES) },
                            onOpenBackup = { nav.navigate(Routes.BACKUP) },
                            onOpenRecurring = { nav.navigate(Routes.RECURRING) },
                            onOpenLedgers = { nav.navigate(Routes.LEDGERS) },
                            onOpenRecycle = { nav.navigate(Routes.RECYCLE) },
                            onOpenImportPreview = {
                                nav.navigate(Routes.CSV_IMPORT) { launchSingleTop = true }
                            },
                            onOpenHelp = { nav.navigate(Routes.HELP) { launchSingleTop = true } },
                            // N54：标签管理里点标签 → 该标签的账单列表
                            onOpenTxnList = { nav.navigate(Routes.TXN_LIST) { launchSingleTop = true } }
                        )
                    }
                    composable(Routes.RECYCLE) {
                        RecycleBinScreen(vm = vm, onBack = { safeBack() })
                    }
                    composable(Routes.CSV_IMPORT) {
                        CsvImportScreen(vm = vm, onBack = { safeBack() })
                    }
                    composable(Routes.SETTINGS + "/{section}") { entry ->
                        val key = entry.arguments?.getString("section")
                        SettingsScreen(
                            section = key,
                            onBack = { safeBack() },
                            vm = vm,
                            onOpenCategories = { nav.navigate(Routes.CATEGORIES) { launchSingleTop = true } },
                            onOpenBackup = { nav.navigate(Routes.BACKUP) { launchSingleTop = true } },
                            onOpenRecurring = { nav.navigate(Routes.RECURRING) { launchSingleTop = true } },
                            onOpenLedgers = { nav.navigate(Routes.LEDGERS) { launchSingleTop = true } },
                            onOpenRecycle = { nav.navigate(Routes.RECYCLE) { launchSingleTop = true } },
                            onOpenImportPreview = { nav.navigate(Routes.CSV_IMPORT) { launchSingleTop = true } },
                            onOpenHelp = { nav.navigate(Routes.HELP) { launchSingleTop = true } },
                            onOpenTxnList = { nav.navigate(Routes.TXN_LIST) { launchSingleTop = true } }
                        )
                    }
                    composable(Routes.CATEGORIES) {
                        CategoryScreen(vm = vm, onBack = { safeBack() })
                    }
                    composable(Routes.BACKUP) {
                        BackupScreen(vm = vm, onBack = { safeBack() })
                    }
                    composable(Routes.RECURRING) {
                        RecurringListScreen(
                            vm = vm,
                            onBack = { safeBack() },
                            onEdit = { id -> nav.navigate(Routes.RECURRING_EDIT + "?ruleId=" + (id ?: -1L)) }
                        )
                    }
                    composable(Routes.LEDGERS) {
                        LedgersScreen(vm = vm, onBack = { safeBack() })
                    }
                    composable(Routes.RECURRING_EDIT + "?ruleId={ruleId}") { entry ->
                        val ruleId = entry.arguments?.getString("ruleId")?.toLongOrNull() ?: -1L
                        RecurringEditScreen(
                            vm = vm,
                            ruleId = ruleId.takeIf { it > 0L },
                            onBack = { safeBack() }
                        )
                    }
                    composable(Routes.DETAIL + "/{txnId}") { entry ->
                        val id = entry.arguments?.getString("txnId")?.toLongOrNull() ?: 0L
                        DetailScreen(vm = vm, txnId = id, onBack = { safeBack() })
                    }
                }
            }
        }
            // v2.8.0 一句话记账（Beta）：输入一句话 → 解析 → 带进记一笔面板
            if (showQuickEntry) {
                TextInputSheet(
                    title = "一句话记账（Beta）",
                    initial = "",
                    placeholder = "午饭 25 支付宝 · 昨天",
                    singleLine = false,
                    onCancel = { showQuickEntry = false },
                    onConfirm = { text ->
                        showQuickEntry = false
                        val d = QuickEntry.parse(
                            text,
                            vm.categories.value,
                            vm.accounts.value,
                            history = vm.txns.value
                        )
                        if (d == null) vm.toast("没识别到金额，先帮你打开记一笔")
                        addSheet = AddSheetRequest(
                            initialType = d?.type ?: lastType,
                            initAmountCents = d?.amountCents ?: 0L,
                            initNote = d?.note ?: "",
                            initCategoryId = d?.childId?.takeIf { it != 0L } ?: d?.parentId ?: 0L,
                            initAccountId = d?.accountId ?: 0L,
                            initDateMillis = d?.let { DateFmt.toMillis(it.date) } ?: 0L
                        )
                    }
                )
            }

            // P1 液态玻璃：悬浮胶囊底栏画在内容之上（内容从它底下穿过 → 毛玻璃有东西可模糊）。
            // v2.5.2：多选时隐藏底栏——它现在是覆盖层，会把「已选 N 笔」操作条压住；
            // 多选本身是模态操作，隐藏导航栏更合理（加号也是同样的处理）
            if (showChrome && !homeMultiActive) {
                BottomBar(
                    hazeState = hazeState,
                    current = route ?: Routes.HOME,
                    onSelect = { target ->
                        if (target != route) {
                            nav.navigate(target) {
                                popUpTo(Routes.HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }   // Box：底栏容器（内容 + 悬浮底栏）
    }

    addSheet?.let { req ->
        AddEntrySheet(
            vm = vm,
            request = req,
            onDismiss = {
                lastType = req.editing?.type ?: req.initialType
                addSheet = null
            }
        )
    }

    // 导入结果：把「跳过的那几行 + 原因」直接摆出来，避免只看到"成功 0 笔"却不知道哪错了
    importReport?.let { r ->
        AlertDialog(
            onDismissRequest = { vm.dismissImportReport() },
            confirmButton = {
                TextButton(onClick = { vm.dismissImportReport() }) {
                    Text("知道了", color = Primary)
                }
            },
            title = { Text("导入结果", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        if (r.skipped == 0) "成功导入 ${r.success} 笔账单。"
                        else "成功导入 ${r.success} 笔，跳过 ${r.skipped} 笔。",
                        fontSize = 13.5.sp,
                        color = Ink
                    )
                    if (r.details.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("没进来的原因：", fontSize = 12.5.sp, color = Sub, fontWeight = FontWeight.SemiBold)
                        r.details.forEach { d ->
                            Text(
                                "· $d",
                                fontSize = 12.sp,
                                color = Expense,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    if (r.skipped > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "提示：日期写成 2026-09-21，类型填 支出 / 收入 / 互转 / 借出 / 借入 即可；" +
                                "从 Excel 直接另存为 CSV 也行，GBK 编码、分号或 Tab 分隔都会自动识别。",
                            fontSize = 11.5.sp,
                            color = Sub,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        )
    }
    }   // CompositionLocalProvider（P1 液态玻璃：减弱视觉效果）
}

@Composable
private fun LightStatusBar(light: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = light
        }
    }
}

/** 悬浮底栏总高度（胶囊 56 + 底部外边距）：FAB 抬升与页面留白都按它算 */
private val FLOATING_BAR_HEIGHT = 66.dp

/**
 * 悬浮液态玻璃底栏（P1，对齐参考视频）：
 * - **悬浮胶囊**：左右留 14dp、下方留 10dp（再叠系统导航栏 inset），四角全圆；
 * - **真实背景模糊**：内容从胶囊底下穿过，用 haze 取样后高斯模糊
 *   （API 31+ 真模糊；更低版本 / 开了「减弱视觉效果」时退化成半透明白或纯色卡片）；
 * - **选中态**：图标 + 文字换成品牌色，背后垫一层胶囊形高亮。
 */
@Composable
private fun BottomBar(
    hazeState: HazeState,
    current: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        Triple(Routes.HOME, "首页", Icons.Rounded.Home),
        Triple(Routes.ACCOUNTS, "账户", Icons.Rounded.AccountBalanceWallet),
        Triple(Routes.OVERVIEW, "总览", Icons.Rounded.PieChart)
    )
    val style = glass
    val dark = IsDarkTheme

    // haze 玻璃配方：底色很淡，靠皮肤透出背后内容；低版本用 fallbackTint 兜底（不透明也不突兀）
    val hazeStyle = HazeStyle(
        backgroundColor = if (dark) Color(0xFF161C24).copy(alpha = 0.58f)
        else Color.White.copy(alpha = 0.55f),
        tints = listOf(
            HazeTint(if (dark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.25f))
        ),
        blurRadius = 22.dp,
        noiseFactor = 0.04f,
        fallbackTint = HazeTint(
            if (dark) Color(0xFF1A2028).copy(alpha = 0.96f) else Color.White.copy(alpha = 0.97f)
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(GlassShapes.Pill)
                .then(
                    if (style.decorated) {
                        // 真实毛玻璃（haze 内部对 API < 31 自动降级为 fallbackTint）
                        Modifier
                            .shadow(10.dp, GlassShapes.Pill, clip = false)
                            .hazeEffect(state = hazeState, style = hazeStyle)
                            .border(1.dp, style.border, GlassShapes.Pill)
                    } else {
                        // 减弱视觉效果：直接不透明卡片
                        Modifier
                            .shadow(6.dp, GlassShapes.Pill, clip = false)
                            .background(CardWhite)
                    }
                )
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (route, label, icon) ->
                val on = route == current
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 7.dp)
                        .clip(GlassShapes.Pill)
                        .then(
                            if (on) {
                                Modifier.background(
                                    if (style.decorated) Primary.copy(alpha = 0.14f) else PrimaryBg
                                )
                            } else {
                                Modifier
                            }
                        )
                        .clickable { onSelect(route) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            icon,
                            contentDescription = label,
                            tint = if (on) Primary else Muted2,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            label,
                            fontSize = 10.5.sp,
                            color = if (on) Primary else Muted2,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerContent(
    vm: AppViewModel,
    onNavigate: (String) -> Unit
) {
    val ledgers by vm.ledgers.collectAsState()
    // v2.8.0：高级功能开关（应收应付关掉后抽屉里不显示）
    val advArApOn by vm.advArAp.collectAsState()
    val currentId by vm.currentLedgerId.collectAsState()
    val rules by vm.rules.collectAsState()
    val backups by vm.backups.collectAsState()

    ModalDrawerSheet(
        drawerContainerColor = CardWhite,
        drawerShape = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp),
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 品牌
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Brush.linearGradient(listOf(PrimaryLight, Primary))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("记", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("Lumo", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(3.dp))
                    Text("Light up your wealth", fontSize = 11.5.sp, color = Sub)
                }
            }
            Spacer(Modifier.height(20.dp))

            DrawerSection("账本")
            // M13：侧栏只做账本切换，「创建账本」移到「设置 → 账本管理」
            ledgers.forEach { l ->
                val on = l.id == currentId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (on) PrimaryBg else Color.Transparent)
                        .clickable { vm.switchLedger(l.id); onNavigate(Routes.HOME) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        l.name,
                        fontSize = 14.sp,
                        color = if (on) Primary else Sub2,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                    )
                    Spacer(Modifier.weight(1f))
                    if (on) Icon(Icons.Rounded.Check, null, tint = Primary, modifier = Modifier.size(15.dp))
                }
            }

            // P1 IA 重组：抽屉只留「去哪」的入口，且按使用频率分两层。
            // 一次性任务（导入 / 导出 / 下载模板）、设置型页面（分类 / 周期 / 回收站 / 隐私）
            // 全部收进「设置」，抽屉从 12 项精简到 7 项
            DrawerSection("常用")
            DrawerItem(Icons.Rounded.Search, "搜索", null, { onNavigate(Routes.SEARCH) })
            DrawerItem(Icons.Rounded.CalendarMonth, "收支日历", null, { onNavigate(Routes.CALENDAR) })
            DrawerItem(Icons.Rounded.BarChart, "报表", null, { onNavigate(Routes.REPORTS) })
            if (advArApOn) {
                DrawerItem(
                    Icons.Rounded.MenuBook, "应收应付",
                    null, { onNavigate(Routes.AR_AP) }
                )
            }
            DrawerItem(
                Icons.Rounded.Restore, "数据备份与恢复",
                if (backups.isEmpty()) "尚未备份" else backups.first().timeText,
                { onNavigate(Routes.BACKUP) }
            )

            DrawerSection("更多")
            DrawerItem(Icons.Rounded.Settings, "设置", null, { onNavigate(Routes.SETTINGS) })
            DrawerItem(Icons.Rounded.HelpOutline, "帮助与反馈", null, { onNavigate(Routes.HELP) })

            Spacer(Modifier.height(22.dp))
            Text(
                "版本 v" + com.qingjizhang.app.BuildConfig.VERSION_NAME,
                fontSize = 11.5.sp,
                color = Muted2,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun DrawerSection(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Muted2,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun DrawerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String?,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Sub2, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(11.dp))
        Text(label, fontSize = 14.sp, color = Sub2)
        Spacer(Modifier.weight(1f))
        if (value != null) {
            Text(value, fontSize = 12.sp, color = Sub)
        } else {
            Icon(
                Icons.Rounded.KeyboardArrowRight,
                null,
                tint = Chevron,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
