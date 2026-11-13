package com.tejashandigol.tiltcards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import org.json.JSONArray

internal object PhotoStore {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(List<File>) -> Unit>()
    private val pending = mutableMapOf<String, MutableSet<() -> Unit>>()
    private val failed = mutableSetOf<String>()
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun isFixed(context: Context) = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
        .getBoolean("fixed_mode", false)

    private fun manifest(context: Context, fixed: Boolean) =
        AtomicFile(File(context.filesDir, if (fixed) "fixed-photos.json" else "tilt-photos.json"))

    fun subscribe(context: Context, listener: (List<File>) -> Unit) {
        listeners.add(listener)
        refresh(context)
    }

    fun unsubscribe(listener: (List<File>) -> Unit) { listeners.remove(listener) }

    fun refresh(context: Context) {
        val app = context.applicationContext
        val fixed = isFixed(app)
        worker.execute {
            val result = runCatching { read(app, fixed) }
            main.post {
                result.onSuccess { files ->
                    if (fixed == isFixed(app)) listeners.toList().forEach { it(files) }
                }
            }
        }
    }

    fun replace(context: Context, photos: List<Uri>, finished: (Boolean) -> Unit) =
        importPhotos(context, photos, isFixed(context), false, finished)

    fun importPhotos(context: Context, photos: List<Uri>, fixed: Boolean,
                     append: Boolean, finished: (Boolean) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val created = mutableListOf<File>()
            val result = runCatching {
                require(fixed || photos.size <= 3)
                val existing = read(app, fixed)
                photos.forEach { uri ->
                    val file = File(app.filesDir, "photo-" + UUID.randomUUID() + ".image")
                    created.add(file)
                    val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Photo unavailable")
                    input.use { source -> file.outputStream().use { source.copyTo(it) } }
                    // Validate each photo independently so batch memory stays bounded.
                    decode(app, file).recycle()
                }
                val files = (if (fixed && append) existing else emptyList()) + created
                write(app, fixed, files)
                existing.filter { it !in files }.forEach { it.delete() }
                files
            }
            if (result.isFailure) created.forEach { it.delete() }
            main.post {
                result.onSuccess { files ->
                    if (fixed == isFixed(app)) listeners.toList().forEach { it(files) }
                }
                finished(result.isSuccess)
            }
        }
    }

    private fun read(context: Context, fixed: Boolean): List<File> {
        val file = manifest(context, fixed)
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val data = file.openRead().bufferedReader().use { JSONArray(it.readText()) }
            return List(data.length()) { File(context.filesDir, data.getString(it)) }
        }
        // Migrate the original shared collection independently into both modes.
        val migrated = mutableListOf<File>()
        try {
            val legacy = AtomicFile(File(context.filesDir, "cards.zip"))
            if (legacy.baseFile.exists() || File(legacy.baseFile.path + ".bak").exists()) {
                ZipInputStream(legacy.openRead().buffered()).use { zip ->
                    while ((fixed || migrated.size < 3) && zip.nextEntry != null) {
                        val original = File(context.filesDir, "photo-" + UUID.randomUUID() + ".image")
                        migrated.add(original)
                        original.outputStream().use { zip.copyTo(it) }
                        zip.closeEntry()
                    }
                }
            }
            write(context, fixed, migrated)
            return migrated
        } catch (error: Exception) {
            migrated.forEach { it.delete() }
            throw error
        }
    }

    private fun write(context: Context, fixed: Boolean, files: List<File>) {
        val file = manifest(context, fixed)
        val stream = file.startWrite()
        try {
            stream.write(JSONArray(files.map { it.name }).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun bitmap(context: Context, file: File, ready: () -> Unit): Bitmap? {
        val key = file.path
        cache.get(key)?.let { return it }
        if (key in failed) return null
        pending[key]?.let { it.add(ready); return null }
        pending[key] = mutableSetOf(ready)
        val app = context.applicationContext
        worker.execute {
            val result = runCatching { decode(app, file) }
            main.post {
                result.onSuccess { cache.put(key, it) }.onFailure { failed.add(key) }
                pending.remove(key)?.forEach { it() }
            }
        }
        return null
    }

    private fun decode(context: Context, original: File): Bitmap {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(original)) { decoder, info, _ ->
            val screen = context.resources.displayMetrics
            val width = info.size.width
            val height = info.size.height
            // Decode enough pixels to fill the physical screen, including its crop.
            // The archived original is never resized or re-encoded.
            val scale = minOf(1.0, maxOf(
                screen.widthPixels.toDouble() / width,
                screen.heightPixels.toDouble() / height
            ))
            val targetWidth = maxOf(1, ceil(width * scale).toInt())
            val targetHeight = maxOf(1, ceil(height * scale).toInt())
            decoder.setTargetSize(targetWidth, targetHeight)
            val ratio = screen.widthPixels.toDouble() / screen.heightPixels.coerceAtLeast(1)
            val cropWidth = minOf(targetWidth, maxOf(1, (targetHeight * ratio).toInt()))
            val cropHeight = minOf(targetHeight, maxOf(1, (targetWidth / ratio).toInt()))
            val left = (targetWidth - cropWidth) / 2
            val top = (targetHeight - cropHeight) / 2
            decoder.setCrop(Rect(left, top, left + cropWidth, top + cropHeight))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
        }
    }

}
