# SafeAI Desk — модуль `ru.safeai.gateway.user`

**Дата разбора:** 16 октября 2026 года.  
**База:** Java 21, Spring Boot 4.1.x, Spring Security, Spring Data JPA, Hibernate, PostgreSQL, Jakarta Bean Validation, 
Lombok, JSpecify, Redis (опционально), Spring Events, JUnit/Mockito/Testcontainers.  
**Исходник:** ветка `main` репозитория `Amid-1/Safeai-desk` (прочитаны актуальные Java-файлы 16.10.2026); описывается модуль, 
предоставленный в сообщении, с проверкой ключевых участков по репозиторию.

## 1. Назначение и границы

`user` — **логический модуль управления пользователями** внутри единого приложения, не самостоятельный микросервис. 
Он отвечает за создание, изменение, деактивацию, роли, сброс паролей, просмотр и статистику пользователей, проверки tenant scope, 
управление версией токенов и фиксацию аудита.

Границы:
- `user`: сущности и запросы пользователей, бизнес-политики, ролевая модель управления, запрет уничтожения важных связей, события безопасности, текущий статус пользователя.
- `auth`: проверка credentials, выдача/обновление JWT и refresh-сессий, проверка токена и фактический отзыв сессий. `user` обращается к абстракции `identity.session.SessionRevocationPort`, а не к конкретной реализации auth; это хороший вариант разделения.
- `organization`: организация пользователя, состояние и security epoch организации; `user` проверяет и блокирует организацию при изменении критичного состояния.
- `audit`: построение `AuditActor` через `AuditActorSnapshotProvider`, аудит операций и outbox.
- `common`: исключения, `SafeAiUserPrincipal`, `SystemRole`, общая пагинация и конверсия ошибок.

**Вывод:** зависимость от auth как от исходного пакета для сборки `user` не должна быть нужна, если `SessionRevocationPort` 
объявлен в нейтральном пакете `identity`. Но проверка реального жизненного цикла JWT требует интеграционных тестов совместно с auth. 
Разделять их на отдельные сетевые сервисы сейчас не нужно.

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
- **`UserAuditActorSnapshotProvider`** — SPI адаптер аудита. `resolve(principal, explicitDisplayName)`: берёт tenant-scoped 
- пользователя из БД, строит снимок с текущими email/fullName; при отсутствии записи используется `AuditActor.fromPrincipal`. Ошибки БД **не подавляются**. `actorFromUser(...)`: создает `AuditActor`. Это относится к разрешению PII для аудита, а не к аутентификации.

### `controller`
- **`UserController`** — REST `/api/users`, `@PreAuthorize(hasAnyRole('ADMIN','SUPER_ADMIN'))`. `create`, `findAll`, `statistics`, `findById`, `updateUser`, `updateEnabled`, `updateRoles`, `resetPassword`, `permanentlyDelete`. У последнего отдельный `hasRole('SUPER_ADMIN')`. Методам делегирована бизнес-авторизация — необходимая вторая линия защиты.

### `dto`
- **`CreateUserRequest`** — `organizationId`, `email`, `password`, `fullName`, единственная роль; `@ValidPassword` и Bean Validation; `toString()` скрывает чувствительные поля.
- **`PermanentDeleteUserRequest`** — подтверждающий email и ожидаемая `version` для удаления.
- **`ResetUserPasswordRequest`** — новый пароль и `expectedVersion`; `toString()` скрывает пароль.
- **`UpdateUserEnabledRequest`** — значение `enabled` плюс optimistic concurrency token.
- **`UpdateUserRequest`** — новый email и имя плюс `expectedVersion`.
- **`UpdateUserRolesRequest`** — непустое множество ровно из одной роли плюс `expectedVersion`.
- **`UserDetailsResponse`** — детальная модель, включая `organizationName`, enabled/roles/version/dates. Compact constructor валидирует ключевые поля, нормализует `organizationName`, копирует роли; дополнительный конструктор сохраняет совместимость со старым контрактом.
- **`UserResponse`** — краткая информация о пользователе; защитная копия множества ролей.
- **`UserStatisticsResponse`** — `total`, `administrators`, `users`, `enabled`, `disabled`; для global scope SUPER_ADMIN входит в `total`, но не в сумму ADMIN+USER.

