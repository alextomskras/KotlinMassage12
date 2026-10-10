package com.example.fess.kotlinmassage1.views

import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.fess.kotlinmassage1.R
import com.example.fess.kotlinmassage1.models.User
import com.example.fess.kotlinmassage1.util.ImageLoader
import com.example.fess.kotlinmassage1.util.ImageUtils

/**
 * Bubble-элементы чата. Декод base64-картинок вынесен в фоновый пул (ImageLoader):
 * раньше он выполнялся синхронно прямо в bind() и фризил скролл длинных чатов.
 * Реализуют ChatRowDelegate — рендерятся через ChatRecyclerAdapter (без groupie).
 */

class ChatFromItem(
    val text: String,
    val user: User,
    val time: String,
    /** Новый текст после правки (>null => показываем его + «изменено»). */
    var editedText: String? = null,
    private val encMsgId: String? = null,
    /** id сообщения в зеркале MY uid — нужен для записи readAt собеседнику. */
    override var msgId: String = "",
    /** Прочтено ли собеседником (readAt > 0) — рисует двойную синюю галочку. */
    override var readAt: Long = -1,
    /** Время правки (>0 => показываем «изменено»). */
    var editTime: Long = -1,
    /** Selfless-конверт для чтения своего текста (E2EE). */
    var envMirror: String? = null,
    /** Ключ узла в зеркале текущего пользователя. */
    var dbId: String = "",
    /** Soft-delete: рисуем заглушку вместо текста. */
    var deleted: Boolean = false,
    /** Пункт 13: превью сообщения-источника (если это ответ). */
    override var replyPreview: String? = null
) : ReplyQuoteRow(), ReadTickRow {

    override val chatPartnerUser: User? get() = user
    override var rowContext: android.content.Context? = null

    override fun layoutRes(): Int = R.layout.chat_from_row

    override fun bindTo(viewHolder: RecyclerView.ViewHolder, position: Int) {
        if (deleted) {
            viewHolder.itemView.findViewById<TextView>(R.id.textview_from_row).text = TextItem.DELETED_LABEL
        } else {
            // Plaintext -> как есть; E2EE -> selfless-зеркало из env/envEdited
            // (displayText не годится: эфемерный конверт своим static-ключом не читается).
            val shown = plainForEditing() ?: displayText(editedText ?: text, encMsgId)
            viewHolder.itemView.findViewById<TextView>(R.id.textview_from_row).text = shown
        }
        viewHolder.itemView.findViewById<TextView>(R.id.textView_chat_from_message_time2).text = time + editedSuffix()
        applyTick(viewHolder)
        ImageLoader.loadAvatarInto(
            user.profileImageUrl,
            viewHolder.itemView.findViewById(R.id.imageview_chat_from_row)
        )
        renderQuote(viewHolder)
    }

    override fun plainTextForMenu(): String? =
        if (deleted) null else plainForEditing() ?: text.take(200)

    override fun rowDbId(): String = dbId

    /** Открытый текст для диалога правки (plaintext или selfless-зеркало из env). */
    fun plainForEditing(): String? =
        com.example.fess.kotlinmassage1.util.CryptoBridge.plainForSender(msgId, editedText ?: text, envMirror)

    /** Пометка «изменено» после времени (редактирование текста). */
    private fun editedSuffix(): String = if (editTime > 0) " · изменено" else ""

    /**
     * Галочки статуса исходящего сообщения: одна серая — отправлено,
     * две синие — прочтено собеседником (readAt проставляет получатель,
     * см. ChatLogActivity.markIncomingAsRead / readTracker).
     */
    fun applyTick(viewHolder: RecyclerView.ViewHolder) {
        val tick = viewHolder.itemView.findViewById<ImageView>(R.id.imageview_msg_status) ?: return
        if (readAt > 0) {
            tick.setImageResource(R.drawable.ic_check_double)
            tick.setColorFilter(androidx.core.content.ContextCompat.getColor(
                tick.context, R.color.tick_read))
        } else {
            tick.setImageResource(R.drawable.ic_check_single)
            tick.setColorFilter(androidx.core.content.ContextCompat.getColor(
                tick.context, R.color.tick_sent))
        }
    }
}

