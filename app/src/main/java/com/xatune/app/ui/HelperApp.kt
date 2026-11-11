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
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
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
    var confirm by remember(release.version) { mutableStateOf(false) }
    var linkError by remember(release.version) { mutableStateOf(false) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    if (!confirm) InAppNoticeBanner("发现新版本 ${release.version}", action = "查看更新", onAction = { confirm = true }, onDismiss = onDismiss)
    if (confirm) AlertDialog(onDismissRequest = onDismiss,
        title = { Text("更新至 ${release.version}") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("确认后将在浏览器中打开 GitHub Release 页面，由你下载并安装新版本。")
            if (notes.isNotBlank()) Text(notes)
            if (linkError) Text("没有可打开 GitHub 的浏览器，请安装浏览器后重试。", color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = {
            runCatching { uriHandler.openUri(release.url) }.onSuccess { onDismiss() }.onFailure { linkError = true }
        }) { Text("前往 GitHub 下载") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } })
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MainShell(state: UiState, model: HelperViewModel, onRelogin: () -> Unit, announcements: AnnouncementViewModel?) {
    var selectedAnnouncement by remember { mutableStateOf<com.xatune.app.data.ReceivedAnnouncement?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var academicScope by rememberSaveable { mutableStateOf<String?>(null) }
    var showCalibration by remember { mutableStateOf(false) }
    var course by remember { mutableStateOf<CourseBlock?>(null) }
    var editRequest by remember { mutableStateOf<CourseEditRequest?>(null) }
    var history by remember { mutableStateOf(false) }
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
    val backModifier = Modifier.predictiveBack(enabled = page != null || tab != 0) { if (page != null) page = parentPage(page) else tab = 0 }
    var previousPage by remember { mutableStateOf(page) }
    val direction = remember(page) { if (page == null || parentPage(previousPage) == page) -1 else 1 }
    SideEffect { previousPage = page }
    ScreenTransition(page, modifier = backModifier, direction = direction) { destination -> when (destination) {
        "settings" -> SettingsScreen(state, { page = null }, { showCalibration = true }, onRelogin, model::logout, { page = "notifications" }, { page = "widgets" }, model::showWeekend, model::showDiagnostics, campusState, { model.campus?.autoElectric(it) }, update, { model.checkUpdates(manual = true) }, onCheckinLocation = { page = "checkinLocation" }, onUpdateChannel = model::updateChannel, onUpdateServer = model::updateServer, onUpdateRepository = model::updateRepositoryUrl, onAutomaticUpdates = model::automaticUpdates, announcements = announcements, onOpenAnnouncement = { selectedAnnouncement = it }, onAnnouncementHistory = { page = "announcementHistory" })
        "announcementHistory" -> announcements?.let { AnnouncementHistoryScreen(it, { page = "settings" }, { selectedAnnouncement = it }) }
        "electricity" -> model.campus?.let { ElectricityScreen(it, { page = null }, { page = "charge" }) }
        "calendar" -> model.campus?.let { CalendarScreen(it, { page = null }) }
        "notifications" -> model.campus?.let { ReminderScreen(it, { page = "settings" }) }
        "checkinLocation" -> CheckinLocationPicker { page = "settings" }
        "widgets" -> WidgetSettings({ page = "settings" }, campusState)
        in CampusLinks.pages.keys -> CampusWeb(destination!!, { model.browserClosed(); page = parentPage(destination) })
        "exams", "grades", "plan" -> AcademicScreen(when (destination) { "exams" -> AcademicKind.EXAMS; "grades" -> AcademicKind.GRADES; else -> AcademicKind.PLAN }, state, model, { page = null }, onRelogin, { page = if (destination == "plan") "planWeb" else "eams" }, initialScope = academicScope)
        else -> Scaffold(topBar = {
        TopAppBar(title = { Text(titles[tab], fontWeight = FontWeight.SemiBold) }, actions = {
            if (tab == 1 && canEdit) {
                IconButton(onClick = { editRequest = CourseEditRequest(EditKind.ADD) }) { Icon(Icons.Default.Add, "添加课程") }
                IconButton(onClick = { history = true }) {
                    BadgedBox(badge = { if (state.pendingEdits.isNotEmpty()) Badge { Text(state.pendingEdits.size.toString()) } }) {
                        Icon(Icons.Default.History, "我的课表修改")
                    }
                }
            }
            if (tab == 1) IconButton(onClick = { showCalibration = true }, enabled = !state.busy) { Icon(Icons.Default.EditCalendar, "校准周次") }
            if (tab != 2) IconButton(onClick = model::refresh, enabled = !state.busy && !state.demo) { Icon(Icons.Default.Refresh, "刷新课表") }
            if (tab == 2) IconButton(onClick = { page = "settings" }) { Icon(Icons.Default.Settings, "设置") }
        })
    }, bottomBar = {
        NavigationBar { titles.forEachIndexed { index, title -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icons[index], null) }, label = { Text(title) }, modifier = Modifier.testTag("tab$index")) } }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
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
            AnimatedContent(targetState = tab, modifier = Modifier.weight(1f).fillMaxWidth(), transitionSpec = {
                val sign = if (targetState > initialState) 1 else -1
                (fadeIn(tween(AppMotion.EnterMillis)) + slideInHorizontally(tween(AppMotion.EnterMillis, easing = AppMotion.Easing)) { sign * it / 12 })
                    .togetherWith(fadeOut(tween(AppMotion.ExitMillis)))
                    .using(SizeTransform(clip = false))
            }, label = "mainTabs") { activeTab -> when (activeTab) {
                0 -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = state.busy, onRefresh = model::refresh) {
                    HomeScreen(state, { tab = 1 }, { course = it }, { model.campus?.dismissError(); page = it }, campusState)
                }
                1 -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = state.busy, onRefresh = model::refresh) {
                    TimetableScreen(state, model, { course = it })
                }
                else -> CampusScreen { model.campus?.dismissError(); page = it }
            } }
        }
    } }
    }
    val reminderPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { _ -> model.resume() }
    val reminderContext = androidx.compose.ui.platform.LocalContext.current
    val offerClasses = page == null && tab == 1 && !campusState.promptedClasses
    val offerExams = page == "exams" && !campusState.promptedExams
    if (!state.demo && campusState.account == state.account && !campusState.busy && (offerClasses || offerExams)) {
        AlertDialog(onDismissRequest = { model.campus?.acknowledgeReminders(offerClasses) },
            title = { Text(if (offerClasses) "上课与考试提醒" else "考试提醒") },
            text = { Text("使用本机缓存安排未来七天的提醒；默认提前 30 分钟，考试前一天 21:00、当天 08:00。可在提醒设置中调整。") },
            confirmButton = { TextButton(onClick = {
                model.campus?.acknowledgeReminders(offerClasses, enable = true)
                if (android.os.Build.VERSION.SDK_INT >= 33 && !com.xatune.app.platform.SystemCoordinator.notificationsAllowed(reminderContext))
                    reminderPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("开启提醒") } },
            dismissButton = { TextButton(onClick = { model.campus?.acknowledgeReminders(offerClasses) }) { Text("先不用") } })
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
                Text(selected.name, style = MaterialTheme.typography.headlineSmall)
                DetailRow(Icons.Default.Schedule, "周${"一二三四五六日"[selected.day - 1]} · ${selected.periodText} · ${Periods.range(selected)}")
                DetailRow(Icons.Default.LocationOn, selected.room.ifEmpty { "未提供教室" })
                DetailRow(Icons.Default.Person, selected.teacher.ifEmpty { "未提供教师" })
                DetailRow(Icons.Default.DateRange, "上课周次：${selected.weeks.joinToString("、")}")
                if (canEdit) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { editRequest = CourseEditRequest(EditKind.MOVE, selected); course = null }) { Text("修改课程") }
                    TextButton(onClick = { editRequest = CourseEditRequest(EditKind.REMOVE, selected); course = null }) { Text("移除课程") }
                }
                FilledTonalButton(onClick = { course = null }) { Text("关闭详情") }
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
        AlertDialog(onDismissRequest = { showCalibration = false }, title = { Text("校准周次") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(onClick = { weekMenu = true }, modifier = Modifier.testTag("actualWeek")) { Text("现在实际是第 $actualWeek 周") }
                    DropdownMenu(weekMenu, { weekMenu = false }) { (1..30).forEach { value ->
                        DropdownMenuItem(text = { Text("第 $value 周") }, onClick = { actualWeek = value; date = mondayForWeek(value).toString(); weekMenu = false })
                    } }
                }
                TextButton(onClick = { showCalibration = false; model.calibrateWeek(actualWeek) }) { Text("按这个周次校正") }
                OutlinedTextField(date, { date = it }, label = { Text("第一周周一") }, supportingText = { Text("日期格式 yyyy-MM-dd；仅修改本地周次。") }, singleLine = true)
            }
        }, confirmButton = { TextButton(onClick = { showCalibration = false; model.calibrate(date) }) { Text("保存") } }, dismissButton = { TextButton(onClick = { showCalibration = false }) { Text("取消") } })
    }
}

