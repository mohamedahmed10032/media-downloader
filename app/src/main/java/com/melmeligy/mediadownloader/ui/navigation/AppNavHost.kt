package com.melmeligy.mediadownloader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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

@Composable
fun AppNavHost(
    sharedUrl: String?,
    onSharedUrlConsumed: () -> Unit
) {
    val navController = rememberNavController()

    // A link shared into the app jumps straight to the quality/format screen.
    LaunchedEffect(sharedUrl) {
        val url = sharedUrl?.trim()
        if (!url.isNullOrEmpty()) {
            navController.navigate(Routes.quality(url))
            onSharedUrlConsumed()
        }
    }

    NavHost(navController = navController, startDestination = Routes.SPLASH) {

        composable(Routes.SPLASH) {
            SplashScreen(onFinished = {
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.SPLASH) { inclusive = true }
                }
            })
        }

        composable(Routes.HOME) {
            MainScaffold(navController, Tab.HOME, stringResource(R.string.home_title)) { padding ->
                HomeScreen(
                    contentPadding = padding,
                    onOpenBrowser = { url -> navController.navigate(Routes.browser(url)) },
                    onDetect = { url -> navController.navigate(Routes.quality(url)) }
                )
            }
        }

        composable(Routes.DOWNLOADS) {
            MainScaffold(navController, Tab.DOWNLOADS, stringResource(R.string.downloads_title)) { padding ->
                DownloadsScreen(contentPadding = padding)
            }
        }

        composable(Routes.LIBRARY) {
            MainScaffold(navController, Tab.LIBRARY, stringResource(R.string.library_title)) { padding ->
                LibraryScreen(
                    contentPadding = padding,
                    onPlay = { id -> navController.navigate(Routes.player(id)) }
                )
            }
        }

        composable(Routes.SETTINGS) {
            MainScaffold(navController, Tab.SETTINGS, stringResource(R.string.settings_title)) { padding ->
                SettingsScreen(
                    contentPadding = padding,
                    onOpenAbout = { navController.navigate(Routes.ABOUT) }
                )
            }
        }

        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.BROWSER_ROUTE,
            arguments = listOf(navArgument(Routes.ARG_URL) {
                type = NavType.StringType; defaultValue = ""
            })
        ) { entry ->
            // Navigation already URL-decodes query arguments, so use the value as-is.
            val url = entry.arguments?.getString(Routes.ARG_URL).orEmpty().ifBlank { null }
            BrowserScreen(
                initialUrl = url,
                onDownload = { detected -> navController.navigate(Routes.quality(detected)) },
                onBack = { navController.popBackStack() }
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
                    navController.navigate(Routes.DOWNLOADS) {
                        popUpTo(Routes.HOME)
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.PLAYER_ROUTE,
            arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong(Routes.ARG_ID) ?: 0L
            PlayerScreen(downloadId = id, onBack = { navController.popBackStack() })
        }
    }
}
