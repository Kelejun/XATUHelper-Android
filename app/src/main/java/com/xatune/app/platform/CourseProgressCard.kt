package com.xatune.app.platform

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import com.xatune.app.R
import com.xatune.app.model.CourseProgressStage
import com.xatune.app.ui.CoursePalette
import java.time.Instant

internal object CourseProgressCard {
    fun accent(context: Context, stage: CourseProgressStage): Int {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val palette = if (dark) CoursePalette.dark else CoursePalette.light
        return palette[stage.lesson.colorIndex.mod(palette.size)].accent.toArgb()
    }

    fun icon(stage: CourseProgressStage): Int = when {
        stage.finished -> R.drawable.ic_course_check
        stage.preparing -> R.drawable.ic_course_walk
        else -> R.drawable.ic_course_book
    }

    fun views(context: Context, stage: CourseProgressStage, now: Instant, compact: Boolean): RemoteViews {
        val accent = accent(context, stage)
        val layout = if (compact) R.layout.notification_course_progress_compact else R.layout.notification_course_progress
        return RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.course_name, stage.lesson.name)
            setTextViewText(R.id.course_phase, stage.phase)
            setTextViewText(R.id.course_room, stage.lesson.room)
            setImageViewResource(R.id.course_phase_icon, icon(stage))
            setInt(R.id.course_phase_icon, "setColorFilter", accent)
            setInt(R.id.course_completion, "setColorFilter", accent)
            setTextColor(R.id.course_phase, accent)
            setTextColor(R.id.course_room, accent)
            setViewVisibility(R.id.course_timer, if (stage.finished) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.course_completion, if (stage.finished) View.VISIBLE else View.GONE)
            setContentDescription(R.id.course_completion, stage.phase)
            val label = if (stage.preparing) "距上课" else "距下课"
            setContentDescription(R.id.course_timer, label)
            setChronometer(R.id.course_timer, SystemClock.elapsedRealtime() + stage.until.toEpochMilli() - now.toEpochMilli(), null, !stage.finished)
            setChronometerCountDown(R.id.course_timer, true)
            if (!compact) {
                val origin = stage.routeOrigin
                setViewVisibility(R.id.course_route, if (origin == null) View.GONE else View.VISIBLE)
                setViewVisibility(R.id.course_room, if (origin == null) View.VISIBLE else View.GONE)
                setTextViewText(R.id.course_origin, origin.orEmpty())
                setTextViewText(R.id.course_destination, stage.lesson.room)
                setTextColor(R.id.course_destination, accent)
                setInt(R.id.course_route_arrow, "setColorFilter", accent)
                setContentDescription(R.id.course_route, "从${origin.orEmpty()}到${stage.lesson.room}")
            }
            val percent = stage.percentAt(now)
            setImageViewBitmap(R.id.course_progress, track(context, accent, percent))
            setContentDescription(R.id.course_progress, "${if (stage.preparing) "课前" else "课程"}进度 $percent%")
        }
    }

    // A small strip stretches to the host width and keeps the per-course accent on API 26–30.
    private fun track(context: Context, accent: Int, percent: Int): Bitmap {
        val bitmap = createBitmap(600, 8)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = context.getColor(R.color.course_activity_secondary)
        paint.alpha = 45
        canvas.drawRoundRect(0f, 0f, 600f, 8f, 4f, 4f, paint)
        paint.color = accent
        canvas.drawRoundRect(0f, 0f, 6f * percent, 8f, 4f, 4f, paint)
        return bitmap
    }
}
