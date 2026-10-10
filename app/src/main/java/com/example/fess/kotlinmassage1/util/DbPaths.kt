package com.example.fess.kotlinmassage1.util

/**
 * Единая точка определения путей Realtime Database.
 * Клиент и бэкенд-релей должны использовать одну и ту же схему — держим её здесь,
 * чтобы не было magic-строк в активити (раньше пути дублировались в 6 файлах).
 */
object DbPaths {

    const val USERS = "users"
    const val USER_MESSAGES = "user-messages"
    const val LATEST_MESSAGES = "latest-messages"
    const val USER_TOKENS = "user-tokens"
    /** Очередь задач на push для бэкенд-релея (клиент пишет, релей читает/удаляет). */
    const val OUTBOX = "outbox"

    /**
     * Read receipts (схема «Вариант А»): отдельная зона статусов прочтения,
     * сообщения НЕ трогаем. /chat-read-status/{chatId}/{readerUid} -> узел
     * {ts: unix-секунды, msgId: id последнего прочитанного сообщения}.
     * chatId — детерминированный ID диалога (sortedChatId), одинаковый у обеих
     * сторон. Каждый участник пишет ТОЛЬКО в свой узел ({readerUid}) — правила
     * RTDB это разрешают без admin-прав; собеседник только слушает.
     * Узел всегда один timestamp на участника — дёшево (Одна запись на открытие
     * чата вместо реворка каждого сообщения) и не требует очистки.
     */
    const val CHAT_READ_STATUS = "chat-read-status"

    /** Детерминированный ID чата: отсортированная конкатенация двух UID. */
    fun sortedChatId(a: String, b: String): String =
        if (a <= b) "${a}_${b}" else "${b}_${a}"

    fun chatReadStatus(chatId: String) = "$CHAT_READ_STATUS/$chatId"

    fun chatReadBy(chatId: String, readerUid: String) = "$CHAT_READ_STATUS/$chatId/$readerUid"

    /**
     * Relay-зона картинок: тело base64 живёт здесь РОВНО 7 дней (expiresAt),
     * получатели скачивают в локальный кэш и пишут ACK deliveredTo/<uid>.
     * Бэкенд-воркер чистит data по TTL или когда все скачали. В самих сообщениях
     * остаётся только ссылка transferRef — дубликование base64 в RTDB запрещено.
     */
    const val TRANSFERS = "transfers"

    fun transfer(msgId: String) = "$TRANSFERS/$msgId"

    fun user(uid: String) = "$USERS/$uid"

    fun conversation(fromId: String, toId: String) = "$USER_MESSAGES/$fromId/$toId"

    fun latestConversation(fromId: String, toId: String) = "$LATEST_MESSAGES/$fromId/$toId"

    fun latestRoot(uid: String) = "$LATEST_MESSAGES/$uid"

    fun deviceToken(uid: String, deviceId: String) = "$USER_TOKENS/$uid/$deviceId"

    fun tokensForUser(uid: String) = "$USER_TOKENS/$uid"
}
