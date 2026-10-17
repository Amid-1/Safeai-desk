# SafeAI Desk — RateLimit

> **Техническое описание и аудит модуля на 17 октября 2026 года**  
> Стек: Java 21, Spring Boot 4, Spring Data Redis / Lettuce, Redis Lua, Micrometer, Spring Security.  
> Источники: Java-классы из предоставленного текста; дополнительно сопоставлены одноимённые файлы ветки `main` репозитория `Amid-1/Safeai-desk`, конфигурации `application.yml`, `application-prod.yml`, `ClientIpResolver`, `RateLimitAuditListener` и имеющиеся тесты.  
> **Статус**: статический аудит. Исправления в исходный код этим README не вносятся; тесты, отказоустойчивость и нагрузка в ходе подготовки отчёта *не запускались*.

## 1. Назначение

Модуль `ru.safeai.gateway.ratelimit` ограничивает частоту обращений к чувствительным API SafeAI Desk. Его задачи:

- затруднять перебор учётных данных на `/login` с учётом **email** и **IP**;
- ограничивать анонимные запросы обновления сессии по **IP** до обращения к БД токенов;
- ограничивать платные **AI-запросы** одновременно на уровне **пользователя** и **организации**;
- одинаково применять лимиты при нескольких экземплярах приложения (включая Kubernetes): счётчики и проверка находятся в общем Redis, а не в памяти JVM;
- при сбоях Redis не переходить к неограниченному пропуску запросов (*fail-closed*);
- выдавать клиенту HTTP **429** и `Retry-After`, а при отказе инфраструктуры — безопасный HTTP **503**;
- генерировать события превышения для аудита и метрики наблюдаемости.

**Границы ответственности.** RateLimit регулирует *частоту*, но не заменяет проверку пароля, JWT/refresh-token, authorization, лимиты финансовых квот AI, учёт физических вызовов провайдера или антибот-защиту. Число допущенных AI-запросов не равно числу оплачиваемых физических AI-вызовов: один логический запрос может порождать несколько попыток выполнения.

## 2. Карта компонентов

```text
ru.safeai.gateway.ratelimit/
├── exception/
│   ├── RateLimitExceededException          # 429, Retry-After
│   ├── RateLimitUnavailableException       # внутренняя ошибка Redis
│   ├── RateLimitExceptionHandler            # HTTP 429 / 503, ApiErrorResponse
│   └── RateLimitUnavailableLogger           # подавление повторяющихся логов
├── AiMessageRateLimitProperties              # user/admin/organization AI лимиты
├── LoginRateLimitProperties                  # login email+IP, окно, audit org
├── RefreshRateLimitProperties                # refresh IP, окно
├── RateLimitRedisKeyProperties               # HMAC secret, namespace, version
├── RateLimitProductionProperties             # явный escape hatch для AI
├── RateLimitProductionConfigurationValidator # production fail-fast
├── RateLimitKeyFactory                       # Redis ключи, HMAC, cluster tags
├── RedisFixedWindowRateLimiter               # все атомарные Lua-операции
├── LoginRateLimitService                     # политика входа/компенсация
├── RefreshRateLimitService                   # политика обновления сессии
├── RedisRateLimitService                     # политика AI трафика
├── RateLimitResult                           # одиночный счётчик и TTL
├── DualRateLimitResult                       # решение по двум измерениям
├── RateLimitDecision                         # ALLOWED/FIRST/SECOND/BOTH
├── RateLimitExceededEvent                    # событие аудита
└── RateLimitMetrics                          # Micrometer счётчики и таймеры
```

Смежные, но важные классы вне модуля:

- `audit/listener/RateLimitAuditListener`: синхронный слушатель `RateLimitExceededEvent`, запись `RATE_LIMIT_EXCEEDED` через standalone audit outbox (`REQUIRES_NEW`);
- `common/security/ClientIpResolver`: разбор IP и доверенных прокси; в конфигурации production `server.forward-headers-strategy: none`;
- `common/exception/ApiErrorResponseFactory` и `ApiExceptionResponseSupport`: единый JSON-контракт ошибок;
- вызовы RateLimit из authentication/chat-кода: **точный порядок всех фильтров и бизнес-транзакций требует отдельной сквозной проверки**.

