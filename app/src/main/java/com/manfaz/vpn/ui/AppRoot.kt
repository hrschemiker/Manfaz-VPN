package com.manfaz.vpn.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.manfaz.vpn.ui.screens.HomeScreen
import com.manfaz.vpn.ui.screens.ConfigEditorScreen
import com.manfaz.vpn.ui.screens.DiagnosticsScreen
import com.manfaz.vpn.ui.screens.ImportScreen
import com.manfaz.vpn.ui.screens.PerAppScreen
import com.manfaz.vpn.ui.screens.ServersScreen
import com.manfaz.vpn.ui.screens.SettingsScreen

private const val EDITOR_PREFIX = "editor/"
private const val EDITOR_ROUTE = EDITOR_PREFIX + "{id}"

private object Routes {
    const val HOME = "home"
    const val SERVERS = "servers"
    const val IMPORT = "import"
    const val SETTINGS = "settings"
    const val PER_APP = "perapp"
    const val DIAGNOSTICS = "diagnostics"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "خانه", Icons.Filled.Home),
    Tab(Routes.SERVERS, "سرورها", Icons.Filled.Dns),
    Tab(Routes.IMPORT, "افزودن", Icons.Filled.Add),
    Tab(Routes.SETTINGS, "تنظیمات", Icons.Filled.Settings),
)

/** Sub-screens that keep their parent tab highlighted while they are open. */
private val tabOwners = mapOf(
    Routes.PER_APP to Routes.SETTINGS,
    Routes.DIAGNOSTICS to Routes.SETTINGS,
    EDITOR_ROUTE to Routes.SERVERS,
)

@Composable
fun AppRoot(vm: MainViewModel, onToggleConnection: () -> Unit, onConnectServer: () -> Unit) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination

    val snackHost = remember { SnackbarHostState() }
    val snack by vm.snack.collectAsState()
    LaunchedEffect(snack) {
        snack?.let {
            snackHost.showSnackbar(it)
            vm.consumeSnack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackHost) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val route = currentRoute?.route
                    val owner = route?.let { tabOwners[it] }
                    val selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true ||
                        owner == tab.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.switchTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(bottom = inner.calculateBottomPadding()),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    vm = vm,
                    onToggle = onToggleConnection,
                    onConnectServer = onConnectServer,
                    onOpenServers = { navController.switchTab(Routes.SERVERS) },
                )
            }
            composable(Routes.SERVERS) {
                ServersScreen(
                    vm = vm,
                    onConnect = onConnectServer,
                    onAddServer = { navController.switchTab(Routes.IMPORT) },
                    onEditServer = { id -> navController.navigate("$EDITOR_PREFIX$id") },
                )
            }
            composable(Routes.IMPORT) {
                ImportScreen(vm) { navController.switchTab(Routes.SERVERS) }
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenPerApp = { navController.navigate(Routes.PER_APP) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                )
            }
            composable(Routes.PER_APP) { PerAppScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { navController.popBackStack() }) }
            composable(EDITOR_ROUTE) { entry ->
                ConfigEditorScreen(
                    vm, entry.arguments?.getString("id") ?: "",
                    onDone = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * Tab switching that behaves the way a bottom bar is expected to: one entry per tab on the
 * back stack, re-tapping the current tab is a no-op, and each tab remembers where it was.
 */
private fun NavHostController.switchTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
