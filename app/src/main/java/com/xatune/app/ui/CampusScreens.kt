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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xatune.app.model.*
import com.xatune.app.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private fun moneyTime(at: Long) = Instant.ofEpochMilli(at).atZone(SchoolZone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CampusFrame(
    title: String,
    state: CampusState,
    onBack: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh, enabled = !state.busy) {
                            Icon(Icons.Default.Refresh, "刷新$title")
                        }
                    }
                    actions()
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        ResponsiveWidthBox(modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                LoadingProgress(state.busy)
                Reveal(state.error != null) {
                    state.error?.let {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                        ) {
                            Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElectricityScreen(
    controller: CampusController,
    onBack: () -> Unit,
    onWeb: () -> Unit,
    onHistory: (() -> Unit)? = null
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(state.electric?.checkedAt) { if (state.electric != null) listState.animateScrollToItem(0) }
    var confirmUnbind by remember { mutableStateOf(false) }
    var usageChart by rememberSaveable { mutableStateOf(true) }
    var roomSearch by rememberSaveable { mutableStateOf("") }
    var overflow by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val isAmount = state.dorm?.isAmountBalance == true
    val unit = state.dorm?.balanceUnit ?: "度"
    val balanceLabel = state.dorm?.balanceLabel ?: "剩余电量"
    val low = state.electric?.balance?.toBigDecimalOrNull()?.let { it < 10.toBigDecimal() } == true
    LaunchedEffect(state.building?.id) { roomSearch = "" }

    CampusFrame(
        title = "电费",
        state = state,
        onBack = onBack,
        actions = {
            if (state.dorm != null) {
                Box {
                    IconButton(onClick = { overflow = true }) {
                        Icon(Icons.Default.MoreVert, "更多选项")
                    }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(
                            text = { Text("更换宿舍") },
                            leadingIcon = { Icon(Icons.Default.SwapHoriz, null, Modifier.size(20.dp)) },
                            enabled = !state.busy,
                            onClick = { overflow = false; roomSearch = ""; controller.selectDorm() }
                        )
                        DropdownMenuItem(
                            text = { Text("解除宿舍绑定", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.LinkOff, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                            enabled = !state.busy,
                            onClick = { overflow = false; confirmUnbind = true }
                        )
                    }
                }
            }
        }
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().testTag("electricity")
        ) {
            if (state.dorm == null) {
                item {
                    ContentCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            IconTile(Icons.Default.Bolt, prominent = true)
                            Text("选择宿舍", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("绑定楼栋和房间后，即可查看宿舍电费余额与每日用量趋势。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                                Button(onClick = { roomSearch = ""; controller.selectDorm() }, enabled = !state.busy) {
                                    Text("选择宿舍")
                                }
                                OutlinedButton(onClick = onWeb) { Text("电费充值") }
                            }
                        }
                    }
                }
            } else {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (low) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(balanceLabel, style = MaterialTheme.typography.labelLarge)
                                    Text(state.dorm?.label.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                }
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
                                ) {
                                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Bolt, null, Modifier.size(22.dp))
                                    }
                                }
                            }
                            AnimatedContent(state.electric?.let { "${it.balance} $unit" } ?: "—", label = "electricBalance") { balance ->
                                Text(
                                    balance,
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("electricBalance")
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    state.electric?.let { "更新于 ${moneyTime(it.checkedAt)}" } ?: "暂未查询",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    FilledTonalButton(onClick = onWeb) { Text("电费充值") }
                                    FilledTonalIconButton(onClick = controller::refreshElectricity, enabled = !state.busy) {
                                        Icon(Icons.Default.Refresh, "刷新电费")
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    GroupedSection(
                        title = if (usageChart) (if (isAmount) "近30天消费" else "近30天用量") else "近7天余额",
                        footer = "每天查询余额后自动记录；含充值区间使用非充值日均值估算。"
                    ) {
                        item { shape ->
                            Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                        SegmentedButton(
                                            selected = usageChart,
                                            onClick = { usageChart = true },
                                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                                            label = { Text(if (isAmount) "30天消费" else "30天用电") }
                                        )
                                        SegmentedButton(
                                            selected = !usageChart,
                                            onClick = { usageChart = false },
                                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                                            label = { Text("余额趋势") }
                                        )
                                    }
                                    if (usageChart) {
                                        ElectricUsageChart(electricUsage(state.history), isAmount = isAmount)
                                    } else {
                                        BalanceChart(
                                            state.history.filter { runCatching { LocalDate.parse(it.day) in schoolToday().minusDays(6)..schoolToday() }.getOrDefault(false) },
                                            false,
                                            unit = unit
                                        )
                                    }
                                }
                            }
                        }
                        if (onHistory != null) {
                            item { shape ->
                                GroupedNavigationRow(
                                    shape = shape,
                                    title = "历史余额",
                                    value = "${state.history.size} 条",
                                    icon = Icons.Default.History,
                                    onClick = onHistory
                                )
                            }
                        }
                    }
                }

                item {
                    val dorm = state.dorm
                    GroupedSection(title = "宿舍") {
                        item { shape ->
                            GroupedValueRow(shape = shape, title = "房间", value = dorm?.label.orEmpty(), icon = Icons.Default.MeetingRoom)
                        }
                        if (dorm != null && dorm.area.name.isNotBlank() && dorm.area.name != dorm.building.name) {
                            item { shape ->
                                GroupedValueRow(shape = shape, title = "校区", value = dorm.area.name, icon = Icons.Default.LocationCity)
                            }
                        }
                        item { shape ->
                            GroupedNavigationRow(
                                shape = shape,
                                title = "更换宿舍",
                                icon = Icons.Default.SwapHoriz,
                                enabled = !state.busy,
                                onClick = { roomSearch = ""; controller.selectDorm() }
                            )
                        }
                    }
                }
            }

            item {
                DiagnosticExport("电费") {
                    "电费诊断\n有宿舍：${state.dorm != null}\n有余额缓存：${state.electric != null}\n更新毫秒：${state.electric?.checkedAt ?: 0}\n历史记录：${state.history.size}\n加载中：${state.busy}\n失败：${state.error != null}"
                }
            }
        }
    }

    if (state.selecting) {
        ModalBottomSheet(onDismissRequest = { if (!state.busy) { focusManager.clearFocus(); controller.closeSelector() } }) {
            Column(
                Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("选择宿舍", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                OptionPicker("电费项目", state.projects, state.project, state.busy, controller::project)
                OptionPicker("校区", state.areas, state.area, state.busy || state.project == null, controller::area)
                OptionPicker("楼栋", state.buildings, state.building, state.busy || state.area == null, controller::building)
                if (state.building != null) {
                    OutlinedTextField(
                        value = roomSearch,
                        onValueChange = { roomSearch = it },
                        label = { Text("筛选房间") },
                        supportingText = { Text("输入房间号缩小选项，最多显示 100 项") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().testTag("roomSearch")
                    )
                }
                OptionPicker("房间", state.rooms.filter { roomSearch.isBlank() || roomSearch in it.name }, state.room, state.busy || state.building == null, controller::room)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { focusManager.clearFocus(); controller.closeSelector() },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("取消") }
                    Button(
                        onClick = { focusManager.clearFocus(); controller.bindDorm() },
                        enabled = !state.busy && state.room != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("保存并查询") }
                }
            }
        }
    }

    if (confirmUnbind) {
        AlertDialog(
            onDismissRequest = { confirmUnbind = false },
            title = { Text("解除绑定？") },
            text = { Text("历史仍按原宿舍保存，重新绑定后可查看。") },
            confirmButton = { TextButton(onClick = { confirmUnbind = false; controller.unbind() }) { Text("解除绑定") } },
            dismissButton = { TextButton(onClick = { confirmUnbind = false }) { Text("取消") } }
        )
    }
}

