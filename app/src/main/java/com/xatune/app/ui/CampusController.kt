package com.xatune.app.ui

import com.xatune.app.data.*
import com.xatune.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant

data class CampusState(
    val account: String = "", val busy: Boolean = false, val error: String? = null,
    val dorm: Dorm? = null, val electric: Electricity? = null, val history: List<DailyBalance> = emptyList(),
    val projects: List<CampusOption> = emptyList(), val areas: List<CampusOption> = emptyList(), val buildings: List<CampusOption> = emptyList(), val rooms: List<CampusOption> = emptyList(),
    val project: CampusOption? = null, val area: CampusOption? = null, val building: CampusOption? = null, val room: CampusOption? = null,
    val promptedClasses: Boolean = true, val promptedExams: Boolean = true,
    val selecting: Boolean = false, val calendar: SchoolCalendar? = null, val reminders: ReminderSettings = ReminderSettings(), val autoElectric: Boolean = true
)
class CampusController(private val repository: CampusRepository, private val scope: CoroutineScope, private val sync: suspend () -> Unit) {
    private val mutable = MutableStateFlow(CampusState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var automaticForeground = true
    private var automaticCalendar = false
    fun resumeAutomaticUpdates() { automaticForeground = true }
    fun pauseAutomaticUpdates() { automaticForeground = false; if (automaticCalendar) job?.cancel() }
    suspend fun load() {
        val account = repository.store.account()
        if (account != state.value.account) job?.cancel()
        else if (state.value.busy) return
        val dorm = repository.selectedDorm(account)
        val value = CampusState(account = account, dorm = dorm, electric = repository.electricity(account), history = dorm?.let { repository.store.history(account, it.key) }.orEmpty(), calendar = repository.calendar(), reminders = repository.reminders(account), autoElectric = repository.autoElectricity(account),
            promptedClasses = repository.store.extra("promptedClasses:$account", Boolean::class.javaObjectType) ?: false,
            promptedExams = repository.store.extra("promptedExams:$account", Boolean::class.javaObjectType) ?: false)
        mutable.value = value
        if (account.isNotBlank() && account != "__demo__" && value.autoElectric && dorm != null && value.electric?.checkedAt?.let { Instant.ofEpochMilli(it).atZone(SchoolZone).toLocalDate() } != schoolToday()) refreshElectricity()
    }
    private fun action(block: suspend (String) -> Unit) {
        if (state.value.busy) return
        val account = state.value.account
        if (account.isBlank()) return
        mutable.update { it.copy(busy = true, error = null) }
        job = scope.launch {
            try { block(account) } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (state.value.account == account) mutable.update { it.copy(error = when (e) { is SessionExpired -> "校园服务登录已过期，请到设置重新登录"; else -> e.message?.take(160) ?: "操作失败，请重试" }) } }
            finally { if (state.value.account == account) mutable.update { it.copy(busy = false) } }
        }
    }
    fun acknowledgeReminders(classes: Boolean, enable: Boolean = false) = action { account ->
        if (classes) repository.store.extra("promptedClasses:$account", true)
        repository.store.extra("promptedExams:$account", true)
        val settings = state.value.reminders.let { if (enable) it.copy(classes = it.classes || classes, exams = true) else it }
        if (enable) repository.store.extra("reminders:$account", settings)
        mutable.update { it.copy(promptedClasses = it.promptedClasses || classes, promptedExams = true, reminders = settings) }
        if (enable) sync()
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun selectDorm() = action { account ->
        val projects = if (account == "__demo__") listOf(CampusOption("p", "示例电费")) else repository.projects(account)
        if (projects.isEmpty()) throw IllegalStateException("缴费平台未提供电费项目")
        mutable.update { it.copy(selecting = true, projects = projects, areas = emptyList(), buildings = emptyList(), rooms = emptyList(), project = null, area = null, building = null, room = null) }
    }
    fun project(value: CampusOption) = action { account ->
        mutable.update { it.copy(project = value, area = null, building = null, room = null, areas = emptyList(), buildings = emptyList(), rooms = emptyList()) }
        val list = if (account == "__demo__") listOf(CampusOption("a", "示例校区")) else repository.areas(account, value.id)
        mutable.update { it.copy(areas = list) }
    }
    fun area(value: CampusOption) = action { account ->
        val project = state.value.project ?: return@action
        mutable.update { it.copy(area = value, building = null, room = null, buildings = emptyList(), rooms = emptyList()) }
        val list = if (account == "__demo__") listOf(CampusOption("b", "9号楼")) else repository.buildings(account, project.id, value.id)
        mutable.update { it.copy(buildings = list) }
    }
    fun building(value: CampusOption) = action { account ->
        val s = state.value
        mutable.update { it.copy(building = value, room = null, rooms = emptyList()) }
        val list = if (account == "__demo__") listOf(CampusOption("r", "328")) else repository.rooms(account, s.project!!.id, s.area!!.id, value.id)
        mutable.update { it.copy(rooms = list) }
    }
    fun room(value: CampusOption) { mutable.update { it.copy(room = value) } }
    fun closeSelector() { if (!state.value.busy) mutable.update { it.copy(selecting = false) } }
    fun bindDorm() = action { account ->
        val s = state.value
        val dorm = Dorm(s.project ?: return@action, s.area ?: return@action, s.building ?: return@action, s.room ?: return@action)
        repository.saveDorm(account, dorm)
        mutable.update { it.copy(dorm = dorm, electric = null, selecting = false, history = emptyList()) }
        refreshNow(account)
    }
    private suspend fun refreshNow(account: String) {
        val dorm = repository.selectedDorm(account) ?: return
        val value = if (account == "__demo__") Electricity(dorm, "68.50", System.currentTimeMillis()).also { repository.store.extra("electric:$account", it); repository.store.record(account, it) } else repository.refreshElectricity(account)
        val history = repository.store.history(account, dorm.key)
        if (state.value.account == account) mutable.update { it.copy(electric = value, history = history) }
        sync()
    }
    fun refreshElectricity() = action(::refreshNow)
    fun unbind() = action { account -> repository.clearDorm(account); mutable.update { it.copy(dorm = null, electric = null, history = emptyList()) }; sync() }
    fun autoElectric(enabled: Boolean) = action { account -> repository.store.extra("autoElectric:$account", enabled); mutable.update { it.copy(autoElectric = enabled) } }
    fun saveReminders(settings: ReminderSettings) = action { account ->
        repository.store.extra("reminders:$account", settings)
        mutable.update { it.copy(reminders = settings) }
        sync()
    }
    suspend fun originalCalendar() = repository.originalCalendar()
    fun saveCalendarCorrection(url: String, draft: CalendarCorrection, saved: () -> Unit) = action {
        val result = repository.saveCalendarCorrection(url, draft)
        mutable.update { it.copy(calendar = result) }
        sync()
        saved()
    }
    fun calendar(force: Boolean = false) = action { account ->
        if (!force && !automaticForeground) return@action
        val cached = state.value.calendar
        val reduced = repository.store.extra("reduceAcademicRefreshFrequency", Boolean::class.javaObjectType) == true
        if (!force && reduced && cached != null && System.currentTimeMillis() - cached.fetchedAt < 7L * 86_400_000) return@action
        if (!force && !reduced && account != "__demo__" && !repository.shouldRefreshCalendar()) return@action
        val result = if (account == "__demo__") {
            val year = schoolToday().year
            SchoolCalendar("示例校历", CalendarParser.index, "demo://calendar", "", "示例数据，仅用于预览。", listOf(SemesterSpan("第一学期", "$year-09-01", "${year + 1}-01-20"), SemesterSpan("第二学期", "${year + 1}-02-20", "${year + 1}-07-10")), System.currentTimeMillis(), "${year + 1}-08-29").also { repository.store.extra("schoolCalendar", it) }.let { repository.applyCorrection(it) }
        } else if (force || reduced) repository.refreshCalendar() else {
            automaticCalendar = true
            try {
                InformationRefreshGate.waitForSlot()
                if (!automaticForeground) return@action
                repository.automaticCalendar() ?: return@action
            } finally { automaticCalendar = false }
        }
        mutable.update { it.copy(calendar = result) }; sync()
    }
}