## 3. Как работает обработка запросов

### 3.1. Вход в систему: LoginRateLimitService

1. При включённом лимитере нормализуются email (`trim` + `Locale.ROOT` lower-case) и IP (`trim`). Пустые значения заменяются специальной строкой `unknown`.
2. `RateLimitKeyFactory` создаёт псевдонимизированные HMAC-SHA-256 ключи для email/IP, а также ключи маркеров превышения.
3. `RedisFixedWindowRateLimiter.incrementBothAndCheck(...)` исполняет **один Lua-скрипт** с четырьмя `KEYS`.
4. Скрипт проверяет существующие счётчики, увеличивает **оба счётчика на попытку** (`INCR`), назначает TTL только если его нет, сравнивает `newCount > limit`.
5. Если превышена хотя бы одна граница, попытка блокируется. При первом превышении данного измерения в окне через `SET ... NX PX` создаётся notification marker.
6. Сервис публикует событие превышения (при необходимости), записывает метрику отказа и выбрасывает `RateLimitExceededException` (HTTP 429).
7. **Только после успешной аутентификации** должен вызываться `onLoginSuccess(...)`: другой Lua-скрипт полностью удаляет email-счётчик и его marker, а IP-счётчик уменьшает на 1, сохраняя ранее накопленные ошибки других пользователей за NAT.

**Семантика:** отличие login от AI важно: отклонённая login-попытка *тоже увеличивает* counters. Это помогает учитывать дальнейший перебор. Ветка успешной авторизации выполняет компенсацию, чтобы добросовестные пользователи за общим NAT не исчерпывали IP-limit.

**Пограничная ситуация:** компенсация не содержит идентификатора конкретной попытки/окна. Если ответ об успешном входе задержался до истечения TTL и создания нового окна либо перекрывается параллельными попытками, `DECR` может затронуть чужой или новый IP-счётчик. Это требует отдельного дизайна и теста (см. P1-03).

### 3.2. Обновление сессии: RefreshRateLimitService

1. Ключ строится по HMAC IP в отдельном namespace `rate-limit:refresh:ip:`.
2. `incrementAndGet()` атомарно увеличивает **один** счётчик, TTL закрепляется на начале фиксированного окна.
3. Проверка `count <= ipLimit`: первые N запросов допускаются, N+1 возвращает HTTP 429.
4. При отказе Redis сервис выдаёт 503. В комментариях указано, что проверка должна выполняться до обращения к хранилищу refresh-токенов; само место вызова нужно подтверждать при интеграционной проверке.

### 3.3. AI-запросы: RedisRateLimitService

1. Вычисляются лимит пользователя (повышенный для `ROLE_ADMIN` / `ROLE_SUPER_ADMIN`) и общий лимит организации.
2. Lua `tryIncrementBoth(...)` **сначала проверяет текущие значения** (`current >= limit`). Если какое-либо измерение исчерпано, оба счётчика **не увеличиваются**.
3. Если оба лимита допускают запрос, скрипт атомарно увеличивает оба counters.
4. При отказе возвращаются HTTP 429 и оставшийся TTL того/тех измерений, которые блокируют запрос; при двойном превышении берётся максимум TTL.
5. Redis недоступен — HTTP 503, а не выполнение платного вызова без ограничения.

**Гарантия:** для корректно выполненного Lua-скрипта при едином Redis состоянии допуск user+org линеаризуем в рамках одного Redis server/slot. **Оговорка:** Redis Lua атомарен относительно конкурирующих команд, но не является транзакцией с rollback при ошибке внутри скрипта; уже выполненные операции могут сохраниться, если следующая команда скрипта аварийно завершилась.

### 3.4. Маркеры уведомлений и аудит

