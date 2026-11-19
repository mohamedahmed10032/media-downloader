package com.melmeligy.mediadownloader.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.melmeligy.mediadownloader.R

/** The four primary destinations reachable from the bottom navigation bar. */
enum class Tab(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    HOME(Routes.HOME, R.string.nav_home, Icons.Rounded.Home),
    DOWNLOADS(Routes.DOWNLOADS, R.string.nav_downloads, Icons.Rounded.Download),
    LIBRARY(Routes.LIBRARY, R.string.nav_library, Icons.Rounded.VideoLibrary),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Rounded.Settings)
}

/**
 * Navigates cleanly between the four primary bottom-bar tabs while keeping saved state
 * consistent and ignoring duplicate taps against an already-selected or mid-transition tab.
 */
fun NavHostController.navigateToTopLevelTab(tab: Tab) {
    val activeRoute = currentBackStackEntry?.destination?.route ?: currentDestination?.route
    if (activeRoute == tab.route) return
    runCatching {
        navigate(tab.route) {
            popUpTo(Routes.HOME) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
}

/** Persistent bottom navigation bar hoisted outside [AppNavHost] animated transitions. */
@Composable
fun PersistentBottomBar(
    navController: NavHostController,
    currentTab: Tab
) {
    NavigationBar {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == currentTab,
                onClick = {
                    if (tab != currentTab) {
                        navController.navigateToTopLevelTab(tab)
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) }
            )
        }
    }
}

/**
 * Shared top-bar scaffold for the primary tab screens. The bottom navigation bar is hoisted
 * into [AppNavHost] so switching tabs never fades or remounts the navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    navController: NavHostController,
    current: Tab,
    title: String,
    outerPadding: PaddingValues = PaddingValues(0.dp),
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    BackHandler(enabled = current != Tab.HOME) {
        navController.navigateToTopLevelTab(Tab.HOME)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }, actions = actions) }
    ) { innerPadding ->
        val merged = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = maxOf(innerPadding.calculateBottomPadding(), outerPadding.calculateBottomPadding())
        )
        content(merged)
    }
}
