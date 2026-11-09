package com.example.chinlern

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.google.android.material.appbar.MaterialToolbar
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** A local-only viewer. It has no access to the study store or a JavaScript/native bridge. */
class StrokeOrderActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var viewerFailed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val paper = Color.rgb(249, 246, 238)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.addView(MaterialToolbar(this).apply {
            title = "Порядок черт"
            setTitleTextColor(Color.rgb(36, 75, 68))
            setNavigationIcon(R.drawable.ic_stroke_back)
            navigationContentDescription = "Закрыть порядок черт"
            setNavigationOnClickListener { finish() }
            setBackgroundColor(paper)
        }, LinearLayout.LayoutParams(-1, (56 * resources.displayMetrics.density).toInt()))
        setContentView(root)

        val forms = intent.getStringArrayListExtra(FORMS).orEmpty()
        val payload = JSONObject().put("forms", JSONArray(forms))
            .put("pinyin", intent.getStringExtra(PINYIN).orEmpty())
            .put("state", savedInstanceState?.getString(STATE).orEmpty())
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        fun blocked() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        fun failed() {
            if (viewerFailed || isFinishing || isDestroyed) return
            viewerFailed = true
            root.removeView(web)
            web.destroy()
            root.addView(TextView(this).apply {
                text = "Не удалось открыть порядок черт. Закройте экран и попробуйте снова."
                textSize = 18f
                setPadding(24, 24, 24, 24)
                setTextColor(Color.rgb(43, 48, 43))
            })
        }
        web = WebView(this).apply {
            setBackgroundColor(paper)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    val local = uri.scheme == "https" && uri.host == "appassets.androidplatform.net" &&
                        uri.port == -1 && request.method == "GET" &&
                        allowedPath.matches(uri.path.orEmpty())
                    return if (local) loader.shouldInterceptRequest(uri) ?: blocked() else blocked()
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onPageFinished(view: WebView, url: String) {
                    if (url.substringBefore('#') == PAGE && !viewerFailed) {
                        view.evaluateJavascript("window.StrokeViewer && window.StrokeViewer.init($payload)", null)
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) failed()
                }
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    failed()
                    return true
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        web.loadUrl(PAGE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::web.isInitialized && !viewerFailed) outState.putString(STATE, web.url?.substringAfter('#', "").orEmpty())
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        if (::web.isInitialized && !viewerFailed) {
            web.evaluateJavascript("window.StrokeViewer && window.StrokeViewer.pause()", null)
            web.onPause()
        }
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        if (::web.isInitialized && !viewerFailed) web.onResume()
    }
    override fun onDestroy() {
        if (::web.isInitialized && !viewerFailed) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val FORMS = "forms"
        private const val PINYIN = "pinyin"
        private const val STATE = "strokeState"
        private const val PAGE = "https://appassets.androidplatform.net/assets/strokes/index.html"
        private val allowedPath = Regex("/assets/strokes/(index\\.html|viewer\\.js|viewer\\.css|hanzi-writer\\.min\\.js|licenses\\.json|data/[0-9a-f]+\\.json)")

        fun intent(context: Context, forms: List<String>, pinyin: String = "") =
            Intent(context, StrokeOrderActivity::class.java)
                .putStringArrayListExtra(FORMS, ArrayList(forms))
                .putExtra(PINYIN, pinyin)
    }
}
