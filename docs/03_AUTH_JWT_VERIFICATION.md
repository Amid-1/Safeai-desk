# SafeAI Desk — Auth/JWT verification

Документ описывает проверку JWT-аутентификации.

## 1. Что проверяем

```text
POST /api/auth/login
GET  /api/auth/me
401 без токена
401 wrong password
401 wrong token
403 для USER на admin endpoint
```

## 2. Login

```bat
curl -i -X POST http://localhost:8080/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"admin@test.com\",\"password\":\"admin123\"}"
```

Ожидаемо:

```text
HTTP/1.1 200
```

Ответ содержит:

```json
{
  "token": "eyJ...",
  "tokenType": "Bearer",
  "user": {
    "email": "admin@test.com",
    "roles": ["ADMIN"]
  }
}
```

## 3. Сохранить TOKEN

Копировать только значение поля `token`.

```bat
set "TOKEN=PASTE_TOKEN_HERE"
```

Проверить:

```bat
echo %TOKEN%
```

JWT должен иметь 3 части через точки:

```text
header.payload.signature
```

## 4. Проверить /api/auth/me

```bat
curl -i http://localhost:8080/api/auth/me ^
  -H "Authorization: Bearer %TOKEN%"
```

Ожидаемо:

```text
HTTP/1.1 200
```

## 5. Protected endpoint без токена

```bat
curl -i http://localhost:8080/api/users
```

Ожидаемо:

```text
HTTP/1.1 401
```

## 6. Wrong password

```bat
curl -i -X POST http://localhost:8080/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"admin@test.com\",\"password\":\"wrong-password\"}"
```

Ожидаемо:

```text
HTTP/1.1 401
```

В audit должно появиться:

```text
USER_LOGIN_FAILED
```

## 7. Wrong token

```bat
curl -i http://localhost:8080/api/users ^
  -H "Authorization: Bearer wrong-token"
```

Ожидаемо:

```text
HTTP/1.1 401
```

## 8. ADMIN на /api/users

```bat
curl -i http://localhost:8080/api/users ^
  -H "Authorization: Bearer %TOKEN%"
```

Ожидаемо:

```text
HTTP/1.1 200
```

## 9. USER на /api/users

Login под обычным пользователем и сохранить:

```bat
set "USER_TOKEN=PASTE_USER_TOKEN_HERE"
```

Проверить:

```bat
curl -i http://localhost:8080/api/users ^
  -H "Authorization: Bearer %USER_TOKEN%"
```

Ожидаемо:

```text
HTTP/1.1 403
```

## 10. Успешный результат

```text
✅ login возвращает token
✅ /api/auth/me работает
✅ без token приходит 401
✅ wrong password приходит 401
✅ wrong token приходит 401
✅ USER на admin endpoint получает 403
✅ ADMIN получает 200
✅ audit пишет USER_LOGIN_SUCCESS и USER_LOGIN_FAILED
```