class ChatToItem(
    val text: String,
    val user: User,
    val time: String,
    private val encMsgId: String? = null,
    val msgId: String = "",
    /** Soft-delete: заглушка вместо контента. */
    var deleted: Boolean = false,
    /** Ключ узла в зеркале текущего пользователя. */
    var dbId: String = "",
    /** Пункт 13: превью сообщения-источника (если это ответ). */
    override var replyPreview: String? = null
) : ReplyQuoteRow() {

    override val chatPartnerUser: User? get() = user
    override var rowContext: android.content.Context? = null

    override fun layoutRes(): Int = R.layout.chat_to_row

    override fun bindTo(viewHolder: RecyclerView.ViewHolder, position: Int) {
        if (deleted) {
            viewHolder.itemView.findViewById<TextView>(R.id.textview_to_row).text = TextItem.DELETED_LABEL
        } else {
            viewHolder.itemView.findViewById<TextView>(R.id.textview_to_row).text = displayText(text, encMsgId)
        }
        viewHolder.itemView.findViewById<TextView>(R.id.textView_chat_to_message_time2).text = time
        ImageLoader.loadAvatarInto(
            user.profileImageUrl,
            viewHolder.itemView.findViewById(R.id.imageview_chat_to_row)
        )
        renderQuote(viewHolder)
    }

    override fun plainTextForMenu(): String? =
        if (deleted) null else displayText(text, encMsgId).takeIf { it != TextItem.DECRYPT_FAIL_LABEL }

    override fun rowDbId(): String = dbId
}

/**
 * E2EE v1: если у сообщения стоит флаг enc, в text лежит base64 конверта —
 * показываем расшифрованный текст; при неудаче (нет приватника / чужой ключ)
 * понятную заглушку вместо кашы base64.
 */
private fun displayText(text: String, encMsgId: String?): String {
    if (encMsgId == null) return text
    val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().uid
    // fromId в чате неизвестен на уровне строки; для текста это не критично —
    // дешифровка идёт эфемерным путём и работает для обеих сторон диалога.
    return com.example.fess.kotlinmassage1.util.CryptoBridge.decryptText(myUid, null, encMsgId, text)
        ?: "🔒 Нет доступа к сообщению"
}

/**
 * Строка зашифрованного текста (E2EE v1). Отличается от Chat{From,To}Item тем,
 * что ВСЕГДА пытается дешифровать и применяется к обеим сторонам диалога:
 * конверт шифруется эфемерной парой отправителя под pubkey получателя, но
 * зеркало чата читается СВОИМ приватником — отправитель тоже видит свой текст
 * (как Signal/WhatsApp). Раньше enc-обработка была только у входящих, и автор
 * своего зашифрованного сообщения видел «🔒 Нет доступа к сообщению».
 */
