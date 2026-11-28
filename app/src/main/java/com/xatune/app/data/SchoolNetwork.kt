package com.xatune.app.data

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

object SchoolUrls {
    const val eams = "http://jwgl2018.xatu.edu.cn/eams/"
    val login = "https://authserver.xatu.edu.cn/authserver/login".toHttpUrl().newBuilder().addQueryParameter("service", eams + "homeExt.action").build().toString()
    fun trusted(url: String): Boolean = runCatching {
        val uri = url.toHttpUrl()
        (uri.scheme == "https" && (uri.host == "xatu.edu.cn" || uri.host.endsWith(".xatu.edu.cn"))) ||
            (uri.scheme == "http" && uri.host in setOf("jwgl2018.xatu.edu.cn", "self.xatu.edu.cn") && uri.port == 80)
    }.getOrDefault(false)
}

/** One CookieManager is shared by OkHttp and WebView, preserving HttpOnly and /eams paths. */
class SchoolCookies(private val manager: CookieManager, private val vault: SecretVault) : CookieJar {
    private val urls = listOf(SchoolUrls.login, "https://authserver.xatu.edu.cn/", SchoolUrls.eams, SchoolUrls.eams + "homeExt.action", SchoolUrls.eams + "courseTableForStd.action", "https://jfpay.xatu.edu.cn/", "https://jfpay.xatu.edu.cn/api/")
    private val observedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun observe(url: String) { if (SchoolUrls.trusted(url)) observedUrls.add(url) }
    private fun onMainAndWait(block: (CountDownLatch) -> Unit) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { block(latch) }
        check(latch.await(10, TimeUnit.SECONDS)) { "Cookie 同步超时，请重试" }
    }
    // Older WebViews cannot export cookie attributes. Preserve original Set-Cookie metadata
    // received by OkHttp instead of reconstructing it from the attribute-free Cookie header.
    private var captured: MutableMap<String, CookieRecord>? = null
    private fun cookieKey(cookie: Cookie) = "${cookie.name}|${cookie.domain}|${cookie.path}|${cookie.hostOnly}"
    private fun capturedCookies(): MutableMap<String, CookieRecord> = captured ?: linkedMapOf<String, CookieRecord>().also { records ->
        vault.load().cookies.forEach { record ->
            record.url.toHttpUrlOrNull()?.let { url -> Cookie.parse(url, restoreValue(record)) }?.let { cookie ->
                if (cookie.expiresAt > System.currentTimeMillis()) records[cookieKey(cookie)] = record
            }
        }
        captured = records
    }
    private fun restoreValue(record: CookieRecord): String {
        if (record.hostOnly == false) return record.value
        val host = record.url.toHttpUrlOrNull()?.host ?: return record.value
        // Chromium exports a host-only cookie with a plain domain, and a domain cookie
        // with a leading dot. Replaying Domain=host would broaden a host-only cookie.
        return record.value.split(';').filterNot { attribute ->
            val part = attribute.trim()
            part.startsWith("domain=", ignoreCase = true) && part.substringAfter('=').trim().equals(host, ignoreCase = true)
        }.joinToString(";")
    }
    @Synchronized fun restore() {
        captured = null // Re-read the latest export rather than replaying stale in-memory metadata.
        capturedCookies().values.forEach { record -> onMainAndWait { done -> manager.setCookie(record.url, restoreValue(record)) { done.countDown() } } }
    }
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val records = capturedCookies()
        cookies.forEach { cookie ->
            onMainAndWait { done -> manager.setCookie(url.toString(), cookie.toString()) { done.countDown() } }
            if (cookie.expiresAt > System.currentTimeMillis()) records[cookieKey(cookie)] = CookieRecord(url.toString(), cookie.toString(), cookie.hostOnly)
            else records.remove(cookieKey(cookie))
        }
        manager.flush()
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = manager.getCookie(url.toString()).orEmpty().split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    @Synchronized fun snapshot(): List<CookieRecord> {
        manager.flush()
        val records = if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
            (urls + observedUrls + capturedCookies().values.map { it.url }).distinct().flatMap { url -> CookieManagerCompat.getCookieInfo(manager, url).map { value ->
                val domain = value.split(';').map(String::trim).firstOrNull { it.startsWith("domain=", true) }?.substringAfter('=')?.trim()
                CookieRecord(url, value, domain == null || domain.equals(url.toHttpUrl().host, ignoreCase = true))
            } }.distinct()
        } else capturedCookies().values.filter { record ->
            val url = record.url.toHttpUrlOrNull()
            val cookie = url?.let { Cookie.parse(it, record.value) }
            cookie != null && cookie.expiresAt > System.currentTimeMillis() &&
                loadForRequest(url).any { it.name == cookie.name && it.value == cookie.value }
        } // Browser-only cookies remain in CookieManager's own store on older WebViews.
        return records
    }
    @Synchronized fun persist(credentials: Credentials? = vault.load().credentials) { vault.save(Secrets(credentials, snapshot())) }
    @Synchronized fun clear() {
        captured = linkedMapOf()
        onMainAndWait { done -> manager.removeAllCookies { done.countDown() } }
        manager.flush()
        vault.clear()
    }
}

