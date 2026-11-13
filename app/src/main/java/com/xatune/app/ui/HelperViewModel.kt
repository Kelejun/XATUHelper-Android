package com.xatune.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xatune.app.BuildConfig
import com.xatune.app.data.*
import com.xatune.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate

data class AcademicUi(val data: AcademicSnapshot? = null, val loading: Boolean = false, val error: String? = null, val needsLogin: Boolean = false)
fun academicKey(kind: AcademicKind, scope: String) = "${kind.name}:$scope"

data class UiState(
    val academic: Map<String, AcademicUi> = emptyMap(), val notice: AcademicNotice? = null, val allGrades: Boolean = false,
    val reducedInformationUpdates: Boolean = false, val gradeRevision: Int = 0, val showWeekend: Boolean = true, val showDiagnostics: Boolean = false, val currentSemester: String = "",
    val edits: List<TimetableEdit> = emptyList(), val pendingEdits: Map<String, List<Int>> = emptyMap(), val baseTable: Timetable? = null,
    val starting: Boolean = true, val account: String = "", val table: Timetable? = null,
    val semesters: List<Semester> = emptyList(), val selectedSemester: String = "", val kind: TableKind = TableKind.STUDENT,
    val week: Int = 1, val busy: Boolean = false, val error: String? = null,
    val webAccount: String = "", val needsLogin: Boolean = false, val showWebLogin: Boolean = false, val demo: Boolean = false
)

typealias UpdateUi = AppUpdateState

