package com.melmeligy.mediadownloader.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.melmeligy.mediadownloader.R
import com.melmeligy.mediadownloader.ui.about.AboutScreen
import com.melmeligy.mediadownloader.ui.browser.BrowserScreen
import com.melmeligy.mediadownloader.ui.downloads.DownloadsScreen
import com.melmeligy.mediadownloader.ui.home.HomeScreen
import com.melmeligy.mediadownloader.ui.library.LibraryScreen
import com.melmeligy.mediadownloader.ui.player.PlayerScreen
import com.melmeligy.mediadownloader.ui.quality.QualityScreen
import com.melmeligy.mediadownloader.ui.settings.SettingsScreen
import com.melmeligy.mediadownloader.ui.splash.SplashScreen

private fun NavHostController.safePopBackStack() {
    if (previousBackStackEntry != null && currentDestination?.route != Routes.HOME) {
        runCatching { popBackStack() }
    }
}

private fun NavHostController.navigateSingleTop(route: String) {
    runCatching {
        navigate(route) {
            launchSingleTop = true
        }
    }
}

@Composable
fun AppNavHost(
    sharedUrl: String?,
    onSharedUrlConsumed: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = Tab.entries.firstOrNull { it.route == currentRoute }

    // A link opened via "Open in Browser to Auto-Catch Stream" jumps straight to the built-in browser.
    LaunchedEffect(sharedUrl) {
        val url = sharedUrl?.trim()
        if (!url.isNullOrEmpty()) {
            navController.navigateSingleTop(Routes.browser(url))
            onSharedUrlConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            if (currentTab != null) {
                PersistentBottomBar(navController = navController, currentTab = currentTab)
            }
        }
    ) { outerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SPLASH,
            enterTransition = { fadeIn(animationSpec = tween(120)) },
            exitTransition = { fadeOut(animationSpec = tween(90)) },
            popEnterTransition = { fadeIn(animationSpec = tween(120)) },
            popExitTransition = { fadeOut(animationSpec = tween(90)) }
        ) {

            composable(Routes.SPLASH) {
                SplashScreen(onFinished = {
                    runCatching {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.SPLASH) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                })
            }

            composable(Routes.HOME) {
                MainScaffold(navController, Tab.HOME, stringResource(R.string.home_title), outerPadding) { padding ->
                    HomeScreen(
                        contentPadding = padding,
                        onOpenBrowser = { url -> navController.navigateSingleTop(Routes.browser(url)) },
                        onDetect = { url -> navController.navigateSingleTop(Routes.quality(url)) }
                    )
                }
            }

            composable(Routes.DOWNLOADS) {
                MainScaffold(navController, Tab.DOWNLOADS, stringResource(R.string.downloads_title), outerPadding) { padding ->
                    DownloadsScreen(contentPadding = padding)
                }
            }

            composable(Routes.LIBRARY) {
                MainScaffold(navController, Tab.LIBRARY, stringResource(R.string.library_title), outerPadding) { padding ->
                    LibraryScreen(
                        contentPadding = padding,
                        onPlay = { id -> navController.navigateSingleTop(Routes.player(id)) }
                    )
                }
            }

            composable(Routes.SETTINGS) {
                MainScaffold(navController, Tab.SETTINGS, stringResource(R.string.settings_title), outerPadding) { padding ->
                    SettingsScreen(
                        contentPadding = padding,
                        onOpenAbout = { navController.navigateSingleTop(Routes.ABOUT) }
                    )
                }
            }

            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.safePopBackStack() })
            }

            composable(
                route = Routes.BROWSER_ROUTE,
                arguments = listOf(navArgument(Routes.ARG_URL) {
                    type = NavType.StringType; defaultValue = ""
                })
            ) { entry ->
                val url = entry.arguments?.getString(Routes.ARG_URL).orEmpty().ifBlank { null }
                BrowserScreen(
                    initialUrl = url,
                    onDownload = { detected -> navController.navigateSingleTop(Routes.quality(detected)) },
                    onBack = { navController.safePopBackStack() }
                )
            }

            composable(
                route = Routes.QUALITY_ROUTE,
                arguments = listOf(navArgument(Routes.ARG_URL) {
                    type = NavType.StringType; defaultValue = ""
                })
            ) { entry ->
                QualityScreen(
                    url = entry.arguments?.getString(Routes.ARG_URL).orEmpty(),
                    onQueued = {
                        navController.safePopBackStack()
                        navController.navigateToTopLevelTab(Tab.DOWNLOADS)
                    },
                    onBack = { navController.safePopBackStack() },
                    onOpenInBrowser = { targetUrl ->
                        navController.safePopBackStack()
                        navController.navigateSingleTop(Routes.browser(targetUrl))
                    }
                )
            }

            composable(
                route = Routes.PLAYER_ROUTE,
                arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong(Routes.ARG_ID) ?: 0L
                PlayerScreen(downloadId = id, onBack = { navController.safePopBackStack() })
            }
        }
    }
}
