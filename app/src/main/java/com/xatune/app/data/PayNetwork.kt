package com.xatune.app.data

import com.xatune.app.model.*
import com.google.gson.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class PayNetwork(private val cookies: CookieJar, private val base: String = "https://jfpay.xatu.edu.cn/", private val auth: String = "https://authserver.xatu.edu.cn/authserver/login") {
    private val root = base.toHttpUrl()
    private val client = OkHttpClient.Builder().cookieJar(cookies).addNetworkInterceptor(CasHttps).followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    private val tokenName = "datalook_reimbursement_token"
    companion object {
        fun tokenFromUrl(url: String): String? = Regex("""[?#&]token=([^&#]+)""").find(url)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
        fun requestBody(projectId: String, factoryCode: String? = null, params: Map<String, String> = emptyMap()): Map<String, Any> {
            if (params.isEmpty()) return mapOf("projectId" to projectId)
            if (usesNestedJsonData(factoryCode)) return mapOf("projectId" to projectId, "jsonData" to params)
            return buildMap {
                put("projectId", projectId)
                putAll(params)
            }
        }
        fun list(data: JsonElement): List<JsonObject> {
            if (data.isJsonArray) return data.asJsonArray.filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject)
            if (data.isJsonObject) {
                data.asJsonObject.entrySet().firstOrNull { it.value.isJsonArray }?.let { return list(it.value) }
                return listOf(data.asJsonObject)
            }
            return emptyList()
        }
    }
    private fun token() = cookies.loadForRequest(root).firstOrNull { it.name == tokenName }?.value
    private fun storeToken(value: String, expired: Boolean = false) {
        cookies.saveFromResponse(root, listOf(Cookie.Builder().name(tokenName).value(value).hostOnlyDomain(root.host).path("/").apply { if (root.isHttps) secure(); if (expired) expiresAt(0) }.build()))
    }
    fun authenticate() {
        var url = auth.toHttpUrl().newBuilder().addQueryParameter("service", base + "casLogin/").build()
        repeat(14) {
            if (url.host !in setOf(root.host, auth.toHttpUrl().host)) throw java.io.IOException("缴费平台跳转地址异常")
            tokenFromUrl(url.toString())?.let { storeToken(it); return }
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val html = response.body?.string().orEmpty()
                response.header("Location")?.let { location ->
                    val next = url.resolve(location) ?: throw ParseFailure("缴费登录跳转地址无效")
                    if (next.host !in setOf(root.host, auth.toHttpUrl().host)) throw java.io.IOException("缴费平台跳转地址异常")
                    tokenFromUrl(next.toString())?.let { storeToken(it); return }
                    url = next
                    return@repeat
                }
                if (!response.isSuccessful) throw java.io.IOException("缴费平台返回 HTTP ${response.code}")
                if (EamsParser.isLogin(html) || url.host == auth.toHttpUrl().host) throw SessionExpired()
                val jump = Regex("""jzcas/casLogin/[0-9]+/[0-9]+""").find(html)?.value
                if (jump != null) { url = (base + "api/pay/web/" + jump).toHttpUrl(); return@repeat }
                if (token() != null) return
                throw SessionExpired()
            }
        }
        throw SessionExpired()
    }
    private fun api(path: String, body: Map<String, Any>? = null): JsonElement {
        if (token() == null) authenticate()
        repeat(2) { attempt ->
            val url = (base + "api/" + path).toHttpUrl()
            val request = Request.Builder().url(url).header("X-Token", token().orEmpty()).header("Accept", "application/json")
            if (body != null) request.post(Gson().toJson(body).toRequestBody("application/json; charset=utf-8".toMediaType()))
            client.newCall(request.build()).execute().use { response ->
                if (response.code in listOf(401, 403) || response.isRedirect) {
                    if (attempt == 0) { storeToken("", true); authenticate(); return@repeat }; throw SessionExpired()
                }
                if (!response.isSuccessful) throw java.io.IOException("缴费平台返回 HTTP ${response.code}")
                val raw = response.body?.string().orEmpty()
                val json = runCatching { JsonParser.parseString(raw) }.getOrElse { throw ParseFailure("缴费平台返回内容异常，请稍后重试") }
                if (json.isJsonArray) return json
                if (!json.isJsonObject) throw ParseFailure("缴费平台返回结构异常")
                val obj = json.asJsonObject
                val code = obj.get("messageCode")?.asString.orEmpty()
                if (code == "0") return obj.get("data") ?: JsonNull.INSTANCE
                if (code in listOf("-1", "-2", "-3", "-4", "-5", "50014")) {
                    if (attempt == 0) { storeToken("", true); authenticate(); return@repeat }; throw SessionExpired()
                }
                throw IllegalStateException(obj.get("message")?.takeUnless { it.isJsonNull }?.asString?.take(120) ?: "缴费平台暂未返回数据")
            }
        }
        throw SessionExpired()
    }
    private fun JsonObject.text(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    fun projects(): List<CampusOption> = list(api("pay/project/getAllProjectList")).filter { it.text("proModelUrl") in listOf("electric", "electricNewCapec") }.map { CampusOption(it.text("id"), it.text("projectName").trim().ifBlank { "电费" }, it.text("factoryCode").trim().takeIf { code -> code.isNotEmpty() }) }.filter { it.id.isNotBlank() }
    private fun options(path: String, args: Map<String, Any>, id: String, name: String) = list(api("pay/web/payEleCostController/$path", args)).map { CampusOption(it.text(id).trim(), it.text(name).trim()) }.filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }
    fun areas(project: String) = options("querySchoolList", requestBody(project), "schoolid", "schoolname")
    fun buildings(project: String, area: String, factoryCode: String? = null) = options("queryBuildingList", requestBody(project, factoryCode, mapOf("areaid" to area)), "buildid", "buildname")
    fun rooms(project: String, area: String, building: String, factoryCode: String? = null) = options("queryRoomList", requestBody(project, factoryCode, mapOf("areaid" to area, "buildid" to building)), "roomid", "roomname")
    fun balance(dorm: Dorm): String {
        val row = list(api("pay/web/payEleCostController/querySydl", requestBody(dorm.project.id, dorm.factoryCode, mapOf("areaid" to dorm.area.id, "buildid" to dorm.building.id, "roomid" to dorm.room.id)))).firstOrNull() ?: throw ParseFailure("宿舍余额暂未返回")
        return balanceAmount(row.get("oddl")?.takeUnless { it.isJsonNull }?.asString, row.get("suml")?.takeUnless { it.isJsonNull }?.asString)
    }
}
