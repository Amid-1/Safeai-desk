# SafeAI Desk — модуль `organization`: техническая документация и аудит

**Состояние оценки:** 17 октября 2026 года  
**Целевая перспектива:** сопровождение в 2026–2027 годах  
**Стек:** Java 21, Spring Boot 4.1.x, Spring Framework 7 / Spring Data JPA 4, Jakarta Persistence, Hibernate, PostgreSQL, 
Flyway, Spring Security, Lombok.  
**Статус документа:** подробный **статический аудит и описание**; не акт о прохождении полного CI, нагрузочных тестов или 
внешнего пентеста.

> **Важно о границах проверки.** Основной материал — все 24 типа из присланного фрагмента `ru.safeai.gateway.organization`. 
> Для различения ошибки копирования и реальной реализации дополнительно просмотрены файлы ветки `main` репозитория `Amid-1/Safeai-desk`, реализация отзыва сессий и релевантные миграции V20, V23, V24, V25, V37, V72, а также список и названия тестов модуля. Результаты относятся к изученному состоянию, а не автоматически к любым локальным незакоммиченным правкам. **Сборка приложения и тесты PostgreSQL/Docker в этой среде не запускались.**

## 1. Назначение и границы модуля

Модуль управляет организациями (tenant organizations) и защищённой платформенной организацией (platform organization): созданием, 
переименованием, чтением, поиском, оценкой последствий отключения, отключением и повторным включением. Он не создаёт пользователей, 
не назначает системные роли, не реализует JWT и не хранит физические AI-вызовы: эти обязанности разделены с `user`, `auth`, `chat`, 
`model` и `audit`.

**Основные инварианты:**

1. Действия, изменяющие организацию, доступны только `SUPER_ADMIN`; `ADMIN` может читать только сведения собственной организации.
2. Платформенную организацию нельзя создать/изменить/отключить через обычные административные методы. Её ID берётся из `safeai.platform.organization-id`.
3. `name` непустое, до 255 символов; уникальность проверяется **PostgreSQL** через `normalized_name` и уникальный индекс, а не только Java-проверкой «сначала поиск, потом вставка».
4. Каждое изменение поддерживает `expectedVersion` / `@Version`: устаревший административный запрос не должен затирать новое состояние.
5. Отключение увеличивает `authVersion`, отзывает refresh-сессии в транзакции, публикует событие для последующих действий и формирует аудит.
6. Устаревшие security epochs не должны разрешать **новые** AI-операции после подтверждённого отключения организации. Граница безопасности реализована также триггерами V72.
7. Аудит должен переживать изменения справочника организаций; исторические имена фиксируются снапшотами, а не восстанавливаются по текущему имени.

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

## 2. Вердикт и приоритеты

**Итог:** основная архитектура актуальна для 2026 года и пригодна как база для 2027 года: есть слой команд и запросов, 
явно ограниченные права, защита платформенного tenant, JPA optimistic locking, PostgreSQL pessimistic locking, нормализация 
и уникальность на уровне БД, транзакционный аудит, безопасность на основе `authVersion` и отдельный адаптер отзыва сессий. 
**Однако ни наличие этих механизмов, ни текущие тесты сами по себе не доказывают полноту гарантий под нагрузкой и при сбоях.**

Обозначения: **P0** — блокирующая ошибка сборки/эксплуатации; **P1** — высокий риск безопасности или доступности; 
**P2** — существенная недоработка контракта, целостности или эксплуатации; **P3** — улучшение производительности/поддерживаемости. 
«Подтверждено» означает непосредственно видимое в коде; «условно» означает риск, для которого нужна эксплуатационная проверка.

| ID | Приоритет | Статус | Находка | Где |
|---|---|---|---|---|
| O-00 | P0 **только если буквальный текст вставлен в `.java`** | Фрагмент пользователя повреждён; `main` чистый | В переданном `OrganizationCommandOperations` после `eventPublisher.publishEvent` повторно вложен блок `disable/enable/changeEnabled` и оборван исходный метод: такой файл не скомпилируется. В изученной GitHub-версии этого **нет**. | `OrganizationCommandOperations` |
| O-01 | **P1** | Подтверждено во внешнем модуле `user` | Production startup verifier требует `SUPER_ADMIN`, но **не требует хотя бы одного активного** `SUPER_ADMIN`. Состояние «все SUPER_ADMIN выключены» формально проходит. | `user/service/PlatformOrganizationInvariantVerifier`; `UserRepository` |
| O-02 | **P1 / эксплуатационный инвариант** | Условно | Ошибочный либо различающийся `safeai.platform.organization-id` между узлами меняет то, кого приложение считает защищённой организацией. Необходимо централизованно контролировать конфигурацию и запуск. | `PlatformProperties`, `OrganizationAccessPolicy`, `OrganizationResponseMapper` |
| O-03 | P2 | Подтверждено расхождение алгоритмов | Java нормализует `[\\p{Z}\\s]+`, PostgreSQL V20 — `[[:space:]]+`; Unicode-пробелы, контрольные символы и коллация могут обрабатываться неодинаково. Конечный арбитр — DB, но UX и идемпотентность могут расходиться. | `OrganizationNameNormalizer`, V20 |
| O-04 | P2 | Подтверждено по логике | `activeRefreshSessions` в preview считает «не отозван и не истёк по времени», но не проверяет все security-epoch/token-version условия действительности токена. Это **счётчик строк**, а не строгий показатель реально используемых сессий. | `OrganizationImpactQueryRepository` |
| O-05 | P2 | Подтверждено по контракту | `disable-impact` — предварительный снимок, а не атомарная гарантия на момент реального отключения: между preview и действием состояние меняется. | `OrganizationQueryOperations.getDisableImpact`, `OrganizationCommandOperations.disable` |
| O-06 | P2 | Зависит от полноты перечня операций | `activeChatOperations` считает только `chat_turns.state in ('NEW','PROCESSING')`; термин «все активные AI-работы» для такого счётчика неточен: не включает отдельно возможные уже начатые физические попытки, не завершённые в пределах состояния turn. | `OrganizationImpactQueryRepository`, модель выполнения AI |
| O-07 | P2 | Условно | Подтверждение имени при отключении приводит NFKC и удаляет кавычки, что удобно, но ослабляет **буквальное** совпадение. Вероятность ошибочного совпадения надо оценить в продуктовой модели риска. `expectedVersion` и проверка прав сохраняются. | `OrganizationNameNormalizer.normalizeForConfirmation` |
| O-08 | P3 | Подтверждено | Поиск `lower(name) LIKE '%...%'` не использует обычный B-tree-индекс для подстроки; при больших каталогах потребуется специальный индекс и нагрузочная проверка. | `OrganizationRepository.searchDirectoryByName` |
| O-09 | P3 | Подтверждено | DTO `UpdateOrganizationEnabledRequest` существует, но endpoint общей смены `enabled` намеренно не выставлен. Риск путаницы у сопровождающих, не уязвимость. | DTO, `OrganizationController` |
| O-10 | P3 | Подтверждено | `OrganizationPageResponse` проверяет неотрицательность полей, но не внутренние арифметические отношения `page/size/totalPages`; штатная фабрика `from(Page)` заполняет их согласованно. | DTO |
| O-11 | P3 | Условно | Хранилище `normalized_name` корректно для уникальности, но поисковый API работает с `name`; план выполнения и порядок по локалям нужно проверять отдельно. | V20/V23, `OrganizationRepository` |

