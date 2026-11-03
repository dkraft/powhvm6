package com.dkraft.powhvm6

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dkraft.powhvm6.ui.DashboardScreen
import com.dkraft.powhvm6.ui.EventsScreen
import com.dkraft.powhvm6.ui.SearchScreen
import com.dkraft.powhvm6.ui.SettingEditorHost
import com.dkraft.powhvm6.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    // Lifecycle-scoped so rotation doesn't create a second poller.
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = when (vm.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            // Status/navigation bar icons follow the chosen scheme, not just the system one.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            LaunchedEffect(vm.keepScreenOn) { applyKeepScreenOn() }
            PowHvmTheme(nightMode = dark) {
                AppRoot(vm)
            }
        }
    }

    private var foreground = false

    override fun onResume() {
        super.onResume()
        foreground = true
        vm.appForeground = true
        applyKeepScreenOn()
    }

    override fun onPause() {
        foreground = false
        vm.appForeground = false
        applyKeepScreenOn()
        super.onPause()
    }

    /** FLAG_KEEP_SCREEN_ON only while the setting is on and the activity is in the foreground. */
    private fun applyKeepScreenOn() {
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (vm.keepScreenOn && foreground) window.addFlags(flag) else window.clearFlags(flag)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    var screen by remember { mutableStateOf(0) }
    /** Bottom tab on the main screen: 0 = Live, 1 = Events. */
    var tab by remember { mutableStateOf(0) }
    /** Tracker whose history is open on the Events tab. */
    var historyId by remember { mutableStateOf<Long?>(null) }
    val eventsVisible = screen == 0 && tab == 1

    DisposableEffect(eventsVisible) {
        if (eventsVisible) vm.startEventsPolling()
        onDispose { vm.stopEventsPolling() }
    }
    BackHandler(enabled = eventsVisible && historyId != null) {
        historyId = null
        vm.clearEventHistory()
    }
    BackHandler(enabled = screen != 0) { screen = 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (screen) {
                            0 -> if (tab == 0) "POW-HVM6"
                                 else if (historyId != null) vm.events?.trackers?.firstOrNull { it.id == historyId }?.name
                                    ?: vm.eventHistory?.tracker?.name ?: "History"
                                 else "Events"
                            1 -> "Settings"
                            2 -> "Search"
                            else -> ""
                        }
                    )
                },
                navigationIcon = {
                    if (eventsVisible && historyId != null) {
                        IconButton(onClick = { historyId = null; vm.clearEventHistory() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back to events")
                        }
                    }
                },
                actions = {
                    if (screen == 0) {
                        IconButton(onClick = { screen = 2 }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { screen = 1 }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    } else {
                        IconButton(onClick = { screen = 0 }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (screen == 0) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.Bolt, contentDescription = null) },
                        label = { Text("Live") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = {
                            if (tab == 1 && historyId != null) { historyId = null; vm.clearEventHistory() }
                            tab = 1
                        },
                        icon = { Icon(Icons.Default.EventNote, contentDescription = null) },
                        label = { Text("Events") }
                    )
                }
            }
        }
    ) { pad ->
        when (screen) {
            0 -> if (tab == 0) DashboardScreen(vm, modifier = Modifier.padding(pad))
                 else EventsScreen(
                     vm,
                     historyId = historyId,
                     onOpenHistory = { historyId = it; vm.loadEventHistory(it) },
                     onCloseHistory = { historyId = null; vm.clearEventHistory() },
                     modifier = Modifier.padding(pad)
                 )
            1 -> SettingsScreen(vm, modifier = Modifier.padding(pad))
            2 -> SearchScreen(vm, modifier = Modifier.padding(pad))
        }
        SettingEditorHost(vm)
    }
}