@Composable private fun HomeScreen(state: UiState, onTable: () -> Unit, onCourse: (CourseBlock) -> Unit, onAcademic: (String) -> Unit, campus: CampusState) {
    var now by remember { mutableStateOf(ZonedDateTime.now(SchoolZone)) }
    LaunchedEffect(Unit) { while (true) { now = ZonedDateTime.now(SchoolZone); delay(30_000) } }
    val table = state.table
    val week = table?.let { weekOf(LocalDate.parse(it.monday), now.toLocalDate()) } ?: state.week
    val current = state.selectedSemester == state.currentSemester
    val schedule = homeSchedule(table, current, now.toLocalDate(), now.toLocalTime())
    val nextExam = state.academic[academicKey(AcademicKind.EXAMS, state.currentSemester)]?.data?.exams
        ?.filter { it.date(examAnchor(state))?.let { day -> day >= now.toLocalDate() } == true }
        ?.sortedWith(compareBy({ it.date(examAnchor(state)) }, { it.time }))?.firstOrNull()
    val examDays = nextExam?.date(examAnchor(state))?.let { java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), it) }
    val status = buildList {
        if (current && week > 0) add("第 $week 周")
        add(if (schedule.remaining == 0) "今天没课了" else "今天还有 ${schedule.remaining} 节课")
        examDays?.let { add(if (it == 0L) "今天有考试" else "下次考试还有 $it 天") }
    }.joinToString(" · ")
    val holiday = nextCampusEvent(campus.calendar, now.toLocalDate())
    LazyColumn(Modifier.fillMaxSize().testTag("home"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(now.format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(campusGreeting(now.toLocalTime()), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("homeGreeting"))
                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            ContentCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Text(schedule.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("周${"一二三四五六日"[schedule.date.dayOfWeek.value - 1]}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (schedule.courses.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(Icons.Default.WbSunny)
                        Text("明天没有课程安排", style = MaterialTheme.typography.titleMedium)
                        Text("本周的其他课程可在课程表中查看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    schedule.courses.forEach { block -> HomeCourseRow(block, when { !schedule.tomorrow && block.id == schedule.active -> "正在上"; !schedule.tomorrow && block.id == schedule.next -> "下一节"; else -> null }) { onCourse(block) } }
                    TextButton(onClick = onTable, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("查看完整课表", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                        Icon(Icons.AutoMirrored.Filled.NavigateNext, null)
                    }
                }
            }
        }
        item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val sideBySide = maxWidth >= 320.dp && LocalDensity.current.fontScale <= 1.1f && holiday != null
                if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeElectricityCard(campus, Modifier.weight(1f)) { onAcademic("electricity") }
                    holiday?.let { HomeHolidayCard(it, now.toLocalDate(), Modifier.weight(1f)) { onAcademic("calendar") } }
                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeElectricityCard(campus, Modifier.fillMaxWidth()) { onAcademic("electricity") }
                    holiday?.let { HomeHolidayCard(it, now.toLocalDate(), Modifier.fillMaxWidth()) { onAcademic("calendar") } }
                }
            }
        }
        item {
            ContentCard(Modifier.fillMaxWidth(), onClick = { onAcademic("exams") }) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (nextExam == null) "考试安排" else "下一场考试", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        examDays?.let { Text(if (it == 0L) "今天" else "还有 $it 天", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    }
                    if (nextExam != null) {
                        Text(nextExam.course, style = MaterialTheme.typography.titleLarge)
                        if (nextExam.time.isNotBlank()) Text(nextExam.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val place = listOf(nextExam.room, nextExam.seat.takeIf { it.isNotBlank() }?.let { "座位 $it" }.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
                        if (place.isNotBlank()) Text(place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("暂无待考科目", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("查看全部考试", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item { table?.let {
            val time = java.time.Instant.ofEpochMilli(it.fetchedAt).atZone(SchoolZone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            Text("课表更新于 $time", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}

@Composable private fun HomeCourseRow(block: CourseBlock, badge: String?, onClick: () -> Unit) {
    val range = Periods.range(block)
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = if (badge == null) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.width(58.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(range.substringBefore('–'), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(range.substringAfter('–'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(block.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (block.room.isNotBlank()) Text(block.room, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (block.teacher.isNotBlank()) Text(block.teacher, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                badge?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable private fun HomeElectricityCard(campus: CampusState, modifier: Modifier, onClick: () -> Unit) {
    val low = campus.electric?.balance?.toBigDecimalOrNull()?.let { it < 10.toBigDecimal() } == true
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = if (low) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Bolt, null, Modifier.size(22.dp))
            Text("剩余电量", style = MaterialTheme.typography.titleMedium)
            val electric = campus.electric
            if (electric != null) {
                Text("${electric.balance} 度", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                val checked = java.time.Instant.ofEpochMilli(electric.checkedAt).atZone(SchoolZone).format(DateTimeFormatter.ofPattern("HH:mm"))
                Text("${campus.dorm?.building?.name.orEmpty()} · $checked", style = MaterialTheme.typography.bodySmall)
            } else Text(if (campus.dorm == null) "还没选宿舍" else if (campus.error == null) "还没查到" else "查询失败", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable private fun HomeHolidayCard(event: CampusEvent, today: LocalDate, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.EventAvailable, null, Modifier.size(22.dp))
            Text("${event.title}还有", style = MaterialTheme.typography.titleMedium)
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, event.date)
            Text(if (days == 0L) "今天" else "$days 天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("${event.date.monthValue}月${event.date.dayOfMonth}日", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun TimetableScreen(state: UiState, model: HelperViewModel, onCourse: (CourseBlock) -> Unit) {
    var semestersOpen by remember { mutableStateOf(false) }
    var kindOpen by remember { mutableStateOf(false) }
    val showWeekend = state.showWeekend
    var weeksOpen by remember { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = state.week - 1, pageCount = { 30 })
    LaunchedEffect(state.week) { if (pager.currentPage != state.week - 1) pager.animateScrollToPage(state.week - 1, animationSpec = tween(AppMotion.EnterMillis, easing = AppMotion.Easing)) }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { model.changeWeek(it + 1) } }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { semestersOpen = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(state.semesters.firstOrNull { it.id == state.selectedSemester }?.name ?: "选择学期", maxLines = 1, overflow = TextOverflow.Ellipsis); Icon(Icons.Default.ArrowDropDown, null) }
                DropdownMenu(expanded = semestersOpen, onDismissRequest = { semestersOpen = false }) { state.semesters.forEach { semester -> DropdownMenuItem(text = { Text(semester.name) }, onClick = { semestersOpen = false; model.select(semester.id, state.kind) }) } }
            }
            Box {
                TextButton(onClick = { kindOpen = true }, enabled = !state.busy && !state.demo) { Text(state.kind.title); Icon(Icons.Default.ArrowDropDown, null) }
                DropdownMenu(expanded = kindOpen, onDismissRequest = { kindOpen = false }) {
                    DropdownMenuItem(text = { Text("返回当前学生课表") }, onClick = { kindOpen = false; model.select(state.currentSemester, TableKind.STUDENT) })
                    HorizontalDivider()
                    TableKind.entries.forEach { kind -> DropdownMenuItem(text = { Text(kind.title) }, onClick = { kindOpen = false; model.select(state.selectedSemester, kind) }) } }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.changeWeek(state.week - 1) }, enabled = state.week > 1, modifier = Modifier.testTag("previousWeek")) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, "上一周") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    TextButton(onClick = { weeksOpen = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("week")) {
                        Text("第 ${state.week} 周", style = MaterialTheme.typography.titleMedium)
                        Icon(Icons.Default.ArrowDropDown, "选择周次")
                    }
                    DropdownMenu(weeksOpen, { weeksOpen = false }) {
                        (1..30).forEach { week -> DropdownMenuItem(text = { Text("第 $week 周") }, onClick = { weeksOpen = false; model.changeWeek(week) }) }
                    }
                }
                state.table?.let { table -> val monday = LocalDate.parse(table.monday).plusWeeks((state.week - 1).toLong()); Text("${monday.monthValue}.${monday.dayOfMonth} – ${monday.plusDays(6).monthValue}.${monday.plusDays(6).dayOfMonth}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            IconButton(onClick = { model.changeWeek(state.week + 1) }, enabled = state.week < 30, modifier = Modifier.testTag("nextWeek")) { Icon(Icons.AutoMirrored.Filled.NavigateNext, "下一周") }
            TextButton(onClick = { state.table?.let { model.changeWeek(weekOf(LocalDate.parse(it.monday), schoolToday())) } }) { Text("本周") }
            IconToggleButton(checked = showWeekend, onCheckedChange = model::showWeekend) { Icon(Icons.Default.ViewWeek, if (showWeekend) "隐藏周末" else "显示周末") }
        }
        } }
        if (state.table == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(if (state.busy) "正在获取课表…" else "此学期尚无离线课表，请刷新或校准周次。", Modifier.padding(24.dp)) }
        else {
            val table = state.table
            val courseColors = remember(table.courses) { CoursePalette.indices(table.courses.map { it.key }) }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).testTag("weekPager"), key = { it }) { page ->
                val blocks = remember(table.courses, page) { blocksFor(table.courses, page + 1) }
                Box(Modifier.fillMaxSize().then(if (page == pager.currentPage) Modifier else Modifier.clearAndSetSemantics { })) {
                    WeekGrid(blocks, courseColors, table.unitCount, LocalDate.parse(table.monday).plusWeeks(page.toLong()), showWeekend, onCourse)
                }
            }
            CacheLabel(table, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

@Composable private fun WeekGrid(blocks: List<CourseBlock>, courseColors: Map<String, Int>, units: Int, monday: LocalDate, weekend: Boolean, onCourse: (CourseBlock) -> Unit) {
    val days = if (weekend) 7 else 5
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val row = (68 * fontScale).dp
    val header = (44 * fontScale).dp
    val gutter = 28.dp
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)
    val colors = if (isSystemInDarkTheme()) CoursePalette.dark else CoursePalette.light
    val placements = remember(blocks) { placeBlocks(blocks) }
    BoxWithConstraints(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
        val dayWidth = (maxWidth - gutter) / days
        Box(Modifier.fillMaxWidth().height(header + row * units)) {
            Canvas(Modifier.matchParentSize()) {
                val g = gutter.toPx(); val h = header.toPx(); val step = row.toPx(); val day = dayWidth.toPx()
                for (i in 0..units) drawLine(lineColor, androidx.compose.ui.geometry.Offset(g, h + i * step), androidx.compose.ui.geometry.Offset(size.width, h + i * step), 1f)
                for (i in 0..days) drawLine(lineColor, androidx.compose.ui.geometry.Offset(g + i * day, 0f), androidx.compose.ui.geometry.Offset(g + i * day, size.height), 1f)
            }
            repeat(days) { day ->
                val date = monday.plusDays(day.toLong())
                val today = date == schoolToday()
                Column(Modifier.offset(x = gutter + dayWidth * day).width(dayWidth).height(header), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("周${"一二三四五六日"[day]}", fontSize = 11.sp, color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${date.dayOfMonth}", style = MaterialTheme.typography.labelLarge, fontWeight = if (today) FontWeight.Bold else FontWeight.Normal)
                }
            }
            repeat(units) { index ->
                val startLabel = Periods.gridStartLabel(index + 1)
                Column(Modifier.offset(y = header + row * index).width(gutter).height(row), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("${index + 1}", fontSize = 12.sp)
                Text(startLabel.replace("/", "/\n"), fontSize = 7.sp, lineHeight = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("periodStart${index + 1}").semantics { contentDescription = startLabel })
            } }
            placements.filter { it.block.day <= days }.forEach { placement ->
                val block = placement.block
                val color = colors[courseColors.getValue(block.key)]
                val width = dayWidth / placement.count
                Surface(onClick = { onCourse(block) }, shape = RoundedCornerShape(12.dp), color = color.background, contentColor = color.content,
                    modifier = Modifier.offset(x = gutter + dayWidth * (block.day - 1) + width * placement.lane + 2.dp, y = header + row * (block.start - 1) + 2.dp)
                        .width(width - 4.dp).height(row * (block.end - block.start + 1) - 4.dp)
                        .semantics { contentDescription = "${block.name}，${block.room}，${block.periodText}" }) {
                    Box {
                        Box(Modifier.fillMaxHeight().width(3.dp).background(color.accent))
                        Column(Modifier.padding(start = 7.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(block.name, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, maxLines = 5, overflow = TextOverflow.Ellipsis)
                            Text(block.room.substringBefore('(').substringBefore('（'), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (blocks.none { it.day <= days }) Text("本周没有课程安排", Modifier.align(Alignment.TopCenter).padding(top = 64.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .95f)).padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun CampusScreen(onOpen: (String) -> Unit) {
    val groups = listOf(
        "教务" to listOf(Triple("exams", "考试安排", "时间、考场、座位号"), Triple("grades", "成绩", "每学期的课程成绩"), Triple("plan", "学业完成情况", "毕业要修多少学分"), Triple("calendar", "校历", "什么时候放假？")),
        "生活" to listOf(Triple("electricity", "电费", "宿舍还剩多少电费"), Triple("netfee", "校园网充值", "续费、查套餐"), Triple("checkin", "签到", "按时点一下，别断签"), Triple("leave", "请销假", "请假、销假都在这里")),
        "杂项" to listOf(Triple("ehall", "一网通办", "更多校园线上服务"), Triple("eams", "教务系统", "进入学校教务网页"))
    )
    LazyColumn(Modifier.fillMaxSize().testTag("campus"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        groups.forEach { (title, entries) ->
            item(key = title) { SectionHeading(title) }
            entries.forEach { (key, label, detail) ->
                item(key = key) { ServiceRow(label, detail, when (key) {
                    "exams" -> Icons.Default.Assignment; "grades" -> Icons.Default.Assessment; "plan" -> Icons.Default.School; "calendar" -> Icons.Default.EventAvailable;
                    "electricity" -> Icons.Default.Bolt; "netfee" -> Icons.Default.Wifi; "checkin" -> Icons.Default.Verified; "leave" -> Icons.Default.Description; "eams" -> Icons.Default.Language; else -> Icons.Default.Apps
                }) { onOpen(key) } }
            }
        }
    }
}
@Composable private fun ErrorCard(message: String) { Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) { Text(message, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer) } }
@Composable private fun DetailRow(icon: ImageVector, text: String) { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Text(text) } }
@Composable private fun CacheLabel(table: Timetable?, modifier: Modifier = Modifier) {
    table?.let { val time = java.time.Instant.ofEpochMilli(it.fetchedAt).atZone(SchoolZone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")); Text("更新于 $time · 可离线查看", modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
