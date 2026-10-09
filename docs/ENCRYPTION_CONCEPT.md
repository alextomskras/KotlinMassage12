# Концепт шифрования для KotlinMassage1 (E2EE поверх Firebase RTDB)

Документ описывает, **надо ли** нам шифрование, **как** мы его делаем, **где берём ключи**
и как это ложится на текущую схему БД (`DbPaths.kt`, `ChatMessage.kt`, relay-зону `/transfers`).

---

## 1. Надо ли вообще? Что у нас сейчас открыто

Firebase Realtime Database — «глупное» хранилище: сервер Firebase видит и хранит всё
в открытом виде, доступ регулируется только правилами (`database-rules.json`).
Сегодня в открытом виде лежат:

| Данные | Где | Риск сегодня |
|---|---|---|
| Текст сообщений | `/user-messages/{fromId}/{toId}`, `/latest-messages` | обход/компрометация правил или аккаунта = чтение переписки; текст виден самому Firebase |
| Тела картинок base64 | `/transfers/{msgId}` (7 дней, TTL) | картинка доступна любому, кто знает msgId, пока не истёк TTL |
| Профили (username, аватар) | `/users/{uid}` | низкая чувствительность |
| FCM-токены | `/user-tokens/{uid}/{deviceId}` | утечка токена = спам push-ами (не чтение данных) |

Плюсы E2EE для нашего стека:
- сообщения и картинки бессмысленны для Firebase, администратора БД и любого, кто обойдёт rules (например, через скомпрометированный сервисный ключ релея);
- relay-зону `/transfers` можно сделать публичной по чтению — без ключа соседа это шум;
- правила БД упрощаются: вместо «кто что видит» — «видят все, читают только участники».

Минусы/ограничения (честно):
- серверный поиск по истории невозможен (у нас он и так только клиентский);
- бэкенд-релей не сможет класть текст в push — только «Новое сообщение» (так делает Signal);
- при потере устройства новая установка не расшифрует старую историю — нужен backup-механизм (п.8).

**Вывод: да, стоит.** Объём работ небольшой — весь путь данных идёт через RTDB и релей.

---

## 2. Схема в два слоя: X25519-инкапсуляция + AES-GCM, поверх — ratchet для текстов

### 2.1. Обмен ключами (public-key слой)

Цель: у отправителя появляется общий секрет с получателем; **публичный ключ соседа берём из `/users/{uid}/publicKey`**.

Алгоритм **X25519** (Curve25519 ECDH) — быстрый, 32-байтные ключи, есть в Bouncy Castle.

```
Alice (отправитель)                                   Bob (получатель)
-----------                                            -----------
1. Читает /users/{bobUid}/publicKey   ──pub_bob────►
2. Генерит эфемерную пару (e_priv, e_pub)
3. shared = X25519(e_priv, pub_bob)
4. HKDF(shared, salt=msgId, info="kotlinmassage1/v1") → aes_key(32B) + iv(12B)
5. Шифрует AES-GCM(aes_key, iv, payload)
6. Пишет в /transfers/{msgId}: { enc, epk=e_pub, ... }
                                                       7. Читает запись, берёт epk
                                                       8. shared = X25519(priv_bob, epk)
                                                       9. Тот же HKDF → aes_key+iv → дешифрует
```

Почему не RSA-OAEP: RSA-2048 даёт 256-байтный шифртекст и медленный; X25519 — 32 байта, быстрее и безопаснее.

### 2.2. Симметричный слой на сообщение (Symmetric Ratchet) — оптимизация для текстов

Для текстов внутри чата (они идут в `/user-messages`) можно не плодить эфемерные пары, а вести цепочку ключей:

```
root_key_0  = X25519(my_priv, their_pub)        // общий секрет пары
chain_key_0 = HKDF(root_key_0, "init")
для сообщения i:
    chain_key_{i+1} = HMAC(chain_key_i, 0x87)   // шаг вперёд
    msg_key_i       = HMAC(chain_key_i, 0x38)
    iv_i            = HMAC(chain_key_i, "iv")[0..12]
    ciphertext_i    = AES-GCM(msg_key_i, iv_i, plaintext_i)
```