**Не являются дефектами:** отсутствие Java preflight-поиска дублей; увеличение `authVersion` только при **отключении**, 
а не повторном включении; `@Transactional(readOnly=true)` на чтении; неизменяемые Java records; `Math.clamp` при Java 21; 
публикация события внутри транзакции при наличии `@TransactionalEventListener(AFTER_COMMIT)` у потребителя; отсутствие а
втоматического удаления уже начатых внешних запросов AI. Последнее — физическое ограничение, а не повод обещать невозможную 
мгновенную отмену.

## 3. Реальные бизнес-процессы: пошагово

### 3.1. Создание организации

1. HTTP `POST /api/organizations`; Spring Security пропускает `SUPER_ADMIN`.
2. `OrganizationService.create()` открывает **write transaction** и вызывает `commands.create()`.
3. `OrganizationAccessPolicy.requireSuperAdmin()` повторно проверяет роль на уровне приложения.
4. `OrganizationNameNormalizer.canonicalize()` отбрасывает пустое/слишком длинное имя и приводит пробелы.
5. Создаётся `OrganizationEntity`, `enabled=true`, `authVersion=0`, новый `id` устанавливается в `@PrePersist`.
6. `saveAndFlush()` выполняет запись. DB trigger V20 вычисляет canonical `name` и `normalized_name`; unique index V23 отклоняет совпадающее имя. `entityManager.refresh()` возвращает DB-generated timestamp/version/name.
7. Именованные нарушения уникальности переводятся в `ConflictException`; неизвестная ошибка БД **пробрасывается**.
8. `AuditEventService.recordPrincipalSnapshot()` формирует audit snapshot; при исключении транзакция откатывается. Возвращается версия и тип организации.

**Сильная сторона:** нет TOCTOU-ошибки уникальности «проверили `exists`, потом конкурент создал такую же строку». Требуется интеграционный тест на конкурирующие вставки — соответствующий тест в репозитории найден.

### 3.2. Переименование

`PATCH /api/organizations/{id}` → SUPER_ADMIN → транзакция → запрет platform-ID → `findByIdForSecurityUpdate(id)` с 
`PESSIMISTIC_WRITE` → сравнение `expectedVersion` → canonicalize(name) → no-op без лишнего аудита при точном совпадении → 
сохранение/refresh → audit. При одновременных изменениях ожидается ровно один успешный переход по прежней версии; устаревший 
запрос получает HTTP 409.

**Нюанс:** API использует тот же `expectedVersion`, который при отключении/включении возрастает: это желаемое предотвращение 
перезаписи состояния. Имя и статус не должны изменяться независимыми устаревшими командами.

### 3.3. Отключение

`POST /api/organizations/{id}/disable` → только SUPER_ADMIN → запрет platform-ID → DB row lock → `expectedVersion` → сравнение 
нормализованных `confirmationName` и текущего имени → если уже выключена, возврат без side effects; иначе `enabled=false`, 
`authVersion = Math.addExact(oldAuthVersion, 1)` → `saveAndFlush` + `refresh` → `SessionRevocationPort.revokeAllForOrganization()` →
событие об epoch change → аудит → commit.

Реальный `UserSessionRevocationService` implements `SessionRevocationPort` и использует **`@Transactional(propagation = MANDATORY)`**. Значит, штатный вызов из `OrganizationService.disable()` работает в **той же** транзакции, а не в отдельной `REQUIRES_NEW`; ошибка при отзыве откатывает основное изменение. Это существенно усиливает гарантии по сравнению с абстрактным интерфейсом.

**V72:** перед новой записью `chat_turns` и новой физической `model_execution_attempts` PostgreSQL сверяет `enabled` и `authorized_organization_auth_version` с `organizations.auth_version`, удерживая `FOR SHARE` до завершения транзакции. Отключение берёт `FOR UPDATE` на организацию, поэтому новый переход через эту DB-границу после commit отключения не должен пройти. Уже отправленный провайдеру HTTP-запрос не «отзывается» SQL-триггером; его стоимость и результат должны обрабатываться по правилам AI-модуля.

### 3.4. Повторное включение

`POST .../enable` → SUPER_ADMIN → row lock → `expectedVersion` → `enabled=true`. **`authVersion` не уменьшается и не сбрасывается**.
Старые токены, привязанные к прежней эпохе, не оживают. Новая сессия после успешной повторной аутентификации получает текущую эпоху.
Код не пытается «разотзывать» refresh-сессии — правильно.

### 3.5. Чтение и поиск

- `SUPER_ADMIN`: чтение всех организаций, полнотекстового имени **нет**, поиск по подстроке имени или точному UUID, ограничение 
- до 50 записей в directory.
- `ADMIN`: список и `/me` только собственной организации, `/{id}` чужой организации выдаёт 404 без раскрытия её существования.
- Операции чтения не должны менять БД. `Pageable` нормализуется через общий whitelist сортировок и стабильный tie-breaker по `id`.
- `/disable-impact` не выполняет отключение и не обеспечивает блокировки до будущего вызова. Это консультационные показатели, а не 
- предусловие безопасности.

## 4. Пофайловый аудит — все классы, методы и значимые переменные

Ниже **OK** — корректно в просмотренном контексте; **NOTE** — допущение/ограничение; **FIX/TEST** — рекомендуется исправить или 
доказать тестом. Используются исходные имена методов и полей, чтобы README можно было применять при code review.

### 4.1. `audit/OrganizationAuditSnapshotProvider.java`

**Назначение:** внутренний SPI `AuditTargetOrganizationSnapshotProvider`, получение имени целевой организации для аудита.

- `organizationRepository` (`final`): JPA-зависимость, передаётся Lombok-конструктором `@RequiredArgsConstructor`; **OK**.
- `findName(UUID organizationId)`: `requireNonNull`; `findById`; `map(OrganizationEntity::getName)`, `trim`, отбор пустой строки, 
- `Optional<String>`. **OK**: отсутствующая организация явно представлена `Optional.empty`, ошибки БД не скрываются; чтение имени 
- делается из canonical DB-row.
- **NOTE:** `trim()` покрывает лишь базовые пробельные символы, но canonical DB-name непустое и нормализованное; унифицировать 
- обработку Unicode можно без изменения публичного контракта.
- **NOTE:** при удалённой организации имя уже нельзя получить живым запросом — V37 предусматривает исторические audit snapshots; 
- не нужно пытаться «всегда» восстанавливать имя через текущую строку.
- **TEST:** существующая организация; пустое имя не возвращается; отсутствует строка; repository error пробрасывается; 
- audit-событие с изменением имени сохраняет прежний snapshot.

### 4.2. `controller/OrganizationController.java`

**Назначение:** HTTP boundary REST `/api/organizations`. Поля: `organizationService` (**OK**, dependency injection); аннотации `@Validated`, `@RestController`, `@RequestMapping`, `@RequiredArgsConstructor` (**OK**).

