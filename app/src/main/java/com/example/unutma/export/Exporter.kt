package com.example.unutma.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.example.unutma.Note
import com.example.unutma.R
import com.example.unutma.data.AudioBlock
import com.example.unutma.data.Block
import com.example.unutma.data.CheckBlock
import com.example.unutma.data.ImageBlock
import com.example.unutma.data.TextBlock
import com.example.unutma.data.noteDisplayTitle
import com.example.unutma.data.plainText
import com.example.unutma.data.stripMarkdown
import com.example.unutma.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Share a note as plain text, a PDF (A4) or an image. */
object Exporter {
    private fun authority(ctx: Context) = ctx.packageName + ".files"

    private fun exportDir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    private fun safeName(title: String) =
        title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(40).ifBlank { "note" }

    fun shareText(ctx: Context, note: Note, blocks: List<Block>) {
        val title = noteDisplayTitle(note, blocks)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, (if (note.title.isNotBlank()) note.title + "\n\n" else "") + blocks.plainText())
        }
        ctx.startActivity(Intent.createChooser(send, "Share note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun shareFile(ctx: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, authority(ctx), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun font(ctx: Context, id: Int): Typeface =
        try { ResourcesCompat.getFont(ctx, id) ?: Typeface.DEFAULT } catch (_: Exception) { Typeface.DEFAULT }

    private class Palette(val bg: Int, val fg: Int, val body: Int, val muted: Int, val done: Int, val accent: Int)

    /** Lays the note out top to bottom; [newPage] is called when the next item would not fit. */
    private fun draw(
        ctx: Context, note: Note, blocks: List<Block>, labelName: String?, width: Int, margin: Float, scale: Float,
        pal: Palette, canvasFor: () -> Canvas, pageHeight: Float?, newPage: () -> Canvas,
    ): Float {
        val regular = font(ctx, R.font.geist_regular)
        val medium = font(ctx, R.font.geist_medium)
        val semibold = font(ctx, R.font.geist_semibold)
        val mono = font(ctx, R.font.geist_mono_regular)
        val inner = (width - margin * 2).toInt()

        var canvas = canvasFor()
        var y = margin

        fun ensure(h: Float) {
            if (pageHeight != null && y + h > pageHeight - margin) { canvas = newPage(); y = margin }
        }

        fun text(s: CharSequence, tf: Typeface, size: Float, color: Int, x: Float = margin, w: Int = inner, strike: Boolean = false, spacing: Float = 1.25f) {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf; textSize = size * scale; this.color = color; isStrikeThruText = strike
            }
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, spacing)
                .build()
            ensure(layout.height.toFloat())
            canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restore()
            y += layout.height
        }

        if (labelName != null) {
            text(labelName.uppercase(), semibold, 10f, pal.accent)
            y += 6 * scale
        }
        text(noteDisplayTitle(note, blocks), medium, 22f, pal.fg, spacing = 1.1f)
        y += 6 * scale
        val meta = buildList {
            note.reminderAt?.let { add("Reminder " + Fmt.dateTime(it)) }
            note.countdownAt?.let { add(Fmt.dateTime(it) + " · " + Fmt.countdown(it, long = true)) }
            note.expiresAt?.let { add("Expires " + Fmt.dayMonth(it)) }
        }.joinToString("  ·  ")
        if (meta.isNotEmpty()) text(meta, mono, 9f, pal.muted)
        y += 16 * scale

        for (b in blocks) {
            when (b) {
                is TextBlock -> if (b.text.isNotBlank()) { text(stripMarkdown(b.text), regular, 12f, pal.body); y += 8 * scale }
                is CheckBlock -> if (b.text.isNotBlank()) {
                    val indent = b.indent * 18f * scale
                    val box = 10f * scale
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (b.checked) pal.accent else pal.muted
                        style = if (b.checked) Paint.Style.FILL else Paint.Style.STROKE
                        strokeWidth = 1f * scale
                    }
                    ensure(18f * scale)
                    canvas.drawRoundRect(RectF(margin + indent, y + 4 * scale, margin + indent + box, y + 4 * scale + box), 2f * scale, 2f * scale, p)
                    val label = stripMarkdown(b.text) + (if (b.qty != null) "  ×${b.qty}" else "")
                    text(label, regular, 12f, if (b.checked) pal.done else pal.body, x = margin + indent + box + 10 * scale,
                        w = (inner - indent - box - 10 * scale).toInt(), strike = b.checked)
                    y += 6 * scale
                }
                is ImageBlock -> {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bmp = try { BitmapFactory.decodeFile(b.path, opts) } catch (_: Exception) { null } ?: continue
                    val w = inner.toFloat()
                    var h = w * bmp.height / bmp.width
                    if (pageHeight != null) h = h.coerceAtMost(pageHeight - margin * 2)
                    val drawW = if (h < w * bmp.height / bmp.width) h * bmp.width / bmp.height else w
                    ensure(h)
                    canvas.drawBitmap(bmp, null, RectF(margin, y, margin + drawW, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                    y += h + 12 * scale
                }
                is AudioBlock -> {
                    text("Voice note · " + (b.durationMs / 1000).let { "%d:%02d".format(it / 60, it % 60) }, mono, 10f, pal.muted)
                    y += 8 * scale
                }
            }
        }
        return y + margin
    }

    suspend fun sharePdf(ctx: Context, note: Note, blocks: List<Block>, labelName: String?, accent: Int) = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val w = 595; val h = 842 // A4 in points
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
        val pal = Palette(0xFFFFFFFF.toInt(), 0xFF141414.toInt(), 0xFF2A2A2A.toInt(), 0xFF6B6B6B.toInt(), 0xFF9E9E9E.toInt(), darken(accent))
        draw(ctx, note, blocks, labelName, w, 48f, 1f, pal, { page.canvas }, h.toFloat()) {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            page.canvas
        }
        doc.finishPage(page)
        val title = noteDisplayTitle(note, blocks)
        val f = File(exportDir(ctx), safeName(title) + ".pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        withContext(Dispatchers.Main) { shareFile(ctx, f, "application/pdf", title) }
    }

    suspend fun shareImage(ctx: Context, note: Note, blocks: List<Block>, labelName: String?, accent: Int, dark: Boolean) = withContext(Dispatchers.IO) {
        val w = 1080
        val scale = 2.6f
        val pal = if (dark) Palette(0xFF000000.toInt(), 0xFFEDEDED.toInt(), 0xFFD4D4D4.toInt(), 0xFF8A8A8A.toInt(), 0xFF6B6B6B.toInt(), accent)
        else Palette(0xFFFFFFFF.toInt(), 0xFF141414.toInt(), 0xFF2A2A2A.toInt(), 0xFF6B6B6B.toInt(), 0xFF9E9E9E.toInt(), darken(accent))
        // First pass measures the height, second pass draws.
        val probe = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        val height = draw(ctx, note, blocks, labelName, w, 72f, scale, pal, { probe }, null) { probe }.toInt().coerceIn(400, 12000)
        val bmp = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(pal.bg)
        draw(ctx, note, blocks, labelName, w, 72f, scale, pal, { c }, null) { c }
        val title = noteDisplayTitle(note, blocks)
        val f = File(exportDir(ctx), safeName(title) + ".png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        withContext(Dispatchers.Main) { shareFile(ctx, f, "image/png", title) }
    }

    /** Label colours are pale on black; on white paper they need to be deeper. */
    private fun darken(c: Int): Int {
        val r = ((c shr 16) and 0xFF) * 6 / 10
        val g = ((c shr 8) and 0xFF) * 6 / 10
        val b = (c and 0xFF) * 6 / 10
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
