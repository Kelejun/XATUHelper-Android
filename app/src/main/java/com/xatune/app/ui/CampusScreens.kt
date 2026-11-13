package com.xatune.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.xatune.app.model.*
import com.xatune.app.platform.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private fun moneyTime(at: Long) = Instant.ofEpochMilli(at).atZone(SchoolZone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CampusFrame(title: String, state: CampusState, onBack: () -> Unit, onRefresh: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回校园") } }, actions = { if (onRefresh != null) IconButton(onClick = onRefresh, enabled = !state.busy) { Icon(Icons.Default.Refresh, "刷新$title") } }) }) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            LoadingProgress(state.busy)
            Reveal(state.error != null) { state.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
            content()
        }
    }
}
@Composable fun ElectricityScreen(controller: CampusController, onBack: () -> Unit, onWeb: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(state.electric?.checkedAt) { if (state.electric != null) listState.animateScrollToItem(0) }
    var confirmUnbind by remember { mutableStateOf(false) }
    var usageChart by rememberSaveable { mutableStateOf(true) }
    var roomSearch by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.building?.id) { roomSearch = "" }
    CampusFrame("电费", state, onBack, if (state.dorm != null) controller::refreshElectricity else null) {
        LazyColumn(state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize().testTag("electricity")) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = if (state.electric?.balance?.toBigDecimalOrNull()?.let { it < 10.toBigDecimal() } == true) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Bolt, null, Modifier.size(20.dp)); Text("剩余电量", style = MaterialTheme.typography.labelLarge) }
                        AnimatedContent(state.electric?.let { "${it.balance} 度" } ?: "—", label = "electricBalance") { balance ->
                            Text(balance, style = MaterialTheme.typography.displaySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.testTag("electricBalance"))
                        }
                        Text(state.dorm?.label ?: "还未选择宿舍")
                        state.electric?.let { Text("更新于 ${moneyTime(it.checkedAt)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.dorm == null && !state.selecting) Button(onClick = { roomSearch = ""; controller.selectDorm() }, enabled = !state.busy) { Text("选择宿舍") }
                OutlinedButton(onClick = onWeb) { Text("电费充值") }
            } }
            if (state.selecting) {
                item { OptionPicker("电费项目", state.projects, state.project, state.busy, controller::project) }
                item { OptionPicker("校区", state.areas, state.area, state.busy || state.project == null, controller::area) }
                item { OptionPicker("楼栋", state.buildings, state.building, state.busy || state.area == null, controller::building) }
                if (state.building != null) item { OutlinedTextField(roomSearch, { roomSearch = it }, label = { Text("筛选房间") }, supportingText = { Text("输入房间号缩小选项，最多显示 100 项") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }), modifier = Modifier.fillMaxWidth().testTag("roomSearch")) }
                item { OptionPicker("房间", state.rooms.filter { roomSearch.isBlank() || roomSearch in it.name }, state.room, state.busy || state.building == null, controller::room) }
                item { Row { Button(onClick = { focusManager.clearFocus(); controller.bindDorm() }, enabled = !state.busy && state.room != null) { Text("保存并查询") }; TextButton(onClick = { focusManager.clearFocus(); controller.closeSelector() }, enabled = !state.busy) { Text("取消") } } }
            }
            item { DiagnosticExport("电费") { "电费诊断\n有宿舍：${state.dorm != null}\n有余额缓存：${state.electric != null}\n更新毫秒：${state.electric?.checkedAt ?: 0}\n历史记录：${state.history.size}\n加载中：${state.busy}\n失败：${state.error != null}" } }
            if (state.dorm != null) {
                item { ToggleRow("每日自动更新余额", "将每日查询结果保存为用电记录", state.autoElectric, controller::autoElectric, enabled = !state.busy) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(usageChart, { usageChart = true }, label = { Text("近七天用电") })
                    FilterChip(!usageChart, { usageChart = false }, label = { Text("余额趋势") })
                } }
                item {
                    Text(if (usageChart) "近七天用电估算（度）" else "近七天余额（度）", style = MaterialTheme.typography.titleMedium)
                    val records = if (usageChart) state.history.mapIndexedNotNull { index, record ->
                        dailyDeduction(state.history.getOrNull(index - 1), record)?.let { DailyBalance(record.day, it.toPlainString()) }
                    } else state.history
                    val recent = records.filter { runCatching { LocalDate.parse(it.day) in schoolToday().minusDays(6)..schoolToday() }.getOrDefault(false) }
                    BalanceChart(recent, usageChart)
                    if (usageChart && recent.isNotEmpty()) {
                        val average = recent.sumOf { it.balance.toBigDecimal() }.divide(recent.size.toBigDecimal(), 2, java.math.RoundingMode.HALF_UP)
                        Text("有记录的 ${recent.size} 天平均用电 $average 度", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Text("历史由本机每日查询生成；相邻两天电量下降为用电估算，电量上升视为充值补电，缺少相邻日记录时不推算用量。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.history.asReversed(), key = { it.day }) { record ->
                    val index = state.history.indexOf(record)
                    val used = dailyDeduction(state.history.getOrNull(index - 1), record)
                    if (index == state.history.lastIndex || state.history.getOrNull(index + 1)?.day?.take(7) != record.day.take(7)) Text(record.day.take(7), style = MaterialTheme.typography.titleMedium)
                    ListItem(headlineContent = { Text(record.day) }, trailingContent = { Text("${record.balance} 度") }, supportingContent = { Text(used?.let { "较昨日用电约 ${it.toPlainString()} 度" } ?: "充值或缺少相邻日记录") })
                }
                item { TextButton(onClick = { confirmUnbind = true }, enabled = !state.busy) { Text("解除宿舍绑定") } }
            }
        }
    }
    if (confirmUnbind) AlertDialog(onDismissRequest = { confirmUnbind = false }, title = { Text("解除绑定？") }, text = { Text("历史仍按原宿舍保存，重新绑定后可查看。") }, confirmButton = { TextButton(onClick = { confirmUnbind = false; controller.unbind() }) { Text("解除绑定") } }, dismissButton = { TextButton(onClick = { confirmUnbind = false }) { Text("取消") } })
}
@Composable private fun OptionPicker(title: String, options: List<CampusOption>, selected: CampusOption?, disabled: Boolean, onPick: (CampusOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Box {
        OutlinedButton(onClick = { focusManager.clearFocus(); expanded = true }, enabled = !disabled && options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("$title：${selected?.name ?: if (options.isEmpty()) "暂无选项" else "请选择"}"); Icon(Icons.Default.ArrowDropDown, null) }
        DropdownMenu(expanded, { expanded = false }) { options.take(100).forEach { option -> DropdownMenuItem(text = { Text(option.name) }, onClick = { expanded = false; onPick(option) }) } }
    }
}
@Composable private fun BalanceChart(records: List<DailyBalance>, usage: Boolean) {
    val points = records.mapNotNull { r -> r.balance.toFloatOrNull()?.let { r.day to it } }
    val color = MaterialTheme.colorScheme.primary
    if (points.isEmpty()) { InfoPanel("累计查询两天后显示趋势"); return }
    Text(points.joinToString("；") { "${it.first.takeLast(5)} ${it.second} 度" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(12.dp).semantics { contentDescription = if (usage) "每日用电估算图" else "每日余额趋势图" }) {
        val min = points.minOf { it.second }; val max = points.maxOf { it.second }; val range = (max - min).coerceAtLeast(1f)
        val first = LocalDate.parse(points.first().first)
        val days = ChronoUnit.DAYS.between(first, LocalDate.parse(points.last().first)).toFloat().coerceAtLeast(1f)
        val offsets = points.map { androidx.compose.ui.geometry.Offset(if (points.size == 1) size.width / 2 else ChronoUnit.DAYS.between(first, LocalDate.parse(it.first)).toFloat() / days * size.width, size.height - (it.second - min) / range * size.height) }
        if (usage) points.forEachIndexed { index, point ->
            val height = (point.second / max.coerceAtLeast(1f) * size.height).coerceAtLeast(2f)
            val width = (size.width / 10).coerceAtMost(36.dp.toPx())
            val x = offsets[index].x.coerceIn(width / 2, size.width - width / 2)
            drawRect(color, androidx.compose.ui.geometry.Offset(x - width / 2, size.height - height), androidx.compose.ui.geometry.Size(width, height))
        } else {
            offsets.zipWithNext().forEach { (a,b) -> drawLine(color, a, b, 5f) }
            offsets.forEach { drawCircle(color, 6f, it) }
        }
    }
}
@Composable fun CalendarScreen(controller: CampusController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var image by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<SchoolCalendar?>(null) }
    val editScope = rememberCoroutineScope()
    LaunchedEffect(Unit) { controller.calendar() }
    CampusFrame("校历", state, onBack, { controller.calendar(true) }) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("schoolCalendar")) {
            val calendar = state.calendar
            if (calendar == null) item { Text(if (state.busy) "正在获取官方校历…" else "连接校园网后获取校历；已保存的校历可离线查看。") }
            else {
                item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.EventAvailable, null, Modifier.size(28.dp))
                        Text(calendar.title, style = MaterialTheme.typography.headlineSmall)
                        Text("识别于 ${moneyTime(calendar.fetchedAt)}", style = MaterialTheme.typography.labelSmall)
                    }
                } }
                item { InfoPanel("日期由本机识别，仅供参考，请以官方原图为准。") }
                if (calendar.imagePath.isNotBlank()) item { Button(onClick = { image = true }) { Text("查看校历原图") } }
                item { TextButton(onClick = { editScope.launch { original = controller.originalCalendar(); editing = original != null } }, enabled = !state.busy) { Text("手动校正") } }
                items(calendar.notes.orEmpty()) { note -> Text(note) }
                items(calendar.semesters, key = { it.start }) { span -> ContentCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(span.label, style = MaterialTheme.typography.titleMedium); Text("${span.start} — ${span.end}") } } }
                items(schoolBreaks(calendar), key = { "break:${it.start}" }) { holiday -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(holiday.title, style = MaterialTheme.typography.titleLarge)
                    Text("${holiday.start} — ${holiday.end?.toString() ?: "结束日期待识别"}")
                } } }

                if (calendar.semesters.isEmpty()) item { Text("尚未识别到完整学期日期，请查看原图。", color = MaterialTheme.colorScheme.error) }
                item { TextButton(onClick = { raw = !raw }) { Text(if (raw) "收起识别文字" else "查看识别文字") }; if (raw) {
                    Text(calendar.rawText.ifBlank { "没有识别到文字，请查看原图。" })
                    DiagnosticExport("校历识别") { calendar.rawText }
                } }
            }
        }
    }
    if (editing) original?.let { source -> CalendarCorrectionEditor(state.calendar ?: source, source, state.busy, { editing = false }) { draft ->
        controller.saveCalendarCorrection(source.imageUrl, draft) { editing = false }
    } }
    if (image) Dialog(onDismissRequest = { image = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding()) {
            TextButton(onClick = { image = false }) { Text("关闭原图") }
            val bitmap by produceState<android.graphics.Bitmap?>(null, state.calendar?.imagePath) { value = withContext(Dispatchers.IO) { state.calendar?.imagePath?.let { run {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(it, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096 || bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 12_000_000) sample *= 2
                android.graphics.BitmapFactory.decodeFile(it, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            } } } }
            var scale by remember { mutableFloatStateOf(1f) }; var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            bitmap?.let { Image(it.asImageBitmap(), "官方校历原图", Modifier.fillMaxSize().pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan } }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }) }
        } }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReminderScreen(controller: CampusController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionEpoch by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionEpoch++ }
    val notificationsAllowed = remember(permissionEpoch) { SystemCoordinator.notificationsAllowed(context) }
    val exactAllowed = remember(permissionEpoch) { SystemCoordinator.exactAllowed(context) }
    var pending by remember { mutableStateOf<ReminderSettings?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> permissionEpoch++; if (granted) pending?.let(controller::saveReminders); pending = null }
    fun update(settings: ReminderSettings) {
        if ((settings.classes || settings.exams) && Build.VERSION.SDK_INT >= 33 && !SystemCoordinator.notificationsAllowed(context)) { pending = settings; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        else controller.saveReminders(settings)
    }
    var editingTime by remember { mutableStateOf<String?>(null) }
    var leadMenu by remember { mutableStateOf<String?>(null) }
    val options = state.reminders
    CampusFrame("提醒设置", state, onBack) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("reminders")) {
            item { InfoPanel("使用当前学期的学生课表与考试缓存，滚动安排未来七天；更新数据后自动重排。", Icons.Default.Notifications) }
            item { SectionHeading("课程提醒") }
            item { ToggleRow("上课提醒", "按课表安排课前通知", options.classes, { update(options.copy(classes = it)) }, enabled = !state.busy) }
            item { Box { OutlinedButton(onClick = { leadMenu = "class" }) { Text("上课前 ${options.classLead} 分钟") }; DropdownMenu(leadMenu == "class", { leadMenu = null }) { listOf(5,10,15,20,30,45,60).forEach { minutes -> DropdownMenuItem(text = { Text("$minutes 分钟") }, onClick = { leadMenu = null; update(options.copy(classLead = minutes)) }) } } } }
            item { HorizontalDivider() }
            item { SectionHeading("考试安排") }
            item { ToggleRow("考试提醒", "根据当前学期的考试安排发送通知", options.exams, { update(options.copy(exams = it)) }, enabled = !state.busy) }
            item { ToggleRow("前一天提醒", "考前一天提前做好准备", options.eve, { update(options.copy(eve = it)) }, enabled = !state.busy && options.exams); TextButton(onClick = { editingTime = "eve" }, enabled = options.exams) { Text("前一天 ${"%02d:%02d".format(options.eveMinutes / 60, options.eveMinutes % 60)}") } }
            item { ToggleRow("当天早间提醒", "考试当天早间发送通知", options.morning, { update(options.copy(morning = it)) }, enabled = !state.busy && options.exams); TextButton(onClick = { editingTime = "morning" }, enabled = options.exams) { Text("当天 ${"%02d:%02d".format(options.morningMinutes / 60, options.morningMinutes % 60)}") } }
            item { ToggleRow("开考前提醒", "按设定的提前时间再次提醒", options.before, { update(options.copy(before = it)) }, enabled = !state.busy && options.exams); Box { OutlinedButton(onClick = { leadMenu = "exam" }) { Text("开考前 ${options.examLead} 分钟") }; DropdownMenu(leadMenu == "exam", { leadMenu = null }) { listOf(10,15,20,30,45,60).forEach { minutes -> DropdownMenuItem(text = { Text("$minutes 分钟") }, onClick = { leadMenu = null; update(options.copy(examLead = minutes)) }) } } } }
            item { HorizontalDivider() }
            item {
                InfoPanel(if (notificationsAllowed) "系统通知已允许" else "系统通知未允许，请在系统设置中开启。", if (notificationsAllowed) Icons.Default.CheckCircle else Icons.Default.NotificationsOff)
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("系统通知设置") }
            }
            item {
                InfoPanel(if (exactAllowed) "准时提醒权限已允许" else "未允许准时提醒，系统可能延后通知。", Icons.Default.Schedule)
                if (Build.VERSION.SDK_INT >= 31 && !exactAllowed) OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("开启准时提醒") }
            }
            item { Text("已安排 ${context.getSharedPreferences("system_schedule", android.content.Context.MODE_PRIVATE).getInt("count", 0)} 条提醒。若没有提醒，请先同步当前学期的学生课表和考试安排。", style = MaterialTheme.typography.bodySmall) }
            item { Text("设备省电策略仍可能影响提醒；强行停止 App 后需重新打开才能恢复。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    editingTime?.let { kind ->
        val minutes = if (kind == "eve") options.eveMinutes else options.morningMinutes
        val time = rememberTimePickerState(minutes / 60, minutes % 60, true)
        AlertDialog(onDismissRequest = { editingTime = null }, title = { Text("设置提醒时间") }, text = { TimeInput(time) }, confirmButton = { TextButton(onClick = { val value = time.hour * 60 + time.minute; editingTime = null; update(if (kind == "eve") options.copy(eveMinutes = value) else options.copy(morningMinutes = value)) }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editingTime = null }) { Text("取消") } })
    }
}
@Composable fun WidgetSettings(onBack: () -> Unit, state: CampusState) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Class<out CampusWidgetProvider>?>(null) }
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("widget_options", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == WidgetPinning.CONFIRMED) message = "小组件已添加到桌面。"
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun openPermissions() {
        if (!WidgetPinning.openPermissionSettings(context)) message = "无法打开设置，请手动进入系统设置 → 应用 → XATune → 权限。"
    }
    CampusFrame("桌面小组件", state, onBack) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("校园信息，就在桌面", "课程、电费和假期数据来自本机缓存")
            InfoPanel("Android 12 及以上跟随系统壁纸配色和深浅模式。点击小组件可进入对应页面。", Icons.Default.Palette)
            ContentCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("添加前，检查桌面权限", style = MaterialTheme.typography.titleMedium)
                    Text(WidgetPinning.PERMISSION_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { openPermissions() }) { Icon(Icons.Default.Security, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("桌面添加权限设置") }
                }
            }
            listOf(Triple("课程", "今天的课程安排，上完后可切换到明天", CourseWidget::class.java), Triple("宿舍电费", "剩余电量一眼可见，支持手动刷新", ElectricityWidget::class.java), Triple("假期倒计时", "查看距离下一个校园假期还有几天", HolidayWidget::class.java)).forEach { (label, detail, cls) ->
                ContentCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(when (label) { "课程" -> Icons.Default.CalendarMonth; "宿舍电费" -> Icons.Default.Bolt; else -> Icons.Default.EventAvailable }, prominent = true)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(label, style = MaterialTheme.typography.titleMedium)
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        FilledTonalButton(onClick = { pending = cls }, modifier = Modifier.align(Alignment.End)) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("添加$label") }
                    }
                }
            }
            message?.let { InfoPanel(it) }
            Spacer(Modifier.height(12.dp))
        }
    }
    pending?.let { provider ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("申请添加桌面小组件") },
            text = { Text(WidgetPinning.PERMISSION_HELP) },
            confirmButton = { TextButton(onClick = { pending = null; message = WidgetPinning.request(context, provider) }) { Text("申请添加") } },
            dismissButton = { TextButton(onClick = { openPermissions() }) { Text("先开启权限") } })
    }
}
