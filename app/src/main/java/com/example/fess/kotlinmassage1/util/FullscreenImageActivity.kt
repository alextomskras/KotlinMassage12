package com.example.fess.kotlinmassage1.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.fess.kotlinmassage1.R
import com.squareup.picasso.Picasso

/**
 * Полноэкранный просмотр картинки чата — «как в WhatsApp», но как Activity,
 * а не Dialog: это даёт возможность hero-перехода (SharedElementTransition)
 * от миниатюры в пузыре до полноэкранного изображения.
 *
 *  - зум/панорамирование жестами (ZoomableImageView);
 *  - тап по фону или по картинке закрывает экран (с обратной hero-анимацией);
 *  - источник тот же, что у FullscreenImageDialog: локальный кэш ImageCache,
 *    затем base64-payload / E2EE-конверт / Picasso для legacy-URL.
 */
class FullscreenImageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRANSFER_REF = "transfer_ref"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_CAPTION = "caption"
        const val EXTRA_ENV_JSON = "env_json"
        const val EXTRA_IS_OUTGOING = "is_outgoing"

        fun intent(
            context: Context,
            transferRef: String?,
            payload: String?,
            caption: String? = null,
            envJson: String? = null,
            isOutgoing: Boolean = false
        ): Intent = Intent(context, FullscreenImageActivity::class.java).apply {
            putExtra(EXTRA_TRANSFER_REF, transferRef)
            putExtra(EXTRA_PAYLOAD, payload)
            putExtra(EXTRA_CAPTION, caption)
            putExtra(EXTRA_ENV_JSON, envJson)
            putExtra(EXTRA_IS_OUTGOING, isOutgoing)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullscreen_image)

        // У Window нет свойств sharedElementsEnterTransition/sharedElementsExitTransition
        // (отсюда Unresolved reference). Shared-element'ы анимируются штатными переходами
        // окна: AutoTransition (включает ChangeTransform — масштаб/позицию hero-картинки)
        // плюс Fade для появления остального контента.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.enterTransition = android.transition.AutoTransition().apply {
                duration = 300
                addTransition(android.transition.Fade())
            }
            window.returnTransition = android.transition.AutoTransition().apply {
                duration = 250
                addTransition(android.transition.Fade())
            }
        }

        val scrim = findViewById<View>(R.id.fullscreen_scrim)
        scrim.animate().alpha(1f).setDuration(if (isChangingConfigurations) 0 else 250).start()

        val image = findViewById<ZoomableImageView>(R.id.fullscreen_image)
        val container = findViewById<View>(R.id.fullscreen_container)

        // закрытие по тапу мимо картинки
        container.setOnClickListener { closeWithHero() }

        // закрытие по одиночному тапу на саму картинку; даблтап остаётся зумом
        image.onSingleTapListener = { closeWithHero() }

        val transferRef = intent.getStringExtra(EXTRA_TRANSFER_REF)
        val payload = intent.getStringExtra(EXTRA_PAYLOAD)
        val envJson = intent.getStringExtra(EXTRA_ENV_JSON)
        val isOutgoing = intent.getBooleanExtra(EXTRA_IS_OUTGOING, false)

        ImageLoader.loadFullBitmap(this, transferRef, payload, envJson, isOutgoing) { bmp ->
            if (isFinishing) return@loadFullBitmap
            when {
                bmp != null -> image.setImageBitmap(bmp)
                !payload.isNullOrEmpty() && !ImageUtils.isImagePayload(payload) ->
                    Picasso.get().load(payload).into(image) // legacy URL
                else -> closeWithHero() // тела нет и в кэше тоже
            }
        }

        val cap = findViewById<TextView>(R.id.fullscreen_caption)
        val caption = intent.getStringExtra(EXTRA_CAPTION)
        if (!caption.isNullOrEmpty()) {
            cap.text = caption
            cap.visibility = View.VISIBLE
        }
    }

    /** Закрытие: на 5+ — с обратной hero-анимацией shared-element. */
    private fun closeWithHero() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAfterTransition()
        } else {
            finish()
        }
    }
}
