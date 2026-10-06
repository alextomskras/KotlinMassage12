package com.example.fess.kotlinmassage1.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Единый помощник по каналу уведомлений.
 * Раньше канал создавался только при первом входящем пуше — если приложение
 * запускалось впервые и юзер сам отправлял сообщение, отправка в "канал без
 * канала" на Android 8+ молча терялась. Теперь вызываем из onCreate активити.
 */
object NotificationHelper {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(com.example.fess.kotlinmassage1.service.MyFirebaseMessagingService.CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    com.example.fess.kotlinmassage1.service.MyFirebaseMessagingService.CHANNEL_ID,
                    "Сообщения",
                    NotificationManager.IMPORTANCE_HIGH
                )
                channel.description = "Уведомления о новых сообщениях"
                manager.createNotificationChannel(channel)
            }
        }
    }
}
