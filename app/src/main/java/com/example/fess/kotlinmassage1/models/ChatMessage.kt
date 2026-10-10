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
     * Delivery receipt: unix-время (сек), когда СЕРВЕРНЫЙ Релей доставил пуш
     * получателю. Источник истины — /user-messages/{toId}/{fromId}/{nodeKey}/
     * delivered (пишет backend outbox_relay при успешной отправке FCM).
     * Отправитель подтягивает его read-tracker'ом и рисует одинарную СИНЮЮ
     * галочку; readAt > 0 перекрывает её двойной синей («прочитано»).
     */
    val deliveredAt: Long = -1,
    /**
     * E2EE-правка текста: после редактирования в [editedText] лежит НОВЫЙ
     * эфемерный конверт, а selfless-зеркало отправителя — здесь (старое [env]
     * относится к оригинальному тексту и для правки непригодно).
     */
    val envEdited: String? = null,
    /**
     * Пункт 13 (swipe reply): id сообщения-источника в ЗЕРКАЛЕ АВТОРА + превью
     * его текста (<=90 символов). Пишутся открытым текстом даже в E2EE — это
     * метаданные ответа, сам контент цитаты не раскрывает (только первые символы
     * уже отправленного текста, как превью пуша).
     */
    val replyToId: String? = null,
    val replyPreview: String? = null
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"

        /**
         * Ручная десериализация узла RTDB в ChatMessage через сырые листы.
         *
         * Штатный snapshot.getValue(ChatMessage::class.java) на E2EE-записях
         * кидает NumberFormatException и СЪЕДАЕТ ВЕСЬ УЗЕЛ: Firebase пытается
         * привести строковые поля к Long/Int, потому что у примитивных полей
         * модели нет @Exclude — а в text/id/transferRef/env лежит base64
         * конверта или push-ключ ("-N..."), которые парсятся как числа только
         * с ошибкой. Узел отбрасывался целиком -> строка не рисовалась, receipt
         * не писался, галочки у отправителя не менялись. Здесь каждое поле
         * читается со своим типом и защитой от мусора.
         */
        @JvmStatic
        fun fromSnapshot(s: com.google.firebase.database.DataSnapshot): ChatMessage? {
            if (!s.exists()) return null
            fun str(field: String): String = s.child(field).getValue(String::class.java) ?: ""
            // id может быть числовым только у древних записей — нормализуем в строку
            val idRaw = s.child("id").value
            val id = when (idRaw) {
                is String -> idRaw
                null -> ""
                else -> idRaw.toString()
            }
            val transferRefRaw = s.child("transferRef").value
            val envRaw = s.child("env").value
            val envEditedRaw = s.child("envEdited").value
            val editedTextRaw = s.child("editedText").value
            val deletedByRaw = s.child("deletedBy").value
            return ChatMessage(
                id = id,
                text = str("text"),
                fromId = str("fromId"),
                toId = str("toId"),
                timestamp = s.child("timestamp").getValue(Long::class.java) ?: -1L,
                msgType = s.child("msgType").getValue(String::class.java) ?: TYPE_TEXT,
                transferRef = when (transferRefRaw) {
                    is String -> transferRefRaw
                    is Number -> transferRefRaw.toString()
                    else -> null
                },
                enc = s.child("enc").getValue(Boolean::class.java) ?: false,
                env = if (envRaw is String) envRaw else null,
                deleted = s.child("deleted").getValue(Boolean::class.java) ?: false,
                deletedBy = if (deletedByRaw is String) deletedByRaw else null,
                editedText = if (editedTextRaw is String) editedTextRaw else null,
                editTime = s.child("editTime").getValue(Long::class.java) ?: -1L,
                readAt = s.child("readAt").getValue(Long::class.java) ?: -1L,
                // delivered пишет релей: либо unix-секунды (Long), либо true
                // (legacy-формат boolean). Оба варианта нормализуем в время.
                deliveredAt = when (val dRaw = s.child("delivered").value) {
                    is Number -> dRaw.toLong()
                    is Boolean -> if (dRaw) System.currentTimeMillis() / 1000 else -1L
                    else -> -1L
                },
                envEdited = if (envEditedRaw is String) envEditedRaw else null,
                replyToId = s.child("replyToId").getValue(String::class.java),
                replyPreview = s.child("replyPreview").getValue(String::class.java)
            )
        }
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