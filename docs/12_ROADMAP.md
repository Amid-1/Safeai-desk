# SafeAI Desk — Roadmap

## 1. Цель проекта

SafeAI Desk — это корпоративный AI Gateway: внутренняя прослойка между сотрудниками компании и внешними AI-провайдерами.

Главная идея:

```text
Employees
  ↓
SafeAI Desk
  ↓
Auth / Roles / Audit / Usage / Limits / Provider Switch / Future RAG
  ↓
AI Providers
```

Проект нужен, чтобы организация могла:

```text
- контролировать доступ пользователей к AI;
- хранить историю чатов;
- видеть аудит действий;
- считать usage по токенам и стоимости;
- переключать AI-провайдеров;
- ограничивать потребление через лимиты;
- позже подключить документы и RAG.
```

---

## 2. Текущий статус

Проект уже доведен до рабочего full-stack MVP.

Сейчас реально работает сценарий:

```text
Login
  ↓
JWT token
  ↓
React frontend
  ↓
Create chat
  ↓
Send message
  ↓
AiProvider
  ↓
MockAiProvider / future OpenAI / future Anthropic
  ↓
Save USER and ASSISTANT messages
  ↓
Save usage fields
  ↓
Write audit events
  ↓
Admin checks Users / Audit / Usage
```

Текущая рабочая связка:

```text
Frontend: http://localhost:5173
Backend:  http://localhost:8080
Database: PostgreSQL localhost:5432/safeai
Redis:    localhost:6379
```

---

## 3. Что уже сделано

### Infrastructure

```text
✅ Docker Compose
✅ PostgreSQL 16
✅ Redis 7
✅ Local development mode
✅ Flyway migrations
✅ PostgreSQL volume-based persistence
```

### Backend foundation

```text
✅ Java 21
✅ Spring Boot 4
✅ Spring Web MVC
✅ Spring Security
✅ OAuth2 Resource Server JWT
✅ Spring Data JPA
✅ Flyway
✅ Maven
✅ Centralized JSON error responses
✅ Stateless security
```

### Database

```text
✅ organizations
✅ users
✅ roles
✅ user_roles
✅ chat_sessions
✅ chat_messages
✅ audit_events
✅ usage fields in chat_messages
✅ jsonb details for audit events
✅ timestamptz migration for created_at fields
✅ indexes for common query paths
✅ unique organization name index
```

### Auth and security

```text
✅ BCrypt password hashing
✅ POST /api/auth/login
✅ GET /api/auth/me
✅ JWT generation
✅ JWT decoding
✅ SafeAiUserPrincipal
✅ ADMIN / USER roles
✅ Protected endpoints
✅ JSON 401 response
✅ JSON 403 response
✅ Method security tests
```

### Organization and user modules

```text
✅ Organization API
✅ User API
✅ Create organization
✅ Create user
✅ List users
✅ List organizations
✅ Role assignment
✅ Duplicate email protection
✅ Missing organization validation
```

### Chat module

```text
✅ Chat sessions
✅ Chat messages
✅ Create chat
✅ List chats
✅ Get chat details
✅ Send message
✅ Save USER message
✅ Save ASSISTANT message
✅ Split persistence around external AI call
```

### AI module

```text
✅ AiProvider interface
✅ AiChatRequest
✅ AiChatResponse
✅ AiMessage
✅ MockAiProvider
✅ AiProviderSupport
✅ OpenAiProvider scaffold
✅ AnthropicProvider scaffold
✅ Configurable provider switch through environment variables
```

Важно: `MockAiProvider` проверен локально. `OpenAiProvider` и `AnthropicProvider` уже заложены архитектурно, но live-проверка требует реальных API keys.

### Audit module

```text
✅ AuditEventEntity
✅ AuditEventService
✅ AuditEventQueryService
✅ Admin audit endpoints
✅ USER_LOGIN_SUCCESS
✅ USER_LOGIN_FAILED
✅ CHAT_CREATED
✅ CHAT_MESSAGE_SENT
✅ AI_RESPONSE_RECEIVED
```

### Usage module

```text
✅ model
✅ inputTokens
✅ outputTokens
✅ totalTokens
✅ costUsd
✅ usage by user and model
✅ usage by user
✅ usage by model
✅ daily usage
✅ usage by userId
✅ usage by organizationId
```

### Frontend MVP

