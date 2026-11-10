package io.autumn.systemui.ui.statusbar

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.autumn.systemui.AutumnApp
import io.autumn.systemui.ModuleConfig
import io.autumn.systemui.report.HookReportStore
import io.autumn.systemui.ui.component.SegmentedColumn
import io.autumn.systemui.ui.component.SegmentedDivider
import io.autumn.systemui.ui.component.TonalMessage
import io.github.libxposed.service.XposedService

@Composable
fun StatusBarPage() {
    val service = AutumnApp.bridge
    val prefs = remember(service) { service?.statusBarPrefsOrNull() }
    var hideVolte by remember(prefs) {
        mutableStateOf(prefs?.getBoolean(ModuleConfig.KEY_HIDE_VOLTE_COLLAPSED, true) ?: true)
    }
    var dualSignal by remember(prefs) {
        mutableStateOf(prefs?.getBoolean(ModuleConfig.KEY_DUAL_SIGNAL, true) ?: true)
    }
    DisposableEffect(prefs) {
        prefs?.edit()?.apply {
            if (!prefs.contains(ModuleConfig.KEY_HIDE_VOLTE_COLLAPSED)) {
                putBoolean(ModuleConfig.KEY_HIDE_VOLTE_COLLAPSED, true)
            }
            if (!prefs.contains(ModuleConfig.KEY_DUAL_SIGNAL)) {
                putBoolean(ModuleConfig.KEY_DUAL_SIGNAL, true)
            }
        }?.apply()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key == null || key == ModuleConfig.KEY_HIDE_VOLTE_COLLAPSED) {
                hideVolte = shared.getBoolean(ModuleConfig.KEY_HIDE_VOLTE_COLLAPSED, true)
            }
            if (key == null || key == ModuleConfig.KEY_DUAL_SIGNAL) {
                dualSignal = shared.getBoolean(ModuleConfig.KEY_DUAL_SIGNAL, true)
            }
        }
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs?.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val report = remember(service) { HookReportStore.read() }
    var reportText by remember(report) { mutableStateOf(report) }
    DisposableEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { shared, _ ->
            reportText = shared.getString(ModuleConfig.REPORT_KEY, "").orEmpty()
        }
        val store = HookReportStore.prefs()
        store.registerOnSharedPreferenceChangeListener(listener)
        reportText = store.getString(ModuleConfig.REPORT_KEY, "").orEmpty()
        onDispose { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        if (prefs == null) {
            TonalMessage(
                message = "配置通道还没就绪。启用模块并连上 LSPosed 后，开关会写到框架的远程配置里。",
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        SegmentedColumn {
            ListItem(
                modifier = Modifier.clickable(enabled = prefs != null) {
                    val next = !hideVolte
                    hideVolte = next
                    prefs?.edit()?.putBoolean(ModuleConfig.KEY_HIDE_VOLTE_COLLAPSED, next)?.apply()
                },
                leadingContent = { Icon(Icons.Outlined.CellTower, contentDescription = null) },
                headlineContent = { Text("隐藏 VoLTE") },
                supportingContent = {
                    Text("状态栏里的 VoLTE / VoNR 一直隐藏，下拉通知栏或控制中心也不显示。")
                },
                trailingContent = {
                    Switch(
                        checked = hideVolte,
                        enabled = prefs != null,
                        onCheckedChange = { checked ->
                            hideVolte = checked
                            prefs?.edit()?.putBoolean(ModuleConfig.KEY_HIDE_VOLTE_COLLAPSED, checked)?.apply()
                        },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            SegmentedDivider()
            ListItem(
                modifier = Modifier.clickable(enabled = prefs != null) {
                    val next = !dualSignal
                    dualSignal = next
                    prefs?.edit()?.putBoolean(ModuleConfig.KEY_DUAL_SIGNAL, next)?.apply()
                },
                leadingContent = { Icon(Icons.Outlined.SignalCellularAlt, contentDescription = null) },
                headlineContent = { Text("双排信号") },
                supportingContent = {
                    Text("两张卡的信号上下叠放。关闭后恢复系统原来的并排显示。")
                },
                trailingContent = {
                    Switch(
                        checked = dualSignal,
                        enabled = prefs != null,
                        onCheckedChange = { checked ->
                            dualSignal = checked
                            prefs?.edit()?.putBoolean(ModuleConfig.KEY_DUAL_SIGNAL, checked)?.apply()
                        },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Text(
            text = "开关通过 LSPosed 远程配置立即下发。图标若仍停在旧状态，用右上角菜单重启系统界面。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text("钩子报告", style = MaterialTheme.typography.titleMedium)
        SegmentedColumn {
            Text(
                text = reportText.ifBlank { "系统界面还没有回报。启用模块、勾选系统界面并重启它之后，这里会列出命中的 VoLTE 和控制中心类。" },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun XposedService.statusBarPrefsOrNull(): SharedPreferences? {
    return try {
        if (frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) {
            null
        } else {
            getRemotePreferences(ModuleConfig.PREF_GROUP)
        }
    } catch (_: Throwable) {
        null
    }
}
