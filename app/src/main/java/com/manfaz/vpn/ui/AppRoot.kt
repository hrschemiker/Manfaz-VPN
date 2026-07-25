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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.manfaz.vpn.ui.screens.HomeScreen
import com.manfaz.vpn.ui.screens.ConfigEditorScreen
import com.manfaz.vpn.ui.screens.DiagnosticsScreen
import com.manfaz.vpn.ui.screens.FreeConfigsScreen
import com.manfaz.vpn.ui.screens.ImportScreen
import com.manfaz.vpn.ui.screens.PerAppScreen
import com.manfaz.vpn.ui.screens.ServersScreen
import com.manfaz.vpn.ui.screens.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "خانه", Icons.Filled.Home),
    Tab("servers", "سرورها", Icons.Filled.Dns),
    Tab("import", "افزودن", Icons.Filled.Add),
    Tab("settings", "تنظیمات", Icons.Filled.Settings),
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
                    val selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true ||
                        (tab.route == "servers" && route == "free") ||
                        (tab.route == "settings" && route == "perapp")
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            // No saveState/restoreState: the Servers tab always lands on the
                            // subscription list (never sticks on the Free Configs sub-screen).
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(inner),
        ) {
            composable("home") { HomeScreen(vm, onToggleConnection) }
            composable("servers") {
                ServersScreen(
                    vm, onConnectServer,
                    onOpenFree = { navController.navigate("free") },
                    onEditServer = { id -> navController.navigate("editor/$id") },
                )
            }
            composable("free") {
                FreeConfigsScreen(vm, onConnect = onConnectServer, onBack = { navController.popBackStack() })
            }
            composable("import") {
                ImportScreen(vm) {
                    navController.navigate("servers") {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            composable("settings") {
                SettingsScreen(
                    onOpenPerApp = { navController.navigate("perapp") },
                    onOpenDiagnostics = { navController.navigate("diagnostics") },
                )
            }
            composable("perapp") { PerAppScreen() }
            composable("diagnostics") { DiagnosticsScreen(onBack = { navController.popBackStack() }) }
            composable("editor/{id}") { entry ->
                ConfigEditorScreen(
                    vm, entry.arguments?.getString("id") ?: "",
                    onDone = { navController.popBackStack() },
                )
            }
        }
    }
}
