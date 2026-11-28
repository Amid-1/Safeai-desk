# SafeAI Desk — модуль `ru.safeai.gateway.organization`

Java 21, Spring Boot, Spring Data JPA, PostgreSQL и Flyway.

## 1. Назначение и границы модуля

Модуль управляет организациями (tenant organizations) и защищённой платформенной организацией (platform organization):
созданием,
переименованием, чтением, поиском, оценкой последствий отключения, отключением и повторным включением. Он не создаёт
пользователей,
не назначает системные роли, не реализует JWT и не хранит физические AI-вызовы: эти обязанности разделены с `user`,
`auth`, `chat`,
`model` и `audit`.

**Основные инварианты:**

1. Действия, изменяющие организацию, доступны только `SUPER_ADMIN`; `ADMIN` может читать только сведения собственной
   организации.
2. Платформенную организацию нельзя создать/изменить/отключить через обычные административные методы. Её ID берётся из
   `safeai.platform.organization-id`.
3. `name` непустое, до 255 символов; уникальность проверяется **PostgreSQL** через `normalized_name` и уникальный
   индекс, а не только Java-проверкой «сначала поиск, потом вставка».
4. Каждое изменение поддерживает `expectedVersion` / `@Version`: устаревший административный запрос не должен затирать
   новое состояние.
5. Отключение увеличивает `authVersion`, отзывает refresh-сессии в транзакции, публикует событие для последующих
   действий и формирует аудит.
6. Устаревшие security epochs не должны разрешать **новые** AI-операции после подтверждённого отключения организации.
   Граница безопасности реализована также триггерами V72.
7. Аудит должен переживать изменения справочника организаций; исторические имена фиксируются снапшотами, а не
   восстанавливаются по текущему имени.

### Основные зависимости

```text
OrganizationController
        |
        v
OrganizationService                 <-- @Transactional / security boundary
        |                                       |
        +--> OrganizationAccessPolicy           |-- PlatformProperties
        +--> OrganizationCommandOperations ----+-- OrganizationRepository / EntityManager
        |                                       |-- OrganizationNameNormalizer
        |                                       |-- SessionRevocationPort
        |                                       |-- AuditEventService / EventPublisher
        +--> OrganizationQueryOperations -------+-- OrganizationRepository
                                                +-- OrganizationImpactQueryRepository
                                                +-- OrganizationResponseMapper

OrganizationAuditSnapshotProvider --> OrganizationRepository --> PostgreSQL

PostgreSQL: organizations, users, user_roles, roles, refresh_tokens, chat_turns,
            audit_outbox / audit_events; organization auth epoch (V25, V72)
```

## Структура исходников

```text
organization/
├── audit/
│   └── OrganizationAuditSnapshotProvider.java
├── controller/
│   └── OrganizationController.java
├── dto/
│   ├── CreateOrganizationRequest.java
│   ├── DisableOrganizationRequest.java
│   ├── EnableOrganizationRequest.java
│   ├── OrganizationDirectoryResponse.java
│   ├── OrganizationDisableImpactResponse.java
│   ├── OrganizationPageResponse.java
│   ├── OrganizationResponse.java
│   ├── OrganizationType.java
│   ├── UpdateOrganizationEnabledRequest.java
│   └── UpdateOrganizationRequest.java
├── entity/
│   └── OrganizationEntity.java
├── event/
│   └── OrganizationSecurityStateChangedEvent.java
├── exception/
│   ├── OrganizationExceptionHandler.java
│   └── OrganizationVersionConflictException.java
├── repository/
│   ├── OrganizationImpactQueryRepository.java
│   └── OrganizationRepository.java
├── service/
│   ├── OrganizationAccessPolicy.java
│   ├── OrganizationCommandOperations.java
│   ├── OrganizationNameNormalizer.java
│   ├── OrganizationQueryOperations.java
│   ├── OrganizationResponseMapper.java
│   └── OrganizationService.java
└── README.md
```

## 3. Реальные бизнес-процессы: пошагово

### 3.1. Создание организации

1. HTTP `POST /api/organizations`; Spring Security пропускает `SUPER_ADMIN`.
2. `OrganizationService.create()` открывает **write transaction** и вызывает `commands.create()`.
3. `OrganizationAccessPolicy.requireSuperAdmin()` повторно проверяет роль на уровне приложения.
4. `OrganizationNameNormalizer.canonicalize()` отбрасывает пустое/слишком длинное имя и приводит пробелы.
5. Создаётся `OrganizationEntity`, `enabled=true`, `authVersion=0`, новый `id` устанавливается в `@PrePersist`.
6. `saveAndFlush()` выполняет запись. DB trigger V20 вычисляет canonical `name` и `normalized_name`; unique index V23
   отклоняет совпадающее имя. `entityManager.refresh()` возвращает DB-generated timestamp/version/name.
7. Именованные нарушения уникальности переводятся в `ConflictException`; неизвестная ошибка БД **пробрасывается**.
8. `AuditEventService.recordPrincipalSnapshot()` формирует audit snapshot; при исключении транзакция откатывается.
   Возвращается версия и тип организации.

**Сильная сторона:** нет TOCTOU-ошибки уникальности «проверили `exists`, потом конкурент создал такую же строку».
Требуется интеграционный тест на конкурирующие вставки — соответствующий тест в репозитории найден.

### 3.2. Переименование

`PATCH /api/organizations/{id}` → SUPER_ADMIN → транзакция → запрет platform-ID → `findByIdForSecurityUpdate(id)` с
`PESSIMISTIC_WRITE` → сравнение `expectedVersion` → canonicalize(name) → no-op без лишнего аудита при точном совпадении
→
сохранение/refresh → audit. При одновременных изменениях ожидается ровно один успешный переход по прежней версии;
устаревший
запрос получает HTTP 409.

