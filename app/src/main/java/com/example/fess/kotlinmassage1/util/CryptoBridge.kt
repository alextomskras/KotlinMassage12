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
    fun decryptText(msgId: String, envelopeB64: String): String? {
        return try {
        val env = jsonToEnvelope(envelopeB64) ?: return null
        CryptoBox.decryptText(env, msgId)
    } catch (e: Exception) {
        Log.w(TAG, "decryptText($msgId) failed: ${e.message}")
        null
    }
    }

    // -------------------------------------------------------------- картинки

    /**
     * Готовит relay-узл /transfers/<id> с ЗАШИФРОВАННЫМ телом.
     * @param base64Payload data-URI base64 оригинала (ImageUtils.compressToResult).
     * @return map для setValue или null, если шифрование невозможно (тогда
     *   вызывающий пишет открытую "data" — старый получатель её прочитает).
     */
    fun buildEncryptedTransferNode(
        context: Context,
        toUid: String,
        msgId: String,
        base64Payload: String,
        fromId: String,
        nowSec: Long,
        ttlSec: Long
    ): Map<String, Any>? {
        return try {
        val pub = pubKeyCache[toUid] ?: return null
        val raw = Base64.decode(base64Payload.substringAfter("base64,"), Base64.NO_WRAP)
        val env = CryptoBox.encrypt(raw, pub, msgId, image = true)
        mapOf(
            "fromId" to fromId,
            "toIds" to listOf(toUid),
            "type" to "image",
            "mime" to ImageUtils.mimeOf(base64Payload),
            "sizeBytes" to base64Payload.length, // размер шифртекста ~ размер тела
            "createdAt" to nowSec,
            "expiresAt" to nowSec + ttlSec,
            "enc" to env.encB64,
            "epk" to env.epkB64,
            "alg" to env.alg,
            "deliveredTo" to mapOf(fromId to nowSec)
        )
    } catch (e: Exception) {
        Log.e(TAG, "buildEncryptedTransferNode failed", e)
        null
    }
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