### `entity`
- **`RoleEntity`** — JPA сущность `roles` (`id`, `name`, unique).
- **`UserEntity`** — `users`: организация, canonical email, BCrypt-хеш, имя, активность, disabledAt, роли M:N, created/updated/lastLogin, tokenVersion, optimistic `@Version`. `prePersist()` задаёт UUID, если отсутствует. Временные отметки создает БД (`@Generated`).

### `event`
- **`UserSecurityStateChangedEvent`** — UUID пользователя для локальной after-commit инвалидации опционального кэша.

### `exception`
- **`UserExceptionHandler`** — преобразует `UserVersionConflictException` в стандартизованную ошибку через `ApiExceptionResponseSupport`; `handleUserVersionConflict`.
- **`UserVersionConflictException`** — типизированный `409 Conflict`: `userId`, `expectedVersion`, `actualVersion`.

### `mapper`
- **`UserRoleMapper`** — `toRoleNames(user)` требует **ровно одну** известную `SystemRole`; `requireRoleName(...)` не разрешает пустые/неизвестные роли. Возвращает immutable Set.

### `repository`
- **`RoleRepository`** — `findByName` и CRUD для системных ролей.
- **`UserRepository`** — JPA и security SQL. `findByEmail`, `existsByEmail`; `findByIdWithRolesAndOrganization`; `findByIdForSecurityUpdate`; `lockOrganizationForAuthentication` (`FOR SHARE`); `updateLastLoginAtWithoutVersion`; выборки ID с/без org/role; `findAllByIdsWithRolesAndOrganization`; `findEnabledAdminsForUpdate` (`PESSIMISTIC_WRITE`); `findByIdAndOrganizationId`; `findByIdWithOrganization`; `findIdsByOrganizationId`; `hasActiveRefreshTokens`; `hasPermanentDeletionDependencies`; групповые `countBy...`. Порядок блокировок: organization → user → refresh token.

