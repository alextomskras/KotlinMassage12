package com.example.fess.kotlinmassage1.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Утилиты для работы с картинками внутри сообщений.
 *
 * Картинки храним прямо в Realtime Database в виде base64 (без Firebase Storage),
 * поэтому перед кодированием обязательно жмём:
 *  - ресайз по длинной стороне до MAX_DIMENSION px
 *  - WebP-сжатие с качеством WEBP_QUALITY (lossy WebP поддерживается Android с API 17,
 *    наш minSdk 21 — значит доступен везде; при сбое кодируем JPEG, как раньше)
 * Экономия против JPEG q75 — примерно 25..35% байт при том же качестве.
 * Старые сообщения с data:image/jpeg продолжают читаться: декодер определяет формат
 * по сигнатуре байт, а не по префиксу.
 */
object ImageUtils {

    private const val TAG = "ImageUtils"

    const val MAX_DIMENSION = 1280        // px по длинной стороне
    const val WEBP_QUALITY = 80           // 1..100
    /** Качество fallback-JPEG: чуть выше, т.к. JPEG при равном размере шумнее. */
    const val JPEG_QUALITY = 82           // 1..100
    const val BASE64_PREFIX = "data:image/webp;base64,"
    /** Префикс старых сообщений (и fallback-нового кодера). */
    const val JPEG_BASE64_PREFIX = "data:image/jpeg;base64,"

    /** Жёсткий лимит на одну картинку в RTDB (base64-строка), чтобы не раздувать базу. */
    const val MAX_BASE64_BYTES = 10 * 1024 * 1024   // 10 МБ base64 (~7.3 МБ бинарного изображения)

    /** Результат сжатия: base64 или понятная причина отказа. */
    sealed class CompressResult {
        data class Ok(val base64: String) : CompressResult()
        data class TooLarge(val base64Bytes: Int) : CompressResult()
        object Failed : CompressResult()
    }

