package io.autumn.systemui.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.autumn.systemui.AutumnApp
import io.autumn.systemui.R
import io.autumn.systemui.ui.home.HomePage
import io.autumn.systemui.ui.settings.SettingsPage
import io.autumn.systemui.ui.statusbar.StatusBarPage
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Rounded.Home),
    StatusBar("状态栏", Icons.Rounded.SignalCellularAlt),
    Settings("设置", Icons.Rounded.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutumnRoot() {
    val tabs = Tab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    var selected by remember { mutableIntStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val title = if (selected == 0) stringResource(R.string.app_name) else tabs[selected].label

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
                ),
                actions = { PowerMenu() },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = {
                            selected = index
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(padding),
            userScrollEnabled = false,
        ) { page ->
            when (tabs[page]) {
                Tab.Home -> HomePage()
                Tab.StatusBar -> StatusBarPage()
                Tab.Settings -> SettingsPage()
            }
        }
    }
}

@Composable
private fun PowerMenu() {
    val context = LocalContext.current
    val service = AutumnApp.bridge
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "电源")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("重启系统界面") },
            onClick = {
                expanded = false
                restartSystemUi(context)
            },
        )
        DropdownMenuItem(
            text = { Text("重新载入模块") },
            onClick = {
                expanded = false
                if (service == null) {
                    context.toast("还没有连上 LSPosed")
                } else {
                    hotReloadSystemUi(context, service)
                }
            },
        )
    }
}
