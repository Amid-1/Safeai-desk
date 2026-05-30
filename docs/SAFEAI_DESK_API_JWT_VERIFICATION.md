# SafeAI Desk — инструкция по проверке API и JWT

Документ описывает, как вручную проверить текущий backend SafeAI Desk через `curl` в Windows CMD после включения JWT-авторизации.

## Текущий статус backend

На текущем этапе реализованы и проверяются:

- `Organization API`;
- `User API`;
- роли `ADMIN` и `USER` через Flyway-миграцию;
- хранение пароля как BCrypt hash;
- единый JSON-формат ошибок через `GlobalExceptionHandler`;
- `Auth/Login/JWT`;
- защита API через `Authorization: Bearer <token>`;
- проверка текущего пользователя через `/api/auth/me`;
- закрытие `/api/users`, `/api/organizations` и других API без токена;
- проверки статусов `200`, `400`, `401`, `404`, `409`.

---

# 1. Перед проверкой

## 1.1. Должны быть запущены Docker-контейнеры

Перейти в папку инфраструктуры:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
```

Запустить PostgreSQL и Redis:

```bat
docker compose up -d
```

Проверить контейнеры:

```bat
docker compose ps
```

Ожидаемый результат:

```text
NAME              IMAGE         SERVICE    STATUS
safeai-postgres   postgres:16   postgres   Up
safeai-redis      redis:7       redis      Up
```

Смысл проверки:

- `safeai-postgres` нужен backend-у для хранения организаций, пользователей, ролей, чатов и audit events.
- `safeai-redis` пока поднят как часть инфраструктуры на будущее: сессии, кеш, rate limit, очереди.
- Если PostgreSQL не запущен, backend не сможет проверить пользователя, роли и пароль.

---

## 1.2. Должен быть запущен backend

В отдельном терминале перейти в backend:

```bat
cd /d "D:\Java projects\Safeai-desk\backend"
```

Запустить Spring Boot:

```bat
mvnw.cmd spring-boot:run
```

Ожидаемый результат в логах:

```text
Tomcat started on port 8080
Started SafeaiBackendApplication
```

Также в логах должно быть что-то похожее на:

```text
Successfully validated ... migrations
Found ... JPA repository interfaces
```

Смысл проверки:

- backend стартует;
- Spring подключается к PostgreSQL;
- Flyway проверяет или применяет миграции;
- Hibernate проверяет соответствие Entity-классов таблицам;
- Spring Data видит Repository;
- Spring Security поднимает JWT-защиту.

---

# 2. Важное изменение после включения JWT

Раньше, до JWT, можно было напрямую делать:

```bat
curl http://localhost:8080/api/users
curl http://localhost:8080/api/organizations
```

и получать данные.

После включения JWT это изменилось.

Теперь без токена защищенные endpoints должны возвращать:

```text
HTTP/1.1 401
```

Это правильно.

Открытыми должны быть только:

```text
POST /api/auth/login
GET  /actuator/health
```

Остальные API требуют заголовок:

```http
Authorization: Bearer <token>
```

---

# 3. Проверка закрытого endpoint без JWT

Команда:

```bat
curl -i http://localhost:8080/api/users
```

Что делает:

- отправляет запрос на защищенный endpoint `/api/users`;
- не передает JWT-токен;
- backend должен отказать в доступе.

Ожидаемый результат:

```text
HTTP/1.1 401
```

Пример возможного ответа:

```text
HTTP/1.1 401
WWW-Authenticate: Bearer resource_metadata="http://localhost:8080/.well-known/oauth-protected-resource"
Content-Length: 0
```

Смысл проверки:

- `SecurityConfig` работает;
- правило `.anyRequest().authenticated()` реально закрывает API;
- без токена пользователь не может получить список пользователей.

---

# 4. Login с правильным паролем

Команда:

```bat
curl -i -X POST http://localhost:8080/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"admin@test.com\",\"password\":\"admin123\"}"
```

Что делает:

- отправляет email и password на `/api/auth/login`;
- `AuthController` принимает `LoginRequest`;
- `AuthService` вызывает `AuthenticationManager`;
- `CustomUserDetailsService` ищет пользователя в таблице `users`;
- `DaoAuthenticationProvider` проверяет пароль через BCrypt;
- `JwtService` создает JWT;
- backend возвращает `LoginResponse`.

Ожидаемый результат:

```text
HTTP/1.1 200
```

Пример ответа:

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "user": {
    "id": "9cd7ff22-49b2-4468-af17-2cf8889665e3",
    "organizationId": "af1e6969-9f96-4ec9-bff2-34c4fe55832a",
    "email": "admin@test.com",
    "enabled": true,
    "roles": ["ADMIN"]
  }
}
```

Смысл проверки:

