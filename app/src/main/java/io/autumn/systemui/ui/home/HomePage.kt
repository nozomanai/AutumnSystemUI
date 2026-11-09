package io.autumn.systemui.ui.home

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.autumn.systemui.AutumnApp
import io.autumn.systemui.BuildConfig
import io.autumn.systemui.ui.component.InfoRow
import io.autumn.systemui.ui.component.SegmentedColumn
import io.autumn.systemui.ui.component.SegmentedDivider
import io.autumn.systemui.ui.component.StatusTag
import io.autumn.systemui.ui.requestSystemUiScope
import io.autumn.systemui.ui.restartSystemUi
import java.io.File

private enum class LinkState {
    Working,
    Waiting,
    MissingScope,
    Disconnected,
}

@Composable
fun HomePage() {
    val context = LocalContext.current
    val service = AutumnApp.bridge
    val scope = remember(service) {
        try {
            service?.scope.orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }
    val running = remember(service, scope) {
        try {
            service?.runningTargets?.any { it.processName.contains("systemui") } == true
        } catch (_: Throwable) {
            false
        }
    }
    val scoped = scope.any { it.contains("systemui") }
    val state = when {
        service == null -> LinkState.Disconnected
        !scoped -> LinkState.MissingScope
        !running -> LinkState.Waiting
        else -> LinkState.Working
    }
    val selinux = remember { readSelinux() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        StatusCard(
            state = state,
            framework = service?.let { "${it.frameworkName} ${it.frameworkVersion}" }.orEmpty(),
            onClick = {
                when (state) {
                    LinkState.MissingScope -> service?.let { requestSystemUiScope(context, it) }
                    LinkState.Waiting -> restartSystemUi(context)
                    LinkState.Working, LinkState.Disconnected -> Unit
                }
            },
        )
        SegmentedColumn {
            InfoRow(Icons.Filled.Tag, "模块版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            SegmentedDivider()
            InfoRow(Icons.Filled.DeveloperBoard, "Android", "${Build.VERSION.RELEASE}  ${Build.DISPLAY}")
            SegmentedDivider()
            InfoRow(Icons.Filled.Smartphone, "设备", "${Build.MANUFACTURER} ${Build.MODEL}")
            SegmentedDivider()
            InfoRow(Icons.Filled.Fingerprint, "指纹", Build.FINGERPRINT)
        }
        SegmentedColumn {
            InfoRow(
                Icons.Filled.Security,
                "SELinux",
                selinux,
            )
            SegmentedDivider()
            InfoRow(
                Icons.Filled.DeveloperBoard,
                "框架",
                if (service == null) "未连接" else "${service.frameworkName} ${service.frameworkVersion}  API ${service.apiVersion}",
            )
            SegmentedDivider()
            InfoRow(
                Icons.Filled.Tag,
                "作用域",
                scope.ifEmpty { listOf("未勾选") }.joinToString("\n"),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusCard(
    state: LinkState,
    framework: String,
    onClick: () -> Unit,
) {
    val container = when (state) {
        LinkState.Working -> MaterialTheme.colorScheme.secondaryContainer
        LinkState.Waiting -> MaterialTheme.colorScheme.tertiaryContainer
        LinkState.MissingScope, LinkState.Disconnected -> MaterialTheme.colorScheme.errorContainer
    }
    val content = MaterialTheme.colorScheme.contentColorFor(container)
    val icon: ImageVector = when (state) {
        LinkState.Working -> Icons.Rounded.CheckCircle
        LinkState.Waiting, LinkState.MissingScope -> Icons.Rounded.Warning
        LinkState.Disconnected -> Icons.Rounded.Block
    }
    val title = when (state) {
        LinkState.Working -> "工作中"
        LinkState.Waiting -> "等待系统界面"
        LinkState.MissingScope -> "未加入作用域"
        LinkState.Disconnected -> "未激活"
    }
    val summary = when (state) {
        LinkState.Working -> "系统界面已加载模块，状态栏 VoLTE 会一直隐藏"
        LinkState.Waiting -> "作用域已包含系统界面。点此重启系统界面后生效"
        LinkState.MissingScope -> "点此向 LSPosed 申请系统界面作用域"
        LinkState.Disconnected -> "请在支持现代接口的 LSPosed 中启用本模块"
    }
    val clickable = state == LinkState.MissingScope || state == LinkState.Waiting
    val card: @Composable () -> Unit = {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = title) },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (state == LinkState.Working && framework.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        StatusTag(
                            label = "SystemUI",
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            },
            supportingContent = {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.75f))
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = content,
                leadingIconColor = content,
                supportingColor = content,
            ),
        )
    }
    if (clickable) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = container,
            contentColor = content,
            onClick = onClick,
            content = card,
        )
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = container,
            contentColor = content,
            content = card,
        )
    }
}

private fun readSelinux(): String {
    return try {
        when (File("/sys/fs/selinux/enforce").readText().trim()) {
            "1" -> "Enforcing"
            "0" -> "Permissive"
            else -> "未知"
        }
    } catch (_: Exception) {
        "未知"
    }
}
