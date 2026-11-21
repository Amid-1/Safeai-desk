# SafeAI Desk — модуль `ru.safeai.gateway.user`

Java 21, Spring Boot, Spring Security, Spring Data JPA и PostgreSQL.

## 1. Назначение и границы

`user` — **логический модуль управления пользователями** внутри единого приложения, не самостоятельный микросервис.
Он отвечает за создание, изменение, деактивацию, роли, сброс паролей, просмотр и статистику пользователей, проверки
tenant scope,
управление версией токенов и фиксацию аудита.

Границы:
- `user`: сущности и запросы пользователей, бизнес-политики, ролевая модель управления, запрет уничтожения важных
  связей, события безопасности, текущий статус пользователя.
- `auth`: проверка credentials, выдача/обновление JWT и refresh-сессий, проверка токена и фактический отзыв сессий.
  `user` обращается к абстракции `identity.session.SessionRevocationPort`, а не к конкретной реализации auth; это
  хороший вариант разделения.
- `organization`: организация пользователя, состояние и security epoch организации; `user` проверяет и блокирует
  организацию при изменении критичного состояния.
- `audit`: построение `AuditActor` через `AuditActorSnapshotProvider`, аудит операций и outbox.
- `common`: исключения, `SafeAiUserPrincipal`, `SystemRole`, общая пагинация и конверсия ошибок.

**Вывод:** зависимость от auth как от исходного пакета для сборки `user` не должна быть нужна, если
`SessionRevocationPort`
объявлен в нейтральном пакете `identity`. Но проверка реального жизненного цикла JWT требует интеграционных тестов
совместно с auth.
Разделять их на отдельные сетевые сервисы сейчас не нужно.
## Структура исходников

```text
user/
├── audit/
│   └── UserAuditActorSnapshotProvider.java
├── controller/
│   ├── UserController.java
│   └── UserDirectorySearchController.java
├── dto/
│   ├── CreateUserRequest.java
│   ├── PermanentDeleteUserRequest.java
│   ├── ResetUserPasswordRequest.java
│   ├── UpdateUserEnabledRequest.java
│   ├── UpdateUserRequest.java
│   ├── UpdateUserRolesRequest.java
│   ├── UserDetailsResponse.java
│   ├── UserResponse.java
│   └── UserStatisticsResponse.java
├── entity/
│   ├── RoleEntity.java
│   └── UserEntity.java
├── event/
│   └── UserSecurityStateChangedEvent.java
├── exception/
│   ├── UserExceptionHandler.java
│   └── UserVersionConflictException.java
├── mapper/
│   └── UserRoleMapper.java
├── repository/
│   ├── RoleRepository.java
│   └── UserRepository.java
├── service/
│   ├── OrganizationStatusCacheInvalidationListener.java
│   ├── PlatformOrganizationInvariantVerifier.java
│   ├── UserAccessPolicy.java
│   ├── UserCommandOperations.java
│   ├── UserDirectorySearchService.java
│   ├── UserEntityAccess.java
│   ├── UserManagementProperties.java
│   ├── UserQueryOperations.java
│   ├── UserResponseMapper.java
│   ├── UserRoleAssignmentResolver.java
│   ├── UserSecurityStatus.java
│   ├── UserService.java
│   ├── UserStatusCacheInvalidationListener.java
│   ├── UserStatusCacheProductionInvariantVerifier.java
│   ├── UserStatusCacheProperties.java
│   └── UserStatusCacheService.java
├── validation/
│   ├── BcryptUtf8Length.java
│   ├── BcryptUtf8LengthValidator.java
│   ├── PasswordPolicy.java
│   ├── PasswordValidator.java
│   ├── UserEmailNormalizer.java
│   └── ValidPassword.java
└── README_USER.md
```

## 2. Схема обращения

```text
HTTP /api/users → UserController → UserService (@Transactional)
                                          ├─ UserCommandOperations
                                          │   ├─ UserAccessPolicy
                                          │   ├─ UserEntityAccess → organization lock → user lock
                                          │   ├─ UserRoleAssignmentResolver
                                          │   ├─ UserRepository / RoleRepository
                                          │   ├─ SessionRevocationPort (identity boundary)
                                          │   ├─ AuditEventService
                                          │   └─ UserSecurityStateChangedEvent
                                          └─ UserQueryOperations
                                              ├─ StablePageableNormalizer
                                              └─ UserResponseMapper

Security filter/auth → UserStatusCacheService → PostgreSQL (production)
Event AFTER_COMMIT → UserStatusCacheInvalidationListener → Redis eviction (если включён)
Production startup → PlatformOrganizationInvariantVerifier
                   → UserStatusCacheProductionInvariantVerifier
Audit → UserAuditActorSnapshotProvider → UserRepository
```