- Внутри Lua `SET marker '1' PX ttl NX` снижает число одинаковых событий за окно.
- `RateLimitExceededEvent` передаётся через `ApplicationEventPublisher`.
- `RateLimitAuditListener` слушает его синхронно и инициирует durable audit outbox в отдельной транзакции. Системный actor применяется для анонимного login.
- Если synchronous publish/enqueue провалился, сервис старается удалить созданные markers, чтобы при следующем отказе была повторная попытка аудита.
- Ошибка публикации **не должна заменять** исходный ответ 429 ошибкой аудита. Это намеренная best-effort модель, а **не** строгая гарантия «каждый отказ сохранён в audit_events».
- Удаление marker сейчас выполняется по имени ключа; без сравнения уникального ownership token возможна гонка с маркером, созданным позже.

**Важная деталь PII:** тест `RateLimitExceededEventPiiTest` демонстрирует безопасно составленный отдельный DTO, однако production-код `LoginRateLimitService` в `buildEventDetails()` добавляет **сырое нормализованное `email`** в `details`, а `RateLimitExceededEvent` получает его как `actorEmail` даже при `actorUserId == null`. Далее listener создаёт системное событие, и центральный unstructured sanitizer, согласно предоставленному коду Audit, не допускает неизвестные свободные строковые ключи (`email`). Это **не доказывает утечку в БД**, но перенос PII по event pipeline существует и требует проверки/сокращения. Тест с ручным DTO не доказывает поведение production-пути.

## 4. Redis: ключи, TTL и особенности Cluster

Примеры логических форм ключей (реальные HMAC-значения не приводятся):

```text
<prefix>:<keyVersion>:rate-limit:{login}:email:<hmac>
<prefix>:<keyVersion>:rate-limit:{login}:ip:<hmac>
<prefix>:<keyVersion>:rate-limit:{login}:email:<hmac>:exceeded
<prefix>:<keyVersion>:rate-limit:refresh:ip:<hmac>
<prefix>:<keyVersion>:rate-limit:{ai-<org-hmac-prefix>}:user:<user-hmac>
<prefix>:<keyVersion>:rate-limit:{ai-<org-hmac-prefix>}:organization
```

- **HMAC-SHA-256** скрывает исходные email/IP/User UUID в ключах Redis. Используются разные domain separators: `login-email:`, `login-ip:`, `refresh-ip:`, `audit-email:`, `audit-ip:`, `ai-user:`, `ai-organization:`.
- Все login-ключи используют `{login}` — одинаковый Redis Cluster hash slot, необходимый для скрипта, изменяющего email+IP. **Цена**: весь login rate-limit трафик концентрируется на **одном шарде**; горизонтальный рост Cluster сам по себе этого не снимает.
- AI user+org используют общий **для конкретной организации** hash tag, т.е. распределяются между организациями. Очень крупная организация остаётся hot slot.
- Refresh использует один ключ без фиксированного общего hash tag и лучше распределяется по слотам.
- `key-prefix`/`key-version` являются частью identity namespace; изменение `key-version` фактически переключает трафик на новые, пустые счётчики. Во время rolling deployment с разными версиями появляются **раздельные бюджеты** ограничений.
- TTL в **миллисекундах**; возвращаемые пользователю TTL округляются **вверх до секунд**. Повторный `INCR` не продлевает первоначальное окно.
- Это **fixed window с TTL от первой операции**, не sliding-window и не token bucket. На границе двух соседних окон краткосрочная плотность запросов может быть близка к двум лимитам подряд.

## 5. HTTP и исключения

| Ситуация | HTTP | Код API | Особенности |
|---|---:|---|---|
| Достигнут лимит | `429` | `RATE_LIMIT_EXCEEDED` | `Retry-After` в секундах, если положителен; безопасное сообщение |
| Redis/script/connection недоступны | `503` | `RATE_LIMIT_UNAVAILABLE` | Фиксированное публичное сообщение, без Redis stack trace |
| Запрос допустим | Зависит от бизнес-эндпоинта | — | RateLimit продолжает бизнес-операцию |