```text
✅ React
✅ TypeScript
✅ Vite
✅ React Router
✅ Fetch API layer
✅ JWT storage in localStorage
✅ Login page
✅ Chat page
✅ Admin Users page
✅ Admin Audit page
✅ Admin Usage page
✅ Logout
✅ Protected routes
✅ Token cleanup on 401
```

---

## 4. Реализованные backend endpoints

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
GET /api/admin/usage/summary
GET /api/admin/usage/users
GET /api/admin/usage/models
GET /api/admin/usage/daily
GET /api/admin/usage/by-user/{userId}
GET /api/admin/usage/by-organization/{organizationId}
```

`/api/admin/usage-summary` оставлен как совместимый старый endpoint. Новый основной endpoint:

```text
GET /api/admin/usage/summary
```

---

## 5. Frontend pages

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
/login
  ↓
admin@test.com / admin123
  ↓
/chat
  ↓
Create chat
  ↓
Send message
  ↓
Mock AI response
  ↓
/admin/users
/admin/audit
/admin/usage
```

---

## 6. Текущая архитектурная схема

```mermaid
flowchart LR
    U[User in browser] --> FE[React + TypeScript Frontend]
    FE -->|Bearer JWT| BE[Spring Boot Backend]

    BE --> AUTH[Auth / JWT / Security]
    BE --> CHAT[Chat Module]
    BE --> USERS[Users / Organizations]
    BE --> AUDIT[Audit Module]
    BE --> USAGE[Usage Queries]
    BE --> AI[AiProvider Interface]

    AI --> MOCK[MockAiProvider]
    AI -. configurable .-> OPENAI[OpenAiProvider]
    AI -. configurable .-> ANTHROPIC[AnthropicProvider]

    BE --> PG[(PostgreSQL)]
    BE --> REDIS[(Redis)]
```

---

## 7. Auth flow

```mermaid
sequenceDiagram
    participant Browser
    participant Frontend
    participant Backend
    participant DB as PostgreSQL

    Browser->>Frontend: Open /login
    Frontend->>Backend: POST /api/auth/login
    Backend->>DB: find user by email
    DB-->>Backend: user + roles
    Backend->>Backend: BCrypt password check
    Backend->>Backend: generate JWT
    Backend->>DB: write USER_LOGIN_SUCCESS audit event
    Backend-->>Frontend: token + user
    Frontend->>Frontend: save token to localStorage
    Frontend-->>Browser: navigate /chat
```

---

## 8. Chat message flow

```mermaid
sequenceDiagram
    participant Frontend
    participant ChatController
    participant ChatService
    participant Persistence as ChatPersistenceService
    participant Provider as AiProvider
    participant DB as PostgreSQL
    participant Audit as AuditEventService

    Frontend->>ChatController: POST /api/chats/{id}/messages
    ChatController->>ChatService: sendMessage(chatId, request, currentUser)

    ChatService->>Persistence: saveUserMessageAndPrepareAiRequest
    Persistence->>DB: load chat session
    Persistence->>DB: load message history
    Persistence->>DB: save USER message
    Persistence->>Audit: CHAT_MESSAGE_SENT

    ChatService->>Provider: sendMessage(aiRequest)
    Provider-->>ChatService: AiChatResponse

    ChatService->>Persistence: saveAssistantMessageAndReturnChat
    Persistence->>DB: save ASSISTANT message with usage
    Persistence->>Audit: AI_RESPONSE_RECEIVED
    Persistence->>DB: load full chat
    Persistence-->>ChatService: ChatDetailsResponse
    ChatService-->>ChatController: ChatDetailsResponse
    ChatController-->>Frontend: updated chat
```

---

## 9. Database high-level schema

```mermaid
erDiagram
    ORGANIZATIONS ||--o{ USERS : has
    USERS ||--o{ CHAT_SESSIONS : owns
    CHAT_SESSIONS ||--o{ CHAT_MESSAGES : contains
    USERS ||--o{ AUDIT_EVENTS : creates
    USERS ||--o{ USER_ROLES : has
    ROLES ||--o{ USER_ROLES : assigned

    ORGANIZATIONS {
        uuid id PK
        varchar name
        timestamptz created_at
    }

    USERS {
        uuid id PK
        uuid organization_id FK
        varchar email
        varchar password_hash
        varchar full_name
        boolean enabled
        timestamptz created_at
    }

    ROLES {
        uuid id PK
        varchar name
    }

    USER_ROLES {
        uuid user_id FK
        uuid role_id FK
    }

    CHAT_SESSIONS {
        uuid id PK
        uuid user_id FK
        varchar title
        timestamptz created_at
    }

    CHAT_MESSAGES {
        uuid id PK
        uuid session_id FK
        varchar role
        text content
        varchar model
        int input_tokens
        int output_tokens
        numeric cost_usd
        timestamptz created_at
    }

    AUDIT_EVENTS {
        uuid id PK
        uuid user_id FK
        varchar event_type
        jsonb details
        timestamptz created_at
    }
```

