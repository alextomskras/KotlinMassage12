package com.example.fess.kotlinmassage1.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject

/**
 * Мост между UI-слоем (текст сообщения / relay-зона картинок) и CryptoBox (E2EE v1).
 *
 * Форматы на проводе (docs/ENCRYPTION_CONCEPT.md п.4):
 *  - текст: в ChatMessage.text лежит base64 JSON-конверта {"enc","epk","alg"};
 *    признак шифрования — поле msg.enc == true;
 *  - картинка: в /transfers/<id> вместо "data" поля с base64 лежат те же три
 *    поля конверта ("enc","epk","alg"), "mime"/размеры не шифруются (не критичны).
 *
 * Совместимость со старыми клиентами: если у собеседника ещё нет pubkey,
 * [encryptTextForSend] возвращает null — отправитель пишет открытым текстом
 * (msg.enc=false), получатель это корректно отрендерит.
 */
object CryptoBridge {

    private const val TAG = "CryptoBridge"

    /** Кэш pubkey собеседников на время жизни процесса (ключи меняются редко; TOFU ловит подмену). */
    private val pubKeyCache = HashMap<String, org.bouncycastle.crypto.params.X25519PublicKeyParameters>()

    // ------------------------------------------------------------------ текст

    /**
     * Шифрует текст под получателя. Колбэк в том же потоке, что вызвал (main допустим:
     * fetchPartnerKey асинхронный, сама криптография — миллисекунды).
     * @param onReady envelopeB64 — base64 JSON-конверта для записи в msg.text;
     * @param onFallback вызывается, если у получателя нет ключа / ключ сменился (TOFU) —
     *   отправляем открытым текстом.
     */
    fun encryptTextForSend(
        context: Context,
        toUid: String,
        plain: String,
        msgId: String,
        onReady: (envelopeB64: String) -> Unit,
        onFallback: (reason: String) -> Unit
    ) {
        KeyManager.fetchPartnerKey(
            toUid,
            onKey = { pub ->
                try {
                    val env = CryptoBox.encryptText(plain, pub, msgId)
                    onReady(envelopeToJson(env))
                } catch (e: Exception) {
                    Log.e(TAG, "encryptText failed", e)
                    onFallback("Не удалось зашифровать: ${e.message}")
                }
            },
            onError = onFallback
        )
    }

    /**
     * Расшифровывает msg.text, если msg.enc==true. Возвращает plain text или null
     * (нет ключей / чужой конверт / битый base64) — вызывающий решает заглушку.
     */
    fun decryptText(myUid: String?, fromId: String?, msgId: String, envelopeB64: String): String? {
        return try {
            // Дешифровка требует ТОЛЬКО своего приватника: текст шифруется
            // ЭФЕМЕРНОЙ парой отправителя (epk лежит в конверте), поэтому
            // чужие ключи и сеть не нужны. getPrivateKey() лениво разворачивает
            // ключ из wrapped-файла синхронно и без сети.
            //
            // Если свой приватник НЕ подошёл (AES-GCM tag не сходится) —
            // сообщение зашифровано ДРУГОЙ парой этого же uid (перелогин /
            // новое устройство / переустановка). Восстановить такой текст
            // невозможно НИ У КОГО — это фундаментальное свойство E2EE, а не
            // баг. Возвращаем null; UI честно покажёт заглушку.
            val env = jsonToEnvelope(envelopeB64) ?: return null
            CryptoBox.decryptText(env, msgId)
        } catch (e: Exception) {
            Log.w(TAG, "decryptText($msgId) failed: ${e.message}")
            null
        }
    }

    /**
     * Превью для пушей/списка диалогов: содержимое шифрованного сообщения
     * недоступно никому, кроме сторон диалога — в превью только сам факт.
     */
    const val ENCRYPTED_PREVIEW = "🔒 Сообщение"

    // -------------------------------------------------------------- картинки

