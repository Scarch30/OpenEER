package com.example.openeer.ui.viewer

import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.Injection
import com.example.openeer.R
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.ui.panel.media.MediaActions
import com.example.openeer.ui.panel.media.MediaStripItem
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import android.view.WindowInsetsController


class DocumentViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_MIME = "mime"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BLOCKID = "blockId"
    }

    private val blocksRepo: BlocksRepository by lazy {
        Injection.provideBlocksRepository(this)
    }

    private val mediaActions: MediaActions by lazy {
        MediaActions(this, blocksRepo)
    }

    private var pfd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path  = intent.getStringExtra(EXTRA_PATH)
        val mime  = (intent.getStringExtra(EXTRA_MIME) ?: "").lowercase()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.document_viewer_title)
        setTitle(title)

        if (path.isNullOrBlank()) {
            Toast.makeText(this, R.string.media_missing_file, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, R.string.media_missing_file, Toast.LENGTH_SHORT).show()
            finish(); return
        }

        // Dispatch simple
        val isPdf = mime == "application/pdf" || path.endsWith(".pdf", true)
        if (isPdf) {
            renderPdf(file); return
        }

        val isHtml = mime == "text/html" || path.endsWith(".html", true) || path.endsWith(".htm", true)
        val isMd   = mime == "text/markdown" || mime == "text/x-markdown" || path.endsWith(".md", true)
        if (isHtml || isMd) {
            renderWeb(file, isMarkdown = isMd); return
        }

        renderPlainText(file)

        setupMenu()
    }

    private fun setupMenu() {
        val blockId = intent.getLongExtra(EXTRA_BLOCKID, -1)
        if (blockId == -1L) return

        findViewById<ImageButton>(R.id.docMenu)?.setOnClickListener { anchor ->
            lifecycleScope.launch {
                val block = blocksRepo.getBlock(blockId) ?: return@launch
                val item = MediaStripItem.File(
                    blockId = block.id,
                    mediaUri = block.mediaUri ?: "",
                    mimeType = block.mimeType,
                    displayName = block.childName ?: block.text ?: "",
                    childOrdinal = block.childOrdinal,
                    childName = block.childName
                )
                mediaActions.showMenu(anchor, item)
            }
        }
    }

    // ---------- TXT ----------
    private fun renderPlainText(file: File) {
        setContentView(R.layout.activity_document_viewer)
        val textView = findViewById<TextView>(R.id.docText)
        textView.typeface = Typeface.MONOSPACE
        textView.setTextIsSelectable(true)

        val content: String = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse {
                runCatching { file.readText(Charsets.ISO_8859_1) }
                    .getOrElse { getString(R.string.document_viewer_read_error) }
            }

        textView.text = content.ifBlank { " " }

        // Contraste dynamique : fond sombre si texte clair, sinon fond clair
        val isTextLight = isColorLight(textView.currentTextColor)
        applyContrastForTextColor(isTextLight)
        setupMenu()
    }

    // ---------- HTML / Markdown ----------
    private fun renderWeb(file: File, isMarkdown: Boolean) {
        setContentView(R.layout.activity_document_viewer_web)
        val web = findViewById<WebView>(R.id.docWeb)

        // Réglages sûrs
        web.settings.javaScriptEnabled = false
        web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        // Fond clair par défaut pour lisibilité
        web.setBackgroundColor(0xFFFFFFFF.toInt())
        setSystemBarIconContrast(lightIcons = true)
        if (Build.VERSION.SDK_INT >= 29) {
            // Laisse WebView décider en auto si le site a un thème sombre,
            // mais notre fond reste blanc par défaut.
            web.settings.forceDark = WebSettings.FORCE_DARK_AUTO
        }

        val raw = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse {
                runCatching { file.readText(Charsets.ISO_8859_1) }
                    .getOrElse { "" }
            }

        if (isMarkdown) {
            val html = markdownToBasicHtml(raw)
            web.loadDataWithBaseURL(
                null, html, "text/html", "utf-8", null
            )
        } else {
            web.loadDataWithBaseURL(
                "file://${file.parentFile?.absolutePath}/",
                raw,
                "text/html",
                "utf-8",
                null
            )
        }
        setupMenu()
    }

    private fun markdownToBasicHtml(md: String): String {
        var t = md
        t = t.replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
        t = t.replace(Regex("\\*\\*([^*]+)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
        t = t.replace(Regex("\\*([^*]+)\\*")) { "<i>${it.groupValues[1]}</i>" }
        t = t.replace(Regex("(?m)^######\\s+(.+)$")) { "<h6>${it.groupValues[1]}</h6>" }
        t = t.replace(Regex("(?m)^#####\\s+(.+)$"))  { "<h5>${it.groupValues[1]}</h5>" }
        t = t.replace(Regex("(?m)^####\\s+(.+)$"))   { "<h4>${it.groupValues[1]}</h4>" }
        t = t.replace(Regex("(?m)^###\\s+(.+)$"))    { "<h3>${it.groupValues[1]}</h3>" }
        t = t.replace(Regex("(?m)^##\\s+(.+)$"))     { "<h2>${it.groupValues[1]}</h2>" }
        t = t.replace(Regex("(?m)^#\\s+(.+)$"))      { "<h1>${it.groupValues[1]}</h1>" }
        t = t.replace(Regex("(?m)^\\s*[-*]\\s+(.+)$")) { "<li>${it.groupValues[1]}</li>" }
        t = t.replace(Regex("(?s)(<li>.*?</li>)")) { "<ul>${it.groupValues[1]}</ul>" }

        val lines = t.split("\n")
        val sb = StringBuilder()
        for (line in lines) {
            if (line.isBlank()) sb.append("<br/>") else sb.append("<p>").append(line).append("</p>")
        }

        val body = sb.toString()
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1"/>
              <style>
                body { font-family: -apple-system,BlinkMacSystemFont,Roboto,Segoe UI,Helvetica,Arial,sans-serif; line-height: 1.45; padding: 12px; background:#fff; color:#111; }
                code { background: #2223; padding: 0 4px; border-radius: 4px; }
                pre  { background: #2223; padding: 8px; border-radius: 6px; overflow-x: auto; }
                h1,h2,h3 { margin-top: 1em; }
                ul { padding-left: 20px; margin: 0.5em 0; }
                p  { margin: 0.25em 0; }
              </style>
            </head>
            <body>$body</body>
            </html>
        """.trimIndent()
    }

    // ---------- PDF (PdfRenderer + RecyclerView) ----------
    private fun renderPdf(file: File) {
        setContentView(R.layout.activity_document_viewer_pdf)
        val list = findViewById<RecyclerView>(R.id.pdfList)
        // Fond clair pour lisibilité sur documents scannés/typo noire
        list.setBackgroundColor(0xFFFFFFFF.toInt())
        setSystemBarIconContrast(lightIcons = true)

        list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)

        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(pfd!!)
        } catch (e: Throwable) {
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.document_viewer_unsupported, "pdf"), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        list.adapter = PdfPageAdapter(pdfRenderer!!)
        setupMenu()
    }

    private class PdfPageAdapter(
        private val renderer: PdfRenderer
    ) : RecyclerView.Adapter<PdfPageVH>() {

        override fun getItemCount(): Int = renderer.pageCount

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PdfPageVH {
            val ctx = parent.context
            val pv = PhotoView(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                adjustViewBounds = true
                val pad = (ctx.resources.displayMetrics.density * 12).toInt()
                setPadding(pad, pad, pad, pad)
                scaleType = ImageView.ScaleType.FIT_CENTER
                minimumScale = 1.0f
                mediumScale   = 2.5f
                maximumScale  = 5.0f
            }
            return PdfPageVH(pv)
        }

        override fun onBindViewHolder(holder: PdfPageVH, position: Int) {
            holder.recycle()
            val page = renderer.openPage(position)
            try {
                val dm = holder.itemView.resources.displayMetrics
                val paddingPx = (dm.density * 24).toInt()
                val targetW = max(dm.widthPixels - paddingPx, 1)
                val scale   = targetW.toFloat() / page.width.toFloat()
                val targetH = max((page.height * scale).toInt(), 1)

                val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                holder.bind(bmp)
            } finally {
                page.close()
            }
        }

        override fun onViewRecycled(holder: PdfPageVH) {
            holder.recycle()
            super.onViewRecycled(holder)
        }
    }

    private class PdfPageVH(
        private val photoView: PhotoView
    ) : RecyclerView.ViewHolder(photoView) {

        private var current: Bitmap? = null

        fun bind(bitmap: Bitmap) {
            current?.let { if (!it.isRecycled) it.recycle() }
            current = bitmap
            photoView.setImageBitmap(bitmap)
            photoView.setScale(photoView.minimumScale, true)
        }

        fun recycle() {
            photoView.setImageDrawable(null)
            current?.let { if (!it.isRecycled) it.recycle() }
            current = null
        }
    }

    // ---------- Helpers contraste & barres système ----------
    private fun isColorLight(color: Int): Boolean {
        val r = (color shr 16 and 0xFF) / 255.0
        val g = (color shr 8 and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return luminance > 0.6
    }

    // REPLACE these two functions:
    private fun applyContrastForTextColor(isTextLight: Boolean) {
        // texte clair -> fond sombre ; texte foncé -> fond clair
        val bg = if (isTextLight) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

        // essaie de cibler un root classique si présent ; sinon décor
        val root = run {
            val id = resources.getIdentifier("documentRoot", "id", packageName)
            if (id != 0) findViewById<View>(id) else null
        } ?: window.decorView
        root.setBackgroundColor(bg)

        // icônes de barres (status/nav) : claires si fond sombre, foncées si fond clair
        setSystemBarIconContrast(lightIcons = !isTextLight)
    }

    @Suppress("DEPRECATION")
    private fun setSystemBarIconContrast(lightIcons: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            val appearance = if (lightIcons) mask else 0
            controller?.setSystemBarsAppearance(appearance, mask)
        } else {
            // Avant Android 11: on ne peut régler que le status bar en clair/foncé
            var flags = window.decorView.systemUiVisibility
            flags = if (lightIcons) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            window.decorView.systemUiVisibility = flags
        }
    }


    override fun onDestroy() {
        runCatching { pdfRenderer?.close() }
        runCatching { pfd?.close() }
        super.onDestroy()
    }
}