**Нюанс:** API использует тот же `expectedVersion`, который при отключении/включении возрастает: это желаемое
предотвращение
перезаписи состояния. Имя и статус не должны изменяться независимыми устаревшими командами.

### 3.3. Отключение

`POST /api/organizations/{id}/disable` → только SUPER_ADMIN → запрет platform-ID → DB row lock → `expectedVersion` →
сравнение
нормализованных `confirmationName` и текущего имени → если уже выключена, возврат без side effects; иначе
`enabled=false`,
`authVersion = Math.addExact(oldAuthVersion, 1)` → `saveAndFlush` + `refresh` →
`SessionRevocationPort.revokeAllForOrganization()` →
событие об epoch change → аудит → commit.

Реальный `UserSessionRevocationService` implements `SessionRevocationPort` и использует
**`@Transactional(propagation = MANDATORY)`**. Значит, штатный вызов из `OrganizationService.disable()` работает в
**той же** транзакции, а не в отдельной `REQUIRES_NEW`; ошибка при отзыве откатывает основное изменение. Это существенно
усиливает гарантии по сравнению с абстрактным интерфейсом.

**V72:** перед новой записью `chat_turns` и новой физической `model_execution_attempts` PostgreSQL сверяет `enabled` и
`authorized_organization_auth_version` с `organizations.auth_version`, удерживая `FOR SHARE` до завершения транзакции.
Отключение берёт `FOR UPDATE` на организацию, поэтому новый переход через эту DB-границу после commit отключения не
должен пройти. Уже отправленный провайдеру HTTP-запрос не «отзывается» SQL-триггером; его стоимость и результат должны
обрабатываться по правилам AI-модуля.

### 3.4. Повторное включение

`POST .../enable` → SUPER_ADMIN → row lock → `expectedVersion` → `enabled=true`.
**`authVersion` не уменьшается и не сбрасывается**.
Старые токены, привязанные к прежней эпохе, не оживают. Новая сессия после успешной повторной аутентификации получает
текущую эпоху.
Код не пытается «разотзывать» refresh-сессии — правильно.

### 3.5. Чтение и поиск

- `SUPER_ADMIN`: чтение всех организаций, полнотекстового имени **нет**, поиск по подстроке имени или точному UUID,
  до 50 записей в справочнике.
- `ADMIN`: список и `/me` только собственной организации, `/{id}` чужой организации выдаёт 404 без раскрытия её
  существования.
- Операции чтения не должны менять БД. `Pageable` нормализуется через общий whitelist сортировок и стабильный
  tie-breaker по `id`.
- `/disable-impact` не выполняет отключение и не обеспечивает блокировки до будущего вызова. Это консультационные
  показатели для подтверждения действия.

## 4. Классы, методы и контракты

Ниже **OK** — корректно в просмотренном контексте; **NOTE** — допущение/ограничение; **FIX/TEST** — рекомендуется
исправить или
доказать тестом. Используются исходные имена методов и полей, чтобы README можно было применять при code review.

### 4.1. `audit/OrganizationAuditSnapshotProvider.java`

**Назначение:** внутренний SPI `AuditTargetOrganizationSnapshotProvider`, получение имени целевой организации для
аудита.

- `organizationRepository` (`final`): JPA-зависимость, передаётся Lombok-конструктором `@RequiredArgsConstructor`;
  **OK**.
- `findName(UUID organizationId)`: `requireNonNull`; `findById`; `map(OrganizationEntity::getName)`, `trim`, отбор
  пустой строки,
- `Optional<String>`. **OK**: отсутствующая организация явно представлена `Optional.empty`, ошибки БД не скрываются;
  чтение имени
  делается из canonical DB-row.
- **NOTE:** `trim()` покрывает лишь базовые пробельные символы, но canonical DB-name непустое и нормализованное;
  унифицировать
  обработку Unicode можно без изменения публичного контракта.
- **NOTE:** при удалённой организации имя уже нельзя получить живым запросом — V37 предусматривает исторические audit
  snapshots;
  не нужно пытаться «всегда» восстанавливать имя через текущую строку.
- **TEST:** существующая организация; пустое имя не возвращается; отсутствует строка; repository error пробрасывается;
  audit-событие с изменением имени сохраняет прежний snapshot.

### 4.2. `controller/OrganizationController.java`

**Назначение:** HTTP boundary REST `/api/organizations`. Поля: `organizationService` (**OK**, dependency injection);
аннотации `@Validated`, `@RestController`, `@RequestMapping`, `@RequiredArgsConstructor` (**OK**).

- **`create` / `POST /`**
  - Роль — SUPER_ADMIN
  - Сигнатура и поведение — `@Valid CreateOrganizationRequest`, `@AuthenticationPrincipal`, HTTP 201
  - Аудит — **OK**: двойная авторизация через сервис
- **`findAll` / `GET /`**
  - Роль — ADMIN/SUPER_ADMIN
  - Сигнатура и поведение — `Pageable` (`size=20`, `createdAt DESC`)
  - Аудит — **OK**: сервис ограничивает область и сортировки; не равнозначно произвольному JPA `findAll`
- **`directory` / `GET /directory`**
  - Роль — SUPER_ADMIN
  - Сигнатура и поведение — `query @Size(max=255)`, `limit @Min(1) @Max(50)`
  - Аудит — **OK**: HTTP-validation и независимая проверка сервиса; test UUID/string
- **`findCurrentOrganization` / `GET /me`**
  - Роль — ADMIN/SUPER_ADMIN
  - Сигнатура и поведение — Не принимает произвольный organization ID
  - Аудит — **OK**
- **`findById` / `GET /{id}`**
  - Роль — ADMIN/SUPER_ADMIN
  - Сигнатура и поведение — UUID path variable
  - Аудит — **OK**: чужой tenant скрыт от ADMIN
