package com.xatune.app

import android.app.Notification
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xatune.app.model.CourseProgressCompletion
import com.xatune.app.model.CourseProgressLesson
import com.xatune.app.model.CourseProgressStage
import com.xatune.app.platform.CourseProgressCard
import com.xatune.app.platform.CourseProgressNotifications
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** Run on a connected physical device with the isolated QA package. */
@RunWith(AndroidJUnit4::class)
class CourseProgressCardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val now = Instant.parse("2026-11-23T01:10:00Z")
    private val lesson = CourseProgressLesson("card", "高等数学", "教1-201", now.plusSeconds(300), now.plusSeconds(3000))

    @Test fun allSixStatesInflateInBothThemesAndAtLargerFontSizes() {
        check(BuildConfig.APPLICATION_ID.endsWith(".qa"))
        check(Build.FINGERPRINT.contains("generic").not() && Build.MODEL.contains("Emulator").not())
        val stages = listOf(
            CourseProgressStage(lesson, now.minusSeconds(300), lesson.start),
            CourseProgressStage(lesson, now.minusSeconds(300), lesson.start, "教1-201"),
            CourseProgressStage(lesson, now.minusSeconds(300), lesson.start, "教2-302"),
            CourseProgressStage(lesson.copy(start = now.minusSeconds(1200)), now.minusSeconds(1200), now.plusSeconds(1080)),
            CourseProgressStage(lesson, now, now.plusSeconds(60), completion = CourseProgressCompletion.STARTED),
            CourseProgressStage(lesson, now, now.plusSeconds(60), completion = CourseProgressCompletion.ENDED)
        )
        instrumentation.runOnMainSync {
            for (night in listOf(false, true)) for (scale in listOf(1f, 1.3f)) for ((index, stage) in stages.withIndex()) for (compact in listOf(false, true)) {
                val config = Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    fontScale = scale
                }
                val themed = context.createConfigurationContext(config)
                val parent = FrameLayout(themed)
                val card = CourseProgressCard.views(themed, stage, now, compact).apply(themed, parent)
                val width = (320 * themed.resources.displayMetrics.density).toInt()
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                card.layout(0, 0, width, card.measuredHeight)
                assertEquals(stage.phase, card.findViewById<TextView>(R.id.course_phase).text.toString())
                assertEquals(if (stage.finished) View.GONE else View.VISIBLE, card.findViewById<Chronometer>(R.id.course_timer).visibility)
                assertEquals(if (stage.finished) View.VISIBLE else View.GONE, card.findViewById<View>(R.id.course_completion).visibility)
                if (!compact) {
                    assertEquals(if (stage.routeOrigin == null) View.GONE else View.VISIBLE, card.findViewById<View>(R.id.course_route).visibility)
                    assertEquals(if (stage.routeOrigin == null) View.VISIBLE else View.GONE, card.findViewById<View>(R.id.course_room).visibility)
                }
                val title = card.findViewById<TextView>(R.id.course_name)
                assertTrue(title.width > 0)
                assertTrue(title.layout.getEllipsisCount(0) == 0)
                val bitmap = Bitmap.createBitmap(width, card.height, Bitmap.Config.ARGB_8888)
                card.draw(Canvas(bitmap))
                val file = File(context.getExternalFilesDir(null), "course-activity/${if (night) "dark" else "light"}-$scale-$index-${if (compact) "compact" else "expanded"}.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    @Test fun completionStopsTheSystemTimerAndRemainsDismissible() {
        val stage = CourseProgressStage(lesson, now, now.plusSeconds(60), completion = CourseProgressCompletion.ENDED)
        val notification = CourseProgressNotifications.notification(context, "fixture", stage, now, stage.until)
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertNotNull(notification.contentView)
        assertNotNull(notification.bigContentView)
        assertNotNull(notification.contentIntent)
        assertNotNull(notification.deleteIntent)
        assertEquals(60_000L, notification.timeoutAfter)
    }
}
