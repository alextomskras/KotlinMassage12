package com.example.fess.kotlinmassage1.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.util.Log
import com.example.fess.kotlinmassage1.R
import com.example.fess.kotlinmassage1.messages.ChatLogActivity
import com.example.fess.kotlinmassage1.messages.NewMessageActivity
import com.example.fess.kotlinmassage1.models.User
import com.example.fess.kotlinmassage1.registerlogin.LoginActivity
import com.example.fess.kotlinmassage1.util.TokenStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Приёмник FCM.
 *
 * ОЖИДАЕМЫЙ ФОРМАТ PUSH (генерирует бэкенд-релей, слушающий /outbox):
 *   notification: { title: "<имя отправителя>", body: "<текст или '📷 Картинка'>" }
 *   data: {
 *     "fromId": "<uid отправителя>",
 *     "toId":   "<uid получателя>",
 *     "type":   "text" | "image",
 *     "msgId":  "<ключ сообщения>"
 *   }
 *
 * Push несёт ТОЛЬКО превью — сама картинка (base64) лежит в БД и подтягивается
 * при открытии чата через ChildEventListener. Так payload держится < 4 КБ.
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val TAG = "FCM_Service"

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "onNewToken received")
        // Сохраняем по схеме /user-tokens/{uid}/{deviceId}.
        // Если юзер не залогинен — токен пропишется при логине/регистрации (см. LoginActivity/RegisterActivity).
        if (FirebaseAuth.getInstance().currentUser != null && token != null) {
            TokenStore.saveCurrentToken(this)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val fromId = data["fromId"]
        val title = remoteMessage.notification?.title ?: data["title"] ?: "Новое сообщение"
        val body = remoteMessage.notification?.body ?: data["body"] ?: ""

        Log.d(TAG, "From: ${remoteMessage.from}, type=${data["type"]}")

        sendNotification(title, body, fromId)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Сообщения",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Пуши новых сообщений мессенджера"
                enableLights(true)
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun sendNotification(title: String, body: String, fromId: String?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, buildTargetIntent(fromId),
            // FLAG_IMMUTABLE обязателен на Android 12+ (API 31), иначе — краш при создании пуша
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_fire_emoji)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)

        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    /**
     * Тап по пушу ведёт сразу в чат с отправителем (если знаем его uid),
     * иначе — на экран логина.
     */
    private fun buildTargetIntent(fromId: String?): Intent {
        if (fromId != null) {
            val user = User(fromId, "", "")
            return Intent(this, ChatLogActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(NewMessageActivity.USER_KEY, user)
            }
        }
        return Intent(this, LoginActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    companion object {
        const val CHANNEL_ID = "messenger_messages_v1"
    }
}
