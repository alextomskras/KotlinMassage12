package com.example.fess.kotlinmassage1.views





import android.util.Log
import com.example.fess.kotlinmassage1.R

import com.example.fess.kotlinmassage1.messages.ChatLogActivity
import com.example.fess.kotlinmassage1.models.ChatMessage
import com.example.fess.kotlinmassage1.models.User
import com.example.fess.kotlinmassage1.views.LatestMessageRow.Companion.TAG
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.squareup.picasso.Picasso
import com.xwray.groupie.Item
import com.xwray.groupie.ViewHolder

class LatestMessageRow(val chatMessage: ChatMessage) : Item<ViewHolder>() {
    companion object {
        val TAG = "ChatItems"
    }

    var chatPartnerUser: User? = null

    override fun bind(viewHolder: ViewHolder, position: Int) {
        viewHolder.itemView.findViewById<android.widget.TextView>(com.xwray.groupie.ViewIdResolver.idOf("text_textview_latest_message")).text = chatMessage.text

        val chatPartnerId: String
        if (chatMessage.fromId == FirebaseAuth.getInstance().uid) {
            chatPartnerId = chatMessage.toId
        } else {
            chatPartnerId = chatMessage.fromId
        }

        val ref = FirebaseDatabase.getInstance().getReference("/users/$chatPartnerId")
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(p0: DataSnapshot) {
                chatPartnerUser = p0.getValue(User::class.java)
                viewHolder.itemView.findViewById<android.widget.TextView>(com.xwray.groupie.ViewIdResolver.idOf("username_textview_latest_message")).text = chatPartnerUser?.username

                val targetImageView = viewHolder.itemView.findViewById<de.hdodenhof.circleimageview.CircleImageView>(com.xwray.groupie.ViewIdResolver.idOf("imageview_latest_message"))
                Picasso.get().load(chatPartnerUser?.profileImageUrl).into(targetImageView)
            }

            override fun onCancelled(p0: DatabaseError) {

            }
        })
    }

    override fun getLayout(): Int {
        return R.layout.latest_message_row
    }
}
