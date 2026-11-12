package com.panelvault.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.*
import java.io.File
import java.util.zip.ZipFile
import com.github.junrar.Archive

class MainActivity : Activity() {
    private val library by lazy { File(filesDir, "comics").apply { mkdirs() } }
    private val progress by lazy { getSharedPreferences("progress", MODE_PRIVATE) }
    private var current: File? = null
    private var pages: List<File> = emptyList()
    private var pdf: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var bitmap: Bitmap? = null
    private var page = 0
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
    private fun showLibrary() {
        cleanup(); current = null
        val view = column()
        view.addView(TextView(this).apply { text = "PANELVAULT • OFFLINE LIBRARY"; textSize = 24f; setTextColor(-1) })
        view.addView(button("Import CBZ / CBR / PDF") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, 12)
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        library.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }?.forEach { file ->
            list.addView(button(file.name + " • Page " + (progress.getInt(file.name,0)+1)) { open(file) })
        }
        view.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(view)
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
                    "cbr" -> Archive(file).use { archive ->
                        for (header in archive.fileHeaders) if (!header.isDirectory) add(header.fileName) { dest ->
                            dest.outputStream().use { archive.extractFile(header,it) }
                        }
                    }
                }
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
                    } catch(e:Exception) { toast(e.message ?: "Cannot open"); showLibrary() }
                }
            } catch(e:Exception) { runOnUiThread { toast("Cannot read: ${e.message}") } }
        }.start()
    }
    private fun cleanupExceptCache() { pdf?.close();pdf=null;descriptor?.close();descriptor=null;bitmap?.recycle();bitmap=null }
    private fun pageCount() = pdf?.pageCount ?: pages.size
    private fun showReader() {
        val view=column()
        val top=LinearLayout(this)
        top.addView(button("Library") { showLibrary() })
        count=TextView(this).apply { setTextColor(-1); gravity=Gravity.CENTER; textSize=18f }
        top.addView(count,LinearLayout.LayoutParams(0,-2,1f));view.addView(top)
        picture=ImageView(this).apply { adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER }
        view.addView(ScrollView(this).apply { addView(picture) },LinearLayout.LayoutParams(-1,0,1f))
        val controls=LinearLayout(this)
        controls.addView(button("Previous") { change(-1) },LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(button("Next") { change(1) },LinearLayout.LayoutParams(0,-2,1f))
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
        current?.let { progress.edit().putInt(it.name,page).apply() }
    }
    override fun onBackPressed() { if(current!=null) showLibrary() else super.onBackPressed() }
    override fun onDestroy() { cleanup();super.onDestroy() }
}