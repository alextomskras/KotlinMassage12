package com.example.fess.kotlinmassage1.views

import android.widget.ImageView
import android.widget.TextView
import com.example.fess.kotlinmassage1.R
import com.example.fess.kotlinmassage1.models.User
import com.example.fess.kotlinmassage1.util.ImageLoader
import com.example.fess.kotlinmassage1.util.ImageUtils
import com.xwray.groupie.Item
import com.xwray.groupie.ViewHolder

/**
 * Bubble-элементы чата. Декод base64-картинок вынесен в фоновый пул (ImageLoader):
 * раньше он выполнялся синхронно прямо в bind() и фризил скролл длинных чатов.
 */

class ChatFromItem(val text: String, val user: User, val time: String) : Item<ViewHolder>() {

    override fun bind(viewHolder: ViewHolder, position: Int) {
        viewHolder.itemView.findViewById<TextView>(R.id.textview_from_row).text = text
        viewHolder.itemView.findViewById<TextView>(R.id.textView_chat_from_message_time2).text = time
        ImageLoader.loadAvatarInto(
            user.profileImageUrl,
            viewHolder.itemView.findViewById(R.id.imageview_chat_from_row)
        )
    }

    override fun getLayout(): Int = R.layout.chat_from_row
}

class ChatToItem(val text: String, val user: User, val time: String) : Item<ViewHolder>() {

    override fun bind(viewHolder: ViewHolder, position: Int) {
        viewHolder.itemView.findViewById<TextView>(R.id.textview_to_row).text = text
        viewHolder.itemView.findViewById<TextView>(R.id.textView_chat_to_message_time2).text = time
        ImageLoader.loadAvatarInto(
            user.profileImageUrl,
            viewHolder.itemView.findViewById(R.id.imageview_chat_to_row)
        )
    }

    override fun getLayout(): Int = R.layout.chat_to_row
}

/** Картинка от нас: base64 из БД декодится в фоне; legacy Storage-URL — Picasso. */
class KartinkaFromItem(val text: String, val user: User, val time: String) : Item<ViewHolder>() {

    override fun bind(viewHolder: ViewHolder, position: Int) {
        viewHolder.itemView.findViewById<TextView>(R.id.textView_message_time).text = time

        val image = viewHolder.itemView.findViewById<ImageView>(R.id.kartinka_chat_from_row2)
        if (ImageUtils.isImagePayload(text)) {
            ImageLoader.loadBase64ToView(text, image)
        } else {
            // старые сообщения — URL из Firebase Storage
            com.squareup.picasso.Picasso.get().load(text).into(image)
        }

        ImageLoader.loadAvatarInto(
            user.profileImageUrl,
            viewHolder.itemView.findViewById(R.id.imageview_chat_from_row2)
        )
    }

    override fun getLayout(): Int = R.layout.kartinka_from_row
}

class KartinkaToItem(val text: String, val user: User, val time: String) : Item<ViewHolder>() {

    override fun bind(viewHolder: ViewHolder, position: Int) {
        viewHolder.itemView.findViewById<TextView>(R.id.textView_to_message_time).text = time

        val image = viewHolder.itemView.findViewById<ImageView>(R.id.kartinka_chat_to_row2)
        if (ImageUtils.isImagePayload(text)) {
            ImageLoader.loadBase64ToView(text, image)
        } else {
            com.squareup.picasso.Picasso.get().load(text).into(image)
        }

        ImageLoader.loadAvatarInto(
            user.profileImageUrl,
            viewHolder.itemView.findViewById(R.id.imageview_chat_to_row2)
        )
    }

    override fun getLayout(): Int = R.layout.kartinka_to_row
}