- пользователь существует;
- пароль `admin123` совпал с BCrypt-хэшем в базе;
- роль `ADMIN` привязана к пользователю;
- JWT успешно генерируется;
- auth-модуль работает.

---

# 5. Как правильно копировать токен

Из ответа login нужно скопировать **только значение поля `token`**.

Правильно копировать вот так:

```text
eyJhbGciOiJIUzI1NiJ9.eyJ...signature
```

Неправильно:

```text
"token":"eyJhbGciOiJIUzI1NiJ9..."
```

Неправильно:

```text
Authorization: Bearer "token":"eyJhbGciOiJIUzI1NiJ9..."
```

JWT должен иметь формат:

```text
header.payload.signature
```

То есть три части через точки.

---

# 6. Сохранить токен в переменную CMD

После login скопировать токен и выполнить:

```bat
set "TOKEN=PASTE_TOKEN_HERE"
```

Пример:

```bat
set "TOKEN=eyJhbGciOiJIUzI1NiJ9.eyJvcmdhbml6YXRpb25JZCI6ImFmMWU2OTY5LTlmOTYtNGVjOS1iZmYyLTM0YzRmZTU1ODMyYSIsInN1YiI6ImFkbWluQHRlc3QuY29tIiwic2NvcGUiOiJST0xFX0FETUlOIiwiaXNzIjoic2FmZWFpLWRlc2siLCJleHAiOjE3ODAxNzY3NzYsImlhdCI6MTc4MDE3MzE3NiwidXNlcklkIjoiOWNkN2ZmMjItNDliMi00NDY4LWFmMTctMmNmODg4OTY2NWUzIn0.1pC2ixpyyz7rnnbc2WF4gAjkqe0n9TdhNBpPBB4buO0"
```

Проверить, что переменная сохранилась:

```bat
echo %TOKEN%
```

Ожидаемый результат:

```text
eyJhbGciOiJIUzI1NiJ9...
```

Если вывелось:

```text
%TOKEN%
```

или пустая строка, значит переменная не сохранилась.

---

# 7. Проверка `/api/auth/me` с JWT

Команда:

```bat
curl -i http://localhost:8080/api/auth/me ^
  -H "Authorization: Bearer %TOKEN%"
```

Что делает:

- отправляет JWT в заголовке `Authorization`;
- Spring Security проверяет подпись токена;
- backend достает текущего пользователя;
- возвращает `AuthUserResponse`.

Ожидаемый результат:

```text
HTTP/1.1 200
```

Пример ответа:

```json
{
  "id": "9cd7ff22-49b2-4468-af17-2cf8889665e3",
  "organizationId": "af1e6969-9f96-4ec9-bff2-34c4fe55832a",
  "email": "admin@test.com",
  "enabled": true,
  "roles": ["ADMIN"]
}
```

Смысл проверки:

- JWT валидный;
- backend может определить текущего пользователя;
- `/api/auth/me` работает;
- роли из токена корректно возвращаются в виде `ADMIN`.

---

# 8. Проверка `/api/users` с JWT

Команда:

```bat
curl -i http://localhost:8080/api/users ^
  -H "Authorization: Bearer %TOKEN%"
```

Что делает:

- отправляет запрос на защищенный endpoint;
- передает валидный JWT;
- backend разрешает доступ и возвращает список пользователей.

Ожидаемый результат:

```text
HTTP/1.1 200
```

Пример ответа:

```json
[
  {
    "id": "9cd7ff22-49b2-4468-af17-2cf8889665e3",
    "organizationId": "af1e6969-9f96-4ec9-bff2-34c4fe55832a",
    "email": "admin@test.com",
    "fullName": "Admin User",
    "enabled": true,
    "roles": ["ADMIN"],
    "createdAt": "2026-05-30T20:58:51.926344"
  }
]
```

Смысл проверки:

- защищенный endpoint работает с JWT;
- `UserController`, `UserService`, `UserRepository` продолжают работать после включения security;
- пароль и `passwordHash` наружу не возвращаются.

---

# 9. Проверка login с неправильным паролем

Команда:

```bat
curl -i -X POST http://localhost:8080/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"admin@test.com\",\"password\":\"wrong-password\"}"
```

Что делает:

- отправляет существующий email;
- передает неправильный пароль;
- backend должен отклонить login.

Ожидаемый результат:

```text
HTTP/1.1 401
```

Пример ответа:

```json
{
  "timestamp": "2026-05-30T23:37:31.542847",
  "status": 401,
  "error": "UNAUTHORIZED",
  "message": "Неверный email или пароль",
  "path": "/api/auth/login",
  "fieldErrors": null
}
```

Смысл проверки:

- `PasswordEncoder.matches(...)` работает;
- неправильный пароль не пропускается;
- `AuthenticationException` превращается в `401`, а не в `500`;
- клиент получает понятную ошибку.

---

# 10. Проверка битого токена

