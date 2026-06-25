# 12. Frontend MVP Verification

Документ описывает проверку frontend-части SafeAI Desk после добавления React/Vite интерфейса.

Цель проверки: убедиться, что frontend запускается, подключается к backend API, выполняет login по JWT, открывает страницы Chat/Admin и корректно отображает данные из backend.

---

## 1. Что проверяется

В рамках frontend MVP проверяются следующие сценарии:

- запуск React/Vite frontend;
- открытие страницы login;
- авторизация пользователя через backend `/api/auth/login`;
- сохранение JWT-токена в `localStorage`;
- переход на страницу `/chat`;
- создание чата;
- отправка сообщения;
- получение mock AI ответа;
- отображение usage-метрик сообщения;
- открытие admin-страниц:
  - `/admin/users`;
  - `/admin/audit`;
  - `/admin/usage`;
- проверка production-сборки frontend через `npm run build`.

---

## 2. Структура frontend

Ожидаемая структура frontend:

```text
frontend
├── index.html
├── package.json
├── package-lock.json
├── tsconfig.json
├── tsconfig.app.json
├── tsconfig.node.json
├── vite.config.ts
└── src
    ├── api
    │   ├── adminApi.ts
    │   ├── authApi.ts
    │   ├── chatApi.ts
    │   ├── http.ts
    │   └── userApi.ts
    ├── pages
    │   ├── AdminAuditPage.tsx
    │   ├── AdminUsagePage.tsx
    │   ├── AdminUsersPage.tsx
    │   ├── ChatPage.tsx
    │   └── LoginPage.tsx
    ├── App.tsx
    ├── global.d.ts
    ├── index.css
    ├── main.tsx
    └── vite-env.d.ts
```

---

## 3. Файлы, которые не должны попадать в Git

Не пушить:

```text
frontend/node_modules/
frontend/dist/
frontend/.vite/
frontend/.env
frontend/.env.local
```

Эти файлы и папки должны быть закрыты через `.gitignore`.

Проверка:

```bat
cd /d "D:\Java projects\Safeai-desk"
git status
```

В выводе не должно быть:

```text
frontend/node_modules/
frontend/dist/
.env
```

---

## 4. Запуск инфраструктуры

Сначала запускаются PostgreSQL и Redis.

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose up -d postgres redis
```

Проверка контейнеров:

```bat
docker ps
```

Ожидаемо должны быть запущены:

```text
safeai-postgres
safeai-redis
```

---

## 5. Запуск backend

Открыть отдельное CMD-окно:

```bat
cd /d "D:\Java projects\Safeai-desk\backend"

set SAFEAI_JWT_SECRET=safeai-local-development-secret-key-change-this-value-please-123456789
set SAFEAI_JWT_EXPIRATION_MINUTES=60

mvnw.cmd spring-boot:run
```

лучше так:
```bat
\backend"

set SPRING_PROFILES_ACTIVE=local
set SAFEAI_JWT_SECRET=safeai-local-development-secret-key-change-this-value-please-123456789
set SAFEAI_JWT_EXPIRATION_MINUTES=60
set SAFEAI_JWT_ISSUER=safeai-desk

set SAFEAI_AUTH_COOKIES_SECURE=false
set SAFEAI_AUTH_COOKIES_SAME_SITE=Lax
set SAFEAI_AUTH_ACCESS_TOKEN_MAX_AGE=15m
set SAFEAI_AUTH_REFRESH_TOKEN_MAX_AGE=30d

set REDIS_PASSWORD=safeai_redis_password