### `service`
- **`OrganizationStatusCacheInvalidationListener`** — after-commit `onOrganizationSecurityStateChanged`, разбивает пользователей организации по 1000 ID, передаёт в `evictAll`. Не является границей безопасности; ошибки журналируются.
- **`PlatformOrganizationInvariantVerifier`** — production startup `run`: проверка существования и активности platform organization, неотрицательных версий, отсутствие обычных пользователей и наличие SUPER_ADMIN. **Текущая ошибка:** не проверяет, что хотя бы один SUPER_ADMIN enabled.
- **`UserAccessPolicy`** — `requireUserManager`, `isAdmin`, `isSuperAdmin`, `isEnabledAdmin`, `hasRole`, `resolveTargetOrganizationId`, `rejectPlatformOrganizationCreation`, `isPlatformOrganization`, `rejectSuperAdminMutation`, `rejectSelfManagement`, `rejectAdminManagingAdmin`, `normalizeListRole`, `normalizeRoles`, `requireExactlyOneRequestedRole`, `requireExpectedVersion`, `normalizeFullName`, `requireValidNewPassword`, `userManagerRequired`; внутренний `hasAuthority`. Назначение: единая application-level авторизация.
- **`UserCommandOperations`** — транзакционные сценарии: `create`, `updateUser`, `updateEnabled`, `updateRoles`, `resetPassword`, `permanentlyDelete`; вспомогательные `saveAndRefresh`, `incrementTokenVersion`, `publishSecurityStateChanged`, `isEmailUniqueViolation`, `duplicateEmail`, `nullToEmpty`. Критично, что отзыв refresh-сессий выполняется в общей транзакции при изменении security state.
- **`UserEntityAccess`** — `findVisible`, `findForSecurityMutation`, `lockOrganization`, `assertNotLastEnabledAdmin`, `userNotFound`, `detachVisibleSecuritySnapshot`. Получает предварительный снимок, отделяет его от persistence context, берёт lock на организацию, затем lock на пользователя, повторно проверяет scope.
- **`UserManagementProperties`** — `permanentDeletionRetention`: по умолчанию 7 дней, максимум 365; zero разрешён политикой конфигурации. Должно согласовываться с юридической политикой хранения.
- **`UserQueryOperations`** — `findAll`, `findDetailsById`, `statistics`, `normalizePageable`: страничная выдача через IDs и secondary fetch, сортировка с tie-breaker `id`, tenant restrictions, aggregate counts.
- **`UserResponseMapper`** — `toResponse`, `toDetailsResponse`; исключает `passwordHash` и `tokenVersion` из API-ответов.
- **`UserRoleAssignmentResolver`** — `resolve`: SUPER_ADMIN назначает USER/ADMIN, ADMIN — только USER, SUPER_ADMIN не назначается через обычный endpoint; роль загружается из каталога.
- **`UserSecurityStatus`** — status snapshot: organizationId, флаги активности, tokenVersion, organizationAuthVersion; проверяет версии.
- **`UserService`** — публичный фасад; `@Transactional` для команд, `readOnly + REPEATABLE_READ` для списков и статистики. Делегирование `commands` / `queries` предотвращает чрезмерный размер одного сервиса.
- **`UserStatusCacheInvalidationListener`** — after-commit `onUserSecurityStateChanged`: удаляет состояние одного пользователя из Redis.
- **`UserStatusCacheProductionInvariantVerifier`** — `run`: запрещает best-effort security cache в production — важная защита.
- **`UserStatusCacheProperties`** — `enabled` default false, `ttl` до 5 минут, префикс ключа. `isEnabled`, `effectiveTtl`, `effectiveKeyPrefix`.
- **`UserStatusCacheService`** — `getStatus`, `evict`, `evictAll`; внутренние `loadFromDatabase`, `cache`, `parse`, `parseBooleanStrict`, `serialize`, `key`. При отключённом кэше проверка всегда идёт в PostgreSQL; при ошибке Redis возврат к БД, при ошибке БД исключение не скрывается. Формат статус-кэша включает обе версии security epoch.

### `validation`
- **`BcryptUtf8Length`** — constraint максимального размера пароля в UTF-8.
- **`BcryptUtf8LengthValidator`** — `initialize`, `isValid`: считает байты, а не Java char.
- **`PasswordPolicy`** — `isValidNewPassword`, `hasValidUtf8Length`, `utf8Length`: ≥12 кодовых точек, разные классы символов, запрет ISO controls, ≤72 UTF-8 байт для BCrypt.
- **`PasswordValidator`** — Bean Validation adapter, `isValid`.
- **`UserEmailNormalizer`** — `normalizeAndValidate`, `normalizeStored`; внутренние `normalize`, `requireEmail`, `canonicalize`, `validateCanonicalEmail`, `rejectControlCharacters`, `rejectWhitespace`, `validateAtSign`; trim + Locale.ROOT lowercase, длина ≤255, один @, no whitespace/control.
- **`ValidPassword`** — декларативный парольный constraint.

## 4. Проверенные архитектурные решения

