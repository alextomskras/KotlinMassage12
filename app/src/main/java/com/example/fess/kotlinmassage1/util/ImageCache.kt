package com.example.fess.kotlinmassage1.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Локальный кэш картинок чата (по messageId).
 *
 * Картинка в RTDB живёт только 7 дней (relay-зона /transfers), поэтому скачанное
 * сохраняем на телефоне: история чата переживает очистку трансфера.
 * Хранилище — filesDir/messages/image_cache (ОС не чистит его сама; в отличие от
 * cacheDir он переживает «экономичный» режим системы). Итоговый размер <= ~500 КБ
 * (см. ImageUtils.ladder), лимит — MAX_ENTRIES записей, настоящий LRU: при каждом
 * попадании в кэш обновляем mtime файла, trim удаляет давно не просмотренные.
 */
object ImageCache {

    private const val TAG = "ImageCache"
    // filesDir/messages/image_cache — системная автоочистка сюда не дотягивается
    private val DIR_PATH = listOf("messages", "image_cache")
    private const val MAX_ENTRIES = 300

    /** Имя файла без расширения — одно и то же для всех форматов. */
    private fun baseName(msgId: String): String = sanitize(msgId)

    /** Директория кэша + перенос старых файлов из legacy cacheDir/chat_images (однократно). */
    private fun dir(context: Context): File {
        val d = DIR_PATH.fold(context.filesDir) { parent, name -> File(parent, name) }
        if (!d.exists()) d.mkdirs()
        migrateLegacy(context, d)
        return d
    }

    /** Разовый перенос ранее скачанных картинок из cacheDir/chat_images в filesDir. */
    @Volatile
    private var migrated = false
    private fun migrateLegacy(context: Context, target: File) {
        if (migrated) return
        migrated = true
        try {
            val old = context.cacheDir.resolve("chat_images")
            if (!old.exists() || !old.isDirectory) return
            old.listFiles()?.forEach { f ->
                val dest = File(target, f.name)
                if (!dest.exists()) {
                    if (!f.renameTo(dest)) {
                        f.copyTo(dest, overwrite = false)
                        f.delete()
                    }
                }
            }
            old.delete()
        } catch (e: Exception) {
            Log.w(TAG, "legacy cache migration failed: ${e.message}")
        }
    }

    /** Актуальный файл кэша (webp — новый формат, jpg — legacy). */
    fun file(context: Context, msgId: String): File {
        val dir = dir(context)
        val webp = File(dir, baseName(msgId) + ".webp")
        if (webp.exists()) return webp
        val jpg = File(dir, baseName(msgId) + ".jpg")
        if (jpg.exists()) return jpg
        // файла ещё нет — целевое расширение определяем по mime payload'а (если это data-URI)
        val ext = if (ImageUtils.mimeOf(msgId).startsWith("image/jpeg")) "jpg" else "webp"
        return File(dir, baseName(msgId) + "." + ext)
    }

    fun has(context: Context, msgId: String): Boolean = file(context, msgId).exists()

    /** base64 data-URI -> файл кэша. Возвращает true при успехе. */
    fun putFromBase64(context: Context, msgId: String, dataUri: String): Boolean {
        return try {
            val raw = dataUri.substringAfter("base64,", dataUri)
            val bytes = Base64.decode(raw, Base64.DEFAULT)
            // расширение — по фактическим байтам (RIFF....WEBP vs JPEG SOI)
            val ext = if (bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                           String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") "webp" else "jpg"
            val target = file(context, msgId)   // уже с правильным расширением и переносом legacy
            // если раньше был кэш в другом расширении — убираем дубликат
            val other = File(target.parentFile, baseName(msgId) + "." + if (ext == "webp") "jpg" else "webp")
            if (other.exists()) other.delete()
            FileOutputStream(target).use { it.write(bytes) }
            trim(target.parentFile)
            true
        } catch (e: Exception) {
            Log.w(TAG, "putFromBase64 failed for $msgId: ${e.message}")
            false
        }
    }

    /**
     * Чтение из кэша -> Bitmap (или null, если нет/битый). maxSide > 0 — downsample для миниатюр.
     * При попадании обновляем mtime файла — это делает trim настоящим LRU: выживают те
     * картинки, которые реально смотрят, а не просто давно скачанные.
     */
    fun getBitmap(context: Context, msgId: String, maxSide: Int = 0): Bitmap? {
        val f = file(context, msgId)
        if (!f.exists()) return null
        return try {
            val bmp = ImageUtils.decodeFile(f, maxSide)
            if (bmp != null) f.setLastModified(System.currentTimeMillis())
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "getBitmap failed for $msgId: ${e.message}")
            null
        }
    }

    private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** Настоящий LRU: сортировка по lastModified, который обновляется на каждый cache hit. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size <= MAX_ENTRIES) return
        files.take(files.size - MAX_ENTRIES).forEach { it.delete() }
    }
}
