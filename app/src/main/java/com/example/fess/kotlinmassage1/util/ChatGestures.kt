package com.example.fess.kotlinmassage1.util

import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.fess.kotlinmassage1.R
import com.example.fess.kotlinmassage1.views.ChatRowDelegate

/**
 * Пункт 13: жесты в списке чата.
 *
 *  - Свайп ВПРАВО  -> «Ответить» (reply) — выделяем строку-источник, показываем
 *    цитату над полем ввода (колбэк onReply с позицией и делегатом строки).
 *  - Свайп ВЛЕВО   -> для картинок открывает полноэкранный просмотр (hero),
 *    для текста — ничего (удаление остаётся в long-press меню, чтобы свайп
 *    не сжигал данные молча).
 *
 * Свайп НЕ удаляет сообщение: после срабатывания строка возвращается на место
 * через notifyItemChanged с payload (без полной пересборки списка).
 */
class ChatSwipeCallback(
    private val onReply: (position: Int, item: ChatRowDelegate) -> Unit,
    private val onOpenImage: (position: Int, item: ChatRowDelegate) -> Unit
) : ItemTouchHelper.SimpleCallback(
    0, // без drag&drop
    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
) {

    /** RV запоминаем сами: protected-поля recyclerView у Callback нет (package-private). */
    private var attachedRv: RecyclerView? = null

    override fun attachToRecyclerView(recyclerView: RecyclerView?) {
        super.attachToRecyclerView(recyclerView)
        attachedRv = recyclerView
    }

    private var replyBg: ColorDrawable? = null
    private var imageBg: ColorDrawable? = null
    private var iconSizePx = -1f
    private var marginPx = -1f

    private fun ensureMetrics(rv: RecyclerView) {
        if (iconSizePx < 0f) {
            val d = rv.context.resources.displayMetrics.density
            iconSizePx = 28f * d
            marginPx = 16f * d
        }
    }

    override fun onMove(
        rv: RecyclerView,
        vh: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false // перетаскивание запрещено

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
        val pos = vh.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        // ItemTouchHelper.Callback хранит RecyclerView после attachToRecyclerView —
        // достаем адаптер через reflection-free способ: itemView прикреплён к RV.
        val adapter = (vh.itemView.parent as? RecyclerView)?.adapter
            as? com.example.fess.kotlinmassage1.views.ChatRecyclerAdapter
        val item = adapter?.itemAt(pos)
        when {
            direction == ItemTouchHelper.RIGHT && item != null -> onReply(pos, item)
            direction == ItemTouchHelper.LEFT &&
                item is com.example.fess.kotlinmassage1.views.KartinkaFromItem -> onOpenImage(pos, item)
            direction == ItemTouchHelper.LEFT &&
                item is com.example.fess.kotlinmassage1.views.KartinkaToItem -> onOpenImage(pos, item)
        }
        // Откатываем строку на место (данные не удалялись — только жест).
        // Payload != null => адаптер может отличить «откат свайпа» от реального
        // изменения; bind пройдёт как обычно.
        adapter?.notifyItemChanged(pos, PAYLOAD_SWIPE_REVERT)
    }

    /** Подложка с иконкой под уезжающей строкой (без сторонних библиотек). */
    override fun onDraw(
        c: Canvas,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        // ВАЖНО: ItemTouchHelper.Callback.onDraw — package-private, super не вызываем.
        if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) return
        val rv = attachedRv ?: return
        ensureMetrics(rv)
        val bg = when {
            dX > 0 -> replyDrawable(rv)
            dX < 0 -> imageDrawable(rv)
            else -> return
        } ?: return
        drawOverlay(c, bg, viewHolder.itemView, dX, rv)
    }

    private fun drawOverlay(
        c: Canvas,
        bg: ColorDrawable,
        itemView: android.view.View,
        dX: Float,
        rv: RecyclerView
    ) {
        bg.setBounds(
            itemView.left, itemView.top,
            itemView.right, itemView.bottom
        )
        bg.draw(c)

        val cy = itemView.top + (itemView.bottom - itemView.top) / 2f
        val cx = if (dX > 0) itemView.left + marginPx + iconSizePx / 2f
                 else itemView.right - marginPx - iconSizePx / 2f
        val iconRes = if (dX > 0) R.drawable.ic_reply_gesture else R.drawable.ic_fullscreen_gesture
        val icon = ContextCompat.getDrawable(rv.context, iconRes) ?: return
        icon.colorFilter = android.graphics.PorterDuffColorFilter(
            ContextCompat.getColor(rv.context, R.color.swipe_white), PorterDuff.Mode.SRC_IN
        )
        icon.setBounds(
            (cx - iconSizePx / 2f).toInt(), (cy - iconSizePx / 2f).toInt(),
            (cx + iconSizePx / 2f).toInt(), (cy + iconSizePx / 2f).toInt()
        )
        icon.draw(c)
    }

    private fun replyDrawable(rv: RecyclerView): ColorDrawable? {
        if (replyBg == null) {
            replyBg = ColorDrawable(ContextCompat.getColor(rv.context, R.color.reply_accent))
        }
        return replyBg
    }

    private fun imageDrawable(rv: RecyclerView): ColorDrawable? {
        if (imageBg == null) {
            imageBg = ColorDrawable(ContextCompat.getColor(rv.context, R.color.swipe_image_background))
        }
        return imageBg
    }

    /** Блокируем свайп-влево у текстовых строк (у картинок — просмотр). */
    override fun getSwipeDirs(
        rv: RecyclerView,
        vh: RecyclerView.ViewHolder
    ): Int {
        val pos = vh.bindingAdapterPosition
        val item = (rv.adapter as? com.example.fess.kotlinmassage1.views.ChatRecyclerAdapter)
            ?.itemAt(pos)
        return if (item is com.example.fess.kotlinmassage1.views.KartinkaFromItem ||
                  item is com.example.fess.kotlinmassage1.views.KartinkaToItem
        ) {
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        } else {
            ItemTouchHelper.RIGHT // текст: только «ответить»
        }
    }


    companion object {
        const val PAYLOAD_SWIPE_REVERT = "swipe_revert"

        /** Навешивает свайпы на RecyclerView чата. Возвращает контроллер. */
        fun attach(
            recyclerView: RecyclerView,
            onReply: (position: Int, item: ChatRowDelegate) -> Unit,
            onOpenImage: (position: Int, item: ChatRowDelegate) -> Unit
        ): ItemTouchHelper {
            val helper = ItemTouchHelper(ChatSwipeCallback(onReply, onOpenImage))
            helper.attachToRecyclerView(recyclerView)
            return helper
        }
    }
}
