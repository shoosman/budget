package ru.budget.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    // выбор файла для «Загрузить копию»
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        // Приложение отдаётся с https://appassets.androidplatform.net/ — у него настоящий origin,
        // поэтому localStorage, fetch к GitHub API и прочее работают как на обычном сайте.
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            // ссылки на внешние сайты (github.com из инструкции) — открываем в браузере
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == "appassets.androidplatform.net") return false
                startActivity(Intent(Intent.ACTION_VIEW, url))
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                pickFile.launch("*/*")
                return true
            }
        }

        web.addJavascriptInterface(AndroidBridge(), "Android")

        if (savedInstanceState == null) {
            web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    inner class AndroidBridge {
        // «Скачать резервную копию»: blob-ссылки в WebView не работают, поэтому страница
        // отдаёт JSON сюда, а мы кладём его в папку «Загрузки».
        @JavascriptInterface
        fun saveFile(name: String, content: String) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("no uri")
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                runOnUiThread { Toast.makeText(this@MainActivity, "Сохранено в Загрузки: $name", Toast.LENGTH_LONG).show() }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Не удалось сохранить файл", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        // даём странице шанс мгновенно отправить несохранённое в облако
        web.evaluateJavascript("document.dispatchEvent(new Event('visibilitychange'))", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
