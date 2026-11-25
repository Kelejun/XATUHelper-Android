package com.xatune.app.data

import com.xatune.app.model.*
import org.jsoup.Jsoup

class ParseFailure(message: String = "教务页面结构发生变化，未覆盖本地课表。请稍后重试。") : Exception(message)
class SessionExpired : Exception("登录已过期，请重新登录")
class WebLoginRequired : Exception("本次登录需要验证码或动态码，请使用网页登录")

object TimetableParser {
    fun parse(html: String): ParsedTable {
        val units = Regex("""var\s+unitCount\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 24) ?: 12
        val chunks = html.split("new TaskActivity")
        // A legitimate empty response still carries the EAMS table scaffold.
        if (chunks.size == 1 && !html.contains("CourseTable(") && !html.contains("CourseTable (")) throw ParseFailure()
        val merged = linkedMapOf<String, Course>()
        var raw = 0
        chunks.drop(1).forEachIndexed { i, chunk ->
            val args = arguments(chunk) ?: throw ParseFailure()
            if (args.size < 7) throw ParseFailure()
            val rawName = string(args[3])
            val codeSuffix = Regex("""\(([^()]*)\)\s*$""").find(rawName)
            val code = codeSuffix?.groupValues?.get(1) ?: string(args[2])
            val name = codeSuffix?.let { rawName.substring(0, it.range.first) } ?: rawName
            val room = string(args[5])
            val mask = string(args[6])
            if (mask.any { it != '0' && it != '1' }) throw ParseFailure()
            val weeks = mask.mapIndexedNotNull { index, c -> index.takeIf { c == '1' && index > 0 } }
            val previous = chunks[i].substringAfterLast("var actTeachers", "")
            val teacher = Regex("""name\s*:\s*["']([^"']*)["']""").findAll(previous).map { it.groupValues[1] }.distinct().joinToString("、")
            val positions = Regex("""index\s*=\s*(\d+)\s*\*\s*unitCount\s*\+\s*(\d+)""").findAll(chunk).toList()
            if (positions.isEmpty()) throw ParseFailure()
            raw++
            if (weeks.isEmpty() || listOf("线上", "平台修读", "虚拟教室", "实践环节", "供选课参考").any { it in room } || "实践环节" in name) return@forEachIndexed
            positions.forEach { match ->
                val day = match.groupValues[1].toInt() + 1
                val period = match.groupValues[2].toInt() + 1
                if (day !in 1..7 || period !in 1..units) throw ParseFailure()
                val key = "$code|$day|$period|$room|$teacher"
                val old = merged[key]
                merged[key] = Course(code, name, teacher, room, day, period, (old?.weeks.orEmpty() + weeks).distinct().sorted())
            }
        }
        return ParsedTable(merged.values.sortedWith(compareBy({ it.day }, { it.period })), units, raw)
    }

    internal fun arguments(chunk: String): List<String>? {
        val start = chunk.indexOf('(')
        if (start < 0) return null
        val result = mutableListOf<String>()
        var begin = start + 1
        var quote: Char? = null
        var escaped = false
        var depth = 1
        for (index in start + 1 until chunk.length) {
            val c = chunk[index]
            if (quote != null) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = null
            } else when {
                c == '"' || c == '\'' -> quote = c
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' && depth == 1 -> { result.add(chunk.substring(begin, index).trim()); return result }
                c == ')' || c == ']' || c == '}' -> depth--
                c == ',' && depth == 1 -> { result.add(chunk.substring(begin, index).trim()); begin = index + 1 }
            }
        }
        return null
    }
    private fun string(raw: String): String = Jsoup.parse(raw.trim().removeSurrounding("\"").removeSurrounding("'")
        .replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\")).text()
}
