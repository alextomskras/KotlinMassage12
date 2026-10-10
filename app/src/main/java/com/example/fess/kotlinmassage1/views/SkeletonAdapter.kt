package com.example.fess.kotlinmassage1.views

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.fess.kotlinmassage1.R

/**
 * Индикатор загрузки списка чатов (пункт 12): набор shimmer-скелетонов вместо
 * пустого белого экрана, пока Firebase тянет latest-messages и профили.
 *
 * Используется как ВТОРОЙ adapter на отдельном RecyclerView-оверлее
 * (см. activity_latest_messages.xml): реальный список появляется только когда
 * пришли первые данные, поэтому layout реальных строк не затрагивается и
 * риск регрессий нулевой. Анимация бликов живёт внутри ShimmerConstraintLayout
 * и останавливается вместе с detach overlay-списка.
 */
class SkeletonAdapter(private val rowCount: Int = DEFAULT_ROWS) :
    RecyclerView.Adapter<SkeletonAdapter.SkeletonViewHolder>() {

    class SkeletonViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SkeletonViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dialog_skeleton, parent, false)
        return SkeletonViewHolder(view)
    }

    override fun onBindViewHolder(holder: SkeletonViewHolder, position: Int) {
        // Данные не нужны: все child-view — серые прямоугольники из layout.
    }

    override fun getItemCount(): Int = rowCount

    companion object {
        /** ~4 строки: заполняют экран, но не «кричат» о несуществующих диалогах. */
        private const val DEFAULT_ROWS = 4
    }
}
