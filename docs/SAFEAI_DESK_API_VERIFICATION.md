# SafeAI Desk — инструкция по проверке API

Документ описывает, как вручную проверить текущий backend SafeAI Desk через `curl` в Windows CMD.

Текущий этап backend-а:

- работает `Organization API`;
- работает `User API`;
- роли `ADMIN` и `USER` создаются через Flyway-миграцию;
- пароль пользователя сохраняется как BCrypt hash;
- ошибки API возвращаются в едином JSON-формате через `GlobalExceptionHandler`;
- проверены статусы `200`, `400`, `404`, `409`;
- дополнительно нужно перепроверить обработку некорректного JSON/UUID после добавления `HttpMessageNotReadableException`.

---

## 1. Перед проверкой

### 1.1. Должны быть запущены Docker-контейнеры

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
- `safeai-redis` пока поднят как часть инфраструктуры на будущее, например для сессий, кеша, rate limit или очередей.

---

### 1.2. Должен быть запущен backend

Перейти в backend:

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
- Spring подключился к PostgreSQL;
- Flyway проверил/применил миграции;
- Hibernate проверил соответствие Entity-классов таблицам;
- Spring Data увидел Repository.

---

## 2. Проверка Organization API

### 2.1. Получить список организаций

Команда:

```bat
curl http://localhost:8080/api/organizations
```

Что делает:

- отправляет `GET`-запрос на endpoint `/api/organizations`;
- backend обращается к таблице `organizations`;
- возвращает список организаций.

Если база пустая, ожидаемый результат:

```json
[]
```

Это нормально. `[]` означает, что endpoint работает, но организаций пока нет.

---

### 2.2. Создать организацию

Команда:

```bat
curl -X POST http://localhost:8080/api/organizations ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"Test Company\"}"
```

Что делает:

- отправляет `POST`-запрос на `/api/organizations`;
- передает JSON-тело запроса;
- создает новую организацию с названием `Test Company`.

Что означает каждая часть:

```bat
curl
```

Запуск консольного HTTP-клиента.

```bat
-X POST
```

Использовать HTTP-метод `POST`, то есть создать ресурс.

```bat
http://localhost:8080/api/organizations
```

Адрес backend-а на твоем компьютере.

```bat
-H "Content-Type: application/json"
```

Заголовок: тело запроса отправляется в формате JSON.

```bat
-d "{\"name\":\"Test Company\"}"
```

Тело запроса. В нормальном JSON виде это:

```json
{
  "name": "Test Company"
}
```

Ожидаемый результат:

```json
{
  "id": "af1e6969-9f96-4ec9-bff2-34c4fe55832a",
  "name": "Test Company",
  "createdAt": "2026-05-30T20:55:26.4839453"
}
```

`id` каждый раз будет другим. Его нужно скопировать для создания пользователя.

---

### 2.3. Повторно получить список организаций

Команда:

```bat
curl http://localhost:8080/api/organizations
```

Ожидаемый результат:

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

- организация реально сохранилась в PostgreSQL;
- `OrganizationController`, `OrganizationService`, `OrganizationRepository` работают вместе;
- `OrganizationEntity` правильно сопоставлен с таблицей `organizations`.

---

## 3. Проверка ошибки 404 для организации

Команда:

```bat
curl http://localhost:8080/api/organizations/11111111-2222-3333-4444-555555555555
```

Что делает:

- пытается получить организацию по UUID;
- такого UUID в базе нет;
- backend должен вернуть ошибку `404 NOT_FOUND`.

Ожидаемый результат:

```json
{
  "timestamp": "2026-05-30T20:19:06.7431822",
  "status": 404,
  "error": "NOT_FOUND",
  "message": "Организация не найдена: 11111111-2222-3333-4444-555555555555",
  "path": "/api/organizations/11111111-2222-3333-4444-555555555555",
  "fieldErrors": null
}
```

Смысл проверки:

- `OrganizationService` выбрасывает `ResourceNotFoundException`;
- `GlobalExceptionHandler` перехватывает исключение;
- API возвращает нормальный JSON, а не stack trace;
- HTTP-статус соответствует ситуации: ресурс не найден.

---

## 4. Проверка ошибки 400 при пустом имени организации

Команда:

```bat
curl -X POST http://localhost:8080/api/organizations ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"\"}"
```

Что делает:

- пытается создать организацию с пустым `name`;
- `CreateOrganizationRequest` содержит `@NotBlank`;
- валидация должна отклонить запрос.

Ожидаемый результат:

