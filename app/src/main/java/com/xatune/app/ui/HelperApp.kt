package com.xatune.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xatune.app.BuildConfig
import com.xatune.app.data.updateNotesForDisplay
import com.xatune.app.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable fun HelperApp(model: HelperViewModel, announcements: AnnouncementViewModel? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val update by model.updateState.collectAsStateWithLifecycle()
    var relogin by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.table?.fetchedAt) { if (state.table != null && !state.needsLogin) relogin = false }
    val screen = when {
        state.starting -> "starting"
        state.showWebLogin -> "webLogin"
        state.account.isEmpty() || state.table == null && state.selectedSemester.isEmpty() || relogin -> "login"
        else -> "main"
    }
    ScreenTransition(screen) { destination -> when (destination) {
        "starting" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        "webLogin" -> WebLogin(state.webAccount, model::closeWeb, model::finishWeb, model::webError)
        "login" -> LoginScreen(state, model::login, model::openWeb, if (BuildConfig.DEMO_ENABLED) model::demo else null, if (state.table != null) ({ relogin = false }) else null, announcements?.takeIf { state.notice == null && update.offer == null })
        else -> MainShell(state, model, { relogin = true }, announcements)
    }
    }
    UpdateNotification(update, model::dismissUpdate)
}

@Composable internal fun UpdateNotification(update: UpdateUi, onDismiss: () -> Unit) {
    val release = update.offer ?: return
    val notes = remember(release.notes) { updateNotesForDisplay(release.notes) }
    var linkError by remember(release.version, release.versionCode, release.url, release.apiUrl) { mutableStateOf(false) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val apiUrl = release.apiUrl
    fun openLink(target: String) {
        runCatching { uriHandler.openUri(target) }.onSuccess { onDismiss() }.onFailure { linkError = true }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${release.version}") },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (apiUrl != null) "确认后将在浏览器中打开对应下载链接，由你完成安装。"
                    else "确认后将在浏览器中打开 GitHub Release 页面，由你下载并安装新版本。"
                )
                if (notes.isNotBlank()) Text(notes)
                if (linkError) {
                    Text(
                        "没有可打开下载链接的浏览器，请安装浏览器后重试。",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (apiUrl != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { openLink(release.url) },
                            modifier = Modifier.fillMaxWidth().testTag("updateDownloadGithub")
                        ) { Text("前往 GitHub 下载") }
                        OutlinedButton(
                            onClick = { openLink(apiUrl) },
                            modifier = Modifier.fillMaxWidth().testTag("updateDownloadApi")
                        ) { Text("国内服务器下载") }
                    }
                }
            }
        },
        confirmButton = {
            if (apiUrl == null) {
                TextButton(onClick = { openLink(release.url) }) { Text("前往 GitHub 下载") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } }
    )
}