| Метод / URL | Роль | Сигнатура и поведение | Аудит |
|---|---|---|---|
| `create` / `POST /` | SUPER_ADMIN | `@Valid CreateOrganizationRequest`, `@AuthenticationPrincipal`, HTTP 201 | **OK**: двойная авторизация через сервис |
| `findAll` / `GET /` | ADMIN/SUPER_ADMIN | `Pageable` (`size=20`, `createdAt DESC`) | **OK**: сервис ограничивает область и сортировки; не равнозначно произвольному JPA `findAll` |
| `directory` / `GET /directory` | SUPER_ADMIN | `query @Size(max=255)`, `limit @Min(1) @Max(50)` | **OK**: HTTP-validation и независимая проверка сервиса; test UUID/string |
| `findCurrentOrganization` / `GET /me` | ADMIN/SUPER_ADMIN | Не принимает произвольный organization ID | **OK** |
| `findById` / `GET /{id}` | ADMIN/SUPER_ADMIN | UUID path variable | **OK**: чужой tenant скрыт от ADMIN |
| `disableImpact` / `GET /{id}/disable-impact` | SUPER_ADMIN | Результат лишь информационный | **NOTE**: UI не должен считать preview достаточной гарантией отсутствия задач |
| `updateName` / `PATCH /{id}` | SUPER_ADMIN | `@Valid UpdateOrganizationRequest` и версия | **OK** |
| `disable` / `POST /{id}/disable` | SUPER_ADMIN | Версия + текст подтверждения | **OK**: осознанный destructive API |
| `enable` / `POST /{id}/enable` | SUPER_ADMIN | Версия | **OK**: отдельно от disable |

**Входные переменные:** `request` (Bean Validation), `currentUser` (аутентифицированный субъект), `id` (UUID), `pageable`, `query`, `limit`. Права из principal проверяются дважды: декларативно `@PreAuthorize` и императивно в `OrganizationAccessPolicy`, что полезно при прямых вызовах service из jobs/tests.

**Пограничные моменты:** авторизация через `@PreAuthorize` предполагает включённый method security в приложении; это надо проверять MVC/security integration tests. `@AuthenticationPrincipal(errorOnInvalidType = true)` предотвращает скрытую подмену типа principal, но **сам по себе** не подтверждает живость/epoch — это задача authentication/security chain.

### 4.3. `dto/CreateOrganizationRequest.java`

- `name`: `@NotBlank`, `@Size(max=255)` — **OK** для HTTP; `OrganizationNameNormalizer` повторяет проверку на application boundary.
- Java record, отсутствие mutable setters — **OK**.
- **TEST:** `null`, пусто, пробелы, 256 символов, Unicode whitespace.

### 4.4. `dto/DisableOrganizationRequest.java`

- `expectedVersion`: `Long` + `@NotNull @PositiveOrZero` — **OK**; именно boxed type позволяет отличить отсутствующий параметр 
- от нуля.
- `confirmationName`: `@NotBlank @Size(max=255)` — **OK**; для destructive-действия сервис сравнивает нормализованное имя, 
- а не просто факт наличия текста.
- **NOTE O-07:** NFKC/удаление кавычек допускают текстовые варианты; это документированная политика удобства, не точное 
- байтовое подтверждение.
- **TEST:** неверное имя/версия, неизменённая строка, отказ для platform organization.

### 4.5. `dto/EnableOrganizationRequest.java`

- `expectedVersion`: `@NotNull @PositiveOrZero` — **OK**; повторное включение не имеет подтверждения имени, зато требует роль и актуальную версию. Если в продукте нужен ещё один барьер, это отдельное UX/security-решение, а не ошибка аннотации.

### 4.6. `dto/OrganizationDirectoryResponse.java`

Поля: `id` (`UUID`), `name` (`String`), `enabled` (`boolean`), `type` (`OrganizationType`), `protectedOrganization` (`boolean`, сериализуется как JSON **`protected`**), `version` (`long`).

- Compact constructor требует `id`, `type`, `name`, `version >= 0`: **OK**.
- `PLATFORM => protectedOrganization=true`, `TENANT => false`: **OK** внутри текущей модели.
- **NOTE:** тип берётся из настроенного platform UUID, а не отдельной колонки БД. Несогласованная конфигурация разных экземпляров может изменить видимое значение `type/protected` для одного и того же ID.
- **TEST:** JSON-имя `protected`, оба типа, запрещённые сочетания, version = 0 / -1.

### 4.7. `dto/OrganizationDisableImpactResponse.java`

Поля: `organizationId`, `organizationVersion`, `enabledUsers`, `administrators`, `activeRefreshSessions`, `activeChatOperations`.

- Compact constructor требует не-null ID и неотрицательность всех пяти числовых показателей; `requireNonNegative(long, String)` валидирует аргументы — **OK**.
- **NOTE O-04/O-06:** название счётчика `activeRefreshSessions` означает в SQL только незавершённость по `revoked_at`/`expires_at`; `activeChatOperations` — только NEW/PROCESSING turns, а не гарантированно все provider calls.
- **NOTE:** итоговая строка не должна восприниматься как lock/reservation; версия актуальна на момент чтения и может устареть.
- **TEST:** отрицательное значение каждого поля, null ID, нулевые счётчики.

### 4.8. `dto/OrganizationPageResponse.java`

Поля: `content` (неизменяемая копия списка), `page`, `size`, `totalElements`, `totalPages`.

- Compact constructor: `List.copyOf`, проверка page >= 0, size >= 1, totalElements/totalPages >= 0 — **OK**.
- `from(Page<OrganizationResponse>)`: берёт показатели из Spring Data Page — **OK**.
- **NOTE O-10:** нет жёсткой сверки (`totalPages` соответствует `totalElements/size`), но принудительная сверка может испортить совместимость с Spring Data для нестандартных Page/динамических изменений; если вводить — с тестами.
- **TEST:** пустая страница, последняя, большой total, попытка мутации `content`.

### 4.9. `dto/OrganizationResponse.java`

Поля: `id`, `name`, `enabled`, `type`, JSON `protected`, `version`, `createdAt`, `updatedAt`.

- Compact constructor валидирует ID, тип, обе временные метки, имя, версию, комбинацию типа и защиты — **OK**.
- **NOTE:** не проверяет `updatedAt >= createdAt` в Java; DB V4 защищает storage-инвариант. Не нужно дублировать всё без бизнес-обоснования.
- **NOTE:** незаполненный `normalizedName` не экспортируется — правильно, это внутренняя DB-деталь.
- **TEST:** сериализация, неизменность immutable record, ошибка противоречивого type/protected.

### 4.10. `dto/OrganizationType.java`

Enum `PLATFORM`, `TENANT`: **OK** — простое явное отражение служебного/клиентского tenant. Нет `UNKNOWN`; отсутствие типа должно приводить к ошибке, а не к молчаливому `TENANT`. **TEST:** mapper правильно выводит тип для платформенного ID.

### 4.11. `dto/UpdateOrganizationEnabledRequest.java`