## 3. Классы и методы

### `audit`
- **`UserAuditActorSnapshotProvider`** — SPI адаптер аудита. `resolve(principal, explicitDisplayName)`: берёт
  пользователя текущей организации
  из БД, строит снимок с текущими email/fullName; при отсутствии записи используется
  `AuditActor.fromPrincipal`. Ошибки БД **не подавляются**. `actorFromUser(...)`: создает `AuditActor`. Это относится к
  разрешению PII для аудита, а не к аутентификации.

### `controller`

- **`UserController`** — `/api/users`. Обрабатывает создание, список, статистику, детали пользователя,
  редактирование профиля, смену активности и ролей, сброс пароля и постоянное удаление.
  Доступ к удалению ограничен ролью `SUPER_ADMIN`. Сервис дополнительно проверяет область доступа.
- **`UserDirectorySearchController`** — `GET /api/users/search` для `ADMIN` и `SUPER_ADMIN`.
  `UserDirectorySearchService` применяет фильтры и ограничения видимости в БД.

### `dto`
- **`CreateUserRequest`** — `organizationId`, `email`, `password`, `fullName`, единственная роль; `@ValidPassword` и
  Bean Validation; `toString()` скрывает чувствительные поля.
- **`PermanentDeleteUserRequest`** — подтверждающий email и ожидаемая `version` для удаления.
- **`ResetUserPasswordRequest`** — новый пароль и `expectedVersion`; `toString()` скрывает пароль.
- **`UpdateUserEnabledRequest`** — значение `enabled` плюс optimistic concurrency token.
- **`UpdateUserRequest`** — новый email и имя плюс `expectedVersion`.
- **`UpdateUserRolesRequest`** — непустое множество ровно из одной роли плюс `expectedVersion`.
- **`UserDetailsResponse`** — детальная модель, включая `organizationName`, enabled/roles/version/dates. Compact
  constructor валидирует ключевые поля, нормализует `organizationName`, копирует роли; дополнительный конструктор
  сохраняет совместимость со старым контрактом.
- **`UserResponse`** — краткая информация о пользователе; защитная копия множества ролей.
- **`UserStatisticsResponse`** — `total`, `administrators`, `users`, `enabled`, `disabled`; для global scope SUPER_ADMIN
  входит в `total`, но не в сумму ADMIN+USER.

### `entity`
- **`RoleEntity`** — JPA сущность `roles` (`id`, `name`, unique).
- **`UserEntity`** — `users`: организация, canonical email, BCrypt-хеш, имя, активность, disabledAt, роли M:N,
  created/updated/lastLogin, tokenVersion, optimistic `@Version`. `prePersist()` задаёт UUID, если отсутствует.
  Временные отметки создает БД (`@Generated`).

### `event`
- **`UserSecurityStateChangedEvent`** — UUID пользователя для локальной after-commit инвалидации опционального кэша.

### `exception`
- **`UserExceptionHandler`** — преобразует `UserVersionConflictException` в стандартизованную ошибку через
  `ApiExceptionResponseSupport`; `handleUserVersionConflict`.
- **`UserVersionConflictException`** — типизированный `409 Conflict`: `userId`, `expectedVersion`, `actualVersion`.

### `mapper`
- **`UserRoleMapper`** — `toRoleNames(user)` требует **ровно одну** известную `SystemRole`; `requireRoleName(...)` не
  разрешает пустые/неизвестные роли. Возвращает immutable Set.

### `repository`
- **`RoleRepository`** — `findByName` и CRUD для системных ролей.
- **`UserRepository`** — JPA и security SQL. `findByEmail`, `existsByEmail`; `findByIdWithRolesAndOrganization`;
  `findByIdForSecurityUpdate`; `lockOrganizationForAuthentication` (`FOR SHARE`); `updateLastLoginAtWithoutVersion`;
  выборки ID с/без org/role; `findAllByIdsWithRolesAndOrganization`; `findEnabledAdminsForUpdate` (`PESSIMISTIC_WRITE`);
  `findByIdAndOrganizationId`; `findByIdWithOrganization`; `findIdsByOrganizationId`; `hasActiveRefreshTokens`;
  `hasPermanentDeletionDependencies`; групповые `countBy...`. Порядок блокировок: organization → user → refresh token.