object CasCrypto {
    private const val chars = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
    private val random = SecureRandom()
    private fun randomText(length: Int) = (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    fun encrypt(password: String, salt: String, prefix: String = randomText(64), iv: String = randomText(16)): String {
        require(salt.toByteArray(Charsets.UTF_8).size == 16 && iv.length == 16 && prefix.length == 64) { "认证页面的加密参数异常，请使用网页登录" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(iv.toByteArray(Charsets.UTF_8)))
        return Base64.getEncoder().encodeToString(cipher.doFinal((prefix + password).toByteArray(Charsets.UTF_8)))
    }
}

class SchoolNetwork(cookies: CookieJar, private val base: String = SchoolUrls.eams, private val loginUrl: String = SchoolUrls.login) {
    @Volatile internal var sessionCheck: () -> Unit = {}
    private val client = OkHttpClient.Builder().cookieJar(cookies).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .addNetworkInterceptor(CasHttps)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (request.url.host !in setOf(loginUrl.toHttpUrl().host, base.toHttpUrl().host)) throw java.io.IOException("认证跳转到了未支持的地址，请使用网页登录")
            chain.proceed(request.newBuilder().header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36 XATUHelper/0.1").build())
        }.build()
    private fun request(url: String, form: Map<String, String>? = null, auth: Boolean = true): Pair<String, String> {
        sessionCheck()
        val builder = Request.Builder().url(CasHttps.upgrade(url))
        form?.let { values -> builder.post(FormBody.Builder().apply { values.forEach { (k, v) -> add(k, v) } }.build()) }
        val response = try { client.newCall(builder.build()).execute() } catch (e: java.net.ProtocolException) { throw SessionExpired() }
        response.use {
            sessionCheck()
            if (!it.isSuccessful) throw java.io.IOException("学校服务返回 HTTP ${it.code}，请稍后重试")
            val html = it.body?.string() ?: throw ParseFailure("学校服务没有返回内容")
            sessionCheck()
            val finalUrl = it.request.url.toString()
            if (auth && (it.request.url.host != base.toHttpUrl().host || EamsParser.isLogin(html))) throw SessionExpired()
            return html to finalUrl
        }
    }
    fun login(account: String, password: String) {
        val (html, landed) = request(loginUrl, auth = false)
        if (landed.toHttpUrl().host == base.toHttpUrl().host && !EamsParser.isLogin(html) && !html.contains("execution")) return
        if (captchaRequired(html)) throw WebLoginRequired()
        val document = Jsoup.parse(html)
        // The live CAS page has QR, FIDO, SMS and password forms, with duplicate IDs.
        val doc = document.selectFirst("form:has(input[name=password])") ?: document
        val salt = doc.selectFirst("#pwdEncryptSalt")?.attr("value") ?: throw WebLoginRequired()
        val execution = doc.selectFirst("#execution, input[name=execution]")?.attr("value") ?: throw WebLoginRequired()
        val fields = linkedMapOf<String, String>()
        doc.select("input[type=hidden][name]").forEach { fields[it.attr("name")] = it.attr("value") }
        fields.putAll(mapOf("username" to account, "password" to CasCrypto.encrypt(password, salt), "passwordText" to "", "captcha" to "", "_eventId" to "submit", "cllt" to "userNameLogin", "dllt" to "generalLogin", "execution" to execution, "rmShown" to "1"))
        val (result, finalUrl) = request(loginUrl, fields, auth = false)
        if (finalUrl.toHttpUrl().host != base.toHttpUrl().host || EamsParser.isLogin(result)) {
            if (captchaRequired(result)) throw WebLoginRequired()
            val message = Jsoup.parse(result).select("#msg, .auth_error, #errorMsg").text().ifEmpty { "登录未成功，请检查账号密码或使用网页登录" }
            if ("验证码" in message || "动态码" in message) throw WebLoginRequired()
            throw IllegalStateException(message.take(180))
        }
    }
    fun renew(): Boolean = try {
        val (html, url) = request(loginUrl, auth = false)
        url.toHttpUrl().host == base.toHttpUrl().host && !EamsParser.isLogin(html) && !html.contains("execution")
    } catch (_: java.io.IOException) { false }
    private fun resolveUrl(raw: String, currentUrl: String): HttpUrl? {
        val cleaned = raw.trim().trim('?', '&')
        if (cleaned.isEmpty()) return null
        if (cleaned.startsWith("http://", ignoreCase = true) || cleaned.startsWith("https://", ignoreCase = true)) {
            return cleaned.toHttpUrlOrNull()
        }
        val baseUrl = base.toHttpUrl()
        if (cleaned.startsWith("/eams/") || cleaned.startsWith(baseUrl.encodedPath)) {
            return baseUrl.resolve(cleaned)
        }
        if (cleaned.startsWith("/")) {
            return (base.trimEnd('/') + cleaned).toHttpUrlOrNull()
        }
        return currentUrl.toHttpUrlOrNull()?.resolve(cleaned) ?: baseUrl.resolve(cleaned)
    }
    private fun appendQuery(url: HttpUrl, items: List<Pair<String, String>>): HttpUrl {
        if (items.isEmpty()) return url
        val existingNames = url.queryParameterNames
        val missing = items.filter { (name, _) -> name !in existingNames }
        if (missing.isEmpty()) return url
        return url.newBuilder().apply {
            missing.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
    }
    private fun resolveMultiProjectIfNeeded(
        html: String,
        finalUrl: String,
        modulePath: String,
        query: Map<String, String>
    ): Pair<String, String?> {
        val wrapper = EamsParser.parseMultiProjectWrapper(html, modulePath) ?: return html to null
        var currentUrl = finalUrl
        var targetInnerPath = wrapper.innerPath
        var resolvedProjectId = wrapper.projectId

        val switchHref = wrapper.switchHref
        if (switchHref != null) {
            val switchUrl = resolveUrl(switchHref, currentUrl)
            if (switchUrl != null) {
                val (switchedHtml, switchedUrl) = request(switchUrl.toString())
                currentUrl = switchedUrl
                val updated = EamsParser.parseMultiProjectWrapper(switchedHtml, modulePath)
                if (updated != null) {
                    targetInnerPath = updated.innerPath
                    resolvedProjectId = updated.projectId ?: resolvedProjectId
                } else {
                    return switchedHtml to resolvedProjectId
                }
            }
        }

        val rawInnerUrl = resolveUrl(targetInnerPath, currentUrl) ?: throw ParseFailure("无法解析教务内页地址")
        val extraQuery = mutableListOf<Pair<String, String>>()
        if (!resolvedProjectId.isNullOrEmpty() &&
            !targetInnerPath.contains("projectId=", ignoreCase = true) &&
            !targetInnerPath.contains("project.id=", ignoreCase = true) &&
            "projectId" !in rawInnerUrl.queryParameterNames &&
            "project.id" !in rawInnerUrl.queryParameterNames
        ) {
            extraQuery.add("projectId" to resolvedProjectId)
        }
        query.forEach { (key, value) -> extraQuery.add(key to value) }
        val innerUrl = appendQuery(rawInnerUrl, extraQuery)
        return request(innerUrl.toString()).first to resolvedProjectId
    }
    private fun pageWithProject(path: String, query: Map<String, String> = emptyMap()): Pair<String, String?> {
        val url = (base + path).toHttpUrl().newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val (html, finalUrl) = request(url.toString())
        val modulePath = base.toHttpUrl().encodedPath + path
        return resolveMultiProjectIfNeeded(html, finalUrl, modulePath, query)
    }
    fun shell(): com.xatune.app.model.Shell {
        val (html, resolvedProject) = pageWithProject("courseTableForStd.action")
        val fetchedProject = try {
            request(base + "dataQuery.action", mapOf("dataType" to "projectId")).first.trim().trim('"', '\'')
        } catch (e: SessionExpired) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        val project = fetchedProject.takeIf { it.matches(Regex("\\d+")) } ?: resolvedProject.orEmpty()
        return EamsParser.shell(html, project)
    }
    fun semesters(shell: com.xatune.app.model.Shell) = EamsParser.semesters(request(base + "dataQuery.action", mapOf("tagId" to shell.tagId, "dataType" to "semesterCalendar", "value" to shell.semesterId, "empty" to "false")).first)
    fun officialWeek(): Int? = EamsParser.officialWeek(request(base + "homeExt!main.action").first)
    fun table(shell: com.xatune.app.model.Shell, semester: String, kind: com.xatune.app.model.TableKind): com.xatune.app.model.ParsedTable {
        val id = if (kind == com.xatune.app.model.TableKind.STUDENT) shell.studentId else shell.classId ?: throw IllegalStateException("这个账号没有班级课表")
        return TimetableParser.parse(request(base + "courseTableForStd!courseTable.action", mapOf("ignoreHead" to "1", "setting.kind" to kind.wire, "startWeek" to "", "project.id" to shell.projectId, "semester.id" to semester, "ids" to id)).first)
    }
    private fun page(path: String, query: Map<String, String> = emptyMap()): String = pageWithProject(path, query).first
    fun exams(semester: String): List<com.xatune.app.model.Exam> {
        val shell = page("stdExamTable.action", mapOf("semester.id" to semester))
        val batches = Jsoup.parse(shell).select("select[name=examBatch.id] option[value]").map { it.attr("value") }.filter { it.toLongOrNull() != null }
        var validEmpty = false
        var failure: ParseFailure? = null
        for (batch in (listOf("0") + batches).distinct()) {
            try {
                val result = AcademicParser.exams(page("stdExamTable!examTable.action", mapOf("examBatch.id" to batch, "semester.id" to semester)))
                if (result.isNotEmpty()) return result
                validEmpty = true
            } catch (e: ParseFailure) { failure = e }
        }
        if (validEmpty) return emptyList()
        throw failure ?: ParseFailure("未读取到考试安排")
    }
    fun grades(semester: String, all: Boolean): com.xatune.app.model.GradePage {
        fun parse(html: String) = AcademicParser.grades(html)
        if (all) return parse(page("teach/grade/course/person!historyCourseGrade.action", mapOf("projectType" to "MAJOR")))
        val shell = page("teach/grade/course/person.action", mapOf("semester.id" to semester))
        val resolved = Regex("""semesterCalendar\s*\(\s*\{[^}]*?value\s*:\s*["'](\d+)["']""").find(shell)?.groupValues?.get(1) ?: semester
        return parse(page("teach/grade/course/person!search.action", mapOf("semesterId" to resolved, "projectType" to "", "looked" to "yes")))
    }
    fun plan() = AcademicParser.plan(page("myPlanCompl.action"))
    fun catalog() = AcademicParser.catalog(page("myPlan.action"))
    companion object {
        fun captchaRequired(html: String): Boolean = Regex("""captchaSwitch\s*=\s*["']?(1|true)\b""", RegexOption.IGNORE_CASE).containsMatchIn(html)
    }
}
