package com.example.isavee

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.*
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var wv: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var camUri: Uri? = null
    private var permReq: PermissionRequest? = null
    private var out: OutputStream? = null
    private var tmp: File? = null
    private var tmpOut: OutputStream? = null
    private var shText: String? = null
    private val shUris = mutableListOf<Uri>()
    private val sharedNow = mutableListOf<Uri>()

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)); fileCb = null
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        fileCb?.onReceiveValue(if (ok && camUri != null) arrayOf(camUri!!) else null); fileCb = null
    }
    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permReq?.let { if (ok) it.grant(it.resources) else it.deny() }; permReq = null
    }
    private val storePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wv = WebView(this)
        setContentView(wv)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        wv.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false; allowFileAccess = false
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val p = request.url.path ?: ""
                if (p.startsWith("/shared/")) {
                    val u = p.removePrefix("/shared/").toIntOrNull()?.let { sharedNow.getOrNull(it) }
                    if (u != null) return try {
                        WebResourceResponse(contentResolver.getType(u) ?: "application/octet-stream", null, contentResolver.openInputStream(u))
                    } catch (e: Exception) { null }
                }
                return loader.shouldInterceptRequest(request.url)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(r: PermissionRequest) {
                runOnUiThread {
                    if (r.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) r.grant(r.resources)
                        else { permReq = r; micPerm.launch(Manifest.permission.RECORD_AUDIO) }
                    } else r.deny()
                }
            }
            override fun onShowFileChooser(w: WebView?, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null); fileCb = cb
                try {
                    if (p.isCaptureEnabled && p.acceptTypes.any { it.startsWith("image") }) {
                        val f = File.createTempFile("cam", ".jpg", cacheDir)
                        camUri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fp", f)
                        camera.launch(camUri!!)
                    } else picker.launch(p.createIntent())
                } catch (e: Exception) { fileCb = null; return false }
                return true
            }
        }
        takeIntent(intent)
        wv.addJavascriptInterface(Bridge(), "Android")
        wv.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        onBackPressedDispatcher.addCallback(this) {
            wv.evaluateJavascript("(window.goBack&&goBack())||false") { if (it != "true") finish() }
        }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) storePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeIntent(intent)
        wv.evaluateJavascript("window.onShared&&onShared()", null)
    }

    @Suppress("DEPRECATION")
    private fun takeIntent(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_SEND -> {
                shText = i.getStringExtra(Intent.EXTRA_TEXT)
                i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { shUris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                shText = i.getStringExtra(Intent.EXTRA_TEXT)
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { shUris.addAll(it) }
            }
            else -> return
        }
    }

    private fun nameOf(u: Uri): String {
        try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i) }
            }
        } catch (e: Exception) { }
        return u.lastPathSegment ?: "file"
    }

    inner class Bridge {
        @JavascriptInterface
        fun hasShared(): Boolean = shText != null || shUris.isNotEmpty()

        @JavascriptInterface
        fun takeShared(): String {
            val o = JSONObject(); val a = JSONArray()
            sharedNow.clear(); sharedNow.addAll(shUris)
            sharedNow.forEach { u -> a.put(JSONObject().put("name", nameOf(u)).put("type", contentResolver.getType(u) ?: "application/octet-stream")) }
            o.put("text", shText ?: ""); o.put("files", a)
            shText = null; shUris.clear()
            return o.toString()
        }

        @JavascriptInterface
        fun cacheOpen(name: String): Boolean = try {
            val d = File(cacheDir, "share"); d.mkdirs(); d.listFiles()?.forEach { it.delete() }
            tmp = File(d, name.replace(Regex("[\\\\/:*?\"<>|]"), "_")); tmpOut = tmp!!.outputStream(); true
        } catch (e: Exception) { false }

        @JavascriptInterface
        fun cacheWrite(b64: String) { tmpOut?.write(Base64.decode(b64, Base64.DEFAULT)) }

        @JavascriptInterface
        fun cacheShare(mime: String, text: String) {
            tmpOut?.close(); tmpOut = null
            val f = tmp ?: return
            runOnUiThread {
                val u = FileProvider.getUriForFile(this@MainActivity, "$packageName.fp", f)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = mime; putExtra(Intent.EXTRA_STREAM, u)
                    if (text.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, null))
            }
        }

        @JavascriptInterface
        fun open(name: String, mime: String): Boolean = try {
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe); put(MediaStore.Downloads.MIME_TYPE, mime)
                }
                val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                out = contentResolver.openOutputStream(u)
            } else {
                val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                d.mkdirs(); out = File(d, safe).outputStream()
            }
            true
        } catch (e: Exception) { false }

        @JavascriptInterface
        fun write(b64: String) { out?.write(Base64.decode(b64, Base64.DEFAULT)) }

        @JavascriptInterface
        fun close() {
            out?.close(); out = null
            runOnUiThread { Toast.makeText(this@MainActivity, "ذخیره شد در پوشه Download", Toast.LENGTH_LONG).show() }
        }

        @JavascriptInterface
        fun shareText(t: String) = runOnUiThread {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t)
            }, null))
        }
    }
}