```json
{
  "timestamp": "2026-05-30T20:20:03.2682949",
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

- `@Valid` в `OrganizationController` работает;
- `@NotBlank` в `CreateOrganizationRequest` работает;
- `GlobalExceptionHandler` обрабатывает `MethodArgumentNotValidException`;
- клиент видит конкретное поле, в котором ошибка.

---

## 5. Проверка User API

Перед созданием пользователя должна существовать организация.

Сначала получить список организаций:

```bat
curl http://localhost:8080/api/organizations
```

Найти `id` созданной организации.

Пример:

```text
af1e6969-9f96-4ec9-bff2-34c4fe55832a
```

---

### 5.1. Получить список пользователей

Команда:

```bat
curl http://localhost:8080/api/users
```

Если пользователей нет, ожидаемый результат:

```json
[]
```

Это нормально. Endpoint работает, но таблица `users` пустая.

---

### 5.2. Создать пользователя

Команда:

```bat
curl -X POST http://localhost:8080/api/users ^
  -H "Content-Type: application/json" ^
  -d "{\"organizationId\":\"af1e6969-9f96-4ec9-bff2-34c4fe55832a\",\"email\":\"admin@test.com\",\"password\":\"admin123\",\"fullName\":\"Admin User\",\"roles\":[\"ADMIN\"]}"
```

Важно: вместо `af1e6969-9f96-4ec9-bff2-34c4fe55832a` нужно подставить реальный `id` организации из твоей базы.

Что делает:

- создает пользователя;
- привязывает пользователя к организации;
- ищет роль `ADMIN` в таблице `roles`;
- хэширует пароль через `PasswordEncoder`;
- сохраняет пользователя в таблицу `users`;
- сохраняет связь пользователя и роли в таблицу `user_roles`;
- возвращает `UserResponse` без пароля и без `passwordHash`.

Ожидаемый результат:

```json
{
  "id": "9cd7ff22-49b2-4468-af17-2cf8889665e3",
  "organizationId": "af1e6969-9f96-4ec9-bff2-34c4fe55832a",
  "email": "admin@test.com",
  "fullName": "Admin User",
  "enabled": true,
  "roles": ["ADMIN"],
  "createdAt": "2026-05-30T20:58:51.9263435"
}
```

Смысл проверки:

- `UserController` принимает JSON;
- `CreateUserRequest` корректно преобразуется из JSON;
- `UserService` проверяет email, организацию и роли;
- `PasswordEncoder` работает;
- `UserRepository` сохраняет пользователя;
- связь many-to-many `UserEntity.roles` работает;
- наружу не возвращается пароль.

---

### 5.3. Повторно получить список пользователей

Команда:

```bat
curl http://localhost:8080/api/users
```

Ожидаемый результат:

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

- пользователь реально сохранен;
- данные читаются из PostgreSQL;
- роли пользователя корректно возвращаются в ответе.

---

## 6. Проверка ошибки 404 при несуществующей организации пользователя

Команда:

```bat
curl -X POST http://localhost:8080/api/users ^
  -H "Content-Type: application/json" ^
  -d "{\"organizationId\":\"11de5cea-1484-4ef0-8cb4-7bc758546040\",\"email\":\"newadmin@test.com\",\"password\":\"admin123\",\"fullName\":\"Admin User\",\"roles\":[\"ADMIN\"]}"
```

Важно: для этой проверки лучше использовать новый email, которого еще нет в базе, например:

```text
newadmin@test.com
```

Почему:

- если email уже существует, backend сначала вернет `409 CONFLICT`;
- до проверки организации код может не дойти;
- для чистой проверки `404` лучше использовать новый email.

Ожидаемый результат:

```json
{
  "timestamp": "2026-05-30T20:56:19.15137",
  "status": 404,
  "error": "NOT_FOUND",
  "message": "Организация не найдена: 11de5cea-1484-4ef0-8cb4-7bc758546040",
  "path": "/api/users",
  "fieldErrors": null
}
```

Смысл проверки:

- `UserService` ищет организацию через `OrganizationRepository`;
- если организации нет, выбрасывает `ResourceNotFoundException`;
- `GlobalExceptionHandler` возвращает `404`.

---

## 7. Проверка ошибки 409 при повторном email

Команда:

```bat
curl -X POST http://localhost:8080/api/users ^
  -H "Content-Type: application/json" ^
  -d "{\"organizationId\":\"af1e6969-9f96-4ec9-bff2-34c4fe55832a\",\"email\":\"admin@test.com\",\"password\":\"admin123\",\"fullName\":\"Admin User\",\"roles\":[\"ADMIN\"]}"
```

Этот запрос нужно выполнить после того, как пользователь `admin@test.com` уже был создан.

Ожидаемый результат:

```json
{
  "timestamp": "2026-05-30T21:04:00.8000681",
  "status": 409,
  "error": "CONFLICT",
  "message": "Пользователь с таким email уже существует: admin@test.com",
  "path": "/api/users",
  "fieldErrors": null
}
```

Смысл проверки:

- `UserRepository.existsByEmail(...)` работает;
- повторный email не допускается;
- `ConflictException` правильно превращается в HTTP `409 CONFLICT`.

---

## 8. Проверка некорректного UUID в JSON

Команда:

```bat
curl -X POST http://localhost:8080/api/users ^
  -H "Content-Type: application/json" ^
  -d "{\"organizationId\":\"PASTE_ORGANIZATION_ID_HERE\",\"email\":\"bad-uuid@test.com\",\"password\":\"admin123\",\"fullName\":\"Admin User\",\"roles\":[\"ADMIN\"]}"
