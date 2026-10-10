# KotlinMassage1

Мессенджер на Android (Kotlin) с Firebase Authentication, Realtime Database и FCM
push-уведомлениями, плюс Python-бэкенд — релей доставок (`backend/`).

## Возможности
- Регистрация / вход через Firebase Auth (`registerlogin/`)
- Список последних сообщений, новый чат, экран переписки (`messages/`)
- Текстовые сообщения и картинки (base64 в Realtime DB, без Firebase Storage)
- **E2EE-шифрование**: X25519 + HKDF-SHA256 + AES-256-GCM, ключи в Android Keystore
- Push-уведомления через FCM (`service/MyFirebaseMessagingService.kt`)
- Жёсткие правила БД + серверный обход через Admin SDK (outbox-релей)

## Структура проекта
```
├── app/                      # Android-приложение (Kotlin)
│   ├── google-services.json  # конфигурация Firebase-проекта (модуль app)
│   ├── database-rules.json   # правила Realtime Database для деплоя
│   └── src/main/java/com/example/fess/kotlinmassage1/
│       ├── messages/         # ChatLogActivity, LatestMessagesActivity, NewMessageActivity
│       ├── models/           # ChatMessage, User
│       ├── registerlogin/    # LoginActivity, RegisterActivity
│       ├── service/          # MyFirebaseMessagingService
│       ├── util/             # DbPaths, ImageLoader/Cache/Utils, KeyManager/CryptoBox/CryptoBridge,
│       │                     # FullscreenImageDialog/ZoomableImageView, NotificationHelper, TokenStore
│       └── views/            # ChatItems, ChatRecyclerAdapter, LatestMessageRow
├── backend/                  # FastAPI/worker-релей outbox → FCM (см. backend/README.md)
├── scripts/                  # check_db_structure.py — инспектор схемы Realtime DB
├── gradle/ + gradlew         # Gradle Wrapper
├── google-services.json      # копия конфигурации Firebase в корне проекта
├── database-rules-test.json  # тестовая конфигурация правил БД
├── build.gradle              # AGP 8.3.2, Kotlin 1.9.24, google-services 4.4.2
└── settings.gradle           # include ':app'
```

## Схема Realtime Database
Пути зафиксированы в `app/.../util/DbPaths.kt` (проверка — `scripts/check_db_structure.py`):

| Путь | Назначение |
|---|---|
| `users/$uid` | профиль + публичный ключ X25519 (`publicKey`) |
| `user-messages/$myUid/$otherUid/$msgId` | зеркало переписки (`.indexOn: ["timestamp"]`); шифрованные сообщения несут `enc: true` и конверт `env` |
| `latest-messages/$myUid/$otherUid` | список последних диалогов |
| `transfers/$msgId` | relay-зона картинок (TTL 7 дней; тело зашифровано receiver-конвертом) |
| `user-tokens/$uid/$deviceId` | FCM-токены устройств |
| `outbox/$msgId` | очередь push-доставок для релея |

Пример правил и тестовая конфигурация: `database-rules-test.json`.

## Шифрование (E2EE)

Гибридная схема, код в `util/KeyManager.kt`, `util/CryptoBox.kt`, `util/CryptoBridge.kt`:

- **Ключи**: пара X25519 генерируется при первом логине/регистрации (`KeyManager.ensureKeys()`),
  приватный ключ обёрнут в Android Keystore (AES-GCM wrap, не покидает устройство),
  публичный публикуется в `users/$uid/publicKey`. Работает и на эмуляторе (software-Keystore).
- **Сообщение**: эфемерная пара X25519 → ECDH с pubkey собеседника → HKDF-SHA256 →
  AES-256-GCM. В БД лежит base64-конверт `{enc, epk, alg}` + флаг `enc: true`.
- **Два конверта** (из-за TTL relay и LRU-кэша картинок): receiver-конверт — в relay-ноду
  `/transfers`, sender/selfless-конверт — в поле `env` самого сообщения, чтобы отправитель
  всегда мог прочитать своё (epk = собственный pubkey, расшифровка своим приватником).
- **Совместимость**: старые plaintext-сообщения отображаются как раньше; декодер картинок
  определяет формат по сигнатуре байт (WebP/JPEG).
- **Модель доверия TOFU**: чужой ключ запоминается при первой встрече; при смене ключа —
  предупреждение. Сообщения, отправленные под старым ключом, после смены устройства не
  восстанавливаются.

Ограничения: нет Double-Ratchet/пересылочной секретности (PFS только на уровне сообщения),
метаданные (кто/кому/когда) открыты, групповых чатов шифрование не касается.

## Требования
- JDK **17**
- Android SDK (compileSdk 34, minSdk 21, targetSdk 34)
- Gradle 8.x (обёртки `./gradlew` в репозитории)
- Файл `google-services.json` в корне модуля `app` (в репозитории есть пример для
  вашего проекта Firebase; при смене проекта замените его)

