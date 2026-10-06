package com.example.fess.kotlinmassage1.messages

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import com.example.fess.kotlinmassage1.R
import com.example.fess.kotlinmassage1.models.ChatMessage
import com.example.fess.kotlinmassage1.models.User
import com.example.fess.kotlinmassage1.util.ImageUtils
import com.example.fess.kotlinmassage1.views.ChatFromItem
import com.example.fess.kotlinmassage1.views.ChatToItem
import com.example.fess.kotlinmassage1.views.KartinkaFromItem
import com.example.fess.kotlinmassage1.views.KartinkaToItem
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.xwray.groupie.GroupAdapter
import com.xwray.groupie.ViewHolder
import kotlinx.android.synthetic.main.activity_chat_log.*
import java.util.*

//import kotlinx.android.synthetic.main.notification_template_lines_media.view.*

class ChatLogActivity : AppCompatActivity() {

    companion object {
        val TAG = "ChatLog"
    }

    val adapter = GroupAdapter<ViewHolder>()

    var toUser: User? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_log)

        recyclerview_chat_log.adapter = adapter

        toUser = intent.getParcelableExtra<User>(NewMessageActivity.USER_KEY)

        supportActionBar?.title = toUser?.username


//    setupDummyData()
        listenForMessages()





        send_button_chat_log.setOnClickListener {
            Log.d(TAG, "Attempt to send message....")
            performSendMessage()
        }

        image_send_button_chat_log.setOnClickListener {
            Log.d(TAG, "Attempt to select images....")
            Toast.makeText(this, "Please select images", Toast.LENGTH_SHORT).show()

            val intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
            startActivityForResult(intent, 0)
        }
    }

    var selectedImageUri: Uri? = null


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 0 && resultCode == Activity.RESULT_OK && data != null) {
            Log.d(TAG, "Photo was selected")

            selectedImageUri = data.data

            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, selectedImageUri)

            select_photoview_image_send.setImageBitmap(bitmap)

            image_send_button_chat_log.alpha = 0f

            // Картинку НЕ грузим в Firebase Storage — жмём и кладём base64 прямо в БД.
            sendSelectedImage()

        }
    }

    private fun sendSelectedImage() {
        val uri = selectedImageUri ?: return
        val b64 = ImageUtils.compressToBase64(this, uri)
        if (b64 == null) {
            Toast.makeText(this, "Не удалось обработать картинку", Toast.LENGTH_SHORT).show()
            resetImagePickerUi()
            return
        }
        performSendImage(b64)
    }

    private fun resetImagePickerUi() {
        runOnUiThread {
            select_photoview_image_send.setImageBitmap(null)
            image_send_button_chat_log.alpha = 1f
            selectedImageUri = null
        }
    }


    /**
     * Рендер сообщения по явному типу: base64-payload -> Kartinka*, иначе Chat*.
     */
    private fun buildChatItem(chatMessage: ChatMessage, isIncoming: Boolean): com.xwray.groupie.Item<ViewHolder> {
        val time1 = chatMessage.timestamp * 1000
        val sfd = java.text.SimpleDateFormat("dd-MM-yyyy HH:mm:ss")
        val sfd1 = sfd.format(Date(time1))
        val isImage = ImageUtils.isImagePayload(chatMessage.text) ||
                // обратная совместимость со старыми сообщениями-URL из Storage
                chatMessage.text.startsWith("https://firebasestorage")

        val user = if (isIncoming) toUser!! else (LatestMessagesActivity.currentUser ?: toUser!!)
        return when {
            isImage && isIncoming -> KartinkaToItem(chatMessage.text, user, sfd1)
            isImage -> KartinkaFromItem(chatMessage.text, user, sfd1)
            isIncoming -> ChatToItem(chatMessage.text, user, sfd1)
            else -> ChatFromItem(chatMessage.text, user, sfd1)
        }
    }

    fun listenForMessages() {

        val fromId = FirebaseAuth.getInstance().uid
        val toId = toUser?.uid
        val ref = FirebaseDatabase.getInstance().getReference("/user-messages/$fromId/$toId")

        ref.addChildEventListener(object : ChildEventListener {

            override fun onChildAdded(p0: DataSnapshot, p1: String?) {
                val chatMessage = p0.getValue(ChatMessage::class.java)

                if (chatMessage != null) {
                    val isIncoming = chatMessage.fromId != FirebaseAuth.getInstance().uid
                    adapter.add(buildChatItem(chatMessage, isIncoming))
                }

                recyclerview_chat_log.scrollToPosition(adapter.itemCount - 1)

            }

            override fun onCancelled(p0: DatabaseError) {

            }

            override fun onChildChanged(p0: DataSnapshot, p1: String?) {

            }

            override fun onChildMoved(p0: DataSnapshot, p1: String?) {

            }

            override fun onChildRemoved(p0: DataSnapshot) {

            }

        })

    }


    /**
     * Единая точка записи сообщения в БД + постановка задачи на push в /outbox.
     * @param text текст сообщения или base64-payload картинки
     */
    private fun writeMessage(text: String) {
        val fromId = FirebaseAuth.getInstance().uid ?: return
        val user = intent.getParcelableExtra<User>(NewMessageActivity.USER_KEY)
        val toId = user.uid

        val reference = FirebaseDatabase.getInstance().getReference("/user-messages/$fromId/$toId").push()
        val toReference = FirebaseDatabase.getInstance().getReference("/user-messages/$toId/$fromId").push()

        val chatMessage = ChatMessage(reference.key!!, text, fromId, toId, System.currentTimeMillis() / 1000)
        reference.setValue(chatMessage)
                .addOnSuccessListener {
                    Log.d(TAG, "Saved our chat message: ${reference.key}")
                    edittext_chat_log.text.clear()
                    recyclerview_chat_log.scrollToPosition(adapter.itemCount - 1)
                }
        toReference.setValue(chatMessage)

        val latestMessageRef = FirebaseDatabase.getInstance().getReference("/latest-messages/$fromId/$toId")
        latestMessageRef.setValue(chatMessage)

        val latestMessageToRef = FirebaseDatabase.getInstance().getReference("/latest-messages/$toId/$fromId")
        latestMessageToRef.setValue(chatMessage)

        // Задача на push для бэкенд-релея: он слушает /outbox, читает токены из
        // /user-tokens/{toId} и шлёт FCM (notification title/body + data.fromId/type/msgId).
        // ВАЖНО: сюда НЕ кладём base64 — только превью, чтобы не дублировать трафик.
        val preview = if (ImageUtils.isImagePayload(text)) "📷 Картинка" else text.take(120)
        val outbox = FirebaseDatabase.getInstance().getReference("/outbox").push()
        outbox.setValue(mapOf(
            "msgId" to chatMessage.id,
            "fromId" to fromId,
            "toId" to toId,
            "type" to chatMessage.type,
            "preview" to preview,
            "timestamp" to chatMessage.timestamp
        ))
    }


    fun performSendMessage() {
        val text = edittext_chat_log.text.toString()
        if (text.isEmpty()) return
        writeMessage(text)
    }


    fun performSendImage(imageBase64: String) {
        writeMessage(imageBase64)
        resetImagePickerUi()
    }

}