class HelperViewModel(private val repository: TimetableRepository, private val campusRepository: CampusRepository? = null, private val updates: AppUpdateRepository? = null, private val updateSchedule: suspend () -> Unit = {}, private val syncSystems: suspend () -> Unit = {}) : ViewModel() {
    val updateState = updates?.state ?: MutableStateFlow(UpdateUi()).asStateFlow()
    private var updateJob: Job? = null
    init { viewModelScope.launch { updates?.initialize() } }
    fun dismissUpdate() { updates?.dismissOffer() }
    fun checkUpdates(manual: Boolean = false) {
        val updater = updates ?: return
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch { updater.check(manual) }
    }
    private fun updateSettings(change: (UpdateSettings) -> UpdateSettings) {
        viewModelScope.launch { updates?.configure(change) }
    }
    fun updateChannel(channel: UpdateChannel) = updateSettings { it.copy(channel = channel) }
    fun updateServer(server: UpdateServer) = updateSettings { it.copy(server = server) }
    fun updateRepositoryUrl(url: String) = updateSettings { it.copy(repositoryUrl = url) }
    fun automaticUpdates(enabled: Boolean) {
        viewModelScope.launch { updates?.configure { it.copy(automatic = enabled) }; updateSchedule() }
    }
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    val campus = campusRepository?.let { CampusController(it, viewModelScope, syncSystems) }
    val destination = MutableStateFlow<String?>(null)
    fun browserClosed() { viewModelScope.launch { repository.saveBrowserCookies(); campus?.load(); runCatching { syncSystems() } } }
    fun openDestination(page: String?) { if (page != null) destination.value = page }
    fun consumeDestination() { destination.value = null }
    fun resume() { automaticForeground = true; campus?.resumeAutomaticUpdates(); if (state.value.starting) return; viewModelScope.launch { campus?.load(); runCatching { syncSystems() }; startAutomaticUpdates() } }
    fun pauseAutomaticUpdates() { automaticForeground = false; campus?.pauseAutomaticUpdates(); automaticJob?.cancel(); planSyncJob?.cancel(); automaticAcademicKeys.toList().forEach { academicJobs[it]?.cancel() } }
    private var automaticForeground = true
    private var automaticJob: Job? = null
    private var planSyncJob: Job? = null
    private var automaticFlow = false
    private var planAttemptedInFlow = false
    fun reducedInformationUpdates(enabled: Boolean) {
        mutable.update { it.copy(reducedInformationUpdates = enabled) }
        viewModelScope.launch { repository.store.extra("reduceAcademicRefreshFrequency", enabled); automaticJob?.cancelAndJoin(); startAutomaticUpdates() }
    }
    private suspend fun refreshTerms(): List<RefreshTerm> {
        val s = state.value
        val current = repository.store.baseTable(s.account, s.currentSemester, TableKind.STUDENT)
        return InformationRefreshPolicy.terms(campusRepository?.scheduleCalendars().orEmpty(), repository.store.semesters(), current?.monday ?: repository.store.monday(s.account, s.currentSemester))
    }
    private suspend fun informationStale(kind: InformationKind, at: Long?) = InformationRefreshPolicy.stale(kind, at, state.value.reducedInformationUpdates, refreshTerms())
    private fun startAutomaticUpdates() {
        val s = state.value
        if (!automaticForeground || campusRepository == null || s.account.isBlank() || s.demo || automaticJob?.isActive == true) return
        automaticJob = viewModelScope.launch {
            automaticFlow = true; planAttemptedInFlow = false
            try {
                val account = s.account
                val term = s.currentSemester.ifBlank { repository.store.currentSemester(account) }
                val gradeScope = if (s.allGrades) "all" else term
                suspend fun at(kind: InformationKind): Long? = when (kind) {
                    InformationKind.TIMETABLE -> repository.store.baseTable(account, term, TableKind.STUDENT)?.fetchedAt
                    else -> repository.store.academic(account, AcademicKind.valueOf(kind.name), if (kind == InformationKind.PLAN) "all" else if (kind == InformationKind.GRADES) gradeScope else term)?.fetchedAt
                }
                val kinds = if (s.reducedInformationUpdates) {
                    InformationKind.entries.filter { informationStale(it, at(it)) }.maxByOrNull { kind -> at(kind)?.let { (System.currentTimeMillis() - it).toDouble() / (kind.hours * 3_600_000) } ?: Double.MAX_VALUE }?.let(::listOf).orEmpty()
                } else listOf(InformationKind.TIMETABLE, InformationKind.GRADES, InformationKind.EXAMS)
                for (kind in kinds) {
                    ensureActive()
                    if (state.value.account != account || state.value.demo) return@launch
                    if (!informationStale(kind, at(kind))) continue
                    if (kind == InformationKind.TIMETABLE) {
                        InformationRefreshGate.waitForSlot()
                        if (!informationStale(kind, at(kind)) || state.value.busy) continue
                        try {
                            val before = state.value
                            val table = repository.refresh(account, term, TableKind.STUDENT)
                            if (state.value.account != account) return@launch
                            if (before.selectedSemester == term && before.kind == TableKind.STUDENT) accept(table)
                            else { repository.store.select(account, before.selectedSemester, before.kind); mutable.update { it.copy(semesters = repository.store.semesters()) }; syncSystems() }
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    } else {
                        val academicKind = AcademicKind.valueOf(kind.name)
                        val scope = if (kind == InformationKind.PLAN) "all" else if (kind == InformationKind.GRADES) gradeScope else term
                        loadAcademic(academicKind, scope)
                        academicJobs[academicKey(academicKind, scope)]?.join()
                    }
                }
                syncPendingPlan()
                if (!state.value.reducedInformationUpdates && campusRepository.shouldRefreshCalendar()) {
                    InformationRefreshGate.waitForSlot()
                    runCatching { campusRepository.automaticCalendar() }.onFailure { if (it is CancellationException) throw it }
                    campus?.load(); syncSystems()
                }
            } finally { automaticFlow = false }
        }
    }
    private suspend fun syncPendingPlan() {
        val s = state.value
        if (!automaticForeground || s.demo || s.reducedInformationUpdates || planAttemptedInFlow || repository.store.extra("academic.pendingPlan:${s.account}", Boolean::class.javaObjectType) != true) return
        planAttemptedInFlow = true
        InformationRefreshGate.waitForSlot()
        if (!automaticForeground || state.value.account != s.account || state.value.reducedInformationUpdates) return
        loadAcademic(AcademicKind.PLAN, "all", true, forceCatalog = false)
        automaticAcademicKeys.add(academicKey(AcademicKind.PLAN, "all"))
        academicJobs[academicKey(AcademicKind.PLAN, "all")]?.join()
    }
    private fun planAfterGrades() {
        if (automaticFlow || planSyncJob?.isActive == true) return
        planSyncJob = viewModelScope.launch { syncPendingPlan() }
    }
    private var request: Job? = null
    private val academicJobs = mutableMapOf<String, Job>()
    private val automaticAcademicKeys = mutableSetOf<String>()
    init {
        request = viewModelScope.launch {
            try {
                repository.initialize()
                // Production updates must not reopen a sample account saved by an older debug build.
                if (!BuildConfig.DEMO_ENABLED && repository.store.account() == "__demo__") repository.logout()
                val account = repository.store.account()
                val (semester, kind) = repository.store.selection()
                val base = repository.store.baseTable(account, semester, kind)
                val edits = if (kind == TableKind.STUDENT) repository.store.edits(account, semester) else emptyList()
                val edited = base?.let { editTimetable(it, edits) }
                val table = edited?.table
                val demo = account == "__demo__" && BuildConfig.DEMO_ENABLED
                mutable.value = UiState(starting = false, account = account, table = table, baseTable = base, edits = edits, pendingEdits = edited?.pending.orEmpty(), semesters = repository.store.semesters(), selectedSemester = semester, kind = kind,
                    week = table?.let { weekOf(LocalDate.parse(it.monday), schoolToday()) } ?: 1, demo = demo,
                    currentSemester = repository.store.currentSemester(account),
                    reducedInformationUpdates = repository.store.extra("reduceAcademicRefreshFrequency", Boolean::class.javaObjectType) ?: false,
                    showWeekend = repository.store.extra("showWeekend", Boolean::class.javaObjectType) ?: true,
                    showDiagnostics = repository.store.extra("showDiagnostics", Boolean::class.javaObjectType) ?: false)
                warmExams()
                campus?.load()
                runCatching { syncSystems() }
                // Cache first. Avoid login requests on every screen or process restart.
                startAutomaticUpdates()
            } catch (e: Exception) { mutable.update { it.copy(starting = false, error = message(e)) } }
        }
    }
    private fun message(e: Exception) = when (e) {
        is java.net.UnknownHostException -> "无法连接学校服务器，请检查网络；已保存的课表仍可离线查看。"
        is java.net.SocketTimeoutException -> "学校服务器响应超时，请稍后重试。"
        is javax.net.ssl.SSLException -> "无法验证学校服务器的安全连接，请稍后重试或使用网页登录。"
        else -> e.message?.take(200) ?: "操作失败，请重试"
    }
    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        request = viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: WebLoginRequired) { mutable.update { it.copy(showWebLogin = true, webAccount = it.webAccount.ifBlank { it.account }) } }
            catch (e: SessionExpired) { mutable.update { it.copy(needsLogin = true, error = message(e)) } }
            catch (e: Exception) { mutable.update { it.copy(error = message(e)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun cancelAcademic() {
        val foreground = automaticForeground
        pauseAutomaticUpdates()
        automaticForeground = foreground
        if (foreground) campus?.resumeAutomaticUpdates()
        gradeRefreshPending = false
        academicJobs.values.forEach { it.cancel() }
        academicJobs.clear()
    }
    fun login(account: String, password: String) {
        if (account.isBlank() || password.isEmpty()) { mutable.update { it.copy(error = "请填写学号和密码") }; return }
        cancelAcademic()
        mutable.update { it.copy(webAccount = account.trim()) }
        action { accept(repository.login(account.trim(), password)) }
    }
    private fun parseMonday(text: String): LocalDate? = if (text.isBlank()) null else runCatching { LocalDate.parse(text) }.getOrElse { throw IllegalArgumentException("日期格式应为 yyyy-MM-dd，例如 2026-09-07") }
    fun openWeb(account: String) {
        if (account.isBlank()) { mutable.update { it.copy(error = "请先填写学号，用于区分本地课表") }; return }
        cancelAcademic()
        action { repository.beginWebLogin(); mutable.update { it.copy(webAccount = account.trim(), showWebLogin = true, error = null) } }
    }
    fun finishWeb() {
        mutable.update { it.copy(showWebLogin = false) }
        action { accept(repository.completeWebLogin(mutable.value.webAccount)) }
    }
    fun closeWeb() { mutable.update { it.copy(showWebLogin = false) } }
    fun webError(message: String) { mutable.update { it.copy(error = message) } }
    private suspend fun accept(incoming: Timetable) {
        val base = repository.store.baseTable(incoming.account, incoming.semester.id, incoming.kind) ?: incoming
        val edits = if (base.kind == TableKind.STUDENT) repository.store.edits(base.account, base.semester.id) else emptyList()
        val edited = editTimetable(base, edits)
        val table = edited.table
        val semesters = repository.store.semesters()
        val currentSemester = repository.store.currentSemester(table.account)
        mutable.update { it.copy(allGrades = if (it.account == table.account) it.allGrades else false, academic = if (it.account == table.account) it.academic else emptyMap(), account = table.account, currentSemester = currentSemester, webAccount = table.account, table = table, baseTable = base, edits = edits, pendingEdits = edited.pending, semesters = semesters, selectedSemester = table.semester.id, kind = table.kind, needsLogin = false, demo = BuildConfig.DEMO_ENABLED && table.account == "__demo__", week = weekOf(LocalDate.parse(table.monday), schoolToday())) }
        warmExams()
        campus?.load()
        runCatching { syncSystems() }
        startAutomaticUpdates()
    }
    fun select(semester: String, kind: TableKind) {
        if (state.value.busy || (semester == state.value.selectedSemester && kind == state.value.kind)) return
        action {
            val cached = repository.store.table(state.value.account, semester, kind)
            mutable.update { it.copy(selectedSemester = semester, kind = kind, table = cached) }
            if (cached != null) { accept(cached); repository.store.select(cached.account, semester, kind) }
            if (!state.value.demo) accept(repository.refresh(state.value.account, semester, kind))
        }
    }
    fun refresh() {
        if (state.value.demo) return
        action { val s = state.value; accept(repository.refresh(s.account, s.selectedSemester, s.kind)) }
    }
    fun showWeekend(value: Boolean) {
        mutable.update { it.copy(showWeekend = value) }
        viewModelScope.launch { repository.store.extra("showWeekend", value) }
    }
    fun showDiagnostics(value: Boolean) {
        mutable.update { it.copy(showDiagnostics = value) }
        viewModelScope.launch { repository.store.extra("showDiagnostics", value) }
    }
    private var gradeRefreshPending = false
    fun gradesLoading() = state.value.academic.any { (key, value) -> key.startsWith("GRADES:") && value.loading } || academicJobs.any { (key, job) -> key.startsWith("GRADES:") && job.isActive }
    fun gradeSemesterChanged(): Boolean {
        if (gradesLoading()) return false
        gradeRefreshPending = true
        mutable.update { it.copy(gradeRevision = it.gradeRevision + 1, academic = if (it.demo) it.academic else it.academic.filterKeys { key -> !key.startsWith("GRADES:") }) }
        return true
    }
    fun gradeScope(all: Boolean) {
        if (all == state.value.allGrades || !gradeSemesterChanged()) return
        mutable.update { it.copy(allGrades = all) }
    }
    fun calibrateWeek(week: Int) = calibrate(mondayForWeek(week).toString())
    fun changeWeek(value: Int) { mutable.update { it.copy(week = value.coerceIn(1, 30)) } }
    fun calibrate(date: String) { action {
        val start = parseMonday(date) ?: throw IllegalArgumentException("请填写第一周周一的日期")
        val s = state.value
        repository.setMonday(s.account, s.selectedSemester, start)
        s.table?.let { accept(repository.calibrate(it, start)) } ?: accept(repository.refresh(s.account, s.selectedSemester, s.kind))
    } }
    fun saveEdits(scope: Timetable, edits: List<TimetableEdit>) { action {
        val s = state.value
        require(s.account == scope.account && s.selectedSemester == scope.semester.id && s.kind == TableKind.STUDENT) { "当前课表已切换，请重新编辑。" }
        val base = repository.store.baseTable(s.account, s.selectedSemester, s.kind) ?: error("当前课表无法修改。")
        require(base == scope) { "课表已变化，请关闭后重新编辑。" }
        editTimetable(base, edits)
        repository.store.edits(s.account, s.selectedSemester, edits)
        accept(base)
        mutable.update { it.copy(week = s.week) }
    } }
    fun logout() { action {
        academicJobs.values.forEach { it.cancel() }; academicJobs.clear()
        repository.logout(); mutable.value = UiState(starting = false); campus?.load(); runCatching { syncSystems() }
    } }
    fun dismissNotice() { mutable.update { it.copy(notice = null) } }
    private suspend fun warmExams() {
        val s = state.value
        repository.store.academic(s.account, AcademicKind.EXAMS, s.currentSemester)?.let { snapshot ->
            mutable.update { if (it.account == s.account && it.currentSemester == s.currentSemester) it.copy(academic = it.academic + (academicKey(AcademicKind.EXAMS, s.currentSemester) to AcademicUi(snapshot))) else it }
        }
    }
    fun loadAcademic(kind: AcademicKind, scope: String, force: Boolean = false, forceCatalog: Boolean = force) {
        val account = state.value.account
        val key = academicKey(kind, scope)
        if (account.isBlank() || academicJobs[key]?.isActive == true) return
        val changedRange = kind == AcademicKind.GRADES && gradeRefreshPending
        if (!force && !changedRange) automaticAcademicKeys.add(key)
        if (!force && !changedRange && !automaticForeground) { automaticAcademicKeys.remove(key); return }
        if (kind == AcademicKind.GRADES) gradeRefreshPending = false
        academicJobs[key] = viewModelScope.launch {
            fun publish(reading: AcademicUi) { mutable.update { if (it.account == account) it.copy(academic = it.academic + (key to reading)) else it } }
            val cached = try {
                if (changedRange && !state.value.demo) null
                else state.value.academic[key]?.data
                    ?: if (state.value.demo && kind == AcademicKind.GRADES) state.value.academic.values.firstNotNullOfOrNull { it.data?.takeIf { snapshot -> snapshot.grades != null } } ?: demoAcademic(kind)
                    else repository.store.academic(account, kind, scope)
            }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { publish(AcademicUi(error = message(e))); return@launch }
            publish(AcademicUi(cached))
            if (!force && !changedRange && !state.value.demo && !informationStale(InformationKind.valueOf(kind.name), cached?.fetchedAt)) return@launch
            if (state.value.demo && cached != null) return@launch
            publish(AcademicUi(cached, loading = true))
            try {
                if (!force && !changedRange && !state.value.demo) {
                    InformationRefreshGate.waitForSlot()
                    if (state.value.account != account || !informationStale(InformationKind.valueOf(kind.name), cached?.fetchedAt)) { publish(AcademicUi(cached)); return@launch }
                }
                val snapshot = if (state.value.demo) demoAcademic(kind) else repository.refreshAcademic(account, kind, scope, forceCatalog = forceCatalog)
                if (state.value.demo) repository.store.saveAcademic(account, kind, scope, snapshot)
                val notice = academicNotice(kind, scope, cached, snapshot)
                publish(AcademicUi(snapshot))
                if (kind == AcademicKind.GRADES && !state.value.demo) planAfterGrades()
                runCatching { syncSystems() }
                if (notice != null && state.value.account == account) mutable.update { it.copy(notice = notice) }
            } catch (e: CancellationException) { publish(AcademicUi(cached)); throw e }
            catch (e: Exception) { publish(AcademicUi(cached, error = message(e), needsLogin = e is SessionExpired || e is WebLoginRequired)) }
            finally { automaticAcademicKeys.remove(key) }
        }
        academicJobs[key]?.invokeOnCompletion { automaticAcademicKeys.remove(key) }
    }
    private fun demoAcademic(kind: AcademicKind): AcademicSnapshot {
        val courses = listOf(
            PlanCourse("d1", "MATH01", "高等数学", 5.0, 5.0, "88", true),
            PlanCourse("d2", "CS01", "程序设计", 4.0, 4.0, "92", true),
            PlanCourse("d3", "PHYS01", "大学物理", 4.0, 0.0, "", false)
        )
        return when (kind) {
            AcademicKind.EXAMS -> AcademicSnapshot(exams = listOf(Exam("e1", "高等数学", "MATH01", "期末考试", "${schoolToday().plusDays(14)} 09:00–11:00", "教1-101", "12", "携带学生证")))
            AcademicKind.GRADES -> AcademicSnapshot(grades = GradePage(listOf(Grade("g1", "高等数学", "MATH01", "5", "88", "3.8", "示例学期", listOf(GradePart("平时成绩", "90"), GradePart("期末成绩", "87"))), Grade("g2", "程序设计", "CS01", "4", "92", "4.2", "示例学期", emptyList())), GradeSummary(2, 9.0, 3.98)))
            AcademicKind.PLAN -> AcademicSnapshot(plan = StudyPlan("示例同学", "示例专业", "3.98", 160.0, 96.0, listOf(PlanSection("module1", "学科基础", 13.0, 9.0, courses, emptyList()))), catalog = listOf(CourseInfo("MATH01", "高等数学", "必修", "5", "80", "1"), CourseInfo("CS01", "程序设计", "必修", "4", "64", "2"), CourseInfo("PHYS01", "大学物理", "必修", "4", "64", "2")))
        }
    }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun demo() {
        if (!BuildConfig.DEMO_ENABLED) return
        cancelAcademic()
        action {
            val start = mondayOf(schoolToday()).minusWeeks(7)
            val semester = Semester("demo", "示例学期", start.toString())
            val courses = listOf(
                Triple("高等数学", 1, 1), Triple("大学英语", 2, 3), Triple("程序设计", 3, 5),
                Triple("大学物理", 4, 1), Triple("线性代数", 5, 3), Triple("体育", 2, 7), Triple("实验课程", 5, 9), Triple("课程讨论", 7, 5)
            ).flatMapIndexed { index, (name, day, period) -> (period..period + 1).map { Course("DEMO$index", name, "示例教师", "教${index % 3 + 1}-${100 + index}", day, it, (1..18).toList()) } }
            val table = Timetable("__demo__", semester, TableKind.STUDENT, courses, 12, start.toString(), System.currentTimeMillis())
            repository.store.save(table); repository.store.semesters(listOf(semester)); repository.store.select(table.account, semester.id, table.kind)
            accept(table)
        }
    }
}
