package com.example.unutma.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Pictures in notes are copied into the app's own storage, so they survive the original being deleted. */
object Images {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun import(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 2048 || bounds.outHeight / (sample * 2) >= 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext null
            val rotation = try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Exception) { 0f }
            if (rotation != 0f) {
                val m = Matrix().apply { postRotate(rotation) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val dir = File(ctx.filesDir, "images").apply { mkdirs() }
            val f = File(dir, UUID.randomUUID().toString() + ".jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            f.absolutePath
        } catch (_: Exception) { null }
    }

    suspend fun load(path: String, maxWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$path@$maxWidth"
        cache.get(key)?.let { return@withContext it }
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.also { cache.put(key, it) }
        } catch (_: Exception) { null }
    }
}

/** Reads the words in a photo (receipts, tickets, whiteboards) with on-device ML Kit. */
object Ocr {
    fun extract(ctx: Context, path: String, done: (String?) -> Unit) {
        try {
            val image = InputImage.fromFilePath(ctx, Uri.fromFile(File(path)))
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(image)
                .addOnSuccessListener { done(it.text) }
                .addOnFailureListener { done(null) }
        } catch (_: Exception) {
            done(null)
        }
    }
}

/** Records voice notes as small AAC files. */
class VoiceRecorder(private val ctx: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording get() = recorder != null

    fun start(): Boolean {
        val dir = File(ctx.filesDir, "audio").apply { mkdirs() }
        val f = File(dir, UUID.randomUUID().toString() + ".m4a")
        return try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r; file = f; startedAt = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            f.delete(); recorder = null; false
        }
    }

    /** Returns (path, duration) or null if nothing usable was recorded. */
    fun stop(): Pair<String, Long>? {
        val r = recorder ?: return null
        val f = file
        recorder = null
        val duration = System.currentTimeMillis() - startedAt
        return try {
            r.stop(); r.release()
            if (f != null && duration > 400) f.absolutePath to duration else { f?.delete(); null }
        } catch (_: Exception) {
            r.release(); f?.delete(); null
        }
    }

    fun cancel() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        file?.delete()
    }
}

/** One shared player, so starting a clip stops any other. */
object VoicePlayer {
    private var player: MediaPlayer? = null
    var playingPath: String? = null
        private set

    fun play(path: String, onDone: () -> Unit) {
        stop()
        try {
            val p = MediaPlayer()
            p.setDataSource(path)
            p.setOnCompletionListener { stop(); onDone() }
            p.prepare()
            p.start()
            player = p; playingPath = path
        } catch (_: Exception) { stop(); onDone() }
    }

    fun position(): Int = try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }

    fun stop() {
        try { player?.stop() } catch (_: Exception) {}
        player?.release(); player = null; playingPath = null
    }
}