1. **Multi-tenancy.** Tenant ADMIN видит только пользователей своей организации. SUPER_ADMIN допускает глобальную видимость по правилам policy. Ограничение повторено на уровне `UserEntityAccess`.
2. **RBAC.** Роль сохраняется ровно одна; известные роли `USER`, `ADMIN`, `SUPER_ADMIN` через `SystemRole`. Публичный API не позволяет назначить SUPER_ADMIN и менять системных SUPER_ADMIN.
3. **Конкурентные обновления.** `@Version` + `expectedVersion` возвращают 409; организация блокируется перед пользователем. Есть реальный PostgreSQL concurrency test на двух администраторов.
4. **Отзыв сессий.** При email/role/password/enabled меняются tokenVersion и refresh-сессии; всё должно быть в одной транзакции. Есть интеграционный тест commit/rollback вместе с аудитом.
5. **Безопасность кэша.** Redis — только best-effort; production verifier его запрещает. Это правильно, поскольку cache eviction не является надежной security fence.
6. **Постоянное удаление.** SUPER_ADMIN-only, disabled account, confirmationEmail, retention policy, absence of active refresh and dependency rows, строгие FK, audit event; это *безопасный отказ*, а не неограниченное каскадное удаление.
7. **Аудит.** Снимок актёра строится по текущему tenant-scoped пользователю; потеря БД — ошибка аудита, а не fallback на недостоверные данные.

## 5. Аудит дефектов и рисков (отделены факты от гипотез)

### P2 — подтверждённый логический пробел: disabled SUPER_ADMIN

В `PlatformOrganizationInvariantVerifier.run()` проверяются `countByOrganization_Id` и `countByOrganizationIdAndRole`, но второй COUNT включает как enabled, так и disabled. Поэтому в организации, содержащей *только отключённых* SUPER_ADMIN, проверка пройдёт, хотя использовать платформенное администрирование невозможно. Это риск availability/control-plane integrity, **не подтвержденный privilege escalation**.

**Исправление:** добавить `UserRepository.countEnabledByOrganizationIdAndRole`, требовать `>=1` для `SUPER_ADMIN`. В комплекте находится `apply_user_startup_fix.py` — небольшой безопасный адресный фикс, обновляющий также unit-тесты.

### P2/P3 — верифицировать схему физического удаления на актуальной DB

`hasPermanentDeletionDependencies` явно учитывает `chat_sessions`, дневные usage rollups, квоты, KB memberships, `audit_outbox`. В новых миграциях могут существовать дополнительные FK на `users`. Фактический `DELETE` будет защищён внешними ключами и завернётся в понятный `409`, но список предварительных проверок может расходиться с реальной схемой. Сверять с `pg_constraint` после *каждой* новой миграции и иметь интеграционный тест для обнаруженных ссылок. Не предлагать `CASCADE` как автоматическое решение.

### P3 — policy/UX: значение retention=0

Конфигурация намеренно допускает нулевой срок хранения для `permanentDeletionRetention`; **это не программная ошибка**. Для реального production требования могут требовать ненулевой минимум/soft deletion и отдельную политику хранения персональных данных и аудита. Нельзя менять default без продуктового решения.

### P3 — security cache в non-production

При включённом кэше возможна гонка между чтением старого security state, after-commit eviction и последующей записью старого значения в Redis. Наличие after-commit listeners не доказывает немедленной инвалидизации. Продукт уже правильно запрещает данный кэш в production; использовать его только в dev/test, явно учитывая задержку до TTL.

### P3 — полнота email validation

`UserEmailNormalizer` проверяет canonical representation, наличие одного `@`, длину, управляющие/пробельные символы; **не выполняет полной синтаксической валидации домена**. HTTP DTO дополнительно помечены `@Email`, но прямые service-вызовы зависят от нормализатора. Если продукт требует полноценную проверку формата по единым правилам на всех входах, добавить единый policy/test. Не следует применять DNS/MX-проверки в каждом REST-запросе как «улучшение» по умолчанию.

### Проверено: мнимые проблемы, которые исправлять не нужно