- **`disableImpact` / `GET /{id}/disable-impact`**
  - Роль — SUPER_ADMIN
  - Сигнатура и поведение — Результат лишь информационный
  - Аудит — **NOTE**: UI не должен считать preview достаточной гарантией отсутствия задач
- **`updateName` / `PATCH /{id}`**
  - Роль — SUPER_ADMIN
  - Сигнатура и поведение — `@Valid UpdateOrganizationRequest` и версия
  - Аудит — **OK**
- **`disable` / `POST /{id}/disable`**
  - Роль — SUPER_ADMIN
  - Сигнатура и поведение — Версия + текст подтверждения
  - Аудит — **OK**: осознанный destructive API
- **`enable` / `POST /{id}/enable`**
  - Роль — SUPER_ADMIN
  - Сигнатура и поведение — Версия
  - Аудит — **OK**: отдельно от disable

**Входные переменные:** `request` (Bean Validation), `currentUser` (аутентифицированный субъект), `id` (UUID),
`pageable`, `query`, `limit`. Права из principal проверяются дважды: декларативно `@PreAuthorize` и императивно в
`OrganizationAccessPolicy`, что полезно при прямых вызовах service из jobs/tests.

**Пограничные моменты:** авторизация через `@PreAuthorize` предполагает включённый method security в приложении; это
надо проверять MVC/security integration tests. `@AuthenticationPrincipal(errorOnInvalidType = true)` предотвращает
скрытую подмену типа principal, но **сам по себе** не подтверждает живость/epoch — это задача authentication/security
chain.

### 4.3. `dto/CreateOrganizationRequest.java`

- `name`: `@NotBlank`, `@Size(max=255)` — **OK** для HTTP; `OrganizationNameNormalizer` повторяет проверку на
  application boundary.
- Java record, отсутствие mutable setters — **OK**.
- **TEST:** `null`, пусто, пробелы, 256 символов, Unicode whitespace.

### 4.4. `dto/DisableOrganizationRequest.java`

- `expectedVersion`: `Long` + `@NotNull @PositiveOrZero` — **OK**; именно boxed type позволяет отличить отсутствующий
  параметр
- от нуля.
- `confirmationName`: `@NotBlank @Size(max=255)` — **OK**; для destructive-действия сервис сравнивает нормализованное
  имя,
- а не просто факт наличия текста.
- **Подтверждение имени.** NFKC/удаление кавычек допускают текстовые варианты; это документированная политика удобства,
  не точное
- байтовое подтверждение.
- **TEST:** неверное имя/версия, неизменённая строка, отказ для platform organization.

### 4.5. `dto/EnableOrganizationRequest.java`

- `expectedVersion`: `@NotNull @PositiveOrZero` — **OK**; повторное включение не имеет подтверждения имени, зато требует
  роль и актуальную версию. Если в продукте нужен ещё один барьер, это отдельное UX/security-решение, а не ошибка
  аннотации.

### 4.6. `dto/OrganizationDirectoryResponse.java`

Поля: `id` (`UUID`), `name` (`String`), `enabled` (`boolean`), `type` (`OrganizationType`), `protectedOrganization`
(`boolean`, сериализуется как JSON **`protected`**), `version` (`long`).

- Compact constructor требует `id`, `type`, `name`, `version >= 0`: **OK**.
- `PLATFORM => protectedOrganization=true`, `TENANT => false`: **OK** внутри текущей модели.
- **NOTE:** тип берётся из настроенного platform UUID, а не отдельной колонки БД. Несогласованная конфигурация разных
  экземпляров может изменить видимое значение `type/protected` для одного и того же ID.
- **TEST:** JSON-имя `protected`, оба типа, запрещённые сочетания, version = 0 / -1.

### 4.7. `dto/OrganizationDisableImpactResponse.java`

Поля ответа включают `organizationId`, `organizationVersion` и шесть счётчиков из `ImpactSnapshot`.
Конструктор проверяет наличие ID и неотрицательность версии и счётчиков.
`organizationVersion` передаётся клиентом как `expectedVersion` при подтверждении отключения.
Число операций и сессий может измениться между предпросмотром и подтверждением.

### 4.8. `dto/OrganizationPageResponse.java`

Поля: `content` (неизменяемая копия списка), `page`, `size`, `totalElements`, `totalPages`.

- Compact constructor: `List.copyOf`, проверка page >= 0, size >= 1, totalElements/totalPages >= 0 — **OK**.
- `from(Page<OrganizationResponse>)`: берёт показатели из Spring Data Page — **OK**.
- **NOTE O-10:** нет жёсткой сверки (`totalPages` соответствует `totalElements/size`), но принудительная сверка может
  испортить совместимость с Spring Data для нестандартных Page/динамических изменений; если вводить — с тестами.
- **TEST:** пустая страница, последняя, большой total, попытка мутации `content`.

### 4.9. `dto/OrganizationResponse.java`

Поля: `id`, `name`, `enabled`, `type`, JSON `protected`, `version`, `createdAt`, `updatedAt`.

- Compact constructor валидирует ID, тип, обе временные метки, имя, версию, комбинацию типа и защиты — **OK**.
- **NOTE:** не проверяет `updatedAt >= createdAt` в Java; DB V4 защищает storage-инвариант. Не нужно дублировать всё без
  бизнес-обоснования.
- **NOTE:** незаполненный `normalizedName` не экспортируется — правильно, это внутренняя DB-деталь.
- **TEST:** сериализация, неизменность immutable record, ошибка противоречивого type/protected.

### 4.10. `dto/OrganizationType.java`

Enum `PLATFORM`, `TENANT`: **OK** — простое явное отражение служебного/клиентского tenant. Нет `UNKNOWN`; отсутствие
типа должно приводить к ошибке, а не к молчаливому `TENANT`. **TEST:** mapper правильно выводит тип для платформенного
ID.