Даёт forward secrecy между сообщениями: компромисс одного msg_key не вскрывает предыдущие.
Без полноценного Double Ratchet — для MVP достаточно; апгрейд до Signal Protocol возможен позже.

### 2.3. Почему нельзя «просто зашифровать файл открытым ключом соседа»

Асимметрия шифрует только короткие симметричные ключи — картинку до 10 МБ напрямую X25519/RSA не зашифровать. Поэтому схема всегда гибридная:
- асимметрия — только инкапсуляция 32-байтного session key;
- сам файл — AES-GCM этим ключом.

«Файл шифруется открытым ключом соседа» в бытовом смысле = **session key → инкапсулирован через X25519 к pub_bob → данные AES-ом**.

---

## 3. Куда мы берём открытый ключ соседа

Единственный источник истины — наша же RTDB:

```
/users/{uid}/publicKey      = base64(X25519 pub, 32 bytes)
/users/{uid}/keyFingerprint = base64(SHA256(pub)[0..6])   // для ручной верификации
/users/{uid}/keyCreatedAt   = timestamp
```

- Чтение: `".read": true` — публичные ключи не секрет.
- Запись: только владелец uid (и один раз / при ротации).

Клиент при первом открытии чата читает `users/toUid/publicKey`, кэширует локально (Map<uid, pub>, TTL 24 ч). Если ключа нет — диалог «ждём ключ собеседника».

Ужесточение против MITM (по мере надобности):
- **TOFU** (Trust On First Use): запоминаем первый увиденный pub_key; при смене — предупреждение «ключ изменился, сверьте fingerprint»;
- **QR-safety-numbers**: показываем keyFingerprint в профиле, юзеры сверяют вручную (как WhatsApp/Signal);
- **PKI на реле**: релей подписывает связку uid→pubKey своим service-account ключом, клиент проверяет подпись.

Для MVP: TOFU + fingerprint в UI. Ноль инфраструктуры, уже лучше, чем ничего.

---

## 4. Как шифруется картинка (concrete)

Текущий flow (ChatLogActivity): bitmap → WebP → base64 data-URI → `/transfers/{msgId}`.

Становится:

```
1. bitmap → WebP bytes (ImageUtils.encodeWithLadder, q=80)
2. ephemeral X25519 pair (e_priv, e_pub)
3. shared = X25519(e_priv, pub_partner)
4. (aes_key, iv) = HKDF-SHA256(shared, salt=msgId, info="img/v1") → 32B + 12B
5. encPayload = AES-GCM(aes_key, iv, webpBytes)   // tag 16B приложен
6. В /transfers/{msgId}:
     {
       "enc": base64(encPayload),
       "epk": base64(e_pub),          // эфемерный публичный ключ отправителя
       "alg": "x25519+hkdf+aes-gcm-256",
       "mime": "image/webp",           // метаданные НЕ шифруем — нужно для кэша/ACK
       "expiresAt": ...,               // TTL как сейчас
       "deliveredTo": { uid: ts }      // ACK как сейчас
     }
7. В /user-messages остаётся transferRef + msgType=image (как сейчас)
```

Дешифровка получателя: epk → X25519(priv_me, epk) → HKDF → AES-GCM.decrypt → webp bytes → ImageCache.putFromBytes → Bitmap.

Важно: расширение файла в ImageCache определяется по сигнатуре байт (RIFF...WEBP vs SOI JPEG) — после дешифровки байты те же, логика LRU/trim/migration не меняется.

---

## 5. Текстовые сообщения

В `/user-messages` поле `text` становится base64 от шифртекста (схема п.2.2 или та же эфемерная, что и для картинок — проще начать с единой эфемерной):

```json
{
  "id": "...", "fromId": "...", "toId": "...", "timestamp": 0,
  "msgType": "text",
  "enc": "<base64 AES-GCM>",
  "epk": "<base64 32B>",
  "ctr": 42
}
```

При пропуске ctr — каждое сообщение независимо (свой epk/msg_key), resync не нужен вовсе; ctr оставляем только для порядка/детекта дублей.