`RateLimitExceededException` округляет `Duration` без опасного `Duration.toMillis()` и насыщает значение на `Long.MAX_VALUE`, что корректно для больших Duration. `RateLimitExceptionHandler` использует общую фабрику JSON-ответов.

**Риск:** наружу стеки не возвращаются, но внутреннее логирование в ряде веток выводит объект исключения с cause. Разделять безопасность HTTP-response и безопасность логов обязательно.

## 6. Конфигурация

Значения по умолчанию из проверенного `backend/src/main/resources/application.yml` (их можно переопределять env):

| Параметр | Значение | Назначение |
|---|---|---|
| `safeai.rate-limit.login.enabled` | `true` | Обязательность login-limiter |
| `safeai.rate-limit.login.email-limit` | `10` | Попыток email за окно |
| `safeai.rate-limit.login.ip-limit` | `100` | Попыток IP за окно |
| `safeai.rate-limit.login.window` | `10m` | Login fixed window |
| `safeai.rate-limit.refresh.enabled` | `true` | Refresh limiter |
| `safeai.rate-limit.refresh.ip-limit` | `600` | Refresh запросов на IP |
| `safeai.rate-limit.refresh.window` | `1m` | Refresh fixed window |
| `safeai.rate-limit.ai-messages.enabled` | `true` | User+org AI limiter |
| `safeai.rate-limit.ai-messages.user-limit-per-hour` | `20` | Часовой лимит пользователя |
| `safeai.rate-limit.ai-messages.admin-limit-per-hour` | `100` | Часовой лимит администратора |
| `safeai.rate-limit.ai-messages.organization-limit-per-hour` | `1000` | Часовой лимит организации |
| `safeai.rate-limit.redis.key-version` | `v1` | Версия namespace счётчиков |
| `safeai.rate-limit.redis.hmac-secret` | Обязательный env | Не менее 32 UTF-8 байт; не выводить в логи |
| `safeai.rate-limit.redis.key-prefix` | `safeai:local` в общем конфиге | **В prod обязательное внешнее значение**, без наследования local |

`safeai.rate-limit.login.audit-organization-id` обязателен для включённого login-limiter; в общей конфигурации предусмотрен fallback через platform organization. Он должен соответствовать реальной существующей организации в БД.

`RateLimitProductionConfigurationValidator` запрещает в production отключение **login** и **refresh**. Для отключения AI-limiter требуется явное `safeai.rate-limit.production.allow-unlimited-ai-traffic=true`. Это предохранитель от случайного отключения, но сам escape hatch открывает риск неограниченного платного трафика; нужен отдельный change approval/мониторинг.

**Deployment:** Redis namespace, HMAC secret и key version должны быть синхронизированы между всеми pod приложения. Разные секреты дают разные Redis-ключи даже при общем префиксе; нарушение означает обход объединённого rate-limit бюджета. При ротации секретов нужен согласованный план cutover.

## 7. Наблюдаемость

Реализованные Micrometer meters (имена до Prometheus-normalization):

- `safeai.rate.limit.allowed` — число допусков (label `type`);
- `safeai.rate.limit.rejected` — отклонения (`type`, `dimension`);
- `safeai.rate.limit.unavailable` — ошибки инфраструктуры (`type`);
- `safeai.rate.limit.audit.publish.failed` — ошибка публикации audit event (`type`);
- `safeai.rate.limit.redis.duration` — latency Redis-операций (`type`, `operation`, `outcome`) и histogram.

`RateLimitMetrics` реализует **best-effort observability**: отказ метрик не может изменить решение о допуске либо скрыть Redis ошибку. Значения tags в штатном использовании имеют низкую кардинальность.

**Предлагаемые алерты** (пороговые значения настроить на профиле нагрузки):

1. `increase(safeai_rate_limit_unavailable_total[5m]) > 0` — любое событие отказа limiter в production;
2. `increase(safeai_rate_limit_audit_publish_failed_total[5m]) > 0` — потери надёжности аудита;
3. высокий p95/p99 `safeai_rate_limit_redis_duration_seconds` — деградация Redis, особенно login hash slot;
4. резкий рост `safeai_rate_limit_rejected_total` по `type` и `dimension` — brute force, сбой клиента либо слишком низкий лимит;
5. Redis memory/CPU latency, `rejected_connections`, `evicted_keys`, состояние Cluster, failover, число тайм-аутов Lettuce.

