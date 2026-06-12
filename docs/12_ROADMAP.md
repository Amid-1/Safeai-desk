# SafeAI Desk — roadmap

## 1. Цель проекта

Собрать backend-first MVP корпоративного AI Gateway:

```text
users → roles → auth → chat → ai provider → audit → usage → frontend → real AI provider → limits → RAG
```

Идея проекта: сделать корпоративную прослойку между пользователями и AI-провайдерами, где есть авторизация, роли, история чатов, аудит действий, учет токенов/стоимости и будущая возможность подключить разные AI-провайдеры и RAG.

---

## 2. Текущий статус

На текущий момент проект уже перешёл из чисто backend MVP в рабочий full-stack MVP.

Рабочий сценарий уже демонстрируется в браузере:

```text
Login
  ↓
JWT
  ↓
Frontend React app
  ↓
Create chat
  ↓
Send message
  ↓
MockAiProvider response
  ↓
Save messages
  ↓
Save usage fields
  ↓
Write audit events
  ↓
Admin checks users / audit / usage
```

---

## 3. Сделано

```text
✅ Infrastructure
✅ Docker Compose
✅ PostgreSQL
✅ Redis
✅ Flyway migrations
✅ Organization API
✅ User API
✅ ADMIN / USER roles
✅ BCrypt passwords
✅ JSON error responses
✅ JWT Auth
✅ /api/auth/login
✅ /api/auth/me
✅ Chat Core
✅ AI Provider abstraction
✅ MockAiProvider
✅ Audit events
✅ Admin Audit API
✅ Usage tracking fields in chat_messages
✅ Admin Usage Summary API
✅ React + TypeScript frontend MVP
✅ Vite frontend dev server
✅ Login page
✅ Chat page
✅ Admin Users page
✅ Admin Audit page
✅ Admin Usage page
✅ Frontend API layer
✅ Frontend JWT storage in localStorage
✅ Basic frontend routing
```

---

## 4. Уже реализованные backend endpoints

### Auth

```text
POST /api/auth/login
GET  /api/auth/me
```

### Organizations

```text
POST /api/organizations
GET  /api/organizations
GET  /api/organizations/{id}
```

### Users

```text
POST /api/users
GET  /api/users
GET  /api/users/{id}
```

### Chats

```text
POST /api/chats
GET  /api/chats
GET  /api/chats/{id}
POST /api/chats/{id}/messages
```

### Admin Audit

```text
GET /api/admin/audit-events
GET /api/admin/audit-events/users/{userId}
```

### Admin Usage

```text
GET /api/admin/usage-summary
```

---

## 5. Текущий frontend MVP

Frontend находится в папке:

```text
frontend
```

Стек:

```text
React
TypeScript
Vite
react-router-dom
Fetch API
```

Реализованные страницы:

```text
/login
/chat
/admin/users
/admin/audit
/admin/usage
```

Текущий browser-flow:

```text
http://localhost:5173
  ↓
Login as admin@test.com / admin123
  ↓
/chat
  ↓
Create chat
  ↓
Send message
  ↓
Receive Mock AI response
  ↓
Open Users / Audit / Usage
```

---

## 6. Текущий рабочий сценарий

### 6.1. Login

Пользователь открывает:

```text
http://localhost:5173/login
```

Вводит:

```text
admin@test.com
admin123
```

Frontend вызывает:

```text
POST /api/auth/login
```

Backend возвращает JWT.

Frontend сохраняет JWT в:

```text
localStorage.safeai_token
```

После этого пользователь попадает на:

```text
/chat
```

---

### 6.2. Chat

Пользователь нажимает:

```text
Create chat
```

Frontend вызывает:

```text
POST /api/chats
```

Потом пользователь отправляет сообщение.

Frontend вызывает:

```text
POST /api/chats/{id}/messages
```

Backend:

```text
1. сохраняет USER message;
2. вызывает AiProvider;
3. MockAiProvider возвращает mock response;
4. сохраняет ASSISTANT message;
5. сохраняет model/inputTokens/outputTokens/costUsd;
6. пишет audit events.
```

На frontend отображается:

```text
USER
Привет

ASSISTANT
Mock AI provider response: Привет

model: mock-safeai | input: 1 | output: 8 | cost: 0
```

---

### 6.3. Admin Users

Страница:

```text
/admin/users
```

Показывает пользователей системы.

Минимально ожидаемый пользователь:

```text
admin@test.com
```

---

### 6.4. Admin Audit

Страница:

```text
/admin/audit
```

Показывает audit events.

После login, создания чата и отправки сообщения ожидаются события:

```text
USER_LOGIN_SUCCESS
CHAT_CREATED
CHAT_MESSAGE_SENT
AI_RESPONSE_RECEIVED
```

---

### 6.5. Admin Usage

Страница:

```text
/admin/usage
```

Показывает агрегированную статистику использования AI.

Ожидаемые данные:

```text
userEmail: admin@test.com
model: mock-safeai
inputTokens
outputTokens
totalTokens
costUsd
```

Backend endpoint:

```text
GET /api/admin/usage-summary
```

---

## 7. Usage tracking — текущий статус

Usage уже реализован на MVP-уровне.

`chat_messages` содержит поля:

```text
model
input_tokens
output_tokens
cost_usd
```

`MockAiProvider` уже заполняет:

```text
model = mock-safeai
inputTokens
outputTokens
costUsd
```

Admin endpoint уже агрегирует usage:

```text
GET /api/admin/usage-summary
```

Текущий пример ответа:

