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
    val timestamp: Long = -1,
    /** Явный тип сообщения ("text"/"image"). Пишется новой версией клиента. */
    val msgType: String = TYPE_TEXT,
    /**
     * Для картинок нового формата: ключ relay-зоны /transfers/<transferRef>,
     * где лежит base64-тело (живёт 7 дней). В самом сообщении тела нет —
     * это убирает дубли base64 в RTDB.
     */
    val transferRef: String? = null
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
    }

    /**
     * Тип с обратной совместимостью: старые записи без поля msgType определяются
     * по содержимому (base64 data-URI или URL из legacy Firebase Storage).
     */
    val type: String
        get() = when {
            msgType == TYPE_IMAGE -> TYPE_IMAGE
            !transferRef.isNullOrEmpty() -> TYPE_IMAGE // новый relay-формат
            text.startsWith("data:image") -> TYPE_IMAGE
            text.startsWith("https://firebasestorage") -> TYPE_IMAGE // legacy Storage-URL
            else -> TYPE_TEXT
        }

    /** uid собеседника относительно текущего пользователя. */
    fun partnerId(myUid: String?): String =
        if (fromId == myUid) toId else fromId
}