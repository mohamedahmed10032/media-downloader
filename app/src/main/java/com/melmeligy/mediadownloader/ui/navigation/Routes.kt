package com.melmeligy.mediadownloader.ui.navigation

import android.net.Uri

/** Central definition of navigation routes and argument keys. */
object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val DOWNLOADS = "downloads"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    const val ARG_URL = "url"
    const val ARG_ID = "id"

    const val BROWSER_ROUTE = "browser?url={url}"
    const val QUALITY_ROUTE = "quality?url={url}"
    const val PLAYER_ROUTE = "player/{id}"

    fun browser(url: String?): String = "browser?url=${Uri.encode(url ?: "")}"
    fun quality(url: String): String = "quality?url=${Uri.encode(url)}"
    fun player(id: Long): String = "player/$id"
}
