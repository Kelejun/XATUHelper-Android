package com.xatune.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xatune.app.BuildConfig
import com.xatune.app.HelperApplication
import com.xatune.app.R
import com.xatune.app.data.ReceivedAnnouncement
import com.xatune.app.data.UpdateChannel
import com.xatune.app.data.UpdateServer
import com.xatune.app.model.*
import com.xatune.app.platform.CheckinLocationPreferences
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val settingsQqGroupNumber = "934935585"
private val settingsQqGroupJoinUrl = "https://qm.qq.com/q/k1mGynJlrW"

private fun settingsTime(at: Long): String =
    Instant.ofEpochMilli(at).atZone(SchoolZone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onCalibrate: () -> Unit,
    onRelogin: () -> Unit,
    onLogout: () -> Unit,
    onReminders: () -> Unit = {},
    onWidgets: () -> Unit = {},
    onWeekend: (Boolean) -> Unit = {},
    onDiagnostics: (Boolean) -> Unit = {},
    campus: CampusState = CampusState(),
    onAutoElectric: (Boolean) -> Unit = {},
    update: UpdateUi = UpdateUi(),
    onCheckUpdates: () -> Unit = {},
    onCheckinLocation: () -> Unit = {},
    onUpdateChannel: (UpdateChannel) -> Unit = {},
    onUpdateServer: (UpdateServer) -> Unit = {},
    onUpdateRepository: (String) -> Unit = {},
    onAutomaticUpdates: (Boolean) -> Unit = {},
    announcements: AnnouncementViewModel? = null,
    onOpenAnnouncement: (ReceivedAnnouncement) -> Unit = {},
    onAnnouncementHistory: () -> Unit = {},
    onReducedInformationUpdates: (Boolean) -> Unit = {},
    onTimetableSettings: (() -> Unit)? = null,
    onInformationUpdates: (() -> Unit)? = null,
    onAppUpdates: (() -> Unit)? = null,
    onLoadProfile: (() -> Unit)? = null
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val location by rememberCheckinLocationSettings()
    val locationPreferences = remember(context) { CheckinLocationPreferences(context) }
    val statistics = remember(context) { (context.applicationContext as HelperApplication).statistics }
    var statisticsEnabled by remember { mutableStateOf(statistics.enabled) }
    var versionTaps by remember { mutableIntStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val studentName = state.academic[academicKey(AcademicKind.PLAN, "all")]?.data?.plan?.studentName?.trim().orEmpty()

    LaunchedEffect(state.account, studentName) {
        if (studentName.isEmpty() && state.account.isNotBlank() && !state.demo) {
            onLoadProfile?.invoke()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        ResponsiveWidthBox(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("settings"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ) {
                                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.AccountCircle, null, Modifier.size(30.dp))
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        if (state.demo) "示例同学" else studentName.ifEmpty { "未获取姓名" },
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    SelectionContainer {
                                        Text(
                                            "学号：${if (state.demo) "—" else state.account.ifEmpty { "—" }}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
                            OutlinedButton(
                                onClick = { confirmLogout = true },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().testTag("logout"),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("退出登录")
                            }
                        }
                    }
                }

                announcements?.let { center ->
                    item { AnnouncementSettingsGroup(center, onOpenAnnouncement, onAnnouncementHistory) }
                }

                item {
                    GroupedSection(title = "通用") {
                        if (onTimetableSettings != null) {
                            item { shape ->
                                GroupedNavigationRow(
                                    shape = shape,
                                    title = "课程表",
                                    subtitle = if (state.showWeekend) "显示周末 · 校准周次" else "隐藏周末 · 校准周次",
                                    icon = Icons.Default.CalendarMonth,
                                    onClick = onTimetableSettings
                                )
                            }
                        } else {
                            item { shape ->
                                GroupedNavigationRow(
                                    shape = shape,
                                    title = "校准第一周日期",
                                    subtitle = "按当前教学周修正本地课表",
                                    icon = Icons.Default.EditCalendar,
                                    onClick = onCalibrate
                                )
                            }
                            item { shape ->
                                GroupedToggleRow(
                                    shape = shape,
                                    title = "显示周末",
                                    subtitle = "在周视图中显示周六和周日",
                                    checked = state.showWeekend,
                                    onCheckedChange = onWeekend,
                                    switchModifier = Modifier.testTag("weekendSetting")
                                )
                            }
                        }

                        if (!state.demo) {
                            if (onInformationUpdates != null) {
                                item { shape ->
                                    GroupedNavigationRow(
                                        shape = shape,
                                        title = "信息更新",
                                        subtitle = "电费自动刷新与教务更新频率",
                                        icon = Icons.Default.Sync,
                                        onClick = onInformationUpdates
                                    )
                                }
                            } else {
                                item { shape ->
                                    GroupedToggleRow(
                                        shape = shape,
                                        title = "每日自动刷新电费余额",
                                        subtitle = "根据已绑定宿舍更新本机记录",
                                        checked = campus.autoElectric,
                                        onCheckedChange = onAutoElectric,
                                        enabled = !campus.busy
                                    )
                                }
                                item { shape ->
                                    GroupedToggleRow(
                                        shape = shape,
                                        title = "降低教务信息更新频率",
                                        checked = state.reducedInformationUpdates,
                                        onCheckedChange = onReducedInformationUpdates,
                                        switchModifier = Modifier.testTag("reduceInformationUpdates")
                                    )
                                }
                            }

                            item { shape ->
                                GroupedNavigationRow(
                                    shape = shape,
                                    title = "提醒",
                                    subtitle = "上课、考试与系统通知权限",
                                    icon = Icons.Default.Notifications,
                                    onClick = onReminders
                                )
                            }
                        }

                        item { shape ->
                            GroupedNavigationRow(
                                shape = shape,
                                title = "桌面小组件",
                                subtitle = "在桌面查看课程、电费和假期",
                                icon = Icons.Default.Widgets,
                                onClick = onWidgets
                            )
                        }
                    }
                }

                item {
                    GroupedSection(title = "应用") {
                        if (onAppUpdates != null) {
                            item { shape ->
                                GroupedNavigationRow(
                                    shape = shape,
                                    title = "应用更新",
                                    subtitle = update.settings.channel.label,
                                    value = if (update.offer != null) "有新版本" else null,
                                    icon = Icons.Default.SystemUpdate,
                                    onClick = onAppUpdates
                                )
                            }
                        }
                        item { shape ->
                            GroupedValueRow(
                                shape = shape,
                                title = "版本",
                                value = "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_COMMIT})",
                                icon = Icons.Default.Info,
                                modifier = Modifier.testTag("appVersion"),
                                onClick = {
                                    versionTaps++
                                    if (versionTaps >= 5) {
                                        locationPreferences.reveal()
                                        versionTaps = 0
                                    }
                                }
                            )
                        }
                        item { shape ->
                            GroupedNavigationRow(
                                shape = shape,
                                title = "隐私政策",
                                icon = Icons.Default.PrivacyTip,
                                onClick = { privacy = true }
                            )
                        }
                        item { shape ->
                            GroupedToggleRow(
                                shape = shape,
                                title = "参与使用统计",
                                checked = statisticsEnabled,
                                onCheckedChange = {
                                    statistics.enabled = it
                                    statisticsEnabled = it
                                },
                                icon = Icons.Default.BarChart,
                                switchModifier = Modifier.testTag("dailyStatisticsSetting")
                            )
                        }
                        item { shape ->
                            GroupedNavigationRow(
                                shape = shape,
                                title = "QQ 讨论群",
                                subtitle = "群号：$settingsQqGroupNumber",
                                icon = ImageVector.vectorResource(R.drawable.ic_qq_outline),
                                modifier = Modifier.testTag("qqDiscussionGroup"),
                                selectableDetail = true,
                                onClick = { uriHandler.openUri(settingsQqGroupJoinUrl) }
                            )
                        }
                        item { shape ->
                            GroupedNavigationRow(
                                shape = shape,
                                title = "GitHub 仓库",
                                subtitle = "Kelejun/XATUHelper-Android",
                                icon = Icons.Default.Code,
                                modifier = Modifier.testTag("githubRepository"),
                                onClick = { uriHandler.openUri("https://github.com/Kelejun/XATUHelper-Android") }
                            )
                        }
                        item { shape ->
                            GroupedValueRow(
                                shape = shape,
                                title = "备案号",
                                value = "粤ICP备2026181989号-1A",
                                icon = Icons.Default.VerifiedUser
                            )
                        }
                    }
                }

                if (onAppUpdates == null) {
                    item {
                        ContentCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp)) {
                                UpdateSettingsPanel(update, onUpdateChannel, onUpdateServer, onUpdateRepository, onAutomaticUpdates, onCheckUpdates)
                            }
                        }
                    }
                }

                item {
                    GroupedSection(
                        title = "诊断",
                        footer = "出现解析异常时，可开启并导出诊断数据给开发者排查。"
                    ) {
                        if (BuildConfig.WEB_DIAGNOSTICS) {
                            item { shape ->
                                Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                    Box(Modifier.padding(14.dp)) { WebDiagnosticExport() }
                                }
                            }
                        }
                        item { shape ->
                            GroupedToggleRow(
                                shape = shape,
                                title = "显示诊断数据",
                                subtitle = "查看本机缓存状态与版本信息",
                                checked = state.showDiagnostics,
                                onCheckedChange = onDiagnostics,
                                icon = Icons.Default.BugReport,
                                switchModifier = Modifier.testTag("diagnosticsSetting")
                            )
                        }
                        if (state.showDiagnostics) {
                            item { shape ->
                                Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            diagnosticSummary(state),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.testTag("diagnostics")
                                        )
                                        DiagnosticExport("设置") { diagnosticSummary(state) }
                                    }
                                }
                            }
                        }
                    }
                }

                if (location.revealed) {
                    item {
                        GroupedSection(
                            title = "签到测试",
                            footer = "先选择位置，再开启传入定位。所选坐标保存在本机，仅用于签到网页。"
                        ) {
                            item { shape ->
                                GroupedToggleRow(
                                    shape = shape,
                                    title = "传入定位",
                                    subtitle = "开启后签到网页使用所选坐标，关闭恢复实际定位",
                                    checked = location.enabled,
                                    onCheckedChange = locationPreferences::enable,
                                    enabled = location.coordinate != null || location.enabled,
                                    switchModifier = Modifier.testTag("checkinLocationEnabled")
                                )
                            }
                            item { shape ->
                                GroupedNavigationRow(
                                    shape = shape,
                                    title = "选择位置",
                                    subtitle = location.coordinate?.let {
                                        "%.6f, %.6f".format(Locale.ROOT, it.latitude, it.longitude)
                                    } ?: "未选择",
                                    icon = Icons.Default.LocationOn,
                                    modifier = Modifier.testTag("checkinLocationPicker"),
                                    onClick = onCheckinLocation
                                )
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (privacy) PrivacyPolicyDialog { privacy = false }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            icon = { Icon(Icons.Default.Logout, null) },
            title = { Text("退出登录？") },
            text = { Text("将清除此设备上的账号、登录状态、课表和教务缓存。") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("确认退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AnnouncementSettingsGroup(
    center: AnnouncementViewModel,
    onOpen: (ReceivedAnnouncement) -> Unit,
    onHistory: () -> Unit
) {
    val records by center.history.collectAsStateWithLifecycle()
    val latest = records.maxByOrNull { it.announcement.publishedAt }
    GroupedSection(title = "公告") {
        item { shape ->
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().clickable(enabled = latest != null) { latest?.let(onOpen) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Campaign, null, Modifier.size(20.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("最新公告", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(latest?.announcement?.title ?: "暂无公告", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        item { shape ->
            GroupedNavigationRow(
                shape = shape,
                title = "历史公告",
                value = "${records.size} 条",
                icon = Icons.Default.History,
                onClick = onHistory
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableSettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onWeekend: (Boolean) -> Unit,
    onCalibrate: () -> Unit,
    onReminders: (() -> Unit)? = null
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("课程表设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        ResponsiveWidthBox(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("timetableSettings"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    GroupedSection(title = "显示") {
                        item { shape ->
                            GroupedToggleRow(
                                shape = shape,
                                title = "显示周末",
                                subtitle = "在周视图中显示周六和周日",
                                checked = state.showWeekend,
                                onCheckedChange = onWeekend,
                                icon = Icons.Default.ViewWeek,
                                switchModifier = Modifier.testTag("weekendSetting")
                            )
                        }
                        state.table?.let { table ->
                            item { shape ->
                                GroupedValueRow(
                                    shape = shape,
                                    title = "上次更新",
                                    value = settingsTime(table.fetchedAt),
                                    icon = Icons.Default.Update
                                )
                            }
                        }
                    }
                }
                if (!state.demo && onReminders != null) {
                    item {
                        GroupedSection(title = "通知") {
                            item { shape ->
                                GroupedNavigationRow(
                                    shape = shape,
                                    title = "提醒",
                                    subtitle = "设置上课与考试前通知",
                                    icon = Icons.Default.Notifications,
                                    onClick = onReminders
                                )
                            }
                        }
                    }
                }
                item {
                    GroupedSection(
                        title = "周次校正",
                        footer = "学校校历如果和默认的不一致，可在此修正本地第一周日期。"
                    ) {
                        item { shape ->
                            GroupedNavigationRow(
                                shape = shape,
                                title = "校准第一周日期",
                                subtitle = state.table?.monday?.let { "当前第一周周一：$it" } ?: "按当前教学周修正本地课表",
                                icon = Icons.Default.EditCalendar,
                                onClick = onCalibrate
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InformationUpdatesScreen(
    state: UiState,
    campus: CampusState,
    onBack: () -> Unit,
    onAutoElectric: (Boolean) -> Unit,
    onReducedInformationUpdates: (Boolean) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("信息更新", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        ResponsiveWidthBox(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("informationUpdates"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    GroupedSection(title = "电费") {
                        item { shape ->
                            GroupedToggleRow(
                                shape = shape,
                                title = "每日自动刷新电费余额",
                                subtitle = "根据已绑定宿舍更新本机记录",
                                checked = campus.autoElectric,
                                onCheckedChange = onAutoElectric,
                                icon = Icons.Default.Bolt,
                                enabled = !campus.busy
                            )
                        }
                    }
                }
                item {
                    GroupedSection(
                        title = "教务信息",
                        footer = "开启后降低课表、成绩、考试和培养方案的后台自动刷新频率。"
                    ) {
                        item { shape ->
                            GroupedToggleRow(
                                shape = shape,
                                title = "降低教务信息更新频率",
                                checked = state.reducedInformationUpdates,
                                onCheckedChange = onReducedInformationUpdates,
                                icon = Icons.Default.School,
                                switchModifier = Modifier.testTag("reduceInformationUpdates")
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdatesScreen(
    update: UpdateUi,
    onBack: () -> Unit,
    onUpdateChannel: (UpdateChannel) -> Unit,
    onUpdateServer: (UpdateServer) -> Unit,
    onUpdateRepository: (String) -> Unit,
    onAutomaticUpdates: (Boolean) -> Unit,
    onCheckUpdates: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("应用更新", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        ResponsiveWidthBox(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("appUpdates"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    ContentCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                IconTile(Icons.Default.SystemUpdate, prominent = true)
                                Column {
                                    Text(
                                        "XATune ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_COMMIT})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "西安工业大学校园助手 · Android",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            UpdateSettingsPanel(
                                update = update,
                                onChannel = onUpdateChannel,
                                onServer = onUpdateServer,
                                onRepository = onUpdateRepository,
                                onAutomatic = onAutomaticUpdates,
                                onCheck = onCheckUpdates
                            )
                        }
                    }
                }
            }
        }
    }
}
