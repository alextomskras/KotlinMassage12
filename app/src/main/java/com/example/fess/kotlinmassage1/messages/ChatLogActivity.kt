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
import com.example.fess.kotlinmassage1.util.ImageCache
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
import com.example.fess.kotlinmassage1.views.ChatRecyclerAdapter
import com.example.fess.kotlinmassage1.views.chatItemFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatLogActivity : AppCompatActivity() {

    companion object {
        const val TAG = "ChatLog"
        private const val REQUEST_PICK_IMAGE = 0
        /** TTL relay-зоны картинок: 7 дней (согласовано с backend/app/transfer_cleanup.py). */
        const val TRANSFER_TTL_SEC = 7L * 24 * 3600
        /** Формат времени сообщений; единый для всего экрана. */
        private val TIME_FORMAT = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
    }

    private val adapter = ChatRecyclerAdapter()
    private var toUser: User? = null
    /** Сохраняем слушателя + ref, чтобы снять его в onDestroy (раньше утекал вместе с активити). */
    private var messagesListener: ChildEventListener? = null
    private var messagesRef: com.google.firebase.database.DatabaseReference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_log)
        NotificationHelper.ensureChannel(this)

        findViewById<RecyclerView>(R.id.recyclerview_chat_log).adapter = this.adapter
        // строкам нужен контекст для локального кэша картинок (ImageCache)
        adapter.rowContextProvider = { this }

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
            var uri = data?.data ?: return
            // Подстраховка: если picker вернул clip-data без основного data (бывает у Google Photos),
            // берём первую строку из ClipData.
            if (uri.toString().isEmpty()) {
                uri = data.clipData?.getItemAt(0)?.uri ?: return
            }
            // Постоянная URI-разрешение на content:// — иначе ContentResolver имеет право вернуть null
            // при фоновом чтении (Samsung Gallery / Android 13+).
            try {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // не все URI поддерживают persistable — не страшно, читаем как есть
            }
            findViewById<Button>(R.id.image_send_button_chat_log).alpha = 0f
            // Сжатие + base64 — в фоне (ImageLoader), результат приходит в main.
            // Картинку НЕ грузим в Firebase Storage — кладём base64 прямо в БД.
            ImageLoader.compressUriDetailed(this, uri) { result ->
                if (isFinishing || isDestroyed) return@compressUriDetailed
                when (result) {
                    is ImageUtils.CompressResult.Ok -> writeMessage(result.base64, ChatMessage.TYPE_IMAGE)
                    is ImageUtils.CompressResult.TooLarge -> Toast.makeText(
                        this, "Картинка слишком большая (>10 МБ даже после сжатия)", Toast.LENGTH_SHORT
                    ).show()
                    ImageUtils.CompressResult.Failed -> Toast.makeText(
                        this, "Не удалось обработать картинку", Toast.LENGTH_SHORT
                    ).show()
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
    private fun buildChatItem(chatMessage: ChatMessage, isIncoming: Boolean): com.example.fess.kotlinmassage1.views.ChatRowDelegate {
        // timestamp<=0 (старые/битые записи) -> текущее время вместо 01.01.1970
        val ts = if (chatMessage.timestamp > 0) chatMessage.timestamp else System.currentTimeMillis() / 1000
        val timeStr = TIME_FORMAT.format(Date(ts * 1000))
        val user = if (isIncoming) toUser!! else (LatestMessagesActivity.currentUser ?: toUser!!)
        return chatItemFor(chatMessage, user, isIncoming, timeStr)
    }

    private fun listenForMessages() {
        val fromId = FirebaseAuth.getInstance().uid ?: return
        val toId = toUser?.uid ?: return
        val ref = FirebaseDatabase.getInstance().getReference(DbPaths.conversation(fromId, toId))

        val listener = object : ChildEventListener {
            override fun onChildAdded(p0: DataSnapshot, p1: String?) {
                // Защита от «пустых» узлов: бэкенд-релей (пишет по admin-правам)
                // мог оставить в зеркале чата ноду без данных сообщения
                // (например {"delivered": true}) -> клиент рисовал пустое
                // сообщение с датой 01.01.1970. Такие узлы игнорируем.
                if (!p0.hasChild("text") && !p0.hasChild("fromId")) return
                val chatMessage = p0.getValue(ChatMessage::class.java) ?: return
                if ((chatMessage.text.isNullOrEmpty() || chatMessage.text == "-1") && chatMessage.fromId.isEmpty()) return
                val isIncoming = chatMessage.fromId != FirebaseAuth.getInstance().uid
                adapter.append(buildChatItem(chatMessage, isIncoming))
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

        val nowSec = System.currentTimeMillis() / 1000

        // Страховка на случай обхода лимита (старый клиент и т.п.): в БД не пишем
        // base64 длиннее MAX_BASE64_BYTES — иначе раздуваем RTDB.
        if (msgType == ChatMessage.TYPE_IMAGE && text.length > ImageUtils.MAX_BASE64_BYTES) {
            Log.e(TAG, "Изображение ${text.length} B > лимита ${ImageUtils.MAX_BASE64_BYTES} B — запись отклонена")
            android.widget.Toast.makeText(this, "Картинка слишком большая для отправки", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // preview для пуша: base64 туда НЕ кладём (лимит payload 4 КБ)
        val preview = if (msgType == ChatMessage.TYPE_IMAGE) "📷 Картинка" else text.take(120)

        val updates = HashMap<String, Any>()

        // --- Новый relay-формат для картинок: тело base64 живёт ОДИН раз в
        // /transfers/<id> (7 дней), в сообщениях только transferRef + превью.
        // Это убирает дубли base64 (раньше одна картинка лежала 4 раза: два
        // зеркала чата + latest-messages x2).
        val chatMessage = if (msgType == ChatMessage.TYPE_IMAGE) {
            val id = messageRef.key!!
            updates["/${DbPaths.transfer(id)}"] = mapOf(
                "fromId" to fromId,
                "toIds" to listOf(toId),
                "type" to "image",
                "mime" to ImageUtils.mimeOf(text),  // image/webp (новый) / image/jpeg (legacy)
                "sizeBytes" to text.length,
                "createdAt" to nowSec,
                "expiresAt" to nowSec + TRANSFER_TTL_SEC,
                "data" to text,
                "deliveredTo" to mapOf(fromId to nowSec) // отправитель «уже имеет»
            )
            ChatMessage(id, preview, fromId, toId, nowSec, msgType, transferRef = id)
        } else {
            ChatMessage(messageRef.key!!, text, fromId, toId, nowSec, msgType)
        }

        // Ключ задачи в /outbox совпадает с id сообщения: релей идемпотентно читает и удаляет его.
        val outboxKey = chatMessage.id

        updates["/${DbPaths.conversation(fromId, toId)}/${messageRef.key}"] = chatMessage
        updates["/${DbPaths.conversation(toId, fromId)}/$mirrorKey"] = chatMessage
        updates["/${DbPaths.latestConversation(fromId, toId)}"] = chatMessage
        updates["/${DbPaths.latestConversation(toId, fromId)}"] = chatMessage
        updates["/${DbPaths.OUTBOX}/$outboxKey"] = mapOf(
            // формат согласован с backend/app/outbox_relay.py: поиск uid получателя
            // идёт по полю "to" (username), текст — "text", тип — "msgType"=="IMAGE"
            "msgId" to chatMessage.id,
            "fromId" to fromId,
            "toId" to toId,
            "to" to (toUser?.username ?: ""),
            "senderName" to (LatestMessagesActivity.currentUser?.username ?: ""),
            "text" to preview,
            "type" to msgType,
            "msgType" to if (msgType == ChatMessage.TYPE_IMAGE) "IMAGE" else "TEXT",
            "preview" to preview,
            "timestamp" to nowSec,
            "sent" to false
        )

        db.updateChildren(updates)
            .addOnSuccessListener {
                Log.d(TAG, "Saved chat message: ${chatMessage.id}")
                // Свою картинку сразу кладём в локальный кэш: после очистки
                // трансфера история чата у отправителя останется полной.
                if (msgType == ChatMessage.TYPE_IMAGE) {
                    ImageCache.putFromBase64(this, chatMessage.id, text)
                }
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
