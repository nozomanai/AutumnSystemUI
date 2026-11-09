package io.autumn.systemui.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.autumn.systemui.AutumnApp
import io.autumn.systemui.BuildConfig
import io.autumn.systemui.ui.component.SegmentedColumn
import io.autumn.systemui.ui.component.SegmentedDivider
import io.autumn.systemui.ui.hotReloadSystemUi
import io.autumn.systemui.ui.requestSystemUiScope
import io.autumn.systemui.ui.restartSystemUi
import io.autumn.systemui.ui.toast

@Composable
fun SettingsPage() {
    val context = LocalContext.current
    val service = AutumnApp.bridge
    val scope = remember(service) {
        try {
            service?.scope.orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        SegmentedColumn {
            ActionRow(
                icon = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
                title = "重启系统界面",
                summary = "需要 root。改动没有立刻出现在状态栏上时用这个",
                onClick = { restartSystemUi(context) },
            )
            SegmentedDivider()
            ActionRow(
                icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                title = "重新载入模块",
                summary = "让已打开的系统界面重新执行钩子，不重启界面",
                onClick = {
                    if (service == null) context.toast("还没有连上 LSPosed")
                    else hotReloadSystemUi(context, service)
                },
            )
            SegmentedDivider()
            ActionRow(
                icon = { Icon(Icons.Filled.Apps, contentDescription = null) },
                title = "申请系统界面作用域",
                summary = if (scope.any { it.contains("systemui") }) {
                    "当前已包含系统界面"
                } else {
                    "向 LSPosed 请求勾选 com.android.systemui"
                },
                onClick = {
                    if (service == null) context.toast("还没有连上 LSPosed")
                    else requestSystemUiScope(context, service)
                },
            )
        }
        SegmentedColumn {
            ListItem(
                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                headlineContent = { Text("关于") },
                supportingContent = {
                    Text(
                        "秋日状态栏 ${BuildConfig.VERSION_NAME}\n" +
                            "始终隐藏状态栏 VoLTE / VoNR，下拉通知栏或控制中心也不显示。\n" +
                            "反编译后的系统界面源码在工程的 decompiled 目录，不纳入 git。",
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ActionRow(
    icon: @Composable () -> Unit,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = icon,
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