*Примечание:* точный экспорт names зависит от Micrometer/Prometheus registry; сверить с реальным `/actuator/prometheus` перед включением правил. Метрики запроса не являются доказательством доставки Audit: отдельно контролировать Audit Outbox backlog/dead-letter.

## 8. Что хорошо и современно на 2026–2027

| Область | Вывод |
|---|---|
| Распределённый rate limit | **Сильно:** общий Redis делает ограничения общими для всех JVM/pod. |
| Атомарный допуск AI | **Сильно:** user и organization проверяются и резервируются одним Lua-скриптом, нет обычной гонки read-then-INCR. |
| Fail-closed | **Сильно:** login/refresh/AI не переходят на безлимитную деградацию при сбое Redis. |
| Redis Cluster | **Хорошо с оговоркой:** ключи multi-key Lua гарантированно относятся к одному slot, но `{login}` создаёт single-shard hotspot. |
| Privacy by design | **Хорошо:** HMAC keys, domain separation, `RateLimitRedisKeyProperties.toString()` редактирует секрет. Нужен контроль PII в событиях и логах. |
| Отдельные политики | **Сильно:** login, refresh и AI имеют разные алгоритмы и конфигурации, не смешаны в generic limiter. |
| Публичный HTTP API | **Хорошо:** 429 + Retry-After, 503 + без раскрытия детали Redis, общий `ApiErrorResponse`. |
| Audit | **Хорошо:** отдельный durable outbox, retry-on-next-rejection при сбое enqueue; однако доставка самих событий остаётся best effort на границе rate-limit. |
| Production invariants | **Хорошо:** fail-fast на выключенных login/refresh, явный escape hatch для AI. |
| Тестируемость | **Хорошо:** уже есть Testcontainers Redis, проверки параллельного допускания, TTL, malformed counters, Cluster slot contract и properties validation. |

Современный дизайн не означает отсутствие предельных случаев. Особенно важно различать **атомарность выполнения Lua относительно других клиентов** и **транзакционный rollback при внутренней ошибке Lua**: второго Redis Lua не обещает.

## 9. Найденные проблемы и приоритеты

### P0 — подтверждённых безусловных блокеров по предоставленному коду нет

Не выявлен доказанный сценарий, в котором обычная ошибка Redis автоматически разрешает неограниченные AI/авторизационные вызовы. Это **не** заключение о полном отсутствии критических уязвимостей: код вызывающих auth/chat-контроллеров и эксплуатационная инфраструктура не охвачены полным сквозным аудитом.

### P1-01. Полные исключения / stack traces в логах — риск утечки сведений

**Подтверждено кодом:**

- `RateLimitUnavailableLogger.log()` передаёт `exception` последним аргументом `logger.error(...)` — SLF4J трактует Throwable как stack trace со всеми вложенными сообщениями.
- `LoginRateLimitService.publishExceededEventBestEffort()` и `warnCleanupFailureRateLimited()` передают exception в `log.warn(...)`.
- `RedisRateLimitService.publishExceededEventBestEffort()` передаёт exception в `log.warn(...)`.
- `RateLimitMetrics.warn()` записывает `exception.toString()`, включая сообщение.
- `RateLimitUnavailableLogger` пишет `request.getRequestURI()` без нормализации; path segments могут быть недоверенными и содержать чувствительные значения.

**Риск:** причины Spring Data Redis/Lettuce и сторонних исключений могут содержать адреса, ключи или части запросов. Включение stack trace в журнал не эквивалентно непосредственной утечке секретов, но делает это возможным.

**Рекомендация:** централизованный `SafeRateLimitFailureDescriptor` — класс исключения, bounded error classification и requestId; без `getMessage()`, `toString()`, `Throwable` и произвольного URI. Сохранять корреляцию через requestId и метрики. Полные трассы — только в специально защищённом диагностическом контуре с контролируемой редукцией, если это действительно необходимо.