class TextItem(
    val text: String,
    val user: User,
    val time: String,
    override var msgId: String,
    private val isIncoming: Boolean = false,
    private val envMirror: String? = null,
    /** Прочтено собеседником (для исходящих; readAt из БД). */
    override var readAt: Long = -1,
    /** Время правки (>0 => «изменено»). */
    var editTime: Long = -1,
    /** Soft-delete: рисуем заглушку вместо контента. */
    var deleted: Boolean = false,
    /** id сообщения в ЗЕРКАЛЕ ПОЛЬЗОВАТЕЛЯ (отличается от msgId у зеркал собеседника). */
    var dbId: String = "",
    /** Пункт 13: превью сообщения-источника (если это ответ). */
    override var replyPreview: String? = null
) : ReplyQuoteRow(), ReadTickRow {

    override val chatPartnerUser: User? get() = user
    override var rowContext: android.content.Context? = null

    override fun layoutRes(): Int = if (isIncoming) R.layout.chat_to_row else R.layout.chat_from_row

    override fun bindTo(viewHolder: RecyclerView.ViewHolder, position: Int) {
        val textId = if (isIncoming) R.id.textview_to_row else R.id.textview_from_row
        val timeId = if (isIncoming) R.id.textView_chat_to_message_time2 else R.id.textView_chat_from_message_time2
        val avatarId = if (isIncoming) R.id.imageview_chat_to_row else R.id.imageview_chat_from_row
        if (deleted) {
            viewHolder.itemView.findViewById<TextView>(textId).text = DELETED_LABEL
            viewHolder.itemView.findViewById<TextView>(timeId).text = time
            if (!isIncoming) applyTick(viewHolder)
        } else if (!isIncoming) {
            // Исходящее E2EE: показываем ЧИТАЕМЫЙ текст (plaintext или selfless-
            // зеркало из env). Раньше здесь был вызов decryptText эфемерным путём —
            // он заведомо падал (эфемерида не сохраняется), и отправитель видел
            // «🔒 Нет доступа» даже когда зеркало живо.
            val plain = com.example.fess.kotlinmassage1.util.CryptoBridge.plainForSender(msgId, text, envMirror)
            viewHolder.itemView.findViewById<TextView>(textId).text = plain ?: DECRYPT_FAIL_LABEL
            viewHolder.itemView.findViewById<TextView>(timeId).text =
                time + if (editTime > 0) " · изменено" else ""
            applyTick(viewHolder)
        } else {
            // Входящее: эфемерный конверт из text читается НАШИМ static-приватником
            // (ECDH с epk). Если сообщение правилось — в editedText лежит свежий
            // конверт под тем же msgId (тот же AAD), шифруем от него же.
            val shown = com.example.fess.kotlinmassage1.util.CryptoBridge.decryptText(null, null, msgId, text)
                ?: DECRYPT_FAIL_LABEL
            viewHolder.itemView.findViewById<TextView>(textId).text = shown
            viewHolder.itemView.findViewById<TextView>(timeId).text =
                time + if (editTime > 0) " · изменено" else ""
        }
        ImageLoader.loadAvatarInto(user.profileImageUrl, viewHolder.itemView.findViewById(avatarId))
        renderQuote(viewHolder)
    }

    override fun plainTextForMenu(): String? {
        if (deleted) return null
        return if (isIncoming) {
            com.example.fess.kotlinmassage1.util.CryptoBridge.decryptText(null, null, msgId, text)?.take(500)
        } else plainForEditing()?.take(500)
    }

    override fun rowDbId(): String = dbId

    /**
     * Галочки статуса исходящего сообщения: одна серая — отправлено,
     * две синие — прочтено собеседником. В входящем layout их нет — findViewById
     * вернёт null, вызов безопасен с любой ветки.
     */
    private fun applyTick(viewHolder: RecyclerView.ViewHolder) {
        val tick = viewHolder.itemView.findViewById<ImageView>(R.id.imageview_msg_status) ?: return
        if (readAt > 0) {
            tick.setImageResource(R.drawable.ic_check_double)
            tick.setColorFilter(androidx.core.content.ContextCompat.getColor(tick.context, R.color.tick_read))
        } else {
            tick.setImageResource(R.drawable.ic_check_single)
            tick.setColorFilter(androidx.core.content.ContextCompat.getColor(tick.context, R.color.tick_sent))
        }
    }

    /** true если строка — входящее сообщение (для long-press фильтра «только свои»). */
    fun isIncomingForMenu(): Boolean = isIncoming

    /** Открытый текст для диалога правки (только для исходящих). */
    fun plainForEditing(): String? =
        com.example.fess.kotlinmassage1.util.CryptoBridge.plainForSender(msgId, text, envMirror)

    companion object {
        const val DELETED_LABEL = "🚫 Сообщение удалено"
        const val DECRYPT_FAIL_LABEL = "🔒 Нет доступа к сообщению"
    }
}