---

## 10. Текущий рабочий сценарий MVP

### 10.1 Login

```text
User opens /login
  ↓
enters admin@test.com / admin123
  ↓
frontend calls POST /api/auth/login
  ↓
backend returns JWT
  ↓
frontend stores token in localStorage.safeai_token
  ↓
user is redirected to /chat
```

### 10.2 Chat

```text
User clicks Create chat
  ↓
frontend calls POST /api/chats
  ↓
backend creates chat session
  ↓
audit event CHAT_CREATED is written
```

```text
User sends message
  ↓
frontend calls POST /api/chats/{id}/messages
  ↓
backend saves USER message
  ↓
backend calls AiProvider
  ↓
MockAiProvider returns response
  ↓
backend saves ASSISTANT message
  ↓
usage fields are saved
  ↓
audit events are written
```

### 10.3 Admin Audit

Expected events after login + chat creation + message:

```text
USER_LOGIN_SUCCESS
CHAT_CREATED
CHAT_MESSAGE_SENT
AI_RESPONSE_RECEIVED
```

### 10.4 Admin Usage

Expected usage fields:

```text
userEmail
model
inputTokens
outputTokens
totalTokens
costUsd
```

Example:

```json
[
  {
    "userId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
    "userEmail": "admin@test.com",
    "model": "mock-safeai",
    "inputTokens": 26,
    "outputTokens": 61,
    "totalTokens": 87,
    "costUsd": 0.000000
  }
]
```

---

## 11. Текущие команды запуска

### 11.1 Infrastructure

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose up -d postgres redis
```

Проверка:

```bat
docker ps
```

Ожидаемые контейнеры:

```text
safeai-postgres
safeai-redis
```

### 11.2 Backend

```bat
cd /d "D:\Java projects\Safeai-desk\backend"

set SAFEAI_JWT_SECRET=safeai-local-development-secret-key-change-this-value-please-123456789
set SAFEAI_JWT_EXPIRATION_MINUTES=60
set SAFEAI_AI_PROVIDER=mock

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

### 11.3 Frontend

```bat
cd /d "D:\Java projects\Safeai-desk\frontend"
npm run dev
```

Открыть:

```text
http://localhost:5173/login
```

### 11.4 Frontend build

```bat
cd /d "D:\Java projects\Safeai-desk\frontend"
npm run build
```

Ожидаемо:

```text
✓ built in ...
```

---

## 12. Environment variables

### Local mock mode

```env
SAFEAI_JWT_SECRET=safeai-local-development-secret-key-change-this-value-please-123456789
SAFEAI_JWT_EXPIRATION_MINUTES=60

SAFEAI_AI_PROVIDER=mock

OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_API_KEY=
OPENAI_MODEL=gpt-4.1

ANTHROPIC_BASE_URL=https://api.anthropic.com/v1
ANTHROPIC_API_KEY=
ANTHROPIC_MODEL=claude-opus-4-8
ANTHROPIC_VERSION=2023-06-01
ANTHROPIC_MAX_TOKENS=1024
```

### OpenAI mode

```env
SAFEAI_AI_PROVIDER=openai
OPENAI_API_KEY=sk-...
OPENAI_MODEL=gpt-4.1
```

### Anthropic mode

```env
SAFEAI_AI_PROVIDER=anthropic
ANTHROPIC_API_KEY=sk-ant-...
ANTHROPIC_MODEL=claude-opus-4-8
ANTHROPIC_MAX_TOKENS=1024
```

---

