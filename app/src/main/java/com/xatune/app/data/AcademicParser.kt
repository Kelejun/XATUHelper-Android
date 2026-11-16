package com.xatune.app.data

import com.xatune.app.model.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parse only the recognized business table, never surrounding navigation or summary tables. */
object AcademicParser {
    private fun cells(row: Element) = row.children().filter { it.tagName() == "td" || it.tagName() == "th" }
    private fun value(text: String): String = text.replace('\u00a0', ' ').trim().takeUnless { it in listOf("-", "—", "--") }.orEmpty()
    private fun businessTable(html: String, header: (List<String>) -> Boolean): Pair<List<String>, List<List<String>>>? {
        val doc = Jsoup.parse(html)
        if (EamsParser.isLogin(html)) throw SessionExpired()
        for (table in doc.select("table")) {
            val rows = table.select("tr").filter { it.closest("table") == table }
            val index = rows.indexOfFirst { header(cells(it).map(Element::text)) }
            if (index >= 0) return cells(rows[index]).map(Element::text) to rows.drop(index + 1).map { cells(it).map { cell -> value(cell.text()) } }
        }
        return null
    }
    private fun emptyKnown(html: String) = Jsoup.parse(html).text().let { text ->
        listOf("没有考试安排", "暂无考试", "没有查询到", "未查询到", "没有符合条件", "暂无成绩", "无符合条件", "暂无数据", "没有数据", "无数据").any { it in text }
    }
    fun exams(html: String): List<Exam> {
        val (header, rows) = businessTable(html) { h -> h.any { "课程名称" in it || "科目" in it } && h.any { "时间" in it || "日期" in it || "考场" in it } }
            ?: return if (emptyKnown(html)) emptyList() else throw ParseFailure("没有识别到考试表，已保留原有缓存。")
        fun col(vararg words: String) = header.indexOfFirst { h -> words.any { it in h } }
        val courseCol = col("课程名称", "科目")
        val timeCol = col("考试时间", "时间", "日期")
        val dateCol = col("日期")
        return rows.mapNotNull { row ->
            fun at(index: Int) = row.getOrNull(index).orEmpty()
            val course = at(courseCol)
            if (course.isBlank() || row.size < 2 || course.contains("课程名称")) return@mapNotNull null
            var time = at(timeCol)
            val date = at(dateCol)
            if (date.isNotBlank() && date != time && !time.contains(date)) time = "$date $time".trim()
            if (!Regex("""\d{1,2}:\d{2}""").containsMatchIn(time)) {
                row.firstOrNull { Regex("""\d{1,2}:\d{2}""").containsMatchIn(it) }?.let { time = "$time $it".trim() }
            }
            time = normalizedExamTime(time)
            val room = at(col("考场", "教室", "地点"))
            if (time.isBlank() && room.isBlank()) return@mapNotNull null
            val code = at(col("课程序号", "课程代码", "课程号"))
            val name = at(col("考试名称"))
            Exam("$code|$course|$name|$time|$room", course, code, name, time, room, at(col("座位")), at(col("备注", "说明")))
        }.distinctBy { it.id }
    }
    fun grades(html: String): GradePage {
        val summary = summary(html)
        val (header, rows) = businessTable(html) { h -> h.any { "课程名称" in it || "科目" in it } && (
            h.any { "成绩" in it || "绩点" in it } ||
            h.any { it.trim() == "学年学期" || it.trim() == "学期" } && h.any { "课程代码" in it || "课程序号" in it } && h.any { it.trim() == "学分" }
        ) }
            ?: return if (emptyKnown(html)) GradePage(emptyList(), summary) else throw ParseFailure("没有识别到成绩表，已保留原有缓存。")
        fun col(vararg words: String) = header.indexOfFirst { h -> words.any { it in h } }
        val scoreCol = col("总评", "最终").takeIf { it >= 0 } ?: header.indexOfFirst { it == "成绩" || it == "总成绩" }
        val creditCol = header.indexOfFirst { "学分" in it && "绩点" !in it }
        val repeated = mutableMapOf<String, Int>()
        val result = rows.mapNotNull { row ->
            fun at(index: Int) = row.getOrNull(index).orEmpty()
            val course = at(col("课程名称", "科目"))
            if (course.isBlank() || row.size < 2 || "课程名称" in course) return@mapNotNull null
            val code = at(col("课程序号", "课程代码", "课程号"))
            val term = at(col("学年学期", "学期", "学年"))
            val parts = header.mapIndexedNotNull { i, label -> if (i != scoreCol && ("成绩" in label || label == "其他") && at(i).isNotBlank()) GradePart(label, at(i)) else null }
            val identity = "$term|$code|$course"
            val occurrence = repeated.getOrDefault(identity, 0)
            repeated[identity] = occurrence + 1
            Grade(if (occurrence == 0) identity else "$identity|$occurrence", course, code, at(creditCol), at(scoreCol), at(col("绩点")), term, parts)
        }.distinctBy { it.id }
        return GradePage(result, summary)
    }
    fun summary(html: String): GradeSummary {
        val doc = Jsoup.parse(html)
        val plain = doc.text()
        fun number(vararg labels: String): Double? {
            for (label in labels) {
                Regex("${Regex.escape(label)}[:：\\s]*([0-9]+(?:\\.[0-9]+)?)").find(plain)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return it }
                for (row in doc.select("tr")) {
                    val values = cells(row).map(Element::text)
                    values.forEachIndexed { i, cell ->
                        if (cell.trim().trimEnd(':', '：') == label) values.getOrNull(i + 1)?.trim()?.toDoubleOrNull()?.let { return it }
                    }
                }
            }
            return null
        }
        return GradeSummary(number("课程门数", "课程数", "门数")?.takeIf { it in 0.0..500.0 }?.toInt(), number("总学分", "合计学分", "学分合计", "已修学分")?.takeIf { it in 0.0..500.0 }, number("平均学分绩点", "平均绩点", "平均GPA")?.takeIf { it in 0.0..6.0 })
    }
    private data class SectionBuilder(val id: String, val title: String, val required: Double, val earned: Double, val courses: MutableList<PlanCourse> = mutableListOf(), val children: MutableList<SectionBuilder> = mutableListOf()) {
        fun freeze(): PlanSection = PlanSection(id, title, required, earned, courses.toList(), children.map { it.freeze() })
    }
    fun plan(html: String): StudyPlan {
        if (EamsParser.isLogin(html)) throw SessionExpired()
        val doc = Jsoup.parse(html)
        val plain = doc.text()
        val roots = mutableListOf<SectionBuilder>()
        var top: SectionBuilder? = null
        var child: SectionBuilder? = null
        var grandchild: SectionBuilder? = null
        var serial = 0
        for (row in doc.select("tr")) {
            val values = cells(row).map { value(it.text()) }
            if (values.isEmpty()) continue
            if (row.hasClass("darkColumn")) {
                val title = values[0].replace(Regex("""(?<=\S)\s*[（(]所有.*$"""), "").trim()
                if (title.isEmpty()) continue
                val numbers = values.drop(1).mapNotNull(String::toDoubleOrNull)
                val section = SectionBuilder("section-${serial++}", title, numbers.getOrElse(0) { 0.0 }, numbers.getOrElse(1) { 0.0 })
                val level2 = Regex("""^[（(][一二三四五六七八九十]+[)）]""").containsMatchIn(title)
                val level3 = Regex("""^\d+[ 　]""").containsMatchIn(title)
                when {
                    level3 && child != null -> { child.children.add(section); grandchild = section }
                    level2 && top != null -> { top.children.add(section); child = section; grandchild = null }
                    else -> { roots.add(section); top = section; child = null; grandchild = null }
                }
            } else if (values.size >= 7 && values[0].toIntOrNull() != null && values[2].isNotBlank()) {
                val parent = grandchild ?: child ?: top ?: continue
                parent.courses.add(PlanCourse("${parent.id}|${values[1]}|${values[0]}", values[1], values[2], values[3].toDoubleOrNull() ?: 0.0, values[4].toDoubleOrNull() ?: 0.0, values[5], values[6].contains("是") || values[6] == "通过"))
            }
        }
        if (roots.isEmpty()) throw ParseFailure("没有识别到学业完成情况，已保留原有缓存。")
        val totals = Regex("""要求学分/实修学分\s*[:：]\s*([\d.]+)\s*/\s*([\d.]+)""").find(plain)
        fun field(pattern: String) = Regex(pattern).find(plain)?.groupValues?.get(1).orEmpty()
        return StudyPlan(field("""姓名\s*[:：]\s*(\S+)"""), field("""专业/专业方向\s*[:：]\s*(\S+)"""), field("""GPA\s*[:：]\s*([\d.]+)"""), totals?.groupValues?.get(1)?.toDoubleOrNull() ?: roots.sumOf { it.required }, totals?.groupValues?.get(2)?.toDoubleOrNull() ?: roots.sumOf { it.earned }, roots.map { it.freeze() })
    }
    fun catalog(html: String): List<CourseInfo> {
        if (EamsParser.isLogin(html)) throw SessionExpired()
        val result = Jsoup.parse(html).select("tr").mapNotNull { row ->
            val columns = cells(row)
            val i = columns.indexOfFirst { it.hasClass("course") }
            if (i < 0) return@mapNotNull null
            fun at(offset: Int) = columns.getOrNull(i + offset)?.text().orEmpty()
            if (at(1).toDoubleOrNull() == null) return@mapNotNull null
            val name = at(0).replace(Regex("""^\d+\s+"""), "")
            val nature = when { "是" in at(10) -> "必修"; "否" in at(10) -> "选修"; else -> "" }
            CourseInfo(at(-1), name, nature, at(1), at(2), at(8))
        }.distinctBy { it.code.ifBlank { it.name } }
        if (result.isEmpty()) throw ParseFailure("教学计划暂未读取成功，仍可查看学业进度。")
        return result
    }
}
