package com.example.fess.kotlinmassage1.views

import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.fess.kotlinmassage1.R
import com.example.fess.kotlinmassage1.models.ChatMessage
import com.example.fess.kotlinmassage1.models.User
import com.example.fess.kotlinmassage1.util.ImageLoader
import com.example.fess.kotlinmassage1.util.ImageUtils
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Общий контракт строк диалогов: по клику активити нужен собеседник. */
interface DialogItem {
    val chatPartnerUser: User?
}

/** Позиция, к которой сейчас привязан holder (защита асинхронных колбэков от переиспользования ячейки). */
private fun RecyclerView.ViewHolder.boundPosition(): Int =
    (itemView.getTag(R.id.tag_bound_position) as? Int) ?: RecyclerView.NO_POSITION

/**
 * Строка списка диалогов (обычное текстовое сообщение).
 * Профиль собеседника подтягивается через ImageLoader.fetchUser (колбэк в main),
 * аватар — Picasso c превью-ресайзом.
 */
class LatestMessageRow(val chatMessage: ChatMessage) : ChatRowDelegate, DialogItem {

    /** Имя собеседника доступно по клику (см. LatestMessagesActivity). */
    override var chatPartnerUser: User? = null
        private set

    override var rowContext: android.content.Context? = null

    override fun layoutRes(): Int = R.layout.latest_message_row

    override fun bindTo(viewHolder: RecyclerView.ViewHolder, position: Int) {

        // картинку (relay-трансфер или legacy base64) в список диалогов не
        // показываем куском кода — только метку
        val preview = if (chatMessage.type == ChatMessage.TYPE_IMAGE) "📷 Картинка"
                      else chatMessage.text.take(120)
        viewHolder.itemView.findViewById<TextView>(R.id.text_textview_latest_message).text = preview

        val partnerId = chatMessage.partnerId(FirebaseAuth.getInstance().uid)
        ImageLoader.fetchUser(partnerId) { user ->
            if (user == null) return@fetchUser
            chatPartnerUser = user
            // ViewHolder мог быть переиспользован за время запроса — проверяем привязку
            if (viewHolder.boundPosition() != position) return@fetchUser
            viewHolder.itemView.findViewById<TextView>(R.id.username_textview_latest_message).text = user.username
            ImageLoader.loadAvatarInto(
                user.profileImageUrl,
                viewHolder.itemView.findViewById(R.id.imageview_latest_message)
            )
        }
    }
}

/**
 * Строка списка диалогов с картинкой. Декод base64 — в фоновом пуле (ImageLoader),
 * раньше выполнялся синхронно в bind() и фризил список на каждом бинде.
 */
class LatestKartinkaMessageRow(val chatMessage: ChatMessage) : ChatRowDelegate, DialogItem {

    override var chatPartnerUser: User? = null
        private set

    override var rowContext: android.content.Context? = null

    override fun layoutRes(): Int = R.layout.latest_image_message_row

    override fun bindTo(viewHolder: RecyclerView.ViewHolder, position: Int) {

        val time = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
            .format(Date(chatMessage.timestamp * 1000))
        viewHolder.itemView.findViewById<TextView>(R.id.textDate_message_time2).text = time

        val isImage = chatMessage.type == ChatMessage.TYPE_IMAGE
        val previewText = viewHolder.itemView.findViewById<TextView>(R.id.text_kartinka_textview_latest_message3)
        val image = viewHolder.itemView.findViewById<ImageView>(R.id.kartinka_imageview_latest_message)

        if (isImage) {
            // в списке диалогов превью НЕ подписываем словами — только сама картинка
            previewText.text = ""
            val ctx = rowContext
            // transferRef: явно (relay-формат) либо id сообщения (fallback для
            // старых записей без поля); пустой ref внутри loadTransferToView сам
            // переходит на payload/URL
            val ref = chatMessage.transferRef ?: chatMessage.id
            when {
                ctx != null ->
                    ImageLoader.loadTransferToView(ctx, ref, FirebaseAuth.getInstance().uid, image, maxSide = 300, payload = chatMessage.text, envJson = chatMessage.env)
                ImageUtils.isImagePayload(chatMessage.text) ->
                    ImageLoader.loadBase64ToView(chatMessage.text, image, maxSide = 300)
                else ->
                    // обратная совместимость со старыми URL из Firebase Storage
                    ImageLoader.loadUrlToView(chatMessage.text, image)
            }
        } else {
            previewText.text = chatMessage.text.take(80)
        }

        val partnerId = chatMessage.partnerId(FirebaseAuth.getInstance().uid)
        ImageLoader.fetchUser(partnerId) { user ->
            if (user == null) return@fetchUser
            chatPartnerUser = user
            if (viewHolder.boundPosition() != position) return@fetchUser
            viewHolder.itemView.findViewById<TextView>(R.id.username_kartinka_textview_latest_message).text = user.username
            ImageLoader.loadAvatarInto(
                user.profileImageUrl,
                viewHolder.itemView.findViewById(R.id.imageview_latest_message1)
            )
        }
    }
}