## Сборка Android
```bash
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/
./gradlew installDebug           # установка на подключённое устройство
```

Конфигурация: `build.gradle` (корень), `app/build.gradle`, `gradle.properties`.

### Примечания по зависимостям
- Используется Firebase BOM (auth, database, messaging); Storage не нужен —
  картинки передаются base64 через Realtime DB.
- Groupie 2.x недоступен (jcenter мёртв, JitPack 401) — от него отказались:
  списки чатов и переписка построены на обычных `RecyclerView.Adapter`
  (`views/ChatRecyclerAdapter.kt`, `views/LatestMessageRow.kt`).
- Картинки: Picasso + CircleImageView. Отправка изображений — в формате WebP
  (lossy, q80; поддерживается Android с API 17, minSdk 21 покрывает), ~25–35% экономии
  против JPEG; при недоступности WebP-энкодера на устройстве — автоматический
  fallback на JPEG. Старые JPEG-сообщения продолжают отображаться (декод по сигнатуре).
- Crypto: BouncyCastle (`bcprov-jdk18on`) — X25519/HKDF; AES-GCM — из стандартного JCE.
- Кэш картинок: `filesDir/messages/image_cache/` (не чистится системой), LRU-лимит 300
  файлов с обновлением времени при просмотре; миграция со старого `cacheDir/chat_images`.

## Push-релей (backend)
Клиент пишет сообщение в `outbox/`, бэкенд с Firebase Admin SDK читает outbox,
отправляет push получателю и помечает доставку. Подробные инструкции по запуску
и деплою (Fly.io / Render): **[`backend/README.md`](backend/README.md)**.

Кратко:
```bash
cd backend
pip install -r requirements.txt
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/serviceAccountKey.json
export FIREBASE_DATABASE_URL=https://<project>-default-rtdb.firebaseio.com
python -m app.worker            # мгновенный worker-режим
# или: uvicorn app.main:app --host 0.0.0.0 --port 8080   # HTTP + внешний cron
```

**Никогда не коммитьте service account key в git.**

## Скрипты
```bash
python3 scripts/check_db_structure.py   # сверка реальной структуры БД с DbPaths.kt
```

## Известные проблемы / history
- Проект мигрирован на AGP 8.x / Gradle 8.x / Kotlin 1.9 (исторический диф —
  `backend-import.patch`).
- Репозиторий очищен: удалены логи JVM-крашей `hs_err_pid*.log`, дублирующие
  PNG-файлы, кэш `__pycache__/` и прочие временные артефакты.
- Сообщения и картинки, отправленные ДО E2EE-фиксов с двумя конвертами (до коммитов
  d60a293/9ad2cef), отправителю не восстанавливаются — ключ к ним потерян по построению;
  у получателя они работают как раньше.
- Push при шифровании показывает только «Новое сообщение» без текста (в FCM payload
  текст не кладётся) — сознательная компромиссная реализация.

## Куда двигаться дальше (roadmap)

**Функциональность:**
1. Верификация ключей вслух (QR/safety-number как в Signal) — закрывает главный минус TOFU.
2. Double Ratchet (пересылочная секретность + восстановление после перехвата).
3. Статусы прочтения / «доставлено», редактирование и удаление сообщений (soft-delete с tombstone).
4. Голосовые сообщения (Opus → шифрованный конверт в relay, тот же путь, что у картинок).
5. Групповые чаты — понадобится групповой протокол (Sender Keys); текущая 1:1-схема не масштабируется.
6. Мультидевайс: перенос ключей между устройствами (зашифрованный backup через recovery-кодовую фразу).

**Надёжность/безопасность:**
7. Ограничение размера тела в правилах RTDB (`.validate`) — сейчас защита только на клиенте.
8. Rate-limit на запись outbox/transfers (серверный, в релея), чтобы не забивать БД.
9. Мониторинг relay-TTL: если релей ещё не подтянул картинку из `/transfers`, а TTL истёк —
   получатель увидит заглушку до следующего открытия чата (env спасает, но стоит алерт).

**UX/дизайн:**
10. Material 3 (Dynamic Color, ночная тема) — сейчас старая AppCompat-тема 2018 года.
11. Единый bubble-стиль WhatsApp-типа (скругления, хвосты, фон входящих/исходящих) + анимация
    появления полноэкранного просмотра (hero-transition от миниатюры).
12. Индикатор загрузки в превью списка чатов (сейчас серый плейсхолдер без спиннера).
13. Жесты: свайп-ответ, long-press-меню (копировать/удалить/переслать).
14. Доступность: contentDescription у картинок, scale-independent размеры текста.

## Лицензия
Учебный/личный проект, лицензия не задана.