Поля `enabled: Boolean`, `expectedVersion: Long`, оба `@NotNull`; версия неотрицательна. **По отдельности тип корректен**, 
но в представленном контроллере **нет** endpoint, который использует это DTO: вместо него существуют раздельные `DisableOrganizationRequest` и `EnableOrganizationRequest`.

**Рекомендация:** пометить как legacy/неиспользуемое только после поиска внешних тестов/клиентов; не возвращать общий 
`PATCH enabled`, потому что он обойдёт подтверждение имени при отключении. Наличие DTO само по себе endpoint не открывает.

### 4.12. `dto/UpdateOrganizationRequest.java`

Поля `name` (`@NotBlank`, `@Size(max=255)`), `expectedVersion` (`@NotNull`, `@PositiveOrZero`) — **OK**. Контракт отличается 
от создания организации наличием optimistic version. **TEST:** нельзя переименовать stale version, проверка 255/256 и whitespace.

### 4.13. `entity/OrganizationEntity.java`

**Соответствие БД `organizations`:**

| Поле | Хранение/аннотация | Оценка |
|---|---|---|
| `id: UUID` | `@Id`; UUID создаётся в `prePersist` | **OK**, нет скрытой зависимости от PostgreSQL sequence |
| `name: String` | `varchar(255)`, not null | **OK**, БД V20 дополнительно canonicalizes |
| `normalizedName: String` | `normalized_name`, `insertable=false`, `updatable=false` | **OK**, генерируется БД и индексируется; не допускать ручного сеттера в бизнес-логике |
| `enabled: boolean` | not null, default Java `true` | **OK**, явный флаг доступа организации |
| `authVersion: long` | not null, V25 и CHECK >=0 | **OK**, security epoch; увеличивать только при значимых security changes |
| `version: long` | `@Version`, not null | **OK**, optimistic lock, отражает административные изменения |
| `createdAt: Instant` | DB-generated, insertable/updatable=false | **OK** при исправном trigger/default и `refresh()` |
| `updatedAt: Instant` | DB-generated, insertable/updatable=false | **OK**, требует DB integration checks |

- `@Entity`, `@Table`, `@Getter`, `@Setter`, `@DynamicUpdate`: допустимое решение. **NOTE:** общий `@Setter` разрешает менять `id/authVersion` из произвольного Java-кода: контроль целостности обеспечен сервисом/DB, но более узкие setter-границы уменьшили бы площадь ошибочного изменения. Не менять API без поиска вызовов.
- `prePersist()`: генерирует UUID, затем `validateInvariant()`; **OK**.
- `preUpdate()`: проверяет те же инварианты; **OK**.
- `validateInvariant()`: `name != null && !isBlank`, `length<=255`, `authVersion>=0`, `version>=0` — **OK**, но это не единственный enforcement; V4/V20/V25 обеспечивают ряд DB CHECK/trigger invariants.
- **TEST:** изменение enabled под optimistic version, timestamps после DB refresh, update без имени, invalid epoch, trigger-induced canonicalization.

### 4.14. `event/OrganizationSecurityStateChangedEvent.java`

Поля: `organizationId: UUID`, `authVersion: long`. Compact constructor: nonnull, nonnegative — **OK**. Событие передаёт достаточную информацию для последующей инвалидации security-кэша и связывает notification с эпохой.

**NOTE:** событие публикуется **до** commit Java-транзакции; подписчики должны слушать через `@TransactionalEventListener(AFTER_COMMIT)`, если их побочные эффекты должны следовать только за commit. Изученный `OrganizationStatusCacheInvalidationListener` следует именно этому паттерну. Неизвестные новые подписчики должны проходить проверку того же правила.

### 4.15. `exception/OrganizationExceptionHandler.java`

Поля: `responses: ApiExceptionResponseSupport` (создаётся через `ApiErrorResponseFactory`); `@Slf4j`, `@RestControllerAdvice`, `@Order(HIGHEST_PRECEDENCE + 11)`.

- Конструктор: централизует фабрику response, **OK**.
- `handleOrganizationVersionConflict(exception, request)`: логирует request ID, organization ID, expected/actual, URI; возвращает унифицированный `ApiErrorResponse`, статус/код/сообщение из исключения — **OK**.
- **NOTE:** порядок advice необходимо проверять при добавлении глобального generic `@ExceptionHandler(Exception.class)`. Текущий специфичный handler не должен быть вытеснен.
- **TEST:** HTTP 409, специализированный `ORGANIZATION_VERSION_CONFLICT`, корректный requestId, отсутствие stacktrace в публичном ответе.

### 4.16. `exception/OrganizationVersionConflictException.java`

Поля `organizationId`, `expectedVersion`, `actualVersion` immutable (`final`), Lombok `@Getter`. Конструктор вызывает базовый `ApiException(HttpStatus.CONFLICT, ApiErrorCode.ORGANIZATION_VERSION_CONFLICT, publicMessage, null)`, проверяет ID и неотрицательные версии.

**OK:** typed error и version data для логирования и диагностики. **NOTE:** класс наследует общий `ApiException`, а не специализированный `ConflictException`, но передаёт явные HTTP 409 и код — это корректное осознанное решение, а не ошибка.

### 4.17. `repository/OrganizationRepository.java`

Интерфейс `JpaRepository<OrganizationEntity, UUID>` — **OK**.

- `findByIdForSecurityUpdate(UUID id)`: `@Lock(PESSIMISTIC_WRITE)` над JPQL `where id=:id`, возвращает `Optional`. **OK**: транзакционная сериализация на одной организации. Lock обязательно держать до commit `OrganizationService`.
- `findAllStable(Pageable pageable)`: Page по JPQL `select organization` — **OK**, порядок/лимит накладывает Spring Data; tie-breaker `id` добавляется перед вызовом в query service.
- `searchDirectoryByName(String query, Pageable pageable)`: case-insensitive `LIKE` с явным `escape '!'` и параметрами — **OK** против SQL injection и wildcard-подстановок; параметр экранирует service. **NOTE:** `%` в начале шаблона = вероятный sequential scan для больших каталогов; нельзя утверждать обратное без `EXPLAIN ANALYZE`.
- **TEST:** реальный PostgreSQL, `'%','_','!'`, case-insensitive, имена с экранируемыми символами, блокировка двух конкурентных update.

### 4.18. `repository/OrganizationImpactQueryRepository.java`

Поля `jdbcTemplate: NamedParameterJdbcTemplate` (`final`, Lombok constructor) — **OK**. Использование SQL здесь оправдано: несколько агрегаций эффективнее читать одной DB-командой, без загрузки сущностей.

- `load(UUID organizationId)`: nonnull ID, `MapSqlParameterSource`, 4 независимых вложенных агрегата по одному `organizationId`, `queryForObject()`, nonnull результат — **OK** для предварительного snapshot.
- `enabled_users`: число `users` с `enabled=true` — ясный показатель.
- `administrators`: `count(distinct user.id)` активных `ADMIN` — distinct защищает от умножения строк при join; `SUPER_ADMIN` намеренно не считаются здесь как tenant ADMIN.
- `active_refresh_sessions`: `revoked_at IS NULL AND expires_at > current_timestamp` плюс связь с организацией; **не включает** `issued_token_version` / `issued_organization_auth_version`, состояние включённости и прочие проверки аутентификации — **O-04**.
- `active_chat_operations`: NEW+PROCESSING turns; это принятые/активные turns, не инвентаризация всей физической занятости AI — **O-06**.
- `ImpactSnapshot(...)`: 4 `long` (`enabledUsers`, `administrators`, `activeRefreshSessions`, `activeChatOperations`); проверка каждого `>=0` через `requireNonNegative` — **OK**.
- **TEST:** пустая организация; два ADMIN с разными ролями; session expired/revoked/stale-epoch; turn NEW/PROCESSING/AMBIGUOUS; race между preview и disable.

