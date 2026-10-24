package com.example.tvboxmediamanager

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Html
import android.util.Xml
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

private const val USER_AGENT = "TVBoxMediaManager/0.3"
private const val NFO_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "mpg", "mpeg", "ts", "m2ts", "wmv", "flv", "iso")
private val DISC_DIRS = setOf("BDMV", "VIDEO_TS")
private val SCALAR_FIELDS = setOf("title", "originaltitle", "showtitle", "year", "plot", "outline", "runtime", "rating", "season", "episode", "aired", "premiered")
private val SEASON_FOLDER = Regex("(?i)(season|s)[ ._-]?\\d{1,3}")
private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

// ---------- JSON helpers (missing or null values become "" / 0) ----------
private fun JSONObject.s(key: String): String = if (isNull(key)) "" else optString(key, "").trim()
private fun JSONObject.d(key: String): Double = if (isNull(key)) 0.0 else optDouble(key, 0.0)
private fun JSONObject.i(key: String): Int = if (isNull(key)) 0 else optInt(key, 0)
private fun JSONObject.arr(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

private fun failure(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)
private fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
private fun stemOf(name: String): String = if ('.' in name) name.substringBeforeLast('.') else name
private fun htmlText(s: String): String = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim()
private fun tmdbImage(path: String, size: String): String =
    if (path.isBlank() || path == "null") "" else "https://image.tmdb.org/t/p/$size$path"
private fun na(s: String): String = if (s == "N/A") "" else s
private fun ratingOf(r: Double): String = if (r > 0) String.format(Locale.ROOT, "%.1f", r) else ""
private fun runtimeOf(r: Int): String = if (r > 0) r.toString() else ""

private fun airDate(raw: String): String {
    if (ISO_DATE.matches(raw)) return raw
    return try {
        val parsed = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).parse(raw)
        if (parsed == null) "" else SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(parsed)
    } catch (e: Exception) {
        ""
    }
}

private fun validateName(name: String) {
    if (name.isBlank() || name.startsWith(".") || name.length > 200) throw Exception("Enter a valid file name.")
    if (name.any { it in "/\\:*?\"<>|" || it.code < 32 }) {
        throw Exception("File names cannot contain / \\ : * ? \" < > | or control characters.")
    }
}

private fun item(provider: String, id: String, title: String, year: String, overview: String, poster: String, kind: String): JSONObject =
    JSONObject().put("provider", provider).put("id", id).put("title", title).put("year", year)
        .put("overview", overview).put("poster", poster).put("kind", kind)

private fun castList(pairs: List<Pair<String, String>>): JSONArray {
    val a = JSONArray()
    pairs.take(15).forEach { (name, role) ->
        if (name.isNotBlank()) a.put(JSONObject().put("name", name).put("role", role))
    }
    return a
}

// ---------- NFO builders (Kodi format) ----------
private fun line(tag: String, value: String): String =
    if (value.isBlank()) "" else "  <$tag>${xml(value)}</$tag>\n"

private fun genresXml(m: JSONObject): String {
    val g = m.arr("genres")
    val sb = StringBuilder()
    for (i in 0 until g.length()) sb.append(line("genre", g.optString(i)))
    return sb.toString()
}

private fun castXml(m: JSONObject): String {
    val c = m.arr("cast")
    val sb = StringBuilder()
    for (i in 0 until c.length()) {
        val p = c.optJSONObject(i) ?: continue
        sb.append("  <actor>\n    <name>${xml(p.s("name"))}</name>\n    <role>${xml(p.s("role"))}</role>\n    <order>$i</order>\n  </actor>\n")
    }
    return sb.toString()
}

private fun uniqueIdsXml(m: JSONObject): String {
    val ids = m.optJSONObject("ids") ?: return ""
    val provider = m.s("provider")
    val sb = StringBuilder()
    val keys = ids.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val v = ids.s(k)
        if (v.isNotBlank()) sb.append("  <uniqueid type=\"$k\" default=\"${k == provider}\">${xml(v)}</uniqueid>\n")
    }
    return sb.toString()
}

private fun setXml(m: JSONObject): String =
    if (m.s("set").isBlank()) "" else "  <set>\n    <name>${xml(m.s("set"))}</name>\n  </set>\n"

private fun movieNfo(m: JSONObject): ByteArray = (NFO_HEADER + "<movie>\n" +
    line("title", m.s("title")) + line("originaltitle", m.s("originalTitle")) + line("year", m.s("year")) +
    line("plot", m.s("overview")) + line("runtime", runtimeOf(m.i("runtime"))) + line("rating", ratingOf(m.d("rating"))) +
    genresXml(m) + uniqueIdsXml(m) + castXml(m) + setXml(m) + "</movie>\n").toByteArray(Charsets.UTF_8)

private fun showNfo(s: JSONObject): ByteArray = (NFO_HEADER + "<tvshow>\n" +
    line("title", s.s("title")) + line("originaltitle", s.s("originalTitle")) + line("year", s.s("year")) +
    line("plot", s.s("overview")) + line("runtime", runtimeOf(s.i("runtime"))) + line("rating", ratingOf(s.d("rating"))) +
    genresXml(s) + uniqueIdsXml(s) + castXml(s) + "</tvshow>\n").toByteArray(Charsets.UTF_8)

private fun episodeNfo(show: JSONObject, eps: List<JSONObject>): ByteArray {
    val sb = StringBuilder(NFO_HEADER)
    for (ep in eps) {
        sb.append("<episodedetails>\n")
        sb.append(line("title", ep.s("title")))
        sb.append(line("showtitle", show.s("title")))
        sb.append(line("season", ep.i("season").toString()))
        sb.append(line("episode", ep.i("number").toString()))
        sb.append(line("plot", ep.s("overview")))
        sb.append(line("aired", airDate(ep.s("aired"))))
        sb.append(line("runtime", runtimeOf(ep.i("runtime"))))
        sb.append(line("rating", ratingOf(ep.d("rating"))))
        sb.append("</episodedetails>\n")
    }
    return sb.toString().toByteArray(Charsets.UTF_8)
}

