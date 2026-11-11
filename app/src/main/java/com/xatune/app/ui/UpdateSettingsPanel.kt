package com.xatune.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xatune.app.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun UpdateSettingsPanel(
    update: UpdateUi,
    onChannel: (UpdateChannel) -> Unit,
    onServer: (UpdateServer) -> Unit,
    onRepository: (String) -> Unit,
    onAutomatic: (Boolean) -> Unit,
    onCheck: () -> Unit
) {
    val settings = update.settings
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("更新渠道", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdateChannel.entries.forEach { channel ->
                FilterChip(selected = settings.channel == channel, onClick = { onChannel(channel) },
                    label = { Text(channel.label) }, modifier = Modifier.testTag("updateChannel-${channel.value}"))
            }
        }
        Text("下载服务器", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = settings.server == UpdateServer.GITHUB, onClick = { onServer(UpdateServer.GITHUB) }, label = { Text("GitHub") })
            FilterChip(selected = settings.server == UpdateServer.OTHER, onClick = { onServer(UpdateServer.OTHER) }, label = { Text("其他") })
        }
        if (settings.server == UpdateServer.OTHER) {
            var url by rememberSaveable { mutableStateOf(settings.repositoryUrl) }
            OutlinedTextField(value = url, onValueChange = { url = it; onRepository(it) },
                label = { Text("仓库网址") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("updateRepositoryUrl"))
            Text("其他服务器暂未接入，目前仅保存仓库网址。", style = MaterialTheme.typography.bodySmall)
        }
        ToggleRow("自动检测更新", "每次启动和每天 9:00 检测，系统省电限制可能延后后台检测", settings.automatic, onAutomatic,
            switchModifier = Modifier.testTag("automaticUpdates"), contentPadding = PaddingValues(vertical = 12.dp))
        if (update.logs.isEmpty()) Text("暂无更新检测记录", style = MaterialTheme.typography.bodySmall)
        update.logs.forEachIndexed { index, log ->
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(log.at))
            val kind = if (log.automatic) "自动" else "手动"
            val result = if (log.success) "成功：${log.detail}" else "失败，原因为：${log.detail}"
            Text("$time ${kind}检测更新$result（${log.channel.label}）", style = MaterialTheme.typography.bodySmall,
                color = if (log.success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("updateLog$index"))
        }
        FilledTonalButton(onClick = onCheck, enabled = !update.checking,
            modifier = Modifier.fillMaxWidth().testTag("checkUpdates")) {
            if (update.checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(if (update.checking) "正在检查更新…" else "检查更新")
        }
        update.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("updateStatus")) }
    }
}
