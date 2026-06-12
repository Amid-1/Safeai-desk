# SafeAI Desk — troubleshooting

## 1. Port 8080 already in use

Причина: backend уже запущен локально или в Docker.

Проверить:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose ps
```

Остановить backend-контейнер:

```bat
docker compose stop backend
```

## 2. SAFEAI_JWT_SECRET не задан

Ошибка:

```text
Could not resolve placeholder SAFEAI_JWT_SECRET
```

Решение:

```bat
set SAFEAI_JWT_SECRET=safeai-local-development-secret-key-change-this-value-please-123456789
set SAFEAI_JWT_EXPIRATION_MINUTES=60
mvnw.cmd spring-boot:run
```

## 3. PostgreSQL connection refused

Ошибка:

```text
Connection to localhost:5432 refused
```

Решение:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose up -d postgres redis
docker compose ps
```

## 4. Backend в Docker не видит PostgreSQL

В Docker нельзя:

```text
jdbc:postgresql://localhost:5432/safeai
```

Нужно:

```text
jdbc:postgresql://postgres:5432/safeai
```

## 5. TOKEN не подставляется

Проверить:

```bat
echo %TOKEN%
```

Если вывод:

```text
%TOKEN%
```

значит переменная не установлена.

## 6. CHAT_ID не подставляется

```bat
echo %CHAT_ID%
```

Если не установлен, выполни:

```bat
set "CHAT_ID=PASTE_CHAT_ID_HERE"
```

## 7. HTML 400 от Tomcat

Частая причина: в URL ушел `%CHAT_ID%` или другой `%...%`, потому что переменная не установлена.

Проверить:

```bat
echo %CHAT_ID%
```

## 8. Token malformed

Нельзя передавать:

```text
Authorization: Bearer "token":"eyJ..."
```

Нужно:

```text
Authorization: Bearer eyJ...
```

## 9. Flyway checksum error

Причина: изменили уже примененную миграцию.

Правильно:

```text
создать новую V4__...sql
```

Для локального сброса:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose down -v
docker compose up -d postgres redis
```

## 10. Map.of падает в audit details

`Map.of(...)` не принимает `null`.

Используй:

```java
Map<String, Object> details = new HashMap<>();
details.put("inputTokens", aiResponse.inputTokens());
```

## 11. docker exec -it странно работает в Windows

Используй без `-it`:

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select now();"
```

## 12. Быстрая диагностика

```bat
docker ps
```

```bat
curl -i http://localhost:8080/actuator/health
```

```bat
echo %TOKEN%
```

```bat
echo %CHAT_ID%
```

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select event_type, details, created_at from audit_events order by created_at desc limit 10;"
```
