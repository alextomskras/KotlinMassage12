package com.example.fess.kotlinmassage1.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import com.example.fess.kotlinmassage1.R
import com.example.fess.kotlinmassage1.models.User
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.squareup.picasso.Picasso
import java.util.concurrent.Executors

/**
 * Фоновый пул для «тяжёлых» операций с картинками:
 *  - декодирование base64 -> Bitmap (раньше делалось СИНХРОННО в RecyclerView.bind() —
 *    фризы при скролле чата с картинками);
 *  - сжатие выбранного фото -> base64 (раньше — голый Thread{} без пула).
 *
 * Корутин в проекте нет намеренно: не трогаем зависимости, один shared-пул из 2 потоков
 * закрывает обе задачи. Все колбэки возвращаются в main-поток.
 */
object ImageLoader {

    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** Ключ для setTag — ОБЯЗАТЕЛЬНО ресурсный id (R.id.*), иначе setTag(key,value) падает. */
    private val TAG_KEY = R.id.tag_image_data

    /**
     * base64-payload -> Bitmap в фоновом потоке; результат применяется к ImageView в main.
     * Защита от переиспользования ViewHolder: если к моменту готовности вьюха уже
     * привязана к другому тексту — результат игнорируется.
     */
    /**
     * base64-payload -> Bitmap в фоновом потоке; результат применяется к ImageView в main.
     * Ставит нейтральный placeholder сразу и сбрасывает старый bitmap — иначе при
     * переиспользовании ViewHolder в списке диалогов показывается картинка/заставка
     * от предыдущей строки, пока грузится текущая.
     */
    fun loadBase64ToView(data: String, target: android.widget.ImageView, maxSide: Int = 0) {
        target.setImageResource(R.drawable.image_placeholder)
        target.setTag(TAG_KEY, data)
        executor.execute {
            val bmp = ImageUtils.base64ToBitmap(data, maxSide)
            target.post {
                if (target.getTag(TAG_KEY) == data && bmp != null) {
                    target.setImageBitmap(bmp)
                }
            }
        }
    }

