package com.example.fess.kotlinmassage1.util

import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Гибридная схема «шифрование на одно сообщение» (docs/ENCRYPTION_CONCEPT.md, п.2.1/4):
 *
 *   эфемерная пара X25519 → shared secret с pub-ключом получателя
 *   → HKDF-SHA256(salt=msgId) → AES key 32B + IV 12B
 *   → AES-256-GCM над payload (картинка bytes / текст utf8).
 *
 * В базу кладётся JSON-конверт: enc (base64 ciphertext||tag), epk (base64 32B), alg.
 * Релей и Firebase видят только шум; читают лишь те, у кого есть приватник пары.
 *
 * Формат конверта совпадает с тем, что пишет/читает backend/relay.py (passthrough)
 * и с описанием в docs/ENCRYPTION_CONCEPT.md п.4.
 */
object CryptoBox {

    const val ALG = "x25519+hkdf+aes-gcm-256"

    private const val HKDF_INFO_TEXT = "kotlinmassage1/text/v1"
    private const val HKDF_INFO_IMG = "kotlinmassage1/img/v1"
    private const val TAG_BITS = 128
    private const val KEY_LEN = 32
    private const val IV_LEN = 12

    class Envelope(val encB64: String, val epkB64: String, val alg: String = ALG)

    // ------------------------------------------------------------------ API

    /** Шифрует payload под публичный ключ получателя. msgId используется как HKDF salt. */
    fun encrypt(payload: ByteArray, recipientPub: X25519PublicKeyParameters, msgId: String, image: Boolean): Envelope {
        val epriv = KeyManager.ephemeralPrivateKey()
        val epub = epriv.generatePublicKey()
        val shared = KeyManager.sharedSecret(epriv, recipientPub)
        val (key, iv) = hkdf(shared, msgId, if (image) HKDF_INFO_IMG else HKDF_INFO_TEXT)
        val ct = aesGcm(Cipher.ENCRYPT_MODE, key, iv, payload)
        return Envelope(
            encB64 = KeyManager.encodeBase64(ct),
            epkB64 = KeyManager.encodeBase64(epub.getEncoded())
        )
    }

    /**
     * Расшифровывает конверт нашим приватником. Бросает исключение при неверном ключе
     * (AES-GCM tag не сходится) — вызывающий решает, показывать ли «нет доступа».
     */
    fun decrypt(encl: Envelope, msgId: String, image: Boolean): ByteArray {
        val epkBytes = KeyManager.decodeBase64(encl.epkB64)
            ?: throw IllegalArgumentException("bad epk base64")
        val cipherData = KeyManager.decodeBase64(encl.encB64)
            ?: throw IllegalArgumentException("bad enc base64")
        val shared = KeyManager.sharedSecret(KeyManager.getPrivateKey(), X25519PublicKeyParameters(epkBytes, 0))
        val (key, iv) = hkdf(shared, msgId, if (image) HKDF_INFO_IMG else HKDF_INFO_TEXT)
        return aesGcm(Cipher.DECRYPT_MODE, key, iv, cipherData)
    }

    /** Удобство для текстов: utf8 in/out. */
    fun encryptText(text: String, recipientPub: X25519PublicKeyParameters, msgId: String): Envelope =
        encrypt(text.toByteArray(Charsets.UTF_8), recipientPub, msgId, image = false)

    fun decryptText(encl: Envelope, msgId: String): String =
        String(decrypt(encl, msgId, image = false), Charsets.UTF_8)