### 4.11. `dto/UpdateOrganizationEnabledRequest.java`

Поля `enabled: Boolean`, `expectedVersion: Long`, оба `@NotNull`; версия неотрицательна.
**По отдельности тип корректен**,
но в представленном контроллере **нет** endpoint, который использует это DTO: вместо него существуют раздельные
`DisableOrganizationRequest` и `EnableOrganizationRequest`.

**Рекомендация:** пометить как legacy/неиспользуемое только после поиска внешних тестов/клиентов; не возвращать общий
`PATCH enabled`, потому что он обойдёт подтверждение имени при отключении. Наличие DTO само по себе endpoint не
открывает.

### 4.12. `dto/UpdateOrganizationRequest.java`

Поля `name` (`@NotBlank`, `@Size(max=255)`), `expectedVersion` (`@NotNull`, `@PositiveOrZero`) — **OK**. Контракт
отличается
от создания организации наличием optimistic version. **TEST:** нельзя переименовать stale version, проверка 255/256 и
whitespace.

### 4.13. `entity/OrganizationEntity.java`

**Соответствие БД `organizations`:**

- **`id: UUID`**
  - Хранение/аннотация — `@Id`; UUID создаётся в `prePersist`
  - Оценка — **OK**, нет скрытой зависимости от PostgreSQL sequence
- **`name: String`**
  - Хранение/аннотация — `varchar(255)`, not null
  - Оценка — **OK**, БД V20 дополнительно canonicalizes
- **`normalizedName: String`**
  - Хранение/аннотация — `normalized_name`, `insertable=false`, `updatable=false`
  - Оценка — **OK**, генерируется БД и индексируется; не допускать ручного сеттера в бизнес-логике
- **`enabled: boolean`**
  - Хранение/аннотация — not null, default Java `true`
  - Оценка — **OK**, явный флаг доступа организации
- **`authVersion: long`**
  - Хранение/аннотация — not null, V25 и CHECK >=0
  - Оценка — **OK**, security epoch; увеличивать только при значимых security changes
- **`version: long`**
  - Хранение/аннотация — `@Version`, not null
  - Оценка — **OK**, optimistic lock, отражает административные изменения
- **`createdAt: Instant`**
  - Хранение/аннотация — DB-generated, insertable/updatable=false
  - Оценка — **OK** при исправном trigger/default и `refresh()`
- **`updatedAt: Instant`**
  - Хранение/аннотация — DB-generated, insertable/updatable=false
  - Оценка — **OK**, требует DB integration checks

- `@Entity`, `@Table`, `@Getter`, `@Setter`, `@DynamicUpdate`: допустимое решение. **NOTE:** общий `@Setter` разрешает
  менять `id/authVersion` из произвольного Java-кода: контроль целостности обеспечен сервисом/DB, но более узкие
  setter-границы уменьшили бы площадь ошибочного изменения. Не менять API без поиска вызовов.
- `prePersist()`: генерирует UUID, затем `validateInvariant()`; **OK**.
- `preUpdate()`: проверяет те же инварианты; **OK**.
- `validateInvariant()`: `name != null && !isBlank`, `length<=255`, `authVersion>=0`, `version>=0` — **OK**, но это не
  единственный enforcement; V4/V20/V25 обеспечивают ряд DB CHECK/trigger invariants.
- **TEST:** изменение enabled под optimistic version, timestamps после DB refresh, update без имени, invalid epoch,
  trigger-induced canonicalization.

### 4.14. `event/OrganizationSecurityStateChangedEvent.java`

Поля: `organizationId: UUID`, `authVersion: long`. Compact constructor: nonnull, nonnegative — **OK**. Событие передаёт
достаточную информацию для последующей инвалидации security-кэша и связывает notification с эпохой.

**NOTE:** событие публикуется **до** commit Java-транзакции; подписчики должны слушать через
`@TransactionalEventListener(AFTER_COMMIT)`, если их побочные эффекты должны следовать только за commit. Изученный
`OrganizationStatusCacheInvalidationListener` следует именно этому паттерну. Неизвестные новые подписчики должны
проходить проверку того же правила.

### 4.15. `exception/OrganizationExceptionHandler.java`

Поля: `responses: ApiExceptionResponseSupport` (создаётся через `ApiErrorResponseFactory`); `@Slf4j`,
`@RestControllerAdvice`, `@Order(HIGHEST_PRECEDENCE + 11)`.

- Конструктор: централизует фабрику response, **OK**.
- `handleOrganizationVersionConflict(exception, request)`: логирует request ID, organization ID, expected/actual, URI;
  возвращает унифицированный `ApiErrorResponse`, статус/код/сообщение из исключения — **OK**.
- **NOTE:** порядок advice необходимо проверять при добавлении глобального generic `@ExceptionHandler(Exception.class)`
  . Текущий специфичный handler не должен быть вытеснен.
- **TEST:** HTTP 409, специализированный `ORGANIZATION_VERSION_CONFLICT`, корректный requestId, отсутствие stacktrace в
  публичном ответе.

### 4.16. `exception/OrganizationVersionConflictException.java`

Поля `organizationId`, `expectedVersion`, `actualVersion` immutable (`final`), Lombok `@Getter`. Конструктор вызывает
базовый `ApiException(HttpStatus.CONFLICT, ApiErrorCode.ORGANIZATION_VERSION_CONFLICT, publicMessage, null)`, проверяет
ID и неотрицательные версии.

**OK:** typed error и version data для логирования и диагностики. **NOTE:** класс наследует общий `ApiException`, а не
специализированный `ConflictException`, но передаёт явные HTTP 409 и код — это корректное осознанное решение, а не
ошибка.

### 4.17. `repository/OrganizationRepository.java`

Интерфейс `JpaRepository<OrganizationEntity, UUID>` — **OK**.

