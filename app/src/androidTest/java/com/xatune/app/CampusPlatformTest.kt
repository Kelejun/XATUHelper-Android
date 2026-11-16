package com.xatune.app

import android.app.*
import android.appwidget.*
import android.content.*
import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xatune.app.data.*
import com.xatune.app.model.*
import com.xatune.app.platform.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CampusPlatformTest {
    @Test fun campusNetworkCleartextExceptionIsLimitedToExactHosts() {
        val policy = android.security.NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted("self.xatu.edu.cn"))
        assertTrue(policy.isCleartextTrafficPermitted("jwgl2018.xatu.edu.cn"))
        assertFalse(policy.isCleartextTrafficPermitted("authserver.xatu.edu.cn"))
        assertFalse(policy.isCleartextTrafficPermitted("evil.self.xatu.edu.cn"))
        assertFalse(policy.isCleartextTrafficPermitted("self.xatu.edu.cn.evil.example"))
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as HelperApplication
    private fun onlyQa() { check(context.packageName.endsWith(".qa")) { "Platform fixtures must never modify the personal account package" } }
    @Test fun electricityHistorySeparatesDormsAndUpsertsDaily() = runBlocking {
        onlyQa()
        val store = app.store
        val a = Dorm(CampusOption("p","P"),CampusOption("a","A"),CampusOption("b","9"),CampusOption("328","328"))
        val b = a.copy(room = CampusOption("329","329"))
        store.record("fixture", Electricity(a,"50.00",0)); store.record("fixture", Electricity(a,"48.00",1)); store.record("fixture", Electricity(b,"99.00",1))
        assertEquals("48.00", store.history("fixture",a.key).single().balance)
        assertEquals("99.00", store.history("fixture",b.key).single().balance)
        assertTrue(store.history("other",a.key).isEmpty())
        store.clear()
    }
    @Test fun bundledChineseOcrRecognizesCalendarWithoutModelDownload() {
        onlyQa()
        val bitmap = Bitmap.createBitmap(1800, 450, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 70f }
            drawText("第一学期", 40f, 100f, paint)
            drawText("2026年9月7日至2027年1月20日", 40f, 220f, paint)
            drawText("第二学期 2027年2月20日至7月10日", 40f, 340f, paint)
        }
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 40, TimeUnit.SECONDS).text
            val spans = CalendarParser.semesters(text)
            assertTrue("Chinese OCR should yield semester ranges", spans.isNotEmpty())
            assertEquals("2026-09-07", spans.first().start)
        } finally { recognizer.close(); bitmap.recycle() }
    }
    @Test fun officialCalendarSummerEndsAtLastGridDay() = runBlocking {
        onlyQa()
        val bitmap = instrumentation.context.assets.open("calendar-2026.jpg").use { BitmapFactory.decodeStream(it) }
        try {
            val parsed = CalendarOcr.read(bitmap)
            java.io.File(context.getExternalFilesDir(null), "calendar-ocr.txt").writeText(parsed.text)
            assertEquals(2, parsed.semesters.size)
            assertEquals("2027-07-16", parsed.semesters.last().end)
            assertEquals("2027-08-29", parsed.coverageEnd)
            val calendar = SchoolCalendar("", "", "", "", parsed.text, parsed.semesters, 0, parsed.coverageEnd)
            assertEquals(SchoolBreak("暑假", LocalDate.parse("2027-07-17"), LocalDate.parse("2027-08-29")), schoolBreaks(calendar).last())
            assertEquals("暑假", nextBreak(calendar, LocalDate.parse("2027-08-15"))?.title)
            assertNull(nextBreak(calendar, LocalDate.parse("2027-08-30")))
            val image = java.io.File(context.filesDir, "calendar-upgrade-fixture.jpg")
            instrumentation.context.assets.open("calendar-2026.jpg").use { input -> image.outputStream().use { input.copyTo(it) } }
            try {
                app.store.extra("schoolCalendar", calendar.copy(imagePath = image.absolutePath, coverageEnd = null))
                val upgraded = app.campusRepository.calendar()!!
                assertEquals("2027-08-29", upgraded.coverageEnd)
                assertEquals(1, upgraded.recognitionVersion)
            } finally { app.store.extra("schoolCalendar", null); image.delete() }
        } finally { bitmap.recycle() }
    }
    @Test fun alarmsDeliverAndLogoutCancelsPendingState() = runBlocking {
        onlyQa()
        val account = "alarm-fixture"
        val store = app.store
        app.repository.logout()
        store.select(account, "current", TableKind.STUDENT); store.extra("current:$account", "current")
        val tomorrow = schoolToday().plusDays(1)
        val table = Timetable(account, Semester("current","current"), TableKind.STUDENT, listOf(Course("c","提醒测试课程","","教1",tomorrow.dayOfWeek.value,1,(1..30).toList())),12,mondayOf(schoolToday()).toString(),System.currentTimeMillis())
        store.save(table); store.extra("reminders:$account", ReminderSettings(classes = true))
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow").close()
        Thread.sleep(700)
        try {
            SystemCoordinator.synchronize(context)
            val ids = context.getSharedPreferences("system_schedule",Context.MODE_PRIVATE).getStringSet("ids",emptySet()).orEmpty()
            assertTrue(ids.isNotEmpty())
            val id = ids.first()
            val sample = Reminder(id,System.currentTimeMillis()+2500,"系统提醒验证","只用于独立 QA 包","timetable")
            val alarm = context.getSystemService(AlarmManager::class.java)
            assertTrue(SystemCoordinator.exactAllowed(context))
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,sample.at,SystemCoordinator.alarmIntent(context,id,sample,account))
            val notifications = context.getSystemService(NotificationManager::class.java)
            val until = System.currentTimeMillis()+15000
            while (System.currentTimeMillis()<until && notifications.activeNotifications.none { it.tag==id }) Thread.sleep(300)
            assertTrue("Actual AlarmManager delivery should post a notification", notifications.activeNotifications.any { it.tag==id })
        } finally {
            app.repository.logout(); SystemCoordinator.synchronize(context)
        }
        assertTrue(context.getSharedPreferences("system_schedule",Context.MODE_PRIVATE).getStringSet("ids",emptySet()).orEmpty().isEmpty())
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }
    @Test fun realWidgetHostsRenderCacheToggleAndClearOnLogout() = runBlocking {
        onlyQa()
        val store = app.store
        val account = "widget-fixture"
        app.repository.logout()
        store.select(account,"current",TableKind.STUDENT); store.extra("current:$account","current"); store.extra("autoElectric:$account",false)
        store.save(Timetable(account,Semester("current","current"),TableKind.STUDENT,listOf(Course("c","组件测试课程","","教1",schoolToday().dayOfWeek.value,12,(1..30).toList())),12,mondayOf(schoolToday()).toString(),System.currentTimeMillis()))
        val dorm = Dorm(CampusOption("p","P"),CampusOption("a","A"),CampusOption("b","9号楼"),CampusOption("328","328"))
        store.extra("dorm:$account",dorm); store.extra("electric:$account",Electricity(dorm,"42.00",System.currentTimeMillis()))
        store.extra("schoolCalendar",SchoolCalendar("组件校历","","","","",listOf(SemesterSpan("学期",schoolToday().minusMonths(1).toString(),schoolToday().plusDays(20).toString())),0))
        instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user 0").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val manager = AppWidgetManager.getInstance(context)
        lateinit var host: AppWidgetHost
        val views = linkedMapOf<Int,AppWidgetHostView>()
        instrumentation.runOnMainSync { host = AppWidgetHost(context, 0x58415455); host.startListening() }
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            for (cls in listOf(CourseWidget::class.java,ElectricityWidget::class.java,HolidayWidget::class.java)) {
                val id = host.allocateAppWidgetId()
                assertTrue("QA host should bind its fixture widgets",manager.bindAppWidgetIdIfAllowed(id,ComponentName(context,cls)))
                instrumentation.runOnMainSync { views[id] = host.createView(context,id,manager.getAppWidgetInfo(id)) }
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            SystemCoordinator.synchronize(context)
            val source = store.table(account, "current", TableKind.STUDENT)!!
            val anchor = schoolToday().atTime(12, 0).atZone(SchoolZone)
            CampusWidgets.publish(context, account, source, anchor, scheduleRefresh = false)
            fun bodies(): List<String> { var result = emptyList<String>(); instrumentation.runOnMainSync { result = views.values.map { it.findViewById<android.widget.TextView>(R.id.widget_body)?.text?.toString().orEmpty() } }; return result }
            val until = System.currentTimeMillis()+8000
            while (System.currentTimeMillis()<until && !(bodies().any { "42.00" in it } && bodies().any { "组件测试课程" in it })) {
                CampusWidgets.publish(context, account, source, anchor, scheduleRefresh = false)
                Thread.sleep(200)
            }
            assertTrue(bodies().any { "42.00" in it })
            assertTrue("Widget bodies: ${bodies()}; snapshot: ${store.extra("widget.courseSnapshot", WidgetCourseSnapshot::class.java)}", bodies().any { "组件测试课程" in it })
            val courseId = views.keys.first()
            instrumentation.runOnMainSync { views.getValue(courseId).findViewById<android.widget.TextView>(R.id.widget_action).performClick() }
            val toggled = System.currentTimeMillis()+5000
            while (System.currentTimeMillis()<toggled && !context.getSharedPreferences("widget_options",Context.MODE_PRIVATE).getBoolean("tomorrow:$courseId",false)) Thread.sleep(100)
            assertTrue(context.getSharedPreferences("widget_options",Context.MODE_PRIVATE).getBoolean("tomorrow:$courseId",false))
            instrumentation.runOnMainSync {
                val density = context.resources.displayMetrics.density
                val width = (340*density).toInt(); val height = (600*density).toInt()
                val root = android.widget.FrameLayout(context)
                views.values.forEachIndexed { index, view -> root.addView(view,android.widget.FrameLayout.LayoutParams(width,(190*density).toInt()).apply { topMargin = (index*200*density).toInt() }) }
                root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY)); root.layout(0,0,width,height)
                val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                java.io.File(context.getExternalFilesDir(null),"widgets.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
            CampusWidgets.publish(context, account, source, anchor.plusDays(1), scheduleRefresh = false)
            val advanced = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < advanced && bodies().first().contains("组件测试课程")) Thread.sleep(100)
            assertFalse(bodies().first().contains("组件测试课程"))
            CampusWidgets.publish(context, account, source, anchor.plusDays(14), scheduleRefresh = false)
            val expired = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < expired && !bodies().first().contains("请先同步")) Thread.sleep(100)
            assertTrue(bodies().first().contains("请先同步"))
            app.repository.logout(); SystemCoordinator.synchronize(context)
            val cleared = System.currentTimeMillis()+5000
            while (System.currentTimeMillis()<cleared && bodies().any { !it.contains("登录后") }) Thread.sleep(100)
            assertTrue(bodies().all { "登录后" in it })
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            views.keys.forEach { host.deleteAppWidgetId(it) }
            instrumentation.runOnMainSync { host.stopListening(); host.deleteHost() }
            app.repository.logout(); SystemCoordinator.synchronize(context)
            instrumentation.uiAutomation.executeShellCommand("appwidget revokebind --package ${context.packageName} --user 0").close()
        }
    }
    @Test fun allThreeWidgetProvidersAreRegistered() {
        onlyQa()
        val providers = AppWidgetManager.getInstance(context).installedProviders.filter { it.provider.packageName==context.packageName }.map { it.provider.className }
        assertTrue(providers.contains(CourseWidget::class.java.name))
        assertTrue(providers.contains(ElectricityWidget::class.java.name))
        assertTrue(providers.contains(HolidayWidget::class.java.name))
    }
}
