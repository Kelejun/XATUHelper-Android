package com.xatune.app

import com.xatune.app.data.*
import okhttp3.CookieJar
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class NetworkTest {
    @Test fun directLoginSubmitsEncryptedPasswordAndHiddenFields() {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/eams/").toString(); val login = server.url("/login").toString()
            server.enqueue(MockResponse().setBody("""<form><input id='pwdEncryptSalt' value='0123456789abcdef'><input id='execution' value='e1s1'><input type='hidden' name='lt' value='ticket-token'></form>"""))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", base + "homeExt.action"))
            server.enqueue(MockResponse().setBody("<html>教务首页</html>"))
            SchoolNetwork(CookieJar.NO_COOKIES, base, login).login("demo", "secret")
            assertEquals("GET", server.takeRequest().method)
            val request = server.takeRequest(); val body = request.body.readUtf8()
            assertTrue(body.contains("execution=e1s1")); assertTrue(body.contains("lt=ticket-token"))
            assertTrue(body.contains("username=demo")); assertFalse(body.contains("secret"))
        }
    }
    @Test fun passwordLoginUsesItsOwnFormWhenIdsAreDuplicated() {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/eams/").toString(); val login = server.url("/login").toString()
            server.enqueue(MockResponse().setBody("""<form id='qr'><input id='execution' value='qr-token'></form><form id='sms'><input id='pwdEncryptSalt' value='wrong-salt'><input id='execution' value='sms-token'></form><form id='pwdFromId'><input name='password'><input id='pwdEncryptSalt' value='0123456789abcdef'><input id='execution' value='password-token'></form>"""))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", base + "homeExt.action"))
            server.enqueue(MockResponse().setBody("教务首页"))
            SchoolNetwork(CookieJar.NO_COOKIES, base, login).login("demo", "secret")
            server.takeRequest()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("execution=password-token")); assertFalse(body.contains("sms-token")); assertFalse(body.contains("qr-token"))
        }
    }
    @Test fun captchaOpensBrowserInsteadOfSubmittingPassword() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("<input id='pwdEncryptSalt'><script>captchaSwitch=1;</script>"))
            val network = SchoolNetwork(CookieJar.NO_COOKIES, server.url("/eams/").toString(), server.url("/login").toString())
            assertThrows(WebLoginRequired::class.java) { network.login("demo", "secret") }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun expiredEamsResponseIsRecognized() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("<input name='password'>"))
            val network = SchoolNetwork(CookieJar.NO_COOKIES, server.url("/eams/").toString(), server.url("/login").toString())
            assertThrows(SessionExpired::class.java) { network.shell() }
        }
    }
    @Test fun httpErrorsDoNotBecomeEmptyTimetables() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(503))
            val network = SchoolNetwork(CookieJar.NO_COOKIES, server.url("/eams/").toString(), server.url("/login").toString())
            assertThrows(java.io.IOException::class.java) { network.shell() }
        }
    }

    private val normalShellHtml = """
        <input id="semesterBar15145952571Semester" />
        <script>
        bg.form.addInput(form,"ids","10001");
        bg.form.addInput(form,"ids","20002");
        semesterCalendar({value:"102"});
        </script>
    """.trimIndent()

    private val majorSelectedWrapper = """
        <div class="navmenu">
        <ul>
            <li><a class="selected" href="javascript:void(0);">本科</a></li>
            <li><a onclick="return bg.Go(this,null)" href="/eams/courseTableForStd!index.action?projectId=82">微专业</a></li>
        </ul>
        </div>
        <div id="div1" class="ajax_container"></div>
        <script>bg.Go('/eams/courseTableForStd!innerIndex.action?projectId=1','div1');</script>
    """.trimIndent()

    private val minorSelectedWrapper = """
        <div class="navmenu">
        <ul>
            <li><a onclick="return bg.Go(this,null)" href="/eams/courseTableForStd!index.action?projectId=1">本科</a></li>
            <li><a class="selected" href="javascript:void(0);">微专业</a></li>
        </ul>
        </div>
        <div id="div1" class="ajax_container"></div>
        <script>bg.Go('/eams/courseTableForStd!innerIndex.action?projectId=82','div1');</script>
    """.trimIndent()

    @Test fun parseMultiProjectWrapperDistinguishesSingleAndMultiProjectPages() {
        assertNull(EamsParser.parseMultiProjectWrapper(normalShellHtml, "/eams/courseTableForStd.action"))
        assertNull(EamsParser.parseMultiProjectWrapper("""<div class="navmenu"><a href="/eams/home.action">首页</a></div>""", "/eams/courseTableForStd.action"))

        val major = EamsParser.parseMultiProjectWrapper(majorSelectedWrapper, "/eams/courseTableForStd.action")
        assertNotNull(major)
        assertNull(major!!.switchHref)
        assertEquals("/eams/courseTableForStd!innerIndex.action?projectId=1", major.innerPath)
        assertEquals("1", major.projectId)

        val minor = EamsParser.parseMultiProjectWrapper(minorSelectedWrapper, "/eams/courseTableForStd.action")
        assertNotNull(minor)
        assertEquals("/eams/courseTableForStd!index.action?projectId=1", minor!!.switchHref)
        assertEquals("1", minor.projectId)

        val fallbackWrapper = """
            <div class="navmenu"><ul>
                <li><a href="/eams/myPlanCompl!index.action?projectId=3">主修</a></li>
                <li><a class="selected" href="javascript:void(0);">微专业</a></li>
            </ul></div>
        """.trimIndent()
        val parsedFallback = EamsParser.parseMultiProjectWrapper(fallbackWrapper, "/eams/myPlanCompl.action")
        assertNotNull(parsedFallback)
        assertEquals("/eams/myPlanCompl!index.action?projectId=3", parsedFallback!!.switchHref)
        assertEquals("/eams/myPlanCompl!innerIndex.action?projectId=3", parsedFallback.innerPath)
        assertEquals("3", parsedFallback.projectId)
    }

    @Test fun singleProjectShellPassesThroughDirectly() {
        MockWebServer().use { server ->
            val paths = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths.add(request.path.orEmpty())
                    return when {
                        request.path == "/eams/courseTableForStd.action" -> MockResponse().setBody(normalShellHtml)
                        request.path == "/eams/dataQuery.action" -> MockResponse().setBody("1")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val network = SchoolNetwork(CookieJar.NO_COOKIES, server.url("/eams/").toString(), server.url("/login").toString())
            val shell = network.shell()
            assertEquals("10001", shell.studentId)
            assertEquals("20002", shell.classId)
            assertEquals("102", shell.semesterId)
            assertEquals("1", shell.projectId)
            assertEquals(listOf("/eams/courseTableForStd.action", "/eams/dataQuery.action"), paths)
        }
    }

    @Test fun multiProjectShellHandlesMajorSelectedMinorSwitchAndProjectFallback() {
        MockWebServer().use { server ->
            var sessionProject = "1"
            var dataQueryBody = "1"
            val paths = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    paths.add(path)
                    return when {
                        path == "/eams/courseTableForStd.action" ->
                            MockResponse().setBody(if (sessionProject == "1") majorSelectedWrapper else minorSelectedWrapper)
                        path.startsWith("/eams/courseTableForStd!index.action") -> {
                            if (path.contains("projectId=1")) sessionProject = "1"
                            MockResponse().setBody(majorSelectedWrapper)
                        }
                        path.startsWith("/eams/courseTableForStd!innerIndex.action") ->
                            MockResponse().setBody(if (sessionProject == "1") normalShellHtml else "<p>empty minor</p>")
                        path == "/eams/dataQuery.action" ->
                            MockResponse().setBody(dataQueryBody)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val network = SchoolNetwork(CookieJar.NO_COOKIES, server.url("/eams/").toString(), server.url("/login").toString())

            // 1. 本科已选中
            val majorShell = network.shell()
            assertEquals("10001", majorShell.studentId)
            assertEquals("20002", majorShell.classId)
            assertEquals("102", majorShell.semesterId)
            assertEquals("1", majorShell.projectId)
            assertTrue(paths.contains("/eams/courseTableForStd!innerIndex.action?projectId=1"))
            assertFalse(paths.any { it.contains("!index.action") })

            // 2. 会话停留在微专业时，先切 !index.action?projectId=1 再进内页；dataQuery 为空时用项目 ID 兜底
            sessionProject = "82"
            dataQueryBody = ""
            paths.clear()
            val switchedShell = network.shell()
            assertEquals("10001", switchedShell.studentId)
            assertEquals("1", switchedShell.projectId)
            val switchIdx = paths.indexOf("/eams/courseTableForStd!index.action?projectId=1")
            val innerIdx = paths.indexOf("/eams/courseTableForStd!innerIndex.action?projectId=1")
            assertTrue("expected !index.action switch call", switchIdx >= 0)
            assertTrue("expected !innerIndex.action call", innerIdx > switchIdx)
        }
    }

    @Test fun multiProjectAcademicEndpointsPreserveQueryAndSwitchSession() {
        MockWebServer().use { server ->
            val paths = mutableListOf<String>()
            var examProject = "82"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val barePath = path.substringBefore('?')
                    paths.add(path)
                    return when (barePath) {
                        "/eams/stdExamTable.action" -> MockResponse().setBody(
                            if (examProject == "1") {
                                """
                                <div class="navmenu"><ul>
                                <li><a class="selected" href="javascript:void(0);">本科</a></li>
                                <li><a href="/eams/stdExamTable!index.action?projectId=82">微专业</a></li>
                                </ul></div><script>bg.Go('/eams/stdExamTable!innerIndex.action?projectId=1','d');</script>
                                """.trimIndent()
                            } else {
                                """
                                <div class="navmenu"><ul>
                                <li><a href="/eams/stdExamTable!index.action?projectId=1">本科</a></li>
                                <li><a class="selected" href="javascript:void(0);">微专业</a></li>
                                </ul></div><script>bg.Go('/eams/stdExamTable!innerIndex.action?projectId=82','d');</script>
                                """.trimIndent()
                            }
                        )
                        "/eams/stdExamTable!index.action" -> {
                            if (path.contains("projectId=1")) examProject = "1"
                            MockResponse().setBody(
                                """
                                <div class="navmenu"><ul>
                                <li><a class="selected" href="javascript:void(0);">本科</a></li>
                                <li><a href="/eams/stdExamTable!index.action?projectId=82">微专业</a></li>
                                </ul></div><script>bg.Go('/eams/stdExamTable!innerIndex.action?projectId=1','d');</script>
                                """.trimIndent()
                            )
                        }
                        "/eams/stdExamTable!innerIndex.action" ->
                            MockResponse().setBody("""<select name="examBatch.id"><option value="0">全部</option><option value="9">期末</option></select>""")
                        "/eams/stdExamTable!examTable.action" ->
                            MockResponse().setBody("<table><tr><th>课程名称</th><th>考试时间</th><th>考场</th></tr><tr><td>编译原理</td><td>2027-01-12 09:00-11:00</td><td>教1-101</td></tr></table>")
                        "/eams/teach/grade/course/person.action" -> MockResponse().setBody(
                            """
                            <div class="navmenu"><ul>
                            <li><a class="selected" href="javascript:void(0);">本科</a></li>
                            <li><a href="/eams/teach/grade/course/person!index.action?projectId=82">微专业</a></li>
                            </ul></div><script>bg.Go('/eams/teach/grade/course/person!innerIndex.action?projectId=1','d');</script>
                            """.trimIndent()
                        )
                        "/eams/teach/grade/course/person!innerIndex.action" ->
                            MockResponse().setBody("""semesterCalendar({value:"102"})""")
                        "/eams/teach/grade/course/person!search.action" ->
                            MockResponse().setBody("""<table><tr><th>学年学期</th><th>课程代码</th><th>课程名称</th><th>学分</th><th>总评成绩</th><th>绩点</th></tr><tr><td>2026-1</td><td>MATH01</td><td>高等数学</td><td>4</td><td>92</td><td>4.2</td></tr></table>""")
                        "/eams/myPlanCompl.action" -> MockResponse().setBody(
                            """
                            <div class="navmenu"><ul>
                            <li><a class="selected" href="javascript:void(0);">本科</a></li>
                            <li><a href="/eams/myPlanCompl!index.action?projectId=82">微专业</a></li>
                            </ul></div><div class="ajax_container"></div>
                            """.trimIndent()
                        )
                        "/eams/myPlanCompl!innerIndex.action" -> MockResponse().setBody(
                            """
                            <div>姓名: 测试 专业/专业方向: 软件工程 要求学分/实修学分: 179 / 20</div>
                            <table><tr class="darkColumn"><td colspan="3">一 通识教育</td><td>40</td><td>20</td></tr></table>
                            """.trimIndent()
                        )
                        "/eams/myPlan.action" -> MockResponse().setBody(
                            """
                            <div class="navmenu"><ul>
                            <li><a class="selected" href="javascript:void(0);">本科</a></li>
                            <li><a href="/eams/myPlan!index.action?projectId=82">微专业</a></li>
                            </ul></div><script>bg.Go('/eams/myPlan!innerIndex.action?projectId=1','d');</script>
                            """.trimIndent()
                        )
                        "/eams/myPlan!innerIndex.action" -> MockResponse().setBody(
                            """
                            <table class="planInfoTable"><tr>
                            <td>C001</td><td class="course">1 编译原理</td><td>3.0</td><td>48</td>
                            <td></td><td></td><td></td><td></td><td></td><td>5</td><td></td><td>是</td>
                            </tr></table>
                            """.trimIndent()
                        )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val network = SchoolNetwork(CookieJar.NO_COOKIES, server.url("/eams/").toString(), server.url("/login").toString())

            val exams = network.exams("102")
            assertEquals(1, exams.size)
            assertEquals("编译原理", exams.single().course)
            assertTrue(paths.contains("/eams/stdExamTable!index.action?projectId=1"))
            assertTrue(paths.contains("/eams/stdExamTable!innerIndex.action?projectId=1&semester.id=102"))

            val grades = network.grades("102", false)
            assertEquals(1, grades.grades.size)
            assertEquals("高等数学", grades.grades.single().course)
            assertTrue(paths.contains("/eams/teach/grade/course/person!innerIndex.action?projectId=1&semester.id=102"))

            val plan = network.plan()
            assertEquals(1, plan.sections.size)
            assertEquals(179.0, plan.required, 0.001)
            assertTrue(paths.contains("/eams/myPlanCompl!innerIndex.action"))

            val catalog = network.catalog()
            assertEquals(1, catalog.size)
            assertEquals("编译原理", catalog.single().name)
            assertTrue(paths.contains("/eams/myPlan!innerIndex.action?projectId=1"))
        }
    }
}