## 13. Проверки перед коммитом

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
✓ built in ...
```

### Manual smoke test

```text
1. Docker: postgres + redis running
2. Backend: localhost:8080
3. Frontend: localhost:5173
4. Login: admin@test.com / admin123
5. Create chat
6. Send message
7. Check /admin/audit
8. Check /admin/usage
9. Logout
10. Open /chat without token → redirect to /login
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
backend/.env
.env
.idea/
API keys
production secrets
temporary files
```

---

## 14. Критерий готовности текущего MVP

Текущий MVP считается рабочим, если:

```text
✅ PostgreSQL и Redis запускаются через Docker Compose
✅ Backend стартует на localhost:8080
✅ /actuator/health возвращает UP
✅ Login работает через admin@test.com / admin123
✅ JWT сохраняется на frontend
✅ Frontend стартует на localhost:5173
✅ /chat защищен от неавторизованного доступа
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

---

## 15. Что дальше

### Next stage: Rate limits через Redis

Это следующий логичный этап.

Причины:

```text
- Redis уже есть в infrastructure;
- JWT уже содержит userId и organizationId;
- /api/chats/{id}/messages — точка потребления AI;
- usage уже считается;
- можно ограничивать количество AI-запросов до вызова AI provider.
```

Минимальная цель:

```text
Ограничить количество AI-запросов пользователя за период времени.
```

Первый вариант правила:

```text
USER: 20 AI-сообщений в час
ADMIN: 100 AI-сообщений в час или без ограничения
```

Минимальные backend-задачи:

```text
1. Добавить RateLimitProperties.
2. Добавить RedisRateLimitService.
3. Добавить RateLimitExceededException.
4. Подключить проверку перед вызовом AiProvider.
5. При превышении лимита возвращать HTTP 429.
6. Писать audit event RATE_LIMIT_EXCEEDED.
7. Добавить unit tests и controller/security tests.
```

Возможные будущие endpoints:

```text
GET /api/admin/limits
PUT /api/admin/limits
GET /api/admin/limits/usage
```

На первом этапе UI не обязателен.

---

## 16. После Rate Limits

Рекомендуемый порядок:

```text
1. Rate limits through Redis                         next
2. Live OpenAI provider verification                 pending
3. Live Anthropic provider verification              pending
4. Provider error handling and retries               pending
5. Provider timeout configuration                    pending
6. Better frontend error rendering                   pending
7. Admin usage charts                                pending
8. User management actions: enable/disable, roles    pending
9. Document upload                                   pending
10. RAG indexing                                     pending
11. RAG retrieval                                    pending
12. Production Docker profile                        pending
13. CI pipeline                                      pending
14. Deployment docs                                  pending
```

---

## 17. High-level roadmap

```text
1. Infrastructure                         ✅ done
2. Database + Flyway                      ✅ done
3. Organization API                       ✅ done
4. User API                               ✅ done
5. Auth/JWT                               ✅ done
6. Chat Core                              ✅ done
7. AI Provider abstraction                ✅ done
8. Mock AI Provider                       ✅ done
9. OpenAI provider scaffold               ✅ done
10. Anthropic provider scaffold           ✅ done
11. Audit events                          ✅ done
12. Admin Audit API                       ✅ done
13. Usage tracking                        ✅ done
14. Admin Usage APIs                      ✅ done
15. Common security hardening             ✅ done
16. React frontend MVP                    ✅ done
17. Protected frontend routes             ✅ done
18. Frontend build verification           ✅ done
19. Rate limits through Redis             ➡️ next
20. Live provider verification            pending
21. Documents upload                      pending
22. RAG                                   pending
23. Admin dashboards                      pending
24. Production deployment                 pending
```

---

## 18. Формулировка для собеседования

```text
Я делаю backend-first MVP корпоративного AI Gateway и уже довёл его до рабочего full-stack MVP. На backend реализованы организации, пользователи, роли, JWT-авторизация, чат, абстракция AI-провайдера, mock provider, заготовки OpenAI/Anthropic providers, аудит действий и учет usage по токенам/стоимости. Чат не зависит от конкретного AI-провайдера: он работает через интерфейс AiProvider.

Для админа реализованы endpoints для пользователей, audit events и usage-аналитики. Usage данные сохраняются в chat_messages и агрегируются по пользователю, модели и дням. Также сделан React + TypeScript frontend на Vite: login, chat, admin users, admin audit и admin usage. Сейчас следующий этап — rate limiting через Redis, затем live-проверка реальных AI-провайдеров и RAG.
```

---

## 19. Локальное подключение к PostgreSQL

```text
Name: SafeAI PostgreSQL Local
Host: localhost
Port: 5432
User: safeai
Password: safeai_password
Database: safeai
```
