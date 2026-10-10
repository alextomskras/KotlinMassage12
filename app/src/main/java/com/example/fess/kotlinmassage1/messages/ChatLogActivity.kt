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
import com.example.fess.kotlinmassage1.util.CryptoBridge
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
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
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

        // E2EE: греем кэш pubkey собеседника — картинки в фоне (ImageLoader)
        // шифруются/расшифровываются без сетевого RTT на каждое сообщение
        CryptoBridge.warmPartnerKey(partner.uid)

        listenForMessages()

        // Долгое нажатие на СВОЁ сообщение -> «Изменить / Удалить для всех».
        // Колбэк объявлен в ChatRecyclerAdapter как СВОЙСТВО:
        //   var onItemLongClickListener: ((Int, ChatRowDelegate) -> Boolean)? = null
        // Обработка вынесена в отдельную функцию — в лямбде нельзя писать
        // return@onItemLongClickListener (label = имя переменной, а не функции).
        adapter.onItemLongClickListener = ::handleItemLongClick

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
        val item = chatItemFor(chatMessage, user, isIncoming, timeStr)
        // Ключ узла в НАШЕМ зеркале отличается от chatMessage.id (тот — id в зеркале
        // автора). Нужен для пересборки строки по onChildChanged и для readAt.
        when (item) {
            is com.example.fess.kotlinmassage1.views.TextItem -> item.dbId = snapshotKey
            is com.example.fess.kotlinmassage1.views.DeletedTextItem -> item.dbId = snapshotKey
            is com.example.fess.kotlinmassage1.views.ChatFromItem -> item.dbId = snapshotKey
            is com.example.fess.kotlinmassage1.views.ChatToItem -> item.dbId = snapshotKey
            is com.example.fess.kotlinmassage1.views.KartinkaFromItem -> item.dbId = snapshotKey
            is com.example.fess.kotlinmassage1.views.KartinkaToItem -> item.dbId = snapshotKey
        }
        return item
    }

    /** Ключ последнего обработанного снапшота (id сообщения в нашем зеркале). */
    private var snapshotKey: String = ""

    private fun listenForMessages() {
        val fromId = FirebaseAuth.getInstance().uid ?: return
        val toId = toUser?.uid ?: return
        val ref = FirebaseDatabase.getInstance().getReference(DbPaths.conversation(fromId, toId))

        val listener = object : ChildEventListener {
            override fun onChildAdded(p0: DataSnapshot, p1: String?) {
                // Ключ запоминаем ДО защитных return: markIncomingAsRead обязан
                // отметить прочтение даже «технических» узлов (например релей
                // дописал delivered:true) — иначе у отправителя галочка так и
                // осталась бы одной серой, хотя адресат сообщение получил.
                snapshotKey = p0.key ?: ""
                // Защита от «пустых» узлов: бэкенд-релей (пишет по admin-правам)
                // мог оставить в зеркале чата ноду без данных сообщения
                // (например {"delivered": true}) -> клиент рисовал пустое
                // сообщение с датой 01.01.1970. Такие узлы не рисуем.
                if (!p0.hasChild("text") && !p0.hasChild("fromId")) {
                    markIncomingIfUnread(p0)
                    return
                }
                val chatMessage = p0.getValue(ChatMessage::class.java) ?: return
                if ((chatMessage.text.isNullOrEmpty() || chatMessage.text == "-1") && chatMessage.fromId.isEmpty()) {
                    markIncomingIfUnread(p0)
                    return
                }
                val isIncoming = chatMessage.fromId != FirebaseAuth.getInstance().uid
                adapter.append(buildChatItem(chatMessage, isIncoming))
                scrollToBottom()
                // Read receipt: входящее и ещё не отмеченное -> ставим readAt
                // В СВОЁМ зеркале (/user-messages/{me}/{partner}/{msgId});
                // отправитель подтянет это своим read-tracker'ом.
                // Отличаем «не прочитано» от «поля нет вовсе»: новые сообщения
                // автор пишет с readAt=0 в копию собеседника (см. writeMessageToDb),
                // legacy-записи поля не имеют -> их НЕ трогаем (без реворка).
                if (isIncoming && chatMessage.readAt == 0L) {
                    markIncomingAsRead(snapshotKey)
                }
            }

            override fun onCancelled(p0: DatabaseError) {
                Log.w(TAG, "messages listener cancelled: ${p0.message}")
            }

            override fun onChildChanged(p0: DataSnapshot, p1: String?) {
                // Soft-delete / редактирование / readAt приходят как change узла
                snapshotKey = p0.key ?: ""
                val chatMessage = p0.getValue(ChatMessage::class.java) ?: return
                applyNodeChange(p0.key ?: "", chatMessage)
            }
            override fun onChildMoved(p0: DataSnapshot, p1: String?) {}
            override fun onChildRemoved(p0: DataSnapshot) {}
        }
        messagesListener = listener
        messagesRef = ref
        ref.addChildEventListener(listener)

        // Отдельный read-tracker по НАШЕМУ зеркалу. Штатный listener выше
        // слушает DbPaths.conversation(me, partner) — для исходящих это папка
        // собеседника, а получатель пишет readAt в СВОЁ зеркало
        // (/user-messages/{receiver}/{sender}/{mirrorKey}). Без этого трека
        // изменение readAt у исходящего сообщения вообще не доходило до
        // клиента отправителя: галочка навсегда оставалась одной серой.
        startReadTracker(FirebaseAuth.getInstance().uid ?: return, toId)
    }

    /** Трекер readAt/readReceipt в нашем зеркале диалога. */
    private var readTrackerRef: DatabaseReference? = null
    private var readTracker: ValueEventListener? = null

    private fun startReadTracker(myUid: String, otherUid: String) {
        stopReadTracker()
        val ref = FirebaseDatabase.getInstance().getReference(DbPaths.conversation(myUid, otherUid))
        // ВАЖНО: именно ValueEventListener на весь узел диалога, а НЕ
        // ChildEventListener. Причина: получатель пишет readAt в СВОЁ зеркало —
        // это ДОЧЕРНИЙ лист nodeKey/readAt. Для отправителя (мы слушаем своё
        // /user-messages/{me}/{partner}) такой deep-path не является прямым
        // ребёнком и ChildEventListener его никогда не увидит — поэтому
        // галочки и не менялись. onValueEvent получает всё дерево целиком и
        // сравнивает readAt каждого узла с тем, что уже отрисовано.
        val tracker = object : ValueEventListener {
            override fun onDataChange(p0: DataSnapshot) {
                for (c in p0.children) applyReadFromMirror(c)
            }
            override fun onCancelled(p0: DatabaseError) {
                Log.w(TAG, "readTracker cancelled: ${p0.message}")
            }
        }
        readTrackerRef = ref
        readTracker = tracker
        ref.addValueEventListener(tracker)
    }

    private fun stopReadTracker() {
        readTracker?.let { t -> readTrackerRef?.removeEventListener(t) }
        readTrackerRef = null
        readTracker = null
    }

    /**
     * Узел из нашего зеркала: если там есть readAt/readReceipt (>0), красим
     * двойную синюю галочку у соответствующей исходящей строки. Строку ищем
     * по dbId (ключ в нашем зеркале), затем по msgId (fallback).
     */
    private fun applyReadFromMirror(c: DataSnapshot) {
        val key = c.key ?: return
        val readValue = (c.child("readAt").getValue(Long::class.java)
            ?: c.child("readReceipt").getValue(Long::class.java)) ?: return
        if (readValue <= 0) return
        // Быстрая проверка: если в списке уже нет строк с этим ключом — пропускаем.
        val pos = adapter.findRowPosition(key)
        if (pos >= 0) {
            when (val item = adapter.itemAt(pos)) {
                is com.example.fess.kotlinmassage1.views.ChatFromItem ->
                    if (item.readAt <= 0) { item.readAt = readValue; adapter.updateAt(pos) }
                is com.example.fess.kotlinmassage1.views.TextItem ->
                    if (!item.isIncomingForMenu() && item.readAt <= 0) { item.readAt = readValue; adapter.updateAt(pos) }
                is com.example.fess.kotlinmassage1.views.KartinkaFromItem ->
                    if (item.readAt <= 0) { item.readAt = readValue; adapter.updateAt(pos) }
                else -> {}
            }
            return
        }
        // fallback: ключ может совпадать с msgId (id в зеркале автора)
        val byMsg = adapter.outgoingReadRows().firstOrNull { (_, d) -> d.msgId == key }
        if (byMsg != null && byMsg.second.readAt <= 0) {
            byMsg.second.readAt = readValue
            adapter.updateAt(byMsg.first)
        }
    }

    /** Точечное обновление живой строки по изменению её узла в зеркале автора. */
    private fun applyNodeChange(nodeKey: String, chatMessage: ChatMessage) {
        val pos = adapter.findRowPosition(nodeKey)
        if (pos < 0) return
        when (val item = adapter.itemAt(pos)) {
            is com.example.fess.kotlinmassage1.views.ChatFromItem -> {
                if (chatMessage.readAt > 0) item.readAt = chatMessage.readAt
                item.editTime = chatMessage.editTime
                item.editedText = chatMessage.editedText
                item.envMirror = if (chatMessage.editedText != null) chatMessage.envEdited else chatMessage.env
                item.deleted = chatMessage.deleted
            }
            is com.example.fess.kotlinmassage1.views.KartinkaFromItem -> {
                if (chatMessage.readAt > 0) item.readAt = chatMessage.readAt
                item.deleted = chatMessage.deleted
            }
            is com.example.fess.kotlinmassage1.views.KartinkaToItem -> {
                item.deleted = chatMessage.deleted
            }
            is com.example.fess.kotlinmassage1.views.ChatToItem -> {
                item.deleted = chatMessage.deleted
            }
            is com.example.fess.kotlinmassage1.views.TextItem -> {
                if (chatMessage.readAt > 0) item.readAt = chatMessage.readAt
                item.editTime = chatMessage.editTime
                item.deleted = chatMessage.deleted
            }
        }
        adapter.updateAt(pos)
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
        if (msgType == ChatMessage.TYPE_TEXT) {
            // E2EE: шифруем текст под pubkey получателя; если ключа нет / TOFU не дал —
            // пишем открытым текстом (обратная совместимость со старыми клиентами).
            val fromId = FirebaseAuth.getInstance().uid ?: return
            val toId = toUser?.uid ?: return
            val db = FirebaseDatabase.getInstance().reference
            val msgId = db.child(DbPaths.conversation(fromId, toId)).push().key ?: return
            CryptoBridge.encryptTextForSend(
                this, toId, text, msgId,
                onReady = { envelopeB64 -> writeMessageToDb(envelopeB64, msgType, encrypted = true, forcedId = msgId) },
                onFallback = { reason ->
                    Log.w(TAG, "E2EE fallback (plaintext): $reason")
                    writeMessageToDb(text, msgType, encrypted = false, forcedId = msgId)
                }
            )
        } else if (msgType == ChatMessage.TYPE_IMAGE) {
            // E2EE картинок: сначала получаем pubkey собеседника и шифруем тело.
            // Конверт кладём В САМОЕ СООБЩЕНИЕ (поле env) — оно живёт вечно,
            // а relay-нода /transfers удаляется через 7 дней. Если делать наоборот
            // (ключ читать синхронно из кэша), при первом открытии диалога кэш пуст и
            // картинка уходит открытой, а env нет — отправитель остаётся с заглушкой.
            val fromId = FirebaseAuth.getInstance().uid ?: return
            val toId = toUser?.uid ?: return
            val db = FirebaseDatabase.getInstance().reference
            val msgId = db.child(DbPaths.conversation(fromId, toId)).push().key ?: return
            CryptoBridge.prepareImageEnvelope(
                toId, msgId, text,
                onReady = { envelopeB64 ->
                    if (!isFinishing && !isDestroyed) writeMessageToDb(text, msgType, encrypted = false, forcedId = msgId, imageEnv = envelopeB64)
                },
                onFallback = { reason ->
                    Log.w(TAG, "E2EE image fallback (plaintext transfer): $reason")
                    if (!isFinishing && !isDestroyed) writeMessageToDb(text, msgType, encrypted = false, forcedId = msgId, imageEnv = null)
                }
            )
        } else {
            writeMessageToDb(text, msgType, encrypted = false)
        }
    }

    /**
     * Единая точка записи сообщения: ОДИН атомарный multi-path update
     * (user-messages в обе стороны + latest-messages + задача push в /outbox).
     * Раньше было 5 независимых setValue — при обрыве сети диалог и «последнее
     * сообщение» могли разъехаться, а push потеряться.
     *
     * ВАЖНО ПРО ГАЛОЧКИ: автор пишет readAt=-1 в СВОЁ зеркало (nodeKey), а в
     * ЗЕРКАЛО СОБЕСЕДНИКА — readAt=0. Разница нужна, чтобы получатель мог по
     * значению отличить «ещё не прочитано» от «нет поля у legacy-сообщений»:
     * markIncomingAsRead срабатывает только когда поле явно присутствует и
     * равно 0. Иначе на старых переписках receipt писался бы заново при каждом
     * открытии чата (лишние записи), а на новых — не писался вовсе.
     */
    private fun writeMessageToDb(text: String, msgType: String, encrypted: Boolean, forcedId: String? = null, imageEnv: String? = null) {
        val fromId = FirebaseAuth.getInstance().uid ?: return
        val toId = toUser?.uid ?: return

        val db = FirebaseDatabase.getInstance().reference
        // id уже выбран на этапе шифрования текста (HKDF salt = msgId) — переиспользуем
        val messageRef = if (forcedId != null) db.child(DbPaths.conversation(fromId, toId)).child(forcedId)
                         else db.child(DbPaths.conversation(fromId, toId)).push()
        val mirrorKey = db.child(DbPaths.conversation(toId, fromId)).push().key ?: return

        val nowSec = System.currentTimeMillis() / 1000

        // Страховка на случай обхода лимита (старый клиент и т.п.): в БД не пишем
        // base64 длиннее MAX_BASE64_BYTES — иначе раздуваем RTDB.
        if (msgType == ChatMessage.TYPE_IMAGE && text.length > ImageUtils.MAX_BASE64_BYTES) {
            Log.e(TAG, "Изображение ${text.length} B > лимита ${ImageUtils.MAX_BASE64_BYTES} B — запись отклонена")
            android.widget.Toast.makeText(this, "Картинка слишком большая для отправки", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // preview для пуша: base64/шифртекст туда НЕ кладём (лимит payload 4 КБ);
        // для шифрованного текста содержимое недоступно — только сам факт сообщения
        val preview = when {
            msgType == ChatMessage.TYPE_IMAGE -> "📷 Картинка"
            encrypted -> CryptoBridge.ENCRYPTED_PREVIEW
            else -> text.take(120)
        }

        val updates = HashMap<String, Any>()

        // --- Новый relay-формат для картинок: тело живёт ОДИН раз в /transfers/<id>
        // (7 дней), в сообщениях только transferRef + превью. Это убирает дубли
        // base64 (раньше одна картинка лежала 4 раза: два зеркала чата + latest x2).
        // E2EE v1: если у получателя есть pubkey — тело шифруется (в ноде поля
        // enc/epk/alg вместо открытой data); иначе пишем открытую data (fallback).
        val chatMessage = if (msgType == ChatMessage.TYPE_IMAGE) {
            val id = messageRef.key!!
            // Relay-нода собирается из receiver-конверта (шифр под pubkey
            // получателя), подготовленного при отправке. В msg.env лежит
            // sender-конверт — по нему отправитель читает свою картинку.
            val receiverEnv = imageEnv?.let { CryptoBridge.takeReceiverEnvelope(id) }
            val encNode = CryptoBridge.buildTransferNodeFromEnv(receiverEnv, id, text, fromId, toId, nowSec, TRANSFER_TTL_SEC)
            if (encNode != null) {
                updates["/${DbPaths.transfer(id)}"] = encNode
            } else {
                updates["/${DbPaths.transfer(id)}"] = mapOf(
                    "fromId" to fromId,
                    "toIds" to listOf(toId),
                    "type" to "image",
                    "mime" to ImageUtils.mimeOf(text),
                    "sizeBytes" to text.length,
                    "createdAt" to nowSec,
                    "expiresAt" to nowSec + TRANSFER_TTL_SEC,
                    "data" to text,
                    "deliveredTo" to mapOf(fromId to nowSec) // отправитель «уже имеет»
                )
            }
            ChatMessage(id, preview, fromId, toId, nowSec, msgType, transferRef = id, env = imageEnv)
        } else {
            // Зеркало НЕ удаляем из кэша при чтении: если updateChildren не
            // подтвердится сервером (offline-очередь), оно останется и повторная
            // запись его возьмёт. Чистим через confirmTextMirror в onDisconnect.
            val textMirror = if (encrypted) forcedId?.let { com.example.fess.kotlinmassage1.util.CryptoBridge.peekTextMirror(it) } else null
            ChatMessage(messageRef.key!!, text, fromId, toId, nowSec, msgType, enc = encrypted, env = textMirror)
        }

        // Ключ задачи в /outbox совпадает с id сообщения: релей идемпотентно читает и удаляет его.
        val outboxKey = chatMessage.id

        updates["/${DbPaths.conversation(fromId, toId)}/${messageRef.key}"] = chatMessage
        // Копия собеседника: readAt=0 — сигнал «не прочитано, отметь при просмотре».
        // Поле присутствует явно (в отличие от legacy-записей без readAt), поэтому
        // получатель гарантированно вызовет markIncomingAsRead при открытии чата.
        val partnerCopy = HashMap<String, Any>(chatMessageToMap(chatMessage)).apply { put("readAt", 0L) }
        updates["/${DbPaths.conversation(toId, fromId)}/$mirrorKey"] = partnerCopy
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
                // Сервер принял запись — selfless-зеркало текста больше не нужно
                // держать в памяти процесса (см. peekTextMirror).
                if (msgType == ChatMessage.TYPE_TEXT && encrypted) {
                    forcedId?.let { CryptoBridge.confirmTextMirror(it) }
                }
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

    /**
     * Отметка прочтения для снапшотов, не прошедших фильтр отрисовки: если
     * узел принадлежит НЕ нам и readAt ещё нет — пишем receipt. Молча ловим
     * Permission denied (у технических nod без fromId правила RTDB могут не
     * пускать в запись) — это не должно ронять UI.
     */
    private fun markIncomingIfUnread(p0: DataSnapshot) {
        val myUid = FirebaseAuth.getInstance().uid ?: return
        val fromId = p0.child("fromId").getValue(String::class.java) ?: return
        if (fromId == myUid) return
        // Пишем receipt только если поле явно присутствует и равно 0 — иначе
        // legacy-узлы без readAt получали бы повторную запись при каждом открытии.
        val hasRead = (p0.child("readAt").getValue(Long::class.java) ?: -1L) > 0
        val marked = (p0.child("readAt").getValue(Long::class.java) ?: -2L) == 0L
        if (hasRead || !marked) return
        try {
            markIncomingAsRead(p0.key ?: return)
        } catch (e: Exception) {
            Log.w(TAG, "markIncomingIfUnread skipped: ${e.message}")
        }
    }

    /**
     * Read receipt: получатель отмечает входящее сообщение прочитанным — пишет
     * readAt только в свою копию зеркала (правила RTDB: запись в свой узел).
     * Отправитель видит change через свой ChildEventListener и красит двойную
     * синюю галочку. Идемпотентно: повторная отметка не пишется.
     */
    private fun markIncomingAsRead(nodeKey: String) {
        val myUid = FirebaseAuth.getInstance().uid ?: return
        val otherUid = toUser?.uid ?: return
        if (nodeKey.isEmpty()) return
        FirebaseDatabase.getInstance().reference
            .child("${DbPaths.conversation(myUid, otherUid)}/$nodeKey/readAt")
            .setValue(System.currentTimeMillis() / 1000)
            .addOnFailureListener { e -> Log.w(TAG, "readAt write failed: ${e.message}") }
    }

    /**
     * Долгое нажатие на строку чата (подписывается в onCreate как колбэк адаптера).
     * Возвращает true, если обработали (показали меню), false — чужое сообщение.
     */
    private fun handleItemLongClick(position: Int, item: com.example.fess.kotlinmassage1.views.ChatRowDelegate): Boolean {
        val mine = item is com.example.fess.kotlinmassage1.views.ChatFromItem ||
            (item is com.example.fess.kotlinmassage1.views.TextItem && !item.isIncomingForMenu()) ||
            (item is com.example.fess.kotlinmassage1.views.KartinkaFromItem)
        if (!mine) return false
        // Для картинок — только удаление; для текста — правка + удаление.
        when (item) {
            is com.example.fess.kotlinmassage1.views.KartinkaFromItem ->
                confirmDeleteImage(item.dbId.ifEmpty { item.msgId })
            else -> showMyMessageMenu(
                plain = (item as? com.example.fess.kotlinmassage1.views.ChatFromItem)?.plainForEditing()
                    ?: (item as? com.example.fess.kotlinmassage1.views.TextItem)?.plainForEditing(),
                editable = item is com.example.fess.kotlinmassage1.views.ChatFromItem ||
                    item is com.example.fess.kotlinmassage1.views.TextItem,
                nodeKey = (item as? com.example.fess.kotlinmassage1.views.ChatFromItem)?.dbId
                    ?: (item as? com.example.fess.kotlinmassage1.views.TextItem)?.dbId ?: "",
                mirrorMsgId = (item as? com.example.fess.kotlinmassage1.views.ChatFromItem)?.msgId
                    ?: (item as? com.example.fess.kotlinmassage1.views.TextItem)?.msgId ?: ""
            )
        }
        return true
    }

    private fun showMyMessageMenu(plain: String?, editable: Boolean, nodeKey: String, mirrorMsgId: String) {
        val options = if (editable) arrayOf("✏️ Изменить", "🗑 Удалить для всех")
                      else arrayOf("🗑 Удалить для всех")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                val idx = if (editable) which else which + 1
                when (idx) {
                    0 -> startEditMessage(plain, nodeKey, mirrorMsgId)
                    else -> softDeleteMessage(nodeKey, mirrorMsgId)
                }
            }
            .show()
    }

    private fun confirmDeleteImage(nodeKey: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage("Удалить картинку для всех?")
            .setPositiveButton("Удалить") { _, _ -> softDeleteMessage(nodeKey, nodeKey) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startEditMessage(plain: String?, nodeKey: String, mirrorMsgId: String) {
        val input = android.widget.EditText(this).apply {
            setText(plain ?: "")
            setSelection(length())
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Изменить сообщение")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newText = input.text.toString()
                if (newText.isNotEmpty()) applyMessageEdit(nodeKey, mirrorMsgId, newText)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Правка текста: перешифровываем НОВЫЙ эфемерный конверт под pubkey получателя
     *  (+ свежее selfless-зеркало себе) и пишем editedText/editTime/envEdited в обе копии. */
    private fun applyMessageEdit(nodeKey: String, mirrorMsgId: String, newText: String) {
        val myUid = FirebaseAuth.getInstance().uid ?: return
        val otherUid = toUser?.uid ?: return
        if (nodeKey.isEmpty()) return
        val db = FirebaseDatabase.getInstance().reference
        // Новый эфемерный конверт генерируем под НОВЫМ msgId (уникальный push-key):
        // AAD конверта привязан к msgId, старый id занят оригиналом.
        val newEnvId = db.push().key ?: return
        CryptoBridge.encryptTextForSend(
            this, otherUid, newText, newEnvId,
            onReady = { envelopeB64 ->
                // encryptTextForSend уже положил свежее selfless-зеркало в кэш —
                // забираем его для поля envEdited (нужно, чтобы самому видеть правку).
                val mirror = CryptoBridge.takeTextMirror(newEnvId) ?: ""
                val ts = System.currentTimeMillis() / 1000
                // ВАЖНО: у каждой стороны свой ключ узла (push-id при двойной записи).
                // nodeKey — ключ В НАШЕМ зеркале; в зеркале собеседника ищем его узел
                // по полю id == mirrorMsgId (авторское id совпадает в обеих копиях).
                val updates = mapOf<String, Any>(
                    "${DbPaths.conversation(myUid, otherUid)}/$nodeKey/editedText" to envelopeB64,
                    "${DbPaths.conversation(myUid, otherUid)}/$nodeKey/editTime" to ts,
                    // envEdited пишем ВСЕГДА (даже ""), чтобы stale-зеркало оригинала
                    // в msg.env не перекрывало новую правку при чтении.
                    "${DbPaths.conversation(myUid, otherUid)}/$nodeKey/envEdited" to mirror
                )
                db.updateChildren(updates)
                    .addOnSuccessListener {
                        CryptoBridge.confirmTextEdit(newEnvId, nodeKey)
                        syncEditToPartnerMirror(mirrorMsgId, envelopeB64, ts)
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Правка не сохранена: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            },
            onFallback = { reason ->
                Log.w(TAG, "edit E2EE fallback: $reason")
                // plaintext-правка (получатель без pubkey): то же, но без env
                val ts = System.currentTimeMillis() / 1000
                val updates = mapOf<String, Any>(
                    "${DbPaths.conversation(myUid, otherUid)}/$nodeKey/editedText" to newText,
                    "${DbPaths.conversation(myUid, otherUid)}/$nodeKey/editTime" to ts
                )
                db.updateChildren(updates).addOnSuccessListener {
                    syncEditToPartnerMirror(mirrorMsgId, newText, ts)
                }
            }
        )
    }

    /** Soft-delete своего сообщения: флаг deleted во всех копиях + relay-ноде. */
    private fun softDeleteMessage(nodeKey: String, mirrorMsgId: String) {
        val myUid = FirebaseAuth.getInstance().uid ?: return
        val otherUid = toUser?.uid ?: return
        if (nodeKey.isEmpty()) return
        val db = FirebaseDatabase.getInstance().reference
        val now = System.currentTimeMillis() / 1000
        val updates = mapOf<String, Any>(
            "${DbPaths.conversation(myUid, otherUid)}/$nodeKey/deleted" to true,
            "${DbPaths.conversation(myUid, otherUid)}/$nodeKey/deletedBy" to myUid,
            // превью в latest тоже прячем
            "${DbPaths.latestConversation(myUid, otherUid)}/deleted" to true,
            "${DbPaths.latestConversation(otherUid, myUid)}/deleted" to true,
            // relay-тело картинки помечаем сразу (cleanup снесёт раньше TTL)
            // relay-тело картинки помечаем по авторскому id (transferRef = id автора)
            "${DbPaths.transfer(mirrorMsgId)}/deleted" to true,
            "${DbPaths.transfer(mirrorMsgId)}/expiresAt" to now
        )
        db.updateChildren(updates)
            .addOnSuccessListener {
                // Превью списка чатов: текст прячем сразу (список читает эти узлы).
                db.child(DbPaths.latestConversation(myUid, otherUid)).child("text").setValue(TextPreview.DELETED_PREVIEW)
                db.child(DbPaths.latestConversation(otherUid, myUid)).child("text").setValue(TextPreview.DELETED_PREVIEW)
                syncDeleteToPartnerMirror(mirrorMsgId)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Не удалось удалить: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Зеркало собеседника хранит копию под СВОИМ ключом узла, но поле id там
     * совпадает с авторским msgId (пишется релеем/клиентом автора). Ищем узел по
     * id и ставим deleted/edit-поля туда. Не нашли (старые данные) — тихо пропускаем:
     * получатель всё равно увидит заглушку после перечитывания, когда реле
     * синхронизирует копию.
     */
    private fun syncDeleteToPartnerMirror(mirrorMsgId: String) {
        val myUid = FirebaseAuth.getInstance().uid ?: return
        val otherUid = toUser?.uid ?: return
        FirebaseDatabase.getInstance().reference
            .child(DbPaths.conversation(otherUid, myUid))
            .orderByChild("id").equalTo(mirrorMsgId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(p0: DataSnapshot) {
                    val upd = HashMap<String, Any>()
                    for (c in p0.children) upd["${DbPaths.conversation(otherUid, myUid)}/${c.key}/deleted"] = true
                    if (upd.isNotEmpty()) FirebaseDatabase.getInstance().reference.updateChildren(upd)
                }
                override fun onCancelled(p0: DatabaseError) {}
            })
    }

    private fun syncEditToPartnerMirror(mirrorMsgId: String, editedEnvelope: String, ts: Long) {
        val myUid = FirebaseAuth.getInstance().uid ?: return
        val otherUid = toUser?.uid ?: return
        FirebaseDatabase.getInstance().reference
            .child(DbPaths.conversation(otherUid, myUid))
            .orderByChild("id").equalTo(mirrorMsgId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(p0: DataSnapshot) {
                    val upd = HashMap<String, Any>()
                    for (c in p0.children) {
                        upd["${DbPaths.conversation(otherUid, myUid)}/${c.key}/editedText"] = editedEnvelope
                        upd["${DbPaths.conversation(otherUid, myUid)}/${c.key}/editTime"] = ts
                    }
                    if (upd.isNotEmpty()) FirebaseDatabase.getInstance().reference.updateChildren(upd)
                }
                override fun onCancelled(p0: DatabaseError) {}
            })
    }

    private object TextPreview {
        const val DELETED_PREVIEW = "🚫 Сообщение удалено"
    }

    /**
     * Поле-в-поле сериализация ChatMessage в Map для multi-path update.
     * Нужна, чтобы переписать отдельное поле (readAt) в копии собеседника,
     * не трогая остальное. null-поля пропускаем — Firebase их всё равно не хранит.
     */
    private fun chatMessageToMap(m: ChatMessage): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        fun put(k: String, v: Any?) { if (v != null) out[k] = v }
        put("id", m.id); put("text", m.text); put("fromId", m.fromId); put("toId", m.toId)
        put("timestamp", m.timestamp); put("msgType", m.msgType); put("transferRef", m.transferRef)
        put("enc", m.enc); put("env", m.env); put("deleted", m.deleted); put("deletedBy", m.deletedBy)
        put("editedText", m.editedText); put("editTime", m.editTime); put("readAt", m.readAt)
        put("envEdited", m.envEdited)
        return out
    }

    override fun onDestroy() {
        // Снимаем слушателя — иначе активити утекает в Firebase навсегда
        stopReadTracker()
        val listener = messagesListener
        if (listener != null) {
            messagesRef?.removeEventListener(listener)
        }
        messagesListener = null
        messagesRef = null
        super.onDestroy()
    }
}