### P1-02. Общий Redis Cluster slot `{login}` — масштабирование и доступность

**Подтверждено кодом:** все email и IP login keys входят в `{login}`. Для `incrementBothAndCheck` Redis Cluster требует общего slot. На интенсивном потоке login один node/slot становится hotspot, даже если Cluster имеет несколько shard.

**Рекомендация:** замер CPU/latency и пропускной способности при пиковом login/brute-force. Нельзя просто убрать `{login}`: multi-key Lua начнёт давать `CROSSSLOT`. Возможные архитектурные варианты — отдельный Redis/шард для login, переработка модели на сегменты с понятной гарантией атомарности либо специализированный gateway-level limiter. Выбор требует требования к точной атомарности двух измерений.

### P1-03. Компенсация успешного login без привязки к попытке и окну

**Подтверждено кодом:** после login `resetFirstAndDecrementSecond(emailKey, ipKey, markers)` уменьшает текущий IP counter, но не проверяет факт/идентификатор ранее засчитанной попытки. Временной интервал между check и success не связан с конкретным TTL или reservation token.

**Риск:** запоздалая успешная аутентификация, смена окна или параллельные запросы под одним NAT могут уменьшить счётчик новых/чужих попыток. Сценарий нужно воспроизвести тестом до утверждения степени фактического ущерба.

**Рекомендация:** reservation id + window id и idempotent компенсация **той самой** инкрементации; либо модель отдельных failed-attempt counters, в которой success не требует раздельного IP `DECR`. Не менять арифметику Lua без нагрузки и регрессионных тестов NAT/параллелизма.

### P1-04. PII в production event pipeline и недостаточная coverage теста

**Подтверждено кодом:** `LoginRateLimitService.buildEventDetails()` передаёт нормализованный raw `email`, а `RateLimitExceededEvent` — `actorEmail`; при этом DTO-тест проверяет вручную созданный event без raw email, не сам production producer. Audit sanitizer может не пропустить поле `email` в хранилище, но **внутри приложения** оно присутствует.

**Рекомендация:** для анонимного login убрать email из event details и actor snapshot, оставить HMAC fingerprint; проверить фактический `AuditDetailsSanitizer` и DB payload сквозным интеграционным тестом, а также запрет raw email в логах. Не удалять fingerprint, который нужен для расследований.

### P1-05. Best-effort audit не равен гарантированному evidence

Сервис отвечает 429 даже после ошибки публикации; маркер удаляется best-effort. При повторных отказах Redis, отсутствии следующей попытки либо ошибках восстановления markers событие превышения **может не попасть в Audit**.

**Рекомендация:** закрепить SLA/требование: является ли каждая блокировка security evidence или достаточны выборочные/агрегированные notifications. Если необходим 100%-й учёт отказов, нужен проект durable-first/event-log подхода, учитывающий failure isolation и latency. Не объявлять нынешний best-effort `at least once` без доказательства.

### P2-01. Гонка при удалении notification markers

`releaseNotificationMarkers()` удаляет Redis keys без проверки значения маркера. При истечении TTL и создании нового marker между `SET NX` и `DEL` старый обработчик может стереть чужой marker. Возможны лишние повторные уведомления либо изменение политики дедупликации.

**Рекомендация:** случайный ownership token на каждый установленный marker; атомарное Lua compare-and-delete. В существующих scripts marker value сейчас константа `'1'`, её придётся менять согласованно в producer/release.

### P2-02. Первое предупреждение может быть подавлено из-за `nanoTime()`

`RateLimitUnavailableLogger` и `LoginRateLimitService` инициализируют `nextLogNanos = 0`. `System.nanoTime()` не гарантирует положительного значения. При отрицательном базовом значении проверка `now - next < 0` может не дать залогировать **первую** ошибку. Вторая проблема: rate limiting логов локальный на JVM, а не cluster-wide.

