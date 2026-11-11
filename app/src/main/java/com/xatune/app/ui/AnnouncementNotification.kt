package com.xatune.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xatune.app.data.ReceivedAnnouncement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun AnnouncementNotification(model: AnnouncementViewModel) {
    val pending by model.pending.collectAsStateWithLifecycle()
    val notice = pending.firstOrNull() ?: return
    key(notice.id) { AnnouncementDetail(notice, null, model) { model.dismiss(notice.id) } }
}

private fun announcementDate(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AnnouncementDetail(announcement: Announcement, receivedAt: Long?, model: AnnouncementViewModel, onConfirm: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onConfirm, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).testTag("announcementDetail")) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(announcement.title, style = MaterialTheme.typography.headlineSmall)
                Text("发布于 " + announcementDate(announcement.publishedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                receivedAt?.let { Text("接收于 " + announcementDate(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                announcement.imageUrl?.let { AnnouncementCover(it, model) }
                AnnouncementMarkdown(announcement.body)
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).testTag("announcementConfirm")) { Text("确认") }
        }
    }
}

@Composable private fun AnnouncementCover(url: String, model: AnnouncementViewModel) {
    val revision by model.imageRevision.collectAsStateWithLifecycle()
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, url, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = model.cachedImage(url) ?: return@withContext null
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                var sample = 1
                while (maxOf(options.outWidth, options.outHeight) / sample > 2048) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, "公告封面", Modifier.fillMaxWidth(), contentScale = androidx.compose.ui.layout.ContentScale.FillWidth) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AnnouncementHistoryScreen(model: AnnouncementViewModel, onBack: () -> Unit, onOpen: (ReceivedAnnouncement) -> Unit) {
    val records by model.history.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("历史公告") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("announcementHistory"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (records.isEmpty()) item { Text("暂无历史公告", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(records, key = { it.key }) { record ->
                val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart) { model.delete(record); true } else false
                })
                SwipeToDismissBox(state = swipe, modifier = Modifier.testTag("announcementHistoryRow:" + record.announcement.id), enableDismissFromStartToEnd = false, backgroundContent = {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Delete, "删除"); Text("删除") }
                    }
                }) {
                    Card(Modifier.fillMaxWidth().clickable { onOpen(record) }) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(record.announcement.title, style = MaterialTheme.typography.titleMedium)
                                Text("接收于 " + announcementDate(record.receivedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { model.delete(record) }) { Icon(Icons.Default.Delete, "删除公告") }
                        }
                    }
                }
            }
            if (records.isNotEmpty()) item { TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("清除本地所有公告") } }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空公告记录？") },
        text = { Text("将清除本机的 ${records.size} 条公告记录及其已保存封面。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; model.clearHistory() }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("暂不清除") } })
}
