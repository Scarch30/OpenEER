package com.example.openeer.ui.viewer

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.openeer.R
import java.io.File
import java.io.FileOutputStream
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.not
import org.hamcrest.Matcher
import org.junit.Test
import org.junit.runner.RunWith
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument

@RunWith(AndroidJUnit4::class)
class FileViewerActivityTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun launchPdfViewer() {
        val path = createSamplePdf()
        val intent = FileViewerActivity.newIntent(context, path, "application/pdf", -1L, "sample.pdf")
        ActivityScenario.launch<FileViewerActivity>(intent).use {
            waitForView(withId(R.id.viewerContainer))
            onView(withId(R.id.errorGroup)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        }
    }

    @Test
    fun launchTextViewer() {
        val path = createSampleText()
        val intent = FileViewerActivity.newIntent(context, path, "text/plain", -1L, "sample.txt")
        ActivityScenario.launch<FileViewerActivity>(intent).use {
            waitForView(withId(R.id.textContent))
            onView(withId(R.id.textContent)).check(matches(withText(containsString("Ligne 2"))))
        }
    }

    @Test
    fun launchDocumentViewer() {
        val path = createSampleDocx()
        val intent = FileViewerActivity.newIntent(context, path, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", -1L, "sample.docx")
        ActivityScenario.launch<FileViewerActivity>(intent).use {
            waitForView(withId(R.id.viewerContainer))
            onView(withId(R.id.errorGroup)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        }
    }

    @Test
    fun showErrorWhenMissingFile() {
        val missing = File(context.cacheDir, "missing.pdf").absolutePath
        val intent = FileViewerActivity.newIntent(context, missing, "application/pdf", -1L, "missing.pdf")
        ActivityScenario.launch<FileViewerActivity>(intent).use {
            waitForView(withId(R.id.errorGroup))
            onView(withId(R.id.errorMessage)).check(matches(withText(R.string.file_viewer_error_missing)))
            onView(withId(R.id.viewerContainer)).check(matches(not(isDisplayed())))
        }
    }

    private fun createSampleText(): String {
        val target = File(context.cacheDir, "test_sample.txt")
        target.writeText(
            buildString {
                appendLine("Ligne 1")
                appendLine("Ligne 2")
                append("Ligne 3")
            }
        )
        return target.absolutePath
    }

    private fun createSamplePdf(): String {
        val target = File(context.cacheDir, "test_sample.pdf")
        PdfDocument().use { pdf ->
            val pageInfo = PdfDocument.PageInfo.Builder(300, 300, 1).create()
            val page = pdf.startPage(pageInfo)
            val canvas = page.canvas
            val paint = Paint().apply {
                isAntiAlias = true
                textSize = 14f
            }
            canvas.drawText("OpenEER PDF", 40f, 60f, paint)
            canvas.drawText("Ligne 2", 40f, 90f, paint)
            pdf.finishPage(page)
            FileOutputStream(target).use { output ->
                pdf.writeTo(output)
            }
        }
        return target.absolutePath
    }

    private fun createSampleDocx(): String {
        val target = File(context.cacheDir, "test_sample.docx")
        XWPFDocument().use { document ->
            val title = document.createParagraph().apply {
                alignment = ParagraphAlignment.CENTER
            }
            title.createRun().apply {
                setText("OpenEER Document")
                isBold = true
            }

            val body = document.createParagraph()
            body.createRun().apply {
                setText("Ligne 2 du document")
            }

            FileOutputStream(target).use { output ->
                document.write(output)
            }
        }
        return target.absolutePath
    }

    private fun waitForView(matcher: Matcher<android.view.View>, timeoutMs: Long = 5000L) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        var error: Throwable? = null
        while (SystemClock.elapsedRealtime() < end) {
            try {
                onView(matcher).check(matches(isDisplayed()))
                return
            } catch (t: Throwable) {
                error = t
                SystemClock.sleep(100)
            }
        }
        throw error ?: AssertionError("View not found")
    }
}
