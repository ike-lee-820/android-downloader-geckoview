package com.yuanbao.downloader.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yuanbao.downloader.browser.TabSessionManager
import com.yuanbao.downloader.presentation.screens.BrowserScreen
import com.yuanbao.downloader.presentation.screens.DownloadScreen
import com.yuanbao.downloader.presentation.screens.OnboardingScreen
import com.yuanbao.downloader.presentation.screens.SettingsScreen

private data class TabSpec(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(
    tabSessionManager: TabSessionManager,
    mainViewModel: MainViewModel
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val settings by mainViewModel.settings.collectAsState()
    val message by mainViewModel.message.collectAsState()
    val confirm by mainViewModel.mobileConfirm.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            mainViewModel.clearMessage()
        }
    }

    if (confirm != null) {
        AlertDialog(
            onDismissRequest = { mainViewModel.dismissMobileConfirm() },
            title = { Text("移动网络确认") },
            text = { Text(confirm.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { mainViewModel.confirmMobile() }) { Text("继续下载") }
            },
            dismissButton = {
                TextButton(onClick = { mainViewModel.dismissMobileConfirm() }) { Text("暂停") }
            }
        )
    }

    if (!settings.onboardingDone) {
        OnboardingScreen(onFinish = { mainViewModel.setOnboardingDone() })
        return
    }

    val tabs = listOf(
        TabSpec("download", "下载", Icons.Outlined.Download),
        TabSpec("browser", "浏览器", Icons.Outlined.Public),
        TabSpec("settings", "设置", Icons.Outlined.Settings)
    )
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "download",
            modifier = Modifier.padding(padding)
        ) {
            composable("download") { DownloadScreen(mainViewModel = mainViewModel) }
            composable("browser") { BrowserScreen(tabSessionManager = tabSessionManager) }
            composable("settings") { SettingsScreen() }
        }
    }
}