- `findByIdForSecurityUpdate(UUID id)`: `@Lock(PESSIMISTIC_WRITE)` над JPQL `where id=:id`, возвращает `Optional`.
  **OK**: транзакционная сериализация на одной организации. Lock обязательно держать до commit `OrganizationService`.
- `findAllStable(Pageable pageable)`: Page по JPQL `select organization` — **OK**, порядок/лимит накладывает Spring
  Data; tie-breaker `id` добавляется перед вызовом в query service.
- `searchDirectoryByName(String query, Pageable pageable)`: case-insensitive `LIKE` с явным `escape '!'` и параметрами —
  **OK** против SQL injection и wildcard-подстановок; параметр экранирует service. **NOTE:** `%` в начале шаблона =
  вероятный sequential scan для больших каталогов; нельзя утверждать обратное без `EXPLAIN ANALYZE`.
- **TEST:** реальный PostgreSQL, `'%','_','!'`, case-insensitive, имена с экранируемыми символами, блокировка двух
  конкурентных update.

### 4.18. `repository/OrganizationImpactQueryRepository.java`

Конструктор принимает `NamedParameterJdbcTemplate`. Метод `load` возвращает `ImpactSnapshot` из одного SQL-запроса.

- `enabledUsers` — включённые пользователи организации.
- `administrators` — включённые пользователи с ролью `ADMIN`.
- `activeRefreshSessions` — пригодные для продления refresh-записи. SQL проверяет активность пользователя
  и организации, отсутствие отзыва и замены, оба срока жизни и совпадение обеих версий безопасности.
- `activeChatOperations` — операции чата в состояниях `NEW` и `PROCESSING`.
- `startedPhysicalAttempts` — физические попытки в состоянии `STARTED`.
- `uncertainPhysicalAttempts` — записи с исходом `AMBIGUOUS` или `PROTOCOL_REJECTED`.
  Счётчик включает исторические исходы и сам по себе не определяет статус биллинга.

`ImpactSnapshot` проверяет неотрицательность всех шести счётчиков.
Запрос читает снимок. Уже отправленные запросы к провайдеру обрабатывает модуль AI.

### 4.19. `service/OrganizationAccessPolicy.java`

Поле `platformProperties` (`final`, `Objects.requireNonNull`) хранит платформенный ID. Private helper `hasAuthority()`
сравнивает `GrantedAuthority#getAuthority` с `SystemRole.authority()`; не использует substring/contains — **OK**.

- `requireOrganizationReader(currentUser)`: nonnull через `requirePrincipal`, допускает `ADMIN` или `SUPER_ADMIN` —
  **OK**.
- `requireSuperAdmin(currentUser)`: требует строго `SUPER_ADMIN` — **OK**.
- `requireMutableOrganizationRequest(organizationId,currentUser)`: nonnull ID → super-admin → запрет платформы — **OK**
  , порядок отказывает прежде обращения к БД.
- `isSuperAdmin(currentUser)`: делегирует `hasAuthority`, **OK**, предполагает nonnull principal, обеспечиваемый точками
  входа.
- `isPlatformOrganization(organizationId)`: сравнение с `platformProperties.organizationId()`, **OK** при однородной
  конфигурации.
- `organizationNotFound(id)`: `ResourceNotFoundException`, **OK** для no-leak / masking.
- `isAdmin(currentUser)`: private helper, **OK**.
- `hasAuthority(currentUser, role)`: точное сравнение authorities, **OK**.
- `rejectPlatformOrganizationMutation(organizationId)`: `ForbiddenOperationException`, **OK**.
- `requirePrincipal(currentUser)`: `Objects.requireNonNull`, **OK**.

**NOTE:** запрет platform-ID должен срабатывать в любом write endpoint. В текущем command flow это соблюдено. Для
критических platform-данных желательно отдельное контрольное наблюдение консистентности ID по узлам и startup-verifier.

### 4.20. `service/OrganizationCommandOperations.java`

**Основное назначение:** реализация команд и точка обеспечения write-инвариантов; класс package-private, не Spring bean,
поэтому `@Transactional` законно находится на его внешней фасадной границе `OrganizationService`.

**Поля/константы:**

- `ORGANIZATION_NAME_UNIQUE_CONSTRAINTS`: множество имён ожидаемых PostgreSQL unique constraints/indexes; правильно не
  превращать любое `DataIntegrityViolationException` в «имя занято».
- `organizationRepository`: JPA CRUD/row locking; `entityManager`: refresh DB-generated данных.
- `auditEventService`: транзакционный audit snapshot; `eventPublisher`: security change signal.
- `userSessionRevocationService: SessionRevocationPort`: identity-boundary без зависимости `organization` от реализации
  auth.
- `accessPolicy`: централизованные права и platform guard; `responseMapper`: формирует DTO.
- Все поля `final`, зависимости конструктора инъектируются напрямую. **NOTE:** нет `Objects.requireNonNull` на каждом
  параметре конструктора; Spring fail-fast при wiring, но ручные тесты с null дадут поздний NPE. Не критично.

**Методы и локальные переменные:**

- **constructor**
  - Переменные / смысл — 7 dependency parameters
  - Вывод — **OK**, соответствует сервисному фасаду
- **`create(request,currentUser)`**
  - Переменные / смысл — `canonicalName`, `entity`, `saved`
  - Вывод — **OK**: role check, нормализация, DB unique, audit
- **`updateName(id,request,currentUser)`**
  - Переменные / смысл — `entity`, `canonicalName`, `oldName`, `saved`
  - Вывод — **OK**: pessimistic lock + optimistic expected version + no-op
- **`disable(id,request,currentUser)`**
  - Переменные / смысл — `entity`, `expectedConfirmation`, `actualConfirmation`
  - Вывод — **OK**, текст подтверждения и версия до изменения
