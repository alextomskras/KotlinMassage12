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
    private val encMsgId: String? = null
) : ChatRowDelegate {

    override val chatPartnerUser: User? get() = user
    override var rowContext: android.content.Context? = null

    override fun layoutRes(): Int = R.layout.chat_from_row

    override fun bindTo(viewHolder: RecyclerView.ViewHolder, position: Int) {
        viewHolder.itemView.findViewById<TextView>(R.id.textview_from_row).text = displayText(text, encMsgId)
        viewHolder.itemView.findViewById<TextView>(R.id.textView_chat_from_message_time2).text = time
        ImageLoader.loadAvatarInto(
            user.profileImageUrl,
            viewHolder.itemView.findViewById(R.id.imageview_chat_from_row)
        )
    }
}

class ChatToItem(
    val text: String,
    val user: User,
    val time: String,
    private val encMsgId: String? = null
) : ChatRowDelegate {

    override val chatPartnerUser: User? get() = user
    override var rowContext: android.content.Context? = null

    override fun layoutRes(): Int = R.layout.chat_to_row

    override fun bindTo(viewHolder: RecyclerView.ViewHolder, position: Int) {
        viewHolder.itemView.findViewById<TextView>(R.id.textview_to_row).text = displayText(text, encMsgId)
        viewHolder.itemView.findViewById<TextView>(R.id.textView_chat_to_message_time2).text = time
        ImageLoader.loadAvatarInto(
            user.profileImageUrl,
            viewHolder.itemView.findViewById(R.id.imageview_chat_to_row)
        )
    }
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
    val msgId: String,
    private val isIncoming: Boolean = false,
    private val envMirror: String? = null
) : ChatRowDelegate {

    override val chatPartnerUser: User? get() = user
    override var rowContext: android.content.Context? = null

    override fun layoutRes(): Int = if (isIncoming) R.layout.chat_to_row else R.layout.chat_from_row

    override fun bindTo(viewHolder: RecyclerView.ViewHolder, position: Int) {
        val textId = if (isIncoming) R.id.textview_to_row else R.id.textview_from_row
        val timeId = if (isIncoming) R.id.textView_chat_to_message_time2 else R.id.textView_chat_from_message_time2
        val avatarId = if (isIncoming) R.id.imageview_chat_to_row else R.id.imageview_chat_from_row
        // Входящее: эфемерный конверт из text читается нашим приватником.
        // Исходящее: эфемерида отправителем не сохраняется — читаем selfless-
        // зеркало из env; если зеркала нет (старое сообщение), показывать
        // нечего (в text лежит base64 шифра) — честная заглушка.
        val shown = if (!isIncoming) {
            com.example.fess.kotlinmassage1.util.CryptoBridge.decryptText(null, null, msgId, text)
                ?: "🔒 Нет доступа к сообщению"
        } else {
            envMirror?.let {
                com.example.fess.kotlinmassage1.util.CryptoBridge.decryptTextFromEnv(msgId, it)
            } ?: "🔒 Нет доступа к сообщению"
        }
        viewHolder.itemView.findViewById<TextView>(textId).text = shown
        viewHolder.itemView.findViewById<TextView>(timeId).text = time
        ImageLoader.loadAvatarInto(user.profileImageUrl, viewHolder.itemView.findViewById(avatarId))
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
    val msgId: String = "",
    val transferRef: String? = null,
    val envJson: String? = null
) : ChatRowDelegate {

    override val chatPartnerUser: User? get() = user
    override var rowContext: android.content.Context? = null

    override fun layoutRes(): Int = R.layout.kartinka_from_row

    override fun bindTo(viewHolder: RecyclerView.ViewHolder, position: Int) {
        viewHolder.itemView.findViewById<TextView>(R.id.textView_message_time).text = time

        val image = viewHolder.itemView.findViewById<ImageView>(R.id.kartinka_chat_from_row2)
        val ctx = rowContext
        when {
            !transferRef.isNullOrEmpty() && ctx != null ->
                ImageLoader.loadTransferToView(ctx, transferRef, com.google.firebase.auth.FirebaseAuth.getInstance().uid, image, maxSide = 300, payload = text, envJson = envJson, isOutgoing = true)
            ImageUtils.isImagePayload(text) -> ImageLoader.loadBase64ToView(text, image, maxSide = 300)
            else -> com.squareup.picasso.Picasso.get().load(text).into(image)
        }

        // Тап по миниатюре — полноэкранный просмотр с зумом (как в WhatsApp).
        image.setOnClickListener {
            val c = ctx ?: image.context
            com.example.fess.kotlinmassage1.util.FullscreenImageDialog(c, transferRef, text, envJson = envJson, isOutgoing = true).show()
        }

        ImageLoader.loadAvatarInto(
            user.profileImageUrl,
            viewHolder.itemView.findViewById(R.id.imageview_chat_from_row2)
        )
    }
}

class KartinkaToItem(
    val text: String,
    val user: User,
    val time: String,
    val msgId: String = "",
    val transferRef: String? = null,
    val envJson: String? = null
) : ChatRowDelegate {

    override val chatPartnerUser: User? get() = user
    override var rowContext: android.content.Context? = null

    override fun layoutRes(): Int = R.layout.kartinka_to_row

    override fun bindTo(viewHolder: RecyclerView.ViewHolder, position: Int) {
        viewHolder.itemView.findViewById<TextView>(R.id.textView_to_message_time).text = time

        val image = viewHolder.itemView.findViewById<ImageView>(R.id.kartinka_chat_to_row2)
        val ctx = rowContext
        when {
            !transferRef.isNullOrEmpty() && ctx != null ->
                ImageLoader.loadTransferToView(ctx, transferRef, com.google.firebase.auth.FirebaseAuth.getInstance().uid, image, maxSide = 300, payload = text, envJson = envJson)
            ImageUtils.isImagePayload(text) -> ImageLoader.loadBase64ToView(text, image, maxSide = 300)
            else -> com.squareup.picasso.Picasso.get().load(text).into(image)
        }

        // Тап по миниатюре — полноэкранный просмотр с зумом (как в WhatsApp).
        image.setOnClickListener {
            val c = ctx ?: image.context
            com.example.fess.kotlinmassage1.util.FullscreenImageDialog(c, transferRef, text, envJson = envJson).show()
        }

        ImageLoader.loadAvatarInto(
            user.profileImageUrl,
            viewHolder.itemView.findViewById(R.id.imageview_chat_to_row2)
        )
    }
}
