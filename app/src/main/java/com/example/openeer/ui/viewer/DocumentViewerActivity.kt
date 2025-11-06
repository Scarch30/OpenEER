package com.example.openeer.ui.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import java.io.File
import com.github.chrisbanes.photoview.PhotoView


class DocumentViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH  = "path"
        const val EXTRA_MIME  = "mime"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BLOCK = "blockId"
    }

    private var pfd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path  = intent.getStringExtra(EXTRA_PATH)
        val mime  = (intent.getStringExtra(EXTRA_MIME) ?: "").lowercase()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.document_viewer_title)

        title.let { setTitle(it) }

        if (path.isNullOrBlank()) {
            Toast.makeText(this, R.string.media_missing_file, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, R.string.media_missing_file, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Dispatch par type (ordre : PDF → HTML/MD → texte brut)
        val isPdf = mime == "application/pdf" || path.endsWith(".pdf", true)
        if (isPdf) {
            renderPdf(file)
            return
        }

        val isHtml = mime == "text/html" || path.endsWith(".html", true) || path.endsWith(".htm", true)
        val isMd   = mime == "text/markdown" || mime == "text/x-markdown" || path.endsWith(".md", true)

        if (isHtml || isMd) {
            renderWeb(file, isMarkdown = isMd)
            return
        }

        // Fallback : texte brut
        renderPlainText(file)
    }

    // --- TXT ---

    private fun renderPlainText(file: File) {
        setContentView(R.layout.activity_document_viewer)
        val textView = findViewById<TextView>(R.id.docText)
        textView.typeface = android.graphics.Typeface.MONOSPACE
        textView.setTextIsSelectable(true)

        val content: String = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse {
                runCatching { file.readText(Charsets.ISO_8859_1) }
                    .getOrElse { getString(R.string.document_viewer_read_error) }
            }

        textView.text = content.ifBlank { " " }
    }

    // --- HTML / Markdown (WebView) ---

    private fun renderWeb(file: File, isMarkdown: Boolean) {
        setContentView(R.layout.activity_document_viewer_web)
        val web = findViewById<WebView>(R.id.docWeb)

        // Réglages safe par défaut
        web.settings.javaScriptEnabled = false
        web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        if (Build.VERSION.SDK_INT >= 29) {
            web.settings.forceDark = WebSettings.FORCE_DARK_AUTO
        }

        val raw = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse {
                runCatching { file.readText(Charsets.ISO_8859_1) }
                    .getOrElse { "" }
            }

        if (isMarkdown) {
            // MVP Markdown → HTML très simple (pas de lib), suffisante pour lecture
            val html = markdownToBasicHtml(raw)
            web.loadDataWithBaseURL(
                /* baseUrl = */ null,
                /* data    = */ html,
                /* mime    = */ "text/html",
                /* enc     = */ "utf-8",
                /* history = */ null
            )
        } else {
            // HTML : on affiche tel quel
            web.loadDataWithBaseURL(
                /* baseUrl = */ "file://${file.parentFile?.absolutePath}/",
                /* data    = */ raw,
                /* mime    = */ "text/html",
                /* enc     = */ "utf-8",
                /* history = */ null
            )
        }
    }

    private fun markdownToBasicHtml(md: String): String {
        // Conversion très légère : titres, gras/italique, code inline, paragraphes & sauts de ligne.
        var t = md

        // Code inline `
        t = t.replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }

        // **bold** / *italic*
        t = t.replace(Regex("\\*\\*([^*]+)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
        t = t.replace(Regex("\\*([^*]+)\\*")) { "<i>${it.groupValues[1]}</i>" }

        // Titres #..######
        t = t.replace(Regex("(?m)^######\\s+(.+)$")) { "<h6>${it.groupValues[1]}</h6>" }
        t = t.replace(Regex("(?m)^#####\\s+(.+)$"))  { "<h5>${it.groupValues[1]}</h5>" }
        t = t.replace(Regex("(?m)^####\\s+(.+)$"))   { "<h4>${it.groupValues[1]}</h4>" }
        t = t.replace(Regex("(?m)^###\\s+(.+)$"))    { "<h3>${it.groupValues[1]}</h3>" }
        t = t.replace(Regex("(?m)^##\\s+(.+)$"))     { "<h2>${it.groupValues[1]}</h2>" }
        t = t.replace(Regex("(?m)^#\\s+(.+)$"))      { "<h1>${it.groupValues[1]}</h1>" }

        // Listes simples
        t = t.replace(Regex("(?m)^\\s*[-*]\\s+(.+)$")) { "<li>${it.groupValues[1]}</li>" }
        t = t.replace(Regex("(?s)(<li>.*?</li>)")) { "<ul>${it.groupValues[1]}</ul>" }

        // Paragraphes & sauts de ligne
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
                body { font-family: -apple-system,BlinkMacSystemFont,Roboto,Segoe UI,Helvetica,Arial,sans-serif; line-height: 1.45; padding: 12px; }
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

    // --- PDF (PdfRenderer + RecyclerView paginé) ---

    private fun renderPdf(file: File) {
        setContentView(R.layout.activity_document_viewer_pdf)
        val list = findViewById<RecyclerView>(R.id.pdfList)
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

        val renderer = pdfRenderer!!
        list.adapter = PdfPageAdapter(renderer)
    }

    private class PdfPageAdapter(
        private val renderer: PdfRenderer
    ) : RecyclerView.Adapter<PdfPageVH>() {

        override fun onViewRecycled(holder: PdfPageVH) {
            super.onViewRecycled(holder)
            holder.recycle()
        }

        override fun getItemCount(): Int = renderer.pageCount

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PdfPageVH {
            val ctx = parent.context
            val pv = PhotoView(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                adjustViewBounds = true
                // marge interne légère pour le confort
                val pad = (ctx.resources.displayMetrics.density * 12).toInt()
                setPadding(pad, pad, pad, pad)
                // comportements de zoom
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                minimumScale = 1.0f
                mediumScale   = 2.5f
                maximumScale  = 5.0f
                // autorise le fling/pan, déjà géré par PhotoView
            }
            return PdfPageVH(pv)
        }


        override fun onBindViewHolder(holder: PdfPageVH, position: Int) {
            holder.recycle() // libère l’ancien bitmap si recyclage
            val page = renderer.openPage(position)
            try {
                // Rendons à la largeur disponible de l’écran (qualité nette + pas d’OOM)
                val dm = holder.itemView.resources.displayMetrics
                val paddingPx = (dm.density * 24).toInt() // ≈ 12dp de chaque côté
                val targetW = (dm.widthPixels - paddingPx).coerceAtLeast(1)
                val scale   = targetW.toFloat() / page.width.toFloat()
                val targetH = (page.height * scale).toInt().coerceAtLeast(1)

                val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                holder.bind(bmp)
            } finally {
                page.close()
            }
        }

    }

    private class PdfPageVH(
        private val photoView: PhotoView
    ) : RecyclerView.ViewHolder(photoView) {

        private var current: Bitmap? = null

        fun bind(bitmap: Bitmap) {
            // libère l’ancien si présent
            current?.let { if (!it.isRecycled) it.recycle() }
            current = bitmap
            photoView.setImageBitmap(bitmap)
            // reset du zoom à chaque nouvelle page liée au holder
            photoView.setScale(photoView.minimumScale, true)
        }

        fun recycle() {
            photoView.setImageDrawable(null)
            current?.let { if (!it.isRecycled) it.recycle() }
            current = null
        }
    }


    override fun onDestroy() {
        runCatching { pdfRenderer?.close() }
        runCatching { pfd?.close() }
        super.onDestroy()
    }
}
