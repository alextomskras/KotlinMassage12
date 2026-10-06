package com.example.fess.kotlinmassage1.models

/**
 * Сообщение чата.
 *
 * Тип сообщения больше НЕ определяется эвристикой "substringBefore('.') == https://firebasestorage".
 * Картинки лежат прямо в поле [text] как base64 data-URI (data:image/jpeg;base64,...),
 * а явный признак типа — префикс; см. ImageUtils.isImagePayload().
 *
 * Поле [pushed] нужно для бэкенда-релея: он слушает /outbox и отправляет FCM,
 * затем может пометить запись обработанной.
 */
class ChatMessage(
    val id: String = "",
    val text: String = "",
    val fromId: String = "",
    val toId: String = "",
    val timestamp: Long = -1
) {
    /** "image" если это base64-payload, иначе null/"text" — обратно совместимо со старыми записями. */
    val type: String?
        get() = if (text.startsWith("data:image")) "image" else "text"
}