    /**
     * Дешифровка конверта СТОРОНОЙ ОТПРАВИТЕЛЯ (для картинок). Ключевой момент:
     * в prepareImageEnvelope epk конверта — это НЕ эфемерида, а static pubkey
     * самого отправителя (KeyManager.getPublicKey()), поэтому shared secret
     * восстанавливается его же приватником: ECDH(priv_sender, epk=pub_sender).
     * Именно так отправитель видит свою картинку без хранения открытого
     * оригинала где-либо ещё. Бросает исключение при несовпадении ключей
     * (перелогин/смена пары) — вызывающий решает про заглушку.
     */
    fun decryptSender(encl: Envelope, msgId: String, image: Boolean): ByteArray {
        val epkBytes = KeyManager.decodeBase64(encl.epkB64)
            ?: throw IllegalArgumentException("bad epk base64")
        val cipherData = KeyManager.decodeBase64(encl.encB64)
            ?: throw IllegalArgumentException("bad enc base64")
        // Ключевая ошибка прошлой реализации: sender-конверт шифровался как
        // ECDH(ephemeral_priv, pub_sender), т.е. shared зависит от ЭФЕМЕРИДЫ,
        // которую никто не сохраняет. Дешифровка «своим приватником против
        // epk=pub_sender» даёт ДРУГОЙ shared secret — AES-GCM tag никогда не
        // сходился, и отправитель вечно видел заглушку. Правильный путь для
        // картинок — selfless-конверт (encryptSelfless/decryptSelfless).
        val shared = KeyManager.sharedSecret(KeyManager.getPrivateKey(), X25519PublicKeyParameters(epkBytes, 0))
        val (key, iv) = hkdf(shared, msgId, if (image) HKDF_INFO_IMG else HKDF_INFO_TEXT)
        return aesGcm(Cipher.DECRYPT_MODE, key, iv, cipherData)
    }

    /**
     * Конверт «сам себе»: симметричный ключ выводится из собственного static
     * приватника + msgId (HKDF без DH-партнёра). Только владелец приватника
     * может вывести тот же ключ — с точки зрения третьих лиц это так же
     * непрозрачно, что и обычный конверт, но расшифровать может ТОЛЬКО сам
     * отправитель. Именно так хранится зеркало картинки в msg.env: картинка
     * физически не может быть зашифрована «в оба конца одним телом», поэтому
     * relay-нода несёт receiver-конверт (эфемерида под pubkey получателя), а
     * msg.env — selfless-конверт того же тела.
     */
    fun encryptSelfless(payload: ByteArray, msgId: String, image: Boolean): Envelope {
        val privEnc = KeyManager.getPrivateKey().getEncoded()
        val shared = MessageDigest.getInstance("SHA-256").digest(privEnc)
        val (key, iv) = hkdf(shared, msgId, if (image) HKDF_INFO_IMG else HKDF_INFO_TEXT)
        val ct = aesGcm(Cipher.ENCRYPT_MODE, key, iv, payload)
        return Envelope(
            encB64 = KeyManager.encodeBase64(ct),
            // маркер selfless-пути вместо чужого epk; на дешифровке не участвует
            epkB64 = KeyManager.encodeBase64("SELF".toByteArray(Charsets.UTF_8))
        )
    }

    /** Дешифровка selfless-конверта (см. encryptSelfless). Бросает исключение при неверном ключе. */
    fun decryptSelfless(encl: Envelope, msgId: String, image: Boolean): ByteArray {
        val cipherData = KeyManager.decodeBase64(encl.encB64)
            ?: throw IllegalArgumentException("bad enc base64")
        val privEnc = KeyManager.getPrivateKey().getEncoded()
        val shared = MessageDigest.getInstance("SHA-256").digest(privEnc)
        val (key, iv) = hkdf(shared, msgId, if (image) HKDF_INFO_IMG else HKDF_INFO_TEXT)
        return aesGcm(Cipher.DECRYPT_MODE, key, iv, cipherData)
    }

    // ------------------------------------------------------------- internals

    /**
     * HKDF-SHA256 (RFC 5869), expand-часть достаточно сильна для нашего кейса:
     * OKM = HMAC(salt-derived-prk, info || 0x01 || 0x02) → 64B → key(32)+iv(12).
     * Реализовано вручную поверх javax.crypto.Mac, чтобы не тянуть BC KDF-обёртки.
     */
    private fun hkdf(shared: ByteArray, salt: String, info: String): Pair<ByteArray, ByteArray> {
        val prk = hmac(salt.toByteArray(Charsets.UTF_8), shared)
        val t1 = hmac(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(0x01))
        val t2 = hmac(prk, t1 + info.toByteArray(Charsets.UTF_8) + byteArrayOf(0x02))
        val okm = t1 + t2 // 64 байта
        return okm.copyOfRange(0, KEY_LEN) to okm.copyOfRange(KEY_LEN, KEY_LEN + IV_LEN)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun aesGcm(mode: Int, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(input)
    }

    /** SHA-256 от шифртекста — можно класть в базу как дедуп-хэш (не используется пока). */
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}
