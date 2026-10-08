package com.example.fess.kotlinmassage1.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Локальный кэш картинок чата (по messageId).
 *
 * Картинка в RTDB живёт только 7 дней (relay-зона /transfers), поэтому скачанное
 * сохраняем на телефоне: история чата переживает очистку трансфера.
 * Файлы лежат в cacheDir/chat_images/<msgId>.jpg, итоговый размер <= ~500 КБ
 * (см. ImageUtils.ladder), лимит кэша — MAX_ENTRIES записей (LRU по mtime).
 */
object ImageCache {

    private const val TAG = "ImageCache"
    private const val DIR_NAME = "chat_images"
    private const val MAX_ENTRIES = 300

    /** Имя файла без расширения — одно и то же для всех форматов. */
    private fun baseName(msgId: String): String = sanitize(msgId)

    /** Актуальный файл кэша (webp — новый формат, jpg — legacy). */
    fun file(context: Context, msgId: String): File {
        val dir = context.cacheDir.resolve(DIR_NAME)
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
            val dir = context.cacheDir.resolve(DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            // расширение — по фактическим байтам (RIFF....WEBP vs JPEG SOI)
            val ext = if (bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                           String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") "webp" else "jpg"
            val target = File(dir, baseName(msgId) + "." + ext)
            // если раньше был кэш в другом расширении — убираем дубликат
            val other = File(dir, baseName(msgId) + "." + if (ext == "webp") "jpg" else "webp")
            if (other.exists()) other.delete()
            FileOutputStream(target).use { it.write(bytes) }
            trim(dir)
            true
        } catch (e: Exception) {
            Log.w(TAG, "putFromBase64 failed for $msgId: ${e.message}")
            false
        }
    }

    /** Чтение из кэша -> Bitmap (или null, если нет/битый). */
    fun getBitmap(context: Context, msgId: String): Bitmap? {
        val f = file(context, msgId)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "getBitmap failed for $msgId: ${e.message}")
            null
        }
    }

    private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** Простой LRU: удаляем самые старые файлы сверх лимита. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size <= MAX_ENTRIES) return
        files.take(files.size - MAX_ENTRIES).forEach { it.delete() }
    }
}
