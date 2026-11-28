package com.xatune.app.data

import com.xatune.app.model.*
import org.jsoup.Jsoup

data class ProjectTab(
    val name: String,
    val href: String?,
    val projectId: String?,
    val isSelected: Boolean
)

data class MultiProjectWrapper(
    val tabs: List<ProjectTab>,
    val switchHref: String?,
    val innerPath: String,
    val projectId: String?
)

object EamsParser {
    private val projectIdRegex = Regex("""(?:projectId|project\.id)=([0-9]+)""", RegexOption.IGNORE_CASE)
    private val selectedWordRegex = Regex("""\bselected\b""", RegexOption.IGNORE_CASE)
    private val innerIndexRegex = Regex("""["']((?:https?://[^"'\s<>()]+)?[^"'\s<>()'"]*!innerIndex\.action(?:\?[^"'\s<>()'"]*)?)["']""")

    fun isLogin(html: String): Boolean = Jsoup.parse(html).select("#pwdEncryptSalt, input[name=password], form[action*=loginExt]").isNotEmpty()
    fun parseMultiProjectWrapper(html: String, modulePath: String): MultiProjectWrapper? {
        val navMenus = Jsoup.parse(html).select("div.navmenu")
        if (navMenus.isEmpty()) return null

        val tabs = navMenus.firstNotNullOfOrNull { nav ->
            val parsed = nav.select("a").map { link ->
                val name = link.text().replace('\u00a0', ' ').trim()
                val isSelected = link.hasClass("selected") || link.hasAttr("selected") ||
                    link.attributes().any { !it.key.equals("href", ignoreCase = true) && selectedWordRegex.containsMatchIn(it.value) }
                val rawHref = link.attr("href").trim()
                val href = rawHref.takeIf {
                    it.isNotEmpty() && !it.startsWith("javascript:", ignoreCase = true) && !it.startsWith("#")
                }
                val projectId = href?.let { projectIdRegex.find(it)?.groupValues?.get(1) }
                ProjectTab(name, href, projectId, isSelected)
            }
            val hasProjectSwitchLink = parsed.any { tab ->
                val href = tab.href ?: return@any false
                href.contains("projectId=", ignoreCase = true) ||
                    href.contains("project.id=", ignoreCase = true) ||
                    href.contains("!index.action", ignoreCase = true)
            }
            parsed.takeIf { hasProjectSwitchLink }
        } ?: return null

        val preferredTab = tabs.firstOrNull { it.name == "本科" }
            ?: tabs.firstOrNull { "本科" in it.name }
            ?: tabs.firstOrNull { "主修" in it.name }
        val switchHref = if (preferredTab != null && !preferredTab.isSelected) preferredTab.href else null

        val extractedInner = innerIndexRegex.find(html)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?.trim('?', '&')
        val switchProjectId = switchHref?.let { projectIdRegex.find(it)?.groupValues?.get(1) }
        val innerPath = extractedInner?.takeIf { it.isNotEmpty() }
            ?: innerIndexPath(modulePath, switchProjectId ?: preferredTab?.projectId)
        val projectId = switchProjectId
            ?: projectIdRegex.find(innerPath)?.groupValues?.get(1)
            ?: preferredTab?.projectId

        return MultiProjectWrapper(
            tabs = tabs,
            switchHref = switchHref,
            innerPath = innerPath,
            projectId = projectId
        )
    }
    private fun innerIndexPath(modulePath: String, projectId: String?): String {
        val barePath = modulePath.substringBefore('?')
        val inner = when {
            barePath.endsWith("!index.action") -> barePath.removeSuffix("!index.action") + "!innerIndex.action"
            barePath.endsWith(".action") -> barePath.removeSuffix(".action") + "!innerIndex.action"
            else -> barePath
        }
        return if (!projectId.isNullOrEmpty()) "$inner?projectId=$projectId" else inner
    }
    fun shell(html: String, project: String = ""): Shell {
        if (isLogin(html)) throw SessionExpired()
        val ids = Regex("""["']ids["']\s*,\s*["'](\d+)["']""").findAll(html).map { it.groupValues[1] }.toList()
        val semester = Regex("""semesterCalendar\s*\(\s*\{[^}]*?value\s*:\s*["'](\d+)["']""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: throw ParseFailure()
        val tag = Jsoup.parse(html).select("[id]").firstOrNull { it.id().matches(Regex("semesterBar\\d+Semester")) }?.id() ?: "semesterCalendar_target"
        return Shell(ids.firstOrNull() ?: throw ParseFailure(), ids.getOrNull(1), semester, tag, project)
    }
    fun semesters(text: String): List<Semester> {
        val records = Regex("""\{[^{}]*\}""").findAll(text).mapNotNull { match ->
            fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
                Regex("""["']?${Regex.escape(key)}["']?\s*:\s*(?:["']([^"']*)["']|(\d+))""").find(match.value)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            }
            val id = field("id") ?: return@mapNotNull null
            val year = field("schoolYear", "school_year", "academicYear", "year").orEmpty()
            val name = field("name", "termName", "term", "title").orEmpty()
            if (year.isEmpty() && name.isEmpty()) return@mapNotNull null
            val term = if (name.contains("学期")) name else "${name.ifEmpty { "1" }}学期"
            Semester(id, if (year.isEmpty()) term else "${year}学年$term", field("startDate", "start_date", "beginDate", "start"))
        }.toList()
        val fallback = Jsoup.parse(text).select("[val], option[value]").mapNotNull {
            val id = it.attr("val").ifEmpty { it.attr("value") }
            if (id.toLongOrNull() != null && it.text().isNotBlank()) Semester(id, it.text()) else null
        }
        return (records.ifEmpty { fallback }).distinctBy { it.id }.sortedByDescending { it.id.toLongOrNull() ?: 0 }
    }
    fun officialWeek(html: String): Int? {
        val patterns = listOf(
            """<span>\s*(\d{1,2})\s*</span>\s*<i>\s*/\s*\d+\s*</i>[\s\S]{0,80}?当前教学周""",
            """当前教学周[\s\S]{0,80}?<span>\s*(\d{1,2})\s*</span>""",
            """第\s*(\d{1,2})\s*周""", """currentWeek\s*[:=]\s*["']?(\d{1,2})"""
        )
        return patterns.firstNotNullOfOrNull { Regex(it).find(html)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { n -> n in 1..30 } }
    }
}