### `service`

- **`UserDirectorySearchService`** — поиск по имени и email с ограничениями по организации и роли.
- **`OrganizationStatusCacheInvalidationListener`** — after-commit `onOrganizationSecurityStateChanged`, разбивает
  пользователей организации по 1000 ID, передаёт в `evictAll`. Не является границей безопасности; ошибки журналируются.
- **`PlatformOrganizationInvariantVerifier`** — проверяет платформенную организацию при запуске production,
  включая наличие хотя бы одного включённого `SUPER_ADMIN`.
- **`UserAccessPolicy`** — `requireUserManager`, `isAdmin`, `isSuperAdmin`, `isEnabledAdmin`, `hasRole`,
  `resolveTargetOrganizationId`, `rejectPlatformOrganizationCreation`, `isPlatformOrganization`,
  `rejectSuperAdminMutation`, `rejectSelfManagement`, `rejectAdminManagingAdmin`, `normalizeListRole`, `normalizeRoles`
  , `requireExactlyOneRequestedRole`, `requireExpectedVersion`, `normalizeFullName`, `requireValidNewPassword`,
  `userManagerRequired`; внутренний `hasAuthority`. Назначение: единая application-level авторизация.
- **`UserCommandOperations`** — транзакционные сценарии: `create`, `updateUser`, `updateEnabled`, `updateRoles`,
  `resetPassword`, `permanentlyDelete`; вспомогательные `saveAndRefresh`, `incrementTokenVersion`,
  `publishSecurityStateChanged`, `isEmailUniqueViolation`, `duplicateEmail`, `nullToEmpty`. Критично, что отзыв
  refresh-сессий выполняется в общей транзакции при изменении security state.
- **`UserEntityAccess`** — `findVisible`, `findForSecurityMutation`, `lockOrganization`, `assertNotLastEnabledAdmin`,
  `userNotFound`, `detachVisibleSecuritySnapshot`. Получает предварительный снимок, отделяет его от persistence context,
  берёт lock на организацию, затем lock на пользователя, повторно проверяет scope.
- **`UserManagementProperties`** — `permanentDeletionRetention`: по умолчанию 7 дней, максимум 365; zero разрешён
  политикой конфигурации. Должно согласовываться с юридической политикой хранения.
- **`UserQueryOperations`** — `findAll`, `findDetailsById`, `statistics`, `normalizePageable`: страничная выдача через
  IDs и secondary fetch, сортировка с tie-breaker `id`, tenant restrictions, aggregate counts.
- **`UserResponseMapper`** — `toResponse`, `toDetailsResponse`; исключает `passwordHash` и `tokenVersion` из
  API-ответов.
- **`UserRoleAssignmentResolver`** — `resolve`: SUPER_ADMIN назначает USER/ADMIN, ADMIN — только USER, SUPER_ADMIN не
  назначается через обычный endpoint; роль загружается из каталога.
- **`UserSecurityStatus`** — status snapshot: organizationId, флаги активности, tokenVersion, organizationAuthVersion;
  проверяет версии.
- **`UserService`** — публичный фасад; `@Transactional` для команд, `readOnly + REPEATABLE_READ` для списков и
  статистики. Делегирование `commands` / `queries` предотвращает чрезмерный размер одного сервиса.
- **`UserStatusCacheInvalidationListener`** — after-commit `onUserSecurityStateChanged`: удаляет состояние одного
  пользователя из Redis.
- **`UserStatusCacheProductionInvariantVerifier`** — `run`: запрещает best-effort security cache в production — важная
  защита.
- **`UserStatusCacheProperties`** — `enabled` default false, `ttl` до 5 минут, префикс ключа. `isEnabled`,
  `effectiveTtl`, `effectiveKeyPrefix`.
- **`UserStatusCacheService`** — `getStatus`, `evict`, `evictAll`; внутренние `loadFromDatabase`, `cache`, `parse`,
  `parseBooleanStrict`, `serialize`, `key`. При отключённом кэше проверка всегда идёт в PostgreSQL; при ошибке Redis
  возврат к БД, при ошибке БД исключение не скрывается. Формат статус-кэша включает обе версии security epoch.

### `validation`
- **`BcryptUtf8Length`** — constraint максимального размера пароля в UTF-8.
- **`BcryptUtf8LengthValidator`** — `initialize`, `isValid`: считает байты, а не Java char.
- **`PasswordPolicy`** — `isValidNewPassword`, `hasValidUtf8Length`, `utf8Length`: ≥12 кодовых точек, разные классы
  символов, запрет ISO controls, ≤72 UTF-8 байт для BCrypt.
