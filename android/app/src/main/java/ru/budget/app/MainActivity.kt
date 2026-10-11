package ru.budget.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REPO = "shoosman/budget"
        private const val VERSION_URL = "https://github.com/$REPO/releases/download/latest/version.json"
        private const val APK_URL = "https://github.com/$REPO/releases/download/latest/budget.apk"
    }

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    @Volatile private var downloading = false
    private var pendingInstall: File? = null

    // выбор файла для «Загрузить копию»
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    // голосовой ввод: системное окно распознавания речи (Google), результат — на страницу
    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val list = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (res.resultCode == Activity.RESULT_OK && !list.isNullOrEmpty()) {
            js("onVoiceResult", JSONObject().put("text", list[0]))
        } else {
            js("onVoiceResult", JSONObject().put("cancelled", true))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Рисуем от края до края и сами отступаем под статус-бар, навигацию и клавиатуру —
        // одинаково на всех версиях Android (на Android 15 иначе страница уезжает под панели).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFFF4F5F7.toInt()) }
        web = WebView(this)
        web.setBackgroundColor(0xFFF4F5F7.toInt())
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        // «Назад»: сначала закрываем шторку/окно/панель на странице, потом уже выходим
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.androidBack && window.androidBack()) ? 'y' : 'n'") { r ->
                    if (r?.contains("y") == true) return@evaluateJavascript
                    if (web.canGoBack()) { web.goBack(); return@evaluateJavascript }
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

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
            // крупный системный шрифт Samsung не должен ломать вёрстку
            textZoom = 100
        }
        // тёмная тема Android не перекрашивает страницу автоматически
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, false)
        }
        web.overScrollMode = WebView.OVER_SCROLL_NEVER

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

    /* ---------- вызов JS-колбэка на странице ---------- */
    private fun js(fn: String, payload: JSONObject) {
        val code = "window.$fn && window.$fn(${payload})"
        runOnUiThread { web.evaluateJavascript(code, null) }
    }

    private fun currentVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun currentVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""

    private fun httpGet(url: String): HttpURLConnection {
        var u = URL(url)
        // GitHub отдаёт файлы релиза через редирект на другой домен — проходим вручную
        repeat(5) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "budget-android")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("redirect without location")
                c.disconnect()
                u = URL(u, loc)
            } else {
                if (code != 200) throw IllegalStateException("HTTP $code")
                return c
            }
        }
        throw IllegalStateException("too many redirects")
    }

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            // первый раз Android попросит разрешить приложению ставить обновления
            pendingInstall = file
            js("onAppUpdateState", JSONObject().put("state", "need-permission"))
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        pendingInstall = null
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        js("onAppUpdateState", JSONObject().put("state", "installer-opened"))
    }

    override fun onResume() {
        super.onResume()
        // вернулись из настроек после выдачи разрешения — продолжаем установку
        val f = pendingInstall
        if (f != null && f.exists() && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) {
            installApk(f)
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

        @JavascriptInterface
        fun startVoice() {
            runOnUiThread {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Например: потратил 500 на такси")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                try {
                    voiceLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    js("onVoiceResult", JSONObject().put("error", "no-recognizer"))
                }
            }
        }

        @JavascriptInterface
        fun getVersion(): String =
            JSONObject().put("code", currentVersionCode()).put("name", currentVersionName()).toString()

        // проверка: есть ли в Releases сборка новее установленной → window.onAppUpdate({...})
        @JavascriptInterface
        fun checkUpdate() {
            thread {
                val res = JSONObject().put("current", currentVersionCode()).put("currentName", currentVersionName())
                try {
                    val c = httpGet(VERSION_URL + "?t=" + System.currentTimeMillis())
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    val remote = JSONObject(body)
                    val latest = remote.optLong("versionCode", 0)
                    res.put("latest", latest)
                        .put("latestName", remote.optString("versionName", latest.toString()))
                        .put("available", latest > currentVersionCode())
                } catch (e: Throwable) {
                    res.put("error", e.message ?: "network")
                }
                js("onAppUpdate", res)
            }
        }

        // скачать APK и открыть установщик; прогресс → window.onAppUpdateState({state:'progress', pct})
        @JavascriptInterface
        fun installUpdate() {
            if (downloading) return
            downloading = true
            thread {
                try {
                    val dir = File(cacheDir, "updates").apply { mkdirs() }
                    val out = File(dir, "budget.apk")
                    val c = httpGet(APK_URL + "?t=" + System.currentTimeMillis())
                    val total = c.contentLengthLong
                    var done = 0L
                    var lastPct = -1
                    c.inputStream.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        js("onAppUpdateState", JSONObject().put("state", "progress").put("pct", pct))
                                    }
                                }
                            }
                        }
                    }
                    c.disconnect()
                    runOnUiThread { installApk(out) }
                } catch (e: Throwable) {
                    js("onAppUpdateState", JSONObject().put("state", "error").put("message", e.message ?: "network"))
                } finally {
                    downloading = false
                }
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

}