    /**
     * Готовит E2EE-конверт для КАРТИНКИ под получателя. Асинхронно (fetchPartnerKey
     * может сходить в RTDB за pubkey). Колбэки приходят в main-потоке вызова.
     * @param base64Payload data-URI base64 оригинала (ImageUtils.compressToResult).
     * @param onReady envelopeB64 — кладём в поле "env" сообщения; тело ещё не шифровано.
     * @param onFallback нет pubkey / ошибка — пишем открытую relay-ноду.
     */
    fun prepareImageEnvelope(
        toUid: String,
        msgId: String,
        base64Payload: String,
        onReady: (envelopeB64: String) -> Unit,
        onFallback: (reason: String) -> Unit
    ) {
        KeyManager.fetchPartnerKey(
            toUid,
            onKey = { pub ->
                try {
                    val raw = Base64.decode(base64Payload.substringAfter("base64,"), Base64.NO_WRAP)
                    // Эфемерная пара для ECDH под pubkey получателя (получатель
                    // дешифрует своим приватником через epk из конверта)...
                    val envReceiver = CryptoBox.encrypt(raw, pub, msgId, image = true)
                    // ...НО зеркало/relay хранит ДВА представления одного и того
                    // же шифртекста невозможно, поэтому для картинок схема иная:
                    // шифруем ОДИН раз под pubkey получателя эфемеридой, а для
                    // отправителя делаем ВТОРОЙ конверт той же картинки под ЕГО
                    // СОБСТВЕННЫЙ static pubkey (epk = его pubkey). Оба конверта
                    // кладутся в env: receiver-конверт уходит в relay-ноду,
                    // sender-конверт остаётся в msg.env. Отправитель читает свою
                    // картинку decryptSender() (ECDH(spriv, epk=spub)).
                    val envSender = CryptoBox.encrypt(raw, KeyManager.getPublicKey(), msgId, image = true)
                    onReady(envelopeToJson(envSender)) // в msg.env — то, что читает отправитель
                    pendingReceiverEnvelopes[msgId] = envelopeToJson(envReceiver)
                } catch (e: Exception) {
                    Log.e(TAG, "prepareImageEnvelope failed", e)
                    onFallback("Не удалось зашифровать картинку: ${e.message}")
                }
            },
            onError = onFallback
        )
    }

    /**
     * Receiver-конверт картинки, подготовленный при отправке (msgId -> JSON
     * base64). Используется buildTransferNodeFromEnv для relay-ноды: туда идёт
     * конверт ПОД ПОЛУЧАТЕЛЯ, а в msg.env — конверт ПОД ОТПРАВИТЕЛЯ.
     */
    private val pendingReceiverEnvelopes = HashMap<String, String>()

    fun takeReceiverEnvelope(msgId: String): String? = pendingReceiverEnvelopes.remove(msgId)

    /**
     * Собирает relay-узел /transfers/<id> по ГОТОВОМУ конверту из сообщения:
     * enc/epk вынимаются из env (дублировать шифрование не нужно — env уже лежит
     * в зеркале диалога и переживает TTL relay). Если env битый/нет — null,
     * вызывающий пишет открытую "data".
     */
    fun buildTransferNodeFromEnv(
        envJson: String?,
        msgId: String,
        base64Payload: String,
        fromId: String,
        toUid: String,
        nowSec: Long,
        ttlSec: Long
    ): Map<String, Any>? {
        return try {
            // В relay-ноду кладём конверт ПОД ПОЛУЧАТЕЛЯ (его подготовили в
            // prepareImageEnvelope и передали сюда как envJson); msg.env хранит
            // отдельный sender-конверт — так обе стороны читают картинку своими
            // приватниками. Вызывающий обязан передать receiver-конверт.
            val env = jsonToEnvelope(envJson ?: return null) ?: return null
            mapOf(
                "fromId" to fromId,
                "toIds" to listOf(toUid),
                "type" to "image",
                "mime" to ImageUtils.mimeOf(base64Payload),
                "sizeBytes" to env.encB64.length, // размер шифртекста ~ размер тела
                "createdAt" to nowSec,
                "expiresAt" to nowSec + ttlSec,
                "enc" to env.encB64,
                "epk" to env.epkB64,
                "alg" to env.alg
            )
        } catch (e: Exception) {
            Log.e(TAG, "buildTransferNodeFromEnv failed", e)
            null
        }
    }

    /**
     * Читает картинку из E2EE-конверта, сохранённого в ТЕКСТЕ сообщения
     * (поле env зеркала /conversation): дешифрует своим приватником и кладёт
     * результат в файловый кэш ImageCache под msgId. Возвращает data-URI или
     * null (нет ключей / битый конверт). Вызывается из строек чата, когда
     * relay-нода /transfers уже удалена (TTL) или ещё не долетела.
     */
    fun decryptImageFromMessage(context: Context, msgId: String, envJson: String): String? =
        decryptImageFromMessage(context, msgId, envJson, ownEpk = false)

    /**
     * @param ownEpk true — для ОТПРАВИТЕЛЯ картинки. Конверт отправителя устроен
     * хитрее: epk в нём = ЭТО его собственный static pubkey (картинка шифруется
     * ECDH(ephemeral_sender_priv, pub_receiver), а receiver восстанавливает
     * shared через ECDH(priv_receiver, epk)). У отправителя приватник от epk
     * нет, поэтому он считает shared как ECDH(static_priv_sender, epk=own_pub) —
     * что математически даёт тот же самый shared secret (DH коммутативность +
     * симметрия sender->receiver node: epk выбран так, чтобы обе стороны сошлись).
     * Фактически это «второй прогон» того же HKDF/AES-GCM своими ключами.
     * false — обычный путь получателя: ECDH(priv, epk).
     */
    fun decryptImageFromMessage(context: Context, msgId: String, envJson: String, ownEpk: Boolean): String? {
        return try {
            val env = jsonToEnvelope(envJson) ?: return null
            val plain = if (ownEpk) CryptoBox.decryptSender(env, msgId, image = true)
                        else CryptoBox.decrypt(env, msgId, image = true)
            val mime = guessMime(plain)
            val b64 = Base64.encodeToString(plain, Base64.NO_WRAP)
            // Сохраняем в кэш — при следующем bind превью возьмётся с диска
            // без повторной асимметричной операции.
            com.example.fess.kotlinmassage1.util.ImageCache.putFromBase64(context, msgId, "data:$mime;base64,$b64")
            "data:$mime;base64,$b64"
        } catch (e: Exception) {
            Log.w(TAG, "decryptImageFromMessage($msgId) failed: ${e.message}")
            null
        }
    }

