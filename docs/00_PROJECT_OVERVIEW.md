# SafeAI Desk — project overview

## 1. Что такое SafeAI Desk

SafeAI Desk — backend-first MVP корпоративного AI Gateway.

Задача проекта — дать сотрудникам доступ к AI через единый внутренний backend, где организация контролирует пользователей, роли, историю чатов, аудит, usage и будущую работу с документами.

## 2. Проблема

Без такого gateway использование AI в компании часто выглядит так:

```text
сотрудники → разные внешние AI-сервисы → нет контроля → нет аудита → нет usage → нет лимитов
```

SafeAI Desk добавляет корпоративный слой:

```text
сотрудники → SafeAI Desk → auth → audit → usage → AI provider
```

## 3. Архитектурная идея

```text
Client
  ↓
REST API
  ↓
JWT Security
  ↓
Controller
  ↓
Service
  ↓
Repository
  ↓
PostgreSQL
```

Для AI:

```text
ChatService
  ↓
AiProvider interface
  ↓
MockAiProvider сейчас
RealAiProvider позже
```

## 4. Backend-пакеты

```text
ru.safeai.gateway
├── ai
├── audit
├── auth
├── chat
├── common
├── organization
└── user
```

## 5. Основные таблицы

```text
organizations
users
roles
user_roles
chat_sessions
chat_messages
audit_events
flyway_schema_history
```

## 6. Основные сценарии

### Login

```text
POST /api/auth/login
  ↓
AuthService
  ↓
AuthenticationManager
  ↓
BCrypt password check
  ↓
JwtService
  ↓
USER_LOGIN_SUCCESS / USER_LOGIN_FAILED
```

### Chat message

```text
POST /api/chats/{id}/messages
  ↓
save USER message
  ↓
AiProvider.sendMessage(...)
  ↓
save ASSISTANT message
  ↓
CHAT_MESSAGE_SENT + AI_RESPONSE_RECEIVED
```

### Audit

```text
AuditEventService.record(...)
  ↓
audit_events.details jsonb
  ↓
GET /api/admin/audit-events
```

## 7. Что уже сделано

```text
✅ Docker Compose
✅ PostgreSQL
✅ Redis
✅ Flyway
✅ Organization API
✅ User API
✅ Auth/JWT
✅ Chat Core
✅ AI Provider abstraction
✅ Mock AI Provider
✅ Audit events
```

## 8. Почему это хороший backend-проект

Он показывает не просто CRUD, а связку корпоративных backend-задач:

- безопасность;
- роли;
- транзакции;
- аудит;
- история;
- расширяемость через интерфейс;
- подготовка к usage и лимитам;
- подготовка к интеграции с реальным AI.
