package com.example.fess.kotlinmassage1.views





import android.util.Log
import com.example.fess.kotlinmassage1.R
import com.example.fess.kotlinmassage1.messages.ChatLogActivity.Companion.TAG
import com.example.fess.kotlinmassage1.models.User
import com.example.fess.kotlinmassage1.util.ImageUtils
import com.squareup.picasso.Picasso
import com.xwray.groupie.Item
import com.xwray.groupie.ViewHolder
import java.util.*


class ChatFromItem(val text: String, val user: User, val sfd1: String) : Item<ViewHolder>() {
    companion object {
        val TAG = "ChatItems"
    }

    override fun bind(viewHolder: ViewHolder, position: Int) {
        viewHolder.itemView.textview_from_row.text = text
        viewHolder.itemView.textView_chat_from_message_time2.text = sfd1

        val uri = user.profileImageUrl
        val targetImageView = viewHolder.itemView.imageview_chat_from_row
        Picasso.get().load(uri).into(targetImageView)
    }

    override fun getLayout(): Int {
        return R.layout.chat_from_row
    }
}

class ChatToItem(val text: String, val user: User, val sfd1: String) : Item<ViewHolder>() {
    override fun bind(viewHolder: ViewHolder, position: Int) {
        viewHolder.itemView.textview_to_row.text = text
        viewHolder.itemView.textView_chat_to_message_time2.text = sfd1

        val uri = user.profileImageUrl
        val targetImageView = viewHolder.itemView.imageview_chat_to_row
        Picasso.get().load(uri).into(targetImageView)
    }

    override fun getLayout(): Int {
        return R.layout.chat_to_row
    }
}

class KartinkaFromItem(val text: String, val user: User, val sfd1: String) : Item<ViewHolder>() {

    override fun bind(viewHolder: ViewHolder, position: Int) {
        Log.d(TAG, "_Message from (len=${text.length})")
        viewHolder.itemView.textView_message_time.text = sfd1

        val targetImageView1 = viewHolder.itemView.kartinka_chat_from_row2
        if (ImageUtils.isImagePayload(text)) {
            // base64 из БД -> Bitmap напрямую, без Picasso/Storage
            val bmp = ImageUtils.base64ToBitmap(text)
            if (bmp != null) targetImageView1.setImageBitmap(bmp)
        } else {
            // старые сообщения — URL из Firebase Storage
            Picasso.get().load(text).into(targetImageView1)
        }

        val uri = user.profileImageUrl
        val targetImageView = viewHolder.itemView.imageview_chat_from_row2
        Picasso.get().load(uri).into(targetImageView)
    }

    override fun getLayout(): Int {
        return R.layout.kartinka_from_row
    }
}

class KartinkaToItem(val text: String, val user: User, val sfd1: String) : Item<ViewHolder>() {
    override fun bind(viewHolder: ViewHolder, position: Int) {
        Log.d(TAG, "_Message_to_ (len=${text.length})")
        viewHolder.itemView.textView_to_message_time.text = sfd1

        val targetImageView1 = viewHolder.itemView.kartinka_chat_to_row2
        if (ImageUtils.isImagePayload(text)) {
            val bmp = ImageUtils.base64ToBitmap(text)
            if (bmp != null) targetImageView1.setImageBitmap(bmp)
        } else {
            Picasso.get().load(text).into(targetImageView1)
        }

        // load our user image into the star
        val uri = user.profileImageUrl
        Log.d(TAG, "_User_to_ $uri")
        val targetImageView = viewHolder.itemView.imageview_chat_to_row2
        Picasso.get().load(uri).into(targetImageView)
    }

    override fun getLayout(): Int {
        return R.layout.kartinka_to_row
    }
}


fun setTimeMessage(): String {
//val dateFormat = SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.SHORT, SimpleDateFormat.SHORT)
    //val dateFormat = LocalDateTime.now()
    val stamp = java.sql.Timestamp(System.currentTimeMillis())
    val date1 = Date(stamp.getTime())
    Log.d(TAG, "The time_ ${date1}")
    return date1.toString()
    // viewHolder.itemView.textView_message_time.text = date1.toString()
}


