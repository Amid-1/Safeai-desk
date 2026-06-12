# SafeAI Desk — Chat Core verification

Документ описывает проверку чатов и сообщений.

## 1. Что проверяем

```text
POST /api/chats
GET  /api/chats
GET  /api/chats/{id}
POST /api/chats/{id}/messages
```

## 2. Схема

```text
JWT → SafeAiUserPrincipal → ChatController → ChatService → PostgreSQL
```

Отправка сообщения:

```text
save USER message → AiProvider → MockAiProvider → save ASSISTANT message
```

## 3. Получить TOKEN

```bat
curl -i -X POST http://localhost:8080/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"admin@test.com\",\"password\":\"admin123\"}"
```

```bat
set "TOKEN=PASTE_TOKEN_HERE"
```

## 4. Создать чат

```bat
curl -i -X POST http://localhost:8080/api/chats ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"title\":\"Audit test chat\"}"
```

Ожидаемо:

```text
HTTP/1.1 200
```

Сохранить `id`:

```bat
set "CHAT_ID=PASTE_CHAT_ID_HERE"
```

Проверить:

```bat
echo %CHAT_ID%
```

## 5. Список чатов

```bat
curl -i http://localhost:8080/api/chats ^
  -H "Authorization: Bearer %TOKEN%"
```

Ожидаемо:

```text
HTTP/1.1 200
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
HTTP/1.1 200
```

У `ASSISTANT` должно быть:

```json
{
  "role": "ASSISTANT",
  "content": "Mock AI provider response: Проверь аудит сообщений",
  "model": "mock-safeai",
  "inputTokens": 5,
  "outputTokens": 12,
  "costUsd": 0
}
```

## 7. Получить чат с историей

```bat
curl -i http://localhost:8080/api/chats/%CHAT_ID% ^
  -H "Authorization: Bearer %TOKEN%"
```

Ожидаемо:

```text
messages[0].role = USER
messages[1].role = ASSISTANT
messages[1].model = mock-safeai
```

## 8. Проверить chat_sessions

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select id, user_id, title, created_at from chat_sessions order by created_at desc limit 10;"
```

## 9. Проверить chat_messages

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select session_id, role, content, model, input_tokens, output_tokens, cost_usd, created_at from chat_messages order by created_at desc limit 10;"
```

## 10. Пустое сообщение

```bat
curl -i -X POST http://localhost:8080/api/chats/%CHAT_ID%/messages ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"content\":\"\"}"
```

Ожидаемо:

```text
HTTP/1.1 400
```

## 11. Несуществующий чат

```bat
curl -i http://localhost:8080/api/chats/11111111-2222-3333-4444-555555555555 ^
  -H "Authorization: Bearer %TOKEN%"
```

Ожидаемо:

```text
HTTP/1.1 404
```

## 12. Частые ошибки

Если получил HTML 400 от Tomcat, проверь:

```bat
echo %CHAT_ID%
```

Если вывод `%CHAT_ID%`, переменная не установлена.

## 13. Успешный результат

```text
✅ чат создается
✅ список чатов работает
✅ сообщение USER сохраняется
✅ ответ ASSISTANT сохраняется
✅ AiProvider вызывается
✅ model/tokens/cost заполняются
✅ история возвращается
✅ PostgreSQL содержит chat_sessions и chat_messages
```
