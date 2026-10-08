# KotlinMassage1

Мессенджер на Android (Kotlin) с Firebase Authentication, Realtime Database и FCM
push-уведомлениями, плюс Python-бэкенд — релей доставок (`backend/`).

## Возможности
- Регистрация / вход через Firebase Auth (`registerlogin/`)
- Список последних сообщений, новый чат, экран переписки (`messages/`)
- Текстовые сообщения и картинки (base64 в Realtime DB, без Firebase Storage)
- Push-уведомления через FCM (`service/MyFirebaseMessagingService.kt`)
- Жёсткие правила БД + серверный обход через Admin SDK (outbox-релей)

## Структура проекта
```
├── app/                      # Android-приложение (Kotlin)
│   └── src/main/java/com/example/fess/kotlinmassage1/
│       ├── messages/         # ChatLogActivity, LatestMessagesActivity, NewMessageActivity
│       ├── models/           # ChatMessage, User
│       ├── registerlogin/    # LoginActivity, RegisterActivity
│       ├── service/          # MyFirebaseMessagingService
│       ├── util/             # DbPaths, ImageLoader/Cache/Utils, NotificationHelper, TokenStore
│       └── views/            # ChatItems, ChatRecyclerAdapter, LatestMessageRow
├── backend/                  # FastAPI/worker-релей outbox → FCM (см. backend/README.md)
├── scripts/                  # check_db_structure.py — инспектор схемы Realtime DB
├── build.gradle              # AGP 8.3.2, Kotlin 1.9.24, google-services 4.4.2
└── settings.gradle           # include ':app'
```

## Схема Realtime Database
Пути зафиксированы в `app/.../util/DbPaths.kt` (проверка — `scripts/check_db_structure.py`):

| Путь | Назначение |
|---|---|
| `users/$uid` | профиль (username и т.д.) |
| `user-messages/$myUid/$otherUid/$msgId` | зеркало переписки (ожидается `.indexOn: ["timestamp"]`) |
| `latest-messages/$myUid/$otherUid` | список последних диалогов |
| `user-tokens/$uid/$deviceId` | FCM-токены устройств |
| `outbox/$msgId` | очередь push-доставок для релея |

Пример правил и тестовая конфигурация: `database-rules-test.json`.

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
- Используется Firebase BOM 32.8.1 (auth, database, messaging).
- Groupie недоступен из мёртвых репозиториев — вместо него локальный шим
  `com.xwray.groupie` (см. комментарий в `app/build.gradle`: jar с настоящим
  Groupie из `app/libs/` удалён, иначе IllegalAccessError в рантайме).
- Картинки: Picasso + CircleImageView.

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
- Проект мигрирован на AGP 8.x / Gradle 8.x / Kotlin 1.9 (см. `backend-import.patch`
  для импорта бэкенда).
- В корне лежат логи JVM-крашей `hs_err_pid*.log` и архив старой версии
  `KotlinMassage1-date-from-timestamp.zip` — не являются частью сборки.

## Лицензия
Учебный/личный проект, лицензия не задана.