Команда:

```bat
curl -i http://localhost:8080/api/users ^
  -H "Authorization: Bearer wrong-token"
```

Что делает:

- передает в `Authorization` строку `wrong-token`;
- это не JWT формата `header.payload.signature`;
- Spring Security должен отклонить запрос.

Ожидаемый результат:

```text
HTTP/1.1 401
```

Пример ответа:

```text
WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: Malformed token"
```

Смысл проверки:

- невалидный токен не проходит;
- JWT validation работает;
- защищенные endpoints нельзя открыть случайной строкой вместо токена.

---

# 11. Проверка Organization API без токена

Команда:

```bat
curl -i http://localhost:8080/api/organizations
```

Ожидаемый результат:

```text
HTTP/1.1 401
```

Смысл проверки:

- `Organization API` тоже защищен;
- без JWT нельзя смотреть организации.

---

# 12. Проверка Organization API с токеном

Команда:

```bat
curl -i http://localhost:8080/api/organizations ^
  -H "Authorization: Bearer %TOKEN%"
```

Ожидаемый результат:

```text
HTTP/1.1 200
```

Пример ответа:

```json
[
  {
    "id": "af1e6969-9f96-4ec9-bff2-34c4fe55832a",
    "name": "Test Company",
    "createdAt": "2026-05-30T20:55:26.483945"
  }
]
```

Смысл проверки:

- endpoint закрыт без токена, но работает с токеном;
- JWT-защита не ломает бизнес-логику `OrganizationService`.

---

# 13. Проверка ошибки 404 для организации с токеном

Команда:

```bat
curl -i http://localhost:8080/api/organizations/11111111-2222-3333-4444-555555555555 ^
  -H "Authorization: Bearer %TOKEN%"
```

Что делает:

- пытается получить организацию по UUID;
- такого UUID в базе нет;
- backend должен вернуть `404`.

Ожидаемый результат:

```text
HTTP/1.1 404
```

Пример ответа:

```json
{
  "timestamp": "2026-...",
  "status": 404,
  "error": "NOT_FOUND",
  "message": "Организация не найдена: 11111111-2222-3333-4444-555555555555",
  "path": "/api/organizations/11111111-2222-3333-4444-555555555555",
  "fieldErrors": null
}
```

Смысл проверки:

- `ResourceNotFoundException` работает после включения security;
- `GlobalExceptionHandler` возвращает JSON-ошибку;
- ошибка бизнес-логики не превращается в `500`.

---

# 14. Проверка ошибки 400 при пустом имени организации с токеном

Команда:

```bat
curl -i -X POST http://localhost:8080/api/organizations ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"\"}"
```

Что делает:

- пытается создать организацию с пустым `name`;
- `CreateOrganizationRequest` содержит `@NotBlank`;
- backend должен отклонить запрос.

Ожидаемый результат:

```text
HTTP/1.1 400
```

Пример ответа:

```json
{
  "timestamp": "2026-...",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "Ошибка валидации запроса",
  "path": "/api/organizations",
  "fieldErrors": {
    "name": "не должно быть пустым"
  }
}
```

Смысл проверки:

- `@Valid` продолжает работать;
- `MethodArgumentNotValidException` обрабатывается;
- field-level errors возвращаются клиенту.

---

# 15. Проверка некорректного UUID в JSON

Команда:

```bat
curl -i -X POST http://localhost:8080/api/users ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"organizationId\":\"PASTE_ORGANIZATION_ID_HERE\",\"email\":\"bad-uuid@test.com\",\"password\":\"admin123\",\"fullName\":\"Admin User\",\"roles\":[\"ADMIN\"]}"
```

Что делает:

- передает текст `PASTE_ORGANIZATION_ID_HERE` вместо UUID;
- поле `organizationId` в `CreateUserRequest` имеет тип `UUID`;
- Spring не может преобразовать строку в UUID.

Ожидаемый результат:

```text
HTTP/1.1 400
```

Пример ответа:

```json
{
  "timestamp": "2026-...",
  "status": 400,
  "error": "BAD_REQUEST",
  "message": "Некорректное тело запроса. Проверьте формат JSON и типы полей",
  "path": "/api/users",
  "fieldErrors": null
}
```

Смысл проверки:

- `HttpMessageNotReadableException` обрабатывается;
- ошибка клиента возвращает `400`, а не `500`;
- backend не падает от некорректного JSON.

---

# 16. Проверка базы данных: users

Команда:

```bat
docker exec -it safeai-postgres psql -U safeai -d safeai -c "select id, organization_id, email, password_hash, enabled, created_at from users;"
```

Ожидаемый результат:

```text
email = admin@test.com
enabled = t
password_hash начинается с $2a$ или $2b$
```

Пример:

