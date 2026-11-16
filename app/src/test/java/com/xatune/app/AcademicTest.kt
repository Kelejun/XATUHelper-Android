package com.xatune.app

import com.xatune.app.data.*
import com.xatune.app.model.*
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AcademicTest {
    private val grades = """<table><tr><th>学年学期</th><th>课程代码</th><th>课程名称</th><th>学分</th><th>平时成绩</th><th>总评成绩</th><th>绩点</th></tr><tr><td>2026-1</td><td>CS01</td><td>程序设计</td><td>4</td><td>90</td><td>92</td><td>4.2</td></tr></table><div>课程门数:1 总学分:4 平均学分绩点:4.2</div>"""
    private val exam = """<table><tr><th>课程名称</th><th>日期</th><th>时间</th><th>考场</th><th>座位号</th></tr><tr><td>程序设计</td><td>2027/1/12</td><td>09:00-11:00</td><td>教1-101</td><td>12</td></tr></table>"""
    private val plan = """<div>姓名:示例 专业/专业方向:计算机 GPA:3.8 要求学分/实修学分:160/96</div><table><tr class='darkColumn'><td colspan='3'>一 基础课程</td><td>13</td><td>9</td></tr><tr class='darkColumn'><td colspan='3'>(一) 学科基础</td><td>13</td><td>9</td></tr><tr class='darkColumn'><td colspan='3'>1 数学组</td><td>5</td><td>5</td></tr><tr><td>1</td><td>MATH01</td><td>高等数学</td><td>5</td><td>5</td><td>88</td><td>是</td><td></td></tr></table>"""
    @Test fun datesAndClockInSeparateColumnsAreCombined() {
        val item = AcademicParser.exams(exam).single()
        assertTrue(item.time.contains("2027-01-12")); assertTrue(item.time.contains("09:00"))
        assertEquals(LocalDate.parse("2027-01-12"), item.date())
        assertEquals("12", item.seat)
    }
    @Test fun gradePartsSummaryAndStableChangeIdentity() {
        val page = AcademicParser.grades(grades)
        assertEquals("4", page.grades.single().credit)
        assertEquals("92", page.grades.single().score)
        assertEquals(listOf(GradePart("平时成绩", "90")), page.grades.single().parts)
        assertEquals(1, page.summary.count); assertEquals(4.2, page.summary.gpa!!, .0001)
        val changed = AcademicParser.grades(grades.replace("<td>92</td>", "<td>95</td>"))
        assertEquals(page.grades.single().id, changed.grades.single().id)
        assertEquals(1, gradeChanges(page.grades, changed.grades))
    }
    @Test fun unpublishedSemesterTableIsAValidEmptyGradeResponse() {
        val header = "<tr><th>学年学期</th><th>课程代码</th><th>课程序号</th><th>课程名称</th><th>课程类别</th><th>学分</th></tr>"
        assertTrue(AcademicParser.grades("<table>$header</table>").grades.isEmpty())
        val row = "<tr><td>2026-1</td><td>C01</td><td>01</td><td>课程</td><td>必修</td><td>3</td></tr>"
        assertEquals("", AcademicParser.grades("<table>$header$row</table>").grades.single().score)
    }
    @Test fun repeatedAttemptsAreNotDiscarded() {
        val repeated = grades.replace("</table>", "<tr><td>2026-1</td><td>CS01</td><td>程序设计</td><td>4</td><td>80</td><td>85</td><td>3.5</td></tr></table>")
        val parsed = AcademicParser.grades(repeated).grades
        assertEquals(2, parsed.size); assertNotEquals(parsed[0].id, parsed[1].id)
    }
    @Test fun gpaHeaderIsNotMistakenForCredit() {
        val html = """<table><tr><th>课程名称</th><th>学分绩点</th><th>学分</th><th>成绩</th></tr><tr><td>课程</td><td>4.1</td><td>3</td><td>90</td></tr></table>"""
        assertEquals("3", AcademicParser.grades(html).grades.single().credit)
    }
    @Test fun unknownPagesNeverOverwriteWithEmptyRecords() {
        assertThrows(ParseFailure::class.java) { AcademicParser.grades("系统维护中") }
        assertThrows(ParseFailure::class.java) { AcademicParser.exams("系统维护中") }
        assertThrows(ParseFailure::class.java) { AcademicParser.plan("系统维护中") }
        assertTrue(AcademicParser.grades("暂无成绩").grades.isEmpty())
        assertTrue(AcademicParser.exams("没有考试安排").isEmpty())
        assertThrows(SessionExpired::class.java) { AcademicParser.plan("<input name='password'>") }
    }
    @Test fun threeLevelPlanHierarchyAndTotalsArePreserved() {
        val parsed = AcademicParser.plan(plan)
        assertEquals(160.0, parsed.required, .001); assertEquals(96.0, parsed.earned, .001)
        assertEquals("高等数学", parsed.sections.single().children.single().children.single().courses.single().name)
        assertEquals(1, parsed.sections.single().allCourses().size)
    }
    @Test fun planCatalogUsesCourseCellRelativeOffsetsDespiteMergedGroups() {
        val cells = "<td rowspan='3'>分组</td><td>MATH01</td><td class='course'>1 高等数学</td>" + listOf("5", "80", "", "", "", "", "", "3", "", "是").joinToString("") { "<td>$it</td>" }
        val item = AcademicParser.catalog("<table><tr>$cells</tr></table>").single()
        assertEquals("MATH01", item.code); assertEquals("高等数学", item.name); assertEquals("3", item.term); assertEquals("必修", item.nature)
    }
    @Test fun missingExamYearsUseAcademicYearAndInvalidDatesAreRejected() {
        fun item(text: String) = Exam("", "", "", "", text, "", "", "")
        assertEquals(LocalDate.parse("2027-01-12"), item("1月12日").date(LocalDate.parse("2026-09-01")))
        assertNull(item("2026-02-30").date())
    }
    @Test fun gradeSemesterUsesResolvedShellIdAndAllScopeUsesHistoryEndpoint() {
        MockWebServer().use { server ->
            server.start()
            val net = SchoolNetwork(CookieJar.NO_COOKIES, server.url("/eams/").toString(), server.url("/login").toString())
            server.enqueue(MockResponse().setBody("""semesterCalendar({value:"999"})""")); server.enqueue(MockResponse().setBody(grades))
            assertEquals(1, net.grades("101", false).grades.size)
            assertTrue(server.takeRequest().path!!.contains("semester.id=101"))
            assertTrue(server.takeRequest().path!!.contains("semesterId=999"))
            server.enqueue(MockResponse().setBody(grades)); net.grades("all", true)
            assertTrue(server.takeRequest().path!!.contains("historyCourseGrade.action?projectType=MAJOR"))
        }
    }
    @Test fun examsFallBackToPublishedBatchesWhenAllIsEmpty() {
        MockWebServer().use { server ->
            server.start()
            val net = SchoolNetwork(CookieJar.NO_COOKIES, server.url("/eams/").toString(), server.url("/login").toString())
            server.enqueue(MockResponse().setBody("<select name='examBatch.id'><option value='2'>期末</option></select>"))
            server.enqueue(MockResponse().setBody("暂无考试")); server.enqueue(MockResponse().setBody(exam))
            assertEquals(1, net.exams("101").size)
            server.takeRequest(); server.takeRequest(); assertTrue(server.takeRequest().path!!.contains("examBatch.id=2"))
        }
    }
}