- **`enable(id,request,currentUser)`**
  - Переменные / смысл — `entity`
  - Вывод — **OK**, separate privileged action
- **`changeEnabled(entity,newEnabled,currentUser)`**
  - Переменные / смысл — `oldEnabled`, `oldAuthVersion`, `saved`, `sessionsRevoked`, `auditSessionsRevoked`
  - Вывод — **OK**: true→false increments auth epoch, same transaction revokes refresh sessions, snapshots audit; no-op
    не мутирует
- **`findMutableOrganizationForUpdate(id,currentUser)`**
  - Переменные / смысл — проверяет policy и получает row lock
  - Вывод — **OK**
- **`saveAndRefresh(organization)`**
  - Переменные / смысл — `saved`
  - Вывод — **OK**: `saveAndFlush` + `EntityManager.refresh` для DB canonicalization/timestamps/version
- **`requireExpectedVersion(organization,expectedVersion)`**
  - Переменные / смысл — `actualVersion` берётся через `getVersion`
  - Вывод — **OK**: explicit validation, HTTP 409 conflict; сравнение после lock
- **`isOrganizationNameUniqueViolation(exception)`**
  - Переменные / смысл — классификация DB constraint
  - Вывод — **OK**, проверить актуальность списка имён при новых миграциях
- **`duplicateOrganizationName(name)`**
  - Переменные / смысл — factory для `ConflictException`
  - Вывод — **OK**; сообщение содержит имя, доступно privileged admin

**Транзакционный порядок:** запрет платформы → row lock → version check → mutation → flush/refresh → session revocation
→ publish event → audit → commit. Он важен для безопасности; не переносить revocation на `@Async` /`REQUIRES_NEW` и не
выносить его за transaction без независимого доказательства эквивалентности.


### 4.21. `service/OrganizationNameNormalizer.java`

**Константы/поля:** `MAX_NAME_LENGTH = 255`; `COLLAPSIBLE_WHITESPACE = Pattern.compile("[\\\\p{Z}\\\\s]+")` (документная
запись Java pattern); `CONFIRMATION_QUOTES` — список ASCII и типографских кавычек. Обе `Pattern` immutable, compile-once
— **OK**.

- Private constructor: utility class не инстанцируется — **OK**.
- `canonicalize` проверяет исходный ввод на запрещённые управляющие символы, сворачивает Unicode-разделители
  и разрешённые ASCII-пробелы, затем проверяет непустое имя и предел в 255 единиц UTF-16.
- `normalizeForConfirmation` применяет NFKC, заменяет кавычки пробелами, сворачивает пробелы
  и приводит текст к нижнему регистру через `Locale.ROOT`.
- `rejectDisallowedControlCharacters` допускает TAB, LF, VT, FF и CR как сворачиваемые пробельные символы.
- Миграция `V73` согласует канонизацию с Java. Уникальный ключ вычисляется через `lower` в коллации PostgreSQL.

### 4.22. `service/OrganizationQueryOperations.java`

**Поля:** `MAX_PAGE_SIZE=100`, `MAX_DIRECTORY_LIMIT=50`, `DEFAULT_PAGE_SORT=createdAt DESC`,
`ALLOWED_SORT_PROPERTIES={createdAt,name,enabled,updatedAt}`, `organizationRepository`, `impactQueryRepository`,
`accessPolicy`, `responseMapper`. Immutable зависимости — **OK**.

- constructor: сохраняет четыре зависимости — **OK**.
- `findAll(currentUser,pageable)`: проверяет роль и nonnull; через `normalizePageable` ограничивает сортировку, page
  size и добавляет `id` -tie-breaker; SUPER_ADMIN получает global Page. ADMIN при `offset>0` получает пустую Page с
  total=1, при `offset=0` читает только собственную организацию — **OK**, минимизация утечки сведений.
- `findDirectory(query,limit,currentUser)`: SUPER_ADMIN; `query` nullable → empty string; `trim`, максимум 255,
  `Math.clamp(limit,1,50)`, сортировка `name,id`; распознавание UUID через `parseUuidOrNull`; иначе escaped LIKE по
  имени. **OK**, но clamp маскирует неправильный прямой application-вызов (HTTP уже отвергает недопустимый предел). Для
  строгой унификации можно на service boundary возвращать 400 и не clamp, только с согласованием существующих
  тестов/контракта.
- `findCurrentOrganization(currentUser)`: берёт ID только из principal, не из HTTP — **OK**.
- `findById(id,currentUser)`: ADMIN не может читать чужую организацию даже если знает UUID; `404` вместо `403` —
  сознательная no-leak политика; **OK**.
- `getDisableImpact(id,currentUser)`: права SUPER_ADMIN и запрет платформы, загружает entity, затем агрегат; эти два
  SELECT и последующее отключение не составляют одной атомарной операции, если UI делает их отдельными запросами. Для
  truly hard precondition нужна повторная проверка в самой write transaction.
- `normalizePageable(pageable)`: whitelist сортировок, limit100, id tie-breaker `FOLLOW_LAST_SORT_DIRECTION` — **OK**;
  не тождественно keyset-pagination, при разных HTTP-вызовах записи могут сдвинуться.
- `escapeDirectoryLikeLiteral(value)`: последовательно `!→!!`, `%→!%`, `_→!_`; **OK** при JPQL `escape '!'`. Порядок
  замен важен: exclamation должен обрабатываться первым.
- `parseUuidOrNull(value)`: UUID parse, false→null и переход к поиску имени — **OK**; при поддержке «строго 36 символов»
  нужны отдельные UX-требования.

### 4.23. `service/OrganizationResponseMapper.java`

Поле `accessPolicy` — для вычисления типа/защиты на основе platform UUID.