**Семантическое решение:** либо переименовать UI-показатель в «неотозванные, неистёкшие refresh-записи», либо сделать count реально пригодных refresh-токенов, учитывая auth/token epoch, если бизнес-цель — показать активные входы. Не изменять SQL вслепую: статус токена определяется всей auth-политикой.

### 4.19. `service/OrganizationAccessPolicy.java`

Поле `platformProperties` (`final`, `Objects.requireNonNull`) хранит платформенный ID. Private helper `hasAuthority()` сравнивает `GrantedAuthority#getAuthority` с `SystemRole.authority()`; не использует substring/contains — **OK**.

- `requireOrganizationReader(currentUser)`: nonnull через `requirePrincipal`, допускает `ADMIN` или `SUPER_ADMIN` — **OK**.
- `requireSuperAdmin(currentUser)`: требует строго `SUPER_ADMIN` — **OK**.
- `requireMutableOrganizationRequest(organizationId,currentUser)`: nonnull ID → super-admin → запрет платформы — **OK**, порядок отказывает прежде обращения к БД.
- `isSuperAdmin(currentUser)`: делегирует `hasAuthority`, **OK**, предполагает nonnull principal, обеспечиваемый точками входа.
- `isPlatformOrganization(organizationId)`: сравнение с `platformProperties.organizationId()`, **OK** при однородной конфигурации.
- `organizationNotFound(id)`: `ResourceNotFoundException`, **OK** для no-leak / masking.
- `isAdmin(currentUser)`: private helper, **OK**.
- `hasAuthority(currentUser, role)`: точное сравнение authorities, **OK**.
- `rejectPlatformOrganizationMutation(organizationId)`: `ForbiddenOperationException`, **OK**.
- `requirePrincipal(currentUser)`: `Objects.requireNonNull`, **OK**.

**NOTE:** запрет platform-ID должен срабатывать в любом write endpoint. В текущем command flow это соблюдено. Для критических platform-данных желательно отдельное контрольное наблюдение консистентности ID по узлам и startup-verifier.

### 4.20. `service/OrganizationCommandOperations.java`

**Основное назначение:** реализация команд и точка обеспечения write-инвариантов; класс package-private, не Spring bean, поэтому `@Transactional` законно находится на его внешней фасадной границе `OrganizationService`.

**Поля/константы:**

- `ORGANIZATION_NAME_UNIQUE_CONSTRAINTS`: множество имён ожидаемых PostgreSQL unique constraints/indexes; правильно не превращать любое `DataIntegrityViolationException` в «имя занято».
- `organizationRepository`: JPA CRUD/row locking; `entityManager`: refresh DB-generated данных.
- `auditEventService`: транзакционный audit snapshot; `eventPublisher`: security change signal.
- `userSessionRevocationService: SessionRevocationPort`: identity-boundary без зависимости `organization` от реализации auth.
- `accessPolicy`: централизованные права и platform guard; `responseMapper`: формирует DTO.
- Все поля `final`, зависимости конструктора инъектируются напрямую. **NOTE:** нет `Objects.requireNonNull` на каждом параметре конструктора; Spring fail-fast при wiring, но ручные тесты с null дадут поздний NPE. Не критично.

**Методы и локальные переменные:**

| Метод | Переменные / смысл | Вывод |
|---|---|---|
| constructor | 7 dependency parameters | **OK**, соответствует сервисному фасаду |
| `create(request,currentUser)` | `canonicalName`, `entity`, `saved` | **OK**: role check, нормализация, DB unique, audit |
| `updateName(id,request,currentUser)` | `entity`, `canonicalName`, `oldName`, `saved` | **OK**: pessimistic lock + optimistic expected version + no-op |
| `disable(id,request,currentUser)` | `entity`, `expectedConfirmation`, `actualConfirmation` | **OK**, текст подтверждения и версия до изменения |
| `enable(id,request,currentUser)` | `entity` | **OK**, separate privileged action |
| `changeEnabled(entity,newEnabled,currentUser)` | `oldEnabled`, `oldAuthVersion`, `saved`, `sessionsRevoked`, `auditSessionsRevoked` | **OK**: true→false increments auth epoch, same transaction revokes refresh sessions, snapshots audit; no-op не мутирует |
| `findMutableOrganizationForUpdate(id,currentUser)` | проверяет policy и получает row lock | **OK** |
| `saveAndRefresh(organization)` | `saved` | **OK**: `saveAndFlush` + `EntityManager.refresh` для DB canonicalization/timestamps/version |
| `requireExpectedVersion(organization,expectedVersion)` | `actualVersion` берётся через `getVersion` | **OK**: explicit validation, HTTP 409 conflict; сравнение после lock |
| `isOrganizationNameUniqueViolation(exception)` | классификация DB constraint | **OK**, проверить актуальность списка имён при новых миграциях |
| `duplicateOrganizationName(name)` | factory для `ConflictException` | **OK**; сообщение содержит имя, доступно privileged admin |

**Транзакционный порядок:** запрет платформы → row lock → version check → mutation → flush/refresh → session revocation → publish event → audit → commit. Он важен для безопасности; не переносить revocation на `@Async`/`REQUIRES_NEW` и не выносить его за transaction без независимого доказательства эквивалентности.

**Главное замечание к присланному фрагменту:** внутри текста `changeEnabled` после вызова `eventPublisher.publishEvent(...)` вставлен повтор `disable()`, `enable()`, `changeEnabled()` и не закрыты методы. Это явная ошибка **пасты**, потому что отдельно открытый оригинальный `OrganizationCommandOperations.java` в GitHub содержит корректное продолжение `final boolean auditSessionsRevoked`, аудит, return и closing braces. В README **не утверждается**, что production main сейчас не компилируется. Если локальный файл буквально совпадает с испорченным фрагментом, восстановить класс целиком из корректной версии до запуска сборки.

### 4.21. `service/OrganizationNameNormalizer.java`

**Константы/поля:** `MAX_NAME_LENGTH = 255`; `COLLAPSIBLE_WHITESPACE = Pattern.compile("[\\\\p{Z}\\\\s]+")` (документная запись Java pattern); `CONFIRMATION_QUOTES` — список ASCII и типографских кавычек. Обе `Pattern` immutable, compile-once — **OK**.