```

Что делает:

- отправляет текст `PASTE_ORGANIZATION_ID_HERE` вместо UUID;
- поле `organizationId` в `CreateUserRequest` имеет тип `UUID`;
- Spring не может преобразовать строку в UUID.

Раньше результат был:

```json
{
  "timestamp": "2026-05-30T20:56:03.5743576",
  "status": 500,
  "error": "INTERNAL_SERVER_ERROR",
  "message": "Внутренняя ошибка сервера",
  "path": "/api/users",
  "fieldErrors": null
}
```

Это было нежелательно, потому что ошибка клиента не должна возвращаться как `500`.

---

### Рекомендация

У тебя уже добавлен обработчик `HttpMessageNotReadableException`. После перезапуска backend надо повторить тест с:

```json
{
  "organizationId": "PASTE_ORGANIZATION_ID_HERE"
}
```

и убедиться, что теперь приходит `400`, а не `500`.

Ожидаемый результат после перезапуска backend:

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

- `HttpMessageNotReadableException` теперь обрабатывается отдельно;
- неправильный UUID в JSON считается ошибкой клиента;
- API возвращает `400 BAD_REQUEST`, а не `500 INTERNAL_SERVER_ERROR`.

---

## 9. Рекомендуемый полный порядок проверки

Лучше проверять в таком порядке:

```bat
curl http://localhost:8080/api/organizations
```

Проверить, пустая база или уже есть организации.

```bat
curl -X POST http://localhost:8080/api/organizations ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"Test Company\"}"
```

Создать организацию и скопировать ее `id`.

```bat
curl http://localhost:8080/api/organizations
```

Убедиться, что организация появилась.

```bat
curl http://localhost:8080/api/organizations/11111111-2222-3333-4444-555555555555
```

Проверить `404`.

```bat
curl -X POST http://localhost:8080/api/organizations ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"\"}"
```

Проверить `400 VALIDATION_ERROR`.

```bat
curl http://localhost:8080/api/users
```

Проверить список пользователей.

```bat
curl -X POST http://localhost:8080/api/users ^
  -H "Content-Type: application/json" ^
  -d "{\"organizationId\":\"REAL_ORGANIZATION_ID\",\"email\":\"admin@test.com\",\"password\":\"admin123\",\"fullName\":\"Admin User\",\"roles\":[\"ADMIN\"]}"
```

Создать пользователя.

```bat
curl http://localhost:8080/api/users
```

Проверить, что пользователь появился.

```bat
curl -X POST http://localhost:8080/api/users ^
  -H "Content-Type: application/json" ^
  -d "{\"organizationId\":\"REAL_ORGANIZATION_ID\",\"email\":\"admin@test.com\",\"password\":\"admin123\",\"fullName\":\"Admin User\",\"roles\":[\"ADMIN\"]}"
```

Проверить `409 CONFLICT`.

```bat
curl -X POST http://localhost:8080/api/users ^
  -H "Content-Type: application/json" ^
  -d "{\"organizationId\":\"PASTE_ORGANIZATION_ID_HERE\",\"email\":\"bad-uuid@test.com\",\"password\":\"admin123\",\"fullName\":\"Admin User\",\"roles\":[\"ADMIN\"]}"
```

Проверить, что некорректный UUID теперь возвращает `400 BAD_REQUEST`.

---

## 10. Что считать успешным результатом этапа

Этап можно считать успешным, если выполняется весь список:

- `GET /api/organizations` возвращает список организаций.
- `POST /api/organizations` создает организацию.
- `GET /api/organizations/{id}` возвращает `404`, если организации нет.
- `POST /api/organizations` с пустым `name` возвращает `400`.
- `GET /api/users` возвращает список пользователей.
- `POST /api/users` создает пользователя с ролью `ADMIN`.
- Пароль не возвращается в API-ответе.
- Повторный email возвращает `409`.
- Несуществующая организация возвращает `404`, если email новый.
- Некорректный UUID в JSON возвращает `400`, а не `500`.
- Backend не падает после ошибочных запросов.
- Все ошибки возвращаются в едином формате `ApiErrorResponse`.

---

## 11. После успешной проверки

Сделать commit:

```bat
cd /d "D:\Java projects\Safeai-desk"
git status
git add .
git commit -m "add api verification instructions"
git push
```

Перед commit проверить, что не попали лишние файлы:

```bat
git status
```

В Git не должны попадать:

```text
backend/target/
infra/cd
infra/cls
infra/docker
.env
API keys
реальные секреты
```

---

## 12. Что будет следующим этапом

После успешной проверки текущего API следующий крупный этап:

```text
Auth / Login / JWT
```

Что нужно будет сделать дальше:

- `AuthController`;
- `LoginRequest`;
- `LoginResponse`;
- `JwtService`;
- `UserDetailsService`;
- проверка пароля через `PasswordEncoder.matches(...)`;
- endpoint `POST /api/auth/login`;
- endpoint `GET /api/auth/me`;
- закрыть все API кроме `/api/auth/login` и `/actuator/health`;
- научить frontend хранить и отправлять JWT.