```text
9cd7ff22-49b2-4468-af17-2cf8889665e3 | af1e6969-9f96-4ec9-bff2-34c4fe55832a | admin@test.com | $2a$10$... | t
```

Смысл проверки:

- пользователь реально сохранен в PostgreSQL;
- пароль не хранится как `admin123`;
- в базе лежит BCrypt hash;
- пользователь включен.

---

# 17. Проверка базы данных: roles

Команда:

```bat
docker exec -it safeai-postgres psql -U safeai -d safeai -c "select * from roles;"
```

Ожидаемый результат:

```text
ADMIN
USER
```

Пример:

```text
11111111-1111-1111-1111-111111111111 | ADMIN
22222222-2222-2222-2222-222222222222 | USER
```

Смысл проверки:

- Flyway seed-миграция ролей сработала;
- роли доступны для `UserService` и `CustomUserDetailsService`.

---

# 18. Проверка связи пользователя с ролью

Команда:

```bat
docker exec -it safeai-postgres psql -U safeai -d safeai -c "select u.email, r.name from users u join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id;"
```

Ожидаемый результат:

```text
admin@test.com | ADMIN
```

Смысл проверки:

- many-to-many связь `users` ↔ `roles` работает;
- таблица `user_roles` заполнена;
- `CustomUserDetailsService` сможет собрать authority `ROLE_ADMIN`.

---

# 19. Что считать успешным результатом

Этап `Auth/Login/JWT` можно считать успешным, если выполняется весь список:

- `POST /api/auth/login` с правильным паролем возвращает `200`.
- Ответ login содержит `token`, `tokenType`, `user`.
- Токен имеет вид `header.payload.signature`.
- `/api/auth/me` с токеном возвращает текущего пользователя.
- `/api/users` без токена возвращает `401`.
- `/api/users` с токеном возвращает `200`.
- `/api/users` с битым токеном возвращает `401`.
- Login с неправильным паролем возвращает `401`.
- `/api/organizations` без токена возвращает `401`.
- `/api/organizations` с токеном возвращает `200`.
- Ошибки `400`, `404`, `409` продолжают возвращаться в JSON-формате.
- `users.password_hash` в базе хранит BCrypt hash, а не обычный пароль.
- `admin@test.com` имеет роль `ADMIN`.
- Backend не падает после ошибочных запросов.

---

# 20. Типовые ошибки при проверке

## Ошибка 1. `Bearer token is malformed`

Причина:

```bat
-H "Authorization: Bearer "token":"eyJ..."
```

Правильно:

```bat
-H "Authorization: Bearer eyJ..."
```

В `Authorization` передается только сам токен.

---

## Ошибка 2. В CMD вставлен prompt вместе с командой

Неправильно:

```bat
C:\Users\Amid>set "TOKEN=eyJ..."
```

Правильно вводить только команду:

```bat
set "TOKEN=eyJ..."
```

`C:\Users\Amid>` — это приглашение командной строки, его не надо копировать.

---

## Ошибка 3. `HTTP/1.1 401` при `/api/auth/me`

Возможные причины:

- токен не вставлен;
- токен вставлен вместе с `"token":`;
- токен истек;
- backend был перезапущен с другим JWT secret;
- в переменной `%TOKEN%` лежит неправильное значение.

Проверить:

```bat
echo %TOKEN%
```

Если нужно, выполнить login заново и вставить новый токен.

---

## Ошибка 4. `HTTP/1.1 500` при login

Возможные причины:

- отсутствует `app.security.jwt.secret` в `application.yml`;
- отсутствует `app.security.jwt.expiration-minutes`;
- ошибка в `JwtService`;
- JWT encoder не получил JWS header `HS256`;
- пользователь есть, но роль не привязана;
- исключение не обработано в `GlobalExceptionHandler`.

Проверить backend-терминал и найти строку:

```text
Caused by:
```

---

# 21. После успешной проверки

Сделать commit:

```bat
cd /d "D:\Java projects\Safeai-desk"
git status
git add .
git commit -m "add jwt authentication"
git push
```

Перед `git add .` проверить, чтобы в Git не попали:

```text
backend/target/
.env
API keys
реальные production-секреты
случайные файлы из infra
```

---

# 22. Следующий этап разработки

Следующий логичный этап:

```text
Chat Core
```

Что делать дальше:

- создать `ChatSessionEntity`;
- создать `ChatMessageEntity`;
- создать `ChatSessionRepository`;
- создать `ChatMessageRepository`;
- создать `ChatController`;
- создать `ChatService`;
- endpoint `POST /api/chats`;
- endpoint `GET /api/chats`;
- endpoint `GET /api/chats/{id}`;
- endpoint `POST /api/chats/{id}/messages`;
- сохранять сообщение пользователя;
- возвращать mock AI response;
- сохранять ответ AI;
- привязывать чаты к текущему пользователю из JWT.
