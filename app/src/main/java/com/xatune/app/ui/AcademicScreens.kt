package com.xatune.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xatune.app.model.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun number(value: Double): String = String.format(Locale.CHINA, "%.2f", value).trimEnd('0').trimEnd('.')
private fun updated(at: Long): String = java.time.Instant.ofEpochMilli(at).atZone(SchoolZone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcademicScreen(
    kind: AcademicKind,
    state: UiState,
    model: HelperViewModel,
    onBack: () -> Unit,
    onRelogin: () -> Unit,
    onWeb: () -> Unit,
    initialScope: String? = null
) {
    var selectedSemester by rememberSaveable(kind, initialScope) { mutableStateOf(initialScope?.takeUnless { it == "all" }.orEmpty()) }
    val semester = selectedSemester.ifEmpty { state.currentSemester.ifEmpty { state.selectedSemester } }
    val allGrades = state.allGrades
    var semesterMenu by remember { mutableStateOf(false) }
    val scope = if (kind == AcademicKind.PLAN || kind == AcademicKind.GRADES && allGrades) "all" else semester
    val reading = state.academic[academicKey(kind, scope)] ?: AcademicUi(loading = true)
    val selectionLoading = reading.loading || kind == AcademicKind.GRADES && state.academic.any { (key, value) -> key.startsWith("GRADES:") && value.loading }
    val semesterName = state.semesters.firstOrNull { it.id == semester }?.name ?: "当前学期"
    val canPickSemester = kind != AcademicKind.PLAN && state.semesters.isNotEmpty() && (!allGrades || kind == AcademicKind.EXAMS)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(kind, scope) { model.loadAcademic(kind, scope) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(kind.title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    if (kind != AcademicKind.PLAN) {
                        Box {
                            TextButton(
                                onClick = { semesterMenu = true },
                                enabled = !selectionLoading && canPickSemester
                            ) {
                                Text(
                                    if (kind == AcademicKind.GRADES && allGrades) "全部学期" else semesterName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (canPickSemester) Icon(Icons.Default.ArrowDropDown, "切换学期", Modifier.size(18.dp))
                            }
                            SemesterSplitMenu(
                                expanded = semesterMenu,
                                onDismiss = { semesterMenu = false },
                                semesters = state.semesters,
                                selectedId = semester,
                                enabled = !selectionLoading,
                                onSelect = { term ->
                                    if (!selectionLoading && term.id != semester && (kind != AcademicKind.GRADES || model.gradeSemesterChanged())) {
                                        selectedSemester = term.id
                                    }
                                }
                            )
                        }
                    }
                    IconButton(onClick = { model.loadAcademic(kind, scope, true) }, enabled = !reading.loading) {
                        Icon(Icons.Default.Refresh, "刷新${kind.title}")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = reading.loading,
            onRefresh = { model.loadAcademic(kind, scope, true) },
            modifier = Modifier.padding(padding).fillMaxSize().testTag("academicRefresh")
        ) {
            ResponsiveWidthBox(modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    LoadingProgress(reading.loading)
                    if (state.demo) {
                        Text(
                            "示例数据 · 未连接学校账号",
                            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (kind == AcademicKind.GRADES) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            SegmentedButton(
                                selected = !allGrades,
                                onClick = { if (!selectionLoading && allGrades) model.gradeScope(false) },
                                enabled = !selectionLoading,
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                                label = { Text("学期成绩") }
                            )
                            SegmentedButton(
                                selected = allGrades,
                                onClick = { if (!selectionLoading && !allGrades) model.gradeScope(true) },
                                enabled = !selectionLoading,
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                                modifier = Modifier.testTag("allGradesScope"),
                                label = { Text("所有学期") }
                            )
                        }
                    }
                    reading.error?.let { message ->
                        Card(
                            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                                if (reading.data != null) Text("正在显示上次保存的数据。", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (reading.needsLogin) TextButton(onClick = onRelogin) { Text("重新登录") }
                                    else TextButton(onClick = { model.loadAcademic(kind, scope, true) }, enabled = !reading.loading) { Text("重试") }
                                    TextButton(onClick = onWeb) { Text("在教务网页中查看") }
                                }
                            }
                        }
                    }
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        DiagnosticExport(kind.title) { diagnosticSummary(state) }
                    }
                    val data = reading.data
                    if (data == null) {
                        if (!reading.loading && reading.error == null) {
                            Box(Modifier.padding(20.dp)) {
                                EmptyMessage("尚无本地数据", "下拉或点击右上角刷新获取最新内容。")
                            }
                        }
                    } else {
                        when (kind) {
                            AcademicKind.EXAMS -> ExamList(data.exams, state.semesters.firstOrNull { it.id == semester }, data.fetchedAt)
                            AcademicKind.GRADES -> data.grades?.let { GradeList(it, "$scope:${state.gradeRevision}", data.fetchedAt) }
                            AcademicKind.PLAN -> data.plan?.let { PlanList(it, data.catalog, data.catalogWarning, data.fetchedAt) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExamList(exams: List<Exam>, semester: Semester?, fetchedAt: Long) {
    val anchor = runCatching { LocalDate.parse(semester?.startDate?.take(10)) }.getOrNull() ?: schoolToday()
    var now by remember { mutableStateOf(java.time.ZonedDateTime.now(SchoolZone)) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { now = java.time.ZonedDateTime.now(SchoolZone) }
    LaunchedEffect(Unit) { while (true) { now = java.time.ZonedDateTime.now(SchoolZone); kotlinx.coroutines.delay(30_000) } }
    val sorted = remember(exams, anchor, now) { orderedExams(exams, anchor, now) }
    val upcoming = remember(sorted, anchor, now) { sorted.filter { it.isUpcoming(now, anchor) && it.date(anchor) != null } }
    val nextExam = upcoming.firstOrNull()
    val laterByDate = remember(upcoming, nextExam) {
        upcoming.drop(if (nextExam != null) 1 else 0).groupBy { it.date(anchor) }
    }
    val pendingExams = remember(sorted, anchor, now) { sorted.filter { it.date(anchor) == null } }
    val completedExams = remember(sorted, anchor, now) { sorted.filter { it.date(anchor) != null && !it.isUpcoming(now, anchor) } }
    var showCompleted by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().testTag("examsList")
    ) {
        if (sorted.isEmpty()) {
            item { EmptyMessage("当前学期暂无考试安排", "学校公布安排后，可在这里刷新查看。") }
        } else {
            nextExam?.let { exam ->
                item(key = "next:${exam.id}") {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("下一场考试", style = MaterialTheme.typography.labelLarge)
                                val remaining = exam.remainingText(anchor, now)
                                if (remaining.isNotEmpty()) {
                                    Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.primary) {
                                        Text(
                                            remaining,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag("examCountdown:${exam.id}"),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                            Text(exam.course.ifBlank { exam.name.ifBlank { "考试" } }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text(exam.time.ifEmpty { "考试时间待公布" }, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "考场：${exam.room.ifEmpty { "待公布" }}${if (exam.seat.isNotBlank()) " · 座位 ${exam.seat}" else ""}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (exam.note.isNotBlank()) Text(exam.note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            laterByDate.forEach { (date, list) ->
                item(key = "date:$date") {
                    GroupedSection(title = "$date · ${list.size} 场") {
                        list.forEach { exam ->
                            item { shape -> ExamRow(exam, anchor, now, shape) }
                        }
                    }
                }
            }

            if (pendingExams.isNotEmpty()) {
                item(key = "pending") {
                    GroupedSection(title = "时间待定 · ${pendingExams.size} 场") {
                        pendingExams.forEach { exam ->
                            item { shape -> ExamRow(exam, anchor, now, shape) }
                        }
                    }
                }
            }

            if (completedExams.isNotEmpty()) {
                item(key = "completed") {
                    GroupedSection {
                        item { shape ->
                            Surface(
                                shape = shape,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth().clip(shape).clickable { showCompleted = !showCompleted }
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("已结束 (${completedExams.size} 场)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                                    Icon(if (showCompleted) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                                }
                            }
                        }
                        if (showCompleted) {
                            completedExams.forEach { exam ->
                                item { shape -> ExamRow(exam, anchor, now, shape, muted = true) }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "更新于 ${updated(fetchedAt)} · 可离线查看",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun ExamRow(exam: Exam, anchor: LocalDate, now: java.time.ZonedDateTime, shape: androidx.compose.ui.graphics.Shape, muted: Boolean = false) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    exam.course.ifBlank { exam.name.ifBlank { "考试" } },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                val remaining = exam.remainingText(anchor, now)
                if (remaining.isNotEmpty()) {
                    Text(
                        remaining,
                        modifier = Modifier.testTag("examCountdown:${exam.id}"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(exam.time.ifEmpty { "考试时间待公布" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "考场：${exam.room.ifEmpty { "待公布" }}${if (exam.seat.isNotBlank()) " · 座位 ${exam.seat}" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (exam.name.isNotBlank()) Text(exam.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (exam.note.isNotBlank()) Text(exam.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GradeList(page: GradePage, scope: String, fetchedAt: Long) {
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(page.grades, search) { page.grades.filter { search.isBlank() || matchesCourse(it.course, it.code, search) } }
    val weighted = page.grades.mapNotNull { g ->
        val credit = g.credit.toDoubleOrNull()
        val point = g.gpa.toDoubleOrNull()
        if (credit != null && credit > 0 && point != null) credit to point else null
    }
    val gpa = page.summary.gpa ?: weighted.takeIf { it.isNotEmpty() }?.let { entries -> entries.sumOf { it.first * it.second } / entries.sumOf { it.first } }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().testTag("gradesList")
    ) {
        if (search.isBlank()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("平均绩点", style = MaterialTheme.typography.labelLarge)
                            Text(gpa?.let(::number) ?: "—", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                            Text(
                                if (page.summary.gpa != null) "来自教务系统汇总" else "按学分加权计算",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${page.summary.count ?: page.grades.size} 门", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${number(page.summary.credits ?: page.grades.sumOf { it.credit.toDoubleOrNull() ?: 0.0 })} 学分",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text("搜索课程或课程号") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (search.isNotBlank()) ({ IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, "清除") } }) else null,
                modifier = Modifier.fillMaxWidth().testTag("gradeSearch")
            )
        }
        if (filtered.isEmpty()) {
            item { EmptyMessage(if (page.grades.isEmpty()) "当前范围暂无成绩" else "没有匹配的课程", "可切换学期或调整搜索内容。") }
        } else {
            item {
                GroupedSection(title = if (search.isBlank()) "课程成绩 · ${filtered.size} 门" else "搜索结果 · ${filtered.size} 门") {
                    filtered.forEach { grade ->
                        item { shape ->
                            var expanded by rememberSaveable(scope, grade.id) { mutableStateOf(false) }
                            val clickableMod = if (grade.parts.isNotEmpty()) Modifier.clip(shape).clickable { expanded = !expanded } else Modifier
                            Surface(
                                shape = shape,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth().then(clickableMod)
                            ) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(grade.course, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                                            val meta = listOf(
                                                grade.term,
                                                grade.credit.takeIf { it.isNotBlank() }?.let { "$it 学分" }.orEmpty(),
                                                grade.code
                                            ).filter(String::isNotBlank).joinToString(" · ")
                                            if (meta.isNotBlank()) {
                                                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                grade.score.ifEmpty { "未公布" },
                                                style = MaterialTheme.typography.titleLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (grade.gpa.isNotBlank()) {
                                                Text("绩点 ${grade.gpa}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                    if (grade.parts.isNotEmpty()) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                if (expanded) "收起分项成绩" else "分项成绩",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Icon(
                                                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                null,
                                                Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Reveal(expanded) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                grade.parts.forEach { part ->
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                        Text(part.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        Text(part.score, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "更新于 ${updated(fetchedAt)} · 可离线查看",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun PlanList(plan: StudyPlan, catalog: List<CourseInfo>, warning: String?, fetchedAt: Long) {
    var query by rememberSaveable { mutableStateOf("") }
    var term by rememberSaveable { mutableStateOf("") }
    var termMenu by remember { mutableStateOf(false) }
    val terms = remember(catalog) { catalog.map { it.term }.filter(String::isNotBlank).distinct().sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it })) }
    val byCode = remember(catalog) { catalog.filter { it.code.isNotBlank() }.associateBy { it.code } }
    val byName = remember(catalog) { catalog.associateBy { it.name } }
    fun info(course: PlanCourse) = byCode[course.code] ?: byName[course.name]
    fun matches(course: PlanCourse): Boolean = (query.isBlank() || matchesCourse(course.name, course.code, query)) && (term.isBlank() || info(course)?.term == term)
    val rows = remember(plan, query, term, catalog) { plan.sections.flatMap { it.allCourses() }.filter(::matches) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().testTag("planList")
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("毕业学分", style = MaterialTheme.typography.labelLarge)
                    Text("${number(plan.earned)} / ${number(plan.required)}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(
                        progress = { if (plan.required > 0) (plan.earned / plan.required).toFloat().coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp))
                    )
                    Text("还差 ${number((plan.required - plan.earned).coerceAtLeast(0.0))} 学分${if (plan.gpa.isNotBlank()) " · GPA ${plan.gpa}" else ""}", style = MaterialTheme.typography.bodyMedium)
                    if (plan.major.isNotBlank()) Text(plan.major, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (warning != null) item { InfoPanel(warning) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("搜索计划课程") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("planSearch")
                )
                Box {
                    FilterChip(
                        selected = term.isNotBlank(),
                        onClick = { termMenu = true },
                        label = { Text(if (term.isBlank()) "学期" else "第${term}学期") },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) }
                    )
                    DropdownMenu(termMenu, { termMenu = false }) {
                        DropdownMenuItem(text = { Text("全部开课学期") }, onClick = { term = ""; termMenu = false })
                        terms.forEach { value -> DropdownMenuItem(text = { Text("开课学期 $value") }, onClick = { term = value; termMenu = false }) }
                    }
                }
            }
        }
        if (query.isNotBlank() || term.isNotBlank()) {
            item {
                GroupedSection(title = "找到 ${rows.size} 门课程") {
                    rows.forEach { course ->
                        item { shape -> PlanCourseRow(course, info(course), shape) }
                    }
                }
            }
        } else {
            items(plan.sections, key = { it.id }) { section -> PlanModule(section, byCode, byName) }
        }
        item {
            Text(
                "更新于 ${updated(fetchedAt)} · 可离线查看",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun PlanModule(section: PlanSection, byCode: Map<String, CourseInfo>, byName: Map<String, CourseInfo>) {
    var expanded by rememberSaveable(section.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ContentCard(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${number(section.earned)} / ${number(section.required)} 学分 · ${section.allCourses().size} 门课程", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(
                    progress = { if (section.required > 0) (section.earned / section.required).toFloat().coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp))
                )
            }
        }
        Reveal(expanded) {
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (section.courses.isNotEmpty()) {
                    GroupedSection {
                        section.courses.forEach { course ->
                            item { shape -> PlanCourseRow(course, byCode[course.code] ?: byName[course.name], shape) }
                        }
                    }
                }
                section.children.forEach { child -> PlanModule(child, byCode, byName) }
            }
        }
    }
}

@Composable
private fun PlanCourseRow(course: PlanCourse, info: CourseInfo?, shape: androidx.compose.ui.graphics.Shape) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(course.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                val meta = listOf(
                    info?.nature.orEmpty(),
                    info?.hours?.takeIf(String::isNotBlank)?.let { "$it 学时" }.orEmpty(),
                    info?.term?.takeIf(String::isNotBlank)?.let { "第${it}学期" }.orEmpty(),
                    course.code
                ).filter(String::isNotBlank).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (course.score.isNotBlank()) course.score else if (course.passed) "已通过" else "未完成",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (course.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("${number(course.earned)}/${number(course.required)} 学分", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyMessage(title: String, detail: String) {
    EmptyState(title, detail, Icons.Default.School)
}
