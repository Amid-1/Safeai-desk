# SafeAI Desk — Audit verification

Документ описывает проверку audit events.

## 1. Что проверяем

```text
USER_LOGIN_SUCCESS
USER_LOGIN_FAILED
CHAT_CREATED
CHAT_MESSAGE_SENT
AI_RESPONSE_RECEIVED
```

## 2. API

```text
GET /api/admin/audit-events
GET /api/admin/audit-events/users/{userId}
```

## 3. Login success

```bat
curl -i -X POST http://localhost:8080/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"admin@test.com\",\"password\":\"admin123\"}"
```

Ожидаемо:

```text
USER_LOGIN_SUCCESS
```

## 4. Login failed

```bat
curl -i -X POST http://localhost:8080/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"admin@test.com\",\"password\":\"wrong-password\"}"
```

Ожидаемо:

```text
USER_LOGIN_FAILED
```

## 5. Создать чат

```bat
curl -i -X POST http://localhost:8080/api/chats ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"title\":\"Audit test chat\"}"
```

Ожидаемо:

```text
CHAT_CREATED
```

## 6. Отправить сообщение

```bat
curl -i -X POST http://localhost:8080/api/chats/%CHAT_ID%/messages ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"content\":\"Проверь аудит сообщений\"}"
```

Ожидаемо:

```text
CHAT_MESSAGE_SENT
AI_RESPONSE_RECEIVED
```

## 7. Проверить Audit API

```bat
curl -i http://localhost:8080/api/admin/audit-events ^
  -H "Authorization: Bearer %TOKEN%"
```

Ожидаемо:

```text
HTTP/1.1 200
```

В ответе должны быть события:

```text
AI_RESPONSE_RECEIVED
CHAT_MESSAGE_SENT
CHAT_CREATED
USER_LOGIN_FAILED
USER_LOGIN_SUCCESS
```

## 8. Проверить PostgreSQL

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select event_type, user_id, details, created_at from audit_events order by created_at desc limit 10;"
```

Ожидаемо:

```text
AI_RESPONSE_RECEIVED | details: model, chatId, messageId, inputTokens, outputTokens, costUsd
CHAT_MESSAGE_SENT    | details: chatId, messageId, messageLength
CHAT_CREATED         | details: title, chatId
USER_LOGIN_FAILED    | details: email
USER_LOGIN_SUCCESS   | details: email, organizationId
```

## 9. Защита audit API

Без токена:

```bat
curl -i http://localhost:8080/api/admin/audit-events
```

Ожидаемо:

```text
HTTP/1.1 401
```

Под USER:

```bat
curl -i http://localhost:8080/api/admin/audit-events ^
  -H "Authorization: Bearer %USER_TOKEN%"
```

Ожидаемо:

```text
HTTP/1.1 403
```

## 10. Успешный результат

```text
✅ login success пишет USER_LOGIN_SUCCESS
✅ wrong password пишет USER_LOGIN_FAILED
✅ создание чата пишет CHAT_CREATED
✅ сообщение пишет CHAT_MESSAGE_SENT
✅ AI ответ пишет AI_RESPONSE_RECEIVED
✅ /api/admin/audit-events работает
✅ audit_events хранит details как jsonb
```
