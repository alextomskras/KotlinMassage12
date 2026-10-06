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
 *  - JPEG-сжатие с качеством JPEG_QUALITY
 * Итог: обычно 80..250 КБ сырых -> ~110..340 КБ в base64, что влезает в лимиты RTDB.
 */
object ImageUtils {

    private const val TAG = "ImageUtils"

    const val MAX_DIMENSION = 1280        // px по длинной стороне
    const val JPEG_QUALITY = 75           // 1..100
    const val BASE64_PREFIX = "data:image/jpeg;base64,"

    /** Читает Uri -> Bitmap c даунсемплом (не грузим полноразмерный JPEG в память). */
    private fun decodeSampledBitmap(context: Context, uri: Uri, reqSize: Int): Bitmap? {
        val optsNoBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, optsNoBounds)
        } ?: return null

        var sample = 1
        val maxSide = Math.max(optsNoBounds.outWidth, optsNoBounds.outHeight)
        while (maxSide / sample > reqSize * 2) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun resizeToMax(bitmap: Bitmap, maxSide: Int): Bitmap {
        val srcMax = Math.max(bitmap.width, bitmap.height)
        if (srcMax <= maxSide) return bitmap
        val scale = maxSide.toFloat() / srcMax
        val matrix = Matrix().apply { setScale(scale, scale) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Uri картинки -> base64 строка с префиксом data:image/jpeg;base64,...
     * @return null если файл не читается или пустой результат
     */
    fun compressToBase64(context: Context, uri: Uri): String? {
        val bmp = decodeSampledBitmap(context, uri, MAX_DIMENSION) ?: run {
            Log.e(TAG, "Не удалось декодировать изображение из $uri")
            return null
        }
        val scaled = resizeToMax(bmp, MAX_DIMENSION)

        val out = ByteArrayOutputStream(96 * 1024)
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val bytes = out.toByteArray()

        if (bytes.isEmpty()) return null
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        Log.d(TAG, "Изображение: ${bytes.size / 1024} КБ сырых, ${b64.length / 1024} КБ base64")
        return BASE64_PREFIX + b64
    }

    /** Обратное преобразование: base64 (с префиксом или без) -> Bitmap. */
    fun base64ToBitmap(data: String): Bitmap? {
        return try {
            val raw = if (data.startsWith(BASE64_PREFIX)) data.substring(BASE64_PREFIX.length) else data
            val bytes = Base64.decode(raw, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось раскодировать base64: ${e.message}")
            null
        }
    }

    /** Признак того, что текст сообщения — это base64-картинка. */
    fun isImagePayload(text: String?): Boolean =
        text != null && text.startsWith(BASE64_PREFIX)
}