- `toResponse(OrganizationEntity organization)`: `protectedOrganization = isPlatformOrganization(id)`, конструирует
  `OrganizationResponse`, берёт id/name/enabled/version/timestamps — **OK**.
- `toDirectoryResponse(...)`: аналогичная логика, возвращает короткое DTO — **OK**.
- **NOTE:** единая функция `isPlatformOrganization` не допускает рассинхронизации типа между DTO **на одном узле**;
  межузловая консистентность зависит от `PlatformProperties`.
- **TEST:** platform/tenant, enabled/disabled, DB-generated timestamps.

### 4.24. `service/OrganizationService.java`

**Роль:** публичный Spring service / транзакционный фасад. Поля `commands`, `queries` — final. `@Service`,
`@EnableConfigurationProperties(PlatformProperties.class)` — **OK** при отсутствии конфликтующего дублирующего биндинга;
прочие настройки должны быть проверены в общей конфигурации.

**Конструктор:** принимает `OrganizationRepository`, `OrganizationImpactQueryRepository`, `AuditEventService`,
`ApplicationEventPublisher`, `PlatformProperties`, `SessionRevocationPort`, `EntityManager`; создаёт
`OrganizationAccessPolicy`, `OrganizationResponseMapper`, `OrganizationCommandOperations`, `OrganizationQueryOperations`
. **OK**, сохраняет внешний DI-контракт, package-private helper классы не требуют отдельных beans.

**Публичные методы и транзакции:**

| Метод | Аннотация | Роль | Оценка |
|---|---|---|---|
| `create` | `@Transactional` | Write | **OK** |
| `findAll` | `@Transactional(readOnly=true)` | Read | **OK**, но разные Page HTTP requests не один snapshot |
| `findDirectory` | `@Transactional(readOnly=true)` | Read | **OK** |
| `findCurrentOrganization` | `@Transactional(readOnly=true)` | Read | **OK** |
| `findById` | `@Transactional(readOnly=true)` | Read | **OK** |
| `getDisableImpact` | `@Transactional(readOnly=true)` | Read / preview | **OK** для preview; нет long-lived lock |
| `updateName` | `@Transactional` | Write | **OK** |
| `disable` | `@Transactional` | Write | **OK** |
| `enable` | `@Transactional` | Write | **OK** |

**NOTE:** `readOnly=true` — transaction hint/режим, а не авторизация и не автоматически `REPEATABLE READ`; это не
заменяет бизнес-проверки. `@Transactional` корректно применяется к публичным методам Spring proxy. Прямое создание
объекта через `new OrganizationService(...)` в unit tests **не** создаёт transaction proxy; интеграционные проверки
должны вызывать Spring bean.

## 5. Безопасность и конкурентность — подробный разбор

### 5.1. Матрица прав

| Операция | USER | ADMIN своего tenant | SUPER_ADMIN |
|---|---:|---:|---:|
| Создание организации | Нет | Нет | Да |
| Список всех организаций | Нет | Только своя | Да |
| `GET /me` | Нет | Своя | Platform organization по principal |
| `GET /{id}` | Нет | Только свой ID | Любой ID |
| Directory / поиск организаций | Нет | Нет | Да |
| Preview последствий отключения | Нет | Нет | Да (кроме платформенной) |
| Переименование | Нет | Нет | Да (кроме платформенной) |
| Отключение | Нет | Нет | Да (кроме платформенной) |
| Повторное включение | Нет | Нет | Да (кроме платформенной) |

Матрица зависит от штатного Spring Security authentication filter и проверки актуальности principal. Непосредственно в
`OrganizationAccessPolicy` нет повторного обращения к БД за состоянием пользователя: это отдельный security boundary
архитектуры. Для полной гарантии нужно также проверить `UserStatusCacheService` / JWT auth gates.

### 5.2. Pessimistic lock, optimistic version и сериализация

В mutation потоке используется двойной барьер: `findByIdForSecurityUpdate` удерживает строку организации с
`PESSIMISTIC_WRITE`, а `expectedVersion` защищает интерфейс администратора от выполнения команды по старому снимку. Это
не излишнее дублирование: первое сериализует конкурентные изменения, второе выявляет stale intent. DB `@Version`
добавляет protection при других write-путях Hibernate.

Lock order важен: disable принимает `organizations FOR UPDATE`; login/refresh используют `organizations FOR SHARE`,
затем user/token locks. Нельзя поменять порядок без анализа deadlock graph. По просмотренной V72 физический AI gate
также берёт `organizations FOR SHARE`. Две самостоятельные транзакции не должны держать разный порядок по
организациям/пользователям.

### 5.3. Безопасность после отключения

- `enabled=false` влияет на актуальные auth checks.
- `authVersion++` инвалидирует токены и AI reservation/attempt epoch даже после `enabled=true` при повторном включении.
- `UserSessionRevocationService` со свойством `MANDATORY` участвует в write transaction и требует reason из whitelist.
- Событие `OrganizationSecurityStateChangedEvent` из `OrganizationCommandOperations` используется для необязательных
  cache-invalidation действий; не должно быть единственным security fence.
- DB V72 защищает начало **новых** turn/physical attempt; уже начавшийся вызов не обещает отменять.

**Приёмочные security-тесты:** disable vs login; disable vs refresh; disable vs создание turn; disable vs новую physical
attempt; enable после disable не возрождает старые access/refresh токены; банк серверов с одинаковым platform UUID;
audit failure делает rollback бизнес-действия при требовании строгого audit.

### 5.4. Инвариант платформенной организации

При запуске production `PlatformOrganizationInvariantVerifier` из модуля `user` проверяет
существование и активность платформенной организации, неотрицательные версии, состав её ролей
и наличие хотя бы одного включённого `SUPER_ADMIN`. UUID задаётся через `PlatformProperties`.

## 6. База данных, миграции и границы доказательств