- Private constructor: utility class не инстанцируется — **OK**.
- `canonicalize(@Nullable String value)`: reject null/blank, `strip()`, сворачивание пробелов, проверка `isBlank`, `rejectResidualControlCharacters`, длины — **OK** с оговоркой O-03.
- `normalizeForConfirmation(@Nullable String value)`: повторная canonicalization, `Normalizer.Form.NFKC`, удаление кавычек, сворачивание whitespace, `toLowerCase(Locale.ROOT)`, отклонение пустого — **OK** в рамках объявленной более мягкой политики подтверждения; **O-07** при требовании побуквенного совпадения.
- `rejectResidualControlCharacters(String value)`: корректный проход Unicode code points (`codePointAt` и `charCount`), ISO controls отвергаются — **OK**. Но символы `\t`, `\r`, `\n`, попавшие под `\\s`, **заменяются пробелами до этой проверки**: если политика означает «запретить любую управляющую последовательность в исходном имени», реализовано не полностью. Не назвать дефектом без уточнения политики.
- **O-03:** Java и PostgreSQL V20 используют неидентичные алгоритмы. Конкретно PG V20: `regexp_replace(btrim(value), '[[:space:]]+', ' ', 'g')`, затем `lower(...)`; Java: `strip()`, `[\\p{Z}\\s]+`, а для confirmation ещё NFKC/quote removal. Сверку проводить реальными интеграционными примерами, в том числе `NBSP`, `EM SPACE`, `NARROW NO-BREAK SPACE`, tabs/newlines, Turkish I, composed/decomposed Unicode. Разница нормализации может привести к неожиданной DB canonicalization/unique conflict или отличающемуся ответу.
- **Не рекомендовано:** просто скопировать SQL-нормализатор на Java regex без проверки коллации/locale; PostgreSQL остаётся source of truth.

### 4.22. `service/OrganizationQueryOperations.java`

**Поля:** `MAX_PAGE_SIZE=100`, `MAX_DIRECTORY_LIMIT=50`, `DEFAULT_PAGE_SORT=createdAt DESC`, `ALLOWED_SORT_PROPERTIES={createdAt,name,enabled,updatedAt}`, `organizationRepository`, `impactQueryRepository`, `accessPolicy`, `responseMapper`. Immutable зависимости — **OK**.

- constructor: сохраняет четыре зависимости — **OK**.
- `findAll(currentUser,pageable)`: проверяет роль и nonnull; через `normalizePageable` ограничивает сортировку, page size и добавляет `id`-tie-breaker; SUPER_ADMIN получает global Page. ADMIN при `offset>0` получает пустую Page с total=1, при `offset=0` читает только собственную организацию — **OK**, минимизация утечки сведений.
- `findDirectory(query,limit,currentUser)`: SUPER_ADMIN; `query` nullable → empty string; `trim`, максимум 255, `Math.clamp(limit,1,50)`, сортировка `name,id`; распознавание UUID через `parseUuidOrNull`; иначе escaped LIKE по имени. **OK**, но clamp маскирует неправильный прямой application-вызов (HTTP уже отвергает недопустимый предел). Для строгой унификации можно на service boundary возвращать 400 и не clamp, только с согласованием существующих тестов/контракта.
- `findCurrentOrganization(currentUser)`: берёт ID только из principal, не из HTTP — **OK**.
- `findById(id,currentUser)`: ADMIN не может читать чужую организацию даже если знает UUID; `404` вместо `403` — сознательная no-leak политика; **OK**.
- `getDisableImpact(id,currentUser)`: права SUPER_ADMIN и запрет платформы, загружает entity, затем агрегат; **O-05**: эти два SELECT и последующее отключение не составляют одной атомарной операции, если UI делает их отдельными запросами. Для truly hard precondition нужна повторная проверка в самой write transaction.
- `normalizePageable(pageable)`: whitelist сортировок, limit100, id tie-breaker `FOLLOW_LAST_SORT_DIRECTION` — **OK**; не тождественно keyset-pagination, при разных HTTP-вызовах записи могут сдвинуться.
- `escapeDirectoryLikeLiteral(value)`: последовательно `!→!!`, `%→!%`, `_→!_`; **OK** при JPQL `escape '!'`. Порядок замен важен: exclamation должен обрабатываться первым.
- `parseUuidOrNull(value)`: UUID parse, false→null и переход к поиску имени — **OK**; при поддержке «строго 36 символов» нужны отдельные UX-требования.

### 4.23. `service/OrganizationResponseMapper.java`

Поле `accessPolicy` — для вычисления типа/защиты на основе platform UUID.

- `toResponse(OrganizationEntity organization)`: `protectedOrganization = isPlatformOrganization(id)`, конструирует `OrganizationResponse`, берёт id/name/enabled/version/timestamps — **OK**.
- `toDirectoryResponse(...)`: аналогичная логика, возвращает короткое DTO — **OK**.
- **NOTE:** единая функция `isPlatformOrganization` не допускает рассинхронизации типа между DTO **на одном узле**; межузловая консистентность зависит от `PlatformProperties`.
- **TEST:** platform/tenant, enabled/disabled, DB-generated timestamps.

### 4.24. `service/OrganizationService.java`

**Роль:** публичный Spring service / транзакционный фасад. Поля `commands`, `queries` — final. `@Service`, `@EnableConfigurationProperties(PlatformProperties.class)` — **OK** при отсутствии конфликтующего дублирующего биндинга; прочие настройки должны быть проверены в общей конфигурации.

**Конструктор:** принимает `OrganizationRepository`, `OrganizationImpactQueryRepository`, `AuditEventService`, `ApplicationEventPublisher`, `PlatformProperties`, `SessionRevocationPort`, `EntityManager`; создаёт `OrganizationAccessPolicy`, `OrganizationResponseMapper`, `OrganizationCommandOperations`, `OrganizationQueryOperations`. **OK**, сохраняет внешний DI-контракт, package-private helper классы не требуют отдельных beans.

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

**NOTE:** `readOnly=true` — transaction hint/режим, а не авторизация и не автоматически `REPEATABLE READ`; это не заменяет бизнес-проверки. `@Transactional` корректно применяется к публичным методам Spring proxy. Прямое создание объекта через `new OrganizationService(...)` в unit tests **не** создаёт transaction proxy; интеграционные проверки должны вызывать Spring bean.

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

Матрица зависит от штатного Spring Security authentication filter и проверки актуальности principal. Непосредственно в `OrganizationAccessPolicy` нет повторного обращения к БД за состоянием пользователя: это отдельный security boundary архитектуры. Для полной гарантии нужно также проверить `UserStatusCacheService` / JWT auth gates.

### 5.2. Pessimistic lock, optimistic version и сериализация

В mutation потоке используется двойной барьер: `findByIdForSecurityUpdate` удерживает строку организации с `PESSIMISTIC_WRITE`, а `expectedVersion` защищает интерфейс администратора от выполнения команды по старому снимку. Это не излишнее дублирование: первое сериализует конкурентные изменения, второе выявляет stale intent. DB `@Version` добавляет protection при других write-путях Hibernate.

Lock order важен: disable принимает `organizations FOR UPDATE`; login/refresh используют `organizations FOR SHARE`, затем user/token locks. Нельзя поменять порядок без анализа deadlock graph. По просмотренной V72 физический AI gate также берёт `organizations FOR SHARE`. Две самостоятельные транзакции не должны держать разный порядок по организациям/пользователям.

