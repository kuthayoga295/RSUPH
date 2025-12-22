package dev.rsuph.id

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.rsuph.id.ui.theme.RSUPHTheme

class MainActivity : ComponentActivity() {
    private val appPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private fun hasAllPermissions(): Boolean {
        return appPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result -> }

        if (!hasAllPermissions()) {
            permissionLauncher.launch(appPermissions)
        }

        super.onCreate(savedInstanceState)

        setContent {
            RSUPHTheme {
                Scaffold(modifier = Modifier.fillMaxSize().safeDrawingPadding()) { innerPadding ->
                    WebViewWrapper(
                        targetUrl = "https://online.rsupermatahati.id",
                        hostUri = "rsupermatahati.id",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewWrapper(targetUrl: String, hostUri: String, modifier: Modifier) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    var customView: View? by remember { mutableStateOf(null) }
    var customViewCallback: WebChromeClient.CustomViewCallback? by remember { mutableStateOf(null) }
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }

    val customViewContainer = remember {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
    }

    fun createCameraIntent(): Intent {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val photoUri = activity.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues()
        )
        cameraImageUri = photoUri
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        return cameraIntent
    }

    fun createVideoIntent(): Intent {
        return Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_DURATION_LIMIT, 60)
            putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
        }
    }

    fun createGalleryIntent(): Intent {
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()
            if (data?.clipData != null) {
                val count = data.clipData!!.itemCount
                for (i in 0 until count) {
                    uris.add(data.clipData!!.getItemAt(i).uri)
                }
            } else if (data?.data != null) {
                uris.add(data.data!!)
            } else if (cameraImageUri != null) {
                uris.add(cameraImageUri!!)
            }

            if (uris.isNotEmpty()) {
                callback.onReceiveValue(uris.toTypedArray())
            } else {
                callback.onReceiveValue(null)
            }
        } else {
            callback.onReceiveValue(null)
        }
        filePathCallback = null
        cameraImageUri = null
    }

    fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                1234
            )
        }
    }

    fun hideSystemBars() {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    fun showSystemBars() {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    val webView = remember {
        WebView(context).apply {

            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadsImagesAutomatically = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                javaScriptCanOpenWindowsAutomatically = true
                allowFileAccess = true
                allowContentAccess = true
                setSupportMultipleWindows(true)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
                setGeolocationEnabled(true)
                blockNetworkLoads = false
                userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    val uri = Uri.parse(url)
                    if (uri.host == hostUri) {
                        return false
                    }
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        return false
                    }
                    if (url.startsWith("intent://")) {
                        return try {
                            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                            activity.startActivity(intent)
                            true
                        } catch (e: Exception) {
                            try {
                                val packageName =
                                    Intent.parseUri(url, Intent.URI_INTENT_SCHEME).`package`
                                if (!packageName.isNullOrEmpty()) {
                                    activity.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            "market://details?id=$packageName".toUri()
                                        )
                                    )
                                    true
                                } else false
                            } catch (_: Exception) {
                                false
                            }
                        }
                    }
                    return try {
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        activity.startActivity(intent)
                        true
                    } catch (_: ActivityNotFoundException) {
                        false
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val newWebView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                    }
                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    transport?.webView = newWebView
                    resultMsg?.sendToTarget()
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallBack: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    filePathCallback = filePathCallBack
                    val hasCameraPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    val cameraIntent = if (hasCameraPermission) createCameraIntent() else null
                    val videoIntent = if (hasCameraPermission) createVideoIntent() else null
                    val galleryIntent = createGalleryIntent()
                    val intentList = mutableListOf<Intent>()
                    cameraIntent?.let { intentList.add(it) }
                    videoIntent?.let { intentList.add(it) }
                    val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, galleryIntent)
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, intentList.toTypedArray())
                    }
                    fileChooserLauncher.launch(chooser)
                    return true
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    requestLocationPermission()
                    callback?.invoke(origin, true, false)
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (customView != null) {
                        onHideCustomView()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    customViewContainer.addView(
                        customView,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    customViewContainer.visibility = View.VISIBLE
                    hideSystemBars()
                }

                override fun onHideCustomView() {
                    customViewContainer.visibility = View.GONE
                    customView?.let { customViewContainer.removeView(it) }
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    showSystemBars()
                }
            }

            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                try {
                    val request = DownloadManager.Request(url.toUri())
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    request.setMimeType(mimeType)
                    request.addRequestHeader("User-Agent", userAgent)
                    request.setDescription("Downloading file…")
                    request.setTitle(fileName)
                    request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    request.setAllowedOverMetered(true)
                    request.setAllowedOverRoaming(true)
                    request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )
                    val dm = context.getSystemService(DownloadManager::class.java)
                    dm.enqueue(request)
                    Toast.makeText(context, "Downloading...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, url.toUri())
                        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(browserIntent)
                        Toast.makeText(context, "Open in external browser…", Toast.LENGTH_SHORT).show()
                    } catch (ex: Exception) {
                        Toast.makeText(context, "Download failed!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            FrameLayout(context).apply {
                addView(
                    webView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                addView(
                    customViewContainer,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        },
        update = { webView.loadUrl(targetUrl) }
    )

    DisposableEffect(Unit) {
        onDispose {
            try {
                webView.apply {
                    loadUrl("about:blank")
                    stopLoading()
                    clearHistory()
                    removeAllViews()
                    destroy()
                }
            } catch (e: Exception) {
            }
        }
    }

    BackHandler(enabled = true) {
        if (webView.canGoBack()) webView.goBack()
        else activity.finish()
    }
}
