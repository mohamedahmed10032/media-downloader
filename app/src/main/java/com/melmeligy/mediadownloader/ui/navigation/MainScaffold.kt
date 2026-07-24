package com.melmeligy.mediadownloader.ui.navigation

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
import androidx.navigation.NavHostController
import com.melmeligy.mediadownloader.R

/** The four primary destinations reachable from the bottom navigation bar. */
enum class Tab(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    HOME(Routes.HOME, R.string.nav_home, Icons.Rounded.Home),
    DOWNLOADS(Routes.DOWNLOADS, R.string.nav_downloads, Icons.Rounded.Download),
    LIBRARY(Routes.LIBRARY, R.string.nav_library, Icons.Rounded.VideoLibrary),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Rounded.Settings)
}

/** Shared scaffold (top app bar + bottom navigation) for the primary tab screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    navController: NavHostController,
    current: Tab,
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }, actions = actions) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == current,
                        onClick = {
                            if (tab != current) {
                                navController.navigate(tab.route) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) }
                    )
                }
            }
        }
    ) { padding -> content(padding) }
}
