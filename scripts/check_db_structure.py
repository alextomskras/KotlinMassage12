#!/usr/bin/env python3
"""Инспектор структуры Realtime Database KotlinMassage.

Сверяет фактическое содержимое БД с путями из app/.../util/DbPaths.kt:
  users/$uid
  user-messages/$myUid/$otherUid/$msgId   (ожидается .indexOn: ["timestamp"])
  latest-messages/$myUid/$otherUid
  user-tokens/$uid/$deviceId
  outbox/$msgId

Admin SDK обходит клиентские правила, поэтому видно реальную структуру,
даже когда REST без токена отвечает Permission denied.

Запуск:
  pip install firebase-admin
  export GOOGLE_APPLICATION_CREDENTIALS=/path/to/serviceAccountKey.json
  python3 scripts/check_db_structure.py

Ключ сервиса: Firebase Console -> Project settings -> Service accounts
-> Generate new private key (файл не коммитить!).
"""
import json
import os
import sys

DB_URL = os.environ.get(
    "FIREBASE_DATABASE_URL", "https://kotlinmessageres.firebaseio.com"
)

# Ожидаемая схема = то, что читает/пишет текущий код приложения
EXPECTED_SCHEMA = {
    "users": {
        "path_depth": 1,               # users/$uid
        "required_fields": ["username"],
    },
    "user-messages": {
        "path_depth": 3,               # user-messages/$myUid/$otherUid/$msgId
        "required_fields": ["id", "text", "fromId", "toId", "timestamp", "type"],
    },
    "latest-messages": {
        "path_depth": 2,               # latest-messages/$myUid/$otherUid
        "required_fields": ["id", "text", "fromId", "toId", "timestamp", "type"],
    },
    "user-tokens": {
        "path_depth": 2,               # user-tokens/$uid/$deviceId
        "required_fields": None,
    },
    "outbox": {
        "path_depth": 1,               # outbox/$msgId
        "required_fields": ["msgId", "fromId", "toId", "type", "preview", "timestamp"],
    },
}


def connect():
    try:
        import firebase_admin
        from firebase_admin import credentials, db
    except ImportError:
        sys.exit("Установите зависимости: pip install firebase-admin")

    cred_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS")
    if not cred_path or not os.path.exists(cred_path):
        sys.exit(
            "Нужен service account key.\n"
            "  export GOOGLE_APPLICATION_CREDENTIALS=/path/to/serviceAccountKey.json\n"
            "Firebase Console -> Project settings -> Service accounts -> "
            "Generate new private key"
        )
    if not firebase_admin._apps:
        firebase_admin.initialize_app(
            credentials.Certificate(cred_path), {"databaseURL": DB_URL}
        )
    return db


def shallow_keys(db, path):
    snap = db.reference(path).get(shallow=True)
    if isinstance(snap, dict):
        return list(snap.keys())
    return []


def sample_record(db, node, depth):
    """Спускается на `depth` уровней от ноды и возвращает (путь, запись)."""
    path = node
    for _ in range(depth):
        keys = shallow_keys(db, path)
        if not keys:
            return path, None
        path = f"{path}/{keys[0]}"
    return path, db.reference(path).get()


def check(db):
    root = db.reference()
    problems = []

    top = shallow_keys(db, "/")
    print(f"Топовые ноды в БД: {top}\n")

    for node, spec in EXPECTED_SCHEMA.items():
        print(f"--- {node} ---")
        if node not in top:
            problems.append(
                f"В базе нет узла '{node}' — создастся при первом сообщении "
                f"либо структура устарела"
            )
            print("  отсутствует\n")
            continue
        keys = shallow_keys(db, node)
        print(f"  записей верхнего уровня: {len(keys)}, примеры: {keys[:5]}")
        path, rec = sample_record(db, node, spec["path_depth"])
        if rec is None:
            print(f"  не удалось взять пример по пути {path}\n")
            continue
        print(f"  пример {path}: {json.dumps(rec, ensure_ascii=False)[:400]}")
        if spec["required_fields"] and isinstance(rec, dict):
            missing = [f for f in spec["required_fields"] if f not in rec]
            extra = [f for f in rec if f not in spec["required_fields"]]
            if missing:
                problems.append(
                    f"{node}: в записи нет полей {missing} — старая структура, "
                    f"нужна миграция"
                )
            if extra:
                print(f"  доп. поля (не критично): {extra}")
        print()

    rules = root.get_rules().get("rules", {})
    print(f"Узлы в правилах: {sorted(rules.keys())}")
    for node in EXPECTED_SCHEMA:
        if node not in rules:
            problems.append(
                f"'{node}' отсутствует в правилах — приложение получит "
                f"Permission denied"
            )
    idx = (
        rules.get("user-messages", {})
        .get("$myUid", {})
        .get("$otherUid", {})
        .get(".indexOn")
    )
    if idx != ["timestamp"]:
        problems.append(f"user-messages: .indexOn должен быть ['timestamp'], сейчас {idx}")

    print("\n=== ИТОГ ===")
    if problems:
        for p in problems:
            print(f"  ✗ {p}")
        return 1
    print("  ✓ Структура БД и правила соответствуют DbPaths.kt / ChatLogActivity.kt")
    return 0


if __name__ == "__main__":
    sys.exit(check(connect()))
