package app.ikev2manager.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class Tab(val route: String, val label: String)

private val tabs = listOf(
    Tab("dashboard", "Dashboard"),
    Tab("servers", "Servers"),
    Tab("connection", "Connect"),
    Tab("settings", "Settings"),
)

@Composable
fun IkeApp(vm: MainViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val run by vm.run.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Keep the screen on while a scan / VPN test runs (tests run only while the app is visible).
    val view = LocalView.current
    DisposableEffect(run.running) {
        view.keepScreenOn = run.running
        onDispose { view.keepScreenOn = false }
    }

    val showBar = route in tabs.map { it.route }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo("dashboard") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {},
                            label = { Text(t.label) },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).then(if (showBar) Modifier else Modifier.navigationBarsPadding())) {
            NavHost(nav, startDestination = "dashboard") {
                composable("dashboard") { DashboardScreen(vm, nav) }
                composable("servers") { ServerListScreen(vm, nav) }
                composable("connection") { ConnectionScreen(vm, nav) }
                composable("settings") { SettingsScreen(vm, nav) }
                composable("scan") { ScanConfigScreen(vm, nav) }
                composable("credentials") { CredentialsScreen(vm, nav) }
                composable("server/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    ServerDetailScreen(vm, nav, e.arguments?.getLong("id") ?: -1L)
                }
            }
        }
    }
}
