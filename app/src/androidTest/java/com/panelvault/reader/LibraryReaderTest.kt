package com.panelvault.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Suppress("DEPRECATION")
class LibraryReaderTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun visibleViews(): List<View> = descendants(activity.window.decorView)
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for UI")
    }
    fun testLibraryDrawerSizeAndReaderProgress() {
        val app = activity
        val library = File(app.filesDir, "comics").apply { mkdirs() }
        val comic = File(library, "Fixture #001.cbz")
        ZipOutputStream(comic.outputStream()).use { zip ->
            repeat(3) { index ->
                zip.putNextEntry(ZipEntry("%03d.png".format(index)))
                val bitmap = Bitmap.createBitmap(180, 270, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if (index == 0) Color.BLUE else Color.CYAN)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip); bitmap.recycle(); zip.closeEntry()
            }
        }
        val active = activity
        // Recreate through the existing library method to avoid keeping a stale Activity reference.
        instrumentation.runOnMainSync {
            MainActivity::class.java.getDeclaredMethod("showLibrary").apply { isAccessible = true }.invoke(active)
        }
        instrumentation.runOnMainSync {
            val slider = visibleViews().filterIsInstance<SeekBar>().single()
            slider.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
            slider.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
            assertTrue(active.getSharedPreferences("display", 0).getInt("coverSize", 0) > 160)
            visibleViews().first { it.contentDescription == "Open navigation" }.performClick()
        }
        waitFor { visibleViews().filterIsInstance<TextView>().any { it.text.toString() == "Close navigation" } }
        instrumentation.runOnMainSync { active.onBackPressed() }
        Thread.sleep(300)
        instrumentation.runOnMainSync {
            MainActivity::class.java.getDeclaredMethod("open", File::class.java).apply { isAccessible = true }.invoke(active, comic)
        }
        waitFor { visibleViews().filterIsInstance<TextView>().any { it.text.toString() == "1 / 3" } }
        instrumentation.runOnMainSync { visibleViews().filterIsInstance<TextView>().first { it.text.toString() == "Next" }.performClick() }
        waitFor { visibleViews().filterIsInstance<TextView>().any { it.text.toString() == "2 / 3" } }
        assertEquals(1, active.getSharedPreferences("progress", 0).getInt(comic.name, -1))
        instrumentation.runOnMainSync { active.onBackPressed() }
        val cbr = File(library, "Fixture RAR5.cbr")
        instrumentation.context.assets.open("fixture.cbr").use { input -> cbr.outputStream().use { input.copyTo(it) } }
        val pdf = File(library, "Fixture PDF.pdf")
        val doc = PdfDocument()
        try {
            repeat(2) { index ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(180, 270, index + 1).create())
                page.canvas.drawColor(Color.BLUE); doc.finishPage(page)
            }
            pdf.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
        listOf(cbr, pdf).forEach { file ->
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("open", File::class.java).apply { isAccessible = true }.invoke(active, file)
            }
            waitFor { visibleViews().filterIsInstance<TextView>().any { it.text.toString() == "1 / 2" } }
            instrumentation.runOnMainSync { visibleViews().filterIsInstance<TextView>().first { it.text.toString() == "Next" }.performClick() }
            waitFor { visibleViews().filterIsInstance<TextView>().any { it.text.toString() == "2 / 2" } }
            assertEquals(1, active.getSharedPreferences("progress", 0).getInt(file.name, -1))
            instrumentation.runOnMainSync { active.onBackPressed() }
        }
        waitFor { visibleViews().any { it.contentDescription == "Open navigation" } }
    }
}

