# SafeAI Desk

**SafeAI Desk** — это production-oriented full-stack MVP корпоративного AI Gateway: внутренней AI-платформы для организаций с аутентификацией, ролевой моделью, multi-tenancy, audit logging, usage analytics, Redis rate limiting и подключаемыми AI providers.

Проект задуман как portfolio/interview-ready система, которая показывает не просто чат с AI, а полноценную backend-архитектуру вокруг корпоративного доступа к AI: безопасность, разграничение прав, учет расходов, аудит действий и подготовку к дальнейшему развитию в сторону RAG, политик безопасности и production deployment.

---

## 1. Цель проекта

SafeAI Desk — это не просто UI поверх AI API. Цель проекта — смоделировать инфраструктуру, которая нужна компании перед тем, как дать сотрудникам доступ к AI-инструментам.

Система отвечает на практические вопросы:

- кто имеет право пользоваться AI-ассистентом;
- к какой организации относится пользователь;
- кто может создавать и администрировать пользователей;
- кто может видеть usage и audit;
- сколько AI-запросов делает каждый пользователь;
- какая модель сколько потребила токенов;
- сколько потенциально стоит использование AI;
- какие действия выполняли администраторы;
- как отозвать старые JWT после смены ролей или сброса пароля;
- как защитить login от brute-force атак;
- как не дать администратору одной организации увидеть данные другой организации;
- как переключаться между mock, OpenAI и Anthropic providers.

---

## 2. Архитектура верхнего уровня

SafeAI Desk построен как модульный монолит с четкими границами пакетов.

```text
React Frontend
        |
        | HTTP / JSON
        v
Spring Boot Backend
        |
        |-- Auth / JWT / RBAC
        |-- User Management
        |-- Organization Management
        |-- Chat Management
        |-- AI Provider Abstraction
        |-- Audit Logging
        |-- Usage Analytics
        |-- Redis Rate Limiting
        |
        +--> PostgreSQL
        +--> Redis
        +--> External AI Provider
```

Текущий стек разработки и запуска:

```text
React + TypeScript + Vite
Spring Boot + Java 21
PostgreSQL
Redis
Docker Compose
Flyway
JWT
Spring Security
```

---

## 3. Технологический стек

### Backend

- Java 21
- Spring Boot
- Spring Web MVC
- Spring Security
- Spring OAuth2 Resource Server JWT
- Spring Data JPA / Hibernate
- PostgreSQL
- Flyway
- Redis
- Maven
- Lombok
- Bean Validation
- Docker / Docker Compose
- подготовленная база для Testcontainers

### Frontend

- React
- TypeScript
- Vite
- React Router
- общий Fetch API wrapper
- простая CSS-стилизация для MVP

### Infrastructure

- PostgreSQL container
- Redis container
- Backend Dockerfile
- Docker Compose для локальной разработки
- конфигурация через environment variables

---

## 4. Основные доменные сущности

Проект построен вокруг нескольких ключевых доменов:

```text
Organization
User
Role
ChatSession
ChatMessage
AuditEvent
UsageSummary
RateLimit
AIProvider
```

### Organization

`Organization` представляет tenant — отдельную организацию/компанию.

Каждый пользователь относится ровно к одной организации.

Текущая модель:

```text
id
name
createdAt
version
```

```text
organization.enabled изменился
↓
публикуем OrganizationSecurityStateChangedEvent
↓
после commit находим всех пользователей организации
↓
удаляем их user-status cache из Redis
↓
следующий запрос пользователя пойдет в БД и увидит organization.enabled = false
```

Граница организации используется для:

- ограничения видимости пользователей;
- фильтрации audit events;
- фильтрации usage analytics;
- будущих organization-level quotas и budgets.

### User

Пользователь принадлежит организации и имеет одну или несколько ролей.

Текущая модель:

```text
id
organization
email
passwordHash
fullName
enabled
createdAt
tokenVersion
version
roles
```

Важные security-поля:

- `enabled` — активна ли учетная запись;
- `tokenVersion` — версия security-состояния пользователя для отзыва старых JWT;
- `version` — optimistic locking.

### Role

Поддерживаемые роли:

```text
SUPER_ADMIN
ADMIN
USER
```

Семантика ролей:

```text
SUPER_ADMIN:
- platform/global scope
- может создавать организации
- видит global usage
- видит global audit
- может управлять пользователями в любых организациях

ADMIN:
- organization scope
- управляет пользователями только своей организации
- видит audit и usage только своей организации

USER:
- обычный пользователь
- может использовать chat
- имеет доступ только к собственным чатам
```

