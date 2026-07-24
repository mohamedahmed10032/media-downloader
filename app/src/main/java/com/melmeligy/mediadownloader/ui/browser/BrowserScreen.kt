package com.melmeligy.mediadownloader.ui.browser

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.core.util.UrlUtils

private const val DETECT_JS =
    "(function(){try{var n=document.querySelectorAll('video,audio,source').length;" +
        "var h=document.documentElement?document.documentElement.innerHTML:'';" +
        "var m=/\\.(mp4|m3u8|m4a|webm|mp3|mov)([\\?\"'&]|$)/i.test(h);" +
        "return (n>0||m)?'1':'0';}catch(e){return '0';}})();"

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    initialUrl: String?,
    onDownload: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: BrowserViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()

    var addressText by remember { mutableStateOf(initialUrl ?: Constants.DEFAULT_HOME_URL) }
    var currentUrl by remember { mutableStateOf(initialUrl ?: Constants.DEFAULT_HOME_URL) }
    var pageTitle by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var hasMedia by remember { mutableStateOf(false) }
    var detectedDownloadUrl by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var initialLoaded by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // Only handle standard web pages inside the WebView; ignore custom schemes.
                    return !(url.startsWith("http://") || url.startsWith("https://"))
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    hasMedia = false
                    detectedDownloadUrl = null
                    if (url != null) {
                        currentUrl = url
                        addressText = url
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (url != null) {
                        currentUrl = url
                        addressText = url
                        viewModel.updateActiveTab(url, view.title.orEmpty())
                    }
                    canGoBack = view.canGoBack()
                    canGoForward = view.canGoForward()
                    view.evaluateJavascript(DETECT_JS) { result ->
                        hasMedia = hasMedia || (result?.contains("1") == true)
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress / 100f
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    pageTitle = title.orEmpty()
                }
            }

            setDownloadListener { url, _, _, _, _ ->
                detectedDownloadUrl = url
                hasMedia = true
            }
        }
    }

    // Load the active tab's URL on entry and whenever the tab is switched.
    androidx.compose.runtime.LaunchedEffect(activeTabId) {
        val target = if (!initialLoaded && initialUrl != null) {
            UrlUtils.toBrowserUrl(initialUrl)
        } else {
            viewModel.activeTab()?.url ?: Constants.DEFAULT_HOME_URL
        }
        initialLoaded = true
        webView.loadUrl(target)
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    BackHandler(enabled = canGoBack) { webView.goBack() }

    fun submitAddress() {
        focusManager.clearFocus()
        webView.loadUrl(UrlUtils.toBrowserUrl(addressText))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close browser")
                    }
                },
                title = {
                    OutlinedTextField(
                        value = addressText,
                        onValueChange = { addressText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Search or type a URL") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submitAddress() })
                    )
                },
                actions = {
                    IconButton(onClick = { webView.reload() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                IconButton(onClick = { if (canGoBack) webView.goBack() }, enabled = canGoBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                IconButton(onClick = { if (canGoForward) webView.goForward() }, enabled = canGoForward) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Forward")
                }
                IconButton(onClick = { webView.loadUrl(Constants.DEFAULT_HOME_URL) }) {
                    Icon(Icons.Rounded.Home, contentDescription = "Home")
                }
                IconButton(onClick = { showBookmarks = true }) {
                    Icon(Icons.Rounded.Bookmarks, contentDescription = "Bookmarks")
                }
                IconButton(onClick = { showTabs = true }) {
                    Icon(Icons.Rounded.Tab, contentDescription = "Tabs (${tabs.size})")
                }
            }
        },
        floatingActionButton = {
            if (hasMedia) {
                ExtendedFloatingActionButton(
                    onClick = { onDownload(detectedDownloadUrl ?: currentUrl) },
                    icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                    text = { Text("Download") }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (progress > 0f && progress < 1f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showTabs) {
        ModalBottomSheet(onDismissRequest = { showTabs = false }) {
            TabsSheet(
                tabs = tabs,
                activeTabId = activeTabId,
                onSelect = { id -> viewModel.switchTab(id); showTabs = false },
                onClose = { id -> viewModel.closeTab(id) },
                onNewTab = { viewModel.newTab(); showTabs = false }
            )
        }
    }

    if (showBookmarks) {
        ModalBottomSheet(onDismissRequest = { showBookmarks = false }) {
            BookmarksSheet(
                bookmarks = bookmarks,
                onOpen = { url -> webView.loadUrl(url); showBookmarks = false },
                onDelete = { id -> viewModel.deleteBookmark(id) },
                onAddCurrent = {
                    viewModel.addBookmark(pageTitle.ifBlank { currentUrl }, currentUrl)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsSheet(
    tabs: List<BrowserTab>,
    activeTabId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNewTab: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        TextButton(onClick = onNewTab, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(Icons.Rounded.Tab, contentDescription = null)
            Text("  New tab", modifier = Modifier.padding(start = 4.dp))
        }
        tabs.forEach { tab ->
            ListItem(
                headlineContent = {
                    Text(
                        tab.title.ifBlank { tab.url },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (tab.id == activeTabId) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                },
                supportingContent = {
                    Text(tab.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = { Icon(Icons.Rounded.Tab, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { onClose(tab.id) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close tab")
                    }
                },
                modifier = Modifier.clickableRow { onSelect(tab.id) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(
    bookmarks: List<com.melmeligy.mediadownloader.domain.model.Bookmark>,
    onOpen: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onAddCurrent: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        TextButton(onClick = onAddCurrent, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(Icons.Rounded.Bookmarks, contentDescription = null)
            Text("  Add current page", modifier = Modifier.padding(start = 4.dp))
        }
        if (bookmarks.isEmpty()) {
            Text(
                "No bookmarks yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        bookmarks.forEach { bookmark ->
            ListItem(
                headlineContent = {
                    Text(bookmark.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(bookmark.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = { Icon(Icons.Rounded.Bookmarks, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { onDelete(bookmark.id) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Delete bookmark")
                    }
                },
                modifier = Modifier.clickableRow { onOpen(bookmark.url) }
            )
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