    /**
     * Копирует поток из ContentResolver целиком в memory.
     * Нужно потому, что openInputStream() у content:// URI может вернуть null или
     * пустой поток без исключения (Samsung Gallery / Google Photos на Android 13+),
     * а также чтобы не держать провайдер занятым между двумя декодами.
     */
    private fun readBytes(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Поток не читается ($uri): ${e.message}")
            null
        }
    }

    /** Читает Uri -> Bitmap c даунсемплом (не грузим полноразмерный JPEG в память). */
    private fun decodeSampledBitmap(context: Context, uri: Uri, reqSize: Int): Bitmap? {
        val bytes = readBytes(context, uri) ?: return null

        val optsNoBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, optsNoBounds)
        if (optsNoBounds.outWidth <= 0 || optsNoBounds.outHeight <= 0) {
            Log.e(TAG, "Байты прочитаны (${bytes.size} B), но это не изображение: $uri")
            return null
        }

        var sample = 1
        val maxSide = Math.max(optsNoBounds.outWidth, optsNoBounds.outHeight)
        while (maxSide / sample > reqSize * 2) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun resizeToMax(bitmap: Bitmap, maxSide: Int): Bitmap {
        val srcMax = Math.max(bitmap.width, bitmap.height)
        if (srcMax <= maxSide) return bitmap
        val scale = maxSide.toFloat() / srcMax
        val matrix = Matrix().apply { setScale(scale, scale) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Кодирует Bitmap по ступеням разрешения/качества: сначала WebP (основной формат),
     * если WebP-энкодер почему-то вернул пусто (редкие ROM) — JPEG-fallback.
     * @return пара (data-URI префикс, base64) либо null, если ни одна ступень не влезла
     * в MAX_BASE64_BYTES.
     */
    private fun encodeWithLadder(bmp: Bitmap): Pair<String, String>? {
        // Ступени сжатия: от штатной к агрессивной. Первая почти всегда достаточна.
        val ladder = listOf(
            MAX_DIMENSION to WEBP_QUALITY,          // 1280px q80 webp
            960 to 70,                              // запасной вариант
            720 to 60                               // последний шанс
        )

        for ((dim, quality) in ladder) {
            val scaled = resizeToMax(bmp, dim)
            // 1) пробуем WebP
            var out = ByteArrayOutputStream(96 * 1024)
            var ok = scaled.compress(Bitmap.CompressFormat.WEBP, quality, out)
            var bytes = out.toByteArray()
            var prefix = BASE64_PREFIX
            if (!ok || bytes.isEmpty()) {
                // 2) fallback: JPEG (устройства без рабочего webp-энкодера)
                Log.w(TAG, "WebP-кодер вернул пусто, фолбэк на JPEG (${dim}px q$quality)")
                out = ByteArrayOutputStream(96 * 1024)
                ok = scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bytes = out.toByteArray()
                prefix = JPEG_BASE64_PREFIX
            }
            if (scaled !== bmp) scaled.recycle()
            if (!ok || bytes.isEmpty()) continue

            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            Log.d(TAG, "Изображение (${if (prefix == BASE64_PREFIX) "webp" else "jpeg"}, ${dim}px q$quality): " +
                    "${bytes.size / 1024} КБ сырых, ${b64.length / 1024} КБ base64")
            if (b64.length <= MAX_BASE64_BYTES) {
                return prefix to b64
            }
        }
        // Даже последняя ступень не влезла (бывает с аномальными источниками) — отказ.
        return null
    }

    /**
     * Uri картинки -> base64 строка с префиксом data:image/webp;base64,...
     * (data:image/jpeg — только если WebP-энкодер недоступен на устройстве).
     * Возвращает CompressResult: Ok(base64) | TooLarge | Failed.
     * Если после первого прохода base64 больше MAX_BASE64_BYTES — автоматически
     * пережимает с меньшим размером/качеством; при невозможности уложиться — TooLarge.
     */
    fun compressToResult(context: Context, uri: Uri): CompressResult {
        val bmp = decodeSampledBitmap(context, uri, MAX_DIMENSION) ?: run {
            Log.e(TAG, "Не удалось прочитать/декодировать изображение из $uri (см. логи выше: поток или формат)")
            return CompressResult.Failed
        }

        val encoded = encodeWithLadder(bmp) ?: return CompressResult.TooLarge(0)
        return CompressResult.Ok(encoded.first + encoded.second)
    }

    /**
     * Uri картинки -> base64 строка с data-URI префиксом (webp; jpeg — fallback).
     * @return null если файл не читается, пустой результат или не влезает в лимит 10 МБ.
     */
    fun compressToBase64(context: Context, uri: Uri): String? {
        return when (val r = compressToResult(context, uri)) {
            is CompressResult.Ok -> r.base64
            is CompressResult.TooLarge -> {
                Log.e(TAG, "Картинка не прошла в лимит ${MAX_BASE64_BYTES / 1024 / 1024} МБ даже после максимального сжатия")
                null
            }
            CompressResult.Failed -> null
        }
    }

    /**
     * Обратное преобразование: base64 data-URI (webp/jpeg, с префиксом или без) -> Bitmap.
     * Формат определяется по сигнатуре байт (BitmapFactory), поэтому старые JPEG-сообщения
     * и новые WebP декодируются одним путём. Префикс снимаем для любого image/* типа.
     */
    fun base64ToBitmap(data: String): Bitmap? {
        return try {
            val raw = if (data.startsWith("data:image")) data.substringAfter("base64,", data)
                      else if (data.startsWith(BASE64_PREFIX)) data.substring(BASE64_PREFIX.length)
                      else data
            val bytes = Base64.decode(raw, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось раскодировать base64: ${e.message}")
            null
        }
    }

    /** Признак того, что текст сообщения — это base64-картинка (webp или legacy jpeg). */
    fun isImagePayload(text: String?): Boolean =
        text != null && text.startsWith("data:image")
}
