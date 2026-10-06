package com.example.fess.kotlinmassage1.util

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Единое место хранения FCM-токенов.
 *
 * СХЕМА БД (обязательно, иначе бэкенд не найдёт адресата):
 *   /user-tokens/{uid}/{deviceId} = "<fcm token>"
 *
 * deviceId — стабильный идентификатор установки, лежит в локальных SharedPreferences
 * (генерится один раз). Это позволяет:
 *  - хранить несколько устройств одного юзера;
 *  - перезаписывать токен на одном и том же месте при ротации FCM-токена;
 *  - удалять протухшие токены с той же гранулярностью.
 *
 * ВАЖНО: сервисный ключ Firebase (serviceAccount.json) хранится ТОЛЬКО на бэкенде.
 * Клиент пишет сюда лишь свой FCM-токен (это публичная информация, она безопасна).
 */
object TokenStore {

    private const val TAG = "TokenStore"
    private const val PREFS_NAME = "messenger_prefs"
    private const val KEY_DEVICE_ID = "device_id"

    /** Стабильный id устройства/установки. */
    fun deviceId(context: Context): String {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    /** Сохраняет текущий FCM-токен текущего залогиненного юзера (modern API вместо IID). */
    fun saveCurrentToken(context: Context) {
        val uid = FirebaseAuth.getInstance().uid ?: return
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (!token.isNullOrEmpty()) write(uid, deviceId(context), token)
            }
    }

    fun write(uid: String, deviceId: String, token: String) {
        FirebaseDatabase.getInstance()
            .getReference("user-tokens/$uid/$deviceId")
            .setValue(token)
    }

    /** Удаляет токен текущего устройства текущего юзера (при logout). */
    fun removeCurrentToken(context: Context) {
        val uid = FirebaseAuth.getInstance().uid ?: return
        FirebaseDatabase.getInstance()
            .getReference("user-tokens/$uid/${deviceId(context)}")
            .removeValue()
    }

    /** Читает все токены получателя (для отправки push через бэкенд). */
    fun fetchTokens(toUid: String, callback: (List<String>) -> Unit) {
        val ref = FirebaseDatabase.getInstance().getReference("user-tokens/$toUid")
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val tokens = snapshot.children.mapNotNull { it.getValue(String::class.java) }
                callback(tokens)
            }

            override fun onCancelled(error: DatabaseError) {
                callback(emptyList())
            }
        })
    }

    /**
     * Убирает из БД токен, который FCM вернул как невалидный (error UNREGISTERED).
     * Ищем по значению среди устройств юзера.
     */
    fun removeStaleToken(uid: String, staleToken: String) {
        val ref = FirebaseDatabase.getInstance().getReference("user-tokens/$uid")
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    if (child.getValue(String::class.java) == staleToken) {
                        child.ref.removeValue()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }
}