### 5.3. Безопасность после отключения

- `enabled=false` влияет на актуальные auth checks.
- `authVersion++` инвалидирует токены и AI reservation/attempt epoch даже после `enabled=true` при повторном включении.
- `UserSessionRevocationService` со свойством `MANDATORY` участвует в write transaction и требует reason из whitelist.
- Событие `OrganizationSecurityStateChangedEvent` из `OrganizationCommandOperations` используется для необязательных cache-invalidation действий; не должно быть единственным security fence.
- DB V72 защищает начало **новых** turn/physical attempt; уже начавшийся вызов не обещает отменять.

**Приёмочные security-тесты:** disable vs login; disable vs refresh; disable vs создание turn; disable vs новую physical attempt; enable после disable не возрождает старые access/refresh токены; банк серверов с одинаковым platform UUID; audit failure делает rollback бизнес-действия при требовании строгого audit.

### 5.4. Особый дефект вне модуля: живой SUPER_ADMIN

`user/service/PlatformOrganizationInvariantVerifier` проверяет включённость платформенной организации, `authVersion`, `version`, общее число пользователей и наличие хотя бы одного пользователя с ролью `SUPER_ADMIN`; дополнительно запрещает пользователей иных ролей. Но он **не проверяет `enabled=true` хотя бы у одного `SUPER_ADMIN`**.

Пример: в платформенной организации два `SUPER_ADMIN`, оба с `users.enabled=false`; `users=2`, `superAdmins=2` — прежний инвариант удовлетворён, но платформой некому управлять.

**Без ослабления:** добавить в `UserRepository` отдельный подсчёт включённых `SUPER_ADMIN` по `organizationId`, в production verifier — `enabledSuperAdmins >= 1`, в тесты — `rejectsPlatformWhenAllSuperAdminsDisabled`, а также positive test на смешанный набор enabled/disabled. Ни один существующий инвариант не удалять. Это изменение вне списка 24 типов данного фрагмента, но затрагивает операционную безопасность `organization` и потому вынесено сюда явно.

## 6. База данных, миграции и границы доказательств

| Миграция | Что проверено | Комментарий |
|---|---|---|
| V1 / V4 | `organizations` и первоначальные unique/check, timestamps, версия | Структура не существует только в Java |
| V20 | `canonical_organization_name`, `normalize_organization_name`, `normalized_name`, триггер `sync_organization_name`, CHECK | БД — источник истины уникальности и canonicalization |
| V23 | `ux_organizations_normalized_name` | Уникальный индекс, в сценариях одновременного создания — основной барьер |
| V24 | Ролевая целостность `user_roles`, аудит/outbox, security hardening | Важно для смежных user/org операций |
| V25 | `organizations.auth_version`, snapshot epoch у refresh tokens | Доказуемая историческая epoch-семантика |
| V37 | `target_organization_name` в audit/outbox | Сохраняет имя организации на время события |
| V72 | DB guards на новую chat turn и physical attempt с row locking | Не является системой отмены уже выполненного provider call |

**Нельзя** исправлять инварианты путём редактирования уже применённой V20/V23/V25/V72. Изменение PostgreSQL-правила только новой Flyway migration после последней установленной версии, с проверкой данных и rollback/forward strategy.

**Ограничения:** наличие текста миграции не доказывает, что она установлена на конкретной базе; наличие `@Version` не доказывает, что все сторонние SQL writers согласованы; наличие unit tests не доказывает порядок блокировок. Подтверждение для production — `flyway info/validate`, инспекция constraints/indexes/triggers на целевой БД, настоящие concurrent PostgreSQL tests.

## 7. Производительность, масштабирование, сопровождение

1. **Directory:** индекс B-tree по `normalized_name` защищает уникальность, но `%fragment%` на `lower(name)` не ускоряет автоматически. Измерить `EXPLAIN (ANALYZE, BUFFERS)` и cardinality. При большом каталоге рассмотреть `pg_trgm`/GIN и новую migration с учётом write overhead, а не добавлять индекс наугад.
2. **Page pagination:** deterministic sort плюс `id` упорядочивает одинаковые `createdAt`; offset pagination не гарантирует snapshot между запросами разных страниц. Для сотен тысяч организаций рассмотреть keyset, только при реальной потребности.
3. **Preview SQL:** все четыре подсчёта используют один SQL statement и один snapshot PostgreSQL, это хорошо. Отдельная загрузка версии организации до него не создаёт атомарный контракт со следующим disable.
4. **`@DynamicUpdate`:** может уменьшать лишние обновления колонок, но делать вывод о реальной производительности без Hibernate SQL logs нельзя. Не требуется заменять на ручной JDBC.
5. **Hibernate entity setters:** широко открытая мутабельность — issue дизайна домена, но изменение Lombok getter/setter-политики потребует анализа всех callers и тестов.
6. **Логирование:** `OrganizationExceptionHandler` пишет ID, версии, URI и requestId, а не secret; оценить redaction и MDC в общем audit/log stack.
7. **Обслуживание:** отделение `OrganizationCommandOperations`/`OrganizationQueryOperations` повышает читаемость, но class-level security policy фактически зависит от фасада — держать тесты на прямые команды и HTTP boundary.
8. **Распределённое окружение:** consistency platform UUID и DB clock/transaction ordering важнее стилистического выравнивания Java-кода.

## 8. Тестовое покрытие: найденные тесты и необходимые случаи

В репозитории обнаружены: `OrganizationNameNormalizerTest`, `OrganizationPostgresIntegrationTest`, `OrganizationSecurityEpochPostgresIntegrationTest`, `OrganizationServiceAuthorizationBoundaryTest`, `OrganizationServiceTest`, `OrganizationControllerSecurityTest`, `OrganizationImpactQueryRepositoryTest`, `OrganizationRequestValidationTest` и другие тесты смежных модулей. Просмотрены названия методов ключевых тестов; **не заявляется**, что они все успешно выполнены на актуальном локальном checkout.

| Приоритет | Дополнительный тест / сценарий | Зачем |
|---|---|---|
| P1 | `rejectsPlatformWhenAllSuperAdminsDisabled` (`user` module) | Фиксирует реальный startup gap |
| P1 | `rejectsMismatchedPlatformOrganizationIdAcrossNodes` или deploy invariant | Защита от разной platform ID конфигурации |
| P1 | Disable vs V72 physical attempt STARTED after epoch increment | Проверить реальную DB-linearisability |
| P1 | Revocation fails -> organization disable transaction rolled back | Подтвердить атомарность `MANDATORY` |
| P2 | `unicodeCanonicalizationMatchesDatabase` (набор Unicode символов) | Java/PG divergence |
| P2 | `staleSecurityEpochRefreshRowNotCountedAsUsableSession` (если контракт уточнён) | Исправить смысл preview |
| P2 | `disableImpactIsAdvisoryAndNeverBypassesExpectedVersion` | Защититься от TOCTOU |
| P2 | `confirmationRejectsMeaningfullyDifferentNames` / NFKC variants | Защитить destructive intent |
| P2 | `auditSnapshotFrozenAfterOrganizationRename` | Историческая целостность |
| P2 | `findByIdAdminForeignOrganizationReturns404` | Tenant non-disclosure |
| P2 | `POST /disable` cannot touch platform ID | Platform protection |
| P2 | Concurrent rename with same expected version | Один commit / один conflict |
| P2 | Concurrent create case/whitespace-equivalent names | Unique index boundary |
| P3 | Directory literal `%`, `_`, `!` | JPQL LIKE safety |
| P3 | Pageable malformed sort, excessive size, negative page | Input bounds |
| P3 | Large directory query plan | Performance regression |