- **`PasswordValidator`** — Bean Validation adapter, `isValid`.
- **`UserEmailNormalizer`** — `normalizeAndValidate`, `normalizeStored`; внутренние `normalize`, `requireEmail`,
  `canonicalize`, `validateCanonicalEmail`, `rejectControlCharacters`, `rejectWhitespace`, `validateAtSign`; trim +
  Locale.ROOT lowercase, длина ≤255, один @, no whitespace/control.
- **`ValidPassword`** — декларативный парольный constraint.

## 4. Проверенные архитектурные решения

1. **Multi-tenancy.** Tenant ADMIN видит только пользователей своей организации. SUPER_ADMIN допускает глобальную
   видимость по правилам policy. Ограничение повторено на уровне `UserEntityAccess`.
2. **RBAC.** Роль сохраняется ровно одна; известные роли `USER`, `ADMIN`, `SUPER_ADMIN` через `SystemRole`. Публичный
   API не позволяет назначить SUPER_ADMIN и менять системных SUPER_ADMIN.
3. **Конкурентные обновления.** `@Version` + `expectedVersion` возвращают 409; организация блокируется перед
   пользователем. Есть реальный PostgreSQL concurrency test на двух администраторов.
4. **Отзыв сессий.** При email/role/password/enabled меняются tokenVersion и refresh-сессии; всё должно быть в одной
   транзакции. Есть интеграционный тест commit/rollback вместе с аудитом.
5. **Безопасность кэша.** Redis — только best-effort; production verifier его запрещает. Это правильно, поскольку cache
   eviction не является надежной security fence.
6. **Постоянное удаление.** `SUPER_ADMIN` подтверждает email и версию отключённой учётной записи.
   По умолчанию срок ожидания составляет семь дней. Активный refresh-токен или связанные записи
   могут заблокировать операцию. Исторические снимки аудита сохраняются, даже если живая ссылка удалена.
7. **Аудит.** Снимок актёра строится по текущему tenant-scoped пользователю; потеря БД — ошибка аудита, а не fallback на
   недостоверные данные.

## 5. Аудит дефектов и рисков (отделены факты от гипотез)

### Инвариант платформенной организации

В production `PlatformOrganizationInvariantVerifier` требует включённую платформенную организацию,
неотрицательные версии, только роль `SUPER_ADMIN` у её пользователей и хотя бы одного включённого
`SUPER_ADMIN`. Проверка активности использует `UserRepository.countEnabledByOrganizationIdAndRole`.

### P2/P3 — верифицировать схему физического удаления на актуальной DB

`hasPermanentDeletionDependencies` явно учитывает `chat_sessions`, дневные usage rollups, квоты, KB memberships,
`audit_outbox`. В новых миграциях могут существовать дополнительные FK на `users`. Фактический `DELETE` будет защищён
внешними ключами и завернётся в понятный `409`, но список предварительных проверок может расходиться с реальной схемой.
Сверять с `pg_constraint` после *каждой* новой миграции и иметь интеграционный тест для обнаруженных ссылок. Не
предлагать `CASCADE` как автоматическое решение.

### P3 — policy/UX: значение retention=0

Конфигурация намеренно допускает нулевой срок хранения для `permanentDeletionRetention`; **это не программная ошибка**.
Для реального production требования могут требовать ненулевой минимум/soft deletion и отдельную политику хранения
персональных данных и аудита. Нельзя менять default без продуктового решения.

### P3 — security cache в non-production

При включённом кэше возможна гонка между чтением старого security state, after-commit eviction и последующей записью
старого значения в Redis. Наличие after-commit listeners не доказывает немедленной инвалидизации. Продукт уже правильно
запрещает данный кэш в production; использовать его только в dev/test, явно учитывая задержку до TTL.

### P3 — полнота email validation

`UserEmailNormalizer` проверяет canonical representation, наличие одного `@`, длину, управляющие/пробельные символы;
**не выполняет полной синтаксической валидации домена**. HTTP DTO дополнительно помечены `@Email`, но прямые
service-вызовы зависят от нормализатора. Если продукт требует полноценную проверку формата по единым правилам на всех
входах, добавить единый policy/test. Не следует применять DNS/MX-проверки в каждом REST-запросе как «улучшение» по
умолчанию.

### Проверено: мнимые проблемы, которые исправлять не нужно

