package com.example.tvboxmediamanager

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val pickTree = 1001
    private var treeUri: Uri? = null
    private val videoExt = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "mpg", "mpeg", "ts", "m2ts", "wmv", "flv")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        treeUri = getSharedPreferences("settings", MODE_PRIVATE).getString("tree", null)?.let(Uri::parse)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun chooseFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, pickTree)
    }

    @Deprecated("Legacy result API retained for broad Android TV compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickTree && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            treeUri = uri
            getSharedPreferences("settings", MODE_PRIVATE).edit().putString("tree", uri.toString()).apply()
            web.evaluateJavascript("window.folderChosen && window.folderChosen(${JSONObject.quote(uri.toString())})", null)
        }
    }

    private fun documentId(uri: Uri): String {
    return if (uri.pathSegments.contains("document")) {
        DocumentsContract.getDocumentId(uri)
    } else {
        DocumentsContract.getTreeDocumentId(uri)
    }
}
    private fun children(parent: Uri): List<JSONObject> {
        val tree = treeUri ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        val parentId = documentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                val id = c.getString(idCol)
                val name = c.getString(nameCol) ?: ""
                val mime = c.getString(mimeCol) ?: ""
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                val obj = JSONObject().put("name", name).put("uri", uri.toString()).put("mime", mime).put("size", if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else 0L)
                out.add(obj)
            }
        }
        return out
    }

    private fun scanFolder(): JSONArray {
        val root = treeUri ?: return JSONArray()
        val out = JSONArray()
        val queue = ArrayDeque<Uri>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 10000) {
            val folder = queue.removeFirst()
            for (f in children(folder)) {
                visited++
                val name = f.optString("name")
                val mime = f.optString("mime")
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    queue.add(Uri.parse(f.getString("uri")))
                } else if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) in videoExt) {
                    out.put(f)
                }
                if (visited >= 10000) break
            }
        }
        return out
    }

    private fun request(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "TVBoxMediaManager/0.1")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw Exception("Provider returned HTTP $code: ${body.take(180)}")
            return JSONObject().put("ok", true).put("body", body)
        } finally { conn.disconnect() }
    }

    private fun search(provider: String, query: String, kind: String, apiKey: String): JSONObject {
        val q = URLEncoder.encode(query, "UTF-8")
        val p = provider.lowercase(Locale.ROOT)
        val url = when (p) {
            "tvmaze" -> "https://api.tvmaze.com/search/shows?q=$q"
            "tmdb" -> {
                if (apiKey.isBlank()) return JSONObject().put("ok", false).put("error", "Add your TMDb API key in Settings.")
                val endpoint = if (kind == "tv") "search/tv" else "search/movie"
                "https://api.themoviedb.org/3/$endpoint?api_key=${URLEncoder.encode(apiKey, "UTF-8")}&query=$q&include_adult=false"
            }
            "omdb" -> {
                if (apiKey.isBlank()) return JSONObject().put("ok", false).put("error", "Add your OMDb API key in Settings.")
                "https://www.omdbapi.com/?apikey=${URLEncoder.encode(apiKey, "UTF-8")}&s=$q&type=${if (kind == "tv") "series" else "movie"}"
            }
            else -> return JSONObject().put("ok", false).put("error", "Unknown provider.")
        }
        return try {
            val raw = request(url).getString("body")
            val results = JSONArray()
            when (p) {
                "tmdb" -> {
                    val arr = JSONObject(raw).optJSONArray("results") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val x = arr.getJSONObject(i)
                        val date = x.optString("release_date", x.optString("first_air_date"))
                        val path = x.optString("poster_path")
                        results.put(JSONObject().put("provider", "tmdb").put("id", x.optString("id")).put("title", x.optString("title", x.optString("name"))).put("year", date.take(4)).put("overview", x.optString("overview")).put("poster", if (path.isNotBlank() && path != "null") "https://image.tmdb.org/t/p/w500$path" else "").put("kind", kind))
                    }
                }
                "omdb" -> {
                    val body = JSONObject(raw)
                    if (body.optString("Response") == "False") throw Exception(body.optString("Error", "OMDb search failed"))
                    val arr = body.optJSONArray("Search") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val x = arr.getJSONObject(i); val poster = x.optString("Poster")
                        results.put(JSONObject().put("provider", "omdb").put("id", x.optString("imdbID")).put("title", x.optString("Title")).put("year", x.optString("Year").take(4)).put("overview", "").put("poster", if (poster == "N/A") "" else poster).put("kind", kind))
                    }
                }
                "tvmaze" -> {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val x = arr.getJSONObject(i).optJSONObject("show") ?: continue
                        val image = x.optJSONObject("image")
                        val summary = android.text.Html.fromHtml(x.optString("summary"), android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                        results.put(JSONObject().put("provider", "tvmaze").put("id", x.optString("id")).put("title", x.optString("name")).put("year", x.optString("premiered").take(4)).put("overview", summary).put("poster", image?.optString("original", "") ?: "").put("kind", "tv"))
                    }
                }
            }
            JSONObject().put("ok", true).put("results", results)
        } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "Provider request failed") }
    }

    private fun findParent(target: Uri): Uri? {
        val root = treeUri ?: return null
        val queue = ArrayDeque<Uri>(); queue.add(root)
        var checked = 0
        while (queue.isNotEmpty() && checked < 10000) {
            val parent = queue.removeFirst()
            for (child in children(parent)) {
                checked++
                val uri = Uri.parse(child.getString("uri"))
                if (uri == target) return parent
                if (child.optString("mime") == DocumentsContract.Document.MIME_TYPE_DIR) queue.add(uri)
                if (checked >= 10000) break
            }
        }
        return null
    }

    private fun createFile(parentUri: Uri, name: String, mime: String, bytes: ByteArray): String {
        val tree = treeUri ?: throw Exception("Choose a media folder first")
        val parentId = documentId(parentUri)
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val existing = children(parentDoc).firstOrNull { it.optString("name").equals(name, true) }
        val target = if (existing != null) Uri.parse(existing.getString("uri")) else DocumentsContract.createDocument(contentResolver, parentDoc, mime, name) ?: throw Exception("Could not create $name")
        contentResolver.openOutputStream(target, "wt")!!.use { it.write(bytes) }
        return target.toString()
    }

    private fun saveMetadata(fileUriString: String, metadata: JSONObject): JSONObject {
        return try {
            val fileUri = Uri.parse(fileUriString)
            val parentUri = findParent(fileUri) ?: throw Exception("Could not locate the file's parent folder")
            val base = metadata.optString("title", "Unknown").trim().ifBlank { "Unknown" }
            val nfo = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<movie>
  <title>${xml(base)}</title>
  <year>${xml(metadata.optString("year"))}</year>
  <plot>${xml(metadata.optString("overview"))}</plot>
  <uniqueid type="${xml(metadata.optString("provider"))}" default="true">${xml(metadata.optString("id"))}</uniqueid>
</movie>
""".toByteArray(Charsets.UTF_8)
            val stem = baseName(queryName(fileUri))
            createFile(parentUri, "$stem.nfo", "application/xml", nfo)
            val poster = metadata.optString("poster")
            if (poster.startsWith("https://")) {
                val conn = URL(poster).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000; conn.readTimeout = 15000
                try { conn.inputStream.use { input -> createFile(parentUri, "$stem-poster.jpg", "image/jpeg", input.readBytes()) } } finally { conn.disconnect() }
            }
            JSONObject().put("ok", true).put("message", "Saved $stem.nfo and poster if available.")
        } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "Save failed") }
    }

    private fun queryName(uri: Uri): String = contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "video" } ?: "video"
    private fun baseName(name: String) = name.substringBeforeLast('.', name).replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "video" }
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun renameFile(uriString: String, newName: String): JSONObject = try {
        if (!newName.matches(Regex("[A-Za-z0-9 ._()\\-]+")) || newName.startsWith(".")) throw Exception("Use only letters, numbers, spaces, dots, brackets, underscores or hyphens.")
        val uri = Uri.parse(uriString)
        val oldName = queryName(uri)
        val ext = oldName.substringAfterLast('.', "")
        val safeName = if (ext.isNotBlank() && !newName.endsWith(".$ext", true)) "$newName.$ext" else newName
        val renamed = DocumentsContract.renameDocument(contentResolver, uri, safeName) ?: throw Exception("This USB storage provider does not support renaming.")
        JSONObject().put("ok", true).put("uri", renamed.toString()).put("message", "Renamed to $safeName")
    } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "Rename failed") }

    inner class Bridge {
        @JavascriptInterface fun chooseFolder() { runOnUiThread { this@MainActivity.chooseFolder() } }
        @JavascriptInterface fun folderStatus(): String = JSONObject().put("selected", treeUri != null).put("uri", treeUri?.toString() ?: "").toString()
        @JavascriptInterface fun scan(): String = try { JSONObject().put("ok", true).put("files", scanFolder()).toString() } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "Scan failed").toString() }
        @JavascriptInterface fun search(provider: String, query: String, kind: String, apiKey: String): String = this@MainActivity.search(provider, query, kind, apiKey).toString()
        @JavascriptInterface fun save(fileUri: String, metadataJson: String): String = saveMetadata(fileUri, JSONObject(metadataJson)).toString()
        @JavascriptInterface fun rename(fileUri: String, newName: String): String = renameFile(fileUri, newName).toString()
    }
}
