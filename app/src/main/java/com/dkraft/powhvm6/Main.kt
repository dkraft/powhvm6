package com.dkraft.powhvm6

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.dkraft.powhvm6.ui.SearchScreen
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
            PowHvmTheme(nightMode = dark) {
                AppRoot(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    var screen by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (screen) {
                            0 -> "POW-HVM6"
                            1 -> "Settings"
                            2 -> "Search"
                            else -> ""
                        }
                    )
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
        }
    ) { pad ->
        when (screen) {
            0 -> DashboardScreen(vm, modifier = Modifier.padding(pad))
            1 -> SettingsScreen(vm, modifier = Modifier.padding(pad))
            2 -> SearchScreen(vm, modifier = Modifier.padding(pad))
        }
    }
}
