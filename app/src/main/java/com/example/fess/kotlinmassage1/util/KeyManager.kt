package com.example.fess.kotlinmassage1.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Управление X25519-ключевой парой пользователя (см. docs/ENCRYPTION_CONCEPT.md, п.8).
 *
 * Как это работает на любом устройстве, включая эмулятор Android:
 *  - приватный ключ (32 байта) генерируется через BouncyCastle X25519;
 *  - он НЕ хранится в открытом виде: оборачивается (AES-GCM) ключом из
 *    Android Keystore (alias [KS_ALIAS]). На эмуляторе это software-Keystore
 *    (DefaultKeystore), на реальном железе — TEE/StrongBox; API одинаковый,
 *    поэтому разработка на симуляторе полностью рабочая;
 *  - public key публикуется в /users/{uid}/publicKey, fingerprint — рядом;
 *  - TOFU-кэш увиденных чужих ключей лежит в filesDir/tofu_keys (смена ключа
 *    собеседника = предупреждение в логе/UI).
 *
 * minSdk 21: AES-GCM и KeyStore доступны с API 21, так что fallback-ветка не нужна.
 */
object KeyManager {

    private const val TAG = "KeyManager"

    /** Alias симметричного ключа обёртки в Android Keystore. */
    const val KS_ALIAS = "km_x25519_wrap"

    private const val WRAP_FILE_NAME = "x25519_secret.wrapped"
    private const val IV_FILE_NAME = "x25519_secret.iv"
    private const val PUB_FILE_NAME = "x25519_public.bin"
    private const val TOFU_DIR_NAME = "tofu_keys"

    /** Префикс wrapped-файла: версия формата + nonce нельзя менять без миграции. */
    private const val WRAP_VERSION = 1

    @Volatile
    private var cachedPriv: X25519PrivateKeyParameters? = null

    // ------------------------------------------------------------------ API

