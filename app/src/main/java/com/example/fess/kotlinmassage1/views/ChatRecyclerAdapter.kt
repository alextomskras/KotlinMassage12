package com.example.fess.kotlinmassage1.views

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

    /** Долгое нажатие на строку (позиция + делегат) — меню «Изменить/Удалить». */
    var onItemLongClickListener: ((Int, ChatRowDelegate) -> Boolean)? = null

    /** Источник контекста активити для строк (нужен локальному кэшу картинок). */
    var rowContextProvider: (() -> android.content.Context?)? = null

    fun append(item: ChatRowDelegate) {
        item.rowContext = rowContextProvider?.invoke()
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    /** Все строки (для массового заполнения reply-карты при старом submit()). */
    val currentItems: List<ChatRowDelegate> get() = items

    /** Точечное обновление строки (readAt / deleted / edited) без пересборки всего списка. */
    fun updateAt(position: Int) {
        if (position in items.indices) notifyItemChanged(position)
    }

    /** Позиция исходящего текстового сообщения по его msgId (-1 если не найдено). */
    fun findOutgoingTextPosition(msgId: String): Int {
        for (i in items.indices) {
            val it = items[i]
            if (it is ChatFromItem && it.msgId == msgId) return i
        }
        return -1
    }

    /**
     * Все исходящие строки, у которых бывает галочка статуса: legacy-текст
     * (ChatFromItem), E2EE-текст (TextItem, только исходящий — входящий рисует
     * чат без тика) и картинки от нас (KartinkaFromItem). Нужен read-tracker'у
     * для массового/точечного проставления readAt.
     */
    fun outgoingReadRows(): List<Pair<Int, ReadTickRow>> =
        items.mapIndexedNotNull { i, d ->
            when {
                d is ChatFromItem -> i to d
                d is KartinkaFromItem -> i to d
                d is TextItem && !d.isIncomingForMenu() -> i to d
                else -> null
            }
        }

    /** Все исходящие текстовые строки (оставлено для совместимости вызовов). */
    fun outgoingTextItems(): List<Pair<Int, ChatFromItem>> =
        items.mapIndexedNotNull { i, d -> if (d is ChatFromItem) i to d else null }

    /** Строка по позиции (для long-press меню). */
    fun itemAt(position: Int): ChatRowDelegate? = items.getOrNull(position)

    // ===== Пункт 13: swipe reply =====
    /** dbId строки -> (msgId источника для цитаты, превью текста <=90 симв.). */
    private val replyMap = HashMap<String, Pair<String, String>>()

    /** Зарегистрировать строку как возможный источник ответа (свайп вправо). */
    fun registerReplySource(dbId: String, msgId: String, preview: String) {
        if (dbId.isEmpty()) return
        replyMap[dbId] = msgId to preview.take(90)
    }

    /** Источник ответа по dbId (из свайпа); null если строка не отвечает. */
    fun replySourceFor(dbId: String): Pair<String, String>? = replyMap[dbId]

    /** Проставить цитату строке-ответчику при добавлении (replyToId из БД). */
    fun applyReply(item: ChatRowDelegate, replyToId: String?) {
        if (item is ReplyQuoteRow && !replyToId.isNullOrEmpty()) {
            val preview = replyMap[replyToId]?.second
                ?: (item.replyPreview ?: "")   // fallback из БД, если источника нет в списке
            if (preview.isNotEmpty()) item.replyPreview = preview
        }
    }

    /** Позиция строки по ключу узла В ЗЕРКАЛЕ ТЕКУЩЕГО ПОЛЬЗОВАТЕЛЯ (dbId). */
    fun findRowPosition(dbId: String): Int {
        for (i in items.indices) {
            val found = when (val d = items[i]) {
                is TextItem -> d.dbId == dbId
                is DeletedTextItem -> d.dbId == dbId
                is ChatFromItem -> d.dbId == dbId
                is ChatToItem -> d.dbId == dbId
                is KartinkaFromItem -> d.dbId == dbId
                is KartinkaToItem -> d.dbId == dbId
                else -> false
            }
            if (found) return i
        }
        return -1
    }

    fun submit(newItems: List<ChatRowDelegate>) {
        val ctx = rowContextProvider?.invoke()
        newItems.forEach { it.rowContext = ctx }
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
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
        holder.itemView.setOnLongClickListener {
            val idx = holder.bindingAdapterPosition
            if (idx != RecyclerView.NO_POSITION && idx < items.size) {
                val cb = onItemLongClickListener
                if (cb != null) return@setOnLongClickListener cb(idx, items[idx])
            }
            false
        }
    }
}

/** Контракт строки списка для ChatRecyclerAdapter (без зависимости от groupie API). */
interface ChatRowDelegate {
    fun layoutRes(): Int
    fun bindTo(holder: RecyclerView.ViewHolder, position: Int)
    /** Собеседник по клику (для списка диалогов). */
    val chatPartnerUser: User?

    /** Контекст для локального кэша картинок (передаёт активити; null = без кэша). */
    var rowContext: android.content.Context?

    /** Открытый текст для long-press меню («Копировать»); null — копировать нечего. */
    fun plainTextForMenu(): String? = null