```text
### SUPER_ADMIN lifecycle

Пользователи с ролью SUPER_ADMIN не изменяются через обычные `/api/users/**` endpoints.

Обычный user-management flow предназначен для ролей:

- USER
- ADMIN

Создание, изменение и удаление SUPER_ADMIN должно выполняться отдельно:
через seed/Flyway, административную консоль платформы или отдельный platform-admin endpoint.

### ChatSession

Сессия чата, принадлежащая конкретному пользователю.
```

```text
id
user
title
createdAt
```

### ChatMessage

Сообщение в чате.

```text
id
session
role
content
model
inputTokens
outputTokens
costUsd
createdAt
```

Поддерживаемые роли сообщений:

```text
USER
ASSISTANT
SYSTEM
```

### AuditEvent

Событие аудита.

```text
id
user
organizationId
eventType
details
createdAt
```

`organizationId` хранится прямо в audit event, чтобы можно было безопасно и быстро фильтровать события по tenant-границе.

---

## 5. Security Architecture

Безопасность — один из центральных элементов проекта.

Backend использует stateless JWT authentication на Spring Security.

### Auth / CSRF
```text
Frontend использует cookie-based authentication.

Перед небезопасными методами `POST`, `PUT`, `PATCH`, `DELETE` frontend должен получить CSRF token через:

GET /api/auth/csrf

После этого значение token нужно отправлять в header:

X-XSRF-TOKEN: <token>

Endpoint `POST /api/auth/logout` доступен без авторизации на уровне permitAll, но остается под CSRF-защитой. 
Поэтому logout также должен отправляться с `X-XSRF-TOKEN`.

Authorization Bearer header поддерживается дополнительно для API-клиентов и тестов. Основной browser
 flow использует cookies.
 ```

```text
JWT uses HS256.

In production SAFEAI_JWT_SECRET must be provided through environment variables
or external secret manager. It must not be committed to git.

Minimum secret length: 32 bytes.
 ```

```text
Frontend browser flow uses cookie authentication.

Access token is stored in HttpOnly cookie:

access_token

Authorization Bearer header is also supported for API clients,
integration tests, Postman and non-browser consumers.

When both Authorization header and access_token cookie are present,
Authorization header has priority.
 ```

```text
Итоговый refresh flow

POST /api/auth/refresh
↓
read refresh_token from HttpOnly cookie
↓
hash refresh_token
↓
find token by hash with PESSIMISTIC_WRITE lock
↓
if not found:
    clear cookies
    401 INVALID_REFRESH_TOKEN
↓
if expired:
    mark revoked_at
    clear cookies
    401 EXPIRED_REFRESH_TOKEN
↓
if revoked:
    revoke all active tokens in same token_family_id
    audit SECURITY_REFRESH_REUSE_DETECTED
    clear cookies
    401 INVALID_REFRESH_TOKEN
↓
mark old token:
    last_used_at = now
    revoked_at = now
↓
create new refresh token:
    same token_family_id
    new token_hash
    new expires_at
    created_by_ip
    user_agent
↓
old.replaced_by_token_id = new.id
↓
issue new access token
↓
set access_token HttpOnly cookie
↓
set refresh_token HttpOnly cookie
 ```



### Login Flow

```text
1. Пользователь отправляет email/password на /api/auth/login
2. Проверяется login rate limit по email и IP
3. AuthenticationManager проверяет credentials
4. CustomUserDetailsService загружает пользователя, роли, organization и tokenVersion
5. JwtService генерирует JWT
6. Успешный login пишется в audit
7. Токен возвращается frontend-у
```

### JWT Claims

JWT содержит:

```text
sub
email
userId
organizationId
roles
tokenVersion
jti
iat
exp
iss
```

```text
Login:
POST /api/auth/login
backend проверяет пароль
backend ставит access_token cookie
backend ставит refresh_token cookie
frontend НЕ получает JWT в body и НЕ кладет его в localStorage

Authenticated requests:
frontend делает fetch(..., { credentials: "include" })
браузер сам прикладывает cookie
backend достает access token из cookie через BearerTokenResolver

Refresh:
POST /api/auth/refresh
backend читает refresh_token cookie
выдает новый access_token cookie

Logout:
POST /api/auth/logout
backend удаляет cookies
backend отзывает refresh token в БД
```

```text
Prodaction:


refresh-token rotation
revokedByIp / revokedReason
device/session management
отдельный UnauthorizedException вместо ResourceNotFoundException для refresh
тесты на ClientIpResolver с X-Forwarded-For spoofing
```


```text
Вариант 1 — frontend через Authorization header
fetch("/api/chats", {
headers: {
Authorization: `Bearer ${token}`,
"Content-Type": "application/json",
},
});
Вариант 2 — frontend через cookie
fetch("/api/chats", {
credentials: "include",
headers: {
"Content-Type": "application/json",
},
});

access token в HttpOnly Secure SameSite cookie
refresh token в HttpOnly Secure SameSite cookie
CSRF protection для unsafe methods
frontend не хранит JWT в localStorage
BearerTokenResolver из cookie — нормально
```

```text
POST /api/auth/logout не требует авторизации на уровне Spring Security,
но остается под CSRF-защитой.

Перед logout frontend должен получить CSRF token через:

GET /api/auth/csrf

И отправить его в header:

X-XSRF-TOKEN: <token>

Иначе logout может вернуть 403 Forbidden.

```


```text
access_token  -> HttpOnly Secure SameSite cookie
refresh_token -> HttpOnly Secure SameSite cookie
XSRF-TOKEN    -> НЕ HttpOnly cookie, только для чтения frontend-ом
frontend      -> отправляет X-XSRF-TOKEN header на POST/PATCH/PUT/DELETE
backend       -> включает csrf(), а не disable()

access_token  -> HttpOnly=true
refresh_token -> HttpOnly=true
XSRF-TOKEN    -> HttpOnly=false, но его лучше создает Spring CSRF, а AuthCookieService только очищает
secure        -> через application.yml/env
sameSite      -> через application.yml/env
maxAge        -> через Duration, не руками в секундах
```


Backend не полагается только на email. В токене явно присутствуют `userId`, `organizationId`, `roles` и `tokenVersion`.

### Token Revocation через tokenVersion

В проекте используется `tokenVersion` для инвалидирования старых JWT.

Когда меняется критичное security-состояние пользователя, backend увеличивает `tokenVersion`.

Примеры:

```text
user disabled
user roles changed
password reset
force logout scenario
```

На каждом authenticated request `UserStatusFilter` проверяет:

```text
user.enabled == true
AND current tokenVersion == latest tokenVersion
```

Если токен устарел, backend возвращает:

```text
401 TOKEN_REVOKED
```

### User Status Cache

Чтобы не ходить в PostgreSQL на каждый запрос, security-состояние пользователя кешируется в Redis.

Кешируемые значения:

```text
enabled
tokenVersion
```

Инвалидация кеша event-driven:

```text
UserService меняет security-состояние
        |
        v
UserSecurityStateChangedEvent
        |
        v
TransactionalEventListener(AFTER_COMMIT)
        |
        v
UserStatusCacheService.evict(userId)
```

Кеш очищается только после успешного commit транзакции.

### RBAC Boundaries

В системе два слоя authorization.

#### 1. Route-level security

Настроено в `SecurityConfig`.

Примеры:

```text
/api/auth/login            public
/api/auth/me               authenticated
/api/chats/**              authenticated
/api/users/**              ADMIN or SUPER_ADMIN
/api/admin/**              ADMIN or SUPER_ADMIN
POST /api/organizations    SUPER_ADMIN
```

#### 2. Service-level tenant isolation

Бизнес-логика дополнительно проверяет tenant boundaries.

Примеры:

```text
ADMIN может управлять пользователями только своей организации
ADMIN может видеть audit только своей организации
ADMIN может видеть usage только своей организации
USER может работать только со своими чатами
SUPER_ADMIN имеет global/platform scope
```

Это важно, потому что одной проверки ролей на уровне route недостаточно для multi-tenant системы.

---

## 6. Multi-Tenancy Model

SafeAI Desk использует organization-based multi-tenancy.

Текущая модель — application-level tenant isolation, а не PostgreSQL Row Level Security.

Основные правила:

```text
SUPER_ADMIN:
- global/platform visibility

ADMIN:
- organization-scoped visibility

USER:
- own resources only
```

Tenant isolation реализована для:

- списка пользователей;
- просмотра пользователя;
- создания пользователя;
- изменения ролей пользователя;
- включения/отключения пользователя;
- сброса пароля;
- audit queries;
- usage analytics;
- доступа к чатам;
- отправки сообщений.

---

## 7. Rate Limiting

В проекте используется Redis-backed fixed-window rate limiting.

### Login Rate Limit

Login защищен двумя счетчиками:

```text
email-based limit
IP-based limit
```

Назначение:

```text
email limit защищает конкретный аккаунт
IP limit защищает систему от массового перебора
```

Ключи хэшируются перед записью в Redis.

Пример:

```text
rate-limit:login:email:{sha256(email)}
rate-limit:login:ip:{sha256(ip)}
```

### AI Message Rate Limit

AI-сообщения ограничиваются на уровне пользователя.

Текущая логика:

```text
USER        -> меньший hourly limit
ADMIN       -> больший hourly limit
SUPER_ADMIN -> пока обрабатывается как admin-level
```

Превышение лимита публикует событие и записывается в audit.

### Redis Lua Script

Limiter использует Lua script для атомарного `INCR + TTL`.

Это лучше, чем раздельные команды:

```text
INCR
EXPIRE
```

Потому что между ними может произойти сбой.

---

## 8. Audit Logging

Audit реализован отдельным сервисом.

Текущие event types:

```text
USER_LOGIN_SUCCESS
USER_LOGIN_FAILED

CHAT_CREATED
CHAT_MESSAGE_SENT
AI_RESPONSE_RECEIVED
AI_RESPONSE_FAILED

USER_CREATED
ORGANIZATION_CREATED

USER_ENABLED_CHANGED
USER_ROLES_CHANGED
USER_PASSWORD_RESET

RATE_LIMIT_EXCEEDED
```

### Принципы audit

```text
1. Audit не должен хранить полный prompt content.
2. Audit должен содержать organizationId там, где это возможно.
3. Ошибка записи audit обычно не должна ломать core business flow.
4. ADMIN не должен видеть audit других организаций.
5. SUPER_ADMIN может видеть global audit.
```

Пример details для сообщения в чат:

```json
{
  "chatId": "...",
  "messageId": "...",
  "messageLength": 120
}
```

Текст prompt не сохраняется в audit details.

---

## 9. Chat Flow

Chat module отвечает за:

- создание chat sessions;
- список чатов пользователя;
- загрузку деталей чата;
- отправку user messages;
- подготовку AI request context;
- сохранение assistant responses;
- сохранение usage data;
- запись audit events.

### Send Message Flow

```text
1. User отправляет message на /api/chats/{id}/messages
2. Backend валидирует тело запроса
3. Backend проверяет ownership чата
4. Backend проверяет AI rate limit
5. Backend сохраняет USER message
6. Backend загружает recent chat history
7. Backend вызывает AiProvider вне DB-транзакции
8. Backend сохраняет ASSISTANT message
9. Backend записывает usage и audit event
10. Backend возвращает обновленные chat details
```

Ключевое архитектурное решение: внешний AI request не выполняется внутри длинной DB-транзакции.

Это не держит PostgreSQL connection, пока backend ожидает внешний AI provider.

### Chat Ownership

Пользователь имеет доступ только к собственным чатам.

Это обеспечивается repository methods:

```text
findByIdAndUser_Id(...)
existsByIdAndUser_Id(...)
```

---

## 10. AI Provider Abstraction

SafeAI Desk использует provider abstraction:

```java
public interface AiProvider {
    AiChatResponse sendMessage(AiChatRequest request);
}
```

Текущие implementations:

```text
MockAiProvider
OpenAiProvider
AnthropicProvider
```

### Mock Provider

Используется для local development, тестов, демо и offline-режима.

Преимущества:

- не нужен внешний API key;
- нет расходов;
- можно тестировать chat/audit/usage flow;
- удобно для portfolio demo.

### OpenAI Provider

Реализован как scaffold реальной integration.

Задачи provider:

- собрать request payload;
- отправить запрос в OpenAI API;
- распарсить output text;
- распарсить usage tokens;
- преобразовать ошибки provider в application exceptions.

### Anthropic Provider

Реализован как scaffold реальной integration.

Задачи provider:

- собрать Anthropic Messages API payload;
- нормализовать roles;
- распарсить content blocks;
- распарсить usage tokens;
- преобразовать ошибки provider в application exceptions.

### AI Error Mapping

Ошибки AI provider преобразуются в стабильные API errors:

```text
AiProviderTimeoutException -> 504 AI_PROVIDER_TIMEOUT
AiProviderException        -> 502 AI_PROVIDER_ERROR
```

---

## 11. Usage Analytics

Usage analytics считаются по assistant messages.

Система отслеживает:

```text
model
inputTokens
outputTokens
totalTokens
costUsd
user
organization
date
```

Текущие admin usage views:

```text
usage summary
usage by users
usage by models
daily usage
usage by user
usage by organization
```

Scope rules:

```text
SUPER_ADMIN -> global usage
ADMIN       -> organization-scoped usage
```

Usage analytics сейчас вычисляются из таблицы chat messages через aggregation queries.

---

## 12. User Management

Admin users могут управлять пользователями.

Реализованные операции:

```text
create user
list users
get user by id
enable / disable user
change user roles
reset user password
```

Важные protections:

```text
ADMIN не может создать пользователя в другой организации
ADMIN не может видеть пользователей другой организации
ADMIN не может отключить самого себя
ADMIN не может удалить/отключить последнего активного ADMIN организации
role changes increment tokenVersion
password reset increments tokenVersion
enabled/disabled change increments tokenVersion
```

Обычный user-management endpoint не разрешает назначать `SUPER_ADMIN`.

`SUPER_ADMIN` создается через seed/migration или отдельный platform-admin flow.

---

## 13. Organization Management

Organization management является platform-scoped.

Реализованные операции:

```text
SUPER_ADMIN может создать organization
SUPER_ADMIN может видеть все organizations
ADMIN может видеть только свою organization
SUPER_ADMIN может получить любую organization by id
ADMIN может получить только свою organization by id
```

Organizations — основа tenant isolation.

---

## 14. Frontend Overview

Frontend предоставляет минимальный, но рабочий UI.

Страницы:

```text
LoginPage
ChatPage
AdminUsersPage
AdminAuditPage
AdminUsagePage
```

### Login Page

Позволяет залогиниться и сохраняет JWT token в localStorage.

### Chat Page

Поддерживает:

- создание чата;
- список чатов;
- открытие чата;
- отправку сообщений;
- отображение assistant response metadata.

### Admin Users Page

Поддерживает:

- список пользователей;
- создание пользователя;
- фильтр по роли;
- enable/disable пользователя;
- изменение роли;
- reset password;
- визуальную защиту platform admins от обычных admin-actions.

### Admin Audit Page

Поддерживает:

- paginated audit events;
- отображение event type;
- отображение user email;
- отображение JSON details.

### Admin Usage Page

Сейчас поддерживает:

- usage summary by user and model.

Планируемые улучшения:

- вкладки users/models/daily;
- filters;
- charts;
- date range.

---

## 15. API Overview

### Auth

```http
POST /api/auth/login
GET  /api/auth/me
```

### Chats

```http
POST /api/chats
GET  /api/chats
GET  /api/chats/{id}
POST /api/chats/{id}/messages
```

### Users

```http
POST  /api/users
GET   /api/users
GET   /api/users/{id}
PATCH /api/users/{id}/enabled
PATCH /api/users/{id}/roles
POST  /api/users/{id}/reset-password
```

### Organizations

```http
POST /api/organizations
GET  /api/organizations
GET  /api/organizations/{id}
```

### Admin Audit

```http
GET /api/admin/audit-events
GET /api/admin/audit-events/users/{userId}
```

### Admin Usage

```http
GET /api/admin/usage/summary
GET /api/admin/usage/users
GET /api/admin/usage/models
GET /api/admin/usage/daily
GET /api/admin/usage/by-user/{userId}
GET /api/admin/usage/by-organization/{organizationId}
```

---

## 16. Error Response Format

Backend возвращает единый формат ошибок:

```json
{
  "timestamp": "2026-01-01T12:00:00Z",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "Ошибка валидации запроса",
  "path": "/api/users",
  "requestId": "abc-123",
  "fieldErrors": {
    "email": [
      "must be a well-formed email address"
    ]
  }
}
```

Поддерживаемые категории ошибок:

```text
NOT_FOUND
CONFLICT
FORBIDDEN
VALIDATION_ERROR
BAD_REQUEST
UNAUTHORIZED
RATE_LIMIT_EXCEEDED
RATE_LIMIT_UNAVAILABLE
AI_PROVIDER_TIMEOUT
AI_PROVIDER_ERROR
INTERNAL_SERVER_ERROR
```

---

## 17. Request ID

Backend поддерживает request correlation через `X-Request-Id`.

Поведение:

```text
если клиент передал валидный X-Request-Id -> использовать его
иначе -> сгенерировать UUID
```

Request id:

- сохраняется в request attribute;
- добавляется в response header;
- добавляется в MDC logging context;
- включается в API error response.

Это помогает связывать frontend errors с backend logs.

---

## 18. Database Migrations

Backend использует Flyway.

Задачи миграций:

```text
initial schema
seed roles
seed demo admin
created_at timestamptz conversion
indexes
unique organization name
user tokenVersion/version
audit event type index
SUPER_ADMIN role
case-insensitive user email index
organization version
platform super admin
audit event organization_id
chat message check constraints
super admin password fix
email unique constraint cleanup
```

Принципы:

```text
1. Уже примененные миграции не редактируются.
2. Новые изменения схемы делаются новыми versioned migrations.
3. Hibernate ddl-auto должен валидировать схему, а не создавать ее.
4. Критичные constraints должны жить в PostgreSQL, а не только в Java.
```

---

## 19. Local Development

### Необходимые инструменты

```text
Java 21
Maven
Node.js
npm
Docker
Docker Compose
```

### Запуск infrastructure

```bash
docker compose up -d postgres redis
```

### Запуск backend

```bash
cd backend
mvn spring-boot:run
```

### Запуск frontend

```bash
cd frontend
npm install
npm run dev
```

### Default local URLs

```text
Frontend: http://localhost:5173
Backend:  http://localhost:8080
Postgres: localhost:5432
Redis:    localhost:6379
```

---

## 20. Demo Accounts

Примеры local/demo аккаунтов:

```text
admin@test.com
superadmin@test.com
```

Фактический пароль зависит от BCrypt hash, записанного в Flyway migrations.

Demo credentials не должны использоваться в production.

---

## 21. Configuration

Приложение конфигурируется через environment variables и `application.yml`.

Основные группы конфигурации:

```text
Database
Redis
JWT
CORS
Rate limits
AI provider
OpenAI settings
Anthropic settings
User status cache
```

Пример выбора AI provider:

```text
safeai.ai.provider=mock
safeai.ai.provider=openai
safeai.ai.provider=anthropic
```
---

## 24. Interview Positioning

SafeAI Desk можно презентовать так:

> Production-oriented full-stack MVP корпоративного AI Gateway с multi-tenancy, RBAC, audit logging, usage analytics, Redis rate limits, JWT token invalidation и подключаемыми AI providers.

Ключевые engineering decisions:

```text
1. JWT содержит userId, organizationId, roles и tokenVersion.
2. tokenVersion инвалидирует старые токены после изменения ролей/пароля/account state.
3. UserStatusFilter проверяет enabled/tokenVersion на каждом authenticated request.
4. Redis кеширует user security status с after-commit invalidation.
5. ADMIN organization-scoped, SUPER_ADMIN platform-scoped.
6. Audit events содержат organizationId для tenant-safe audit queries.
7. Usage analytics scoped by organization для ADMIN.
8. Chat ownership проверяется до rate limit и message processing.
9. External AI calls выполняются вне длинных database transactions.
10. AiProvider interface позволяет переключать mock/OpenAI/Anthropic.
```

---


Рекомендуемая следующая фаза:

```text
1. Clean migrations and frontend hygiene
2. Add Organizations UI
3. Improve Usage dashboard
4. Live-verify OpenAI provider
5. Add RAG Knowledge Base
```

## Структура проекта

По текущей структуре проект выглядит так:


```text
Safeai-desk/
├── backend/
│   ├── .idea/
│   ├── .mvn/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── ru/
│   │   │   │       └── safeai/
│   │   │   │           └── gateway/
│   │   │   │               ├── admin/
│   │   │   │               ├── ai/
│   │   │   │               ├── audit/
│   │   │   │               ├── auth/
│   │   │   │               ├── chat/
│   │   │   │               ├── common/
│   │   │   │               ├── organization/
│   │   │   │               ├── ratelimit/
│   │   │   │               ├── user/
│   │   │   │               └── SafeaiBackendApplication.java
│   │   │   │
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       ├── application-local.yml
│   │   │       ├── application-local-nginx.yml
│   │   │       ├── application-prod.yml
│   │   │       ├── logback-spring.xml
│   │   │       ├── static/
│   │   │       ├── templates/
│   │   │       └── db/
│   │   │           ├── migration/
│   │   │           │   ├── V1__init_schema.sql
│   │   │           │   └──V2__seed_reference_data.sql
│   │   │           │   ├── V3__denormalize_chat_organization.sql
│   │   │           │   └── 4__schema_hardening.sql
│   │   │           │   ├── V5__audit_event_types.sql
│   │   │           │   └── V6__updated_at_triggers.sql
│   │   │           │   ├── V7__usage_quotas_and_rollups.sql
│   │   │           │ 
│   │   │           │    
│   │   │           └── local-migration/
│   │   │               └── R__seed_local_demo_data.sql
│   │   │
│   │   └── test/
│   │       ├── java/
│   │       │   └── ru/
│   │       │       └── safeai/
│   │       │           └── gateway/
│   │       │               ├── admin/
│   │       │               ├── ai/
│   │       │               ├── audit/
│   │       │               ├── auth/
│   │       │               ├── chat/
│   │       │               ├── common/
│   │       │               ├── organization/
│   │       │               ├── ratelimit/
│   │       │               ├── user/
│   │       │               ├── PasswordHashGenerator.java
│   │       │               └── SafeaiBackendApplicationTests.java
│   │       │
│   │       └── resources/
│   │           └── application-test.yml
│   │
│   ├── .env
│   ├── .env.example
│   ├── .env.prod
│   ├── Dockerfile
│   ├── mvnw
│   ├── mvnw.cmd
│   └── pom.xml
│
├── frontend/
│   ├── src/
│   │   ├── api/
│   │   ├── auth/
│   │   ├── components/
│   │   ├── pages/
│   │   ├── utils/
│   │   ├── App.tsx
│   │   ├── global.d.ts
│   │   ├── index.css
│   │   ├── main.tsx
│   │   └── vite-env.d.ts
│   │
│   ├── index.html
│   ├── package.json
│   ├── package-lock.json
│   ├── tsconfig.app.json
│   ├── tsconfig.json
│   ├── tsconfig.node.json
│   └── vite.config.ts
│
├── infra/
│   └── docker-compose.yml
│
├── scripts/
│   ├── check-health.bat
│   ├── full-docker-up.bat
│   ├── psql-audit-events.bat
│   ├── run-backend-local.bat
│   ├── run-infra.bat
│   └── stop-infra.bat
│
├── docs/
│
├── .gitignore
└── README.md
```



---

## Backend-модули


```text
ru.safeai.gateway
├── admin
│   ├── controller
│      └── AdminUsageController
│       
│
├── ai
│   ├── config
│   │   └── AiConfiguration
│   │
│   ├── dto
│   │   ├── AiChatRequest
│   │   ├── AiChatResponse
│   │   └── AiMessage
│   │
│   ├── exception
│   │   ├── AiProviderException
│   │   ├── AiProviderRateLimitedException
│   │   ├── AiProviderTimeoutException
│   │   └── AiProviderUnavailableException
│   │
│   ├── pricing
│   │   ├── ModelPricingProperties
│   │   └── ModelPricingService
│   │
│   ├── provider
│   │   ├── AiProvider
│   │   ├── AiProviderProperties
│   │   ├── AiProviderRetryExecutor
│   │   ├── AiProviderSupport
│   │   ├── AiRestClientFactory
│   │   ├── AiRetryProperties
│   │   │
│   │   ├── anthropic
│   │   │   ├── AnthropicProperties
│   │   │   └── AnthropicProvider
│   │   │
│   │   ├── mock
│   │   │   └── MockAiProvider
│   │   │
│   │   └── openai
│   │       ├── OpenAiProperties
│   │       └── OpenAiProvider
│   │
│   └── web
│       └── AiExceptionHandler
│
├── audit
│   ├── controller
│   │   └── AuditController
│   ├── dto
│   │   ├── AuditEventFilter
│   │   └── AuditEventResponse
│   ├── entity
│   │   └── AuditEventEntity
│   ├── listener
│   │   └── RateLimitAuditListener
│   ├── repository
│   │   └── AuditEventRepository
│   ├── service
│   │   ├── AuditEventQueryService
│   │   └── AuditEventService
│   └── AuditEventType
│
├── auth
│   ├── controller
│   │   ├── AuthController
│   │   └── CsrfController
│   ├── dto
│   │   ├── CurrentUserResponse
│   │   └── LoginRequest
│   │    
│   ├── entity
│   │   └── RefreshTokenEntity
│   ├── repository
│   │   └── RefreshTokenRepository
│   ├── security
│   │   ├── CsrfCookieFilter
│   │   ├── CustomUserDetailsService
│   │   ├── SecurityConfig
│   │   ├── SpaCsrfTokenRequestHandler
│   │   └── UserStatusFilter
│   └── service
│       ├── AuthCookieProperties
│       ├── AuthCookieService
│       ├── AuthEventService
│       ├── AuthService
│       ├── RefreshTokenCleanupJob
│       ├── RefreshTokenService
│       └── UserSessionRevocationService
│
├── chat
│   ├── controller
│   │   └── ChatController
│   ├── dto
│   │   ├── ChatDetailsResponse
│   │   ├── ChatResponse
│   │   ├── CreateChatRequest
│   │   ├── MessageResponse
│   │   ├── SendMessageRequest
│   │   ├── UsageDailySummaryResponse
│   │   ├── UsageModelSummaryResponse
│   │   ├── UsageSummaryResponse
│   │   └── UsageUserSummaryResponse
│   ├── entity
│   │   ├── ChatMessageEntity
│   │   ├── ChatMessageRole
│   │   ├── ChatMessageStatus
│   │   └── ChatSessionEntity
│   ├── repository
│   │   ├── ChatMessageRepository
│   │   ├── ChatSessionRepository
│   │   ├── UsageDailySummaryProjection
│   │   └── UsageQueryRepository
│   └── service
│       ├── ChatLockProperties
│       ├── ChatLockService
│       ├── ChatMapper
│       ├── ChatPersistenceService
│       ├── ChatProcessingContext
│       ├── ChatProperties
│       └── ChatService
│
├── common
│   ├── exception
│   │   ├── ApiErrorResponse
│   │   ├── ApiErrorResponseFactory
│   │   ├── BadRequestException
│   │   ├── ConflictException
│   │   ├── ExpiredRefreshTokenException
│   │   ├── ForbiddenOperationException
│   │   ├── GlobalExceptionHandler
│   │   ├── InvalidRefreshTokenException
│   │   ├── RateLimitExceededException
│   │   ├── RateLimitUnavailableException
│   │   ├── RefreshTokenReuseDetectedException
│   │   └── ResourceNotFoundException
│   │
│   ├── platform
│   │   └── PlatformProperties
│   │
│   └── security
│       ├── ClientIpProperties
│       ├── ClientIpResolver
│       ├── CorsProperties
│       ├── JsonAccessDeniedHandler
│       ├── JsonAuthenticationEntryPoint
│       ├── JsonSecurityErrorWriter
│       ├── JwtProperties
│       ├── JwtService
│       ├── RequestIdFilter
│       ├── RoleAuthorityMapper
│       ├── SafeAiJwtAuthenticationConverter
│       └── SafeAiUserPrincipal
│
├── organization
│   ├── controller
│   │   └── OrganizationController
│   ├── dto
│   │   ├── CreateOrganizationRequest
│   │   ├── OrganizationResponse
│   │   ├── UpdateOrganizationEnabledRequest
│   │   └── UpdateOrganizationRequest
│   ├── entity
│   │   └── OrganizationEntity
│   ├── event
│   │   └── OrganizationSecurityStateChangedEvent
│   ├── repository
│   │   └── OrganizationRepository
│   └── service
│       ├── OrganizationService
│       └── OrganizationStatusCacheInvalidationListener
│
├── ratelimit
│   ├── AiMessageRateLimitProperties
│   ├── LoginRateLimitProperties
│   ├── LoginRateLimitService
│   ├── RateLimitExceededEvent
│   ├── RateLimitKeyFactory
│   ├── RateLimitRedisKeyProperties
│   ├── RateLimitResult
│   ├── RedisFixedWindowRateLimiter
│   └── RedisRateLimitService
│
│     ── usage
│        ├── dto
│        │   ├── UsageDailySummaryResponse.java
│        │   ├── UsageModelSummaryResponse.java
│        │   ├── UsageSummaryResponse.java
│        │   └── UsageUserSummaryResponse.java
│        │
│        ├── repository
│        │   ├── UsageDailySummaryProjection.java
│        │   └── UsageQueryRepository.java
│        │
│        └── service
│            └── UsageQueryService.java


└── user
    ├── controller
    │   └── UserController
    ├── dto
    │   ├── CreateUserRequest
    │   ├── ResetUserPasswordRequest
    │   ├── UpdateUserEnabledRequest
    │   ├── UpdateUserRolesRequest
    │   └── UserResponse
    ├── entity
    │   ├── RoleEntity
    │   └── UserEntity
    ├── event
    │   └── UserSecurityStateChangedEvent
    ├── repository
    │   ├── RoleRepository
    │   └── UserRepository
    └── service
    │    ├── UserSecurityStatus
    │    ├── UserService
    │    ├── UserStatusCacheInvalidationListener
    │    ├── UserStatusCacheProperties
    │    └── UserStatusCacheService
    │    
    └── validation
        └── PasswordPolicy
```



### Назначение модулей

| Модуль | Назначение |
|---|---|
| `auth` | login, текущий пользователь, JWT выдача, audit login events |
| `common.security` | SecurityConfig, JWT, principal, requestId, JSON 401/403, UserStatusFilter |
| `common.exception` | единый формат API ошибок |
| `common.ratelimit` | Redis login/AI-message rate limits |
| `organization` | организации, platform-level управление |
| `user` | пользователи, роли, enable/disable, reset password |
| `chat` | chat sessions/messages, сохранение usage |
| `ai` | AI provider abstraction и реализации |
| `audit` | запись и чтение audit events |
| `admin` | usage dashboards/aggregation endpoints |

---

## Frontend-модули

```text
frontend/
├── dist/
├── node_modules/
├── src/
│   ├── api/
│   │   ├── adminApi.ts
│   │   ├── authApi.ts
│   │   ├── chatApi.ts
│   │   ├── http.ts
│   │   ├── organizationApi.ts
│   │   └── userApi.ts
│   │
│   ├── auth/
│   │   └── AuthContext.tsx
│   │
│   ├── components/
│   │   ├── ConfirmDialog.tsx
│   │   ├── ErrorBoundary.tsx
│   │   └── Modal.tsx
│   │
│   ├── pages/
│   │   ├── AdminAuditPage.tsx
│   │   ├── AdminOrganizationsPage.tsx
│   │   ├── AdminUsagePage.tsx
│   │   ├── AdminUsersPage.tsx
│   │   ├── ChatPage.tsx
│   │   └── LoginPage.tsx
│   │
│   ├── utils/
│   │   ├── format.ts
│   │   └── page.ts
│   │
│   ├── App.tsx
│   ├── global.d.ts
│   ├── index.css
│   ├── main.tsx
│   └── vite-env.d.ts
│
├── index.html
├── package.json
├── package-lock.json
├── tsconfig.app.json
├── tsconfig.json
├── tsconfig.node.json
└── vite.config.ts
```

### Назначение frontend-файлов

| Файл | Назначение |
|---|---|
| `api/http.ts` | общий `apiRequest`, token handling, ApiError |
| `api/authApi.ts` | login и `/api/auth/me` |
| `api/chatApi.ts` | CRUD чатов и отправка сообщений |
| `api/userApi.ts` | admin user-management actions |
| `api/adminApi.ts` | audit и usage APIs |
| `App.tsx` | routes, protected routes, topbar |
| `LoginPage.tsx` | login form |
| `ChatPage.tsx` | список чатов и сообщения |
| `AdminUsersPage.tsx` | пользователи, роли, enable/disable, reset password |
| `AdminAuditPage.tsx` | audit events |
| `AdminUsagePage.tsx` | usage summary |

---

А в application.yml замени pricing на:

safeai:
ai:
pricing:
currency: USD
mode: estimate
models:
- model: mock-safeai
input-usd-per-1m-tokens: 0
output-usd-per-1m-tokens: 0
- model: gpt-4.1
input-usd-per-1m-tokens: 2.00
output-usd-per-1m-tokens: 8.00
- model: claude-opus-4-8
input-usd-per-1m-tokens: 15.00
output-usd-per-1m-tokens: 75.00

Но важно: если у тебя ModelPricingProperties сейчас ожидает только models, то поля currency и mode могут сломать binding, если класс строгий. Тогда безопаснее пока так:

safeai:
ai:
pricing:
models:
- model: mock-safeai
input-usd-per-1m-tokens: 0
output-usd-per-1m-tokens: 0
- model: gpt-4.1
input-usd-per-1m-tokens: 2.00
output-usd-per-1m-tokens: 8.00
- model: claude-opus-4-8
input-usd-per-1m-tokens: 15.00
output-usd-per-1m-tokens: 75.00

А в README написать: cost is an estimate based on configured model pricing.


Как должно быть по ролям
SUPER_ADMIN

Может:

- видеть все организации;
- создавать организации;
- включать/выключать организации;
- видеть всех пользователей всех организаций;
- создавать ADMIN/USER в любой организации;
- смотреть весь audit;
- смотреть весь usage;
- фильтровать audit/usage по organizationId;
- управлять ADMIN внутри организаций.

Не должен:

- создавать еще одного SUPER_ADMIN через обычную форму;
- случайно создавать пользователей в SafeAI Platform;
- работать как обычный пользователь клиентской организации.
  ADMIN

Может:

- видеть пользователей только своей организации;
- создавать USER внутри своей организации;
- сбрасывать пароль USER своей организации;
- включать/выключать USER своей организации;
- смотреть audit только своей организации;
- смотреть usage только своей организации;
- пользоваться чатом.

Спорно, но для безопасного production лучше:

ADMIN не должен создавать других ADMIN.

Назначение ADMIN лучше оставить только SUPER_ADMIN.

USER

Может:

- видеть только свои чаты;
- создавать свои чаты;
- отправлять сообщения;
- видеть только свои сообщения.

Не может:

- видеть Users;
- видеть Organizations;
- видеть Audit;
- видеть Usage;
- создавать пользователей;
- менять роли;
- видеть чужие чаты.


Правила должны быть такие:

SUPER_ADMIN:
- может createUser в любой organizationId;
- может назначать USER/ADMIN;
- не может через обычный endpoint назначать SUPER_ADMIN.

ADMIN:
- может createUser только в своей organizationId;
- если в request пришел чужой organizationId -> ForbiddenOperationException;
- может назначать только USER;
- не может назначать ADMIN/SUPER_ADMIN.