    /**
     * Картинка из relay-ноды для ОТПРАВИТЕЛЯ: нода содержит те же enc/epk, что
     * и конверт сообщения; epk = pubkey отправителя, поэтому дешифровка идёт
     * его приватником (decryptSender), а не через ephemeral-путь получателя.
     */
    fun readTransferPayloadAsSender(msgId: String, snapshot: DataSnapshot): String? {
        val enc = snapshot.child("enc").getValue(String::class.java)
        val epk = snapshot.child("epk").getValue(String::class.java)
        if (enc.isNullOrEmpty() || epk.isNullOrEmpty()) return null
        val plain = try {
            CryptoBox.decryptSender(CryptoBox.Envelope(enc, epk), msgId, image = true)
        } catch (e: Exception) {
            Log.w(TAG, "sender transfer $msgId decrypt failed (key rotated?): ${e.message}")
            return null
        }
        val mime = snapshot.child("mime").getValue(String::class.java) ?: guessMime(plain)
        return "data:$mime;base64," + Base64.encodeToString(plain, Base64.NO_WRAP)
    }

    /**
     * Читает /transfers/<id>: если там конверт (есть поля enc+epk) — расшифровывает,
     * иначе отдаёт открытую "data" как есть (обратная совместимость).
     * Результат — base64 data-URI картинки (или null); колбэк в main.
     */
    fun readTransferPayload(msgId: String, snapshot: DataSnapshot): String? {
        val open = snapshot.child("data").getValue(String::class.java)
        if (!open.isNullOrEmpty()) return open // открытая нода (старый клиент / fallback)
        val enc = snapshot.child("enc").getValue(String::class.java)
        val epk = snapshot.child("epk").getValue(String::class.java)
        if (enc.isNullOrEmpty() || epk.isNullOrEmpty()) return null
        val plain = try {
            CryptoBox.decrypt(CryptoBox.Envelope(enc, epk), msgId, image = true)
        } catch (e: Exception) {
            Log.w(TAG, "transfer $msgId decrypt failed (wrong key?): ${e.message}")
            return null
        }
        val mime = snapshot.child("mime").getValue(String::class.java) ?: guessMime(plain)
        return "data:$mime;base64," + Base64.encodeToString(plain, Base64.NO_WRAP)
    }

    /**
     * Инит для фоновых путей (ImageLoader не имеет Activity-контекста): кладём наш
     * pubkey в кэш по uid, чтобы read/write картинок работал без сетевого RTT.
     * Вызывается один раз при входе в диалог.
     */
    fun warmPartnerKey(toUid: String) {
        if (pubKeyCache.containsKey(toUid)) return
        FirebaseDatabase.getInstance().getReference("${DbPaths.user(toUid)}/publicKey")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    val b64 = snap.value as? String ?: return
                    val bytes = KeyManager.decodeBase64(b64) ?: return
                    if (bytes.size == 32) {
                        pubKeyCache[toUid] =
                            org.bouncycastle.crypto.params.X25519PublicKeyParameters(bytes, 0)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "warmPartnerKey($toUid): ${error.message}")
                }
            })
    }

    // ------------------------------------------------------------- конверт JSON

    private fun envelopeToJson(env: CryptoBox.Envelope): String {
        val o = JSONObject()
        o.put("enc", env.encB64)
        o.put("epk", env.epkB64)
        o.put("alg", env.alg)
        return Base64.encodeToString(o.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun jsonToEnvelope(envelopeB64: String): CryptoBox.Envelope? = try {
        val json = String(Base64.decode(envelopeB64, Base64.NO_WRAP), Charsets.UTF_8)
        val o = JSONObject(json)
        val enc = o.getString("enc")
        val epk = o.getString("epk")
        CryptoBox.Envelope(enc, epk)
    } catch (e: Exception) {
        Log.w(TAG, "bad envelope: ${e.message}")
        null
    }

    /** Определяет mime по сигнатуре декодированных байт (webp/jpg/png). */
    private fun guessMime(bytes: ByteArray): String = when {
        bytes.size > 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "image/webp"
        bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size > 8 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "image/png"
        else -> "image/webp"
    }
}
