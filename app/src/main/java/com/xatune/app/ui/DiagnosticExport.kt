package com.xatune.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
internal fun DiagnosticExport(category: String, label: String = "导出诊断", snapshot: suspend () -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val file = pending?.let(::File)
        if (uri == null) { file?.delete(); pending = null; busy = false }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(file)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("无法打开保存位置")
                }
                result = "诊断文本已导出"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "导出失败，请重新选择保存位置" }
            finally { file?.delete(); pending = null; busy = false }
        }
    }
    TextButton(enabled = !busy, modifier = Modifier.testTag("exportDiagnostic:$category"), onClick = {
        busy = true
        scope.launch {
            try {
                val content = snapshot()
                val file = withContext(Dispatchers.IO) { File.createTempFile("diagnostic-", ".txt", context.cacheDir).apply { writeText(content, Charsets.UTF_8) } }
                pending = file.absolutePath
                picker.launch("XATune-$category-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.txt")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { pending?.let(::File)?.delete(); pending = null; busy = false; error = "导出失败，请重试" }
        }
    }) { Text(if (busy) "导出中…" else label) }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("无法导出诊断") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("好") } }) }
    result?.let { message -> AlertDialog(onDismissRequest = { result = null }, text = { Text(message) }, confirmButton = { TextButton(onClick = { result = null }) { Text("好") } }) }
}