// ---------- Undo log: every change is recorded; overwritten files keep a backup ----------
object UndoLog {
    private const val MAX_ENTRIES = 300
    private var dir: File? = null

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "undo").apply { mkdirs() }
    }

    private fun logFile(): File = File(dir!!, "log.json")

    @Synchronized
    fun entries(): JSONArray = try {
        JSONArray(logFile().readText())
    } catch (e: Exception) {
        JSONArray()
    }

    fun toList(arr: JSONArray): List<JSONObject> = arr.objects()

    @Synchronized
    fun push(entry: JSONObject) {
        val all = toList(entries()).toMutableList()
        all.add(entry)
        while (all.size > MAX_ENTRIES) backupFile(all.removeAt(0))?.delete()
        save(all)
    }

    @Synchronized
    fun removeBatch(batch: String) {
        val all = toList(entries())
        val gone = all.filter { it.s("batch") == batch }
        save(all.filter { it.s("batch") != batch })
        gone.forEach { backupFile(it)?.delete() }
    }

    fun newBackup(bytes: ByteArray): String {
        val name = UUID.randomUUID().toString() + ".bin"
        File(dir!!, name).writeBytes(bytes)
        return name
    }

    fun backupFile(entry: JSONObject): File? =
        entry.s("backup").takeIf { it.isNotBlank() }?.let { File(dir!!, it) }

    private fun save(list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        logFile().writeText(arr.toString())
    }
}

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val pickTree = 1001
    private var treeUri: Uri? = null
    @Volatile private var batchId: String? = null
    @Volatile private var batchLabel: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UndoLog.init(this)
        treeUri = getSharedPreferences("settings", MODE_PRIVATE).getString("tree", null)?.let(Uri::parse)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        // WebView's default confirm()/prompt() return false silently, so the app's
        // confirmations never appeared. These handlers show real native dialogs.
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
                val input = EditText(this@MainActivity)
                input.setText(defaultValue ?: "")
                input.setSingleLine(true)
                AlertDialog.Builder(this@MainActivity).setMessage(message).setView(input)
                    .setPositiveButton("OK") { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }
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

    // ---------- SAF helpers ----------
    private fun documentId(uri: Uri): String =
        if (uri.pathSegments.contains("document")) DocumentsContract.getDocumentId(uri)
        else DocumentsContract.getTreeDocumentId(uri)

    private fun queryName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "video"

    private fun children(parent: Uri): List<JSONObject> {
        val tree = treeUri ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        val parentId = documentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
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
                out.add(
                    JSONObject().put("name", name).put("uri", uri.toString()).put("mime", mime)
                        .put("size", if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else 0L)
                )
            }
        }
        return out
    }

    private fun scanFolder(): JSONArray {
        val root = treeUri ?: return JSONArray()
        val out = JSONArray()
        val queue = ArrayDeque<Pair<Uri, String>>()
        queue.add(root to "")
        var visited = 0
        while (queue.isNotEmpty() && visited < 10000) {
            val (folder, dir) = queue.removeFirst()
            val kids = children(folder)
            visited += kids.size
            val isDisc = dir.isNotEmpty() && kids.any {
                it.s("mime") == DocumentsContract.Document.MIME_TYPE_DIR && it.s("name").uppercase(Locale.ROOT) in DISC_DIRS
            }
            if (isDisc) {
                // A Blu-ray or DVD folder is one movie. Its streams are not listed, and it is not descended into.
                val entry = JSONObject().put("name", dir.substringAfterLast('/')).put("uri", folder.toString())
                    .put("mime", DocumentsContract.Document.MIME_TYPE_DIR).put("size", 0L)
                    .put("dir", dir.substringBeforeLast('/', "")).put("disc", true)
                kids.firstOrNull { it.s("name").equals("movie.nfo", ignoreCase = true) }?.let { entry.put("nfo", it.s("uri")) }
                out.put(entry)
                continue
            }
            val nfoByName = kids.filter { it.s("name").endsWith(".nfo", ignoreCase = true) }
                .associate { it.s("name").lowercase(Locale.ROOT) to it.s("uri") }
            for (f in kids) {
                val name = f.s("name")
                if (f.s("mime") == DocumentsContract.Document.MIME_TYPE_DIR) {
                    queue.add(Uri.parse(f.s("uri")) to (if (dir.isEmpty()) name else "$dir/$name"))
                } else if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) in VIDEO_EXT) {
                    f.put("dir", dir)
                    nfoByName[stemOf(name).lowercase(Locale.ROOT) + ".nfo"]?.let { f.put("nfo", it) }
                    out.put(f)
                }
            }
        }
        return out
    }

    /** Breadth-first search fallback for providers whose document IDs are opaque. */
    private fun findParent(target: Uri): Uri? {
        val root = treeUri ?: return null
        val queue = ArrayDeque<Uri>()
        queue.add(root)
        var checked = 0
        while (queue.isNotEmpty() && checked < 10000) {
            val parent = queue.removeFirst()
            for (child in children(parent)) {
                checked++
                val uri = Uri.parse(child.s("uri"))
                if (uri == target) return parent
                if (child.s("mime") == DocumentsContract.Document.MIME_TYPE_DIR) queue.add(uri)
                if (checked >= 10000) break
            }
        }
        return null
    }

    /** The folder that contains a file or folder. Fast path uses the document ID; falls back to a search. */
    private fun parentOf(uri: Uri): Uri {
        val tree = treeUri ?: throw Exception("Choose a media folder first")
        val name = queryName(uri)
        val docId = DocumentsContract.getDocumentId(uri)
        if (docId.contains(':')) {
            val parentId = if (docId.contains('/')) docId.substringBeforeLast('/') else docId.substringBefore(':') + ":"
            val candidate = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            if (children(candidate).any { it.s("name") == name }) return candidate
        }
        return findParent(uri) ?: throw Exception("Could not locate the folder containing $name")
    }

    /** For an episode file, the show folder is the parent, or the grandparent if the parent is a "Season N" folder. */
    private fun showFolderOf(fileUri: Uri): Uri {
        val folder = parentOf(fileUri)
        val name = queryName(folder)
        return if (SEASON_FOLDER.matches(name)) parentOf(folder) else folder
    }

    /**
     * Writes a file into a folder, replacing any same-named file. Overwrites keep a backup
     * of the previous bytes so the change can be undone.
     */
    private fun writeFile(folder: Uri, name: String, mime: String, bytes: ByteArray): String {
        val tree = treeUri ?: throw Exception("Choose a media folder first")
        val folderDoc = DocumentsContract.buildDocumentUriUsingTree(tree, documentId(folder))
        val existing = children(folderDoc).firstOrNull { it.s("name").equals(name, ignoreCase = true) }
        if (existing != null) {
            val target = Uri.parse(existing.s("uri"))
            val previous = contentResolver.openInputStream(target)?.use { it.readBytes() }
            val backup = previous?.let { UndoLog.newBackup(it) } ?: ""
            contentResolver.openOutputStream(target, "wt")!!.use { it.write(bytes) }
            record(JSONObject().put("type", "overwrite").put("uri", target.toString()).put("name", name).put("backup", backup))
            return target.toString()
        }
        val target = DocumentsContract.createDocument(contentResolver, folderDoc, mime, name)
            ?: throw Exception("Could not create $name")
        contentResolver.openOutputStream(target, "wt")!!.use { it.write(bytes) }
        record(JSONObject().put("type", "create").put("uri", target.toString()).put("name", name))
        return target.toString()
    }

    private fun record(entry: JSONObject) {
        UndoLog.push(
            entry.put("batch", batchId ?: UUID.randomUUID().toString())
                .put("label", batchLabel)
                .put("time", System.currentTimeMillis())
        )
    }

    // ---------- Network ----------
    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } } ?: ""
            if (code !in 200..299) throw Exception("Provider returned HTTP $code: ${body.take(180)}")
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchBytes(url: String): ByteArray? {
        if (!url.startsWith("https://")) return null
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Provider lookups ----------
    private fun search(provider: String, query: String, kind: String, apiKey: String, year: String): JSONObject {
        val q = URLEncoder.encode(query, "UTF-8")
        val key = URLEncoder.encode(apiKey, "UTF-8")
        val p = provider.lowercase(Locale.ROOT)
        val yearNum = year.filter { it.isDigit() }.take(4)
        return try {
            val results = JSONArray()
            when (p) {
                "tmdb" -> {
                    if (apiKey.isBlank()) return failure("Add your TMDb API key in Settings.")
                    val isTv = kind == "tv"
                    var url = "https://api.themoviedb.org/3/" + (if (isTv) "search/tv" else "search/movie") +
                        "?api_key=$key&query=$q&include_adult=false"
                    if (yearNum.isNotBlank()) url += if (isTv) "&first_air_date_year=$yearNum" else "&year=$yearNum"
                    JSONObject(fetchText(url)).arr("results").objects().forEach { x ->
                        val date = x.s("release_date").ifBlank { x.s("first_air_date") }
                        results.put(item("tmdb", x.optInt("id").toString(), x.s("title").ifBlank { x.s("name") },
                            date.take(4), x.s("overview"), tmdbImage(x.s("poster_path"), "w500"), kind))
                    }
                }
                "omdb" -> {
                    if (apiKey.isBlank()) return failure("Add your OMDb API key in Settings.")
                    var url = "https://www.omdbapi.com/?apikey=$key&s=$q&type=" + (if (kind == "tv") "series" else "movie")
                    if (yearNum.isNotBlank()) url += "&y=$yearNum"
                    val body = JSONObject(fetchText(url))
                    if (body.s("Response") == "False") throw Exception(body.s("Error").ifBlank { "OMDb search failed" })
                    body.arr("Search").objects().forEach { x ->
                        results.put(item("omdb", x.s("imdbID"), x.s("Title"), x.s("Year").take(4), "",
                            na(x.s("Poster")), kind))
                    }
                }
                "tvmaze" -> {
                    JSONArray(fetchText("https://api.tvmaze.com/search/shows?q=$q")).objects().forEach { w ->
                        val x = w.optJSONObject("show") ?: return@forEach
                        results.put(item("tvmaze", x.optInt("id").toString(), x.s("name"), x.s("premiered").take(4),
                            htmlText(x.s("summary")), x.optJSONObject("image")?.s("original") ?: "", "tv"))
                    }
                }
                else -> return failure("Unknown provider.")
            }
            JSONObject().put("ok", true).put("results", results)
        } catch (e: Exception) {
            failure(e.message ?: "Provider request failed")
        }
    }

    private fun tmdbDetails(id: String, kind: String, apiKey: String): JSONObject {
        if (apiKey.isBlank()) throw Exception("Add your TMDb API key in Settings.")
        val isTv = kind == "tv"
        val path = if (isTv) "tv" else "movie"
        val j = JSONObject(fetchText("https://api.themoviedb.org/3/$path/$id?api_key=${URLEncoder.encode(apiKey, "UTF-8")}&append_to_response=external_ids,credits"))
        val m = JSONObject()
        m.put("title", j.s(if (isTv) "name" else "title"))
        m.put("originalTitle", j.s(if (isTv) "original_name" else "original_title"))
        m.put("year", j.s(if (isTv) "first_air_date" else "release_date").take(4))
        m.put("overview", j.s("overview"))
        m.put("poster", tmdbImage(j.s("poster_path"), "w500"))
        m.put("fanart", tmdbImage(j.s("backdrop_path"), "w1280"))
        m.put("runtime", if (isTv) j.arr("episode_run_time").optInt(0, 0) else j.i("runtime"))
        m.put("rating", j.d("vote_average"))
        val imdb = if (isTv) (j.optJSONObject("external_ids")?.s("imdb_id") ?: "") else j.s("imdb_id")
        m.put("ids", JSONObject().put("tmdb", id).put("imdb", imdb))
        val genres = JSONArray()
        j.arr("genres").objects().forEach { genres.put(it.s("name")) }
        m.put("genres", genres)
        val credits = j.optJSONObject("credits")?.arr("cast")?.objects() ?: emptyList()
        m.put("cast", castList(credits.map { it.s("name") to it.s("character") }))
        m.put("set", if (isTv) "" else (j.optJSONObject("belongs_to_collection")?.s("name") ?: ""))
        if (isTv) {
            val seasons = JSONArray()
            j.arr("seasons").objects().filter { it.i("season_number") > 0 }.forEach { s ->
                seasons.put(JSONObject().put("number", s.i("season_number")).put("episodes", s.i("episode_count"))
                    .put("poster", tmdbImage(s.s("poster_path"), "w500")))
            }
            m.put("seasons", seasons)
        }
        return m
    }

    private fun omdbDetails(id: String, kind: String, apiKey: String): JSONObject {
        if (apiKey.isBlank()) throw Exception("Add your OMDb API key in Settings.")
        val j = JSONObject(fetchText("https://www.omdbapi.com/?apikey=${URLEncoder.encode(apiKey, "UTF-8")}&i=${URLEncoder.encode(id, "UTF-8")}&plot=full"))
        if (j.s("Response") == "False") throw Exception(j.s("Error").ifBlank { "OMDb lookup failed" })
        val m = JSONObject()
        m.put("title", j.s("Title"))
        m.put("originalTitle", "")
        m.put("year", j.s("Year").take(4))
        m.put("overview", na(j.s("Plot")))
        m.put("poster", na(j.s("Poster")))
        m.put("fanart", "")
        m.put("runtime", Regex("\\d+").find(j.s("Runtime"))?.value?.toIntOrNull() ?: 0)
        m.put("rating", j.s("imdbRating").toDoubleOrNull() ?: 0.0)
        m.put("ids", JSONObject().put("imdb", id))
        val genres = JSONArray()
        na(j.s("Genre")).split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { genres.put(it) }
        m.put("genres", genres)
        m.put("cast", castList(na(j.s("Actors")).split(",").map { it.trim() to "" }))
        if (kind == "tv") {
            val seasons = JSONArray()
            val total = j.s("totalSeasons").toIntOrNull() ?: 0
            for (n in 1..minOf(total, 60)) seasons.put(JSONObject().put("number", n).put("episodes", 0).put("poster", ""))
            m.put("seasons", seasons)
        }
        return m
    }

    private fun tvmazeDetails(id: String): JSONObject {
        val j = JSONObject(fetchText("https://api.tvmaze.com/shows/$id?embed%5B%5D=cast"))
        val m = JSONObject()
        m.put("title", j.s("name"))
        m.put("originalTitle", "")
        m.put("year", j.s("premiered").take(4))
        m.put("overview", htmlText(j.s("summary")))
        m.put("poster", j.optJSONObject("image")?.s("original") ?: "")
        m.put("fanart", "")
        m.put("runtime", j.i("averageRuntime").takeIf { it > 0 } ?: j.i("runtime"))
        m.put("rating", j.optJSONObject("rating")?.d("average") ?: 0.0)
        m.put("ids", JSONObject().put("tvmaze", id).put("imdb", j.optJSONObject("externals")?.s("imdb") ?: ""))
        val genres = JSONArray()
        val g = j.arr("genres")
        for (i in 0 until g.length()) genres.put(g.optString(i))
        m.put("genres", genres)
        val cast = j.optJSONObject("_embedded")?.arr("cast")?.objects() ?: emptyList()
        m.put("cast", castList(cast.map {
            (it.optJSONObject("person")?.s("name") ?: "") to (it.optJSONObject("character")?.s("name") ?: "")
        }))
        val seasons = JSONArray()
        JSONArray(fetchText("https://api.tvmaze.com/shows/$id/seasons")).objects().filter { it.i("number") > 0 }.forEach { s ->
            seasons.put(JSONObject().put("number", s.i("number")).put("episodes", s.i("episodeOrder"))
                .put("poster", s.optJSONObject("image")?.s("original") ?: ""))
        }
        m.put("seasons", seasons)
        return m
    }

    private fun details(provider: String, id: String, kind: String, apiKey: String): JSONObject {
        return try {
            val m = when (provider) {
                "tmdb" -> tmdbDetails(id, kind, apiKey)
                "omdb" -> omdbDetails(id, kind, apiKey)
                "tvmaze" -> tvmazeDetails(id)
                else -> throw Exception("Unknown provider.")
            }
            m.put("provider", provider).put("id", id).put("kind", kind)
            JSONObject().put("ok", true).put("meta", m)
        } catch (e: Exception) {
            failure(e.message ?: "Lookup failed")
        }
    }

    private fun episodes(provider: String, id: String, apiKey: String, season: Int): JSONObject {
        return try {
            val out = JSONArray()
            when (provider) {
                "tmdb" -> {
                    if (apiKey.isBlank()) throw Exception("Add your TMDb API key in Settings.")
                    val j = JSONObject(fetchText("https://api.themoviedb.org/3/tv/$id/season/$season?api_key=${URLEncoder.encode(apiKey, "UTF-8")}"))
                    j.arr("episodes").objects().forEach { e ->
                        out.put(JSONObject().put("season", season).put("number", e.i("episode_number"))
                            .put("title", e.s("name")).put("overview", e.s("overview")).put("aired", e.s("air_date"))
                            .put("runtime", e.i("runtime")).put("still", tmdbImage(e.s("still_path"), "w500"))
                            .put("rating", e.d("vote_average")))
                    }
                }
                "tvmaze" -> {
                    JSONArray(fetchText("https://api.tvmaze.com/shows/$id/episodes")).objects()
                        .filter { it.i("season") == season }.forEach { e ->
                            out.put(JSONObject().put("season", season).put("number", e.i("number"))
                                .put("title", e.s("name")).put("overview", htmlText(e.s("summary")))
                                .put("aired", e.s("airdate")).put("runtime", e.i("runtime"))
                                .put("still", e.optJSONObject("image")?.s("original") ?: "")
                                .put("rating", e.optJSONObject("rating")?.d("average") ?: 0.0))
                        }
                }
                "omdb" -> {
                    if (apiKey.isBlank()) throw Exception("Add your OMDb API key in Settings.")
                    val j = JSONObject(fetchText("https://www.omdbapi.com/?apikey=${URLEncoder.encode(apiKey, "UTF-8")}&i=${URLEncoder.encode(id, "UTF-8")}&Season=$season"))
                    if (j.s("Response") == "False") throw Exception(j.s("Error").ifBlank { "Episode lookup failed" })
                    j.arr("Episodes").objects().forEach { e ->
                        out.put(JSONObject().put("season", season).put("number", e.s("Episode").toIntOrNull() ?: 0)
                            .put("title", e.s("Title")).put("overview", "").put("aired", airDate(e.s("Released")))
                            .put("runtime", 0).put("still", "").put("rating", e.s("imdbRating").toDoubleOrNull() ?: 0.0))
                    }
                }
                else -> throw Exception("Unknown provider.")
            }
            JSONObject().put("ok", true).put("episodes", out)
        } catch (e: Exception) {
            failure(e.message ?: "Episode lookup failed")
        }
    }

    // ---------- Writes ----------
    private fun saveMovie(fileUri: String, metaJson: String): JSONObject {
        return try {
            val uri = Uri.parse(fileUri)
            val m = JSONObject(metaJson)
            val isDisc = queryMime(uri) == DocumentsContract.Document.MIME_TYPE_DIR
            val folder = if (isDisc) uri else parentOf(uri)
            val stem = if (isDisc) "movie" else stemOf(queryName(uri))
            val nfoName = if (isDisc) "movie.nfo" else "$stem.nfo"
            val posterName = if (isDisc) "poster.jpg" else "$stem-poster.jpg"
            val fanartName = if (isDisc) "fanart.jpg" else "$stem-fanart.jpg"
            val written = mutableListOf(nfoName)
            writeFile(folder, nfoName, "application/octet-stream", movieNfo(m))
            fetchBytes(m.s("poster"))?.let { writeFile(folder, posterName, "image/jpeg", it); written.add("poster") }
            fetchBytes(m.s("fanart"))?.let { writeFile(folder, fanartName, "image/jpeg", it); written.add("fanart") }
            JSONObject().put("ok", true).put("message", "Saved ${written.joinToString()} for ${if (isDisc) queryName(uri) else stem}.")
        } catch (e: Exception) {
            failure(e.message ?: "Save failed")
        }
    }

    private fun saveShow(fileUri: String, showJson: String): JSONObject {
        return try {
            val show = JSONObject(showJson)
            val folder = showFolderOf(Uri.parse(fileUri))
            writeFile(folder, "tvshow.nfo", "application/octet-stream", showNfo(show))
            fetchBytes(show.s("poster"))?.let { writeFile(folder, "poster.jpg", "image/jpeg", it) }
            fetchBytes(show.s("fanart"))?.let { writeFile(folder, "fanart.jpg", "image/jpeg", it) }
            var posters = 0
            for (season in show.arr("seasons").objects().take(40)) {
                val bytes = fetchBytes(season.s("poster")) ?: continue
                writeFile(folder, String.format(Locale.ROOT, "season%02d-poster.jpg", season.i("number")), "image/jpeg", bytes)
                posters++
            }
            JSONObject().put("ok", true)
                .put("message", "Saved tvshow.nfo, artwork and $posters season poster(s) in ${queryName(folder)}.")
        } catch (e: Exception) {
            failure(e.message ?: "Show save failed")
        }
    }

    private fun saveEpisode(fileUri: String, showJson: String, episodeJson: String): JSONObject {
        return try {
            val uri = Uri.parse(fileUri)
            val show = JSONObject(showJson)
            val raw = episodeJson.trim()
            val eps = if (raw.startsWith("[")) JSONArray(raw).objects() else listOf(JSONObject(raw))
            if (eps.isEmpty()) throw Exception("No episodes to save.")
            val folder = parentOf(uri)
            val stem = stemOf(queryName(uri))
            writeFile(folder, "$stem.nfo", "application/octet-stream", episodeNfo(show, eps))
            val written = mutableListOf("$stem.nfo")
            fetchBytes(eps[0].s("still"))?.let { writeFile(folder, "$stem-thumb.jpg", "image/jpeg", it); written.add("thumbnail") }
            JSONObject().put("ok", true).put("message", "Saved ${written.joinToString()} for $stem.")
        } catch (e: Exception) {
            failure(e.message ?: "Episode save failed")
        }
    }

    private fun queryMime(uri: Uri): String =
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "application/octet-stream"

    private fun querySize(uri: Uri): Long =
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L

    /** Finds a subfolder by name, or creates it. Newly created folders are logged so undo can remove them. */
    private fun ensureFolder(parent: Uri, name: String): Uri {
        val tree = treeUri ?: throw Exception("Choose a media folder first")
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(tree, documentId(parent))
        val existing = children(parentDoc).firstOrNull {
            it.s("name").equals(name, ignoreCase = true) && it.s("mime") == DocumentsContract.Document.MIME_TYPE_DIR
        }
        if (existing != null) return Uri.parse(existing.s("uri"))
        val created = DocumentsContract.createDocument(contentResolver, parentDoc, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw Exception("Could not create folder $name")
        record(JSONObject().put("type", "folder").put("uri", created.toString()).put("name", name))
        return created
    }

    /** Resolves a path such as "Movies/Dune (2021)" from the library root, creating missing folders. */
    private fun resolveFolder(relPath: String): Uri {
        val tree = treeUri ?: throw Exception("Choose a media folder first")
        var current = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        for (seg in relPath.split("/").filter { it.isNotBlank() }) {
            validateName(seg)
            current = ensureFolder(current, seg)
        }
        return current
    }

    /**
     * Moves one file to another folder and/or renames it, as a single undoable step.
     * Uses a native move when the provider supports it. Otherwise copies the file, checks the size,
     * and removes the original, so a failed copy never loses data.
     */
    private fun relocate(src: Uri, oldName: String, newName: String, fromFolder: Uri, toFolder: Uri, mime: String): Uri {
        val tree = treeUri ?: throw Exception("Choose a media folder first")
        val fromDoc = DocumentsContract.buildDocumentUriUsingTree(tree, documentId(fromFolder))
        val toDoc = DocumentsContract.buildDocumentUriUsingTree(tree, documentId(toFolder))

        if (documentId(fromDoc) == documentId(toDoc)) {
            val renamed = DocumentsContract.renameDocument(contentResolver, src, newName)
                ?: throw Exception("This storage provider does not support renaming.")
            record(JSONObject().put("type", "rename").put("uri", renamed.toString()).put("name", oldName))
            return renamed
        }

        val moved: Uri? = try {
            DocumentsContract.moveDocument(contentResolver, src, fromDoc, toDoc)
        } catch (e: Exception) {
            null
        }
        if (moved != null) {
            var result: Uri = moved
            if (newName != oldName) {
                val renamed = try {
                    DocumentsContract.renameDocument(contentResolver, moved, newName)
                } catch (e: Exception) {
                    null
                }
                if (renamed == null) {
                    DocumentsContract.moveDocument(contentResolver, moved, toDoc, fromDoc)
                    throw Exception("Could not rename $oldName after moving it, so the move was reversed.")
                }
                result = renamed
            }
            record(
                JSONObject().put("type", "move").put("uri", result.toString()).put("name", oldName).put("newName", newName)
                    .put("mime", mime).put("fromParent", fromDoc.toString()).put("toParent", toDoc.toString()).put("copied", false)
            )
            return result
        }

        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            throw Exception("This storage provider cannot move folders. Move the folder by hand.")
        }
        // Fallback for providers without native move: copy, verify, then remove the original.
        val size = querySize(src)
        val target = DocumentsContract.createDocument(contentResolver, toDoc, mime, newName)
            ?: throw Exception("Could not create $newName in the destination folder.")
        val copied: Long = try {
            val input = contentResolver.openInputStream(src) ?: throw Exception("Could not read $oldName")
            val output = contentResolver.openOutputStream(target, "wt") ?: throw Exception("Could not write $newName")
            input.use { i -> output.use { o -> i.copyTo(o) } }
        } catch (e: Exception) {
            DocumentsContract.deleteDocument(contentResolver, target)
            throw Exception("Copying $oldName failed; the original was kept. ${e.message ?: ""}".trim())
        }
        if (size > 0 && copied != size) {
            DocumentsContract.deleteDocument(contentResolver, target)
            throw Exception("The copy of $oldName was incomplete, so the original was kept.")
        }
        if (!DocumentsContract.deleteDocument(contentResolver, src)) {
            DocumentsContract.deleteDocument(contentResolver, target)
            throw Exception("Could not remove $oldName from its old folder, so nothing was moved.")
        }
        record(
            JSONObject().put("type", "move").put("uri", target.toString()).put("name", oldName).put("newName", newName)
                .put("mime", mime).put("fromParent", fromDoc.toString()).put("toParent", toDoc.toString()).put("copied", true)
        )
        return target
    }

    /**
     * Renames a video and, unless keepFolder is set, moves it into the folder path under the library root.
     * Companion files that share the video's name (NFO, posters, fanart, subtitles) move with it.
     */
    private fun organize(fileUri: String, relFolder: String, newStem: String, keepFolder: Boolean): JSONObject {
        return try {
            validateName(newStem)
            val uri = Uri.parse(fileUri)
            val oldName = queryName(uri)
            val isDir = queryMime(uri) == DocumentsContract.Document.MIME_TYPE_DIR
            val oldStem = if (isDir) oldName else stemOf(oldName)
            val ext = if (isDir || '.' !in oldName) "" else oldName.substringAfterLast('.')
            val newName = if (ext.isNotEmpty()) "$newStem.$ext" else newStem
            val oldFolder = parentOf(uri)
            val targetFolder = if (keepFolder) oldFolder else resolveFolder(relFolder)
            val sameFolder = documentId(oldFolder) == documentId(targetFolder)
            if (sameFolder && newName == oldName) {
                return JSONObject().put("ok", true).put("uri", uri.toString()).put("message", "Already in place.")
            }
            val oldSiblings = children(oldFolder)
            val targetNames = (if (sameFolder) oldSiblings else children(targetFolder)).map { it.s("name") }.toMutableSet()
            if (newName in targetNames) throw Exception("A file named $newName already exists in the destination.")

            val result = relocate(uri, oldName, newName, oldFolder, targetFolder, queryMime(uri))
            targetNames.add(newName)

            var companions = 0
            val skipped = mutableListOf<String>()
            if (!isDir && oldStem.isNotBlank()) {
                for (s in oldSiblings) {
                    val name = s.s("name")
                    if (name == oldName || !name.startsWith(oldStem)) continue
                    val rest = name.substring(oldStem.length)
                    if (!(rest.startsWith(".") || rest.startsWith("-"))) continue
                    if (rest.substringAfterLast('.', "").lowercase(Locale.ROOT) in VIDEO_EXT) continue
                    val target = newStem + rest
                    if (target == name) continue
                    if (target in targetNames) {
                        skipped.add(name)
                        continue
                    }
                    relocate(Uri.parse(s.s("uri")), name, target, oldFolder, targetFolder, s.s("mime"))
                    targetNames.add(target)
                    companions++
                }
            }
            val where = if (sameFolder) "Renamed to $newName" else "Moved to ${relFolder.ifBlank { "library root" }}/$newName"
            val note = if (skipped.isEmpty()) "" else " Skipped existing: ${skipped.joinToString()}."
            JSONObject().put("ok", true).put("uri", result.toString())
                .put("message", "$where, with $companions companion file(s).$note")
        } catch (e: Exception) {
            failure(e.message ?: "Move failed")
        }
    }

    private fun exportCsv(fileName: String, csv: String): JSONObject {
        return try {
            validateName(fileName)
            val tree = treeUri ?: throw Exception("Choose a media folder first")
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            writeFile(root, fileName, "text/csv", csv.toByteArray(Charsets.UTF_8))
            JSONObject().put("ok", true).put("message", "Exported $fileName to the library folder.")
        } catch (e: Exception) {
            failure(e.message ?: "Export failed")
        }
    }

    // ---------- Reading existing NFO files ----------
    /** Parses Kodi-style NFO XML. Several <episodedetails> blocks in one file are supported. */
    private fun parseNfo(raw: String): JSONObject {
        val cleaned = raw.replaceFirst(Regex("^\\s*<\\?xml[^>]*>"), "")
        val parser = Xml.newPullParser()
        parser.setInput(StringReader("<nfo>$cleaned</nfo>"))
        val out = JSONObject()
        val genres = JSONArray()
        val cast = JSONArray()
        val ids = JSONObject()
        val eps = JSONArray()
        val stack = ArrayList<String>()
        var kind = ""
        var ep: JSONObject? = null
        var actor: JSONObject? = null
        var uniqueType = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    stack.add(name)
                    val depth = stack.size
                    if (depth == 2) {
                        kind = name
                        if (name == "episodedetails") ep = JSONObject()
                    }
                    if (depth == 3 && name == "actor") actor = JSONObject()
                    if (depth == 3 && name == "uniqueid") uniqueType = parser.getAttributeValue(null, "type") ?: ""
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty() && stack.size >= 3) {
                        val depth = stack.size
                        val field = stack[depth - 1]
                        val target: JSONObject? = if (kind == "episodedetails") ep else out
                        when {
                            depth == 4 && stack[2] == "actor" && field == "name" -> actor?.put("name", text)
                            depth == 4 && stack[2] == "actor" && field == "role" -> actor?.put("role", text)
                            depth == 4 && stack[2] == "set" && field == "name" -> out.put("set", text)
                            depth == 3 && field == "genre" -> genres.put(text)
                            depth == 3 && field == "uniqueid" -> ids.put(uniqueType, text)
                            depth == 3 && target != null && field in SCALAR_FIELDS -> target.put(field, text)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val depth = stack.size
                    if (depth == 3 && stack[2] == "actor") {
                        actor?.let { cast.put(it) }
                        actor = null
                    }
                    if (depth == 2 && kind == "episodedetails") {
                        ep?.let { eps.put(it) }
                        ep = null
                    }
                    stack.removeAt(depth - 1)
                }
            }
            event = parser.next()
        }
        out.put("kind", when (kind) {
            "movie" -> "movie"
            "tvshow" -> "tv"
            "episodedetails" -> "episode"
            else -> ""
        })
        out.put("genres", genres).put("cast", cast).put("ids", ids).put("episodes", eps)
        return out
    }

    private fun readNfoFile(uri: Uri): JSONObject {
        return try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw Exception("Could not open the NFO file.")
            JSONObject().put("ok", true).put("nfo", parseNfo(text))
        } catch (e: Exception) {
            failure(e.message ?: "Could not read the NFO file.")
        }
    }

    /** The tvshow.nfo in a file's show folder, or null when there is none. */
    private fun readShowNfo(fileUri: Uri): JSONObject {
        return try {
            val folder = showFolderOf(fileUri)
            val file = children(folder).firstOrNull { it.s("name").equals("tvshow.nfo", ignoreCase = true) }
            if (file == null) {
                JSONObject().put("ok", true).put("nfo", JSONObject.NULL)
            } else {
                readNfoFile(Uri.parse(file.s("uri")))
            }
        } catch (e: Exception) {
            failure(e.message ?: "Could not read the show NFO.")
        }
    }

    // ---------- Undo ----------
    private fun undoList(): JSONArray {
        val groups = linkedMapOf<String, MutableList<JSONObject>>()
        UndoLog.toList(UndoLog.entries()).forEach { groups.getOrPut(it.s("batch")) { mutableListOf() }.add(it) }
        val out = JSONArray()
        groups.values.reversed().take(20).forEach { g ->
            val first = g.first()
            out.put(JSONObject().put("label", first.s("label")).put("count", g.size).put("time", first.optLong("time")))
        }
        return out
    }

    private fun undoLast(): JSONObject {
        val all = UndoLog.toList(UndoLog.entries())
        if (all.isEmpty()) return JSONObject().put("ok", true).put("message", "Nothing to undo.").put("moved", JSONArray())
        val batch = all.last().s("batch")
        val group = all.filter { it.s("batch") == batch }.asReversed()
        val moved = JSONArray()
        var done = 0
        val failed = mutableListOf<String>()
        for (e in group) {
            try {
                val uri = Uri.parse(e.s("uri"))
                when (e.s("type")) {
                    "rename" -> {
                        val back = DocumentsContract.renameDocument(contentResolver, uri, e.s("name"))
                            ?: throw Exception("rename refused")
                        moved.put(JSONObject().put("from", uri.toString()).put("to", back.toString()))
                    }
                    "move" -> {
                        val fromDoc = Uri.parse(e.s("fromParent"))
                        val toDoc = Uri.parse(e.s("toParent"))
                        val oldName = e.s("name")
                        val mime = e.s("mime").ifBlank { "application/octet-stream" }
                        if (e.optBoolean("copied")) {
                            val back = DocumentsContract.createDocument(contentResolver, fromDoc, mime, oldName)
                                ?: throw Exception("could not recreate $oldName")
                            val input = contentResolver.openInputStream(uri) ?: throw Exception("moved copy not readable")
                            val output = contentResolver.openOutputStream(back, "wt") ?: throw Exception("could not write back")
                            input.use { i -> output.use { o -> i.copyTo(o) } }
                            if (!DocumentsContract.deleteDocument(contentResolver, uri)) throw Exception("could not remove moved copy")
                            moved.put(JSONObject().put("from", uri.toString()).put("to", back.toString()))
                        } else {
                            var current = uri
                            if (e.s("newName") != oldName) {
                                current = DocumentsContract.renameDocument(contentResolver, current, oldName)
                                    ?: throw Exception("rename refused")
                            }
                            val back = DocumentsContract.moveDocument(contentResolver, current, toDoc, fromDoc)
                                ?: throw Exception("move refused")
                            moved.put(JSONObject().put("from", uri.toString()).put("to", back.toString()))
                        }
                    }
                    "folder" -> {
                        if (!DocumentsContract.deleteDocument(contentResolver, uri)) throw Exception("folder is not empty")
                    }
                    "create" -> {
                        if (!DocumentsContract.deleteDocument(contentResolver, uri)) throw Exception("delete refused")
                    }
                    "overwrite" -> {
                        val bytes = UndoLog.backupFile(e)?.takeIf { it.exists() }?.readBytes()
                            ?: throw Exception("backup missing")
                        contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                    }
                }
                done++
            } catch (ex: Exception) {
                failed.add(e.s("name").ifBlank { e.s("uri") })
            }
        }
        UndoLog.removeBatch(batch)
        val note = if (failed.isEmpty()) "" else " Could not undo: ${failed.joinToString()}."
        return JSONObject().put("ok", true).put("message", "Undid $done change(s).$note").put("moved", moved)
    }

    // ---------- JavaScript bridge (all methods run off the UI thread) ----------
    inner class Bridge {
        @JavascriptInterface fun chooseFolder() { runOnUiThread { this@MainActivity.chooseFolder() } }

        @JavascriptInterface fun folderStatus(): String =
            JSONObject().put("selected", treeUri != null).put("uri", treeUri?.toString() ?: "").toString()

        @JavascriptInterface fun scan(): String = try {
            JSONObject().put("ok", true).put("files", scanFolder()).toString()
        } catch (e: Exception) {
            failure(e.message ?: "Scan failed").toString()
        }

        @JavascriptInterface fun search(provider: String, query: String, kind: String, apiKey: String, year: String): String =
            this@MainActivity.search(provider, query, kind, apiKey, year).toString()

        @JavascriptInterface fun details(provider: String, id: String, kind: String, apiKey: String): String =
            this@MainActivity.details(provider, id, kind, apiKey).toString()

        @JavascriptInterface fun episodes(provider: String, id: String, apiKey: String, season: String): String =
            this@MainActivity.episodes(provider, id, apiKey, season.toIntOrNull() ?: 0).toString()

        @JavascriptInterface fun saveMovie(fileUri: String, metaJson: String): String =
            this@MainActivity.saveMovie(fileUri, metaJson).toString()

        @JavascriptInterface fun saveShow(fileUri: String, showJson: String): String =
            this@MainActivity.saveShow(fileUri, showJson).toString()

        @JavascriptInterface fun saveEpisode(fileUri: String, showJson: String, episodeJson: String): String =
            this@MainActivity.saveEpisode(fileUri, showJson, episodeJson).toString()

        @JavascriptInterface fun rename(fileUri: String, newStem: String): String =
            this@MainActivity.organize(fileUri, "", newStem, true).toString()

        @JavascriptInterface fun organize(fileUri: String, relFolder: String, newStem: String, keepFolder: String): String =
            this@MainActivity.organize(fileUri, relFolder, newStem, keepFolder == "true").toString()

        @JavascriptInterface fun exportCsv(fileName: String, csv: String): String =
            this@MainActivity.exportCsv(fileName, csv).toString()

        @JavascriptInterface fun beginBatch(label: String) {
            batchId = UUID.randomUUID().toString()
            batchLabel = label
        }

        @JavascriptInterface fun endBatch() {
            batchId = null
            batchLabel = ""
        }

        @JavascriptInterface fun readNfo(uriString: String): String = this@MainActivity.readNfoFile(Uri.parse(uriString)).toString()

        @JavascriptInterface fun readShowNfo(uriString: String): String = this@MainActivity.readShowNfo(Uri.parse(uriString)).toString()

        @JavascriptInterface fun undoList(): String = this@MainActivity.undoList().toString()

        @JavascriptInterface fun undoLast(): String = this@MainActivity.undoLast().toString()
    }
}