- **V1 / V4**
  - Что проверено — `organizations` и первоначальные unique/check, timestamps, версия
  - Комментарий — Структура не существует только в Java
- **V20**
  - Что проверено — `canonical_organization_name`, `normalize_organization_name`, `normalized_name`, триггер
    `sync_organization_name`, CHECK
  - Комментарий — БД — источник истины уникальности и canonicalization
- **V23**
  - Что проверено — `ux_organizations_normalized_name`
  - Комментарий — Уникальный индекс, в сценариях одновременного создания — основной барьер
- **V24**
  - Что проверено — Ролевая целостность `user_roles`, аудит/outbox, security hardening
  - Комментарий — Важно для смежных user/org операций
- **V25**
  - Что проверено — `organizations.auth_version`, snapshot epoch у refresh tokens
  - Комментарий — Доказуемая историческая epoch-семантика
- **V37**
  - Что проверено — `target_organization_name` в audit/outbox
  - Комментарий — Сохраняет имя организации на время события
- **V72**
  - Что проверено — DB guards на новую chat turn и physical attempt с row locking
  - Комментарий — Не является системой отмены уже выполненного provider call

| V73 | Unicode-пробелы, ISO controls и длина UTF-16 | Правила канонизации имён согласованы с Java |
**Нельзя** исправлять инварианты путём редактирования уже применённой V20/V23/V25/V72. Изменение PostgreSQL-правила
только новой Flyway migration после последней установленной версии, с проверкой данных и rollback/forward strategy.

**Ограничения:** наличие текста миграции не доказывает, что она установлена на конкретной базе; наличие `@Version` не
доказывает, что все сторонние SQL writers согласованы; наличие unit tests не доказывает порядок блокировок.
Подтверждение для production — `flyway info/validate`, инспекция constraints/indexes/triggers на целевой БД, настоящие
concurrent PostgreSQL tests.

## 7. Производительность, масштабирование, сопровождение

1. **Directory:** индекс B-tree по `normalized_name` защищает уникальность, но `%fragment%` на `lower(name)` не ускоряет
   автоматически. Измерить `EXPLAIN (ANALYZE, BUFFERS)` и cardinality. При большом каталоге рассмотреть `pg_trgm` /GIN и
   новую migration с учётом write overhead, а не добавлять индекс наугад.
2. **Page pagination:** deterministic sort плюс `id` упорядочивает одинаковые `createdAt`; offset pagination не
   гарантирует snapshot между запросами разных страниц. Для сотен тысяч организаций рассмотреть keyset, только при
   реальной потребности.
3. **Preview SQL:** все шесть счётчиков вычисляются одним SQL-запросом на общем снимке PostgreSQL. Отдельная
   загрузка версии организации до него не создаёт атомарный контракт со следующим disable.
4. **`@DynamicUpdate`:** может уменьшать лишние обновления колонок, но делать вывод о реальной производительности без
   Hibernate SQL logs нельзя. Не требуется заменять на ручной JDBC.
5. **Hibernate entity setters:** широко открытая мутабельность — issue дизайна домена, но изменение Lombok
   getter/setter-политики потребует анализа всех callers и тестов.
6. **Логирование:** `OrganizationExceptionHandler` пишет ID, версии, URI и requestId, а не secret; оценить redaction и
   MDC в общем audit/log stack.
7. **Обслуживание:** отделение `OrganizationCommandOperations` /`OrganizationQueryOperations` повышает читаемость, но
   class-level security policy фактически зависит от фасада — держать тесты на прямые команды и HTTP boundary.
8. **Распределённое окружение:** consistency platform UUID и DB clock/transaction ordering важнее стилистического
   выравнивания Java-кода.

## 8. Тесты и проверки перед выпуском

### Структура тестов

```text
organization/
├── controller/
│   └── OrganizationControllerSecurityTest.java
├── dto/
│   ├── OrganizationDisableImpactResponseTest.java
│   └── OrganizationRequestValidationTest.java
├── exception/
│   ├── OrganizationExceptionHandlerTest.java
│   └── OrganizationVersionConflictExceptionTest.java
├── repository/
│   └── OrganizationImpactQueryRepositoryTest.java
└── service/
    ├── OrganizationNameNormalizerTest.java
    ├── OrganizationPostgresIntegrationTest.java
    ├── OrganizationSecurityEpochPostgresIntegrationTest.java
    ├── OrganizationServiceAuthorizationBoundaryTest.java
    └── OrganizationServiceTest.java
```

`OrganizationUserBoundaryArchitectureTest` из пакета `architecture` проверяет направление зависимостей.
Тесты контроллера покрывают роли и HTTP-контракт. PostgreSQL-тесты проверяют уникальность имён,
конкурентное переименование, отзыв сессий и гонки отключения с входом, refresh и сменой роли.

Из корня репозитория с Java 21 и запущенным Docker:

```bash
cd backend
./mvnw test -Dtest='ru.safeai.gateway.organization.**.*Test,OrganizationUserBoundaryArchitectureTest'
./mvnw clean verify
```

### Приёмочные сценарии

1. Проверить `V73` на копии рабочей БД, если обновляется существующий стенд. Миграция прерывается
   при коллизиях исторических имён и не объединяет организации автоматически.
2. Сверить `SAFEAI_PLATFORM_ORGANIZATION_ID` на всех экземплярах с UUID платформенной организации в БД.
3. На HTTPS-стенде отключить тестовую организацию с активной сессией и проверить отказ со старыми
   access JWT и refresh. Повторно включить организацию и проверить новый вход.
4. Для расширения покрытия проверить на PostgreSQL состав всех шести счётчиков предпросмотра,
   включая данные двух организаций, устаревший refresh и статусы физических попыток.
5. Проверить откат отключения при сбое отзыва сессий или записи аудита. После ошибки сверить
   `enabled`, `authVersion` и refresh-сессии в БД.