- `normalizeFullName` в реальном исходнике использует `replaceAll("\\s+", " ")`: regex корректный, это не `"\s+"` с Java
  escape-пробелом.
- `UserStatusCacheService` не «открывает доступ при падении Redis»: fallback обращается к PostgreSQL, а сбой БД не
  превращается в успешную авторизацию.
- `@Version` в `UserEntity` вместе с `expectedVersion` предназначен для пользовательских команд;
  `updateLastLoginAtWithoutVersion` не должен из-за входа в систему генерировать 409 в админке.
- `UserStatisticsResponse.total != administrators + users` у SUPER_ADMIN является **оговоренным контрактом**, поскольку
  SUPER_ADMIN относится к total.
- В `UserEntityAccess` порядок pessimistic locks не случайный: сначала organization, затем user. Не переставлять без
  полного анализа auth/organization потоков.

## 6. Имеющиеся тесты и обязательная регрессия
### Структура тестов

```text
user/
├── audit/
│   └── UserAuditActorSnapshotProviderTest.java
├── controller/
│   └── UserControllerSecurityTest.java
├── dto/
│   ├── CreateUserRequestValidationTest.java
│   └── UserMutationRequestValidationTest.java
├── exception/
│   ├── UserExceptionHandlerTest.java
│   └── UserVersionConflictExceptionTest.java
├── mapper/
│   └── UserRoleMapperTest.java
├── service/
│   ├── OrganizationStatusCacheInvalidationListenerTest.java
│   ├── PlatformOrganizationInvariantVerifierProductionConditionTest.java
│   ├── PlatformOrganizationInvariantVerifierTest.java
│   ├── UserDirectorySearchServiceTest.java
│   ├── UserManagementPostgresIntegrationTest.java
│   ├── UserManagementPropertiesTest.java
│   ├── UserSecurityStatusTest.java
│   ├── UserSecurityTransactionIntegrationTest.java
│   ├── UserServiceAuthorizationBoundaryTest.java
│   ├── UserServiceReadTest.java
│   ├── UserServiceTest.java
│   ├── UserStatusCacheInvalidationIntegrationTest.java
│   ├── UserStatusCacheProductionInvariantVerifierTest.java
│   ├── UserStatusCachePropertiesTest.java
│   └── UserStatusCacheServiceTest.java
└── validation/
    ├── PasswordPolicyTest.java
    ├── PasswordValidatorTest.java
    └── UserEmailNormalizerTest.java
```

В репозитории обнаружены тесты `UserManagementPostgresIntegrationTest`, `UserSecurityTransactionIntegrationTest`,
`UserServiceAuthorizationBoundaryTest`, `PlatformOrganizationInvariantVerifierTest`, `UserStatusCacheServiceTest`,
`UserServiceReadTest`, `UserRoleMapperTest`, `PasswordPolicyTest`, `UserEmailNormalizerTest`, тесты
controller/DTO/exception и другие.

Проверки, уже заявленные тестовыми методами:
- `concurrentDuplicateEmailCreationProducesOneSuccessAndOneConflict`
- `adminOrgACannotSeeOrChangeUserOrgB`
- `twoConcurrentDisablesLeaveOneActiveAdmin`
- `concurrentDisableAndRoleRemovalLeaveOneActiveAdmin`
- `successfulSecurityMutationRevokesRefreshSessionInSameCommit`
- `auditFailureRollsBackMutationAndRefreshRevocation`
- `emptyDisabledUserCanBePermanentlyDeleted`
- `auditEvidenceSurvivesUserDeletionAndLiveLinkIsRemoved`

Регрессия `rejectsPlatformWhenAllSuperAdminsDisabled` входит в `PlatformOrganizationInvariantVerifierTest`.

```bash
cd backend
./mvnw test -Dtest='UserServiceTest,UserServiceAuthorizationBoundaryTest,UserStatusCacheServiceTest'
./mvnw test -Dtest='PasswordPolicyTest,UserEmailNormalizerTest'
# Для тестов с Testcontainers/PostgreSQL необходим рабочий Docker runtime.
./mvnw verify
```

## 7. Проверки перед выпуском

1. Прогнать полный набор тестов с Docker и проверить миграции на PostgreSQL.
2. На HTTPS-стенде проверить отказ со старыми токенами после смены email, роли, пароля и отключения.
3. При изменениях схемы сверять внешние ключи и предварительные проверки удаления.
4. Согласовать сроки хранения отключённых учётных записей и исторических снимков аудита.