### Запуск в проекте (на локальной машине)

```bash
cd /Users/amid/Workspace/Projects/Products/SafeAI-Desk/backend

# Тесты модуля организации; точное имя Maven-модуля и плагины — по pom.xml.
./mvnw -Dtest='Organization*Test' test

# Полная сборка проекта; PostgreSQL integration tests могут требовать Docker.
./mvnw clean verify
```

Если в проекте отсутствует исполняемый wrapper `./mvnw`, использовать установленный `mvn`. Для точного теста продакшен-инвариантов нужны профили и environment variables согласно существующей тестовой инфраструктуре. **Не отключать `MockitoExtension` strict stubs, DB constraints, проверки `authVersion` или security method annotations ради зелёной сборки.**

## 9. Рекомендованный порядок исправлений без ослабления

**Этап 1 — безопасность и блокирующие риски.** Проверить, что локальный `OrganizationCommandOperations.java` не повреждён повторной вставкой; доработать проверку хотя бы одного **активного** SUPER_ADMIN при запуске production; закрепить одинаковый и валидный platform organization ID на всех узлах; сохранить миграционные DB security gates V25/V72.

**Этап 2 — точность контрактов.** Уточнить, что означает `activeRefreshSessions` и `activeChatOperations`; назвать их согласно SQL либо измерять реально действительные сессии/операции. Явно указать в REST/OpenAPI, что `disable-impact` — advisory snapshot, не запрет отключения при ненулевых числах. Добавить end-to-end tests на race preview→disable.

**Этап 3 — Unicode и UX.** Прогнать одинаковые наборы имён в Java и PostgreSQL, определить согласованную политику `NFKC`, control chars, Unicode Z-space; только затем вводить новую migration/изменение нормализатора. Не менять applied migrations и не переписывать canonical names без проверки конфликтующих строк.

**Этап 4 — эксплуатационная зрелость.** PostgreSQL concurrency tests, план поиска `LIKE`, readiness и audit monitoring, CI с Flyway validate + security tests, документировать expectedVersion/409, no-op операции и реальный scope SUPER_ADMIN vs ADMIN.

### Критерии готовности

- При `users>0`, `superAdmins>0`, `enabledSuperAdmins=0` production startup отвергается.
- `SUPER_ADMIN` с действующей записью имеется в platform organization; ошибочные platform ID не проходят startup проверки.
- После commit отключения V72 отвергает новые turns/attempts старой эпохи; уже начатые физические запросы учитываются без фальсификации отмены.
- При двух конкурентных администраторах версия и DB lock исключают silently lost updates.
- Неизвестные DB constraint failures не замаскированы под duplicate name.
- Обязательный аудит и отзыв refresh-сессий остаются в одной транзакционной модели.
- Счётчики и preview не используются как гарантированные показатели отсутствия нагрузки без соответствующего SQL доказательства.
- Корректная Java 21 + Spring Boot 4 сборка и интеграционные PostgreSQL тесты проходят в CI.

## 10. Современность для 2026–2027 годов

**Java 21:** выбранный язык позволяет records, switch, `Math.addExact` и **`Math.clamp` (доступен с Java 21)**. Версия языка соответствует данному коду; насильственный переход на новые language features не нужен.

**Spring Framework 7 / Boot 4.1:** `@Transactional`, `@PreAuthorize`, `@Validated`, Spring Data JPA `@Lock`, `@Query`, `Pageable` и transaction-bound events — применимые подходы. Рекомендация сохранять разделение application security, транзакций и persistence; проверять совместимость точных minor versions зависимостей в проекте. Планы на 2027 год не являются доказательством поведения будущих релизов.

**PostgreSQL:** сочетание UNIQUE/CHECK, миграций, row locks, DB triggers, security epoch и аудита — надёжная база для системы с несколькими узлами. Без DB integration и failure-injection tests нельзя назвать её формально доказанной.

**Архитектурный итог:** **полный переписыватель модулю не требуется**. Нужны узкие, проверяемые усиления перечисленных границ и мониторинга. Удалять двойную авторизацию, `expectedVersion`, pessimistic locks, transaction-bound revocation, V72 guards или строгие exceptions **не следует**.

## 11. Источники и воспроизводимость

### Исходный код, сверенный при подготовке

- GitHub `Amid-1/Safeai-desk`, ветка `main`: `backend/src/main/java/ru/safeai/gateway/organization/**` (включая `OrganizationCommandOperations`, `OrganizationQueryOperations`, `OrganizationNameNormalizer`, `OrganizationService`, `OrganizationImpactQueryRepository`).
- `backend/src/main/java/ru/safeai/gateway/auth/service/UserSessionRevocationService.java` — `@Transactional(propagation=MANDATORY)`.
- `backend/src/main/java/ru/safeai/gateway/user/service/PlatformOrganizationInvariantVerifier.java` — текущая проверка числа, но не включённости SUPER_ADMIN.
- `backend/src/main/resources/db/migration/V20__production_integrity_hardening.sql` — canonicalization/trigger.
- `V23__organization_normalized_name_index.sql`, `V24__user_security_and_audit_outbox_hardening.sql`, `V25__organization_auth_version.sql`, `V37__audit_target_organization_snapshots.sql`, `V72__organization_auth_epoch_physical_attempt_gate.sql`.
- Названия тестов в `backend/src/test/java/ru/safeai/gateway/organization/{service,controller,repository,dto}`.

### Официальная документация для проверки API

- [Java SE 21 `Math.clamp`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Math.html)
- [Spring Data JPA: `@Query`, escaping wildcard и LIKE](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)
- [Spring Framework: transaction-bound application events](https://docs.spring.io/spring-framework/reference/7.0/data-access/transaction/event.html)
- [Spring Data: repository query methods и pagination](https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html)

### Чего этот аудит **не** делал

- Не менял production Java файлы, Flyway migrations и клиентский REST контракт.
- Не исполнял Maven, Gradle, Docker, PostgreSQL или integration tests в пользовательском окружении.
- Не утверждал, что уязвимость на уровне PostgreSQL существует, исходя только из названия Java-метода.
- Не переносил ошибку повреждённого pasted text на корректный файл в GitHub.
- Не предсказывал будущие версии Spring/Java в 2027 году.

---

**Заключение:** модуль `organization` уже содержит существенные enterprise-механизмы целостности и безопасности. Подтверждённый риск максимального приоритета рядом с ним — отсутствие в production startup-verifier проверки **активного** платформенного SUPER_ADMIN. Далее следует формально определить значения advisory impact-счётчиков и проверить межъязыковую Unicode-нормализацию. Ослабление аутентификации, контроля версий, БД-блокировок и миграционных security fences **не требуется и не рекомендуется**.