    /**
     * Гарантирует наличие пары ключей у текущего юзера и опубликованный pubkey.
     * Вызывать после успешного логина/регистрации (Login/RegisterActivity).
     * Идемпотентен: если pubkey уже опубликован и совпадает с локальным — no-op.
     */
    fun ensureKeys(onDone: (pubKeyB64: String) -> Unit) {
        try {
            val priv = getOrCreatePrivateKey()
            val pub = publicKeyOf(priv)
            publishPublicKey(pub) {
                onDone(encodeBase64(pub.getEncoded()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureKeys failed", e)
            // Не роняем экран логина: сообщения уйдут незашифрованными до починки ключей.
            onDone("")
        }
    }

    /** Приватный ключ пары (unwrap из Keystore-wrapped файла), кэшируется в памяти. */
    @Throws(Exception::class)
    fun getPrivateKey(): X25519PrivateKeyParameters {
        cachedPriv?.let { return it }
        synchronized(this) {
            cachedPriv?.let { return it }
            val f = wrappedFile()
            if (!f.exists()) throw IllegalStateException("Keypair not initialized; call ensureKeys()")
            val unwrapped = unwrap(readBytes(f))
            cachedPriv = unwrapped
            return unwrapped
        }
    }

    fun getPublicKey(): X25519PublicKeyParameters = publicKeyOf(getPrivateKey())

    /** base64(NO_WRAP) публичного ключа — то, что лежит в /users/{uid}/publicKey. */
    fun publicKeyBase64(): String = encodeBase64(getPublicKey().getEncoded())

    /**
     * Короткий отпечаток для ручной верификации (TOFU): первые 6 байт SHA-256,
     * попарно в hex, напр. "a1b2.c3d4.e5f6".
     */
    fun fingerprint(pub: ByteArray = getPublicKey().getEncoded()): String {
        val d = MessageDigest.getInstance("SHA-256").digest(pub).copyOfRange(0, 6)
        return d.joinToString(".") { "%02x".format(it) }
    }

    /**
     * Читает публичный ключ собеседника из /users/{uid}/publicKey с TOFU-проверкой.
     * @param onKey получен pubkey; @param onError причина: ключа нет или он СМЕНИЛСЯ.
     */
    fun fetchPartnerKey(uid: String, onKey: (X25519PublicKeyParameters) -> Unit, onError: (String) -> Unit) {
        FirebaseDatabase.getInstance().getReference("${DbPaths.user(uid)}/publicKey")
            .get()
            .addOnSuccessListener { snap ->
                val b64 = snap.value as? String
                if (b64.isNullOrEmpty()) {
                    onError("У собеседника ещё нет ключа")
                    return@addOnSuccessListener
                }
                val pub = decodeBase64(b64)
                if (pub == null || pub.size != 32) {
                    onError("Некорректный pubkey в базе")
                    return@addOnSuccessListener
                }
                when (tofuCheck(uid, pub)) {
                    TofuResult.OK, TofuResult.STORED_FIRST -> onKey(X25519PublicKeyParameters(pub, 0))
                    TofuResult.CHANGED -> {
                        Log.e(TAG, "TOFU: ключ $uid изменился! Старый fp=${tofuPrevFp}, новый=${fingerprint(pub)}")
                        onError("Ключ собеседника изменился (mitm?). Сверьте отпечатки: ${fingerprint(pub)}")
                    }
                }
            }
            .addOnFailureListener { onError("Не удалось прочитать ключ: ${it.message}") }
    }

    // ------------------------------------------------------------- генерация

    private fun getOrCreatePrivateKey(): X25519PrivateKeyParameters {
        cachedPriv?.let { return it }
        synchronized(this) {
            cachedPriv?.let { return it }
            val f = wrappedFile()
            if (f.exists()) {
                val p = unwrap(readBytes(f))
                cachedPriv = p
                return p
            }
            val gen = X25519KeyPairGenerator()
            gen.init(X25519KeyGenerationParameters(SecureRandom()))
            val pair = gen.generateKeyPair()
            val priv = pair.private as X25519PrivateKeyParameters
            val wrapped = wrap(priv.getEncoded())
            atomicWrite(wrappedFile(), wrapped)
            atomicWrite(pubFile(), pair.public.getEncoded())
            cachedPriv = priv
            Log.i(TAG, "X25519 keypair generated, fingerprint=${fingerprint(pair.public.getEncoded())}")
            return priv
        }
    }

    private fun publicKeyOf(priv: X25519PrivateKeyParameters): X25519PublicKeyParameters = priv.generatePublicKey()

    /** Эфемерная приватная пара для одного сообщения (использует CryptoBox). */
    fun ephemeralPrivateKey(): X25519PrivateKeyParameters {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(SecureRandom()))
        return gen.generateKeyPair().private as X25519PrivateKeyParameters
    }

    // ----------------------------------------------------------- публикация

    private fun publishPublicKey(pub: X25519PublicKeyParameters, done: () -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.w(TAG, "publishPublicKey: user not logged in")
            done()
            return
        }
        val b64 = encodeBase64(pub.getEncoded())
        val usersRef = FirebaseDatabase.getInstance().getReference(DbPaths.user(uid))
        usersRef.child("publicKey").get().addOnSuccessListener { snap ->
            val existing = snap.value as? String
            if (existing == b64) {
                done() // уже опубликован наш ключ
            } else if (existing != null) {
                Log.e(TAG, "publicKey mismatch in DB for $uid — не перезаписываем, чинить вручную")
                done()
            } else {
                val fp = fingerprint(pub.getEncoded())
                usersRef.updateChildren(
                    mapOf(
                        "publicKey" to b64,
                        "keyFingerprint" to fp,
                        "keyCreatedAt" to System.currentTimeMillis()
                    )
                ).addOnCompleteListener { done() }
            }
        }.addOnFailureListener {
            Log.e(TAG, "publishPublicKey read failed", it)
            done()
        }
    }

    // ------------------------------------------------------------ Keystore

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateWrappingKey(): SecretKey {
        val ks = keystore()
        (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    /** AES-256-GCM ключом из Keystore. Формат: [ver byte][ciphertext||tag], iv — рядом в файле. */
    private fun wrap(secret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        writeBytes(ivFile(), cipher.iv)
        return byteArrayOf(WRAP_VERSION.toByte()) + cipher.doFinal(secret)
    }

    private fun unwrap(blob: ByteArray): X25519PrivateKeyParameters {
        require(blob.isNotEmpty() && blob[0] == WRAP_VERSION.toByte()) { "unsupported wrap format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = readBytes(ivFile())
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateWrappingKey(),
            GCMParameterSpec(128, iv)
        )
        val secret = cipher.doFinal(blob, 1, blob.size - 1)
        return X25519PrivateKeyParameters(secret, 0)
    }

    // ---------------------------------------------------------------- TOFU

    enum class TofuResult { OK, STORED_FIRST, CHANGED }

    private var tofuPrevFp: String = ""

    private fun tofuCheck(uid: String, pub: ByteArray): TofuResult {
        val file = File(tofuDir(), uid)
        return if (file.exists()) {
            val stored = readBytes(file)
            if (stored.contentEquals(pub)) TofuResult.OK
            else {
                tofuPrevFp = fingerprint(stored)
                TofuResult.CHANGED
            }
        } else {
            atomicWrite(file, pub)
            TofuResult.STORED_FIRST
        }
    }

    // --------------------------------------------------------------- files

    /** Должен быть вызван один раз в Application.onCreate (или первой активностью). */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    private var appContext: android.content.Context? = null

    private fun ctx(): android.content.Context =
        appContext ?: throw IllegalStateException("KeyManager.init(context) not called")

    private fun secretsRoot(): File = File(ctx().filesDir, "keys").apply { mkdirs() }
    private fun wrappedFile() = File(secretsRoot(), WRAP_FILE_NAME)
    private fun ivFile() = File(secretsRoot(), IV_FILE_NAME)
    private fun pubFile() = File(secretsRoot(), PUB_FILE_NAME)
    private fun tofuDir(): File = File(ctx().filesDir, TOFU_DIR_NAME).apply { mkdirs() }

    private fun readBytes(f: File): ByteArray = f.readBytes()
    private fun writeBytes(f: File, data: ByteArray) {
        f.parentFile?.mkdirs()
        f.writeBytes(data)
    }

    private fun atomicWrite(target: File, data: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    // -------------------------------------------------------------- base64

    fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun decodeBase64(s: String): ByteArray? = try {
        Base64.decode(s, Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Общий секрет X25519 (для CryptoBox). */
    fun sharedSecret(myPriv: X25519PrivateKeyParameters, theirPub: X25519PublicKeyParameters): ByteArray {
        val agr = X25519Agreement()
        agr.init(myPriv)
        val out = ByteArray(agr.agreementSize)
        agr.calculateAgreement(theirPub, out, 0)
        return out
    }
}