    /** Текст строки (для превью цитаты при swipe reply). Пусто, если текста нет. */
    fun rowText(): String = ""

    /** id сообщения в зеркале автора (для transitionName/ключа цитаты). */
    fun rowMsgId(): String = ""

    /** dbId строки (ключ узла в нашем зеркале) — для reply-карты. Пусто, если нет. */
    fun rowDbId(): String = ""
}

/**
 * Строка, умеющая рисовать цитату «Ответ на: …» (пункт 13, swipe reply).
 * Плашка лежит ВНЕ пузыря со ссылкой textview_reply_quote (видна только с цитатой).
 */
abstract class ReplyQuoteRow : ChatRowDelegate {
    /** Превью сообщения-источника; null/пусто -> плашка скрыта. */
    abstract var replyPreview: String?

    protected fun renderQuote(viewHolder: RecyclerView.ViewHolder) {
        val q = viewHolder.itemView.findViewById<TextView>(R.id.textview_reply_quote) ?: return
        val preview = replyPreview
        if (!preview.isNullOrEmpty()) {
            q.text = q.context.getString(R.string.reply_to_prefix, preview)
            q.visibility = View.VISIBLE
        } else {
            q.visibility = View.GONE
        }
    }
}

/** Строка исходящего сообщения с галочкой статуса (readAt > 0 => две синие). */
interface ReadTickRow {
    var readAt: Long
    /** id сообщения в зеркале автора — fallback-ключ для read-tracker'а. */
    var msgId: String
}

/** Тип сообщения -> строка лога чата (было в ChatLogActivity.buildChatItem). */
fun chatItemFor(chatMessage: ChatMessage, user: User, isIncoming: Boolean, timeStr: String): ChatRowDelegate = when {
    // Soft-delete: любой тип -> одна и та же заглушка (контент не показываем).
    chatMessage.deleted -> DeletedTextItem(timeStr, user, isIncoming).apply { dbId = chatMessage.id }
    chatMessage.type == ChatMessage.TYPE_IMAGE && isIncoming ->
        // payload = text: для E2EE-картинок в text лежит base64 КОНВЕРТА {enc,epk,alg},
        // а не картинки — isImagePayload(text) вернёт false, так что ложное срабатывание
        // legacy-ветки исключено; для старых незашифрованных сообщений с base64 в тексте
        // это единственный путь показа.
        KartinkaToItem(chatMessage.text, user, timeStr, msgId = chatMessage.id, transferRef = chatMessage.transferRef ?: (if (chatMessage.type == com.example.fess.kotlinmassage1.models.ChatMessage.TYPE_IMAGE) chatMessage.id else null), envJson = chatMessage.env, replyPreview = chatMessage.replyPreview)
    chatMessage.type == ChatMessage.TYPE_IMAGE ->
        KartinkaFromItem(chatMessage.text, user, timeStr, msgId = chatMessage.id, transferRef = chatMessage.transferRef ?: (if (chatMessage.type == com.example.fess.kotlinmassage1.models.ChatMessage.TYPE_IMAGE) chatMessage.id else null), envJson = chatMessage.env, readAt = chatMessage.readAt, replyPreview = chatMessage.replyPreview)
    // E2EE: enc-сообщение читают СВОИМ приватником обе стороны диалога — и
    // входящее, и исходящее. Раньше дешифровка была прикручена только к
    // входящим (ChatToItem), поэтому отправитель своего же шифрованного
    // сообщения видел «🔒 Нет доступа» вместо текста.
    // ВАЖНО ПРО ГАЛОЧКИ: у E2EE-сообщений поле text — base64 КОНВЕРТА {enc,epk,alg},
    // а не открытого текста. Firebase getValue(ChatMessage::class.java) при
    // десериализации приводит такие строки к Long и падает на NumberFormatException;
    // после исключения узел приходит с дефолтным readAt=-1 даже если получатель уже
    // поставил receipt. Поэтому для enc-строк readAt всегда стартует с -1 — живую
    // галочку рисует read-tracker (applyReadFromMirror), который читает сырой лист.
    chatMessage.enc -> TextItem(chatMessage.editedText ?: chatMessage.text, user, timeStr, msgId = chatMessage.id, isIncoming = isIncoming, envMirror = if (chatMessage.editedText != null) chatMessage.envEdited else chatMessage.env, readAt = -1L, editTime = chatMessage.editTime, deleted = chatMessage.deleted, dbId = chatMessage.id, replyPreview = chatMessage.replyPreview)
    isIncoming -> ChatToItem(chatMessage.editedText ?: chatMessage.text, user, timeStr, msgId = chatMessage.id, deleted = chatMessage.deleted, replyPreview = chatMessage.replyPreview)
    else -> ChatFromItem(chatMessage.text, user, timeStr, editedText = chatMessage.editedText, msgId = chatMessage.id, readAt = chatMessage.readAt, editTime = chatMessage.editTime, envMirror = if (chatMessage.editedText != null) chatMessage.envEdited else chatMessage.env, deleted = chatMessage.deleted, replyPreview = chatMessage.replyPreview)
}