/**
 * Картинка от нас. Три формата (обратная совместимость):
 *  - transferRef -> relay-зона /transfers/<id> (base64 живёт 7 дней, затем локальный кэш);
 *  - base64 data-URI в text -> legacy «тело в сообщении»;
 *  - URL firebasestorage -> самый старый формат, Picasso.
 */
class KartinkaFromItem(
    val text: String,
    val user: User,
    val time: String,
    override var msgId: String = "",
    val transferRef: String? = null,
    val envJson: String? = null,
    /** Прочтено собеседником (для исходящих). */
    override var readAt: Long = -1,
    /** Soft-delete: рисуем заглушку вместо картинки. */
    var deleted: Boolean = false,
    /** Ключ узла в зеркале текущего пользователя. */
    var dbId: String = "",
    /** Пункт 13: превью сообщения-источника (если это ответ). */
    override var replyPreview: String? = null
) : ReplyQuoteRow(), ReadTickRow {

    override val chatPartnerUser: User? get() = user
    override var rowContext: android.content.Context? = null

    override fun layoutRes(): Int = R.layout.kartinka_from_row

    override fun bindTo(viewHolder: RecyclerView.ViewHolder, position: Int) {
        viewHolder.itemView.findViewById<TextView>(R.id.textView_message_time).text = time

        val image = viewHolder.itemView.findViewById<ImageView>(R.id.kartinka_chat_from_row2)
        val ctx = rowContext
        if (deleted) {
            image.setImageResource(R.drawable.image_expired)
            image.setOnClickListener(null)
        } else when {
            !transferRef.isNullOrEmpty() && ctx != null ->
                ImageLoader.loadTransferToView(ctx, transferRef, com.google.firebase.auth.FirebaseAuth.getInstance().uid, image, maxSide = 300, payload = text, envJson = envJson, isOutgoing = true)
            ImageUtils.isImagePayload(text) -> ImageLoader.loadBase64ToView(text, image, maxSide = 300)
            else -> com.squareup.picasso.Picasso.get().load(text).into(image)
        }

        // Тап по миниатюре — полноэкранный просмотр с зумом и hero-переходом
        // (миниатюра «разлетается» в полный экран через SharedElementTransition).
        if (!deleted) {
            com.example.fess.kotlinmassage1.util.HeroTransition.armHero(image, msgId.ifEmpty { transferRef })
            image.setOnClickListener {
                val activity = (ctx as? android.app.Activity) ?: (image.context as? android.app.Activity)
                if (activity != null) {
                    com.example.fess.kotlinmassage1.util.HeroTransition.launch(
                        activity, image, transferRef, text, envJson = envJson, isOutgoing = true
                    )
                } else {
                    // Контекст не активность (например, превью) — fallback на диалог.
                    com.example.fess.kotlinmassage1.util.FullscreenImageDialog(
                        image.context, transferRef, text, envJson = envJson, isOutgoing = true
                    ).show()
                }
            }
        }

        // Галочки статуса (одна серая / две синие).
        val tick = viewHolder.itemView.findViewById<ImageView>(R.id.imageview_msg_status_img)
        if (tick != null) {
            if (readAt > 0) {
                tick.setImageResource(R.drawable.ic_check_double)
                tick.setColorFilter(androidx.core.content.ContextCompat.getColor(tick.context, R.color.tick_read))
            } else {
                tick.setImageResource(R.drawable.ic_check_single)
                tick.setColorFilter(androidx.core.content.ContextCompat.getColor(tick.context, R.color.tick_sent))
            }
        }

        ImageLoader.loadAvatarInto(
            user.profileImageUrl,
            viewHolder.itemView.findViewById(R.id.imageview_chat_from_row2)
        )
        renderQuote(viewHolder)
    }

    override fun plainTextForMenu(): String? = null // копировать картинку нечего

    override fun rowDbId(): String = dbId
}

