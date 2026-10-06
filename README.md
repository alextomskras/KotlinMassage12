# KotlinMassage

Мессенджер-приложение для Android на **Kotlin** с использованием **Firebase** (Authentication, Realtime Database, Cloud Messaging).

Версия: 2.0 · applicationId: `com.example.fess.kotlinmassage1`

## Возможности

- Регистрация и вход через Firebase Authentication
- Список последних сообщений (Latest Messages)
- Создание новых чатов и выбор получателя
- Лог переписки в реальном времени (Chat Log)
- Отправка изображений (base64 через Realtime Database)
- Push-уведомления через Firebase Cloud Messaging
- Обновление статуса сообщений на экране

## Технологии

| Компонент | Версия |
|---|---|
| Kotlin | 1.9.24 |
| Android Gradle Plugin | 8.3.2 |
| Gradle | 8.x |
| compileSdk / targetSdk | 34 |
| minSdk | 21 |
| Java | 17 |

**Библиотеки:** Firebase BOM 32.8.1 (auth, database, messaging), AndroidX (core-ktx, appcompat, constraintlayout, recyclerview, material, cardview, multidex), CircleImageView 3.1.0, Picasso 2.8, ViewBinding.

> Примечание: Groupie 2.x недоступен из-за закрытия jcenter — используется локальный шим `com.xwray.groupie` в `app/src/main/java/com/xwray/groupie/`.

## Структура проекта

```
app/src/main/java/com/example/fess/kotlinmassage1/
├── registerlogin/   # LoginActivity, RegisterActivity — экраны входа и регистрации
├── messages/        # LatestMessagesActivity, NewMessageActivity, ChatLogActivity
├── models/          # ChatMessage, User — модели данных
├── views/           # Элементы интерфейса чатов (ChatItems, LatestMessageRow)
├── service/         # MyFirebaseMessagingService — обработка push-уведомлений
└── util/            # TokenStore, NotificationHelper, ImageUtils, ImageLoader, DbPaths
```

## Требования

- JDK 17
- Android Studio (Giraffe или новее) с Android SDK 34
- Актуальный `google-services.json` в корне проекта и в `app/`

## Сборка и запуск

```bash
# Клонировать репозиторий
git clone <url-репозитория>
cd KotlinMassage

# Сборка debug-варианта
./gradlew assembleDebug

# Установка на подключённое устройство
./gradlew installDebug
```

Или откройте проект в Android Studio и нажмите **Run**.

## Конфигурация Firebase

1. Создайте проект в [Firebase Console](https://console.firebase.google.com/).
2. Добавьте Android-приложение с applicationId `com.example.fess.kotlinmassage1`.
3. Скачайте `google-services.json` и поместите его в директорию `app/`.
4. Включите **Authentication** (Email/Password) и **Realtime Database**.
5. Для push-уведомлений включите **Cloud Messaging**.
6. Правила БД приведены в `app/database-rules.json`.

## Тесты

```bash
./gradlew test            # unit-тесты
./gradlew connectedAndroidTest  # instrumentation-тесты (нужно устройство/эмулятор)
```

## Лицензия

Информация о лицензии отсутствует.