**Рекомендация:** явный `initialized`/CAS sentinel, независимый от знака `nanoTime()`, либо проверенный monotonic rate-limited logger. Добавить тест с инжектируемым clock/nanoTime source и отрицательным стартовым значением.

### P2-03. `unknown` объединяет разных клиентов в общий bucket

`LoginRateLimitService.normalizeEmail/normalizeIp`, `RefreshRateLimitService.normalizeIp` возвращают одинаковое `unknown` для пустого идентификатора. Если upstream validation допускает такие обращения, независимые клиенты оказываются в общем bucket: один атакующий сможет исчерпать лимит для других.

**Рекомендация:** email валидировать до limiter, IP получать исключительно через `ClientIpResolver`, неизвестный IP обрабатывать отдельной защитной политикой, а не считать его полноценной идентичностью. Важно не превращать отсутствие IP в разрешение запроса без учёта.

### P2-04. Окна fixed-window и изменения конфигурации

На границе окна можно отправить плотный всплеск запросов; Redis key version / HMAC secret / key-prefix при несогласованном rolling deployment порождают несколько независимых наборов counters. Это документированная семантика, не программная ошибка.

**Рекомендация:** если требуется строгий per-minute/per-second enforcement, оценить sliding-window / GCRA / token bucket; для конфигурации — immutable rollout и проверку всех pod на одинаковые namespace/secret/version без вывода самих секретов.

### P2-05. Redis Lua частичные изменения при runtime-ошибке

Скрипт атомарен по отношению к другим запросам, но error после успешного `INCR`, `PEXPIRE` либо первого marker `SET` не откатывает исполненные Redis-команды. В обычных сценариях код валидирует counters до инкрементов, что хорошо, но это не покрывает ошибки вроде OOM после первой записи.

**Рекомендация:** fault-injection на стадии мутаций, анализ допустимой консервативной блокировки и мониторинг расхождения/ошибок Redis. Не полагаться на несуществующий Lua rollback.

### P2-06. Масштабирование счётчиков и мониторинг

Хотя TTL сдерживает срок жизни большинства ключей, атакующий может создавать очень много разных идентификаторов и раздувать Redis memory. Также нет доказанного теста throughput для единого `{login}` slot и сетевых сбоев Sentinel/Cluster во всех production-сценариях.

**Рекомендация:** ограничить длины/кардинальность email перед key derivation, использовать upstream WAF/gateway per-IP protection и Redis memory alerts; провести нагрузочные и failover тесты.

## 10. Программа тестирования перед production

### Уже присутствуют в репозитории (наличие проверено, успешный прогон не подтверждён)

- `RedisFixedWindowRateLimiterIntegrationTest`: Testcontainers Redis, atomic limits, TTL, NAT и notifications;
- `RedisFixedWindowRateLimiterIT`: конкурентная dual reservation, TTL, malformed counter/overflow;
- `RedisFixedWindowRateLimiterSingleKeyIntegrationTest`: single-key сценарии;
- `RedisClusterSlotContractTest`: соответствие hash slots для multi-key Lua;
- `LoginRateLimitServiceTest`, `RedisRateLimitServiceTest`, `RefreshRateLimitServiceTest`: business policies;
- `RateLimitExceededExceptionTest` и `exception/RateLimitExceptionHandlerTest`: `Retry-After`, 429/503;
- `RateLimitProductionConfigurationValidatorTest`, `RateLimitPropertiesTest`, `RateLimitKeyFactoryTest`: конфигурация и HMAC;
- `RateLimitExceededEventPiiTest`: часть PII DTO-contract, **не полноценный end-to-end producer → Audit outbox тест**.

### Обязательно добавить / проверить