@Composable internal fun LoginScreen(state: UiState, onLogin: (String, String) -> Unit, onWeb: (String) -> Unit, onDemo: (() -> Unit)?, onBack: (() -> Unit)?, announcements: AnnouncementViewModel? = null) {
    var privacy by remember { mutableStateOf(false) }
    if (privacy) PrivacyPolicyDialog { privacy = false }
    if (!privacy && !state.busy) announcements?.let { AnnouncementNotification(it) }
    var account by rememberSaveable { mutableStateOf(state.account.takeUnless { it == "__demo__" }.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val backModifier = Modifier.predictiveBack(enabled = onBack != null) { onBack?.invoke() }
    Surface(Modifier.fillMaxSize().then(backModifier)) {
        ResponsiveWidthBox(maxWidth = 520.dp, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(80.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(androidx.compose.ui.res.painterResource(com.xatune.app.R.drawable.ic_xatune_mark), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                }
                Text("XATune", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("校园生活，一处掌握", style = MaterialTheme.typography.titleMedium)
                Text("连接学校统一身份认证，查看教务信息，使用校园服务。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = account, onValueChange = { account = it.trim() }, label = { Text("学号") }, leadingIcon = { Icon(Icons.Default.Person, null) }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("account"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("统一认证密码") }, leadingIcon = { Icon(Icons.Default.Lock, null) }, singleLine = true, enabled = !state.busy,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "隐藏密码" else "显示密码") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("password"))
                Reveal(state.error != null) { state.error?.let { ErrorCard(it) } }
                Button(onClick = { onLogin(account, password) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("login")) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text("登录")
                }
                OutlinedButton(onClick = { password = ""; onWeb(account) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Default.Language, null); Spacer(Modifier.width(8.dp)); Text("使用网页登录") }
                InfoPanel("需要验证码或动态码时，可在学校网页完成认证。凭据仅加密保存在此设备，直接连接学校服务器。", Icons.Default.Lock)
                if (BuildConfig.DEMO_ENABLED) onDemo?.let { demo -> TextButton(onClick = demo, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("demo")) { Text("体验校园助手 · 调试版") } }
                TextButton(onClick = { privacy = true }, modifier = Modifier.fillMaxWidth()) { Text("隐私政策") }
                onBack?.let { TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("返回离线课表") } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MainShell(state: UiState, model: HelperViewModel, onRelogin: () -> Unit, announcements: AnnouncementViewModel?) {
    var selectedAnnouncement by remember { mutableStateOf<com.xatune.app.data.ReceivedAnnouncement?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var timetableSettingsReturn by rememberSaveable { mutableStateOf<String?>("settings") }
    var remindersReturn by rememberSaveable { mutableStateOf<String?>("settings") }
    var academicScope by rememberSaveable { mutableStateOf<String?>(null) }
    var showCalibration by remember { mutableStateOf(false) }
    var course by remember { mutableStateOf<CourseBlock?>(null) }
    var editRequest by remember { mutableStateOf<CourseEditRequest?>(null) }
    var history by remember { mutableStateOf(false) }
    var timetableTitleMenu by remember { mutableStateOf(false) }
    var timetableOverflow by remember { mutableStateOf(false) }
    val canEdit = state.table != null && state.kind == TableKind.STUDENT && !state.busy
    LaunchedEffect(state.account, state.selectedSemester, state.kind) { course = null; editRequest = null; history = false }
    val requestedPage by model.destination.collectAsStateWithLifecycle()
    LaunchedEffect(requestedPage) {
        val request = requestedPage
        if (request != null) {
            academicScope = request.substringAfter(':', "").ifEmpty { null }
            val targetPage = request.substringBefore(':')
            if (targetPage == "timetable") { tab = 1; page = null } else { tab = 2; page = targetPage }
            model.consumeDestination()
        }
    }
    val update by model.updateState.collectAsStateWithLifecycle()
    LaunchedEffect(page) { if (page != "exams" && page != "grades") academicScope = null }
    val campusState = model.campus?.state?.collectAsStateWithLifecycle()?.value ?: CampusState()
    val titles = listOf("首页", "课程表", "校园")
    val icons = listOf(Icons.Default.Home, Icons.Default.CalendarMonth, Icons.Default.AccountBalance)

    fun navigateBack(current: String?): String? = when (current) {
        "timetableSettings" -> timetableSettingsReturn
        "notifications" -> remindersReturn
        else -> parentPage(current)
    }

    val backModifier = Modifier.predictiveBack(enabled = page != null || tab != 0) {
        if (page != null) page = navigateBack(page) else tab = 0
    }
    var previousPage by remember { mutableStateOf(page) }
    val direction = remember(page) { if (page == null || navigateBack(previousPage) == page) -1 else 1 }
    SideEffect { previousPage = page }

    ScreenTransition(page, modifier = backModifier, direction = direction) { destination -> when (destination) {
        "settings" -> SettingsScreen(
            state = state,
            onBack = { page = null },
            onCalibrate = { showCalibration = true },
            onRelogin = onRelogin,
            onLogout = model::logout,
            onReminders = { remindersReturn = "settings"; page = "notifications" },
            onWidgets = { page = "widgets" },
            onWeekend = model::showWeekend,
            onDiagnostics = model::showDiagnostics,
            campus = campusState,
            onAutoElectric = { model.campus?.autoElectric(it) },
            update = update,
            onCheckUpdates = { model.checkUpdates(manual = true) },
            onCheckinLocation = { page = "checkinLocation" },
            onUpdateChannel = model::updateChannel,
            onUpdateServer = model::updateServer,
            onUpdateRepository = model::updateRepositoryUrl,
            onAutomaticUpdates = model::automaticUpdates,
            announcements = announcements,
            onOpenAnnouncement = { selectedAnnouncement = it },
            onAnnouncementHistory = { page = "announcementHistory" },
            onReducedInformationUpdates = model::reducedInformationUpdates,
            onTimetableSettings = { timetableSettingsReturn = "settings"; page = "timetableSettings" },
            onInformationUpdates = { page = "informationUpdates" },
            onAppUpdates = { page = "appUpdates" },
            onLoadProfile = { model.loadAcademic(AcademicKind.PLAN, "all") }
        )
        "timetableSettings" -> TimetableSettingsScreen(
            state = state,
            onBack = { page = timetableSettingsReturn },
            onWeekend = model::showWeekend,
            onCalibrate = { showCalibration = true },
            onReminders = { remindersReturn = "timetableSettings"; page = "notifications" }
        )
        "informationUpdates" -> InformationUpdatesScreen(
            state = state,
            campus = campusState,
            onBack = { page = "settings" },
            onAutoElectric = { model.campus?.autoElectric(it) },
            onReducedInformationUpdates = model::reducedInformationUpdates
        )
        "appUpdates" -> AppUpdatesScreen(
            update = update,
            onBack = { page = "settings" },
            onUpdateChannel = model::updateChannel,
            onUpdateServer = model::updateServer,
            onUpdateRepository = model::updateRepositoryUrl,
            onAutomaticUpdates = model::automaticUpdates,
            onCheckUpdates = { model.checkUpdates(manual = true) }
        )
        "announcementHistory" -> announcements?.let { AnnouncementHistoryScreen(it, { page = "settings" }, { selectedAnnouncement = it }) }
        "electricity" -> model.campus?.let {
            ElectricityScreen(it, { page = null }, { page = "charge" }, onHistory = { page = "electricityHistory" })
        }
        "electricityHistory" -> model.campus?.let {
            ElectricityHistoryScreen(it) { page = "electricity" }
        }
        "calendar" -> model.campus?.let { CalendarScreen(it, { page = null }) }
        "notifications" -> model.campus?.let { ReminderScreen(it, { page = remindersReturn }) }
        "checkinLocation" -> CheckinLocationPicker { page = "settings" }
        "widgets" -> WidgetSettings({ page = "settings" }, campusState)
        in CampusLinks.pages.keys -> CampusWeb(destination!!, { model.browserClosed(); page = parentPage(destination) }, onSessionExpired = model::webSessionExpired)
        "exams", "grades", "plan" -> AcademicScreen(when (destination) { "exams" -> AcademicKind.EXAMS; "grades" -> AcademicKind.GRADES; else -> AcademicKind.PLAN }, state, model, { page = null }, onRelogin, { page = if (destination == "plan") "planWeb" else "eams" }, initialScope = academicScope)
        else -> {
            val selectedSemesterName = state.semesters.firstOrNull { it.id == state.selectedSemester }?.name ?: "课程表"
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    NavigationBar {
                        titles.forEachIndexed { index, title ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(icons[index], null) },
                                label = { Text(title) },
                                modifier = Modifier.testTag("tab$index")
                            )
                        }
                    }
                }
            ) { padding ->
                Crossfade(
                    targetState = tab,
                    animationSpec = tween(160),
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    label = "mainTabs"
                ) { activeTab ->
                    when (activeTab) {
                        0 -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                            LoadingProgress(state.busy)
                            if (state.demo) Text("示例数据 · 未连接学校账号", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                            Reveal(state.error != null) { state.error?.let { error ->
                                ElevatedCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        Row {
                                            if (state.needsLogin) TextButton(onClick = onRelogin) { Text("重新登录") }
                                            else TextButton(onClick = model::refresh, enabled = !state.busy) { Text("重试") }
                                            TextButton(onClick = model::clearError) { Text("收起") }
                                        }
                                    }
                                }
                            } }
                            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                                isRefreshing = state.busy,
                                onRefresh = model::refresh,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            ) {
                                HomeScreen(state, { tab = 1 }, { course = it }, { model.campus?.dismissError(); page = it }, campusState)
                            }
                        }
                        1 -> Column(Modifier.fillMaxSize()) {
                            TopAppBar(
                                title = {
                                    Box {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable(enabled = !state.busy && (state.semesters.isNotEmpty() || !state.demo)) { timetableTitleMenu = true }
                                                .padding(horizontal = 4.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = if (state.kind == TableKind.STUDENT) selectedSemesterName else "$selectedSemesterName · ${state.kind.title}",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (state.semesters.isNotEmpty() || !state.demo) {
                                                Icon(Icons.Default.ArrowDropDown, "切换学期", Modifier.size(20.dp))
                                            }
                                        }
                                        SemesterSplitMenu(
                                            expanded = timetableTitleMenu,
                                            onDismiss = { timetableTitleMenu = false },
                                            semesters = state.semesters,
                                            selectedId = state.selectedSemester,
                                            enabled = !state.busy,
                                            onSelect = { semester -> model.select(semester.id, state.kind) },
                                            selectedKind = if (!state.demo) state.kind else null,
                                            showReturnCurrent = !state.demo && (state.selectedSemester != state.currentSemester || state.kind != TableKind.STUDENT),
                                            onReturnCurrent = { model.select(state.currentSemester, TableKind.STUDENT) },
                                            onSelectKind = { kind -> model.select(state.selectedSemester, kind) }
                                        )
                                    }
                                },
                                actions = {
                                    if (canEdit) {
                                        IconButton(onClick = { editRequest = CourseEditRequest(EditKind.ADD) }) {
                                            Icon(Icons.Default.Add, "添加课程")
                                        }
                                    }
                                    Box {
                                        IconButton(onClick = { timetableOverflow = true }) {
                                            BadgedBox(badge = { if (canEdit && state.pendingEdits.isNotEmpty()) Badge() }) {
                                                Icon(Icons.Default.MoreVert, "更多选项")
                                            }
                                        }
                                        DropdownMenu(expanded = timetableOverflow, onDismissRequest = { timetableOverflow = false }) {
                                            if (canEdit) {
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(if (state.pendingEdits.isEmpty()) "我的课表修改" else "我的课表修改 (${state.pendingEdits.size})")
                                                    },
                                                    leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(20.dp)) },
                                                    onClick = { timetableOverflow = false; history = true }
                                                )
                                            }
                                            if (!state.demo) {
                                                DropdownMenuItem(
                                                    text = { Text("刷新课表") },
                                                    leadingIcon = { Icon(Icons.Default.Refresh, null, Modifier.size(20.dp)) },
                                                    enabled = !state.busy,
                                                    onClick = { timetableOverflow = false; model.refresh() }
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("课程表设置") },
                                                leadingIcon = { Icon(Icons.Default.Settings, null, Modifier.size(20.dp)) },
                                                onClick = {
                                                    timetableOverflow = false
                                                    timetableSettingsReturn = null
                                                    page = "timetableSettings"
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                            LoadingProgress(state.busy)
                            if (state.demo) Text("示例数据 · 未连接学校账号", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                            Reveal(state.error != null) { state.error?.let { error ->
                                ElevatedCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        Row {
                                            if (state.needsLogin) TextButton(onClick = onRelogin) { Text("重新登录") }
                                            else TextButton(onClick = model::refresh, enabled = !state.busy) { Text("重试") }
                                            TextButton(onClick = model::clearError) { Text("收起") }
                                        }
                                    }
                                }
                            } }
                            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                                isRefreshing = state.busy,
                                onRefresh = model::refresh,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            ) {
                                TimetableScreen(state, model, { course = it })
                            }
                        }
                        else -> CampusScreen(
                            campus = campusState,
                            onSettings = { page = "settings" },
                            onOpen = { model.campus?.dismissError(); page = it }
                        )
                    }
                }
            }
        }
    } }

    val reminderPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { _ -> model.resume() }
    val reminderContext = androidx.compose.ui.platform.LocalContext.current
    val offerClasses = page == null && tab == 1 && !campusState.promptedClasses
    val offerExams = page == "exams" && !campusState.promptedExams
    if (!state.demo && campusState.account == state.account && !campusState.busy && (offerClasses || offerExams)) {
        var draft by remember(campusState.account, offerClasses, offerExams) { mutableStateOf(campusState.reminders) }
        var leadMenu by remember(campusState.account, offerClasses, offerExams) { mutableStateOf(false) }
        val leadOptions = listOf(5, 10, 15, 20, 30, 45, 60)
        AlertDialog(
            onDismissRequest = { model.campus?.acknowledgeReminders(offerClasses, enable = false) },
            title = { Text(if (offerClasses) "上课提醒" else "考试提醒") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (offerClasses) {
                        Text("使用当前学期的学生课表缓存安排课前通知，可在提醒设置中随时调整。")
                        Box {
                            OutlinedButton(onClick = { leadMenu = true }) { Text("上课前 ${draft.classLead} 分钟") }
                            DropdownMenu(expanded = leadMenu, onDismissRequest = { leadMenu = false }) {
                                leadOptions.forEach { minutes ->
                                    DropdownMenuItem(text = { Text("$minutes 分钟") }, onClick = {
                                        leadMenu = false
                                        draft = draft.copy(classLead = minutes)
                                    })
                                }
                            }
                        }
                    } else {
                        Text("根据当前学期的考试安排发送考前通知，可在提醒设置中随时调整。")
                        ToggleRow("前一天提醒", "${"%02d:%02d".format(draft.eveMinutes / 60, draft.eveMinutes % 60)}", draft.eve, { draft = draft.copy(eve = it) })
                        ToggleRow("当天早晨提醒", "${"%02d:%02d".format(draft.morningMinutes / 60, draft.morningMinutes % 60)}", draft.morning, { draft = draft.copy(morning = it) })
                        ToggleRow("考前提醒", "开考前 ${draft.examLead} 分钟", draft.before, { draft = draft.copy(before = it) })
                        if (draft.before) Box {
                            OutlinedButton(onClick = { leadMenu = true }) { Text("开考前 ${draft.examLead} 分钟") }
                            DropdownMenu(expanded = leadMenu, onDismissRequest = { leadMenu = false }) {
                                leadOptions.forEach { minutes ->
                                    DropdownMenuItem(text = { Text("$minutes 分钟") }, onClick = {
                                        leadMenu = false
                                        draft = draft.copy(examLead = minutes)
                                    })
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                model.campus?.acknowledgeReminders(offerClasses, enable = true, draft = draft)
                if (android.os.Build.VERSION.SDK_INT >= 33 && !com.xatune.app.platform.SystemCoordinator.notificationsAllowed(reminderContext))
                    reminderPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("开启提醒") } },
            dismissButton = { TextButton(onClick = { model.campus?.acknowledgeReminders(offerClasses, enable = false) }) { Text("先不用") } }
        )
    }
    selectedAnnouncement?.let { record ->
        announcements?.let { center -> AnnouncementDetail(record.announcement, record.receivedAt, center) { center.acknowledge(record); selectedAnnouncement = null } }
    }
    if (page == null && course == null && editRequest == null && !history && !showCalibration && selectedAnnouncement == null &&
        state.notice == null && update.offer == null && (state.demo || campusState.promptedClasses || tab != 1)) {
        announcements?.let { AnnouncementNotification(it) }
    }
    // Display one banner at a time; academic notices retain their full lifetime after update dismissal.
    if (update.offer == null) state.notice?.let { notice ->
        AcademicNotification(notice, onOpen = {
            if (notice.kind == AcademicKind.GRADES) model.gradeScope(notice.scope == "all")
            model.openDestination("${if (notice.kind == AcademicKind.EXAMS) "exams" else "grades"}:${notice.scope}")
            model.dismissNotice()
        }, onDismiss = model::dismissNotice)
    }
    course?.let { selected ->
        ModalBottomSheet(onDismissRequest = { course = null }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(selected.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                DetailRow(Icons.Default.Schedule, "周${"一二三四五六日"[selected.day - 1]} · ${selected.periodText} · ${Periods.range(selected)}")
                DetailRow(Icons.Default.LocationOn, selected.room.ifEmpty { "未提供教室" })
                DetailRow(Icons.Default.Person, selected.teacher.ifEmpty { "未提供教师" })
                DetailRow(Icons.Default.DateRange, "上课周次：${selected.weeks.joinToString("、")}")
                if (canEdit) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    FilledTonalButton(onClick = { editRequest = CourseEditRequest(EditKind.MOVE, selected); course = null }) { Text("修改课程") }
                    OutlinedButton(
                        onClick = { editRequest = CourseEditRequest(EditKind.REMOVE, selected); course = null },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("移除课程") }
                }
                if (!selected.key.startsWith("local-")) Text("课程号 ${selected.key}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    editRequest?.let { request -> if (state.table != null) TimetableEditDialog(state, request, { editRequest = null }, model::saveEdits) }
    if (history) TimetableHistoryDialog(state, { history = false }, model::saveEdits)

    if (showCalibration) {
        var date by remember { mutableStateOf(state.table?.monday.orEmpty()) }
        var actualWeek by remember { mutableIntStateOf(state.table?.let { weekOf(LocalDate.parse(it.monday), schoolToday()) } ?: 1) }
        var weekMenu by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { showCalibration = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("校准周次", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Box {
                    OutlinedButton(onClick = { weekMenu = true }, modifier = Modifier.fillMaxWidth().testTag("actualWeek")) {
                        Text("现在实际是第 $actualWeek 周")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(weekMenu, { weekMenu = false }) {
                        (1..30).forEach { value ->
                            DropdownMenuItem(text = { Text("第 $value 周") }, onClick = {
                                actualWeek = value
                                date = mondayForWeek(value).toString()
                                weekMenu = false
                            })
                        }
                    }
                }
                FilledTonalButton(
                    onClick = { showCalibration = false; model.calibrateWeek(actualWeek) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("按这个周次校正") }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("第一周周一") },
                    supportingText = { Text("日期格式 yyyy-MM-dd；仅修改本地周次。") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { showCalibration = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = { showCalibration = false; model.calibrate(date) }, modifier = Modifier.weight(1f)) { Text("保存日期") }
                }
            }
        }
    }
}

@Composable private fun HomeScreen(state: UiState, onTable: () -> Unit, onCourse: (CourseBlock) -> Unit, onAcademic: (String) -> Unit, campus: CampusState) {
    var now by remember { mutableStateOf(ZonedDateTime.now(SchoolZone)) }
    LaunchedEffect(Unit) { while (true) { now = ZonedDateTime.now(SchoolZone); delay(30_000) } }
    val table = state.currentTable ?: state.table?.takeIf { it.kind == TableKind.STUDENT && it.semester.id == state.currentSemester }
    val week = table?.let { weekOf(LocalDate.parse(it.monday), now.toLocalDate()) } ?: state.week
    val current = table != null && table.semester.id == state.currentSemester
    val schedule = homeSchedule(table, current, now.toLocalDate(), now.toLocalTime())
    val anchor = examAnchor(state)
    val nextExam = state.academic[academicKey(AcademicKind.EXAMS, state.currentSemester)]?.data?.exams
        ?.filter { it.isUpcoming(now, anchor) }
        ?.sortedWith(compareBy({ it.startAt(anchor) }, { it.time }))?.firstOrNull()
    val examDays = nextExam?.date(anchor)?.let { java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), it) }
    val status = buildList {
        if (current && week > 0) add("第 $week 周")
        add(if (schedule.remaining == 0) "今天没课了" else "今天还有 ${schedule.remaining} 节课")
        examDays?.let { add(if (it == 0L) "今天有考试" else "下次考试还有 $it 天") }
    }.joinToString(" · ")
    val holiday = nextCampusEvent(campus.calendar, now.toLocalDate())
    ResponsiveWidthBox(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("home"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(now.format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(campusGreeting(now.toLocalTime()), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("homeGreeting"))
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                ContentCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(schedule.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("周${"一二三四五六日"[schedule.date.dayOfWeek.value - 1]}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (schedule.courses.isEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                IconTile(Icons.Default.WbSunny)
                                Text("明天没有课程安排", style = MaterialTheme.typography.titleMedium)
                                Text("本周的其他课程可在课程表中查看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                schedule.courses.forEachIndexed { index, block ->
                                    HomeCourseRow(
                                        block = block,
                                        badge = when {
                                            !schedule.tomorrow && block.id == schedule.active -> "正在上"
                                            !schedule.tomorrow && block.id == schedule.next -> "下一节"
                                            else -> null
                                        },
                                        shape = groupedItemShape(index, schedule.courses.size),
                                        onClick = { onCourse(block) }
                                    )
                                }
                            }
                        }
                        TextButton(onClick = onTable, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("查看完整课表", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                            Icon(Icons.AutoMirrored.Filled.NavigateNext, null)
                        }
                    }
                }
            }
            item {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val sideBySide = maxWidth >= 320.dp && LocalDensity.current.fontScale <= 1.15f && holiday != null
                    if (sideBySide) {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HomeElectricityCard(campus, Modifier.weight(1f).fillMaxHeight()) { onAcademic("electricity") }
                            holiday?.let { HomeHolidayCard(it, now.toLocalDate(), Modifier.weight(1f).fillMaxHeight()) { onAcademic("calendar") } }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            HomeElectricityCard(campus, Modifier.fillMaxWidth()) { onAcademic("electricity") }
                            holiday?.let { HomeHolidayCard(it, now.toLocalDate(), Modifier.fillMaxWidth()) { onAcademic("calendar") } }
                        }
                    }
                }
            }
            item {
                ContentCard(Modifier.fillMaxWidth(), onClick = { onAcademic("exams") }) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (nextExam == null) "考试安排" else "下一场考试", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            examDays?.let {
                                Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text(
                                        if (it == 0L) "今天" else "还有 $it 天",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        if (nextExam != null) {
                            Text(nextExam.course, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                            if (nextExam.time.isNotBlank()) Text(nextExam.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val place = listOf(nextExam.room, nextExam.seat.takeIf { it.isNotBlank() }?.let { "座位 $it" }.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
                            if (place.isNotBlank()) Text(place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("暂无待考科目", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                table?.let {
                    val time = java.time.Instant.ofEpochMilli(it.fetchedAt).atZone(SchoolZone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 4.dp, top = 2.dp)) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("课表更新于 $time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable private fun HomeCourseRow(block: CourseBlock, badge: String?, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit) {
    val range = Periods.range(block)
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (badge == null) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(50.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(range.substringBefore('–'), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(range.substringAfter('–'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(block.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOf(block.room, block.teacher).filter(String::isNotBlank).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            badge?.let {
                Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.primary) {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
    }
}

@Composable private fun HomeElectricityCard(campus: CampusState, modifier: Modifier, onClick: () -> Unit) {
    val low = campus.electric?.balance?.toBigDecimalOrNull()?.let { it < 10.toBigDecimal() } == true
    val balanceLabel = campus.dorm?.balanceLabel ?: "剩余电量"
    val unit = campus.dorm?.balanceUnit ?: "度"
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (low) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Bolt, null, Modifier.size(20.dp))
            Text(balanceLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            val electric = campus.electric
            if (electric != null) {
                Text("${electric.balance} $unit", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val checked = java.time.Instant.ofEpochMilli(electric.checkedAt).atZone(SchoolZone).format(DateTimeFormatter.ofPattern("HH:mm"))
                Text("${campus.dorm?.building?.name.orEmpty()} · $checked", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(if (campus.dorm == null) "还没选宿舍" else if (campus.error == null) "还没查到" else "查询失败", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable private fun HomeHolidayCard(event: CampusEvent, today: LocalDate, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.EventAvailable, null, Modifier.size(20.dp))
            Text("${event.title}还有", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, event.date)
            Text(if (days == 0L) "今天" else "$days 天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${event.date.monthValue}月${event.date.dayOfMonth}日", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun TimetableScreen(state: UiState, model: HelperViewModel, onCourse: (CourseBlock) -> Unit) {
    val showWeekend = state.showWeekend
    var weeksOpen by remember { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = state.week - 1, pageCount = { 30 })
    LaunchedEffect(state.week) { if (pager.currentPage != state.week - 1) pager.animateScrollToPage(state.week - 1, animationSpec = tween(AppMotion.EnterMillis, easing = AppMotion.Easing)) }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { model.changeWeek(it + 1) } }
    val isCurrentSemester = state.selectedSemester == state.currentSemester
    val currentWeekNumber = state.table?.let { weekOf(LocalDate.parse(it.monday), schoolToday()) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { model.changeWeek(state.week - 1) },
                enabled = state.week > 1,
                modifier = Modifier.size(36.dp).testTag("previousWeek")
            ) {
                Icon(Icons.AutoMirrored.Filled.NavigateBefore, "上一周", Modifier.size(20.dp))
            }
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { weeksOpen = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("week"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("第 ${state.week} 周", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Default.ArrowDropDown, "选择周次", Modifier.size(18.dp))
                }
                DropdownMenu(weeksOpen, { weeksOpen = false }) {
                    (1..30).forEach { week ->
                        DropdownMenuItem(
                            text = { Text(if (isCurrentSemester && week == currentWeekNumber) "第 $week 周 · 本周" else "第 $week 周") },
                            trailingIcon = if (week == state.week) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                            onClick = { weeksOpen = false; model.changeWeek(week) }
                        )
                    }
                }
            }
            IconButton(
                onClick = { model.changeWeek(state.week + 1) },
                enabled = state.week < 30,
                modifier = Modifier.size(36.dp).testTag("nextWeek")
            ) {
                Icon(Icons.AutoMirrored.Filled.NavigateNext, "下一周", Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            if (isCurrentSemester && currentWeekNumber != null && state.week != currentWeekNumber) {
                TextButton(
                    onClick = { model.changeWeek(currentWeekNumber) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("回到本周", style = MaterialTheme.typography.labelLarge)
                }
            } else if (!isCurrentSemester && state.currentSemester.isNotBlank()) {
                TextButton(
                    onClick = { model.select(state.currentSemester, TableKind.STUDENT) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("回到现在", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        if (state.table == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (state.busy) "正在获取课表…" else "此学期尚无离线课表，请刷新或校准周次。", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val table = state.table
            val courseColors = remember(table.courses) { CoursePalette.indices(table.courses.map { it.key }) }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).testTag("weekPager"), key = { it }) { page ->
                val blocks = remember(table.courses, page) { blocksFor(table.courses, page + 1) }
                Box(Modifier.fillMaxSize().then(if (page == pager.currentPage) Modifier else Modifier.clearAndSetSemantics { })) {
                    WeekGrid(blocks, courseColors, table.unitCount, LocalDate.parse(table.monday).plusWeeks(page.toLong()), showWeekend, onCourse)
                }
            }
        }
    }
}

@Composable private fun WeekGrid(blocks: List<CourseBlock>, courseColors: Map<String, Int>, units: Int, monday: LocalDate, weekend: Boolean, onCourse: (CourseBlock) -> Unit) {
    val days = if (weekend) 7 else 5
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val header = (40 * fontScale).dp
    val gutter = 30.dp
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)
    val todayTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.045f)
    val weekendTint = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.45f)
    val axisBg = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f)
    val colors = if (isSystemInDarkTheme()) CoursePalette.dark else CoursePalette.light
    val visibleBlocks = remember(blocks, days) { blocks.filter { it.day <= days } }
    val placements = remember(visibleBlocks) { placeBlocks(visibleBlocks) }
    val periods = remember(visibleBlocks) { maxOf(visibleBlocks.maxOfOrNull { it.end } ?: 0, 4) }
    val compactText = remember { PlatformTextStyle(includeFontPadding = false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val dayWidth = (maxWidth - gutter) / days
        val available = (maxHeight - header).coerceAtLeast(0.dp)
        val minRow = (52 * fontScale).dp
        val maxRow = 84.dp
        val fitted = available / periods
        val row = fitted.coerceIn(minRow, maxRow)
        val gridHeight = maxOf(row * periods, available)
        val drawnPeriods = kotlin.math.ceil(gridHeight.value / row.value).toInt().coerceAtLeast(periods)
        val labeledPeriods = drawnPeriods.coerceAtMost(maxOf(periods, units))

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(header),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(gutter).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text("节次", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                repeat(days) { day ->
                    val date = monday.plusDays(day.toLong())
                    val today = date == schoolToday()
                    Column(
                        Modifier.width(dayWidth).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "一二三四五六日"[day].toString(),
                            style = TextStyle(fontSize = 11.sp, fontWeight = if (today) FontWeight.SemiBold else FontWeight.Normal, platformStyle = compactText),
                            color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            "${date.dayOfMonth}",
                            style = TextStyle(fontSize = 14.sp, fontWeight = if (today) FontWeight.Bold else FontWeight.Normal, platformStyle = compactText),
                            color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            HorizontalDivider(color = lineColor)

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Box(Modifier.fillMaxWidth().height(gridHeight)) {
                    Canvas(Modifier.matchParentSize()) {
                        val g = gutter.toPx(); val step = row.toPx(); val dayW = dayWidth.toPx()
                        drawRect(color = axisBg, topLeft = androidx.compose.ui.geometry.Offset.Zero, size = androidx.compose.ui.geometry.Size(g, size.height))
                        for (d in 0 until days) {
                            val isToday = monday.plusDays(d.toLong()) == schoolToday()
                            val fill = when {
                                isToday -> todayTint
                                d >= 5 -> weekendTint
                                else -> null
                            }
                            if (fill != null) {
                                drawRect(
                                    color = fill,
                                    topLeft = androidx.compose.ui.geometry.Offset(g + d * dayW, 0f),
                                    size = androidx.compose.ui.geometry.Size(dayW, size.height)
                                )
                            }
                        }
                        for (i in 0..drawnPeriods) {
                            drawLine(lineColor, androidx.compose.ui.geometry.Offset(g, i * step), androidx.compose.ui.geometry.Offset(size.width, i * step), 1f)
                        }
                        for (i in 0..days) {
                            drawLine(lineColor, androidx.compose.ui.geometry.Offset(g + i * dayW, 0f), androidx.compose.ui.geometry.Offset(g + i * dayW, size.height), 1f)
                        }
                    }
                    repeat(labeledPeriods) { index ->
                        val startLabel = Periods.gridStartLabel(index + 1)
                        Column(
                            Modifier.offset(y = row * index).width(gutter).height(row).padding(top = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("${index + 1}", style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium, platformStyle = compactText))
                            Text(
                                startLabel.replace("/", "/\n"),
                                style = TextStyle(fontSize = 7.5.sp, lineHeight = 9.sp, platformStyle = compactText),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().testTag("periodStart${index + 1}").semantics { contentDescription = startLabel }
                            )
                        }
                    }
                    placements.forEach { placement ->
                        val block = placement.block
                        val color = colors[courseColors.getValue(block.key)]
                        val width = dayWidth / placement.count
                        val shortRoom = block.room.substringBefore('(').substringBefore('（')
                        Surface(
                            onClick = { onCourse(block) },
                            shape = RoundedCornerShape(12.dp),
                            color = color.background,
                            contentColor = color.content,
                            modifier = Modifier
                                .offset(x = gutter + dayWidth * (block.day - 1) + width * placement.lane + 2.dp, y = row * (block.start - 1) + 2.dp)
                                .width(width - 4.dp)
                                .height(row * (block.end - block.start + 1) - 4.dp)
                                .semantics { contentDescription = "${block.name}，${block.room}，${block.periodText}" }
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxHeight().width(3.dp).background(color.accent))
                                Column(
                                    Modifier.fillMaxSize().padding(start = 7.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        block.name,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false).padding(bottom = 4.dp)
                                    )
                                    if (shortRoom.isNotBlank()) {
                                        Text(
                                            shortRoom,
                                            fontSize = 9.sp,
                                            lineHeight = 12.sp,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (visibleBlocks.isEmpty()) {
                        Text(
                            "本周没有课程安排",
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = .95f))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private data class CampusEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CampusScreen(campus: CampusState, onSettings: () -> Unit, onOpen: (String) -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val electricSubtitle = campus.electric?.let {
        "剩 ${it.balance} ${campus.dorm?.balanceUnit ?: "度"}"
    } ?: "宿舍还剩多少电费"
    val groups = listOf(
        Triple("教务", 0, listOf(
            CampusEntry("exams", "考试安排", "时间、考场、座位号", Icons.Default.Assignment),
            CampusEntry("grades", "成绩", "每学期的课程成绩", Icons.Default.Assessment),
            CampusEntry("plan", "学业完成情况", "毕业要修多少学分", Icons.Default.School),
            CampusEntry("calendar", "校历", "什么时候放假？", Icons.Default.EventAvailable)
        )),
        Triple("生活", 1, listOf(
            CampusEntry("electricity", "电费", electricSubtitle, Icons.Default.Bolt),
            CampusEntry("netfee", "校园网充值", "续费、查套餐", Icons.Default.Wifi),
            CampusEntry("checkin", "签到", "按时点一下，别断签", Icons.Default.Verified),
            CampusEntry("leave", "请销假", "请假、销假都在这里", Icons.Default.Description)
        )),
        Triple("杂项", 2, listOf(
            CampusEntry("ehall", "一网通办", "更多校园线上服务", Icons.Default.Apps),
            CampusEntry("eams", "教务系统", "进入学校教务网页", Icons.Default.Language)
        ))
    )
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                title = { Text("校园", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        ResponsiveWidthBox(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("campus"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                groups.forEach { (title, tone, entries) ->
                    item(key = title) {
                        val iconBg = when (tone) {
                            0 -> MaterialTheme.colorScheme.primaryContainer
                            1 -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.tertiaryContainer
                        }
                        val iconFg = when (tone) {
                            0 -> MaterialTheme.colorScheme.onPrimaryContainer
                            1 -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onTertiaryContainer
                        }
                        GroupedSection(title = title) {
                            entries.forEach { entry ->
                                item { shape ->
                                    GroupedNavigationRow(
                                        shape = shape,
                                        title = entry.title,
                                        subtitle = entry.subtitle,
                                        icon = entry.icon,
                                        iconContainerColor = iconBg,
                                        iconTint = iconFg,
                                        onClick = { onOpen(entry.key) }
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable private fun ErrorCard(message: String) { Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) { Text(message, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer) } }
@Composable private fun DetailRow(icon: ImageVector, text: String) { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Text(text) } }