---

## 6. Приватность метаданных (что остаётся открытым)

Даже с E2EE наружу торчат:
- uid отправителя/получателя, граф чатов;
- timestamps;
- размеры сообщений (маскируются padding'ом до кратных 256 Б);
- факт «это картинка» (msgType=image видно всем).

Padding и heartbeat — осознанно откладываем, фиксируем как известное ограничение.

---

## 7. Push-уведомления через релей

Релей слушает `/outbox`, читает `/user-tokens/{toUid}`, шлёт FCM. Теперь title/body нельзя брать из текста.

Решение:
- notification body = «Новое сообщение» (имя чата и так лежит в `/users`);
- опционально: в `/outbox` кладём `previewEnc` (зашифрован общим ключом пары) — приложение само расшифрует и покажет in-app превью; FCM несёт только флаг.

---

## 8. Управление ключами пользователя

- **Генерация**: при первом входе после авторизации — `KeyManager.ensureKeys()` (вызов из Register/Login).
- **Хранение приватника**: Android Keystore (alias `km_x25519`), non-exportable. Нюанс: аппаратная поддержка X25519/EC в Keystore стабильна с API 23; для minSdk 21–22 фолбэк — wrap приватника AES-ключом из Keystore RSA и хранение обёртки в SharedPreferences.
- **Ротация**: новая пара; старый pubkey помечается retired. Старые сообщения остаются читаемыми (в каждом лежал epk, priv_bob не менялся).
- **Backup**: экспорт seed'а (32B) под паролем (Argon2id/PBKDF2 → AES-GCM) в файл — «Настройки → Резервная копия ключей». Без него новая установка не прочитает историю.

---

## 9. Изменения в коде (карта PR)

1. `util/KeyManager.kt` (новый): генерация X25519, Keystore wrap/unwrap, публикация pubkey в `/users/{uid}/publicKey`, fingerprint.
2. `util/CryptoBox.kt` (новый): `encrypt(payload, recipientPub, msgId): EncryptedBlob`, `decrypt(blob, myPriv)`; HKDF + AES-GCM.
3. `models/ChatMessage.kt`: поля enc/epk/ctr (или отдельный Envelope).
4. `messages/ChatLogActivity.kt`: перед записью в `/transfers` и `/user-messages` — CryptoBox.encrypt; при загрузке — decrypt.
5. `util/ImageLoader.kt` / `FullscreenImageDialog.kt`: после получения base64 из transfers — сначала decrypt, затем decode.
6. `util/ImageCache.kt`: без изменений (работает с пост-дешифрованными байтами).
7. `RegisterActivity.kt` / `LoginActivity.kt`: `KeyManager.ensureKeys()` после успешной авторизации.
8. `database-rules.json`: запись `users/$uid/publicKey` только владельцу; `transfers` — read-any (там только шифртекст).
9. `backend/relay.py`: крипто не трогает (пересылает opaque blob), но убирает text-preview из FCM.

Зависимость: BouncyCastle `org.bouncycastle:bcprov-jdk18on` (X25519/ECDH/HKDF) — ~несколько МБ APK. Альтернатива — libsodium-JNI.

---

## 10. Дорожная карта

- **v1 (MVP)**: X25519 ephemeral per message + AES-GCM для картинок и текстов; TOFU; Keystore для приватника.
- **v1.1**: symmetric ratchet для текстов (меньше затрат, чем эфемерная пара на сообщение).
- **v2**: Double Ratchet / Signal Protocol, sealed sender, disappearing messages с криптографическим стиранием ключей.
- **v3**: encrypted backups, групповые чаты (sender keys).

---

## 11. Открытые вопросы (решаем вместе)

1. Превью картинки в списке чатов (`/latest-messages`): незашифрованный blurred thumbnail (UX ценой части инфы) или только после дешифровки (медленнее)?
2. Padding размеров — делаем или нет?
3. Backup-фраза: генерируем сами (24 слова) или просим пароль пользователя?
4. BouncyCastle в APK (~+5–7 МБ) acceptable, или пишем X25519 сами/берём sodium?
