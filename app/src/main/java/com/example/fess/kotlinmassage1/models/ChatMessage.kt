package com.example.fess.kotlinmassage1.models

/**
 * Сообщение чата.
 *
 * Тип сообщения больше НЕ определяется эвристикой "substringBefore('.') == https://firebasestorage".
 * Картинки лежат прямо в поле [text] как base64 data-URI (data:image/webp;base64,...;
 * у старых сообщений — data:image/jpeg), а явный признак типа — префикс "data:image";
 * см. ImageUtils.isImagePayload().
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
    val transferRef: String? = null,
    /**
     * E2EE v1 (docs/ENCRYPTION_CONCEPT.md): текст зашифрован (CryptoBox AES-GCM),
     * в [text] лежит base64 JSON-конверта {"enc","epk","alg"}.
     */
    val enc: Boolean = false,
    /**
     * E2EE картинка: relay-тело живёт в /transfers всего 7 дней, поэтому ключи
     * конверта (epk+enc) дублируются в зеркало диалога. Есть поле — картинку
     * можно расшифровать и после удаления relay-ноды (decryptImageFromMessage).
     */
    val env: String? = null,
    /**
     * Soft-delete («удалить для всех»): физически запись НЕ удаляется, ставится
     * флаг deleted=true (+ deletedBy = uid инициатора). Клиенты видят флаг и
     * рисуют заглушку «Сообщение удалено». Серверный cleanup (backend) сносит
     * такие записи через N дней.
     */
    val deleted: Boolean = false,
    val deletedBy: String? = null,
    /**
     * Редактирование текста: [editedText] — новый шифртекст/открытый текст,
     * [editTime] — время правки (сек). Оригинал остаётся в [text] (история);
     * UI показывает editedText с пометкой «изменено». Для E2EE правка
     * перешифровывается заново под pubkey получателя.
     */
    val editedText: String? = null,
    val editTime: Long = -1,
    /**
     * Read receipt: unix-время (сек), когда СОБЕСЕДНИК открыл диалог и увидел
     * сообщение. Пишет получатель в свою копию зеркала (/user-messages/{toId}/...),
     * отправитель подтягивает его read-listener'ом и рисует двойную синюю галочку.
     */
    val readAt: Long = -1,
    /**
     * E2EE-правка текста: после редактирования в [editedText] лежит НОВЫЙ
     * эфемерный конверт, а selfless-зеркало отправителя — здесь (старое [env]
     * относится к оригинальному тексту и для правки непригодно).
     */
    val envEdited: String? = null
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