    /** Полноразмерный bitmap из payload/кэша — для полноэкранного просмотра. Колбэк в main. */
    fun loadFullBitmap(context: Context, transferRef: String?, payload: String?, envJson: String? = null, callback: (Bitmap?) -> Unit) {
        executor.execute {
            // 1) локальный кэш без понижения — мгновенно и в полном размере
            if (!transferRef.isNullOrEmpty()) {
                val cached = ImageCache.getBitmap(context.applicationContext, transferRef)
                if (cached != null) {
                    main.post { callback(cached) }
                    return@execute
                }
            }
            // 1b) E2EE-картинка: конверт в зеркале диалога (msg.env). Работает
            // и после удаления relay-ноды по TTL 7 дней; греет кэш.
            if (!transferRef.isNullOrEmpty() && envJson != null && envJson.contains("\"epk\"")) {
                val d = CryptoBridge.decryptImageFromMessage(context.applicationContext, transferRef, envJson)
                if (d != null) {
                    val bmp = ImageUtils.base64ToBitmap(d)
                    main.post { callback(bmp) }
                    return@execute
                }
            }
            // 2) полный base64 из сообщения / relay-зоны
            val data = when {
                payload != null && ImageUtils.isImagePayload(payload) -> payload
                !transferRef.isNullOrEmpty() -> null // пойдёт асинхронное чтение RTDB ниже
                else -> payload // legacy URL -> Picasso
            }
            if (data != null && ImageUtils.isImagePayload(data)) {
                val bmp = ImageUtils.base64ToBitmap(data)
                main.post { callback(bmp) }
                return@execute
            }
            if (!transferRef.isNullOrEmpty()) {
                FirebaseDatabase.getInstance().getReference(DbPaths.transfer(transferRef))
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            // E2EE: нода может содержать конверт enc/epk вместо открытой data
                            val d = CryptoBridge.readTransferPayload(transferRef, snapshot)
                            val bmp = if (!d.isNullOrEmpty()) ImageUtils.base64ToBitmap(d) else null
                            if (!d.isNullOrEmpty()) ImageCache.putFromBase64(context.applicationContext, transferRef, d)
                            main.post { callback(bmp) }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            main.post { callback(null) }
                        }
                    })
                return@execute
            }
            if (!payload.isNullOrEmpty()) {
                // legacy firebasestorage URL
                try {
                    val bmp = com.squareup.picasso.Picasso.get().load(payload).get()
                    main.post { callback(bmp) }
                } catch (e: Exception) {
                    main.post { callback(null) }
                }
                return@execute
            }
            main.post { callback(null) }
        }
    }

    /** Uri -> base64 в фоновом потоке; результат — в main. null = не удалось или >10 МБ. */
    fun compressUri(context: Context, uri: Uri, callback: (String?) -> Unit) {
        executor.execute {
            val b64 = ImageUtils.compressToBase64(context.applicationContext, uri)
            main.post { callback(b64) }
        }
    }

    /** Uri -> типизированный результат сжатия (Ok/TooLarge/Failed) в фоне; колбэк в main. */
    fun compressUriDetailed(context: Context, uri: Uri, callback: (ImageUtils.CompressResult) -> Unit) {
        executor.execute {
            val result = ImageUtils.compressToResult(context.applicationContext, uri)
            main.post { callback(result) }
        }
    }

    /**
     * Картинка-трансфер (новый relay-формат): /transfers/<msgId>.data живёт 7 дней.
     * Порядок: локальный кэш (мгновенно, переживает очистку трансфера) -> чтение
     * из RTDB + сохранение в кэш + ACK deliveredTo/<myUid> (сигнал для чистильщика
     * «все скачали»). Если тела уже нет и в кэша тоже — callback(null) = заглушка.
     * Колбэк всегда в main-потоке.
     */
    fun loadTransferToView(
        context: Context,
        msgId: String,
        myUid: String?,
        target: android.widget.ImageView,
        maxSide: Int = 0,
        payload: String? = null,
        envJson: String? = null
    ) {
        // сбрасываем вьюху на нейтральный placeholder: без этого при переиспользовании
        // ViewHolder показывается bitmap/заставка от предыдущей строки списка
        target.setImageResource(R.drawable.image_placeholder)
        target.setTag(TAG_KEY, msgId)
        // msgId пустой (старое сообщение без id) — сразу пробуем payload/URL
        if (msgId.isEmpty()) {
            if (payload != null && ImageUtils.isImagePayload(payload)) {
                loadBase64ToView(payload, target, maxSide)
            } else if (!payload.isNullOrEmpty()) {
                Picasso.get().load(payload).into(target)
            }
            return
        }
        executor.execute {
            // 1) локальный кэш
            val cached = ImageCache.getBitmap(context.applicationContext, msgId, maxSide)
            if (cached != null) {
                main.post {
                    if (target.getTag(TAG_KEY) == msgId) target.setImageBitmap(cached)
                }
                return@execute
            }
            // 1b) legacy-формат: base64 лежит прямо в тексте сообщения
            if (payload != null && ImageUtils.isImagePayload(payload)) {
                val bmpLegacy = ImageUtils.base64ToBitmap(payload, maxSide)
                main.post {
                    if (bmpLegacy != null && target.getTag(TAG_KEY) == msgId) target.setImageBitmap(bmpLegacy)
                }
                return@execute
            }
            // 1c) E2EE-картинка: конверт лежит в зеркале диалога (msg.env).
            // Дешифруем своим приватником и греем кэш — работает даже когда
            // relay-нода /transfers уже удалена по TTL 7 дней.
            if (envJson != null && envJson.contains("\"epk\"")) {
                val data = CryptoBridge.decryptImageFromMessage(context.applicationContext, msgId, envJson)
                if (data != null) {
                    val bmpEnv = ImageUtils.base64ToBitmap(data, maxSide)
                    main.post {
                        if (bmpEnv != null && target.getTag(TAG_KEY) == msgId) target.setImageBitmap(bmpEnv)
                    }
                    return@execute
                }
            }
            // 2) relay-зона в RTDB
            FirebaseDatabase.getInstance().getReference(DbPaths.transfer(msgId))
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        // E2EE v1: нода может быть конвертом enc/epk — readTransferPayload
                        // расшифрует нашим приватником; открытая "data" читается как раньше.
                        var data = CryptoBridge.readTransferPayload(msgId, snapshot)
                        // Relay-ноды нет (TTL истёк) или её тело не читается — пробуем
                        // E2EE-конверт из текста сообщения, если он дошёл сюда позже нас.
                        if (data.isNullOrEmpty() && envJson != null) {
                            data = CryptoBridge.decryptImageFromMessage(context.applicationContext, msgId, envJson)
                        }
                        if (data.isNullOrEmpty()) {
                            main.post {
                                if (target.getTag(TAG_KEY) == msgId)
                                    target.setImageResource(R.drawable.image_expired) // заглушка: удалено/истекло/нет ключа
                            }
                            return
                        }
                        val bmp = ImageUtils.base64ToBitmap(data, maxSide)
                        // сохраняем в кэш и ставим ACK доставки
                        ImageCache.putFromBase64(context.applicationContext, msgId, data)
                        if (!myUid.isNullOrEmpty()) {
                            try {
                                FirebaseDatabase.getInstance()
                                    .getReference(DbPaths.transfer(msgId))
                                    .child("deliveredTo").child(myUid)
                                    .setValue(System.currentTimeMillis() / 1000)
                            } catch (_: Exception) { /* правила могут запретить — не критично */ }
                        }
                        main.post {
                            if (bmp != null && target.getTag(TAG_KEY) == msgId) target.setImageBitmap(bmp)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        android.util.Log.w("ImageLoader", "transfer $msgId read failed: ${error.message}")
                        // доступ к relay-зоне запрещён/ошибка сети: если тело пришло
                        // прямо в тексте сообщения (legacy base64 или URL) — рисуем его,
                        // иначе серая заглушка вместо пустого места
                        if (!payload.isNullOrEmpty()) {
                            if (ImageUtils.isImagePayload(payload)) loadBase64ToView(payload, target, maxSide)
                            else Picasso.get().load(payload).into(target)
                        } else {
                            main.post {
                                if (target.getTag(TAG_KEY) == msgId)
                                    target.setImageResource(R.drawable.image_expired)
                            }
                        }
                    }
                })
        }
    }

    /** Профиль пользователя по uid (однократное чтение); результат — в main. */
    fun fetchUser(uid: String, callback: (User?) -> Unit) {
        FirebaseDatabase.getInstance().getReference(DbPaths.user(uid))
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val user = snapshot.getValue(User::class.java)
                    main.post { callback(user) }
                }

                override fun onCancelled(error: DatabaseError) {
                    main.post { callback(null) }
                }
            })
    }

    /** Аватар: Picasso сам грузит асинхронно; режем превью, чтобы не тянуть полноразмер. */
    fun loadAvatarInto(url: String?, target: de.hdodenhof.circleimageview.CircleImageView) {
        if (url.isNullOrEmpty()) return
        Picasso.get().load(url).resize(160, 160).centerCrop().into(target)
    }
}