class KartinkaToItem(
    val text: String,
    val user: User,
    val time: String,
    val msgId: String = "",
    val transferRef: String? = null,
    val envJson: String? = null,
    /** Soft-delete: рисуем заглушку вместо картинки. */
    var deleted: Boolean = false,
    /** Ключ узла в зеркале текущего пользователя. */
    var dbId: String = "",
    /** Пункт 13: превью сообщения-источника (если это ответ). */
    override var replyPreview: String? = null
) : ReplyQuoteRow() {

    override val chatPartnerUser: User? get() = user
    override var rowContext: android.content.Context? = null

    override fun layoutRes(): Int = R.layout.kartinka_to_row

    override fun bindTo(viewHolder: RecyclerView.ViewHolder, position: Int) {
        viewHolder.itemView.findViewById<TextView>(R.id.textView_to_message_time).text = time

        val image = viewHolder.itemView.findViewById<ImageView>(R.id.kartinka_chat_to_row2)
        val ctx = rowContext
        if (deleted) {
            image.setImageResource(R.drawable.image_expired)
            image.setOnClickListener(null)
        } else when {
            !transferRef.isNullOrEmpty() && ctx != null ->
                ImageLoader.loadTransferToView(ctx, transferRef, com.google.firebase.auth.FirebaseAuth.getInstance().uid, image, maxSide = 300, payload = text, envJson = envJson)
            ImageUtils.isImagePayload(text) -> ImageLoader.loadBase64ToView(text, image, maxSide = 300)
            else -> com.squareup.picasso.Picasso.get().load(text).into(image)
        }

        // Тап по миниатюре — полноэкранный просмотр с зумом и hero-переходом.
        if (!deleted) {
            com.example.fess.kotlinmassage1.util.HeroTransition.armHero(image, msgId.ifEmpty { transferRef })
            image.setOnClickListener {
                val activity = (ctx as? android.app.Activity) ?: (image.context as? android.app.Activity)
                if (activity != null) {
                    com.example.fess.kotlinmassage1.util.HeroTransition.launch(
                        activity, image, transferRef, text, envJson = envJson
                    )
                } else {
                    com.example.fess.kotlinmassage1.util.FullscreenImageDialog(
                        image.context, transferRef, text, envJson = envJson
                    ).show()
                }
            }
        }

        ImageLoader.loadAvatarInto(
            user.profileImageUrl,
            viewHolder.itemView.findViewById(R.id.imageview_chat_to_row2)
        )
        renderQuote(viewHolder)
    }

    override fun plainTextForMenu(): String? = null

    override fun rowDbId(): String = dbId
}

/**
 * Строка soft-deleted сообщения (любого исходного типа): заглушка
 * «Сообщение удалено» в обычном пузыре. Контент не показываем и не дешифруем —
 * даже если шифртекст ещё живёт в БД до серверного cleanup.
 */
class DeletedTextItem(
    val time: String,
    val user: User,
    private val isIncoming: Boolean = false,
    var dbId: String = "",
    override var replyPreview: String? = null
) : ReplyQuoteRow() {

    override val chatPartnerUser: User? get() = user
    override var rowContext: android.content.Context? = null

    override fun layoutRes(): Int = if (isIncoming) R.layout.chat_to_row else R.layout.chat_from_row

    override fun bindTo(viewHolder: RecyclerView.ViewHolder, position: Int) {
        val textId = if (isIncoming) R.id.textview_to_row else R.id.textview_from_row
        val timeId = if (isIncoming) R.id.textView_chat_to_message_time2 else R.id.textView_chat_from_message_time2
        val avatarId = if (isIncoming) R.id.imageview_chat_to_row else R.id.imageview_chat_from_row
        viewHolder.itemView.findViewById<TextView>(textId).text = TextItem.DELETED_LABEL
        viewHolder.itemView.findViewById<TextView>(timeId).text = time
        ImageLoader.loadAvatarInto(user.profileImageUrl, viewHolder.itemView.findViewById(avatarId))
        renderQuote(viewHolder)
    }

    override fun plainTextForMenu(): String? = null // удалённое сообщение копировать нечего

    override fun rowDbId(): String = dbId
}
