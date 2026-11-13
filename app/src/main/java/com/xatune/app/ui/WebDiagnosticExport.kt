package com.xatune.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.xatune.app.BuildConfig
import com.xatune.app.platform.WebDiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
internal fun WebDiagnosticExport(beforeExport: () -> Unit = {}) {
    if (!BuildConfig.WEB_DIAGNOSTICS) return
    val context = LocalContext.current
    DiagnosticExport("网页") {
        beforeExport()
        withContext(Dispatchers.IO) {
            ByteArrayOutputStream().also { WebDiagnosticLog.export(context, it) }.toString(Charsets.UTF_8.name())
        }
    }
}
