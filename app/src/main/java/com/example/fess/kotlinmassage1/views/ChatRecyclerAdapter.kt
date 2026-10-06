package com.example.fess.kotlinmassage1.views

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.fess.kotlinmassage1.R
import com.example.fess.kotlinmassage1.models.ChatMessage
import com.example.fess.kotlinmassage1.models.User

/**
 * Обычный RecyclerView.Adapter вместо локального шима Groupie (com.xwray.groupie).
 * Groupie для этого проекта мёртв (jcenter закрыт, JitPack 401), а шим постоянно
 * ломался: IllegalAccessError на телефоне, "Cannot create an instance of an abstract
 * class" при сборке. Здесь только стандартные компоненты AndroidX.
 *
 * Все строки реализуют ChatRowDelegate; layoutRes() у каждой строки свой, поэтому
 * разные типы ячеек в одном списке работают корректно.
 */
class ChatRecyclerAdapter(
    private val items: MutableList<ChatRowDelegate> = mutableListOf()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onItemClickListener: ((ChatRowDelegate) -> Unit)? = null

    fun submit(newItems: List<ChatRowDelegate>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun append(item: ChatRowDelegate) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    override fun getItemCount(): Int = items.size

    // уникальный viewType каждой строки -> корректный пул переиспользования
    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val delegate = items[viewType.coerceIn(0, items.size - 1)]
        val view = LayoutInflater.from(parent.context)
            .inflate(delegate.layoutRes(), parent, false)
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // защита асинхронных колбэков от переиспользования ячейки (аналог boundPosition)
        holder.itemView.setTag(R.id.tag_bound_position, position)
        items[position].bindTo(holder, position)
        holder.itemView.setOnClickListener {
            val idx = holder.bindingAdapterPosition
            if (idx != RecyclerView.NO_POSITION && idx < items.size) {
                onItemClickListener?.invoke(items[idx])
            }
        }
    }
}

/** Контракт строки списка для ChatRecyclerAdapter (без зависимости от groupie API). */
interface ChatRowDelegate {
    fun layoutRes(): Int
    fun bindTo(holder: RecyclerView.ViewHolder, position: Int)
    /** Собеседник по клику (для списка диалогов). */
    val chatPartnerUser: User?
}

/** Тип сообщения -> строка лога чата (было в ChatLogActivity.buildChatItem). */
fun chatItemFor(chatMessage: ChatMessage, user: User, isIncoming: Boolean, timeStr: String): ChatRowDelegate = when {
    chatMessage.type == ChatMessage.TYPE_IMAGE && isIncoming ->
        KartinkaToItem(chatMessage.text, user, timeStr)
    chatMessage.type == ChatMessage.TYPE_IMAGE ->
        KartinkaFromItem(chatMessage.text, user, timeStr)
    isIncoming -> ChatToItem(chatMessage.text, user, timeStr)
    else -> ChatFromItem(chatMessage.text, user, timeStr)
}