@Composable
fun ElectricityHistoryScreen(controller: CampusController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val isAmount = state.dorm?.isAmountBalance == true
    val unit = state.dorm?.balanceUnit ?: "度"
    val grouped = remember(state.history) {
        state.history.asReversed().groupBy { it.day.take(7) }
    }
    CampusFrame("历史余额", state, onBack) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().testTag("electricityHistory")
        ) {
            if (state.history.isEmpty()) {
                item { EmptyState("暂无历史记录", "每次查询宿舍电费后，系统会自动按天保存一条余额记录。", Icons.Default.History) }
            } else {
                grouped.forEach { (month, records) ->
                    item(key = month) {
                        GroupedSection(title = month) {
                            records.forEach { record ->
                                val index = state.history.indexOf(record)
                                val used = dailyDeduction(state.history.getOrNull(index - 1), record)
                                item { shape ->
                                    GroupedValueRow(
                                        shape = shape,
                                        title = record.day,
                                        subtitle = used?.let { "较昨日${if (isAmount) "消费" else "用电"}约 ${it.toPlainString()} $unit" } ?: "充值或缺少相邻日记录",
                                        value = "${record.balance} $unit"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun OptionPicker(title: String, options: List<CampusOption>, selected: CampusOption?, disabled: Boolean, onPick: (CampusOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Box {
        OutlinedButton(onClick = { focusManager.clearFocus(); expanded = true }, enabled = !disabled && options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("$title：${selected?.name ?: if (options.isEmpty()) "暂无选项" else "请选择"}", Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) { options.take(100).forEach { option -> DropdownMenuItem(text = { Text(option.name) }, onClick = { expanded = false; onPick(option) }) } }
    }
}

@Composable private fun BalanceChart(records: List<DailyBalance>, usage: Boolean, unit: String = "度") {
    val points = records.mapNotNull { r -> r.balance.toFloatOrNull()?.let { r.day to it } }
    val color = MaterialTheme.colorScheme.primary
    if (points.isEmpty()) { InfoPanel("累计查询两天后显示趋势"); return }
    Text(points.joinToString("；") { "${it.first.takeLast(5)} ${it.second} $unit" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            offsets.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, 5f) }
            offsets.forEach { drawCircle(color, 6f, it) }
        }
    }
}

@Composable
fun CalendarScreen(controller: CampusController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var image by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<SchoolCalendar?>(null) }
    var overflow by remember { mutableStateOf(false) }
    val editScope = rememberCoroutineScope()
    LaunchedEffect(Unit) { controller.calendar() }
    val calendar = state.calendar

    CampusFrame(
        title = "校历",
        state = state,
        onBack = onBack,
        onRefresh = { controller.calendar(true) },
        actions = {
            if (calendar != null) {
                Box {
                    IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, "更多选项") }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(
                            text = { Text("手动校正") },
                            leadingIcon = { Icon(Icons.Default.EditCalendar, null, Modifier.size(20.dp)) },
                            enabled = !state.busy,
                            onClick = {
                                overflow = false
                                editScope.launch {
                                    original = controller.originalCalendar()
                                    editing = original != null
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (raw) "收起识别文字" else "查看识别文字") },
                            leadingIcon = { Icon(Icons.Default.Description, null, Modifier.size(20.dp)) },
                            onClick = { overflow = false; raw = !raw }
                        )
                    }
                }
            }
        }
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().testTag("schoolCalendar")
        ) {
            if (calendar == null) {
                item {
                    EmptyState(
                        title = if (state.busy) "正在获取官方校历…" else "暂无校历缓存",
                        detail = "连接校园网后获取校历；已保存的校历可离线查看。",
                        icon = Icons.Default.EventAvailable
                    )
                }
            } else {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Default.EventAvailable, null, Modifier.size(26.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(calendar.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text("识别于 ${moneyTime(calendar.fetchedAt)}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (calendar.imagePath.isNotBlank()) {
                                FilledTonalButton(onClick = { image = true }) {
                                    Icon(Icons.Default.Image, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("查看校历原图")
                                }
                            }
                        }
                    }
                }

                if (calendar.semesters.isNotEmpty()) {
                    item {
                        GroupedSection(title = "学期安排", footer = "日期由本机识别，仅供参考，请以官方原图为准。") {
                            calendar.semesters.forEach { span ->
                                item { shape ->
                                    GroupedValueRow(
                                        shape = shape,
                                        title = span.label,
                                        value = "${span.start} — ${span.end}"
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item { InfoPanel("尚未识别到完整学期日期，请查看原图。") }
                }

                val breaks = schoolBreaks(calendar)
                if (breaks.isNotEmpty()) {
                    item {
                        GroupedSection(title = "假期") {
                            breaks.forEach { holiday ->
                                item { shape ->
                                    GroupedValueRow(
                                        shape = shape,
                                        title = holiday.title,
                                        value = "${holiday.start} — ${holiday.end?.toString() ?: "待识别"}"
                                    )
                                }
                            }
                        }
                    }
                }

                val notes = calendar.notes.orEmpty().filter(String::isNotBlank)
                if (notes.isNotEmpty()) {
                    item {
                        GroupedSection(title = "备注") {
                            notes.forEach { note ->
                                item { shape ->
                                    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                        Text(note, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }

                if (raw) {
                    item {
                        GroupedSection(title = "识别文字") {
                            item { shape ->
                                Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(calendar.rawText.ifBlank { "没有识别到文字，请查看原图。" }, style = MaterialTheme.typography.bodySmall)
                                        DiagnosticExport("校历识别") { calendar.rawText }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editing) original?.let { source ->
        CalendarCorrectionEditor(state.calendar ?: source, source, state.busy, { editing = false }) { draft ->
            controller.saveCalendarCorrection(source.imageUrl, draft) { editing = false }
        }
    }

    if (image) Dialog(onDismissRequest = { image = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                TextButton(onClick = { image = false }) { Text("关闭原图") }
                val bitmap by produceState<android.graphics.Bitmap?>(null, state.calendar?.imagePath) {
                    value = withContext(Dispatchers.IO) {
                        state.calendar?.imagePath?.let {
                            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeFile(it, bounds)
                            var sample = 1
                            while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096 || bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 12_000_000) sample *= 2
                            android.graphics.BitmapFactory.decodeFile(it, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                    }
                }
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        "官方校历原图",
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                            }
                        }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderScreen(controller: CampusController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionEpoch by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionEpoch++ }
    val notificationsAllowed = remember(permissionEpoch) { SystemCoordinator.notificationsAllowed(context) }
    val exactAllowed = remember(permissionEpoch) { SystemCoordinator.exactAllowed(context) }
    var pending by remember { mutableStateOf<ReminderSettings?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionEpoch++
        if (granted) pending?.let(controller::saveReminders)
        pending = null
    }
    fun update(settings: ReminderSettings) {
        if ((settings.classes || settings.exams) && Build.VERSION.SDK_INT >= 33 && !SystemCoordinator.notificationsAllowed(context)) {
            pending = settings
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else controller.saveReminders(settings)
    }
    var editingTime by remember { mutableStateOf<String?>(null) }
    var leadMenu by remember { mutableStateOf<String?>(null) }
    val options = state.reminders
    val scheduledCount = context.getSharedPreferences("system_schedule", android.content.Context.MODE_PRIVATE).getInt("count", 0)

    CampusFrame("提醒", state, onBack) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().testTag("reminders")
        ) {
            item {
                GroupedSection(
                    title = "课程提醒",
                    footer = "使用当前学期的学生课表缓存滚动安排未来七天通知。"
                ) {
                    item { shape ->
                        GroupedToggleRow(
                            shape = shape,
                            title = "上课提醒",
                            subtitle = "按课表安排课前通知",
                            checked = options.classes,
                            onCheckedChange = { update(options.copy(classes = it)) },
                            icon = Icons.Default.NotificationsActive,
                            enabled = !state.busy
                        )
                    }
                    item { shape ->
                        Box {
                            GroupedNavigationRow(
                                shape = shape,
                                title = "提前时间",
                                value = "上课前 ${options.classLead} 分钟",
                                enabled = !state.busy && options.classes,
                                onClick = { leadMenu = "class" }
                            )
                            DropdownMenu(leadMenu == "class", { leadMenu = null }) {
                                listOf(5, 10, 15, 20, 30, 45, 60).forEach { minutes ->
                                    DropdownMenuItem(text = { Text("$minutes 分钟") }, onClick = { leadMenu = null; update(options.copy(classLead = minutes)) })
                                }
                            }
                        }
                    }
                }
            }

            item {
                GroupedSection(title = "考试提醒") {
                    item { shape ->
                        GroupedToggleRow(
                            shape = shape,
                            title = "考试提醒",
                            subtitle = "根据当前学期的考试安排发送通知",
                            checked = options.exams,
                            onCheckedChange = { update(options.copy(exams = it)) },
                            icon = Icons.Default.Assignment,
                            enabled = !state.busy
                        )
                    }
                    if (options.exams) {
                        item { shape ->
                            GroupedToggleRow(
                                shape = shape,
                                title = "前一天提醒",
                                subtitle = "考前一天 ${"%02d:%02d".format(options.eveMinutes / 60, options.eveMinutes % 60)}",
                                checked = options.eve,
                                onCheckedChange = { update(options.copy(eve = it)) },
                                enabled = !state.busy
                            )
                        }
                        if (options.eve) {
                            item { shape ->
                                GroupedNavigationRow(
                                    shape = shape,
                                    title = "前一天提醒时间",
                                    value = "%02d:%02d".format(options.eveMinutes / 60, options.eveMinutes % 60),
                                    onClick = { editingTime = "eve" }
                                )
                            }
                        }
                        item { shape ->
                            GroupedToggleRow(
                                shape = shape,
                                title = "当天早间提醒",
                                subtitle = "考试当天 ${"%02d:%02d".format(options.morningMinutes / 60, options.morningMinutes % 60)}",
                                checked = options.morning,
                                onCheckedChange = { update(options.copy(morning = it)) },
                                enabled = !state.busy
                            )
                        }
                        if (options.morning) {
                            item { shape ->
                                GroupedNavigationRow(
                                    shape = shape,
                                    title = "当天早间时间",
                                    value = "%02d:%02d".format(options.morningMinutes / 60, options.morningMinutes % 60),
                                    onClick = { editingTime = "morning" }
                                )
                            }
                        }
                        item { shape ->
                            GroupedToggleRow(
                                shape = shape,
                                title = "开考前提醒",
                                subtitle = "开考前 ${options.examLead} 分钟再次提醒",
                                checked = options.before,
                                onCheckedChange = { update(options.copy(before = it)) },
                                enabled = !state.busy
                            )
                        }
                        if (options.before) {
                            item { shape ->
                                Box {
                                    GroupedNavigationRow(
                                        shape = shape,
                                        title = "开考前提前时间",
                                        value = "${options.examLead} 分钟",
                                        onClick = { leadMenu = "exam" }
                                    )
                                    DropdownMenu(leadMenu == "exam", { leadMenu = null }) {
                                        listOf(10, 15, 20, 30, 45, 60).forEach { minutes ->
                                            DropdownMenuItem(text = { Text("$minutes 分钟") }, onClick = { leadMenu = null; update(options.copy(examLead = minutes)) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                GroupedSection(
                    title = "系统权限与状态",
                    footer = "已安排 $scheduledCount 条提醒。设备省电策略可能延后提醒；强行停止 App 后需重新打开恢复。"
                ) {
                    item { shape ->
                        GroupedNavigationRow(
                            shape = shape,
                            title = "系统通知权限",
                            subtitle = if (notificationsAllowed) "已允许发送通知" else "未允许，点击前往系统设置开启",
                            value = if (notificationsAllowed) "已开启" else "去开启",
                            icon = if (notificationsAllowed) Icons.Default.CheckCircle else Icons.Default.NotificationsOff,
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }
                        )
                    }
                    item { shape ->
                        if (Build.VERSION.SDK_INT >= 31 && !exactAllowed) {
                            GroupedNavigationRow(
                                shape = shape,
                                title = "准时提醒权限",
                                subtitle = "未允许时系统可能延后通知",
                                value = "去开启",
                                icon = Icons.Default.Schedule,
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                                }
                            )
                        } else {
                            GroupedValueRow(
                                shape = shape,
                                title = "准时提醒权限",
                                value = if (exactAllowed) "已开启" else "受限",
                                icon = Icons.Default.Schedule
                            )
                        }
                    }
                }
            }
        }
    }

    editingTime?.let { kind ->
        val minutes = if (kind == "eve") options.eveMinutes else options.morningMinutes
        val time = rememberTimePickerState(minutes / 60, minutes % 60, true)
        AlertDialog(
            onDismissRequest = { editingTime = null },
            title = { Text("设置提醒时间") },
            text = { TimeInput(time) },
            confirmButton = {
                TextButton(onClick = {
                    val value = time.hour * 60 + time.minute
                    editingTime = null
                    update(if (kind == "eve") options.copy(eveMinutes = value) else options.copy(morningMinutes = value))
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingTime = null }) { Text("取消") } }
        )
    }
}

@Composable
fun WidgetSettings(onBack: () -> Unit, state: CampusState) {
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
    val widgets = listOf(
        Triple("课程", "今日课程安排，上完后自动切换明天", CourseWidget::class.java),
        Triple("考试倒计时", "最近一场考试倒计时、考场与后续场次", ExamWidget::class.java),
        Triple("宿舍电费", "电费余额一眼可见，支持手动刷新", ElectricityWidget::class.java),
        Triple("假期倒计时", "查看距离下一个校园假期还有几天", HolidayWidget::class.java)
    )
    CampusFrame("桌面小组件", state, onBack) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                GroupedSection(
                    title = "可添加的小组件",
                    footer = "数据来自本机缓存，Android 12+ 跟随系统壁纸配色与深浅模式。"
                ) {
                    widgets.forEach { (label, detail, cls) ->
                        item { shape ->
                            GroupedNavigationRow(
                                shape = shape,
                                title = label,
                                subtitle = detail,
                                value = "添加",
                                icon = when (label) {
                                    "课程" -> Icons.Default.CalendarMonth
                                    "考试倒计时" -> Icons.Default.School
                                    "宿舍电费" -> Icons.Default.Bolt
                                    else -> Icons.Default.EventAvailable
                                },
                                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                onClick = { pending = cls }
                            )
                        }
                    }
                }
            }
            item {
                GroupedSection(title = "桌面权限", footer = WidgetPinning.PERMISSION_HELP) {
                    item { shape ->
                        GroupedNavigationRow(
                            shape = shape,
                            title = "桌面添加权限设置",
                            subtitle = "如无法直接添加小组件，请检查系统快捷方式权限",
                            icon = Icons.Default.Security,
                            onClick = { openPermissions() }
                        )
                    }
                }
            }
            message?.let { text ->
                item { InfoPanel(text) }
            }
        }
    }
    pending?.let { provider ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("申请添加桌面小组件") },
            text = { Text(WidgetPinning.PERMISSION_HELP) },
            confirmButton = { TextButton(onClick = { pending = null; message = WidgetPinning.request(context, provider) }) { Text("申请添加") } },
            dismissButton = { TextButton(onClick = { openPermissions() }) { Text("先开启权限") } }
        )
    }
}
