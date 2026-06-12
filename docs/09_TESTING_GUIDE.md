# SafeAI Desk — testing guide

Документ описывает минимальные тесты для текущего backend.

## 1. Запуск всех тестов

```bat
cd /d "D:\Java projects\Safeai-desk\backend"
mvnw.cmd test
```

Ожидаемо:

```text
BUILD SUCCESS
```

## 2. Компиляция

```bat
mvnw.cmd clean compile
```

## 3. Минимальный набор тестов

```text
MockAiProviderTest
OrganizationServiceTest
UserServiceTest
AuthServiceTest
ChatServiceTest
AuditEventServiceTest
AuditEventQueryServiceTest
```

## 4. Что не тестировать отдельно

```text
DTO records
Entity getters/setters
JpaRepository стандартные методы
```

## 5. MockAiProviderTest

Проверяет:

```text
content
model = mock-safeai
inputTokens
outputTokens
costUsd = 0
```

## 6. OrganizationServiceTest

Проверяет:

```text
create success
findAll
findById success
findById not found
```

## 7. UserServiceTest

Проверяет:

```text
create user success
duplicate email
organization not found
role not found
password hashing
roles mapping
```

## 8. AuthServiceTest

Проверяет:

```text
login success
bad credentials
JWT generation
roles mapping
audit USER_LOGIN_SUCCESS
audit USER_LOGIN_FAILED
```

После добавления аудита нужен mock:

```java
@Mock
private AuditEventService auditEventService;
```

## 9. ChatServiceTest

Проверяет:

```text
create chat
send message
save USER message
call AiProvider
save ASSISTANT message
audit CHAT_CREATED
audit CHAT_MESSAGE_SENT
audit AI_RESPONSE_RECEIVED
chat not found
```

После добавления аудита нужен mock:

```java
@Mock
private AuditEventService auditEventService;
```

## 10. Controller/security tests позже

Позже добавить:

```text
/api/auth/login public
/api/auth/me requires auth
/api/users without token -> 401
/api/users USER -> 403
/api/users ADMIN -> 200
/api/admin/audit-events ADMIN -> 200
```

## 11. Частые ошибки

Если в сервис добавили новое `final` поле, в тест с `@InjectMocks` нужно добавить соответствующий `@Mock`.

Если тесты упали после аудита, проверь вызовы:

```java
verify(auditEventService).record(...)
```

## 12. Успешный результат

```text
✅ clean compile проходит
✅ mvnw.cmd test проходит
✅ service tests проходят
✅ новые зависимости сервисов замоканы
```
