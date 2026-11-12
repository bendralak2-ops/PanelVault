package com.panelvault.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.text.TextUtils
import java.util.concurrent.Executors
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.widget.*
import java.io.File
import java.util.zip.ZipFile
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry

class MainActivity : Activity() {
    private val library by lazy { File(filesDir, "comics").apply { mkdirs() } }
    private val progress by lazy { getSharedPreferences("progress", MODE_PRIVATE) }
    private var current: File? = null
    private var pages: List<File> = emptyList()
    private var pdf: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var bitmap: Bitmap? = null
    private var page = 0
    private var sortMode = "Title A–Z"
    private var gridMode = true
    private var librarySection = "Comics"
    private var libraryQuery = ""
    private var visibleCount = 60
    private var selectedSeries: String? = null
    private var readerControlsVisible = true
    private var readerTop: View? = null
    private var readerBottom: View? = null
    private val coverWorker = Executors.newSingleThreadExecutor()
    private var libraryGeneration = 0
    private val coverDir by lazy { File(cacheDir, "covers").apply { mkdirs() } }
    private lateinit var picture: ImageView
    private lateinit var count: TextView
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12,12,12,12); setBackgroundColor(0xff101a2a.toInt()) }
    private fun button(s: String, f: () -> Unit) = Button(this).apply { text = s; setOnClickListener { f() } }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); showLibrary() }
    private fun cleanup() {
        pdf?.close(); pdf = null; descriptor?.close(); descriptor = null
        bitmap?.recycle(); bitmap = null
        pages = emptyList(); File(cacheDir,"pages").deleteRecursively()
    }
    private fun dp(n: Int) = (resources.displayMetrics.density * n + 0.5f).toInt()
    private fun label(text: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
    }
    private fun coverKey(file: File) = file.name.hashCode().toUInt().toString(16) + "_" + file.length() + "_" + file.lastModified()
    private fun coverFile(file: File) = File(coverDir, coverKey(file) + ".jpg")
    private fun extractCover(file: File): Bitmap? {
        if (file.extension.equals("pdf", true)) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { p ->
                        val w = 300
                        val h = (p.height.toDouble() * w / p.width).toInt().coerceIn(1, 650)
                        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { cover ->
                            cover.eraseColor(Color.WHITE)
                            p.render(cover, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }
        val bytes: ByteArray? = when (file.extension.lowercase()) {
            "cbz" -> ZipFile(file).use { zip ->
                val first = zip.entries().asSequence().filter { !it.isDirectory && image(it.name) }
                    .minByOrNull { it.name.lowercase() }
                first?.let { zip.getInputStream(it).use { stream -> java.io.ByteArrayOutputStream().use { out ->
                        val buffer = ByteArray(32768)
                        while (out.size() < 12 * 1024 * 1024) {
                            val n = stream.read(buffer, 0, minOf(buffer.size, 12 * 1024 * 1024 - out.size()))
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    } } }
            }
            "cbr" -> {
                val archive = Archive.readNew()
                try {
                    Archive.readSupportFilterAll(archive)
                    Archive.readSupportFormatRar(archive)
                    Archive.readSupportFormatRar5(archive)
                    Archive.readOpenFileName(archive, file.absolutePath.toByteArray(Charsets.UTF_8), 10240)
                    var candidate: ByteArray? = null
                    while (true) {
                        val entry = Archive.readNextHeader(archive)
                        if (entry == 0L) break
                        val name = ArchiveEntry.pathnameUtf8(entry) ?: ""
                        if (ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG && image(name)) {
                            val temp = File.createTempFile("cover", ".img", cacheDir)
                            try {
                                ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE).use { fd ->
                                    Archive.readDataIntoFd(archive, fd.fd)
                                }
                                if (temp.length() <= 12L * 1024 * 1024) candidate = temp.readBytes()
                            } finally { temp.delete() }
                            break
                        } else Archive.readDataSkip(archive)
                    }
                    candidate
                } finally { Archive.readFree(archive) }
            }
            "pdf" -> null
            else -> null
        }
        if (bytes == null) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 500) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    private fun loadCover(file: File, target: ImageView, generation: Int) {
        val cached = coverFile(file)
        if (cached.exists()) {
            BitmapFactory.decodeFile(cached.path)?.let { target.setImageBitmap(it); return }
        }
        coverWorker.execute {
            try {
                val bmp = extractCover(file) ?: return@execute
                cached.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 78, it) }
                bmp.recycle()
                runOnUiThread {
                    if (generation == libraryGeneration) {
                        BitmapFactory.decodeFile(cached.path)?.let { target.setImageBitmap(it) }
                    }
                }
            } catch (_: Exception) { /* Leave the placeholder for unreadable covers. */ }
        }
    }
    private fun comicTitle(file: File) = file.nameWithoutExtension.replace('_', ' ').trim()
    private fun seriesName(file: File): String {
        val title = comicTitle(file)
        return title.replace(Regex("""(?i)\\s*(?:#|issue\\s*|no\\.?\\s*)\\d+.*$"""), "")
            .replace(Regex("""\\s+\\(\\d{4}\\).*$"""), "")
            .replace(Regex("""\\s+\\d{3,4}$"""), "")
            .trim(' ', '-', '_').ifBlank { title }
    }
    private fun panelBackground(selected: Boolean = false) = GradientDrawable().apply {
        setColor(if (selected) 0xff205b94.toInt() else 0xff1b2d47.toInt())
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), if (selected) 0xff4ca9ed.toInt() else 0xff315b85.toInt())
    }
    private fun navButton(title: String, selected: Boolean, action: () -> Unit) = button(title, action).apply {
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        background = panelBackground(selected)
    }
    private fun comicCard(file: File, generation: Int): LinearLayout {
        val width = (resources.displayMetrics.widthPixels - dp(40)) / 2
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(9), dp(9), dp(12))
            background = panelBackground()
            elevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { open(file) }
            val cover = ImageView(this@MainActivity).apply {
                setBackgroundColor(0xff253957.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(android.R.drawable.ic_menu_gallery)
                contentDescription = "Cover for ${comicTitle(file)}"
            }
            addView(cover, LinearLayout.LayoutParams(-1, dp(220)))
            loadCover(file, cover, generation)
            val name = label(comicTitle(file), 14f).apply {
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(name, LinearLayout.LayoutParams(-1, dp(50)))
            val saved = progress.getInt(file.name, 0)
            val hasProgress = progress.contains(file.name)
            addView(label(if (hasProgress) "Continue • Page ${saved + 1}" else file.extension.uppercase(), 12f, 0xff93b9e9.toInt()))
        }
    }
    private fun libraryMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        listOf("Sort: Title A–Z", "Sort: Title Z–A", "Sort: Recently added", "Sort: Recently read",
            if (gridMode) "View: List" else "View: Cover grid", "About PanelVault").forEach { popup.menu.add(it) }
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "About PanelVault" -> android.app.AlertDialog.Builder(this).setTitle("PanelVault")
                    .setMessage("Offline comic library • CBZ / CBR / PDF").setPositiveButton("OK", null).show()
                "View: List", "View: Cover grid" -> { gridMode = !gridMode; showLibrary() }
                else -> { sortMode = item.title.toString().removePrefix("Sort: "); showLibrary() }
            }
            true
        }
        popup.show()
    }
    private fun showLibrary() {
        libraryGeneration++
        val generation = libraryGeneration
        cleanup(); current = null
        val root = column()
        root.setPadding(dp(12), dp(12), dp(12), dp(6))
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label("PANELVAULT", 29f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        val menuButton = navButton("☰ Menu", false) {}
        menuButton.setOnClickListener { libraryMenu(menuButton) }
        heading.addView(menuButton)
        root.addView(heading)
        root.addView(label("YOUR COMIC COLLECTION", 11f, 0xff8db5e5.toInt()))
        root.addView(navButton("＋  IMPORT COMICS & BOOKS", true) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, 12)
        })
        val search = EditText(this).apply {
            hint = "Search comics and books"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xff9aadc9.toInt())
            setText(libraryQuery)
            background = GradientDrawable().apply {
                setColor(0xff1d304b.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(46)))
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf("Comics", "Series", "Rulebooks", "All").forEach { section ->
            val tab = navButton(section, librarySection == section) { librarySection = section; selectedSeries = null; visibleCount = 60; showLibrary() }
            tabs.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(tabs)
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val allFiles = library.listFiles()?.filter {
            it.isFile && it.extension.lowercase() in listOf("cbz", "cbr", "pdf")
        } ?: emptyList()
        val refresh = object {
            fun render() {
                body.removeAllViews()
                val filtered = allFiles.filter { file ->
                    (librarySection == "All" ||
                     (librarySection == "Comics" && !file.extension.equals("pdf", true)) ||
                     (librarySection == "Series" && !file.extension.equals("pdf", true)) ||
                     (librarySection == "Rulebooks" && file.extension.equals("pdf", true))) &&
                     comicTitle(file).contains(libraryQuery, ignoreCase = true) &&
                     (librarySection != "Series" || selectedSeries == null || seriesName(file) == selectedSeries)
                }
                if (librarySection == "Series" && selectedSeries == null) {
                    val groups = filtered.groupBy { seriesName(it) }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    body.addView(label("SERIES  •  " + groups.size, 19f).apply { setPadding(0, dp(16), 0, dp(8)) })
                    groups.forEach { (name, issues) ->
                        body.addView(navButton(name + "   •   " + issues.size + " issues", false) {
                            selectedSeries = name
                            visibleCount = 60
                            render()
                        }, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(5) })
                    }
                    return
                }
                if (librarySection == "Series" && selectedSeries != null) {
                    body.addView(navButton("‹ ALL SERIES", false) { selectedSeries = null; render() })
                }
                val sorted = when (sortMode) {
                    "Title Z–A" -> filtered.sortedByDescending { it.name.lowercase() }
                    "Recently added" -> filtered.sortedByDescending { it.lastModified() }
                    "Recently read" -> filtered.sortedByDescending { progress.getLong("recent_" + it.name, 0L) }
                    else -> filtered.sortedBy { it.name.lowercase() }
                }
                val ongoing = sorted.filter { progress.contains(it.name) }
                    .sortedByDescending { progress.getLong("recent_" + it.name, 0L) }
                if (ongoing.isNotEmpty() && libraryQuery.isBlank()) {
                    body.addView(label("CONTINUE READING", 19f).apply { setPadding(0, dp(14), 0, dp(8)) })
                    ongoing.take(5).forEach { file ->
                        body.addView(button(comicTitle(file) + " • Page " + (progress.getInt(file.name, 0) + 1)) { open(file) })
                    }
                }
                body.addView(label(librarySection.uppercase() + " • " + sorted.size, 19f).apply {
                    setPadding(0, dp(16), 0, dp(8))
                })
                if (sorted.isEmpty()) body.addView(label("Nothing here yet. Import a file or change your search.", 15f, 0xffaebdd0.toInt()))
                val shown = sorted.take(visibleCount)
                if (gridMode) shown.chunked(2).forEach { pair ->
                    val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                    pair.forEach { row.addView(comicCard(it, generation)) }
                    body.addView(row)
                } else shown.forEach { file ->
                    body.addView(button(comicTitle(file) + " • " + file.extension.uppercase()) { open(file) })
                }
                if (shown.size < sorted.size) body.addView(button("Show more (" + (sorted.size - shown.size) + " remaining)") {
                    visibleCount += 60
                    render()
                })
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                libraryQuery = s?.toString() ?: ""
                visibleCount = 60
                refresh.render()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        refresh.render()
        setContentView(root)
    }
    @Deprecated("Legacy Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode != 12 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<android.net.Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
        Thread {
            var n=0
            for (uri in uris) try {
                val name = contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "comic.cbz"
                val safe = name.replace(Regex("[^A-Za-z0-9 ._()-]"),"_")
                if (safe.substringAfterLast('.',"").lowercase() !in listOf("cbz","cbr","pdf")) continue
                var dest=File(library,safe); var i=2
                while(dest.exists()) { dest=File(library,"(${i++}) $safe") }
                contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: error("Unreadable file")
                n++
            } catch (_: Exception) {}
            runOnUiThread { toast("Imported $n comics"); showLibrary() }
        }.start()
    }
    private fun archiveFormat(file: File): String {
        val signature = ByteArray(8)
        val n = file.inputStream().use { it.read(signature) }
        if (n < 7) return "Unrecognized archive"
        val prefix = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)
        if (!prefix.indices.all { signature[it] == prefix[it] }) return "Not a RAR archive"
        return when (signature[6].toInt() and 255) {
            0 -> "RAR4"
            1 -> "RAR5"
            else -> "Unknown RAR version"
        }
    }
    private fun diagnostic(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        return "${root.javaClass.simpleName}: ${root.message ?: "no details"}"
    }
    private fun image(name: String) = name.lowercase().matches(Regex(".*\\.(jpg|jpeg|png|webp|gif)"))
    private fun open(file: File) {
        Thread {
            try {
                val output = File(cacheDir,"pages").apply { deleteRecursively(); mkdirs() }
                val found=mutableListOf<Pair<String,File>>()
                var total=0L
                fun add(name:String, writer:(File)->Unit) {
                    if (!image(name)) return
                    if(found.size>=3000) error("Too many pages")
                    val target=File(output,"%05d.page".format(found.size))
                    writer(target)
                    total+=target.length()
                    if(target.length()>100L*1024*1024 || total>2L*1024*1024*1024) error("Archive too large")
                    found.add(name to target)
                }
                when(file.extension.lowercase()) {
                    "cbz" -> ZipFile(file).use { zip ->
                        val entries=zip.entries().asSequence().filter { !it.isDirectory && image(it.name) }.sortedBy { it.name.lowercase() }.toList()
                        for (entry in entries) add(entry.name) { dest -> zip.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } } }
                    }
                    "cbr" -> {
                        val format = archiveFormat(file)
                        if (format != "RAR4" && format != "RAR5") error("Unsupported CBR format: $format")
                        val archive = Archive.readNew()
                        try {
                            Archive.readSupportFilterAll(archive)
                            Archive.readSupportFormatRar(archive)
                            Archive.readSupportFormatRar5(archive)
                            Archive.readOpenFileName(archive, file.absolutePath.toByteArray(Charsets.UTF_8), 10240)
                            while (true) {
                                val entry = Archive.readNextHeader(archive)
                                if (entry == 0L) break
                                val name = ArchiveEntry.pathnameUtf8(entry) ?: ""
                                if (ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG && image(name)) {
                                    if (ArchiveEntry.sizeIsSet(entry) && ArchiveEntry.size(entry) > 100L * 1024 * 1024) error("Page too large")
                                    add(name) { dest ->
                                        ParcelFileDescriptor.open(dest, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE).use { fd ->
                                            Archive.readDataIntoFd(archive, fd.fd)
                                        }
                                    }
                                } else Archive.readDataSkip(archive)
                            }
                        } finally { Archive.readFree(archive) }
                    }
                }
                if (file.extension.lowercase() != "pdf" && found.isEmpty()) error("No supported image pages found")
                runOnUiThread {
                    try {
                        cleanupExceptCache()
                        current=file
                        pages=found.sortedBy { it.first.lowercase() }.map { it.second }
                        if (file.extension.equals("pdf",true)) {
                            descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
                            pdf=PdfRenderer(descriptor!!)
                        }
                        page=progress.getInt(file.name,0).coerceIn(0,(pageCount()-1).coerceAtLeast(0))
                        showReader()
                    } catch(e:Exception) { toast("Open failed: ${diagnostic(e)}"); showLibrary() }
                }
            } catch(e:Exception) { runOnUiThread { toast("Cannot read: ${diagnostic(e)}") } }
        }.start()
    }
    private fun cleanupExceptCache() { pdf?.close();pdf=null;descriptor?.close();descriptor=null;bitmap?.recycle();bitmap=null }
    private fun pageCount() = pdf?.pageCount ?: pages.size
    private fun toggleReaderControls() {
        readerControlsVisible = !readerControlsVisible
        readerTop?.visibility = if (readerControlsVisible) View.VISIBLE else View.GONE
        readerBottom?.visibility = if (readerControlsVisible) View.VISIBLE else View.GONE
    }
    private fun showReader() {
        val view=column()
        readerControlsVisible = true
        val top=LinearLayout(this)
        top.addView(button("Library") { showLibrary() })
        count=TextView(this).apply { setTextColor(-1); gravity=Gravity.CENTER; textSize=18f }
        top.addView(count,LinearLayout.LayoutParams(0,-2,1f))
        top.addView(button("⋮") {
            val options = arrayOf("Go to page", "Hide controls")
            android.app.AlertDialog.Builder(this).setTitle("Reader menu").setItems(options) { _, which ->
                if (which == 1) toggleReaderControls()
                else {
                    val input = EditText(this).apply { inputType = 2; hint = "Page number" }
                    android.app.AlertDialog.Builder(this).setTitle("Go to page").setView(input)
                        .setPositiveButton("Go") { _, _ ->
                            input.text.toString().toIntOrNull()?.let { page = (it - 1).coerceIn(0, (pageCount()-1).coerceAtLeast(0)); draw() }
                        }.setNegativeButton("Cancel", null).show()
                }
            }.show()
        })
        readerTop = top
        view.addView(top)
        picture=ImageView(this).apply { adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER }
        var zoom = 1f
        var panX = 0f
        var panY = 0f
        val scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
                if (zoom == 1f) { panX = 0f; panY = 0f }
                picture.scaleX = zoom; picture.scaleY = zoom
                picture.translationX = panX; picture.translationY = panY
                return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                when {
                    e.x < picture.width * 0.25f -> change(-1)
                    e.x > picture.width * 0.75f -> change(1)
                    else -> toggleReaderControls()
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoom = if (zoom > 1f) 1f else 2f
                panX = 0f; panY = 0f
                picture.scaleX = zoom; picture.scaleY = zoom
                picture.translationX = 0f; picture.translationY = 0f
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (zoom <= 1f) return false
                panX = (panX - dx).coerceIn(-picture.width * (zoom-1f)/2f, picture.width * (zoom-1f)/2f)
                panY = (panY - dy).coerceIn(-picture.height * (zoom-1f)/2f, picture.height * (zoom-1f)/2f)
                picture.translationX = panX; picture.translationY = panY
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (zoom > 1f) return false
                if (kotlin.math.abs(vx) > 500f && kotlin.math.abs(vx) > kotlin.math.abs(vy)) {
                    change(if (vx < 0f) 1 else -1)
                    return true
                }
                return false
            }
        })
        picture.setOnTouchListener { _, event ->
            scaler.onTouchEvent(event)
            if (!scaler.isInProgress) gestures.onTouchEvent(event)
            true
        }
        view.addView(picture, LinearLayout.LayoutParams(-1,0,1f))
        val controls=LinearLayout(this)
        controls.addView(button("Previous") { change(-1) },LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(button("Next") { change(1) },LinearLayout.LayoutParams(0,-2,1f))
        readerBottom = controls
        view.addView(controls);setContentView(view);draw()
    }
    private fun change(d:Int) { page=(page+d).coerceIn(0,(pageCount()-1).coerceAtLeast(0));draw() }
    private fun draw() {
        if(pageCount()==0) { toast("No pages found");return }
        val next=if(pdf!=null) {
            pdf!!.openPage(page).use { p ->
                val width=p.width.coerceAtMost(1600)
                val height=(p.height.toDouble()*width/p.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { p.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            }
        } else {
            val f=pages[page]
            val opts=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(f.path,opts)
            var sample=1;while(maxOf(opts.outWidth,opts.outHeight)/sample>2400) sample*=2
            BitmapFactory.decodeFile(f.path,BitmapFactory.Options().apply { inSampleSize=sample })
        }
        if(next==null) { toast("Unable to decode page");return }
        picture.setImageBitmap(null);bitmap?.recycle();bitmap=next;picture.setImageBitmap(next)
        count.text="${page+1} / ${pageCount()}"
        current?.let { progress.edit().putInt(it.name,page).putLong("recent_" + it.name, System.currentTimeMillis()).apply() }
    }
    override fun onBackPressed() { if(current!=null) showLibrary() else super.onBackPressed() }
    override fun onDestroy() { cleanup();super.onDestroy() }
}