mvnw.cmd spring-boot:run
```

Ожидаемый результат в логах:

```text
Tomcat started on port 8080
Started SafeaiBackendApplication
```

Backend должен быть доступен по адресу:

```text
http://localhost:8080
```

---

## 6. Проверка backend health

В новом CMD-окне:

```bat
curl -i http://localhost:8080/actuator/health
```

Ожидаемый результат:

```http
HTTP/1.1 200
```

Тело ответа:

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

---

## 7. Проверка backend login через curl

```bat
curl -i -X POST http://localhost:8080/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"admin@test.com\",\"password\":\"admin123\"}"
```

Ожидаемый результат:

```http
HTTP/1.1 200
```

В JSON-ответе должен быть JWT token:

```json
{
  "token": "...",
  "tokenType": "Bearer",
  "user": {
    "email": "admin@test.com",
    "roles": ["ADMIN"]
  }
}
```

---

## 8. Запуск frontend

Открыть отдельное CMD-окно:

```bat
cd /d "D:\Java projects\Safeai-desk\frontend"
npm run dev
```

Ожидаемый результат:

```text
VITE ready
Local: http://localhost:5173/
```

Frontend должен быть доступен по адресу:

```text
http://localhost:5173/
```

---

## 9. Проверка login-страницы

Открыть в браузере:

```text
http://localhost:5173/
```

Или сразу:

```text
http://localhost:5173/login
```

Ожидаемый результат:

- открывается страница `SafeAI Desk Login`;
- поля заполнены демо-данными:
  - email: `admin@test.com`;
  - password: `admin123`;
- кнопка `Login` доступна.

После нажатия `Login` ожидается переход на:

```text
http://localhost:5173/chat
```

---

## 10. Проверка JWT в localStorage

После успешного login открыть DevTools:

```text
F12 → Application → Local Storage → http://localhost:5173
```

Ожидаемый ключ:

```text
safeai_token
```

Значение должно содержать JWT-токен.

---

## 11. Проверка Chat page

Открыть:

```text
http://localhost:5173/chat
```

Ожидаемые элементы:

- верхнее меню:
  - `SafeAI Desk`;
  - `Chat`;
  - `Users`;
  - `Audit`;
  - `Usage`;
  - `Logout`;
- заголовок `Chat`;
- кнопка `Create chat`;
- список чатов;
- поле ввода сообщения;
- кнопка `Send`.

---

## 12. Создание чата

На странице `/chat` нажать:

```text
Create chat
```

Ожидаемый результат:

- в списке слева появляется новый чат;
- справа открывается область выбранного чата;
- заголовок примерно такой:

```text
Demo chat HH:MM:SS
```

---

## 13. Отправка сообщения

В поле ввода написать:

```text
Привет
```

Нажать:

```text
Send
```

Ожидаемый результат:

Должно появиться сообщение пользователя:

```text
USER
Привет
```

И ответ assistant:

```text
ASSISTANT
Mock AI provider response: Привет
```

Также должны отображаться usage-метрики:

```text
model: mock-safeai | input: 1 | output: 8 | cost: 0
```

Фактические значения `input` и `output` могут немного отличаться, если текст сообщения другой.

---

## 14. Проверка страницы Users

Открыть через меню:

```text
Users
```

Или вручную:

```text
http://localhost:5173/admin/users
```

Ожидаемый результат:

Таблица пользователей должна содержать минимум:

```text
admin@test.com
```

Ожидаемые колонки:

```text
Email
Full name
Roles
Enabled
Created at
```

---

## 15. Проверка страницы Audit

Открыть через меню:

```text
Audit
```

Или вручную:

```text
http://localhost:5173/admin/audit
```

Ожидаемый результат:

Должны отображаться audit events.

После login, создания чата и отправки сообщения ожидаются события:

```text
USER_LOGIN_SUCCESS
CHAT_CREATED
CHAT_MESSAGE_SENT
AI_RESPONSE_RECEIVED
```

Ожидаемые колонки:

```text
Created at
User
Event type
Details
```

---

## 16. Проверка страницы Usage

Открыть через меню:

```text
Usage
```

Или вручную:

```text
http://localhost:5173/admin/usage
```

Ожидаемый результат:

Должна отображаться таблица usage summary.

Ожидаемые данные:

```text
admin@test.com
mock-safeai
inputTokens
outputTokens
totalTokens
costUsd
```

Ожидаемые колонки:

```text
User
Model
Input tokens
Output tokens
Total tokens
Cost USD
```

---

## 17. Проверка logout

Нажать:

```text
Logout
```

Ожидаемый результат:

- пользователь переходит на `/login`;
- JWT удаляется из `localStorage`;
- верхнее меню скрывается.

Проверить в DevTools:

```text
F12 → Application → Local Storage
```

Ключ `safeai_token` должен отсутствовать.

---

## 18. Проверка frontend build

В CMD:

```bat
cd /d "D:\Java projects\Safeai-desk\frontend"
npm run build
```

Ожидаемый результат:

```text
built in ...
```

После успешной сборки появляется папка:

```text
frontend/dist
```

Папку `dist` не пушить в Git.

---

## 19. Проверка backend тестов

В CMD:

```bat
cd /d "D:\Java projects\Safeai-desk\backend"
mvnw.cmd clean test
```

Ожидаемый результат:

```text
BUILD SUCCESS
```

---

## 20. Проверка Git перед коммитом

Из корня проекта:

```bat
cd /d "D:\Java projects\Safeai-desk"
git status
```

В Git можно добавлять:

```text
frontend/package.json
frontend/package-lock.json
frontend/index.html
frontend/vite.config.ts
frontend/tsconfig.json
frontend/tsconfig.app.json
frontend/tsconfig.node.json
frontend/src/**
docs/12_FRONTEND_MVP_VERIFICATION.md
.gitignore
```

Не должно быть в staged/untracked:

```text
frontend/node_modules/
frontend/dist/
backend/target/
.env
.idea/
```

---

## 21. Коммит frontend MVP

```bat
cd /d "D:\Java projects\Safeai-desk"

git add .gitignore
git add frontend/package.json
git add frontend/package-lock.json
git add frontend/index.html
git add frontend/vite.config.ts
git add frontend/tsconfig.json
git add frontend/tsconfig.app.json
git add frontend/tsconfig.node.json
git add frontend/src
git add docs/12_FRONTEND_MVP_VERIFICATION.md

git commit -m "feat: add frontend MVP"
git push origin main
```

---

## 22. Итоговый критерий готовности

Frontend MVP считается рабочим, если выполнены условия:

- `docker compose up -d postgres redis` запускает инфраструктуру;
- backend стартует на `8080`;
- `/actuator/health` возвращает `UP`;
- frontend стартует на `5173`;
- login проходит через `admin@test.com / admin123`;
- после login открывается `/chat`;
- можно создать чат;
- можно отправить сообщение;
- появляется mock AI ответ;
- usage-метрики отображаются в чате;
- `/admin/users` показывает пользователей;
- `/admin/audit` показывает события;
- `/admin/usage` показывает usage summary;
- `npm run build` завершается успешно;
- `mvnw.cmd clean test` завершается успешно;
- в Git не попадают `node_modules`, `dist`, `target`, `.env`.