- `normalizeFullName` в реальном исходнике использует `replaceAll("\\s+", " ")`: regex корректный, это не `"\s+"` с Java escape-пробелом.
- `UserStatusCacheService` не «открывает доступ при падении Redis»: fallback обращается к PostgreSQL, а сбой БД не превращается в успешную авторизацию.
- `@Version` в `UserEntity` вместе с `expectedVersion` предназначен для пользовательских команд; `updateLastLoginAtWithoutVersion` не должен из-за входа в систему генерировать 409 в админке.
- `UserStatisticsResponse.total != administrators + users` у SUPER_ADMIN является **оговоренным контрактом**, поскольку SUPER_ADMIN относится к total.
- В `UserEntityAccess` порядок pessimistic locks не случайный: сначала organization, затем user. Не переставлять без полного анализа auth/organization потоков.

## 6. Имеющиеся тесты и обязательная регрессия

В репозитории обнаружены тесты `UserManagementPostgresIntegrationTest`, `UserSecurityTransactionIntegrationTest`, `UserServiceAuthorizationBoundaryTest`, `PlatformOrganizationInvariantVerifierTest`, `UserStatusCacheServiceTest`, `UserServiceReadTest`, `UserRoleMapperTest`, `PasswordPolicyTest`, `UserEmailNormalizerTest`, тесты controller/DTO/exception и другие.

Проверки, уже заявленные тестовыми методами:
- `concurrentDuplicateEmailCreationProducesOneSuccessAndOneConflict`
- `adminOrgACannotSeeOrChangeUserOrgB`
- `twoConcurrentDisablesLeaveOneActiveAdmin`
- `concurrentDisableAndRoleRemovalLeaveOneActiveAdmin`
- `successfulSecurityMutationRevokesRefreshSessionInSameCommit`
- `auditFailureRollsBackMutationAndRefreshRevocation`
- `emptyDisabledUserCanBePermanentlyDeleted`
- `auditEvidenceSurvivesUserDeletionAndLiveLinkIsRemoved`

**Добавить:** `rejectsPlatformWhenAllSuperAdminsDisabled` — в комплекте исправления.

```bash
cd backend
./mvnw test -Dtest=PlatformOrganizationInvariantVerifierTest
./mvnw test -Dtest=UserServiceTest,UserServiceAuthorizationBoundaryTest,UserStatusCacheServiceTest,PasswordPolicyTest,UserEmailNormalizerTest
# Для тестов с Testcontainers/PostgreSQL необходим рабочий Docker runtime.
./mvnw verify
```

**Важно:** приведённая команда является рекомендацией, а не утверждением, что Maven-тесты уже запущены. В текущем анализе выполнялась статическая сверка с GitHub и изучение списка и содержания тестов.

## 7. Приоритеты

1. Закрыть startup-инвариант *enabled SUPER_ADMIN* и добавить unit-тест.
2. Прогнать весь regression suite и Testcontainers PostgreSQL; проверить поведение auth/logout после смены email, роли, пароля и disable.
3. Сверить FK и список preflight-зависимостей удаления с последними Flyway-миграциями.
4. Утвердить продуктовую retention/GDPR/152-ФЗ политику для audit snapshots и физического удаления; не удалять аудит «для удобства».
5. Не превращать логический модуль `user` в отдельный микросервис; продолжать архитектуру modular monolith с интерфейсами между доменами.

## 8. Современность на октябрь 2026

Стек и подходы выглядят современно: Java 21 LTS, Spring Security method authorization, Jakarta Validation, Hibernate ORM/JPA, optimistic concurrency + PostgreSQL row locks, транзакционный аудит, JSpecify, однозначная системная роль, tenant-aware repository queries, изоляция runtime security cache. Наличие Lombok само по себе не является устаревшим решением. Архитектура не требует переписывания на WebFlux или микросервисы для «современности».

**Итог:** по просмотренному коду нет оснований утверждать о подтверждённой P0 уязвимости. Один конкретный P2 пробел найден и снабжён адресным патчем. Полноценный security sign-off требует запуска тестов и изучения фактической production-конфигурации `auth`, `organization`, `audit` и PostgreSQL.
