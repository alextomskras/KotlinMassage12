package com.example.fess.kotlinmassage1.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.core.app.ActivityOptionsCompat
import androidx.core.util.Pair

/**
 * Hero-переход (SharedElementTransition) для полноэкранного просмотра картинки чата.
 *
 * Миниатюра в пузыре «разлетается» в полноэкранный экран: система анимирует
 * позицию и масштаб shared-element'а (самого ImageView), фон затемняется отдельно.
 *
 * Требования:
 *  - у миниатюры должен быть уникальный transitionName (см. [armHero]);
 *  - тема FullscreenImageActivity — прозрачное окно (windowIsTranslucent),
 *    иначе переход не сработает на Android 9 и ниже;
 *  - Android 5+ (API 21). На более старых — обычный запуск без анимации.
 */
object HeroTransition {

    /** Уникальный transitionName для миниатюры (по msgId/transferRef, чтобы не было коллизий в списке). */
    fun armHero(view: View, key: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val name = if (!key.isNullOrEmpty()) "chat_image_$key"
                   else "chat_image_${System.identityHashCode(view)}_${view.hashCode()}"
        view.transitionName = name
    }

    /** Запуск [FullscreenImageActivity] с hero-анимацией от [source]. */
    fun launch(
        activity: Activity,
        source: View,
        transferRef: String?,
        payload: String?,
        caption: String? = null,
        envJson: String? = null,
        isOutgoing: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || source.transitionName == null) {
            // Нет shared-element — просто открываем экран.
            activity.startActivity(FullscreenImageActivity.intent(activity, transferRef, payload, caption, envJson, isOutgoing))
            return
        }
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
            activity, Pair(source, source.transitionName)
        )
        activity.startActivity(
            FullscreenImageActivity.intent(activity, transferRef, payload, caption, envJson, isOutgoing),
            options.toBundle()
        )
    }

    /** Затемнение фона при старте перехода (фон fullscreen-активности прозрачный). */
    fun scrimColor(): Int = Color.parseColor("#DD000000")
}
