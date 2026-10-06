package com.xwray.groupie

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * Мини-реализация Groupie 2.x API (com.xwray.groupie.Item / ViewHolder / GroupAdapter).
 * Официальный groupie 2.1.0 был только на jcenter (мёртв), а JitPack в этом окружении
 * отдаёт 401 — поэтому локальный шим с идентичными сигнатурами, чтобы не переписывать
 * все экраны. Поведение: плоский список, bind по позиции, notifyDataSetChanged на мутациях
 * (для учебных объёмов сообщений более чем достаточно).
 */
open class ViewHolder(val itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
    val context: Context get() = itemView.context

    /**
     * Groupie передаёт позицию в bind(); у нашего ViewHolder она нужна для защиты
     * асинхронных колбэков (профиль/картинка) от переиспользования ячейки.
     */
    var boundPosition: Int = RecyclerView.NO_POSITION

    /**
     * Замена Kotlin Synthetics (kotlinx.android.synthetic, удалён из Kotlin 1.4+):
     * itemView.some_id -> itemView.findViewById(R.id.some_id).
     */
    operator fun get(name: String): android.view.View? =
        itemView.findViewById(ViewIdResolver.idOf(name))
}

abstract class Item<VH : ViewHolder> {
    abstract fun getLayout(): Int
    abstract fun bind(viewHolder: VH, position: Int)
    open fun createViewHolder(view: android.view.View): VH {
        @Suppress("UNCHECKED_CAST")
        return ViewHolder(view) as VH
    }
    open val id: Long get() = hashCode().toLong()
}

class GroupAdapter<VH : ViewHolder> : RecyclerView.Adapter<ViewHolder>() {

    private val items = mutableListOf<Item<*>>()

    private var onItemClickListener: ((Item<*>, android.view.View) -> Unit)? = null
    fun setOnItemClickListener(listener: (Item<*>, android.view.View) -> Unit) {
        onItemClickListener = listener
    }

    fun add(item: Item<*>) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    /**
     * Дифф-обновление списка. Точечные уведомления (inserted/removed) выпускаются только
     * когда изменены ИСКЛЮЧИТЕЛЬНО хвостовые позиции — тогда старые индексы валидны для
     * notifyItemRange*. Любые другие варианты (добавление/замена в середине, перестановка)
     * безопасно уходят в notifyDataSetChanged(). Это чинит O(n^2) пересборку всего списка
     * на каждое новое сообщение в LatestMessagesActivity.
     */
    fun updateWithDiff(newItems: List<Item<*>>) {
        val old = ArrayList<Item<*>>(items)
        items.clear()
        items.addAll(newItems)

        // длина изменилась только за счёт хвоста, и префикс совпадает по id -> точечное уведомление
        val minLen = Math.min(old.size, newItems.size)
        var common = 0
        while (common < minLen && old[common].id == newItems[common].id) common++

        val tailOnlyChange =
            (newItems.size > old.size && common == old.size) ||   // добавлены элементы в конец
            (old.size > newItems.size && common == newItems.size) // удалён хвост
        if (tailOnlyChange) {
            if (newItems.size > old.size) {
                notifyItemRangeInserted(old.size, newItems.size - old.size)
            } else {
                notifyItemRangeRemoved(newItems.size, old.size - newItems.size)
            }
            return
        }
        if (old.size == newItems.size && common == old.size) return // идентично, ничего не меняем
        notifyDataSetChanged()
    }

    fun update(newItems: List<Item<*>>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun clear() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutId = items[firstNonNegative(viewType)].getLayout()
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        @Suppress("UNCHECKED_CAST")
        return (items[firstNonNegative(viewType)] as Item<ViewHolder>).createViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.boundPosition = position
        @Suppress("UNCHECKED_CAST")
        (items[position] as Item<ViewHolder>).bind(holder, position)
        holder.itemView.setOnClickListener {
            val idx = holder.bindingAdapterPosition
            if (idx != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                onItemClickListener?.invoke(items[idx], holder.itemView)
            }
        }
    }

    // viewType == позиция при стабильных ID запрещаем; используем позицию как viewType
    override fun getItemViewType(position: Int): Int = position

    private fun firstNonNegative(v: Int) = if (v >= 0) v else 0
}
