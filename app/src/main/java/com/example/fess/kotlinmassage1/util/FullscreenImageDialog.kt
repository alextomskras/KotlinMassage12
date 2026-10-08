package com.example.fess.kotlinmassage1.util

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import com.example.fess.kotlinmassage1.R

/**
 * Полноэкранный просмотр картинки чата — «как в WhatsApp»:
 *  - открывается по тапу на миниатюре;
 *  - зум/панорамирование жестами (ZoomableImageView);
 *  - тап по фону (мимо самой картинки) закрывает окно.
 *
 * Источник картинки тот же, что и в пузыре чата: сначала локальный кэш ImageCache
 * (мгновенно), затем base64-payload из сообщения /Picasso для legacy-URL.
 */
class FullscreenImageDialog(
    context: Context,
    private val transferRef: String?,
    private val payload: String?,
    private val caption: String? = null
) : Dialog(context, R.style.Theme_FullscreenImage) {

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setContentView(R.layout.dialog_fullscreen_image)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val image = findViewById<ZoomableImageView>(R.id.fullscreen_image)
        val container = findViewById<ViewGroup>(R.id.fullscreen_container)

        // закрытие по тапу мимо картинки (по самой картинке — см. onSingleTapListener ниже)
        container.setOnClickListener { dismiss() }

        // закрытие по одиночному тапу на саму картинку — как в WhatsApp.
        // onDoubleTap в ZoomableImageView при этом остаётся зумом:
        // GestureDetector ждёт подтверждения, что это не даблтап.
        image.onSingleTapListener = { dismiss() }

        ImageLoader.loadFullBitmap(context, transferRef, payload) { bmp ->
            if (!isShowing()) return@loadFullBitmap
            when {
                bmp != null -> image.setImageBitmap(bmp)
                !payload.isNullOrEmpty() && !ImageUtils.isImagePayload(payload) ->
                    com.squareup.picasso.Picasso.get().load(payload).into(image) // legacy URL
                else -> dismiss() // тела нет и в кэша тоже
            }
        }

        val cap = findViewById<TextView>(R.id.fullscreen_caption)
        if (!caption.isNullOrEmpty()) {
            cap.text = caption
            cap.visibility = android.view.View.VISIBLE
        }
    }
}