1. **PII leakage regression:** fake exception `new RuntimeException("secret_token=... email=...")`; убедиться, что ответ **и все структурированные журналы** не содержат секретов.
2. **End-to-end audit:** producer login с настоящими details → listener → outbox → audit_events; verify raw email/user IP/header не попали в immutable event.
3. **Login post-window compensation:** check в окне A, задержка, новое окно B, затем success A; попытка A не уменьшает счётчик B.
4. **Marker compare-and-delete concurrency:** TTL expired, new marker claimed, stale failure A не удаляет marker B.
5. **Kubernetes/Redis Cluster smoke:** два pod с одним HMAC/namespace, подтверждение общего budget; разные секреты/версии должны быть обнаружены в deployment-контроле.
6. **Single-slot load test:** тысячи одновременных login при допустимых условиях, p95/p99 и CPU shard `{login}`, connect pool/timeouts.
7. **Redis failure-injection:** timeout, unreachable, failover, error после первой Lua мутации, malformed keys/counters, eviction, Redis OOM.
8. **Proxy trust validation:** подмена `X-Forwarded-For`, недоверенный client socket, trusted proxy chain, IPv4/IPv6, invalid headers и `unknown`.
9. **HTTP contract:** 429 с правильным `Retry-After`, 503 без backend details и всегда `Cache-Control: no-store`.
10. **Alert delivery:** проверить Prometheus scrape/Alertmanager и связать инциденты `rate.limit.unavailable` с состоянием Redis и Audit dead-letter.

Команды в корне `backend` (при наличии Docker/Testcontainers, JDK 21, Maven wrapper):

```bash
./mvnw -Dtest=RateLimitPropertiesTest,RateLimitKeyFactoryTest,RedisClusterSlotContractTest,LoginRateLimitServiceTest,RedisRateLimitServiceTest,RefreshRateLimitServiceTest,RateLimitProductionConfigurationValidatorTest test

./mvnw -Dtest=RedisFixedWindowRateLimiterIntegrationTest,RedisFixedWindowRateLimiterSingleKeyIntegrationTest,RedisFixedWindowRateLimiterIT test
```

Если Maven wrapper отсутствует, использовать `mvn`. Уточнить в CI, какие `*IT` запускаются через Surefire и какие вынесены в Failsafe/profile: совпадение имени не является гарантией запуска нужного набора по умолчанию.

## 11. План улучшений без ослабления существующих гарантий

**Этап 1 — конфиденциальность и проверяемость (P1):** безопасное логирование без exception messages/Throwable; исключить raw email из audit event producer; добавить end-to-end PII и log-capture тесты. Не менять политику fail-closed и HTTP-коды.

**Этап 2 — консистентность (P1/P2):** согласовать модель компенсации login по window/reservation; ввести ownership-aware notification marker release; добавить конкурентные и boundary-тесты. Обязательно сохранить неизменными смысл двух типов Lua-скриптов: login считает все попытки, AI — только разрешённые.

**Этап 3 — эксплуатационная надёжность (P1/P2):** профилировать `{login}` slot; проверить Redis Sentinel/Cluster failover; алерты по недоступности, latency, rejects, audit publish failures; защитить секреты и namespace от несогласованного rollout.

**Этап 4 — масштабирование по измерениям:** при необходимости рассмотреть модель login-limiter без общего hot slot, но только с формальным контрактом атомарного dual limit. Простая смена hash tag сломает Redis Cluster multi-key Lua.

## 12. Итоговый вердикт

**Архитектура RateLimit в целом грамотная и актуальная для 2026–2027 годов**: распределённое состояние, атомарная проверка Redis Lua, детализированные политики, HMAC-псевдонимизация, fail-closed, отдельные домены Redis, безопасный HTTP-контракт, production guards и наличие реальных Redis integration tests.

**Не следует считать модуль полностью production-hardened без устранения P1:** прежде всего исключить потенциально конфиденциальные stack traces, убрать raw email из production event pipeline, проверить компенсацию успешного login при пересечении окон и измерить нагрузку на единый `{login}` slot. P2 — гонки markers, monotonic logger, `unknown` buckets, window-boundary/rolling secrets и реальные fault/load-тесты.

**Важное ограничение отчёта:** это инженерный аудит предоставленных классов и дополнительных найденных исходников, а не результат успешного CI/CD или тестирования живого кластера. Указанные выводы отделяют подтверждённые по коду свойства от требующих воспроизведения гипотез.
