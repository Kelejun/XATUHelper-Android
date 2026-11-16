package com.xatune.app.data

import com.xatune.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

class TimetableRepository(val store: LocalStore, private val vault: SecretVault, private val cookies: SchoolCookies, private val network: SchoolNetwork) {
    private val sessionMutex = Mutex()
    private val epoch = SessionEpoch()
    fun sessionVersion(): Long = epoch.capture()
    fun invalidateSession() { epoch.invalidate() }
    suspend fun checkSession(version: Long) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); epoch.check(version) }
    private suspend fun <T> guarded(newSession: Boolean = false, block: suspend (Long) -> T): T {
        val version = if (newSession) epoch.invalidate() else epoch.capture()
        return withContext(Dispatchers.IO) { sessionMutex.withLock {
            checkSession(version)
            val context = kotlinx.coroutines.currentCoroutineContext()
            network.sessionCheck = { context.ensureActive(); epoch.check(version) }
            try { block(version).also { checkSession(version) } }
            catch (e: Exception) { checkSession(version); throw e }
            finally { network.sessionCheck = {} }
        } }
    }
    private var shell: Shell? = null
    private val attempts = ArrayDeque<Long>()
    suspend fun saveBrowserCookies() = guarded { cookies.persist() }
    suspend fun initialize() = guarded { cookies.restore() }
    private suspend fun <T> authenticated(version: Long, operation: suspend () -> T): T {
        checkSession(version)
        try { return operation().also { checkSession(version) } } catch (expired: SessionExpired) {
            checkSession(version)
            val now = System.currentTimeMillis()
            while (attempts.isNotEmpty() && now - attempts.first() > 3_600_000) attempts.removeFirst()
            if (attempts.size >= 3 || (attempts.lastOrNull()?.let { now - it < 60_000 } == true)) throw SessionExpired()
            attempts.addLast(now)
            // Try existing CAS SSO first; password submission is only a bounded fallback.
            if (!network.renew().also { checkSession(version) }) {
                val saved = vault.load().credentials ?: throw SessionExpired()
                network.login(saved.account, saved.password)
                checkSession(version)
            }
            shell = null
            return operation()
        }
    }
    suspend fun <T> withSession(account: String, operation: () -> T): T = guarded { version ->
        if (store.account() != account || account.isBlank()) throw SessionExpired()
        authenticated(version) { operation() }.also { checkSession(version); cookies.persist() }
    }
    suspend fun beginWebLogin() = guarded(newSession = true) { shell = null; cookies.clear() }
    suspend fun login(account: String, password: String, firstMonday: LocalDate? = null): Timetable = guarded(newSession = true) { version ->
        cookies.clear(); shell = null
        network.login(account, password)
        checkSession(version)
        if (store.account() != account) store.clear()
        val result = fetch(account, "", TableKind.STUDENT, firstMonday, version)
        checkSession(version)
        cookies.persist(Credentials(account, password))
        result
    }
    suspend fun completeWebLogin(account: String, firstMonday: LocalDate? = null): Timetable = guarded(newSession = true) { version ->
        shell = null
        if (store.account() != account) store.clear()
        val result = fetch(account, "", TableKind.STUDENT, firstMonday, version)
        checkSession(version)
        // A user may change the identity in the browser. Never retain an unverified typed password.
        cookies.persist(credentials = null)
        result
    }
    suspend fun refresh(account: String, semester: String, kind: TableKind): Timetable = guarded { version ->
        if (store.account() != account || account.isBlank()) throw SessionExpired()
        authenticated(version) { fetch(account, semester, kind, version = version) }.also { checkSession(version); cookies.persist() }
    }
    suspend fun refreshAcademic(account: String, kind: AcademicKind, scope: String, forceCatalog: Boolean = false): AcademicSnapshot = guarded { version ->
        if (store.account() != account) throw SessionExpired()
        val snapshot = when (kind) {
            AcademicKind.EXAMS -> AcademicSnapshot(exams = authenticated(version) { network.exams(scope) })
            AcademicKind.GRADES -> AcademicSnapshot(grades = authenticated(version) { network.grades(scope, scope == "all") })
            AcademicKind.PLAN -> {
                // Entering this screen must not burn CAS password attempts automatically.
                val plan = network.plan()
                checkSession(version)
                val previous = store.academic(account, kind, "all")
                var warning: String? = null
                val catalog = previous?.catalog?.takeIf { it.isNotEmpty() && !forceCatalog } ?: try { network.catalog() } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    warning = "教学计划暂未读取成功，学期筛选可能不完整。"
                    previous?.catalog.orEmpty()
                }
                AcademicSnapshot(plan = plan, catalog = catalog, catalogWarning = warning)
            }
        }
        checkSession(version)
        val owned = snapshot.copy(account = account, scope = scope)
        if (kind == AcademicKind.GRADES) {
            val fingerprint = gradeFingerprint(snapshot.grades?.grades.orEmpty())
            val previous = store.extra("academic.gradeFingerprint:$account:$scope", String::class.java)
            if (previous?.let { it != fingerprint } ?: snapshot.grades?.grades.orEmpty().isNotEmpty()) store.extra("academic.pendingPlan:$account", true)
            store.extra("academic.gradeFingerprint:$account:$scope", fingerprint)
        }
        if (kind == AcademicKind.PLAN && snapshot.plan?.sections?.isNotEmpty() == true) store.extra("academic.pendingPlan:$account", null)
        checkSession(version)
        store.saveAcademic(account, kind, scope, snapshot)
        checkSession(version)
        cookies.persist()
        owned
    }
    private suspend fun fetch(account: String, semesterId: String, kind: TableKind, firstMonday: LocalDate? = null, version: Long): Timetable {
        val info = network.shell().also { shell = it }
        checkSession(version)
        val semesters = network.semesters(info).ifEmpty { listOf(Semester(info.semesterId, "当前学期")) }
        val target = semesterId.ifEmpty { info.semesterId }
        val semester = semesters.firstOrNull { it.id == target } ?: Semester(target, "学期 $target")
        checkSession(version)
        val parsed = network.table(info, target, kind)
        checkSession(version)
        val cached = store.table(account, target, kind)
        val start = firstMonday?.let(::mondayOf) ?: store.monday(account, target)?.let(LocalDate::parse) ?: runCatching { LocalDate.parse(semester.startDate?.take(10)) }.getOrNull()?.let(::mondayOf)
            ?: cached?.monday?.let(LocalDate::parse)
            ?: if (target == info.semesterId) runCatching { network.officialWeek() }.getOrNull()?.let { mondayOf(schoolToday()).minusWeeks((it - 1).toLong()) } else null
            ?: throw ParseFailure("未取得学期开学日期。请先校准第一周周一，再刷新课表。")
        checkSession(version)
        if (firstMonday != null) store.monday(account, target, mondayOf(firstMonday).toString())
        val table = Timetable(account, semester, kind, parsed.courses, parsed.unitCount, start.toString(), System.currentTimeMillis())
        checkSession(version)
        store.save(table)
        checkSession(version)
        store.extra("current:$account", info.semesterId)
        store.semesters(semesters)
        store.select(account, target, kind)
        return table
    }
    suspend fun setMonday(account: String, semester: String, date: LocalDate) { store.monday(account, semester, mondayOf(date).toString()) }
    suspend fun calibrate(table: Timetable, monday: LocalDate): Timetable = withContext(Dispatchers.IO) {
        val base = store.baseTable(table.account, table.semester.id, table.kind) ?: table
        base.copy(monday = mondayOf(monday).toString()).also { store.save(it) }
    }
    suspend fun logout(onCleared: suspend () -> Unit = {}) {
        val version = epoch.invalidate()
        withContext(Dispatchers.IO) {
            store.clear()
            checkSession(version)
            onCleared()
            sessionMutex.withLock {
                checkSession(version)
                shell = null; attempts.clear(); cookies.clear()
            }
        }
    }
}
