# Настройка онлайн-обновления базы

## Шаг 1: Создай GitHub репозиторий

1. Перейди на https://github.com/new
2. Название: `RadioNameHook-DB` (или любое)
3. Публичный (Public) — приватный не будет работать без токенов
4. Инициализируй с README

## Шаг 2: Загрузи базу

В репозиторий нужны 2 файла в корне:

**`radio.db`** — SQLite база (та что в `app/src/main/assets/radio.db`)

**`radio.db.version`** — текстовый файл с одной строкой — номером версии, например:
```
1
```

При каждом обновлении базы:
- Меняешь `radio.db`
- **Увеличиваешь число** в `radio.db.version` (2, 3, 4...)

## Шаг 3: Обнови URL в коде

В `MainActivity.java` найди строки:

```java
private static final String REMOTE_DB_URL =
    "https://raw.githubusercontent.com/YOUR_USER/YOUR_REPO/main/radio.db";
private static final String REMOTE_VERSION_URL =
    "https://raw.githubusercontent.com/YOUR_USER/YOUR_REPO/main/radio.db.version";
```

Замени `YOUR_USER/YOUR_REPO` на своё, например:
```java
"https://raw.githubusercontent.com/ivan/RadioNameHook-DB/main/radio.db"
```

## Как это работает

- При запуске приложения — тихая проверка обновлений (не чаще раза в 24 часа)
- Кнопка **"🌐 Обновить онлайн"** — принудительная проверка с диалогом
- Приложение сравнивает локальную версию с `radio.db.version`
- Если версии разные — скачивает новый `radio.db`
- Проверяет что файл — валидный SQLite (по header)
- Сохраняет все пользовательские имена и кастомные города
- Применяет обновление

## Формат radio.db

Схема должна совпадать со встроенной базой:

```sql
CREATE TABLE cities (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    region TEXT,
    latitude REAL DEFAULT 0,
    longitude REAL DEFAULT 0
);
CREATE TABLE stations (
    id INTEGER PRIMARY KEY,
    city_id INTEGER NOT NULL,
    freq TEXT NOT NULL,
    band TEXT NOT NULL DEFAULT 'FM',
    name TEXT NOT NULL,
    user_name TEXT DEFAULT NULL
);
CREATE TABLE settings (
    key TEXT PRIMARY KEY,
    value TEXT
);
```
