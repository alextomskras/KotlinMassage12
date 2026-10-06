package com.example.fess.kotlinmassage1.messages

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.fess.kotlinmassage1.R
import com.example.fess.kotlinmassage1.models.ChatMessage
import com.example.fess.kotlinmassage1.models.User
import com.example.fess.kotlinmassage1.registerlogin.LoginActivity
import com.example.fess.kotlinmassage1.util.DbPaths
import com.example.fess.kotlinmassage1.util.ImageLoader
import com.example.fess.kotlinmassage1.util.ImageUtils
import com.example.fess.kotlinmassage1.util.NotificationHelper
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
import com.xwray.groupie.Item
import com.xwray.groupie.ViewHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatLogActivity : AppCompatActivity() {

    companion object {
        const val TAG = "ChatLog"
        private const val REQUEST_PICK_IMAGE = 0
        /** Формат времени сообщений; единый для всего экрана. */
        private val TIME_FORMAT = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
    }

    private val adapter = GroupAdapter<ViewHolder>()
    private var toUser: User? = null
    /** Сохраняем слушателя + ref, чтобы снять его в onDestroy (раньше утекал вместе с активити). */
    private var messagesListener: ChildEventListener? = null
    private var messagesRef: com.google.firebase.database.DatabaseReference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_log)
        NotificationHelper.ensureChannel(this)

        findViewById<RecyclerView>(R.id.recyclerview_chat_log).adapter = this.adapter

        // ЭКРАНАЦИЯ PUSH-ТАПА: раньше ChatLog открывался с произвольным fromId из
        // Intent без проверки сессии. Теперь без авторизации экран не работает.
        if (FirebaseAuth.getInstance().uid == null) {
            startActivity(Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        toUser = intent.getParcelableExtra<User>(NewMessageActivity.USER_KEY)

        val partner = toUser
        if (partner == null || partner.uid.isEmpty()) {
            // Прямой запуск экрана без выбранного собеседника — возвращаемся к списку чатов
            Toast.makeText(this, "Собеседник не выбран", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        supportActionBar?.title = partner.username

        listenForMessages()

        findViewById<Button>(R.id.send_button_chat_log).setOnClickListener {
            performSendMessage()
        }

        findViewById<Button>(R.id.image_send_button_chat_log).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
            startActivityForResult(intent, REQUEST_PICK_IMAGE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            findViewById<Button>(R.id.image_send_button_chat_log).alpha = 0f
            // Сжатие + base64 — в фоне (ImageLoader), результат приходит в main.
            // Картинку НЕ грузим в Firebase Storage — кладём base64 прямо в БД.
            ImageLoader.compressUri(this, uri) { b64 ->
                if (isFinishing || isDestroyed) return@compressUri
                if (b64 == null) {
                    Toast.makeText(this, "Не удалось обработать картинку", Toast.LENGTH_SHORT).show()
                } else {
                    writeMessage(b64, ChatMessage.TYPE_IMAGE)
                }
                resetImagePickerUi()
            }
        }
    }

    private fun resetImagePickerUi() {
        findViewById<de.hdodenhof.circleimageview.CircleImageView>(R.id.select_photoview_image_send)
            .setImageBitmap(null)
        findViewById<Button>(R.id.image_send_button_chat_log).alpha = 1f
    }

    /**
     * Рендер сообщения по ЯВНОМУ типу (ChatMessage.type с обратной совместимостью):
     * image -> Kartinka*, иначе Chat*. Декод base64 внутри items — через ImageLoader (фон).
     */
    private fun buildChatItem(chatMessage: ChatMessage, isIncoming: Boolean): Item<ViewHolder> {
        val timeStr = TIME_FORMAT.format(Date(chatMessage.timestamp * 1000))
        val user = if (isIncoming) toUser!! else (LatestMessagesActivity.currentUser ?: toUser!!)
        return when {
            chatMessage.type == ChatMessage.TYPE_IMAGE && isIncoming ->
                KartinkaToItem(chatMessage.text, user, timeStr)
            chatMessage.type == ChatMessage.TYPE_IMAGE ->
                KartinkaFromItem(chatMessage.text, user, timeStr)
            isIncoming -> ChatToItem(chatMessage.text, user, timeStr)
            else -> ChatFromItem(chatMessage.text, user, timeStr)
        }
    }

    private fun listenForMessages() {
        val fromId = FirebaseAuth.getInstance().uid ?: return
        val toId = toUser?.uid ?: return
        val ref = FirebaseDatabase.getInstance().getReference(DbPaths.conversation(fromId, toId))

        val listener = object : ChildEventListener {
            override fun onChildAdded(p0: DataSnapshot, p1: String?) {
                val chatMessage = p0.getValue(ChatMessage::class.java) ?: return
                val isIncoming = chatMessage.fromId != FirebaseAuth.getInstance().uid
                adapter.add(buildChatItem(chatMessage, isIncoming))
                scrollToBottom()
            }

            override fun onCancelled(p0: DatabaseError) {
                Log.w(TAG, "messages listener cancelled: ${p0.message}")
            }

            override fun onChildChanged(p0: DataSnapshot, p1: String?) {}
            override fun onChildMoved(p0: DataSnapshot, p1: String?) {}
            override fun onChildRemoved(p0: DataSnapshot) {}
        }
        messagesListener = listener
        messagesRef = ref
        ref.addChildEventListener(listener)
    }

    private fun scrollToBottom() {
        if (adapter.itemCount > 0) {
            findViewById<RecyclerView>(R.id.recyclerview_chat_log)
                .scrollToPosition(adapter.itemCount - 1)
        }
    }

    /**
     * Единая точка записи сообщения: ОДИН атомарный multi-path update
     * (user-messages в обе стороны + latest-messages + задача push в /outbox).
     * Раньше было 5 независимых setValue — при обрыве сети диалог и «последнее
     * сообщение» могли разъехаться, а push потеряться.
     */
    private fun writeMessage(text: String, msgType: String) {
        val fromId = FirebaseAuth.getInstance().uid ?: return
        val toId = toUser?.uid ?: return

        val db = FirebaseDatabase.getInstance().reference
        val messageRef = db.child(DbPaths.conversation(fromId, toId)).push()
        val mirrorKey = db.child(DbPaths.conversation(toId, fromId)).push().key ?: return
        val outboxKey = db.child(DbPaths.OUTBOX).push().key ?: return

        val nowSec = System.currentTimeMillis() / 1000
        val chatMessage = ChatMessage(messageRef.key!!, text, fromId, toId, nowSec, msgType)

        // preview для пуша: base64 туда НЕ кладём (лимит payload 4 КБ)
        val preview = if (msgType == ChatMessage.TYPE_IMAGE) "📷 Картинка" else text.take(120)

        val updates = HashMap<String, Any>()
        updates["/${DbPaths.conversation(fromId, toId)}/${messageRef.key}"] = chatMessage
        updates["/${DbPaths.conversation(toId, fromId)}/$mirrorKey"] = chatMessage
        updates["/${DbPaths.latestConversation(fromId, toId)}"] = chatMessage
        updates["/${DbPaths.latestConversation(toId, fromId)}"] = chatMessage
        updates["/${DbPaths.OUTBOX}/$outboxKey"] = mapOf(
            "msgId" to chatMessage.id,
            "fromId" to fromId,
            "toId" to toId,
            "type" to msgType,
            "preview" to preview,
            "timestamp" to nowSec
        )

        db.updateChildren(updates)
            .addOnSuccessListener {
                Log.d(TAG, "Saved chat message: ${chatMessage.id}")
                findViewById<EditText>(R.id.edittext_chat_log).text.clear()
                scrollToBottom()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to save message", e)
                Toast.makeText(this, "Сообщение не отправлено: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun performSendMessage() {
        val text = findViewById<EditText>(R.id.edittext_chat_log).text.toString()
        if (text.isEmpty()) return
        writeMessage(text, ChatMessage.TYPE_TEXT)
    }

    override fun onDestroy() {
        // Снимаем слушателя — иначе активити утекает в Firebase навсегда
        val listener = messagesListener
        if (listener != null) {
            messagesRef?.removeEventListener(listener)
        }
        messagesListener = null
        messagesRef = null
        super.onDestroy()
    }
}