```json
[
  {
    "userId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
    "userEmail": "admin@test.com",
    "model": "mock-safeai",
    "inputTokens": 17,
    "outputTokens": 31,
    "totalTokens": 48,
    "costUsd": 0.000000
  }
]
```

---

## 8. Что осталось улучшить в Usage

Текущий usage summary уже работает, но для полноценного admin-модуля позже можно добавить дополнительные endpoints:

```text
GET /api/admin/usage/users
GET /api/admin/usage/models
GET /api/admin/usage/daily
GET /api/admin/usage/by-user/{userId}
GET /api/admin/usage/by-organization/{organizationId}
```

Это уже не блокирует MVP, потому что базовая usage-аналитика работает.

---

## 9. Следующий этап

Следующий логичный этап после текущего full-stack MVP:

```text
Rate limits через Redis
```

Почему это следующий этап:

```text
- Redis уже есть в инфраструктуре;
- JWT и userId уже есть;
- chat endpoint уже является точкой потребления AI;
- usage уже считается;
- можно ограничивать количество сообщений или токенов на пользователя/организацию.
```

Минимальная цель этапа:

```text
Ограничить количество AI-запросов пользователя за период времени.
```

Пример будущего правила:

```text
USER: 20 сообщений в час
ADMIN: без ограничения или отдельный лимит
```

Возможные будущие endpoints/admin-настройки:

```text
GET /api/admin/limits
PUT /api/admin/limits
GET /api/admin/limits/usage
```

На первом этапе можно сделать без UI, только backend enforcement.

---

## 10. После Rate Limits

После лимитов логичный порядок такой:

```text
⬜ Real AI provider
⬜ Provider config
⬜ Provider switch через application.yml/env
⬜ Better frontend auth state
⬜ Protected routes
⬜ Chat history loading improvement
⬜ Admin dashboards
⬜ Documents upload
⬜ RAG
```

---

## 11. High-level roadmap

```text
1. Infrastructure              ✅ done
2. Database + Flyway           ✅ done
3. Organization API            ✅ done
4. User API                    ✅ done
5. Auth/JWT                    ✅ done
6. Chat Core                   ✅ done
7. AI Provider abstraction     ✅ done
8. Mock AI Provider            ✅ done
9. Audit events                ✅ done
10. Admin Audit API            ✅ done
11. Usage tracking             ✅ done
12. Admin Usage Summary API    ✅ done
13. Frontend MVP               ✅ done
14. Frontend verification docs ✅ done
15. Rate limits                ➡️ next
16. Real AI provider           pending
17. Provider configuration     pending
18. Documents upload           pending
19. RAG                        pending
20. Admin dashboards           pending
```

---

## 12. Текущие команды запуска

### 12.1. Infrastructure

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose up -d postgres redis
```

Проверка:

```bat
docker ps
```

---

### 12.2. Backend

```bat
cd /d "D:\Java projects\Safeai-desk\backend"

set SAFEAI_JWT_SECRET=safeai-local-development-secret-key-change-this-value-please-123456789
set SAFEAI_JWT_EXPIRATION_MINUTES=60

mvnw.cmd spring-boot:run
```

Проверка:

```bat
curl -i http://localhost:8080/actuator/health
```

Ожидаемо:

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

---

### 12.3. Frontend

```bat
cd /d "D:\Java projects\Safeai-desk\frontend"
npm run dev
```

Открыть:

```text
http://localhost:5173
```

---

## 13. Что проверять перед коммитом

### Backend tests

```bat
cd /d "D:\Java projects\Safeai-desk\backend"
mvnw.cmd clean test
```

Ожидаемо:

```text
BUILD SUCCESS
```

### Frontend build

```bat
cd /d "D:\Java projects\Safeai-desk\frontend"
npm run build
```

Ожидаемо:

```text
built in ...
```

### Git status

```bat
cd /d "D:\Java projects\Safeai-desk"
git status
```

Не должны попадать в Git:

```text
frontend/node_modules/
frontend/dist/
backend/target/
.env
.idea/
```

---

## 14. Формулировка для собеседования

```text
Я делаю backend-first MVP корпоративного AI Gateway, который уже довёл до рабочего full-stack MVP. На backend реализованы организации, пользователи, роли, JWT-авторизация, чат, абстракция AI-провайдера, mock provider, аудит действий и учет usage по токенам/стоимости. Чат не зависит от конкретного AI-провайдера: он работает через интерфейс AiProvider.

Для админа реализованы audit и usage endpoints. Usage данные сохраняются в chat_messages и агрегируются по пользователю и модели. Также добавлен React + TypeScript frontend на Vite: login, chat, admin users, admin audit и admin usage. Сейчас следующий этап — rate limiting через Redis, затем подключение реального AI-провайдера и RAG.
```

---

## 15. Критерий текущей готовности MVP

Текущий MVP считается рабочим, если:

```text
✅ PostgreSQL и Redis запускаются через Docker Compose
✅ Backend стартует на localhost:8080
✅ /actuator/health возвращает UP
✅ Login работает через admin@test.com / admin123
✅ JWT сохраняется на frontend
✅ Frontend стартует на localhost:5173
✅ /chat открывается после login
✅ Можно создать чат
✅ Можно отправить сообщение
✅ MockAiProvider возвращает ответ
✅ Сообщения сохраняются в БД
✅ Usage-поля сохраняются в chat_messages
✅ Audit events пишутся
✅ /admin/users отображает пользователей
✅ /admin/audit отображает события
✅ /admin/usage отображает usage summary
✅ npm run build проходит
✅ mvnw.cmd clean test проходит
```

Name: SafeAI PostgreSQL Local
Host: localhost
Port: 5432
User: safeai
Password: safeai_password
Database: safeai