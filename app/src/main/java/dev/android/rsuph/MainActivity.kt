package dev.android.rsuph

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Context.DOWNLOAD_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsIntent.SHARE_STATE_OFF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.android.rsuph.ui.theme.RSUPHTheme
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RSUPHTheme {
                Main()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "QueryPermissionsNeeded")
@Composable
fun Main() {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var inProgress by remember { mutableStateOf(true) }

    fun openInCustomTab(context: Context, url: String) {
        val uri = url.toUri()
        try {
            val builder = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(SHARE_STATE_OFF)
                .setShareIdentityEnabled(true)
                .setInstantAppsEnabled(true)
            val customTabsIntent = builder.build()
            customTabsIntent.launchUrl(context, uri)
        } catch (_: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "Unable to open this link", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun handleExternalUri(uri: Uri): Boolean {
        val urlStr = uri.toString()

        if (uri.host?.contains("rsupermatahati.id") == true) {
            return false
        }

        fun safeStart(intent: Intent): Boolean {
            return try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "Cannot open intent: $urlStr", Toast.LENGTH_SHORT).show()
                openInCustomTab(context, urlStr)
                false
            }
        }

        val scheme = uri.scheme ?: return false

        return try {
            if (scheme == "intent") {
                try {
                    val intent = Intent.parseUri(urlStr, Intent.URI_INTENT_SCHEME)
                    if (safeStart(intent)) return true
                } catch (_: Exception) {
                    Toast.makeText(context, "Cannot open intent: $urlStr", Toast.LENGTH_SHORT).show()
                }
                uri.getQueryParameter("browser_fallback_url")?.let {
                    safeStart(Intent(Intent.ACTION_VIEW, it.toUri()))
                }
                true
            }
            else {
                val intent = when (scheme) {
                    "mailto" -> Intent(Intent.ACTION_SENDTO, uri)
                    "tel" -> Intent(Intent.ACTION_DIAL, uri)
                    "sms" -> Intent(Intent.ACTION_VIEW, uri)
                    "geo" -> Intent(Intent.ACTION_VIEW, uri)
                    else -> null
                }
                if (intent != null && safeStart(intent)) {
                    true
                } else {
                    openInCustomTab(context, urlStr)
                    true
                }
            }
        } catch (_: Exception) {
            Toast.makeText(context, "Cannot open: $urlStr", Toast.LENGTH_SHORT).show()
            openInCustomTab(context, urlStr)
            true
        }
    }

    var pendingPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    var pendingGeoCallback by remember { mutableStateOf<GeolocationPermissions.Callback?>(null) }
    var pendingGeoOrigin by remember { mutableStateOf<String?>(null) }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val granted = results.values.all { it }
        pendingPermissionRequest?.let {
            if (granted) {
                it.grant(it.resources)
            } else {
                it.deny()
            }
            pendingPermissionRequest = null
        }
    }

    val geoPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val granted = results.values.all { it }
        pendingGeoCallback?.let {
            it.invoke(pendingGeoOrigin, granted, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }
    }

    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val resultCode = result.resultCode
        val results = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        fileChooserCallback?.onReceiveValue(results)
        fileChooserCallback = null
    }

    BackHandler(enabled = true) {
        if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            activity.finish()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(Modifier
            .fillMaxSize()
            .padding(innerPadding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    WebView(context).apply {
                        webViewInstance = this

                        // Layout Params
                        layoutParams = ViewGroup.LayoutParams (
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = WebView.OVER_SCROLL_NEVER

                        // Basic Config
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.setSupportMultipleWindows(true)
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.mediaPlaybackRequiresUserGesture = false

                        // Zoom and Viewport
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false

                        // Storage and Cookies
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.allowUniversalAccessFromFileURLs = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.cacheMode = WebSettings.LOAD_DEFAULT

                        settings.userAgentString = settings.userAgentString.replace("; wv", "")

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                inProgress = true
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                inProgress = false
                                webViewInstance = view
                                CookieManager.getInstance().flush()
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest) {
                                val permissions = mutableListOf<String>()
                                request.resources.forEach {
                                    when (it) {
                                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                                            permissions.add(Manifest.permission.CAMERA)
                                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                                            permissions.add(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                                val missing = permissions.filter {
                                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                                }
                                if (missing.isEmpty()) {
                                    request.grant(request.resources)
                                } else {
                                    pendingPermissionRequest = request
                                    mediaPermissionLauncher.launch(missing.toTypedArray())
                                }
                            }

                            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                    callback.invoke(origin, true, false)
                                } else {
                                    pendingGeoOrigin = origin
                                    pendingGeoCallback = callback
                                    geoPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            }

                            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                val tempWebView = WebView(view?.context ?: return false).apply {
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                            val uri = request.url
                                            if (uri.scheme == "http" || uri.scheme == "https") {
                                                openInCustomTab(context, uri.toString())
                                            } else {
                                                handleExternalUri(uri)
                                            }
                                            view.destroy()
                                            return true
                                        }
                                    }
                                }
                                transport.webView = tempWebView
                                resultMsg.sendToTarget()
                                return true
                            }

                            override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                                fileChooserCallback = filePathCallback
                                val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                }
                                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                                    putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                                    putExtra(Intent.EXTRA_TITLE, "Choose file...")
                                }
                                try {
                                    fileChooserLauncher.launch(chooserIntent)
                                } catch (_: Exception) {
                                    fileChooserCallback = null
                                    return false
                                }
                                return true
                            }
                        }

                        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                            when {
                                url.startsWith("blob") -> {
                                    Toast.makeText(context, "Download not supported for blob URLs", Toast.LENGTH_LONG).show()
                                }
                                url.startsWith("http://") || url.startsWith("https://") -> {
                                    try {
                                        val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                                        val request = DownloadManager.Request(url.toUri()).apply {
                                            setMimeType(mimetype)
                                            addRequestHeader("User-Agent", userAgent)
                                            CookieManager.getInstance().getCookie(url)?.let {
                                                addRequestHeader("cookie", it)
                                            }
                                            setTitle(fileName)
                                            setDescription("Downloading file...")
                                            setNotificationVisibility(
                                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                                            )
                                            setDestinationInExternalPublicDir(
                                                Environment.DIRECTORY_DOWNLOADS,
                                                fileName
                                            )
                                        }
                                        val dm = context.getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                                        dm.enqueue(request)
                                        Toast.makeText(context, "Downloading file...", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "Error downloading file: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                else -> {
                                    Toast.makeText(context, "Unsupported download scheme", Toast.LENGTH_LONG).show()
                                    openInCustomTab(context, url)
                                }
                            }
                        }

                        loadUrl("https://online.rsupermatahati.id")
                    }
                },
                update = { }
            )

            if (inProgress) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            }
        }
    }
}