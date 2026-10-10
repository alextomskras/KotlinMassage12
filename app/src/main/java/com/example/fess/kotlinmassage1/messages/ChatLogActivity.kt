package com.example.fess.kotlinmassage1.messages

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
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
        // Галочки статуса: delivered/readAt пишутся в копию сообщения в ЗЕРКАЛЕ
        // ПОЛУЧАТЕЛЯ под mirrorKey (push-ключ, отличный от нашего msgId). Трекер
        // получает ключ узла чужого зеркала и должен сопоставить его с живой
        // строкой — берём mapping из outbox-ноды (пишется при отправке там же:
        // "mirrorKey" to mirrorKey). Без этого галочка оставалась серой всегда.
        adapter.mirrorKeyProvider = { msgId -> readOutboxMirrorKey(msgId) }

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

        // Пункт 13: долгое нажатие на ЛЮБУЮ строку -> BottomSheetDialog с действиями
        // («Копировать», «Ответить», + для своих «Изменить»/«Удалить»).
        adapter.onItemLongClickListener = ::handleItemLongClick

        // Пункт 13: свайпы ItemTouchHelper — вправо «Ответить», влево у картинок
        // полноэкранный просмотр (hero-переход). Свайп НЕ удаляет данные.
        com.example.fess.kotlinmassage1.util.ChatSwipeCallback.attach(
            findViewById(R.id.recyclerview_chat_log),
            onReply = { _, item -> startReplyTo(item) },
            onOpenImage = { _, item -> openImageFullscreen(item) }
        )

        findViewById<View>(R.id.reply_cancel_button).setOnClickListener { clearPendingReply() }

        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.send_button_chat_log).setOnClickListener {
            performSendMessage()
        }

        // Отправка по Enter с аппаратной/экранной клавиатуры
        findViewById<EditText>(R.id.edittext_chat_log).setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            ) {
                performSendMessage()
                true
            } else {
                false
            }
        }

        findViewById<ImageButton>(R.id.image_send_button_chat_log).setOnClickListener {
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
            // Превью выбранного фото поверх кнопки (крестиком не закрываем —
            // после отправки resetImagePickerUi вернёт иконку).
            findViewById<de.hdodenhof.circleimageview.CircleImageView>(R.id.select_photoview_image_send)
                .apply {
                    setImageURI(uri)
                    visibility = View.VISIBLE
                }
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
            .apply {
                setImageBitmap(null)
                visibility = View.GONE
            }
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

    // ===== mapping msgId -> mirrorKey (для галочек статуса) =====
    /**
     * Копия нашего исходящего сообщения лежит в зеркале получателя под СВОИМ
     * push-ключом (mirrorKey), и именно туда релей пишет delivered, а
     * получатель — readAt. Чтобы трекер находил живую строку по ключу чужого
     * узла, сохраняем пару (msgId -> mirrorKey): локально в SharedPreferences
     * (переживает перезапуск) и продублировано в outbox-ноде (см. writeMessageToDb,
     * читается при восстановлении истории).
     */
    private val mirrorKeys = HashMap<String, String>()

    private fun prefsMirrorKeyMap(): Map<String, String> {
        if (mirrorKeys.isEmpty()) {
            val raw = getSharedPreferences("chat_status", MODE_PRIVATE)
                .getString("mirror_keys_" + (FirebaseAuth.getInstance().uid ?: ""), "") ?: ""
            raw.split("\n").forEach { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2 && kv[0].isNotEmpty()) mirrorKeys[kv[0]] = kv[1]
            }
        }
        return mirrorKeys
    }

    private fun rememberMirrorKey(msgId: String, mirrorKey: String) {
        if (msgId.isEmpty() || mirrorKey.isEmpty()) return
        prefsMirrorKeyMap()
        mirrorKeys[msgId] = mirrorKey
        val uid = FirebaseAuth.getInstance().uid ?: return
        // Храним последние 500 пар, чтобы prefs не разрастались.
        val entries = mirrorKeys.entries.toList()
        val trimmed = if (entries.size > 500) entries.takeLast(500) else entries
        // putAll ждёт Map, а у нас List<Entry> — пересобираем в LinkedHashMap.
        val trimmedMap = LinkedHashMap<String, String>(trimmed.size)
        for ((k, v) in trimmed) trimmedMap[k] = v
        mirrorKeys.clear(); mirrorKeys.putAll(trimmedMap)
        getSharedPreferences("chat_status", MODE_PRIVATE).edit()
            .putString("mirror_keys_$uid", trimmed.joinToString("\n") { "${it.key}=${it.value}" })
            .apply()
    }

    /** msgId -> mirrorKey копии в зеркале получателя (или null, если не знаем). */
    private fun readOutboxMirrorKey(msgId: String): String? {
        if (msgId.isEmpty()) return null
        prefsMirrorKeyMap()[msgId]?.let { return it }
        // Fallback: читаем outbox-ноду (там "mirrorKey" с момента отправки).
        // Асинхронно кладём в кэш — следующий запрос будет мгновенным.
        FirebaseDatabase.getInstance().reference
            .child(DbPaths.OUTBOX).child(msgId).child("mirrorKey")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val mk = s.getValue(String::class.java)
                    if (!mk.isNullOrEmpty()) rememberMirrorKey(msgId, mk)
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        return null
    }

    /** Восстановление mapping из снапшотов /outbox при старте трекинга. */
    private fun primeMirrorKeysFromOutbox(snap: DataSnapshot) {
        for (c in snap.children) {
            val msgId = c.child("msgId").getValue(String::class.java) ?: c.key ?: continue
            val mk = c.child("mirrorKey").getValue(String::class.java)
            if (!mk.isNullOrEmpty()) rememberMirrorKey(msgId, mk)
        }
    }

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
                // Десериализуем вручную: getValue(ChatMessage) на E2EE-узлах падает
                // NumberFormatException (base64 конверта в text/id Firebase пытается
                // привести к Long) — узел целиком отбрасывался, строка не рисовалась,
                // receipt не писался. Читаем сырые листы; числовые поля только там,
                // где формат гарантирован (timestamp/msgType/readAt пишет наш клиент).
                val chatMessage = snapshotToChatMessage(p0) ?: return
                if ((chatMessage.text.isNullOrEmpty() || chatMessage.text == "-1") && chatMessage.fromId.isEmpty()) {
                    markIncomingIfUnread(p0)
                    return
                }
                val isIncoming = chatMessage.fromId != FirebaseAuth.getInstance().uid
                // Read receipt ДО отрисовки: входящее и ещё не отмеченное ->
                // ставим readAt в СВОЁ зеркало (/user-messages/{me}/{partner}/
                // {nodeKey}). Ключ узла берём ИЗ ПАРАМЕТРА onChildAdded, а не из
                // общего поля snapshotKey (его перезаписывают другие колбэки —
                // раньше receipt уходил в чужой узел). Пишем до append(), чтобы
                // при переключении между чатами read-tracker гарантированно
                // увидел свежий timestamp.
                // Отличаем «не прочитано» от «поля нет вовсе»: новые сообщения
                // автор пишет с readAt=0 в копию собеседника (см. writeMessageToDb),
                // legacy-записи поля не имеют -> их НЕ трогаем (без реворка).
                if (isIncoming && chatMessage.readAt == 0L) {
                    markIncomingAsRead(p0.key ?: snapshotKey)
                    // Оптимистично: раз мы (получатель) только что отметили прочтение, это же
                    // время прочтения актуально для нашей копии автора — перекрашиваем галку
                    // заранее, не дожидаясь round-trip через зеркало собеседника.
                    val nowSec = System.currentTimeMillis() / 1000
                    adapter.applyReadToRowByKey(p0.key ?: snapshotKey, nowSec)
}
                val row = buildChatItem(chatMessage, isIncoming)
                // Пункт 13: каждая строка — потенциальный источник «Ответить»
                // (свайп вправо). Ключ — dbId В НАШЕМ зеркале (snapshotKey),
                // msgId автора — для replyToId у ответного сообщения.
                adapter.registerReplySource(snapshotKey, chatMessage.id,
                    row.plainTextForMenu() ?: "📷 Картинка")
                // Если это ответ — восстановить плашку цитаты по replyToId.
                adapter.applyReply(row, chatMessage.replyToId)
                adapter.append(row)
                scrollToBottom()
            }

            override fun onCancelled(p0: DatabaseError) {
                Log.w(TAG, "messages listener cancelled: ${p0.message}")
            }

            override fun onChildChanged(p0: DataSnapshot, p1: String?) {
                // Soft-delete / редактирование / readAt приходят как change узла
                snapshotKey = p0.key ?: ""
                val chatMessage = snapshotToChatMessage(p0) ?: return
                applyNodeChange(p0.key ?: "", chatMessage)
            }
            override fun onChildMoved(p0: DataSnapshot, p1: String?) {}
            override fun onChildRemoved(p0: DataSnapshot) {}
        }
        messagesListener = listener
        messagesRef = ref
        ref.addChildEventListener(listener)

        // Read-tracker по ЗЕРКАЛУ СОБЕСЕДНИКА: наши исходящие сообщения лежат
        // в /user-messages/{partner}/{me}, и получатель проставляет readAt в
        // свою копию именно там. Слушать своё зеркало бессмысленно — receipt
        // там не появляется, и галочка навсегда оставалась одной серой.
        val myUidForTracker = FirebaseAuth.getInstance().uid ?: return
        // Перед стартом трекинга греем mapping msgId -> mirrorKey из /outbox:
        // delivered/readAt в зеркале получателя приходят под mirrorKey, и без
        // отображения трекер не находит живую строку — галочка висит серой.
        FirebaseDatabase.getInstance().reference.child(DbPaths.OUTBOX)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { primeMirrorKeysFromOutbox(s) }
                override fun onCancelled(e: DatabaseError) {}
            })
        // ВАЖНО: аргументы в обратном порядке — (партнёр, я), чтобы трекер
        // слушал user-messages/{partner}/{me} (копии наших исходящих с readAt).
        startReadTracker(toId, myUidForTracker)
    }

    /** Трекер readAt/readReceipt в зеркале собеседника (копии наших исходящих). */
    private var readTrackerRef: DatabaseReference? = null
    private var readTracker: ValueEventListener? = null
    /** Когда трекер уже делал полный redraw (см. троттлинг в onDataChange). */
    private var lastFullRedrawMs: Long? = null

    /**
     * Ручная десериализация — тонкая обёртка над ChatMessage.fromSnapshot().
     * Штатный getValue(ChatMessage::class.java) на E2EE-узлах кидает
     * NumberFormatException и СЪЕДАЕТ ВЕСЬ УЗЕЛ (base64 конверта в text/id
     * Firebase пытается привести к Long) — строка не рисовалась, receipt не
     * писался, галочки у отправителя не менялись. Подробности — в модели.
     */
    private fun snapshotToChatMessage(s: DataSnapshot): ChatMessage? =
        ChatMessage.fromSnapshot(s)

    private fun startReadTracker(myUid: String, otherUid: String) {
        stopReadTracker()
        // ВАЖНО: слушаем ЧУЖОЕ зеркало — /user-messages/{partner}/{me}.
        // Копии НАШИХ исходящих сообщений живут именно там (автор пишет их в
        // папку получателя), и получатель проставляет readAt в тот же узел:
        //   /user-messages/{otherUid}/{myUid}/{mirrorKey}/readAt.
        // В своём зеркале ({myUid}/{otherUid}) лежат входящие + наши авторские
        // копии с readAt=-1 (маркер «не реворкать»), поэтому трекер по своему
        // пути никогда не увидел бы receipt — галочка осталась бы серой.
        val ref = FirebaseDatabase.getInstance().getReference(DbPaths.conversation(otherUid, myUid))
        // ВАЖНО: именно ValueEventListener на весь узел диалога, а НЕ
        // ChildEventListener. Причина: получатель пишет readAt как ДОЧЕРНИЙ
        // лист nodeKey/readAt. Для ChildEventListener такой deep-path не
        // является прямым ребёнком и изменение никогда не приходит — поэтому
        // галочки и не менялись. onValueEvent получает всё дерево целиком и
        // сравнивает readAt каждого узла с тем, что уже отрисовано.
        val tracker = object : ValueEventListener {
            override fun onDataChange(p0: DataSnapshot) {
                // Пропускаем полную выгрузку, пока чат ещё пустой: ValueEventListener
                // может прийти раньше, чем onChildAdded основного слушателя достроит
                // строки. Раньше отсюда для узлов без живой строки уходил ПОТОК
                // receipt-записей (в т.ч. в чужие узлы), правила RTDB их отклоняли,
                // а Firebase при Permission denied снимал ВСЕ слушатели — поэтому
                // галочки у отправителя никогда не перекрашивались.
                if (adapter.itemCount == 0) return
                for (c in p0.children) applyReadFromMirror(c)
                // После того как трекер проехал по всему узлу диалога и все
                // live-строки получили readAt, принудительно перерисовываем
                // список. Это закрывает случай, когда receipt был проставлен,
                // пока клиента в чате не было (сообщение прочитано вчера):
                // основной ChildEventListener НЕ выдаёт onChildChanged на
                // уже загруженные узлы, и без этого шага галочка осталась бы
                // серой до первого изменения узла.
                // Принудительная перерисовка нужна только при ПЕРВОМ проходе
                // (receipt проставлен, пока клиента в чате не было). При
                // каждом последующем событии это стоило бы полной пересборки
                // списка раз в минуту из-за delivered-флагов релея, поэтому
                // ограничиваем частоту: один полный redraw за сессию трекинга.
                if (lastFullRedrawMs == null) {
                    lastFullRedrawMs = System.currentTimeMillis()
                    adapter.notifyDataSetChanged()
                }
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
            ?: c.child("readReceipt").getValue(Long::class.java)) ?: 0L
        // Delivery receipt пишет СЕРВЕРНЫЙ релей в зеркало получателя:
        // delivered = unix-секунды либо true (legacy-формат boolean).
        // Это единственный честный источник «галочки доставки» — клиент сам
        // её никогда не ставит. Раньше статус доставки вообще не использовался,
        // и галка висела серой до прочтения; теперь: отправлено(серая) ->
        // доставлено(синяя, от релея) -> прочитано(две синие, readAt).
        val deliveredValue = when (val dRaw = c.child("delivered").value) {
            is Number -> dRaw.toLong()
            is Boolean -> if (dRaw) System.currentTimeMillis() / 1000 else 0L
            else -> 0L
        }
        if (readValue <= 0 && deliveredValue <= 0) return
        // Трекер слушает ЧУЖОЕ зеркало: delivered/readAt приходят в копию
        // сообщения под mirrorKey (push-ключ, НЕ наш msgId). Единая перекраска
        // applyStatusToRowByKey сопоставляет ключ по msgId / dbId / mirrorKey
        // (mapping из prefs+outbox): readAt > 0 красит две синие,
        // delivered > 0 — одну синюю. Если живая строка не найдена — событие
        // просто игнорируется: раньше отсюда уходил поток receipt-записей в
        // чужие узлы, правила RTDB их отклоняли (Permission denied), и
        // Firebase снимал ВСЕ слушатели — галочки навсегда оставались серыми.
        adapter.applyStatusToRowByKey(key, readValue, deliveredValue)
        // Совпадения нет ни по msgId, ни по dbId. Возможные случаи:
        //  a) узел принадлежит МНЕ (fromId == myUid), но строки с ним в списке
        //     нет — чат ещё достраивается. НИЧЕГО не пишем: раньше отсюда
        //     уходил поток receipt-записей в чужие узлы, правила RTDB их
        //     отклоняли (Permission denied), и Firebase снимал ВСЕ слушатели
        //     — галочки у отправителя навсегда оставались серыми.
        //  b) чужой узел с readAt > 0, а строки ещё нет — просто не отрисована;
        //     при достройке readAt подтянется из snapshotToChatMessage.
        //  c) раньше отсюда уходила «доводка» receipt копией в зеркало автора —
        //     при прослушке чужого зеркала это создавало петлю записей между
        //     клиентами. Теперь узлы без живой строки просто игнорируются:
        //     при достройке истории readAt подтянется напрямую из снапшота.
    }

    /** Точечное обновление живой строки по изменению её узла в зеркале автора. */
    private fun applyNodeChange(nodeKey: String, chatMessage: ChatMessage) {
        val pos = adapter.findRowPosition(nodeKey)
        if (pos < 0) return
        when (val item = adapter.itemAt(pos)) {
            is com.example.fess.kotlinmassage1.views.ChatFromItem -> {
                if (chatMessage.readAt > 0) item.readAt = chatMessage.readAt
                if (chatMessage.deliveredAt > 0) item.deliveredAt = chatMessage.deliveredAt
                item.editTime = chatMessage.editTime
                item.editedText = chatMessage.editedText
                item.envMirror = if (chatMessage.editedText != null) chatMessage.envEdited else chatMessage.env
                item.deleted = chatMessage.deleted
            }
            is com.example.fess.kotlinmassage1.views.KartinkaFromItem -> {
                if (chatMessage.readAt > 0) item.readAt = chatMessage.readAt
                if (chatMessage.deliveredAt > 0) item.deliveredAt = chatMessage.deliveredAt
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
                if (chatMessage.deliveredAt > 0) item.deliveredAt = chatMessage.deliveredAt
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
    private fun writeMessage(text: String, msgType: String, reply: Triple<String, String, String>? = null) {
        if (msgType == ChatMessage.TYPE_TEXT) {
            // E2EE: шифруем текст под pubkey получателя; если ключа нет / TOFU не дал —
            // пишем открытым текстом (обратная совместимость со старыми клиентами).
            val fromId = FirebaseAuth.getInstance().uid ?: return
            val toId = toUser?.uid ?: return
            val db = FirebaseDatabase.getInstance().reference
            val msgId = db.child(DbPaths.conversation(fromId, toId)).push().key ?: return
            CryptoBridge.encryptTextForSend(
                this, toId, text, msgId,
                onReady = { envelopeB64 -> writeMessageToDb(envelopeB64, msgType, encrypted = true, forcedId = msgId, reply = reply) },
                onFallback = { reason ->
                    Log.w(TAG, "E2EE fallback (plaintext): $reason")
                    writeMessageToDb(text, msgType, encrypted = false, forcedId = msgId, reply = reply)
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
                    if (!isFinishing && !isDestroyed) writeMessageToDb(text, msgType, encrypted = false, forcedId = msgId, imageEnv = envelopeB64, reply = reply)
                },
                onFallback = { reason ->
                    Log.w(TAG, "E2EE image fallback (plaintext transfer): $reason")
                    if (!isFinishing && !isDestroyed) writeMessageToDb(text, msgType, encrypted = false, forcedId = msgId, imageEnv = null, reply = reply)
                }
            )
        } else {
            writeMessageToDb(text, msgType, encrypted = false, reply = reply)
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
    private fun writeMessageToDb(text: String, msgType: String, encrypted: Boolean, forcedId: String? = null, imageEnv: String? = null, reply: Triple<String, String, String>? = null) {
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

        // СВОЙ узел (зеркало автора) всегда содержит delivered=false — иначе у
        // старых сообщений поле отсутствовало бы вовсе, и трекер не смог бы
        // отличить «не доставлено» от legacy. readAt=-1 — маркер «не реворкать».
        val ownNode = HashMap<String, Any>(chatMessageToMap(chatMessage)).apply {
            put("readAt", -1L); put("delivered", false)
        }

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
            ChatMessage(id, preview, fromId, toId, nowSec, msgType, transferRef = id, env = imageEnv, replyToId = reply?.second, replyPreview = reply?.third)
        } else {
            // Зеркало НЕ удаляем из кэша при чтении: если updateChildren не
            // подтвердится сервером (offline-очередь), оно останется и повторная
            // запись его возьмёт. Чистим через confirmTextMirror в onDisconnect.
            val textMirror = if (encrypted) forcedId?.let { com.example.fess.kotlinmassage1.util.CryptoBridge.peekTextMirror(it) } else null
            ChatMessage(messageRef.key!!, text, fromId, toId, nowSec, msgType, enc = encrypted, env = textMirror, replyToId = reply?.second, replyPreview = reply?.third)
        }

        // Ключ задачи в /outbox совпадает с id сообщения: релей идемпотентно читает и удаляет его.
        val outboxKey = chatMessage.id

        updates["/${DbPaths.conversation(fromId, toId)}/${messageRef.key}"] = ownNode
        // Копия собеседника: readAt=0 — сигнал «не прочитано, отметь при просмотре».
        // Поле присутствует явно (в отличие от legacy-записей без readAt), поэтому
        // получатель гарантированно вызовет markIncomingAsRead при открытии чата.
        // delivered=false — маркер «ждёт доставки». Когда релей (backend
        // outbox_relay) успешно отправит FCM получателю, он переписывает это
        // поле в true/unix-время. Отправитель ловит изменение read-tracker'ом
        // и красит одинарную СИНИЮ галочку («доставлено»). Без явного false
        // поле у новых сообщений отсутствовало бы, и трекер не различал бы
        // «не доставлено» от legacy-узлов.
        val partnerCopy = HashMap<String, Any>(chatMessageToMap(chatMessage)).apply {
            put("readAt", 0L); put("delivered", false)
        }
        // Источник цитаты регистрируем ПО dbId ЗЕРКАЛА ПОЛУЧАТЕЛЯ (mirrorKey):
        // когда он прочтёт историю, applyReply найдёт превью по своему ключу узла.
        reply?.let { adapter.registerReplySource(mirrorKey, it.second, it.third) }
        // Запоминаем пару msgId -> mirrorKey для галочек статуса (см. prefsMirrorKeyMap).
        rememberMirrorKey(chatMessage.id, mirrorKey)
        updates["/${DbPaths.conversation(toId, fromId)}/$mirrorKey"] = partnerCopy
        // В latest-зеркало цитату НЕ пишем: replyPreview нужен только внутри
        // полного списка чата, а в списке диалогов его никто не рисует (экономия).
        updates["/${DbPaths.latestConversation(fromId, toId)}"] = HashMap<String, Any>(chatMessageToMap(chatMessage)).apply { remove("replyToId"); remove("replyPreview") }
        updates["/${DbPaths.latestConversation(toId, fromId)}"] = HashMap<String, Any>(chatMessageToMap(chatMessage)).apply { remove("replyToId"); remove("replyPreview") }
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
            // Ключ копии в зеркале получателя: по нему релей/получатель пишут
            // delivered/readAt, а read-tracker сопоставляет событие с живой
            // строкой (adapter.mirrorKeyProvider читает это поле).
            "mirrorKey" to mirrorKey,
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
                clearPendingReply()
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
        // pendingReply читаем ДО отправки: writeMessage асинхронный (E2EE),
        // очистка панели — в onSuccess записи.
        writeMessage(text, ChatMessage.TYPE_TEXT, reply = pendingReply)
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
            val key = p0.key ?: return
            markIncomingAsRead(key)
            adapter.applyReadToRowByKey(key, System.currentTimeMillis() / 1000)
        } catch (e: Exception) {
            Log.w(TAG, "markIncomingIfUnread skipped: ${e.message}")
        }
    }

    /**
     * Read receipt: получатель отмечает входящее сообщение прочитанным — пишет
     * readAt только в свою копию зеркала (правила RTDB: запись в свой узел).
     * Отправитель подтягивает изменение read-tracker'ом по своему зеркалу
     * (он слушает /user-messages/{partner}/{me}) и красит двойную синюю
     * галочку. Идемпотентно: повторная отметка не пишется (поле уже > 0).
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
     * Пункт 13: для ВСЕХ строк показываем BottomSheetDialog с действиями:
     * «Копировать» (если есть открытый текст), «Ответить», а для своих сообщений
     * ещё «Изменить» и «Удалить для всех». Возвращает true — обработали.
     */
    private fun handleItemLongClick(position: Int, item: com.example.fess.kotlinmassage1.views.ChatRowDelegate): Boolean {
        val mine = item is com.example.fess.kotlinmassage1.views.ChatFromItem ||
            (item is com.example.fess.kotlinmassage1.views.TextItem && !item.isIncomingForMenu()) ||
            (item is com.example.fess.kotlinmassage1.views.KartinkaFromItem)

        // Собираем действия динамически: индекс пункта == индекс обработчика.
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun act(label: String, handler: () -> Unit) { labels.add(label); actions.add(handler) }

        val plain = item.plainTextForMenu()
        if (!plain.isNullOrEmpty()) act(getString(R.string.copy_action)) { copyToClipboard(plain) }
        act(getString(R.string.reply_action)) { startReplyTo(item) }
        if (mine && item is com.example.fess.kotlinmassage1.views.ChatFromItem) {
            act(getString(R.string.edit_action)) {
                startEditMessage(item.plainForEditing(), item.dbId, item.msgId)
            }
        }
        if (mine && item is com.example.fess.kotlinmassage1.views.TextItem && !item.isIncomingForMenu()) {
            act(getString(R.string.edit_action)) {
                startEditMessage(item.plainForEditing(), item.dbId, item.msgId)
            }
        }
        if (mine) act(getString(R.string.delete_action)) {
            when (item) {
                is com.example.fess.kotlinmassage1.views.KartinkaFromItem ->
                    confirmDeleteImage(item.dbId.ifEmpty { item.msgId })
                is com.example.fess.kotlinmassage1.views.ChatFromItem ->
                    softDeleteMessage(item.dbId, item.msgId)
                is com.example.fess.kotlinmassage1.views.TextItem ->
                    softDeleteMessage(item.dbId, item.msgId)
            }
        }

        showActionsSheet(labels, actions)
        return true
    }

    /** Строка шторки действий (объявлена ВНЕ анонимного адаптера — иначе ViewHolder не виден базовому классу). */
    class SheetRow(val tv: android.widget.TextView) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(tv)

    /** BottomSheetDialog со списком действий (Material, без кастомных layout-файлов). */
    private fun showActionsSheet(labels: List<String>, actions: List<() -> Unit>) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val list = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
            adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<
                ChatLogActivity.SheetRow>() {

                override fun getItemCount(): Int = labels.size

                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SheetRow {
                    val tv = android.widget.TextView(parent.context).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setPadding(48, 40, 48, 40)
                        textSize = 16f
                        setBackgroundResource(android.R.drawable.list_selector_background)
                        isClickable = true
                        isFocusable = true
                    }
                    return SheetRow(tv)
                }

                override fun onBindViewHolder(holder: SheetRow, position: Int) {
                    holder.tv.text = labels[position]
                    holder.tv.setOnClickListener {
                        sheet.dismiss()
                        // Действие после анимации закрытия шторки
                        holder.tv.postDelayed({ actions[position]() }, 120)
                    }
                }
            }
        }
        sheet.setContentView(list)
        sheet.show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
        Toast.makeText(this, R.string.copied_toast, Toast.LENGTH_SHORT).show()
    }

    // ===== Пункт 13: swipe reply =====

    /** Источник ответа: dbId узла + msgId автора (для replyToId) + превью (для плашки). */
    private var pendingReply: Triple<String, String, String>? = null

    /** Свайп/меню «Ответить»: запоминаем источник и показываем цитату над полем ввода. */
    private fun startReplyTo(item: com.example.fess.kotlinmassage1.views.ChatRowDelegate) {
        val dbId = item.rowDbId()
        if (dbId.isEmpty()) return
        val preview = item.plainTextForMenu()?.takeIf { it.isNotEmpty() }
            ?: item.rowText().takeIf { it.isNotEmpty() }
            ?: return
        // msgId источника одинаков в обоих зеркалах (по нему строится replyMap).
        val sourceMsgId = item.rowMsgId().ifEmpty { dbId }
        pendingReply = Triple(dbId, sourceMsgId, preview)
        findViewById<View>(R.id.reply_preview_bar).visibility = View.VISIBLE
        findViewById<android.widget.TextView>(R.id.reply_preview_text).text =
            getString(R.string.reply_to_prefix, preview)
        findViewById<EditText>(R.id.edittext_chat_log).requestFocus()
    }

    private fun clearPendingReply() {
        pendingReply = null
        findViewById<View>(R.id.reply_preview_bar).visibility = View.GONE
    }

    /** Свайп влево по картинке — полноэкранный просмотр (hero-переход, пункт 11). */
    private fun openImageFullscreen(item: com.example.fess.kotlinmassage1.views.ChatRowDelegate) {
        val ctx = item.rowContext ?: return
        if (ctx !is Activity || ctx.isFinishing || ctx.isDestroyed) return
        val ref = when (item) {
            is com.example.fess.kotlinmassage1.views.KartinkaFromItem -> item.transferRef
            is com.example.fess.kotlinmassage1.views.KartinkaToItem -> item.transferRef
            else -> return
        }
        val payload = item.rowText()
        val env = when (item) {
            is com.example.fess.kotlinmassage1.views.KartinkaFromItem -> item.envJson
            is com.example.fess.kotlinmassage1.views.KartinkaToItem -> item.envJson
            else -> null
        }
        val outgoing = item is com.example.fess.kotlinmassage1.views.KartinkaFromItem
        // Миниатюра «вооружена» transitionName в bindTo (armHero по msgId/transferRef).
        val key = when (item) {
            is com.example.fess.kotlinmassage1.views.KartinkaFromItem -> item.msgId.ifEmpty { ref ?: "" }
            else -> (item as com.example.fess.kotlinmassage1.views.KartinkaToItem).msgId.ifEmpty { ref ?: "" }
        }
        val source = findMiniature(key)
        if (source != null && android.os.Build.VERSION.SDK_INT >= 21 && source.transitionName != null) {
            com.example.fess.kotlinmassage1.util.HeroTransition.launch(
                ctx, source, ref, payload, envJson = env, isOutgoing = outgoing)
        } else {
            ctx.startActivity(
                com.example.fess.kotlinmassage1.util.FullscreenImageActivity.intent(ctx, ref, payload, null, env, outgoing)
            )
        }
    }

    /** Найти миниатюру картинки на экране по ключу hero (chat_image_<key>). */
    private fun findMiniature(key: String): View? {
        if (android.os.Build.VERSION.SDK_INT < 21 || key.isEmpty()) return null
        val name = "chat_image_$key"
        val rv = findViewById<RecyclerView>(R.id.recyclerview_chat_log)
        for (i in 0 until rv.childCount) {
            val found = rv.getChildAt(i).findViewWithTag<View>(name)
                ?: run {
                    val iv = rv.getChildAt(i).findViewById<View>(R.id.imageview_chat_from_row2)
                        ?: rv.getChildAt(i).findViewById<View>(R.id.imageview_chat_to_row2)
                    if (iv?.transitionName == name) iv else null
                }
            if (found != null) return found
        }
        return null
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
        put("replyToId", m.replyToId); put("replyPreview", m.replyPreview)
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
