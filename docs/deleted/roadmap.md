# SafeAI Desk — Production Roadmap: управляемое удаление данных и закрытие организаций

> **Документ:** `roadmap.md` · версия **1.2 / 26.11.2026**
> **Статус:** архитектурное решение, исторический аудит и план R2.3. **НЕ Production GO**.
> Разделы с датой 24.11.2026 отражают исторический snapshot, а не текущее состояние.
> R2.3 — кандидат на локальную приёмку; не подтверждён Maven, PostgreSQL, E2E и CI.
> **Основа ревизии:** новые архивы Java (`Архив(20261124-164251).zip`), resources, resources_test, tests,
> frontend(4), frontend root (`Архив(20261124-164535).zip`); предоставленные ранее 9 тематических архивов и
> предыдущий roadmap.
> **Область:** USER, ADMIN, SUPER_ADMIN; чаты, отдельные сообщения, AI-запросы, документы, базы знаний,
> учётные записи, администраторы, организации, модели, секреты, производные данные, выгрузки, резервные
> копии, аудит.
> **Правило маркировки:** **[ФАКТ]** подтверждено архивным кодом/SQL; **[ПРОЕКТ]** целевой контракт;
> **[ПРОВЕРИТЬ]** требует сверки с Git HEAD, окружением или правовой политикой. **[РЕШЕНИЕ]** рекомендуемый
> принцип, подлежащий утверждению до реализации.

---

## Навигация по документу

- [0–3. Решение, аудит, операции и полномочия](#0-решение-техлида-и-границы-системы)
- [4–7. Доступ, данные, заявки, миграции](#4-авторизация-проверка-личности-и-независимое-согласование)
- [8–9. REST API, кабинеты и уведомления](#8-целевые-rest-api-контракты-проект--см-80-о-фактических-маршрутах)
- [10–11. Исполнение, S3, инфраструктура](#10-пообъектная-оркестрация-исполнение)
- [12–14. Этапы, P0-тесты, Production GO](#12-roadmap-внедрения-с-конкретными-результатами-и-шлюзами)
- [15–19. Решения, R0+R1, исходники, итог](#15-решения-которые-нужно-утвердить-до-r1-не-угадывать)

---

## 0. Решение техлида и границы системы

**Нужно построить единый управляемый контур заявок (Erasure Case Management) и независимый контур исполнения
(Erasure Orchestration).** Заявка, разрешение, техническая возможность, фактическое удаление, юридическое
удержание и удаление из резервных копий — **разные события**. HTTP 200/202, удалённая строка БД и зелёный
индикатор не доказывают завершение всех операций.

Принципиальные решения:

1. **Никаких прямых массовых DELETE по одному клику**. Организация, пользователь и модель проходят
   специализированный workflow.
2. **Многоарендность:** `organization_id` обязателен для каждой связанной операции. Не полагаться на ID из
   URL, клиентские claims или наличие кнопки в UI; проверять владельца и состояние организации в транзакции.
3. **Не удаляем чужое вместе со своим:** личный контент, совместно используемый контент организации, чужое
   авторство, доказательства расходов и инфраструктурные данные разделены.
4. **Согласование по риску, не для красоты:** обычные низкорисковые операции доступны без лишней бюрократии,
   но удаление чужой учётной записи, базы знаний целиком, администратора, организации и защищённых данных
   требует независимого контроля.
5. **Сохраняем защитные механизмы:** текущие V1–V102 не меняем; `RuntimeAclPolicy`, reviewed manifest,
   ограничения DB, worker-specific credentials, S3 Object Lock и юридические удержания не обходим.
6. **Приоритет удаления:** персональные права субъекта не дают автоматического права уничтожить
   корпоративную базу знаний, финансовый аудит, чужие сообщения или юридически обязательные записи.
7. **Внешние AI-провайдеры:** нельзя подтверждать стирание на стороне провайдера без поддерживаемого
   API/договора/официального подтверждения. В статусе указывать границу проверки.
8. **Никакого «удалено навсегда» при наличии непроверенных копий.** UI показывает ровно подтверждённый scope
   удаления, исключения и сроки их устранения.
9. **Каждому типу данных — отдельный release gate.** Разрешение запросов `chat_allowed` /
   `knowledge_allowed` из V102 не заменяет проверку executor, прав, S3 и dependency scan.
10. **Разработку начинать с R0 → R1 → R2.** Физическое удаление подключать только к уже проверенным
    обработчикам и по типам ресурсов.

### 0.1. Что НЕ обещаем

- Не обещаем мгновенного стирания из неизменяемого аудита, S3 Object Lock, внешних AI-провайдеров или
  резервных копий.
- Не считаем отключение пользователя, скрытие сообщения, архивирование чата или disable организации
  физическим удалением.
- Не считаем `ADMIN` собственником всех личных данных сотрудников.
- Не добавляем в MVP универсальное удаление `SUPER_ADMIN` и платформенной организации через пользовательский
  API.
- Не добавляем право на физическое удаление immutable model catalog row: в текущем V45 есть запрещающий
  trigger.
- Не делаем новые миграции без учёта `reviewed-migrations.sha256`, Runtime ACL и Flyway sequential upgrade.

---

### 0.2. Заключение ревью версии 1.1

**Исходный roadmap верен по направлению, но не готов к реализации без уточнений.**
Ниже устранены ошибки и усилены обязательные условия:

1. Создание **черновика** и **подача** заявки разделены по API и состояниям. Ответ `201 DRAFT` не равен
   `SUBMITTED`, а `202` не означает стирание.
2. Обращение о правах субъекта данных сохраняется даже при выключенном worker. V102 запрещает **новый
   destructive intent**, но не регистрацию законного обращения.
3. Глобальная политика, разрешение конкретного объекта и право worker на стирание проверяются
   **независимо**; одобрение случая не пропускает блокировку.
4. Все старые destructive endpoints должны пройти общий серверный gate. Новый UI без закрытия обходного пути
   в backend **не принимается**.
5. Запрет `hold/cancel` сериализуется с **первым необратимым шагом**; при частично исполненной saga отмена
   не превращается в ложный `CANCELLED`.
6. На каждый источник данных ведётся отдельное обязательство удаления; `COMPLETED_FULL_SCOPE` недопустим при
   открытых или неизвестных исключениях.
7. Обезличивание отделено от псевдонимизации, а очистка backup требует отдельного доказательства и защиты от
   восстановления данных.
8. Добавлены проверки подмены preview, обхода worker, повторного approval, деанонимизации по хэшам и
   зависимостей между организациями и моделью.
9. Внешние правовые сроки, MFA и production-готовность не выдумываются. Без решения владельца процесса
   соответствующий destructive gate остаётся `OFF`.
10. Форматирование рассчитано на **ширину 108 символов** в IntelliJ IDEA: длинные Markdown-таблицы
    преобразованы в вертикальные записи без потери полей.

### 0.3. Что утверждаем, а что пока блокируем

- **APPROVE R0:** инвентаризация, юридическая матрица, моделирование угроз.
- **APPROVE R1:** регистрация и рассмотрение заявок, при неизменно выключенном исполнении.
- **R2 — интерфейс, безопасные read-only проекции и workflow:** не симулировать физическое удаление.
- **NO-GO R3–R8:** необратимые операции до отдельной evidence-приёмки каждого ресурса.
- **NO-GO:** универсальное стирание аккаунта, организации, immutable версии модели,
  обход WORM/hold либо массовое стирание чужого содержимого одной ролью ADMIN.

---

## 1. Результаты повторного аудита исходников

Из вновь предоставленных архивов восстановлена **кодовая выборка**: 758 файлов backend production Java, 474
backend Java test, 126 backend resources, 6 test resources, 584 frontend src; дополнительно получены
корневые файлы frontend, старые `.github`/`infra` и `pom.xml`. ZIP-архивы **не содержат проверяемого Git
commit SHA**; поэтому все ссылки на текущую реализацию надо ещё сопоставить с `git rev-parse HEAD` перед
написанием R1. Архивные CI/infra файлы могли быть старее живой ветки.

Манифест `backend/src/main/resources/db/acl/reviewed-migrations.sha256` проверен по байтам: **111 записей,
ошибок 0**, `V102` = `767091c7cf9f5b6a2d9c81d9269a258baac1d64c32fb0c3fa85ddd4b2c794266`. Проверка хэша
**не** подтверждает применённость миграции к конкретной БД.

**Retention**
- **[ФАКТ] в коде на 24.11.2026:** `RetentionManagementService`: глобальные `chat_allowed`,
  `knowledge_allowed`, optimistic version, SUPER_ADMIN платформы; `ErasureAdmissionPolicy` проверяет допуск
  под блокировкой
- **Главный пробел:** Нет адресной заявки, согласующих, фактического health/result proof

**Chat**
- **[ФАКТ] в коде на 24.11.2026:** `ChatLifecycleController` `/api/chats`: архив/восстановление владельца;
  `DELETE /{chatId}/erasure` только `ADMIN`; собственный архивный чат, подтверждение, идемпотентность,
  `ChatErasureWorker`
- **Главный пробел:** Нет self-service USER и удаления **одного** сообщения; стирается
  текст/заголовок/retrieval query, не все связанные исторические записи

**Document**
- **[ФАКТ] в коде на 24.11.2026:** `KnowledgeDocumentDeletionController`: `DELETE
  /api/knowledge-bases/{baseId}/documents/{documentId}`, `If-Match`, `Idempotency-Key`, `OWNER`/same-tenant
  ADMIN; отдельные read endpoints и worker
- **Главный пробел:** Нет общего реестра, согласования для чужого содержимого и единой прозрачной справки по
  всем производным копиям

**Knowledge base**
- **[ФАКТ] в коде на 24.11.2026:** `KnowledgeBaseDeletionController`: `ADMIN`, `DELETE
  /api/knowledge-bases/{baseId}`; атомарное создание child intents и последующее выполнение
- **Главный пробел:** Нет independent approval, единой preflight-карты, полноценного tenant-offboarding

**Storage**
- **[ФАКТ] в коде на 24.11.2026:** `S3ObjectStorage.purgeAllVersionsVerified()` перечисляет версии и delete
  markers, проверяет отсутствие после удаления; `KnowledgeErasureTombstoneJournal` требует отдельный
  versioned Compliance Object Lock bucket
- **Главный пробел:** Требуются staging-доказательства на реальном провайдере/репликах/backup; флаги
  конфигурации не доказывают физическую готовность

**User**
- **[ФАКТ] в коде на 24.11.2026:** `POST /api/users/{id}/permanent-deletion` только SUPER_ADMIN; запрет
  self-delete, SUPER_ADMIN, platform tenant, required disabled / configurable 7d / no dependencies
- **Главный пробел:** Нет self-service intake, управляемой очистки связанных данных, независимого approval;
  событие аудита сохраняет email/ФИО удалённого

**Organization**
- **[ФАКТ] в коде на 24.11.2026:** `POST /api/organizations/{id}/disable|enable` только SUPER_ADMIN,
  authVersion и отзыв сессий
- **Главный пробел:** Нет реального удаления tenant, inventory/freeze/saga

**AI Model catalog**
- **[ФАКТ] в коде на 24.11.2026:** `/api/admin/models/catalog` read ADMIN/SUPER_ADMIN, запись новой версии
  SUPER_ADMIN; `ModelLifecycle` = ACTIVE/DEPRECATED/DISABLED/RETIRED; V45 запрещает UPDATE/DELETE каталога,
  политик, route decisions
- **Главный пробел:** Нет lifecycle-request процесса, проверки зависимостей, удаления secrets/self-hosted
  artifacts

**Auth**
- **[ФАКТ] в коде на 24.11.2026:** JWT/cookie, CSRF, `UserStatusFilter`, роли и `SecurityConfig`; `/api/**`
  по умолчанию denyAll
- **Главный пробел:** Новые endpoints требуют явного включения в security chain, отдельного step-up/MFA и
  прав per-case

**Audit/Usage**
- **[ФАКТ] в коде на 24.11.2026:** Event types, outbox, immutable financial/AI provenance, аудит действий
- **Главный пробел:** Нужна privacy-classification отдельных полей, законные retention windows и sanitized
  receipts

**Frontend**
- **[ФАКТ] в коде на 24.11.2026:** Retention, Chat Archive, Knowledge dialogs, администрирование
  Users/Organizations/Models, Audit
- **Главный пробел:** Нет «Мои запросы», «Очередь согласований», case/receipt UI, личного кабинета удаления


**Дополнительное [ФАКТ] об ACL:** `KnowledgeAccessService` интерпретирует `ROLE_ADMIN` как уровень `OWNER`
для базы внутри организации. Для USER доступ к удалению документа определяется членством уровня `OWNER`,
**не авторством загрузки**. Для новой заявки «удалить мой документ» нужен отдельный субъектный/владельческий
анализ, а не автоматическое расширение существующего OWNER ACL.

**Дополнительное [ФАКТ] о моделях:** триггеры V45 `trg_model_catalog_entries_immutable_v45`,
`trg_organization_model_policies_immutable_v45`, `trg_model_route_decisions_immutable_v45` не позволяют
прямое UPDATE/DELETE. Исторические route decisions ссылаются на версии каталога, политики, чат, организацию.
Нельзя строить план с немедленным удалением этих строк.

**Дополнительное [ФАКТ] об аудитe USER:** `UserCommandOperations.permanentlyDelete` пишет `targetUserEmail`
и `targetUserFullName` в `USER_PERMANENTLY_DELETED`. Это отдельный privacy blocker, поскольку стирание
строки `users` **не означает**, что ПД больше не хранятся.

---

## 2. Терминология: 8 разных действий

**`HIDE`**
- **Смысл:** Убрать из представления, данные остаются
- **Можно ли откатить:** Да
- **Выдаваемая гарантия:** Только скрытие

**`ARCHIVE`**
- **Смысл:** Скрыть активный чат без стирания содержимого
- **Можно ли откатить:** Да
- **Выдаваемая гарантия:** Архивирование

**`DISABLE`**
- **Смысл:** Запретить вход/использование аккаунта, модели, организации
- **Можно ли откатить:** Обычно да
- **Выдаваемая гарантия:** Блокировка новых действий, не завершение уже начатых внешних вызовов

**`DETACH`**
- **Смысл:** Отвязать пользователя от базы или модель от организации
- **Можно ли откатить:** Обычно да
- **Выдаваемая гарантия:** Удаление конкретной связи

**`REDACT_CONTENT`**
- **Смысл:** Удалить текст/заголовок/контент в поддерживаемом scope
- **Можно ли откатить:** Нет для очищенного контента
- **Выдаваемая гарантия:** Доказанное редактирование указанных хранилищ

**`PURGE_OBJECT_VERSIONS`**
- **Смысл:** Удалить все разрешённые версии конкретных S3-объектов
- **Можно ли откатить:** Нет
- **Выдаваемая гарантия:** Только перечисленные и проверенные версии

**`ANONYMIZE_SUBJECT`**
- **Смысл:** Обезличить/псевдонимизировать данные в разрешённых системах
- **Можно ли откатить:** Зависит от типа
- **Выдаваемая гарантия:** Подтверждённый перечень преобразований; псевдонимизация сама по себе не равно
  анонимизация

**`OFFBOARD_TENANT`**
- **Смысл:** Отключить организацию и вывести её данные по утверждённой матрице
- **Можно ли откатить:** Частично, до destructive barrier
- **Выдаваемая гарантия:** Доказательства по каждому дочернему ресурсу и исключения


Дополнительный тип `RETIRE_MODEL` означает новую версию каталога со статусом `RETIRED`/`DISABLED` и
отключение маршрутизации, **не** SQL DELETE исторических записей.

Разделяем: **заявка пользователя**, **действие администратора**, **административное поручение по
обращению**, **автоматическая обработка по утверждённому сроку**, **закрытие организации**. Автоматический
retention job не подменяет согласие или внешнее обращение. Для самостоятельных privacy-обращений допускается
регистрация дела даже при выключенном worker: неисполняемая заявка на рассмотрение не является новым
разрешённым destructive intent.

---

## 3. Матрица всех сценариев и полномочий

**Уровни:** `SELF` — только собственный объект; `TENANT` — собственная организация; `PLATFORM` —
платформенная операция. `APPROVER` — отдельное полномочие согласования, **не** автоматическое следствие роли
ADMIN. `OPERATOR` — техническая/эксплуатационная роль с ограниченным доступом, **не** пользовательское право
на выбор контента.

**Риск:** L = низкий, M = средний, H = высокий, C = критический. Все права в сценариях ниже — **[ПРОЕКТ]**,
если явно не отмечено обратное.

**1. Архивировать свой чат**
- **Риск:** `L`
- **Инициатор:** USER/ADMIN
- **Решение:** Автоматически по ownership
- **Результат:** `ARCHIVE`, без стирания

**2. Восстановить свой архивный чат**
- **Риск:** `L`
- **Инициатор:** USER/ADMIN
- **Решение:** Автоматически
- **Результат:** `RESTORE`, если стирание не начато

**3. Стереть текст **всего своего** чата**
- **Риск:** `M`
- **Инициатор:** USER/ADMIN
- **Решение:** SELF-проверка + step-up при политике; для высоких рисков approver
- **Результат:** Новый case, затем существующий Chat worker

**4. Удалить **одно своё сообщение****
- **Риск:** `M`
- **Инициатор:** Автор/владелец чата
- **Решение:** По ACL и контексту, не автоматический общий DELETE
- **Результат:** Отдельный scoped executor R3b; до него «не поддерживается»

**5. Удалить ответ AI в своём чате**
- **Риск:** `M`
- **Инициатор:** Владелец чата
- **Решение:** Self + проверка зависимостей
- **Результат:** Redact ответа, derived context и кэши; audit/usage по retention

**6. Удалить все свои чаты**
- **Риск:** `H`
- **Инициатор:** USER/ADMIN
- **Решение:** Поэлементный manifest и проверка hold
- **Результат:** Пакет из child cases; нет необратимого global cascade

**7. ADMIN удаляет **чужой** чат**
- **Риск:** `H`
- **Инициатор:** ADMIN организации
- **Решение:** Независимое решение + доказанное основание/право
- **Результат:** Нет немедленного bypass ownership; согласованный scope

**8. SUPER_ADMIN удаляет чат организации**
- **Риск:** `H`
- **Инициатор:** SUPER_ADMIN
- **Решение:** Независимый авторизованный approver, tenant/hold review
- **Результат:** Привилегия платформы сама по себе недостаточна

**9. Владелец базы знаний удаляет документ**
- **Риск:** `M`
- **Инициатор:** OWNER базы / tenant ADMIN [факт: текущая ACL]
- **Решение:** Self ACL или по risk policy
- **Результат:** Versioned Knowledge document deletion

**10. USER загрузил документ в общую базу, но не OWNER**
- **Риск:** `M`
- **Инициатор:** USER-заявитель
- **Решение:** OWNER/ADMIN организации + legal hold check
- **Результат:** Заявка, а не прямое расширение прав

**11. ADMIN удаляет чужой документ**
- **Риск:** `H`
- **Инициатор:** ADMIN организации
- **Решение:** При чужом личном/чувствительном содержимом независимый approver
- **Результат:** Необходимое основание, impacted-copy map

**12. Удалить одну **версию** документа**
- **Риск:** `H`
- **Инициатор:** OWNER/ADMIN-заявитель
- **Решение:** Специальная политика версий, удержания
- **Результат:** Только после отдельного version-scoped executor; текущий deletion удаляет документ

**13. Удалить **целую** базу знаний**
- **Риск:** `H`
- **Инициатор:** ADMIN организации
- **Решение:** Второй уполномоченный субъект по threshold
- **Результат:** Freeze + child-document jobs + проверка остатка

**14. Владелец базы просит удалить всю базу**
- **Риск:** `H`
- **Инициатор:** OWNER (может не быть ADMIN)
- **Решение:** ADMIN + независимость по риску
- **Результат:** OWNER может подать заявку, не выполнять обход current ADMIN endpoint

**15. Удалить членство в базе**
- **Риск:** `L`
- **Инициатор:** USER выходит, OWNER/ADMIN управляет
- **Решение:** ACL на membership
- **Результат:** Только связь, документы базы остаются

**16. Удалить свою учётную запись**
- **Риск:** `H`
- **Инициатор:** USER
- **Решение:** Проверенная личность + уполномоченное решение
- **Результат:** Disable, transfer/cleanup, grace period, anonymize/erase permitted data

**17. ADMIN удаляет свою учётную запись**
- **Риск:** `H`
- **Инициатор:** ADMIN
- **Решение:** SUPER_ADMIN + проверка другого активного ADMIN
- **Результат:** Нельзя автоматически удалить last ADMIN; transfer ownership

**18. ADMIN инициирует удаление USER**
- **Риск:** `H`
- **Инициатор:** ADMIN того же tenant
- **Решение:** SUPER_ADMIN или назначенная независимая функция согласно policy
- **Результат:** Только после основания и dependency scan; ADMIN не получает прямой SQL DELETE

**19. ADMIN инициирует удаление другого ADMIN**
- **Риск:** `C`
- **Инициатор:** ADMIN-заявитель
- **Решение:** SUPER_ADMIN + независимый второй подтверждающий
- **Результат:** Проверка назначения преемника, last admin, MFA

**20. SUPER_ADMIN инициирует удаление USER/ADMIN**
- **Риск:** `H/C`
- **Инициатор:** SUPER_ADMIN
- **Решение:** Другой допущенный субъект для C/H, без self-approval
- **Результат:** Нет bypass legal holds и tenant policy

**21. Удаление `SUPER_ADMIN`**
- **Риск:** `C`
- **Инициатор:** Только special governance
- **Решение:** Вне обычного REST workflow, два уполномоченных лица
- **Результат:** Текущий endpoint запрещает; сначала ротация корневой власти

**22. Отключить организацию**
- **Риск:** `H`
- **Инициатор:** SUPER_ADMIN [факт текущего кода]
- **Решение:** По действующему admin API
- **Результат:** Сессии отзываются, новые действия закрываются

**23. Организация просит закрыть свой tenant**
- **Риск:** `C`
- **Инициатор:** Уполномоченный ADMIN организации
- **Решение:** Два независимых ответственных платформы
- **Результат:** Intake + due diligence + staged offboarding

**24. SUPER_ADMIN закрывает чужую организацию**
- **Риск:** `C`
- **Инициатор:** SUPER_ADMIN
- **Решение:** Двойной контроль + подтверждённое основание
- **Результат:** Точно такой же контроль, что и при клиентском запросе

**25. Закрыть платформенную организацию**
- **Риск:** `C`
- **Инициатор:** Никто через обычный UI
- **Решение:** Специальная процедура вне API
- **Результат:** Запрет в обычном endpoint, не входит в обычный rollout

**26. Отключить модель для своей организации**
- **Риск:** `M`
- **Инициатор:** ADMIN организации
- **Решение:** Tenant model policy, текущие контракты контроля доступа
- **Результат:** Новая версия model policy; чужие организации не меняются

**27. Вывести **глобальную** модель из использования**
- **Риск:** `H`
- **Инициатор:** SUPER_ADMIN
- **Решение:** Независимый approver при active dependencies
- **Результат:** Новая версия со статусом RETIRED/DISABLED, migration routing

**28. Удалить версию глобального каталога**
- **Риск:** `C`
- **Инициатор:** SUPER_ADMIN-заявитель
- **Решение:** Не выполняем до особого review immutable evidence
- **Результат:** По умолчанию **не удаляется** из-за V45 и ссылок

**29. Удалить секрет ключа AI-провайдера**
- **Риск:** `H`
- **Инициатор:** Authorized platform operator
- **Решение:** Dual control для production keys
- **Результат:** Secret rotation/revocation, no plaintext export

**30. Удалить свои промпты/AI-ответы**
- **Риск:** `M`
- **Инициатор:** USER-владелец чата
- **Решение:** Через Chat/content scope
- **Результат:** Redact текст, не менять финансовые факты и immutable route evidence

**31. Запрос по письму/обращению в поддержку**
- **Риск:** `По объекту`
- **Инициатор:** Любой заявитель
- **Решение:** Верификация личности и полномочий; затем owner/approver
- **Результат:** Case связывается с обращением, без автоматического удаления по тексту

**32. Запрос через обычное сообщение в AI-чате**
- **Риск:** `H`
- **Инициатор:** USER/ADMIN
- **Решение:** **Не считается командой удаления**
- **Результат:** Assistant может направить на защищённую форму; защищённая заявка создаётся отдельно

**33. Автоматическое удаление по истечении срока**
- **Риск:** `H`
- **Инициатор:** Верифицированная retention policy + scheduler
- **Решение:** Policy approval + per-object legal guard
- **Результат:** Детерминированные scope jobs; не обходить удержания

**34. Экспорт/скачанный файл пользователя**
- **Риск:** `M`
- **Инициатор:** USER/ADMIN по правам
- **Решение:** Scope-specific
- **Результат:** Стереть наши серверные экспорты; скачанный файл на чужом устройстве не контролируем

**35. Запись аудита, usage или финансов**
- **Риск:** `C`
- **Инициатор:** Data subject / operator
- **Решение:** Privacy/legal policy review
- **Результат:** Удалить/обезличить только разрешённые поля; сохранять обязательную provenance

**36. Строки из backup/replica/AI provider**
- **Риск:** `C`
- **Инициатор:** Заявка на удаление объекта-родителя
- **Решение:** Операционный процесс + внешняя граница
- **Результат:** Tombstones, expiry policy, proof; не обещать мгновенного физического исчезновения


**Ограничение:** удаление личного профиля USER не должно автоматически удалять чаты сотрудников,
корпоративную базу знаний, её документы, историю использования общего биллинга или записи юридического
удержания. Автоматически удаляются только точно определённые персональные копии либо данные с надлежащим
основанием и согласием владельца/организации.

---

## 4. Авторизация, проверка личности и независимое согласование

### 4.1. Разделение субъектов

`requested_by` — кто подал; `subject_user_id` — чьи ПД; `resource_owner` — кто владеет контентом; `actor` —
кто совершает конкретный переход; `approver` — кто утверждает; `executor_identity` — технический worker;
`legal_reviewer` — кто принимает решение по удержанию; `operator` — кто восстанавливает застрявшую операцию.

**Нельзя:** `requested_by == approver` в H/C; общий владелец двух технических аккаунтов не даёт
независимости, `executor_identity` использовать как пользовательский токен, tenant из body выбирать без
серверной проверки, согласовать заявку своей отозванной ролью, обходить owner ACL через подмену
`subject_user_id`.

### 4.2. Аутентификация

- [ФАКТ] В проекте используются JWT/cookie, CSRF, `UserStatusFilter`, `tokenVersion` и
  `organizationAuthVersion`.
- [ПРОЕКТ] Для необратимых H/C действий: recent-auth step-up и поддерживаемый MFA. Если MFA ещё нет, до
  утверждения отдельной документированной эквивалентной процедуры с независимым контролем исполнение
  заблокировано. Повторный ввод email/пароля сам по себе не заменяет MFA.
- [ПРОЕКТ] Повторно проверять текущие роли и enabled состояние **в БД в момент каждого перехода**, включая
  approval, dispatch и перед destructive barrier.
- Смена роли, пароля, tenant status, model/knowledge ownership, resource version, legal hold или consent
  должна инвалидировать ранее выданное approval/preview, если влияет на область удаления.
- Для внешних заявителей использовать verified support case: проверка личности через существующие trusted
  каналы, протокол доказательств, TTL, оператор не может «подтвердить от имени субъекта» без оснований.

### 4.3. Политики согласования [ПРОЕКТ]

**L**
- **Кто может создать:** владелец своего обратимого объекта
- **Допуск к исполнению:** только ownership + tenant state

**M**
- **Кто может создать:** владелец/OWNER/ADMIN при достаточных правах
- **Допуск к исполнению:** step-up по policy, ресурсный preflight; без второго approver для низкоопасного
  удаления собственного контента, если legal policy разрешает

**H**
- **Кто может создать:** ADMIN/SUPER_ADMIN или подтверждённый субъект
- **Допуск к исполнению:** минимум одно независимое авторизованное решение, запрет self-approve, проверка
  hold и immutable scope

**C**
- **Кто может создать:** организация/администратор/глобальная модель/защищённые субъекты
- **Допуск к исполнению:** два независимых полномочных решения в разных идентичностях, step-up, обязательная
  задержка и impact preview; при конфликте интересов escalation


**Независимость** определяется не названием роли, а различием verified principals и правилом `requester !=
approver != independent_confirming_actor` для C. Повторное подтверждение под другим аккаунтом того же
пользователя не должно считаться независимым. Политика проверки юридической независимости двух физических
лиц должна быть закреплена организационно.

### 4.4. Обращения снаружи

- `source = UI|VERIFIED_SUPPORT|SIGNED_API|RETENTION_SCHEDULE` (закрытый enum). Email, мессенджер, тикет или
  AI-сообщение — **источник запроса**, не разрешение.
- Case содержит `external_case_ref`, верифицированного заявителя, время/метод проверки, legal basis и
  evidence ref; сырой текст письма, паспорт, промпт не копируем в audit/outbox.
- Подпись API-клиента не заменяет authority policy. Подтверждённое согласие на один объект нельзя расширить
  на организацию.
- Публичный intake для неавторизованного пользователя [ПРОЕКТ] допускается только через защищённый claim
  flow с rate limit и anti-enumeration; для начальной реализации предпочтителен проверенный support intake.

---

## 5. Карта данных, зависимостей и копий

### 5.1. Реальная база данных [ФАКТ из V1–V102]

Проверкой `CREATE TABLE` в предоставленном наборе миграций обнаружено **62 основных объявления таблиц**. Для
окончательного R0 это предварительный список, а не точный каталог действующей production-БД: ALTER, views,
функции, materialized data, дополнительные таблицы расширений и другие схемы надо извлечь из живого
`pg_catalog`.

**Auth/identity**
- **Таблицы/семейства для dependency scan:** `organizations`, `users`, `roles`, `user_roles`,
  `refresh_tokens`
- **Риск:** активные токены, временные сессии, role ownership

**Chats**
- **Таблицы/семейства для dependency scan:** `chat_sessions`, `chat_messages`, `chat_turns`,
  `chat_erasure_operations_v86`, `chat_retention_holds_v86`, `chat_erasure_operator_approvals_v86`
- **Риск:** текст сообщений, ответы AI, legal hold, финансовые связи

**Knowledge**
- **Таблицы/семейства для dependency scan:** `knowledge_bases`, `knowledge_base_memberships`,
  `knowledge_documents`, `knowledge_document_versions`, `knowledge_document_chunks`,
  `knowledge_ingestion_jobs`, `knowledge_index_generations`
- **Риск:** S3 objects, document versions, embeddings, RAG

**Knowledge derived**
- **Таблицы/семейства для dependency scan:** `knowledge_retrieval_runs`, `knowledge_retrieval_hits`,
  `knowledge_answer_passports`, `knowledge_answer_citations`, `knowledge_rag_context_receipts_v64`,
  evaluation cases/runs
- **Риск:** цитаты и извлечённый контекст, исторические ссылки

**Knowledge deletion**
- **Таблицы/семейства для dependency scan:** `knowledge_document_deletions_v78`,
  `knowledge_base_deletions_v82`, `knowledge_document_erasure_objects_v81`,
  `knowledge_erasure_commit_outbox_v89`, identity tables V80/V82, `knowledge_retention_recovery_reviews_v89`
- **Риск:** окончательность, tombstones, recovery

**Knowledge execution**
- **Таблицы/семейства для dependency scan:** `knowledge_rag_execution_permits_v85`,
  `knowledge_rag_execution_attempt_leases_v85`, `knowledge_rag_execution_permit_sources_v85`,
  `knowledge_storage_upload_intents`
- **Риск:** поздняя публикация, uploads, in-flight RAG

**AI/Models**
- **Таблицы/семейства для dependency scan:** `model_catalog_entries`, `organization_model_policies`,
  `model_route_decisions`, `model_execution_plans`, `model_execution_attempts`,
  `model_route_prepared_requests_v62`, `model_route_reserved_requests`,
  `model_route_budget_commitments_v64`, `model_physical_dispatch_receipts_v100`,
  `model_policy_preview_confirmations`
- **Риск:** immutable provenance, маршрут, расходы, provider dispatch

**Usage**
- **Таблицы/семейства для dependency scan:** `usage_daily_org_model_rollups`,
  `usage_daily_user_model_rollups`, `usage_daily_quality_rollups`, `usage_dirty_dates`, `usage_export_jobs`,
  `organization_ai_quotas`, `user_ai_quotas`, `usage_rollup_state`
- **Риск:** учёт, пользовательские идентификаторы, exports

**Audit**
- **Таблицы/семейства для dependency scan:** `audit_events`, `audit_event_types`, `audit_event_user_links`,
  `audit_outbox`
- **Риск:** ФИО/email в payload, неизменяемость и сроки

**Platform**
- **Таблицы/семейства для dependency scan:** `platform_erasure_policy_v102`, `chat_quota_calendar_v96`,
  `chat_quota_reservations`
- **Риск:** global gating, квоты/резервы


**R0 должен сохранить реальный граф FK**, включая `ON DELETE RESTRICT/CASCADE/SET NULL`, составные ключи и
виртуальные ссылки в JSONB. Текущий `hasPermanentDeletionDependencies()` проверяет лишь часть связей
(chat_sessions, usage user rollups, user quotas, knowledge memberships, audit_outbox); это не полная data
map и не достаточное условие для автоматического удаления любых пользовательских данных.

### 5.2. Вне PostgreSQL [ПРОВЕРИТЬ]

- S3 bucket документа: current + все `VersionId`, delete markers, preview, OCR, thumbnails, derived files и
  промежуточные файлы; реальный перечень ключей устанавливается из `storage_key` и upload intents.
- S3 WORM bucket журнала удаления: сохраняется отдельно, не должен содержать исходный документ или промпт.
- Redis: refresh/revocation, rate limiting, locks, caches, status, short-lived payload, очереди (если
  имеются).
- Внешняя AI-платформа: запросы, логирование/retention у провайдера, batches, cached prompts, external
  attachment APIs, доступные deletion receipts.
- Промежуточные файлы: OCR, embeddings, vector data, chunk content, индексы и снапшоты.
- Экспорты CSV/отчёты, staging/temp, фоновые задания/outbox, email/notification bodies, application logs,
  tracing/observability, backups/replicas/DR.
- Браузерные файлы на устройстве клиента, уже отправленные письма и чужие выгрузки: **внешняя граница**, их
  нельзя считать удалёнными платформой.

Для каждой копии записать `storage/system`, `physical_locator`, `tenant`, `subject`, `owner`, `purpose`,
`retention_class`, `legal_hold`, `erase_method`, `verification_method`, `restore_policy`,
`external_controller`, `active_use`, `risk`.

### 5.3. Режимы сохранения

`ERASABLE_NOW`, `RETAIN_UNTIL(date,basis)`, `LEGAL_HOLD(case)`, `NON_ERASABLE_PROVENANCE(limit)`,
`EXTERNAL_PENDING`, `UNKNOWN_REVIEW`.

`UNKNOWN_REVIEW` = **блокирующее**, а не «успешно». Ни ADMIN, ни SUPER_ADMIN не могут вручную преобразовать
`UNKNOWN_REVIEW` в удалено без проверяемого доказательства.

Для каждой копии требуется отдельный `obligation_id` со статусом: `PENDING`, `HELD`, `DELETING`,
`VERIFYING`, `VERIFIED_REMOVED`, `RETAINED_WITH_BASIS`, `EXTERNAL_PENDING`, `UNKNOWN_REVIEW` или `FAILED`.
Нельзя закрыть общий case, просто удалив его основную строку из PostgreSQL.

**Криптостирание** допускается как отдельный способ только при подтверждённых индивидуальных ключах,
отсутствии общих зависимых ключей и доказанном уничтожении всех доступных копий ключевого материала. Обычная
ротация общего KMS-ключа не равна удалению конкретного пользовательского файла.

---

## 6. Единый домен заявки [ПРОЕКТ]

### 6.1. Case — одна цель, один проверяемый scope

Сервер выдаёт UUID заявки; дочерние операции по зависимым ресурсам описываются в **замороженном manifest**
до approval. Batch пользовательских чатов = parent case + отдельные child cases, каждый со своей проверкой
hold. Нельзя незаметно расширить scope после подписи.

Обязательные поля:

- Identity: `id`, `organization_id`, `resource_type`, `resource_id`, `resource_owner_id`,
  `subject_user_id?`, `requested_by_user_id?`, `initiator_kind`, `source_channel`, `external_case_id?`.
- Meaning: `operation` (`ARCHIVE`/`REDACT`/`DELETE_OBJECT`/`DELETE_ACCOUNT`/`OFFBOARD_TENANT`/`RETIRE_MODEL`
  и др.), `scope`, `reason_code`, `legal_basis_code`, `retention_policy_id`, `notice_policy_id`.
- Concurrency: `resource_version`, `organization_auth_version`, `request_version`, `policy_version`,
  `manifest_sha256`, `idempotency_scope`, `idempotency_key`, `request_body_sha256`, `preview_expires_at`.
- Safety: `risk`, `requires_step_up`, `hold_snapshot_id`, `dependency_snapshot_id`, `approvals_required`,
  `cooling_until`, `destructive_started_at`, `failure_code`, `current_state`.
- Time: `created_at`, `updated_at`, `verified_at`, `approved_at`, `scheduled_at`, `completed_at?`,
  `due_at?`.
- Controlled records: events (append-only), approval decisions, impacted resources, hold references, outbox
  messages, child operations, external obligations, proof receipts, exception obligations.

Не хранить тела сообщений, документ, секреты, токены, SQL exceptions или полноценные письма в
`erasure_request`, audit/outbox, receipts, метриках и URL.

### 6.2. Состояния заявки

```text
DRAFT
  -> IDENTITY_VERIFICATION_REQUIRED -> IDENTITY_VERIFIED [если нужно]
  -> SUBMITTED
  -> PREFLIGHT_RUNNING -> PREFLIGHT_READY
  -> APPROVAL_REQUIRED -> APPROVED [либо AUTO_APPROVED по policy]
  -> COOLING_PERIOD [если требуется]
  -> QUEUED -> EXECUTING -> VERIFYING
  -> COMPLETED_ACTIVE_SCOPE | COMPLETED_FULL_SCOPE
  -> COMPLETED_WITH_EXCEPTIONS [если есть подтверждённые исключения]
```

Боковые состояния: `NEEDS_INFORMATION`, `REJECTED`, `WITHDRAWN` (до первого destructive barrier),
`BLOCKED_POLICY`, `BLOCKED_RETENTION`, `BLOCKED_DEPENDENCIES`, `BLOCKED_SECURITY`, `EXPIRED`,
`FAILED_RETRYABLE`, `NEEDS_REVIEW`, `PENDING_EXTERNAL`.

- `APPROVED` **не означает** начало исполнения.
- `EXECUTING` **не означает** стирание всех копий.
- `COMPLETED_ACTIVE_SCOPE` означает, что подтверждены перечисленные активные системы, но
  backup/external/retention может оставаться в отдельном открытом обязательстве.
- `COMPLETED_FULL_SCOPE` допустим только при `VERIFIED_REMOVED` для всех обязательств запрошенного scope
  **без исключений**. При обязательном удержании, недоступной внешней копии или ожидающем backup
  используется только `COMPLETED_WITH_EXCEPTIONS` с отдельным подтверждённым перечнем. Неизвестная копия
  оставляет case в `NEEDS_REVIEW`, а не в terminal state.
- `WITHDRAW` допустим до первого необратимого шага; после него можно запросить **приостановку оставшихся
  шагов** и проверку частичного результата. При этом уже удалённое не откатывается и case не помечается
  `WITHDRAWN`.
- Каждый подресурс имеет собственный `state`, `fencing_token`, `attempts`, `next_retry_at`, `proof_id`.

### 6.2a. Обязательные условия переходов [РЕШЕНИЕ]

- `POST /requests` создаёт только `DRAFT` с ограниченным TTL; никаких worker intents.
- `verify-identity` подтверждает заявителя, но не даёт права удалить иной объект.
- `submit` заново проверяет preview, actor, tenant, target, scope, version, holds
и создаёт `SUBMITTED` + событие/уведомление **в одной транзакции**. Это быстрый защитный precondition check,
а не финальный dependency scan.
- Последующий `PREFLIGHT_RUNNING` строит полный immutable digest зависимостей
и выявляет новые holds и копии перед одобрением.
- `approval` сверяет digest, risk, личность проверяющего и отсутствие конфликта.
- `queue` требует действующих approvals и отдельного execution release gate.
- Непосредственно перед первым внешним удалением worker проверяет тот же
manifest, resource versions, hold epoch, fence и конфигурацию, не доверяя UI.
- Изменение owner, версий, hold, policy, tenant status или approval authority
делает прежнее разрешение `STALE`; требуется повторная проверка и согласование.
- Долгая обработка внешнего провайдера не выполняется внутри SQL-транзакции.
- В состоянии `PENDING_EXTERNAL` обязательно сохраняются срок, владелец
обязательства и видимая граница невозможности проверки.

**Инвариант R1:** заявки можно регистрировать и рассматривать при выключенном исполнителе.
`ErasureAdmissionPolicy` V102 проверяется **при принятии нового необратимого intent**, а не используется для
отказа в приёме законного обращения субъекта данных. Для исполнения отдельно проверяются runtime feature
gate, состояние tenant, права, юридические удержания и approvals.

### 6.3. Закрытие доступа и freeze

`preflight` обязан определить все in-flight операции и время, когда безопасно закрыть доступ. Перед
irreversible execution — атомарно поставить **сервисный fence** в соответствующих таблицах/row locks,
отозвать необходимые credentials/leases и остановить новые записи. При конфликте с chat AI turn или
Knowledge RAG/ingestion вернуться в блокирующее состояние, **а не выполнить частичный DELETE**.

### 6.4. Состояние организации как собственная конечная автоматика

`ACTIVE -> OFFBOARDING_REQUESTED -> OFFBOARDING_APPROVED -> FROZEN -> PURGING -> RESIDUAL_REVIEW ->
TOMBSTONED`.

`OFFBOARDING_CANCELLED` только до destructive barrier; `FROZEN` запрещает новые uploads, пользователей, AI
dispatch, изменения моделей/политик, но сохраняет необходимые read-only operator/status/audit сервисы. При
отмене freeze before barrier требуется контролируемое восстановление auth epoch, а не простой enable без
review.

---

## 7. Проект БД и миграций V103+ [ПРОЕКТ]

**Не менять применённые V1–V105, их checksum или ACL.** Сверять Git HEAD, Flyway history
и reviewed manifest перед любой новой миграцией. Исходный план номеров V104–V110 от 24.11.2026
устарел и отменён. Фактические назначения подтверждены предоставленными SQL:

- **V103 — `erasure_case_intake`:** `erasure_requests_v103`, `erasure_request_events_v103`, previews;
  DRAFT / SUBMITTED / WITHDRAWN, version, append-only events.
- **V104 — `erasure_actor_detachment_guards`:** защита связанных идентичностей и detachment.
- **V105 — `erasure_review_intake`:** независимые REVIEW/LEGAL, `erasure_reviews_v105`,
  `erasure_review_events_v105`, `erasure_review_holds_v105`, защищённые переходы. Состояние
  `APPROVED_REVIEW_ONLY` не даёт право исполнения.
- **R2.3:** read-only проекции поверх V103/V105; новые таблицы, миграции и grants не требуются
  по проекту пакета. Проверить фактические runtime SELECT/ACL на PostgreSQL.
- **Будущие миграции (номера не резервировать):** identity proof, frozen manifest, execution steps,
  obligations, receipts, tombstones, retention decisions, user/tenant offboarding, model lifecycle.
  Выделять номер только после сверки максимальной миграции в актуальной ветке.

### 7.1. Доступ и индексы

- `UNIQUE(organization_id, requester_identity_id, idempotency_key)` + обязательная сверка
  `request_body_sha256`; одна активная destructive заявка на `(org, type, target, scope)` через partial
  unique index (определить нормализованный scope hash).
- `row_version bigint`, `CHECK(version>=0)`, `created_at` timezone UTC; все cross-tenant FK составные `(id,
  organization_id)` где это применимо. JSONB — только для bounded, классифицированных метаданных, не сырого
  контента.
- `audit append-only`: защитные triggers и отдельные DB roles; `safeai_app` **не получает DELETE** на
  protected data tables или разрешения снимать holds.
- Dedicated `safeai_erasure_intake` (или текущий app role с минимально нужными INSERT/SELECT) — только
  запись заявки; `safeai_erasure_scheduler` читает готовые шаги и не имеет прав произвольного удаления;
  Chat/Knowledge сохраняют собственные dedicated worker-credentials, а User/Tenant worker получают только
  узко ограниченные процедуры/права.
- Read access: через серверные tenant-queries с обязательным authorization check, в production желательно
  enforced RLS дополнительно; `operator` не видит содержимое объектов по умолчанию.
- Индексы `(organization_id, requested_by_user_id, created_at desc, id desc)`, `(organization_id, state,
  updated_at)`, `(state, next_attempt_at, id)`, `(parent_request_id, id)`, `(resource_type, resource_id,
  state)` с фильтром tenant, `(external_case_id)` с scoped unique.
- Новые event types регистрировать SQL-миграциями, enum `AuditEventType` и фронтенд-каталогом; отсутствие
  события = транзакционный отказ, не best-effort swallow.

### 7.2. Транзакции, idempotency, fencing

1. При создании/подаче **дела** в одной транзакции: persisted membership, authority, target/version, preview
   binding + case + event + outbox. Для **отдельного destructive intent** требуется следующая транзакция с
   V102 `ErasureAdmissionPolicy`, holds, approvals, fence и execution gate.
2. `Idempotency-Key` 12–128 безопасных символов, как в существующих Chat/Knowledge endpoints; точное
   совпадение payload возвращает старый case, другое содержимое = `409 IDEMPOTENCY_CONFLICT`.
3. Любая внешняя операция (S3/provider/mail) запускается **после commit**. Нельзя держать DB-транзакцию
   открытой на неопределённое время сети.
4. Claim step через `FOR UPDATE SKIP LOCKED`, lease и монотонный fencing token. Worker перепроверяет
   полномочия и hold до dispatch, а после внешнего результата ещё раз проверяет fencing и запись proof перед
   terminal state.
5. Повторный запуск без побочных эффектов; late worker лишён права terminal commit. Для долговременных
   ошибок — `NEEDS_REVIEW` и отдельное операторское действие с reason.
6. `withdraw` и новый legal hold сериализуются с началом первого destructive шага. Если hold появился после
   частичного стирания, оставшиеся шаги останавливаются; уже подтверждённый результат фиксируется без
   ложного rollback.

### 7.2a. Важные ограничения хранения идентичности [РЕШЕНИЕ]

- Само удаление `users` не должно каскадом уничтожать `erasure_requests`,
audit, биллинговые обязательства либо историю юридических решений.
- Для удалённых субъектов предусмотрен минимальный `subject_tombstone_id`
без исходного email/ФИО. Связь с личностью доступна только в отдельно защищённом реестре, если законно
необходима, с собственным retention/ACL.
- Для паролей, токенов подтверждения и secrets хранить только допустимые
защищённые представления; не включать их в `manifest_sha256` или логи.
- Для малых/угадываемых ПД нельзя использовать открытый SHA-256 как
«анонимизацию». Нужен keyed HMAC или криптографически защищённая ссылка, при этом и она остаётся
персональными данными, если допускает привязку.
- FK/unique ограничения для повторной регистрации email проверять вместе с
tombstone-политикой. Не сохранять email навсегда ради защиты от повтора.

### 7.3. Встроенная проверка миграций

Изменение V103+ требует одновременно: immutable new SQL + `.conf` при необходимости, хэш в
`reviewed-migrations.sha256`, `RuntimeAclPolicy.REVIEWED_THROUGH`, runtime-v90 ACL policy или новый явно
reviewed ACL, Flyway sequential test, fresh V1→latest, populated upgrade V85→latest, изолированный DB test
на PostgreSQL 16. **Никаких `flyway repair` для маскировки checksum**.

---

## 8. Целевые REST API-контракты [ПРОЕКТ — см. 8.0 о фактических маршрутах]

Предыдущий `openapi-erasure-DRAFT.yaml` является **непроверенным черновиком v1.0**. Перед R1 его необходимо
пересобрать по v1.1, особенно `DRAFT -> submit`, `202`, legal holds и scope. Не подключать старый файл к
генерации клиента или маршрутам. Нормативная целевая семантика описана ниже.

### 8.0. Реальный контракт R1a/R1b и R2.3 — отдельно от целевого дизайна

[ФАКТ ИЗ ИСХОДНИКОВ / ПРОВЕРИТЬ В СРЕДЕ] R1a реализует `POST /api/erasure/previews`,
`GET/POST /api/erasure/requests`, `GET /api/erasure/requests/{id}` и `POST .../{id}/submit|withdraw`.
`GET /api/erasure/requests` возвращает личные обращения (не глобальный реестр), до 100 записей.
R1b использует `GET /api/admin/erasure/reviews`, `/reviews/access`, `/reviews/overview`,
`/reviews/{id}`, `/reviews/{id}/events`; `POST /reviews/{id}/decision`, `/holds`, `/holds/release`
(все под `/api/admin/erasure`). Личные read/review routes: `/api/erasure/requests/{id}/review`,
`/review-events`, `POST /provide-information`. Проверять DTO и feature flags по контроллерам.
Целевые `/approve`, `/reject`, `/queue`, `/release-hold` не являются алиасами этих маршрутов.
Не генерировать runtime-клиент по целевому контракту. Step-up, receipt, execution, operator retry
и подтверждённое удаление остаются будущими функциями.

**Read-only workspace:**

- `GET /api/erasure/requests/capabilities` — актуальные boolean-возможности; не выдаёт права.
- `GET /api/admin/erasure/overview` — прежняя платформенная сводка V105; без approvals flag.
- `GET /api/admin/erasure/registry-summary` [R2.3 КАНДИДАТ] — scope PLATFORM/TENANT;
  total совпадает с registry ALL при неизменных данных. PLATFORM: SUBMITTED+WITHDRAWN, без
  черновиков, held = число дел с активным удержанием. TENANT: включая DRAFT, только свой tenant;
  reviewed/pending/needsInformation/held = null, а не 0. registered = intake SUBMITTED.
- `GET /api/admin/erasure/registry` — page 0..100000, 25 строк, state, query до 160 символов,
  hasNext; платформенная выдача без requester и resourceId. R2.3 добавляет intakeState,
  reviewState (nullable), reviewVisible; legacy state остаётся проекцией для фильтров.
- `GET /api/admin/erasure/registry/{requestId}` [R2.3 КАНДИДАТ] — тот же scope, отсутствие
  чужой/несуществующей заявки = 404, только метаданные; никаких мутаций. Tenant не читает
  V105 через этот маршрут. executionState=NOT_AVAILABLE, completionProven=false.
- `GET /api/admin/erasure/review-queue` — актуальный REVIEW/LEGAL и approvals flag, только
  организация проверяющего, 25 строк; не глобальная очередь, даже для SUPER_ADMIN.
- `GET /api/admin/erasure/history` — платформа либо независимый согласующий своего tenant.
  R2.3: V103+V105, source INTAKE/REVIEW, eventId, независимая version; зеркальные события
  регистрации отмечены разными источниками. Черновики, которые ещё не подавались, исключены;
  tenant reviewer видит лишь дела с записью V105. История V102 остаётся в общем аудите.

Offset-пагинация — текущая реализация; cursor из 8.1 остаётся целевым требованием.
Не выдавать сырые ошибки, PII платформе или обещание исполнения. Все новые GET требуют
аутентификацию, live role/tenant и серверный scope, а не только скрытие меню.

### 8.1. Общие заголовки и правила

- Session/JWT по существующему Auth; для browser cookie-мутаций используется `X-XSRF-TOKEN` (подтверждено
  `frontend/src/shared/api/http/httpConfig.ts` и `SecurityConfig.java`); новый scope явно разрешить в
  `SecurityConfig`, иначе `/api/**` denyAll.
- `Idempotency-Key` обязателен для `POST create` и `submit`, а также переотправки мутаций; ключ ограничен
  principal + tenant + endpoint и canonical payload digest. `If-Match: "N"` обязателен при мутации дела;
  версии ресурса перепроверяются при `submit` и `dispatch`.
- `X-Correlation-ID` от клиента считается недоверенным, проходит валидацию/ограничение длины; идентификатор
  трассировки генерирует сервер. `requestId` — серверный UUID; tenant берётся только из persisted membership
  / разрешённого контекста платформы.
- Content-Type `application/json`; hard payload size limit; normalized `reasonCode` enum; никаких raw
  documents или текста AI в запросе.
- Пагинация: серверный cursor (opaque, привязанный к tenant/filter), bounded `limit` (например 20–100),
  сортировка `createdAt DESC, id DESC`; нет глобального листинга для USER.
- Anti-enumeration: чужой или несуществующий объект → одинаковый `404`, а не подробности доступа. Внутренний
  reason в sanitized audit.

### 8.2. Read/capability и preview

**`GET` `/api/erasure/capabilities?resourceType=...&resourceId=...`**
- **Права:** SELF/TENANT scoped
- **Назначение:** Можно ли создать заявку, reason codes, **без** исполнения

**`POST` `/api/erasure/previews`**
- **Права:** SELF/TENANT scoped
- **Назначение:** Preflight: список детей, holds, риски, сроки, `previewId`, digest

**`GET` `/api/erasure/requests`**
- **Права:** USER свои; ADMIN tenant; SUPER_ADMIN по явному выбору tenant
- **Назначение:** Страница заявок с фильтрами

**`GET` `/api/erasure/requests/{requestId}`**
- **Права:** Scope + verified requestor/subject/approver
- **Назначение:** Текущее состояние, steps, решения, sanitized history

**`GET` `/api/erasure/requests/{requestId}/receipt`**
- **Права:** Requestor/authorized reviewer
- **Назначение:** Объём доказанного удаления, исключения, digest/подпись

**`GET` `/api/admin/erasure/queue`**
- **Права:** Appointed approver по tenant/platform
- **Назначение:** Очередь решений без лишних PII

**`GET` `/api/admin/erasure/requests/{id}/impact`**
- **Права:** Authorized approver
- **Назначение:** Зависимости и scope для решения

**`GET` `/api/admin/erasure/operations`**
- **Права:** Authorized operator
- **Назначение:** Обезличенная операционная очередь и причины сбоев


`/capabilities` и `/previews` информируют, но **не разрешают** удаление. Preview связывается с actor,
tenant, target, operation, scope, resource version, policy/hold epoch, expiry и digest. `submit` заново
проверяет все эти данные; изменённый preview требует нового подтверждения, а не молчаливой правки scope.

### 8.3. Изменение заявки и approvals

**`POST` `/api/erasure/requests`**
- **Правило:** Создаёт `DRAFT` + preview binding + idempotency; без submit/worker

**`POST` `/api/erasure/requests/{id}/verify-identity`**
- **Правило:** Подтверждение actor через one-time proof, step-up; role-independent

**`POST` `/api/erasure/requests/{id}/submit`**
- **Правило:** Подтверждённая личность (если требуется), live preflight, `SUBMITTED`, без стирания

**`POST` `/api/erasure/requests/{id}/withdraw`**
- **Правило:** Только requestor до destructive barrier

**`POST` `/api/admin/erasure/requests/{id}/approve`**
- **Правило:** Approver независим; If-Match; обновлённая manifest digest

**`POST` `/api/admin/erasure/requests/{id}/reject`**
- **Правило:** Safe reason enum, без произвольных персональных данных

**`POST` `/api/admin/erasure/requests/{id}/request-information`**
- **Правило:** Need-info с TTL, уведомление заявителю

**`POST` `/api/admin/erasure/requests/{id}/hold`**
- **Правило:** Специальная legal role; отдельный review и evidence

**`POST` `/api/admin/erasure/requests/{id}/release-hold`**
- **Правило:** Нельзя обычному ADMIN; юридическое разрешение + независимый контроль

**`POST` `/api/operator/erasure/steps/{stepId}/retry`**
- **Правило:** Только оператор, fencing + expected version + runbook reason

**`POST` `/api/operator/erasure/steps/{stepId}/reconcile`**
- **Правило:** Только оператор, проверка внешней фактической операции; без ручной отметки completed


Существующие `/api/chats/...` и `/api/knowledge-bases/...` не могут оставаться обходом case-flow. При
rollout каждого типа нужен **общий backend admission gate** для старого и нового маршрута; он проверяет
approved case, signed scope, роли, tenant, hold и runtime policy. Legacy-вызов без нужной авторизации
отказан, а worker нельзя вызвать напрямую от пользовательского контроллера. Тестировать обход через старый
URL до включения нового UI.

Рекомендуемая последовательность: shadow audit -> enforcement `OFF` -> совместимость клиента и флагов ->
тесты отказа -> enforcement `ON` -> удаление/закрытие legacy-прямого пути. Нельзя включить executor при
наличии двух разных источников полномочий на удаление.

### 8.4. Пример запроса: удалить собственный чат

```http
POST /api/erasure/requests
Content-Type: application/json
Idempotency-Key: usrchat-a1c0ce4f29c3
X-XSRF-TOKEN: <csrf-from-auth>
```

```json
{
  "operation": "REDACT_CONTENT",
  "resourceType": "CHAT",
  "resourceId": "9c783cec-68b2-4e45-ae13-855c184fcace",
  "scope": "FULL_CHAT_CONTENT",
  "expectedResourceVersion": 7,
  "previewId": "583b45d3-3992-4cef-8857-e1df315b92ed",
  "previewDigest": "<sha256-hex-from-server>",
  "reasonCode": "OWNER_REQUEST"
}
```

Значения UUID выше — вымышленные примеры. Organization, requester, subject берутся из authenticated server
context. Ответ:

```json
{
  "requestId": "a6577ddd-ac02-46f6-921d-b61064d9422e",
  "state": "DRAFT",
  "operation": "REDACT_CONTENT",
  "resourceType": "CHAT",
  "scope": "FULL_CHAT_CONTENT",
  "version": 0,
  "reviewRequired": null,
  "executionStarted": false,
  "completionProven": false,
  "nextAction": "SUBMIT",
  "links": {"self": "/api/erasure/requests/a6577ddd-ac02-46f6-921d-b61064d9422e"}
}
```

`HTTP 201` означает только создание `DRAFT`; `POST /submit` после всех условий возвращает `202` с
`state=SUBMITTED`, если заявка **надёжно зафиксирована на рассмотрение**. Это не запуск стирания. Отдельное
`QUEUED` выставляется диспетчером только после проверки execution gate. Ни один HTTP-ответ не означает
«контент уже стёрт».

### 8.5. Пример preflight: удалить аккаунт USER

```http
POST /api/erasure/previews
```

```json
{
  "operation": "DELETE_ACCOUNT",
  "resourceType": "USER_ACCOUNT",
  "resourceId": "9d8f3b95-8f2a-4bb0-bcc0-2e4fd3c8d509",
  "scope": "PERSONAL_DATA_AND_ACCESS"
}
```

Ответ включает **не содержимое чужих документов**, а категории:

```json
{
  "previewId": "fa02aa1f-af3a-4310-b48e-662532cc6d6a",
  "summary": {
    "ownedChats": 12,
    "sharedKnowledgeMemberships": 2,
    "ownedKnowledgeBases": 1,
    "transferRequired": true,
    "activeSessions": 3,
    "holdsCount": 0,
    "usageEvidenceRetained": true
  },
  "allowedActions": ["DISABLE_ACCOUNT", "REVOKE_SESSIONS", "TRANSFER_OWNERSHIP"],
  "blockedActions": ["DELETE_ACCOUNT"],
  "blockedReasons": ["DEPENDENCIES_REQUIRE_RESOLUTION"],
  "previewDigest": "<sha256-hex-from-server>",
  "expiresAt": "2026-11-24T17:15:00Z"
}
```

Данные и время в примере условные. В production не возвращать подробности личного контента других
пользователей, даже если граф зависимостей их выявил.

### 8.6. Пример решения администратора

```http
POST /api/admin/erasure/requests/{requestId}/approve
If-Match: "3"
```

```json
{
  "decision": "APPROVE",
  "manifestDigest": "<approved-digest>",
  "reasonCode": "VERIFIED_OWNER_REQUEST",
  "stepUpProofId": "<ephemeral-proof-id>"
}
```

Сервер отдельно проверяет текущие роли approver, владелец/tenant, self-approval, актуальную версию объекта,
legal holds, флаги и истечение TTL. `APPROVED` лишь открывает следующий этап, не выполняет удаление прямо в
HTTP request.

### 8.7. Стандартизированные ошибки

```json
{
  "code": "RETENTION_HOLD",
  "message": "Удаление временно недоступно из-за обязательного срока хранения.",
  "requestId": "<correlation-id>",
  "retryable": false
}
```

**400 `INVALID_SCOPE`, `INVALID_CONFIRMATION`**
- **Смысл:** Неоднозначная или некорректная операция

**401 `AUTH_REQUIRED`, `STEP_UP_REQUIRED`**
- **Смысл:** Необходимо повторное подтверждение личности

**403 `NOT_AUTHORIZED`, `INDEPENDENT_APPROVER_REQUIRED`**
- **Смысл:** Недостаточно прав или конфликт ролей

**404 `RESOURCE_NOT_FOUND`**
- **Смысл:** Чужой и несуществующий ID не различаются

**409 `RESOURCE_VERSION_CHANGED`, `IDEMPOTENCY_CONFLICT`, `APPROVAL_STALE`, `EXECUTION_ALREADY_STARTED`**
- **Смысл:** Конкурентное изменение/повтор

**409 `RETENTION_HOLD`, `ACTIVE_AI_OPERATION`, `DEPENDENCIES_REQUIRE_RESOLUTION`, `POLICY_PAUSED`**
- **Смысл:** Детерминированный запрет

**412 `PRECONDITION_FAILED`**
- **Смысл:** If-Match устарел (если выбран единый HTTP ETag mapping)

**422 `UNSUPPORTED_OPERATION`, `PREVIEW_REQUIRED`**
- **Смысл:** Текущий тип нельзя выполнить или нет проверенного scope

**429 `RATE_LIMITED`**
- **Смысл:** Отсечение злоупотреблений

**503 `EXECUTOR_UNAVAILABLE`, `STORAGE_UNVERIFIED`**
- **Смысл:** Техническое ограничение, заявка не считается удалённой


Финальный выбор `409` или `412` для resource version зафиксировать единообразно в OpenAPI и тестах; нельзя
возвращать разные коды за одинаковый конфликт в разных ресурсах. Не выдавать SQL, имя bucket, чужой email,
чужие role details.

---

## 9. Личные кабинеты, страницы и UX [ПРОЕКТ]

### 9.0. R2.3 — единая фактическая навигация (кандидат)

- SUPER_ADMIN: `/admin/erasure` (обзор), `/admin/erasure/registry` (заявки),
  `/admin/erasure/registry/:requestId` (read-only карточка), `/admin/retention`
  («Политика хранения»), `/admin/erasure/history` («История изменений»).
- ADMIN: `/admin/erasure` (tenant overview), `/admin/erasure/intake` (реестр своего tenant),
  та же read-only карточка; без раскрытия V105 через intake. Показ при server capability.
- Оба: `/admin/erasure/requests` и `/admin/erasure/requests/:requestId` — отдельная очередь
  и карточка рассмотрения только при независимых REVIEW/LEGAL; проверка каждого действия на сервере.
- USER/ADMIN: `/settings/privacy`, `/my/erasure-requests`, `/my/erasure-requests/:requestId` —
  только личные обращения при подтверждённом доступе. SUPER_ADMIN — особый порядок.
- Два уровня заголовков: модуль «Управление данными» и имя выбранного раздела.
  История политики ведёт в `/admin/audit?eventType=RETENTION_POLICY_CHANGED`; возврат через
  allowlist внутренних маршрутов, URL-фильтры и best-effort window scroll; при прямом входе
  безопасный fallback. Не добавлять второе хранилище аудита.
- V102 управляет допуском отдельных legacy destructive intents, не приёмом личных обращений.
  Готовность, допуск, intake, review, execution и доказательство — разные состояния.
- Не показывать неподдерживаемые кнопки, сырые R1/R3/enum в основном интерфейсе, фиктивные
  числа или страницы при пустом первом списке. Не выдавать глобальные метрики ADMIN.

Разделы 9.1–9.5 ниже — дальнейший целевой UX; не считать все указанные функции реализованными.

### 9.1. USER: «Мои данные» и «Мои запросы»

Предлагаемые frontend routes:

- `/settings/privacy` — персональная страница: «Мои данные», какие сведения хранятся и сроки, экспорт
  (отдельный безопасный scope), «Запросить удаление аккаунта».
- `/my/erasure-requests` — список своих заявок: объект, дата, понятный статус, сколько осталось до
  решения/удаления, как обратиться в поддержку.
- `/my/erasure-requests/:requestId` — доказанные этапы, безопасные причины блокировки, подтверждение
  личности, отмена **до** необратимой стадии, receipt без исходных ПД.
- `/chat` — в меню конкретного чата «Архивировать» и (если feature gate) «Запросить стирание содержимого».
  Для одного сообщения отдельная функция появится только после R3b.
- `/knowledge/:knowledgeBaseId` — у документа доступное действие с подсказкой: «Удалить сейчас», «Отправить
  заявку владельцу базы» либо «Недоступно» в зависимости от серверной ACL.

Кнопка всегда имеет **meaningful capability**: если выключено, сообщаем конкретный безопасный reason, не
даём фиктивный успешный toast. Подтверждение показывает название, тип, affected scope, последствия,
сохранённые записи, срок и возможность отмены.

### 9.2. ADMIN: «Запросы организации»

- `/admin/erasure/requests`: только tenant, фильтры `Мои/Требуют решения/В работе/Заблокированы/Завершены`,
  заявитель, тип, объект, безопасный reason, срока и ответственный; полный SQL не показывать.
- `/admin/erasure/requests/:requestId`: impact preview, authority, owner, текущее одобрение/конфликт
  интересов, child resources, исключения; кнопки Approve/Reject только при назначенных правах.
- `/admin/users/:id`: «Отключить» и «Запросить удаление» **разделены**; ADMIN не может напрямую удалить
  другого ADMIN.
- `/knowledge/:id` и контекстное меню документа: три разных действия «Отвязать участника», «Удалить
  документ», «Запросить удаление базы».
- `/admin/models?section=access`: «Снять доступ организации к модели», но не удалять глобальный каталог.

### 9.3. SUPER_ADMIN: «Центр удаления данных»

- `/admin/erasure`: обзор по всем организациям, текущие admission policies, фактическая **проверенная**
  готовность исполнителей, backlog, blocked/review, нарушения SLA, outbox/reconcile, ссылки на runbooks. По
  умолчанию показывать агрегаты без ПД.
- `/admin/erasure/requests`: tenant selector, typed filters, queue approvals, двойной контроль и
  независимость.
- `/admin/erasure/organizations/:id`: offboarding wizard: impact → freeze → approvals → waiting period →
  execution → verify → receipt. Платформенная организация всегда защищена.
- `/admin/erasure/models/:modelKey`: lifecycle, pinned versions/active policies/financial refs, план замены
  defaults, retiring new version.
- `/admin/retention`: **оставить текущую страницу глобальной политики**, две сводные карточки и статусы. Не
  превращать её в окно ручного удаления; добавить ссылку на центр заявок.

### 9.4. Понятные статусы для людей

**`SUBMITTED`**
- **USER:** «Запрос получен»
- **ADMIN / operator:** Case ID, источник, owner

**`IDENTITY_VERIFICATION_REQUIRED`**
- **USER:** «Нужно подтвердить личность»
- **ADMIN / operator:** Verification method/TTL без секрета

**`APPROVAL_REQUIRED`**
- **USER:** «Ожидает решения»
- **ADMIN / operator:** approver queue, deadline, конфликт интересов

**`COOLING_PERIOD`**
- **USER:** «До начала удаления осталось …»
- **ADMIN / operator:** destructive barrier not entered

**`EXECUTING`**
- **USER:** «Удаление выполняется»
- **ADMIN / operator:** шаги по категориям, без содержимого

**`BLOCKED_RETENTION`**
- **USER:** «Некоторые данные должны храниться до установленной даты»
- **ADMIN / operator:** hold reason, release authority, deadline

**`NEEDS_REVIEW`**
- **USER:** «Требуется проверка специалиста»
- **ADMIN / operator:** code, retry/lease, evidence, runbook

**`COMPLETED_ACTIVE_SCOPE`**
- **USER:** «Удаление в активных системах подтверждено»
- **ADMIN / operator:** перечисленные источники, pending external/backup отдельно

**`COMPLETED_WITH_EXCEPTIONS`**
- **USER:** «Часть данных сохранена по обязательным правилам»
- **ADMIN / operator:** точный список удержанных категорий и оснований


Никогда не писать «удалено навсегда» там, где доказано лишь отключение, редактирование отдельных колонок или
отсутствие видимого объекта.

### 9.5. Уведомления и обратная связь

- In-app inbox первым релизом; email только через verified address и transactional outbox; SMS/messenger
  лишь через выбранный approved channel. Письма не содержат имени файла/текста промпта/PII в заголовке.
- События: `REQUEST_RECEIVED`, `IDENTITY_ACTION_REQUIRED`, `ADDITIONAL_INFO_REQUIRED`, `APPROVAL_DECIDED`,
  `EXECUTION_STARTED`, `HOLD_APPLIED`, `NEEDS_REVIEW`, `COMPLETED_*`.
- Пользователь видит идентификатор case, ссылку, дату, действия; ADMIN видит tenant SLA; SUPER_ADMIN видит
  redacted aggregate.
- Сменивший роль/организацию пользователь теряет доступ к чужим case; инициатор self-account удаления после
  блокировки аккаунта получает безопасный подтверждённый канал просмотра статуса без восстановления
  полномочий.
- A11y: focus trap в подтверждении, screenreader status, WCAG-ориентированные сообщения, адаптация к
  390/768/1280/1440; action не только по цвету.

---

## 10. Пообъектная оркестрация (исполнение)

### 10.0. Жёсткий pre-dispatch contract для ВСЕХ executors

Worker получает не произвольный `resourceId`, а проверенный `step_id`. Он самостоятельно загружает из БД
approved manifest и сверяет:

1. Tenant/org state, актуальные права, approver independence, risk class.
2. Неистёкший consent/authority proof, approved digest и version/hold epoch.
3. Состояние parent/child case и отсутствие более новой destructive заявки.
4. Наличие нужного DB credential, release gate, lease и fencing token.
5. Отсутствие активных читателей/писателей по типу ресурса.
6. Точный список data copies и legal bases, а не маску `DELETE *`.
7. Неизменность границы удаления относительно согласованного preview.

После каждого внешнего шага — persist receipt, digest и verification state. При неоднозначном ответе сети
операция остаётся `NEEDS_REVIEW`, не `COMPLETED`. Оператор не получает кнопки «завершить без доказательств».

### 10.1. Чат целиком — R3a

1. Авторизованный владелец запрашивает `FULL_CHAT_CONTENT`; сервер проверяет tenant, состояние USER/ADMIN и
   owner, archived status и resource version.
2. Preflight: active Chat Turn, provider dispatch, RAG leases, retention holds, chat size, idempotency,
   vendor boundary.
3. В текущем коде [ФАКТ] `ChatLifecycleService` допускает content erasure **только ADMIN для собственного
   архивного чата**; расширение до USER — **новый контракт**. Не менять `ChatErasureWorker` ради обхода
   авторизации.
4. Freeze / accepted case; существующий V86 worker redacts `chat_messages.content`,
   `knowledge_retrieval_runs.query_text`, `chat_sessions.title`; `chat_erasure_operations_v86` завершается
   state `ERASED`.
5. Проверить scope PostgreSQL и производные поля. `route decisions`, usage, обязательные audit remain;
   провайдерские копии проверяются отдельно.
6. Receipt содержит `content_redacted=true`, `remaining_evidence_categories` и `external_proof_status`, не
   «вся сессия уничтожена».
7. Нельзя физически удалять `chat_sessions`, если на них ссылаются route decisions или финансовые записи.

### 10.2. Отдельное сообщение/ответ AI — R3b, **после R3a**

- Сначала исследовать ссылки/контекст: порядок messages, regeneration, Chat Turn, route/usage/cost, RAG
  receipts, citations, message attachments, streaming/pending output.
- Нельзя удалять одно сообщение в течение незавершённого AI turn. Удаление исходного пользовательского
  сообщения не означает, что AI не использовал его уже во внешнем запросе.
- Вводим `CHAT_MESSAGE_CONTENT` scope с проверкой `(chatId, messageId, tenant, owner, version)` и своей
  redaction receipt, sanitized placeholder, dependent context manifest. UI показывает «содержание
  скрыто/стёрто в подтверждённых хранилищах».
- Для shared/quoted messages: собственный автор не всегда имеет право удалить чужие обязательные записи;
  review case.
- Не реализовывать UI удаления отдельного сообщения до end-to-end executor и полноценной индексации всех
  копий.

### 10.3. Документ — R4

- `knowledge_base_id`, `document_id`, `version`, OWNER membership под DB lock; если пользователь лишь
  uploader, но не OWNER, создаётся case на рассмотрение.
- Freeze uploads/rename/publication и RAG permits, reject late jobs; Knowledge V78/V80/V81/V85/V89 executor
  сохраняет свои lease/fencing механизмы.
- Все live+historical S3 object versions и delete markers по точному `key`, список object identity,
  chunks/embeddings/derived versions, previews, OCR, ingestion, citations/answer provenance по approved
  policy.
- S3 Object Lock: при действующем Compliance/legal hold не удалять и не пытаться обходить, сохранить
  `BLOCKED_RETENTION` с доказанным version-specific reason. При сетевой неоднозначности `NEEDS_REVIEW`, не
  `DELETED`.
- WORM journal `KnowledgeErasureTombstoneJournal` содержит intent/purge/commit receipt, не исходные байты и
  не открытые PII.
- Проверка: свежий list versions + HEAD по точному ключу, orphan scan, БД state и barrier; test double
  недостаточен, нужен real provider staging.

### 10.4. База знаний — R5

- Перед согласием показать список дочерних документов (количество и размер), owners, members, active
  searches/uploads, holds, связанные цитаты; approve manifest по hash/version.
- Tenant ADMIN сегодня может принять base deletion напрямую; новый высокорисковый case требует independent
  approval согласно risk policy. OWNER без ADMIN — право **попросить**, не обход действующего endpoint.
- Freeze base, children в durable queue в одной транзакции с parent; wait for all child terminal evidence;
  обработать orphan storage/derived data.
- Удалять parent row только после child convergence и проверки всех ссылок. Если хоть один child удержан или
  не проверен, parent не `COMPLETED`.
- Member accounts, AI usage и чужие чаты не удаляются вместе с базой.

### 10.5. Пользователь USER — R6a

1. Создать self-request или проверенный ADMIN request, reason, identity verification.
2. Preflight по всем ресурсам, в том числе share ownership, роль, active AI turn, login tokens, knowledge
   memberships, chats, usage, audit/outbox, provider liabilities.
3. Отключить доступ (в нужной точке workflow), повысить `tokenVersion`, отозвать sessions/refresh tokens; не
   терять возможность проверенного просмотра статуса.
4. Передать организациям общие базы/документы и активные задачи, не уничтожать корпоративные сведения
   автоматически.
5. По approved scopes вызвать Chat/Knowledge child workflows, решить retention/financial exceptions.
6. Обезличить (если требуется законом и возможно) `users`, identity cache, report aliases, audit/outbox PII;
   **первым блокером** исправить `USER_PERMANENTLY_DELETED` snapshot с email/ФИО.
7. Соблюдать настроенный период (`SAFEAI_USER_DELETION_RETENTION`, сейчас default `7d`, **это не
   универсальный законный срок**), целостность FK, last-admin controls, no active refresh, stale version.
8. Финальная операция `users DELETE` возможна лишь после проверенного closure и FK-safe transaction; если
   protected evidence требует стабильного субъекта, использовать policy-defined tombstone/pseudonymization
   вместо немедленного delete.

### 10.6. Администратор ADMIN — R6b

Все шаги USER плюс проверка: не последний активный ADMIN, кто станет владельцем баз, чатов, model policies и
решений, кто подтвердит, конфликты интересов и незавершённые approvals. ADMIN не имеет права согласовать
**собственный** offboarding, удаление другого ADMIN требует независимого SUPER_ADMIN/approver. Нельзя
удалять пользователя, пока он единственный подписант незавершённых юридических/финансовых процедур.

### 10.7. SUPER_ADMIN и системные учётные записи — special governance

[ФАКТ] текущий обычный endpoint запрещает SUPER_ADMIN и platform tenant. В новых пользовательских маршрутах
сохранить запрет. Удаление такого аккаунта — отдельная административная операция **после** назначения
другого корневого субъекта, проверки break-glass, ключей и capability, двух независимых одобрений и recovery
plan. На первом релизе **не делать** обычную кнопку «Удалить SUPER_ADMIN».

### 10.8. Модели, их версии, политики и ключи — R7

**Четыре независимые цели:** (a) снять доступ организации; (b) деактивировать модель в runtime; (c) вывести
глобальную catalog model в `RETIRED`; (d) отозвать/удалить секреты и self-hosted weights/artifacts. «Удалить
модель» в интерфейсе должно требовать выбрать точный тип.

- Для (a): новая версия tenant model policy, проверки fallback/defaults, уже принятых plans/physical
  attempts. Immutable history сохраняется.
- Для (b): disable configuration, drain/finish in-flight provider requests и контроль расходов; не обещать
  остановку удалённого запроса.
- Для (c): `SUPER_ADMIN` создаёт **новую** версию `ModelCatalogEntry` с lifecycle `RETIRED`/`DISABLED`; V45
  запрещает UPDATE/DELETE старых версий и route decisions. Исключение из каталога лишь после специального
  privacy/legal review + отдельной миграции, не по обычной кнопке.
- Для (d): rotation/revoke provider key, проверка всех consumers, secure secret store и job cache; если
  self-hosted weights принадлежат платформе — отдельный storage erasure plan. Внешняя модель
  OpenAI/Anthropic/etc не находится на нашей дисковой системе, стирание её параметров с нашей стороны
  невозможно.
- Исторические расходы, ledger, price versions, route decisions, warnings об использовании данных
  провайдером сохранять в допустимом evidence scope.

### 10.9. Организация — R8

1. **Intake:** ADMIN организации/уполномоченный представитель подаёт запрос, SUPER_ADMIN может инициировать
   по обоснованной процедуре; проверяется юридическая и договорная authority, а не только роль в UI.
2. **Impact preview:** пользователи, ADMINs, чаты, базы/документы и S3-версии, model bindings, bills/usage,
   audit, export jobs, provider attempts, незакрытые holds, backup obligations, внешние integrations.
3. **Два независимых решения:** verified tenant representative (если применимо) + независимые представители
   платформы; cooling-off и возможность отозвать до barrier. Указать уведомления и ответственность.
4. **Freeze:** запрет новых логинов, uploads, chats, model dispatch, новых пользователей, API integrations;
   authVersion/security epoch и отзыв sessions. In-flight внешние AI вызовы дождаться/изолировать в
   `NEEDS_REVIEW`, не считать отменёнными.
5. **Child saga:** R3 chats/messages → R4 documents → R5 bases → R6 users/admins → R7 organization model
   bindings → exports/caches/derived. Порядок строго корректировать по реальному FK графу и удержаниям, не
   hardcode наугад.
6. **Проверка:** при каждом child checkpoint журнал/receipt, контроль других tenant; S3 final list, SQL
   reference sweep, multi-replica status, Redis, provider commitments.
7. **Retained evidence:** финансовые/аудиторские записи сохраняются в согласованной минимальной форме с
   retention basis; tenant tombstone/обезличенная identity удерживает безопасные FK.
8. **Завершение:** `TOMBSTONED` не означает немедленное исчезновение всех backup. Receipt указывает `active
   data removed`, `retained legal`, `external pending`, `backup expiry`; `COMPLETED_FULL_SCOPE` только по
   закрытым obligations.
9. **Защита:** `platform.organizationId()` никогда не удаляется обычным workflow; запрет cross-tenant, no
   delete of shared provider/global catalog.

### 10.10. Аудит, биллинг, AI logs, экспорт и резервные копии

- Личные данные в `audit_event`/`audit_outbox` классифицировать и отделить от обязательных доказательств.
  Новые события без email/ФИО; существующие неизменяемые записи не переписывать без юридически и технически
  согласованной процедуры, при необходимости использовать защищённое отделение lookup-персоналий и
  ограничение просмотра.
- `usage_rollups`, `model_route_decisions`, provider attempts, receipts: сохранять необходимые агрегаты,
  суммы и provenance, но по возможности разорвать не требуемую законодательно прямую идентификацию.
- На восстановлении из backup **до пользовательского HTTP/AI доступа** reapply verified erasure tombstones,
  revoke epochs, reconcile deleted objects; затем скан на resurrection. Backup retention определяется
  утверждённой политикой, не UI.
- Redis/cache/index/export/analytics хранилища имеют собственные deletion obligations. Не маркировать
  «успешно» по одной таблице.

---

## 11. Инфраструктура и независимое исполнение

### 11.1. Компоненты [ПРОЕКТ]

```text
Frontend USER / ADMIN / SUPER_ADMIN
   -> Existing Auth/Security (JWT/cookie/CSRF/role/tenant)
   -> Erasure API (case intake, preview, approvals, read)
   -> PostgreSQL (append-only case, requests, events, outbox)
   -> Dispatcher (ready case + approvals + lease/fencing)
        -> Chat erasure adapter -> existing V86 ChatErasureWorker DB role
        -> Document/base adapter -> existing Knowledge dedicated worker
        -> User account offboarding adapter -> scoped transactional procedures
        -> Model policy/catalog adapter -> versioned control plane
        -> Tenant offboarding saga -> child orchestration
   -> Verification/reconciliation -> evidence receipts + tombstones
   -> Monitoring / alerts / sanitized audit
```

- Intake не имеет прав на физическое стирание, technical worker не выбирает объект без approved manifest,
  operator не может подменить подтверждённый результат.
- Существующие dedicated logins `safeai_chat_erasure_worker`, `safeai_knowledge_erasure_worker` сохранить и
  расширить только минимальными audited grants.
- Scheduler не обходит `ErasureAdmissionPolicy`, hold/ACL, feature gate. Состояние «поступила новая заявка»
  при выключенном исполнителе может быть допустимо **как заявка на рассмотрение**, но не как принятие
  необратимой операции.

### 11.1a. Privacy, retention и доказательная граница [РЕШЕНИЕ]

- Модель данных допускает юридический запрос на удаление при `worker OFF`.
Ответ заявителю не должен скрывать наличие обязательного удержания.
- Для каждой категории удержания нужны нормативное основание, владелец,
дата пересмотра и ограничение доступа. Без установленного основания нельзя автоматически считать бессрочное
хранение допустимым.
- Прямой доступ пользователей к исходному audit receipt запрещён;
выдаётся минимально необходимая санитизированная версия с детальным указанием категории исключений и границы
доказанного удаления.
- Технический актор/оператор не получает права читать исходный файл:
аудит и доказательство содержат IDs, digests и outcome codes, не контент.
- Для backup: отдельная политика срока жизни, контролируемая изоляция
при восстановлении, повторное применение signed tombstones и доказательство отсутствия resurrection до
открытия HTTP.
- Для логов, traces, метрик, exports и сторонних провайдеров обязателен
отдельный data-controller и подтверждение объёма реально удаляемого.
- Там, где уничтожение копии невозможно по закону/технике, это честно
отражается как `RETAINED_WITH_BASIS` или `EXTERNAL_PENDING`, не как успех.

### 11.2. Storage и Object Lock

[ФАКТ] `S3ObjectStorage` уже имеет `purgeAllVersionsVerified` с enumerating object versions, exact key,
heartbeat, без bypass retention. [ФАКТ] `KnowledgeErasureTombstoneJournal` проверяет отдельный S3 Compliance
bucket/versioning. **[ПРОВЕРИТЬ]** на staging целевого S3-compatible провайдера: версии, delete markers,
list consistency, replica lag, metadata protection, WORM retention, credentials, orphan uploads.

AWS документирует, что простой `DELETE` при Object Lock может создать `delete marker` с HTTP 200, не удаляя
защищённую версию. Следовательно, `200` и отсутствие объекта в обычном GET не являются доказательством
физического стирания. Ссылка: <https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html>.

### 11.3. Релизные флаги и безопасность

- [ФАКТ] `safeai.chat.erasure.accept-enabled`, `.executor-enabled`, `.retention-approved`; Knowledge
  `.deletion.accept-enabled`, `.executor-enabled`, `.base-deletion.*`, `.erasure-journal.*`,
  `.erasure-release.*`.
- [ПРОЕКТ] новые flags `safeai.erasure.requests.enabled`, `safeai.erasure.approvals.enabled`,
  `safeai.erasure.chat-self-service.enabled`, `safeai.erasure.chat-message.enabled`,
  `safeai.erasure.user-offboarding.enabled`, `safeai.erasure.organization-offboarding.enabled`,
  `safeai.erasure.models.enabled`.
- Default `false` для всех destructive flags; flags не являются evidence. Доступ `SUPER_ADMIN` не меняет
  значение policy или legal hold.
- Продакшн-release требует реального signed release manifest (commit SHA, environment ID, storage provider,
  tested feature, external evidence links) и независимой проверки перед включением.

### 11.4. Observability и действия оператора

Отслеживать без PII: `pending_cases`, `approval_age`, `blocked_retention`, `needs_review`, `inflight_steps`,
`lease_expiry`, `retry_count`, `unverified_storage_keys`, `external_receipt_lag`, `backup_replay_failures`,
`tenant_freeze_age`.

Оператор может: смотреть sanitized diagnostics, повторять **идемпотентный** шаг под новым fencing,
инициировать reconcile/read-only scan, эскалировать hold/legal review. Оператор **не может** вручную
отмечать DELETED или стирать записи по сырому SQL без отдельной break-glass процедуры и аудита.

---

## 12. Roadmap внедрения с конкретными результатами и шлюзами

Фиксируем порядок **R0–R9**, добавляя подэтап R3b для одного сообщения и отдельные блокеры для enterprise
deletion. Каждый пункт — самостоятельный cumulative patch, не серия неконтролируемых ручных изменений.

****R0. Data & Threat Inventory****
- **Что разрабатываем:** Полный data map, FK graph, provider/copy inventory, legal matrix, учёт версий/hold
  и bug-risk review
- **Поставляемые изменения:** `docs/production/erasure-inventory.md`, `erasure-threat-model.md`,
  `erasure-role-matrix.md`, signed decisions; без DDL
- **Quality/production gate:** 100% найденных PII/copy sinks классифицировано; нет неизвестных destructive
  dependencies для MVP

****R1. Case Foundation****
- **Что разрабатываем:** V103–V105 (intake, detachment, review); дальнейшие версии только после сверки, typed domain, idempotency, state transitions, immutable events, approval
  rules, no worker execution
- **Поставляемые изменения:** additive SQL, Runtime ACL manifest, Java interfaces/controllers, OpenAPI,
  unit/IT
- **Quality/production gate:** Replay, IDOR, self approval, stale roles, concurrency, migration refusal;
  flags OFF

****R2. UX/Workflow****
- **Что разрабатываем:** User privacy & My requests, tenant queue, superadmin queue, step-up/verified
  support intake, notifications
- **Поставляемые изменения:** React views/APIs, accessible dialogs, outbox notifications, tests
- **Quality/production gate:** E2E USER/ADMIN/SUPER_ADMIN; no false success, no exposed foreign tenant data

****R2.3. Workflow Consistency & Role Acceptance****
- [КАНДИДАТ] Согласованная сводка и реестр, read-only карточка, независимые статусы,
  V103/V105 history, упрощение UI, обновлённый OpenAPI, серверные scope tests.
- [ПРОВЕРИТЬ] PostgreSQL 16: две организации, две независимые роли, DRAFT/SUBMITTED/WITHDRAWN,
  REVIEW/LEGAL grant/revoke, holds, более 100 записей, равенство summary и registry,
  прямые чужие ID → 404, неизвестный scope → deny, все flags OFF/ON в тестовой среде.
- [ПРОВЕРИТЬ] USER/ADMIN/SUPER_ADMIN браузерная приёмка, возврат с фильтрами, 390/768/1440 px,
  runtime ACL, coverage baselines, Maven/npm/Playwright, GitHub Actions. Нужны фактические логи.
- [БЛОКЕР ДО R3] Проверить старые chat/document/base/account endpoints, worker credentials,
  выключение accept/executor flags; подготовить единый admission gate (shadow и enforcement).
  Не включать executor до доказанных отказов обходных путей. Не считать существующие legacy
  операции объединёнными с заявками. Организация: disable/enable не равно удалению.
- [БЛОКЕР ДО EXECUTION] Настоящий step-up/MFA, полноценный per-case allowedActions, proof,
  safe retention/legal policy, реальная инфраструктура. Ни один новый workflow executor не включён.

****R3a. Whole-chat content erasure****
- **Что разрабатываем:** Case adapter к V86, USER self-service после авторизации, coverage provider boundary
- **Поставляемые изменения:** Adapter + worker contracts + IT + UI
- **Quality/production gate:** Real PostgreSQL, active turn/hold/cancel/races, content proof

****R3b. Single-message and AI-output erasure****
- **Что разрабатываем:** Scoped message deletion (если утверждено как продуктовая функция)
- **Поставляемые изменения:** New executor, provenance mapping, tests
- **Quality/production gate:** No broken conversation/usage, no hidden copies, exact message scope

****R4. Knowledge document****
- **Что разрабатываем:** Case wrapping V78–V89, S3 full version proof, owner/uploader request semantics
- **Поставляемые изменения:** Adapter + WORM evidence + real S3 IT
- **Quality/production gate:** Object Lock, network failures, index/embedding/preview/old versions, orphan
  scan

****R5. Knowledge base****
- **Что разрабатываем:** Frozen parent, child saga, dual approval, no orphan
- **Поставляемые изменения:** Base adapter, UI impact, reconciliation
- **Quality/production gate:** Multi-doc/active ingestion/hold/cancel/crash/restart cases

****R6. User/Admin offboarding****
- **Что разрабатываем:** Self-request, admin request, transfer, disable, verified privacy cleanup, current
  delete integration
- **Поставляемые изменения:** Services + safe audit redesign + UI
- **Quality/production gate:** Last admin, SUPER_ADMIN protect, legal/financial holds, no cross-owner delete

****R7. Model lifecycle****
- **Что разрабатываем:** Per-tenant detach, global retirement version, safe runtime/key revocation
- **Поставляемые изменения:** Versioned model commands, impact UI, tests
- **Quality/production gate:** No broken default model, budgeting/routing; V45 immutable facts preserved

****R8. Tenant offboarding****
- **Что разрабатываем:** Organization freeze, dual control, all child sagas, tombstone
- **Поставляемые изменения:** Tenant coordinator, DB gates, UI, receipts
- **Quality/production gate:** All dependencies, split failures, in-flight provider, restore/replay,
  cross-tenant isolation

****R9. Production Operational GO****
- **Что разрабатываем:** Staging destruction drills, DR, security, privacy, rollout, monitoring
- **Поставляемые изменения:** Full evidence bundle, runbooks, dashboards, go/no-go memo
- **Quality/production gate:** Explicit GO for each type, approved owners, canary, rollback of flags (not
  erased data)


### 12.0a. Условия передачи между этапами

- **R0 -> R1:** готов data map с владельцами всех известных копий;
неизвестный PII sink блокирует destructive релиз его типа.
- **R1 -> R2:** все case-эндпоинты имеют tenant-authorization и idempotency;
PostgreSQL IT доказывает отсутствие worker dispatch при `OFF`.
- **R2 -> R3:** нельзя отправить destructive intent через старые прямые API;
работающий support-intake не маскирует выключенный executor.
- **R3a -> R3b:** отдельно доказана изоляция одного сообщения, RAG и
финансовых записей; если нельзя, R3b остаётся `NOT_SUPPORTED`.
- **R4 -> R5:** document purge подтверждён на конкретном S3-провайдере;
parent не финализируется по «все child HTTP ответили 200».
- **R5 -> R6:** перенос владения корпоративными данными испытан;
нет скрытых PII в audit/outbox/usage и на временных копиях.
- **R7 -> R8:** models, учет расходов, секреты, ключи и архивы корректно
работают с tenant tombstone; нет активных provider dispatch.
- **Каждый destructive релиз -> R9 evidence gate:** signed receipts,
staging failures, независимая проверка, canary и отдельный rollback flags.

Приёмка R9 **не переносится целиком на конец проекта**: каждый тип удаления получает собственный P0/GO, а
общий R9 объединяет доказательства и утверждает tenant-offboarding.

### 12.1. DoD для КАЖДОГО патча

1. Один контролируемый cumulative patch/archive с точными исходниками, версией репозитория, file
   manifest/SHA и rollback **кода**. Никаких непроверенных `unzip` поверх конфликтующих изменений.
2. Java 21, нормальная читаемость и длина строк согласно стилю проекта; комментарий с путём для новых
   исходников, если это требование репозитория; frontend layering/architecture checks.
3. Проверки `mvn test/verify` + real PostgreSQL IT + конкретные failure/concurrency tests; frontend `npm run
   check`, Playwright e2e по необходимости.
4. JaCoCo baseline/quality scripts обновляются **только при появлении нового runtime-модуля**, без снижения
   порогов ради зелёного CI.
5. Migration checksum/ACL review + sequential upgrades/fresh DB + тесты отказов missing/changed migrations.
6. Product/security/legal review: risk, permissions, data map, approved scope, threat model, privacy, no
   hidden PII in logs.
7. Staging receipt включая negative tests; production feature gates remain OFF до отдельного решения.
8. User-facing docs: значение каждого статуса, порядок действий, ограничения, сохранённые законом данные.

### 12.2. Gate dependency graph

```text
R0 -> R1 -> R2 -> R3a -> R4 -> R5 -> R6 -> R7 -> R8 -> R9
                    \-> R3b after R3a and data dependency review
```

`R4/R5` может разрабатываться параллельно с `R3b`, но tenant offboarding запрещён до приёмки `R3a`, `R4`,
`R5`, `R6`, `R7`. User offboarding должен уметь корректно сохранить/переоформить **shared** Knowledge даже
при выключенном физическом удалении Knowledge. На UI case-intake можно идти раньше physical release, но
экран обязан показывать «принято на рассмотрение».

---

## 13. Обязательная матрица тестирования (минимум)

### 13.0. Обязательный способ проверки

Любая проверка неудачного удаления должна подтвердить **отсутствие физического side effect**, а не только
HTTP `403`/`409`. Для DB/S3 использовать snapshot до/после, worker dispatch spy, WORM journal и проверку
объектов другого tenant. Каждая негативная проверка — отдельный регрессионный тест с фиксированным
идентификатором сценария.


### 13.1. Security / authorization P0

- USER A не видит, не создаёт и не подтверждает case пользователя B или другого tenant (подмена UUID, body
  org, query, cursor, support case, response linking).
- ADMIN не удаляет ADMIN или чужую организацию, не назначает себе SUPER_ADMIN, не approves собственную H/C
  заявку, не обходит owner basis, не видит чужой персональный content.
- SUPER_ADMIN не может удалить platform tenant, last SUPER_ADMIN или обойти legal hold.
- Удалённая роль, выключенный user/org, rotation token/version после approval блокируют все новые переходы;
  stale JWT/cookie и CSRF attack fail closed.
- SQL injection, mass assignment, invalid enums, oversized payload, forged preview signature, replay другого
  tenant, disclosure timing/address enumeration.
- Через текст AI-сообщения/тикет невозможно запустить destructive endpoint автоматически.
- Legacy `DELETE` без approved case, прямой вызов worker, повтор старого approval и массовая подмена scope
  запрещаются на сервере, независимо от UI.
- Для одинаковых малых полей ПД нельзя извлечь email/ФИО перебором публичного SHA digest; receipt и аудит не
  содержат секретный mapping key.

### 13.2. Concurrency / state P0

- Две одновременные заявки на один объект; одинаковый/разный idempotency key; different payload same key;
  race approve/cancel/hold; race upload/delete.
- Approval после изменения owner/tenant/role/target version/manifest истекает; недостающий approver +
  self-approval запрещены.
- Crash после DB intent, после S3 удаления до DB commit, после outbox receipt, после release lease; retry
  без двойного уничтожения/ложного completed.
- Worker late fencing, lost lock, network partition, DB primary failover, redis unavailable, delayed
  replication; terminal commits только с актуальным token.
- Cancellation before/after destructive barrier и scope refresh; never appear `CANCELLED` after irreversible
  step.

### 13.3. Storage / retention P0

- Реальный staging PostgreSQL 16 + pgvector, S3-compatible bucket с versioning+Object Lock Compliance,
  отдельный WORM journal, отдельные DB logins.
- 0/1/много object versions, delete marker, current+historical versions, >128 versions, interrupted listing,
  403/404/409/5xx, partial purge, delayed consistency, corrupted receipt, tombstone mismatch.
- Active Compliance/Legal Hold, hold placed concurrently, hold released only legal governance, orphan upload
  after delete blocked.
- Backups/replicas: restore isolated copy, reapply tombstones before serving, zero resurrection; проверка
  локальных кэшей, OCR previews, embeddings, search citations, export files.
- Production probe не уничтожает живые данные и не ставит необратимый Object Lock на рабочие
  пользовательские объекты.

### 13.4. Functional P0

- Self whole-chat, single-message not enabled before release, own versus чужой документ, document uploader
  without OWNER, Knowledge base child held, shared base ownership transfer.
- User self-request, user ADMIN offboarding, last ADMIN, support verified request, user with
  financial/history data, user with shared knowledge access.
- Tenant freeze and in-flight AI provider attempt, tenant with 0 users/very many objects, platform tenant
  refusal, step-by-step child statuses, resuming pending tasks.
- Model default fallback, RETIRED catalog new version, immutable old row, shared model multi-tenants,
  price/usage/route history intact, key revocation.
- UI all states, button gating, 390–1440 layouts, keyboard/focus, stale tabs/React race, no misleading
  success message.

### 13.5. Privacy/compliance P0

- Ни один новый audit event, receipt, trace/log, notification, CSV не содержит исходные chat messages,
  документ, секрет или ненужные ПД.
- Существующие `USER_PERMANENTLY_DELETED` audit payload/actor snapshots попадают в отдельную remediation
  проверку.
- `COMPLETED_ACTIVE_SCOPE` выдаётся только с доказательствами всех активных копий; receipt содержит явно
  обозначенные законно сохранённые категории и ожидающие external/backup обязательства.
- Privacy/legal requests обрабатываются в рамках утверждённых сроков по юрисдикции; нет несанкционированного
  уничтожения финансовых и защищённых доказательств.

**P0 failure = NO-GO**, даже если JUnit, CI и JaCoCo зелёные.

---

## 13.6. Доступность и большие объёмы [ПРОЕКТ]

- Базы с 0, 1, 10 000+ документов; большие чаты, длинная история;
лимиты сканирования и детерминированная разбивка задач.
- Backpressure и fairness по tenant: удаление крупной организации
не блокирует операции другой организации и обычный AI-traffic.
- Retry-budget, alert threshold, time-to-review и deadline по заявке
конфигурируются и согласуются с юридическим SLA; числа не выдумываются.
- Повторный запуск после релиза не может повторно стирать уже подтверждённые
данные или превратить `RETAINED_WITH_BASIS` в успешное удаление.

---

## 14. Production evidence, rollout и эксплуатация

### 14.1. Release evidence bundle

Для каждого `resource_type`/worker хранить вне приложения: commit SHA, migrations/ACL SHA, test-run ID,
Testcontainers images digest, staging PostgreSQL/S3 identity, Object Lock proof, backup restore proof,
failure injection report, authorization review, privacy review, independent approver, timestamps и signed
decision. Скрипт evidence gate должен быть fail-closed: отсутствует proof/хэш/вторая подпись = NO-GO.

### 14.2. Rollout

1. `requests.enabled=true`, все physical flags OFF: приём обращений и статусы без создания destructive
   worker intents.
2. Dry-run internal organization, isolated staging fixtures, owner/tenant role matrix tests.
3. Chat whole-content canary с отдельным SLO; затем document, base, users, models, organization. Никакой
   общей кнопки «включить всё».
4. Непосредственно перед rollout: freeze schema versions, audit DB grants, credential separation, object
   lock, receipts, restore drill.
5. Мониторинг реальных первых операций, ограниченный throughput, alerting и операторский runbook.
6. При проблеме выключить новый dispatch и destructive execution per-resource. **Не прекращать регистрацию
   законных обращений без отдельного основания**; case intake может оставаться доступен. Уже начатые шаги
   переводить в ограниченное состояние review; отката стёртых байтов нет.

### 14.3. Runbook incidents

- S3 refusing/blocked: inspect version-specific evidence, retain operation and proof; legal hold не
  обходить.
- DB outage: no new acceptance, retry bounded; reconcile receipts and DB states with fencing.
- Outbox unavailable: case committed, delivery pending; не делать destructive action без всех preconditions.
- Audit tampering/unknown hash: stop new destructive operations, alert Security, preserve evidence.
- Provider deletion impossible: expose documented external retention boundary, separate obligations.
- Restore resurrection discovered: emergency auth/serving fence for affected resources, replay tombstones
  and reverify **до** снятия блокировки.

---

## 15. Решения, которые нужно утвердить до R1 (не угадывать)

**Юрисдикции и основания удаления**
- **Почему нужен:** GDPR/локальные нормы, финансовые retention periods и юридические exceptions различаются
- **Предлагаемое значение до решения:** `LEGAL_POLICY_PENDING`: запрет H/C destructive

**Можно ли USER удалять собственные чаты без ADMIN approval?**
- **Почему нужен:** Текущий executor ограничен ADMIN
- **Предлагаемое значение до решения:** Предлагается self-service после владельческой проверки, но без
  расширения до другого tenant

**Можно ли USER удалять **одно** сообщение AI?**
- **Почему нужен:** Может нарушить контекст, аудит, провайдерские копии
- **Предлагаемое значение до решения:** Только после R3b и proofed executor

**Что считать «личным документом»?**
- **Почему нужен:** Uploader может не быть OWNER общей базы
- **Предлагаемое значение до решения:** Не считать uploader достаточным правом автоматически

**Когда отключать аккаунт при self-delete?**
- **Почему нужен:** Пользователь должен подтвердить и отслеживать
- **Предлагаемое значение до решения:** После verified intake и policy gate; до irreversible barrier
  договориться о cancel window

**Нужна ли реальная MFA/step-up?**
- **Почему нужен:** Текущий SecurityConfig не доказывает наличие этого механизма
- **Предлагаемое значение до решения:** High risk блокировать, пока не реализован/не утверждён alternate
  control

**Каковы сроки хранения аудита, финансов, backup?**
- **Почему нужен:** Нельзя выдать receipt без retention matrix
- **Предлагаемое значение до решения:** Отдельная подписанная таблица сроков и legal basis

**Какая именно S3-платформа используется в prod?**
- **Почему нужен:** AWS-совместимость не равна доказанной Object Lock семантике
- **Предлагаемое значение до решения:** Провести staging provider conformance suite

**Куда поступают обращения по email/поддержке?**
- **Почему нужен:** Нужна verified identity и целостный case ID
- **Предлагаемое значение до решения:** Ввести manual verified support intake, не auto-from-email

**Как назначаются approvers?**
- **Почему нужен:** Не все организации имеют 2 ADMIN
- **Предлагаемое значение до решения:** Назначенная роль платформы с independence/conflict-of-interest
  checks

**Обязателен ли физический DELETE организационной строки?**
- **Почему нужен:** FK/финансы/аудит могут требовать tombstone
- **Предлагаемое значение до решения:** Предпочитать tombstone до legal review

**Можно ли удалять старую модель/version?**
- **Почему нужен:** V45 immutable trigger, historical provenance
- **Предлагаемое значение до решения:** RETIRE новую версию; физическое удаление не входит в обычный
  контракт

**Каналы уведомлений и текст писем**
- **Почему нужен:** Не допустить PII leak
- **Предлагаемое значение до решения:** In-app + verified email без секретов/имен документов


До получения правовых решений можно реализовать R1 как **безопасный intake без destructive execution**, но
не заявлять Production GO на удаление организаций/аккаунтов.

---

### 15.1. Порядок утверждения спорных решений

Сначала согласовать `role-matrix`, `retention-class`, jurisdiction, `independent-approver`, `MFA`, границы
provider и смысл user-facing статусов. Решение должно содержать владельца, дату, версию, основание и scope.
Если решение отсутствует, R1 продолжает безопасный intake, но H/C execution и создание соответствующих
destructive steps остаются `OFF`.

---

## 16. Конкретный первый пакет разработки: R0 + R1 Foundation

### R0: выдаваемые материалы

1. Подтвердить точный SHA рабочей ветки и что архивы соответствуют ему (команды `git rev-parse HEAD`, `git
   status --short`, `git diff --name-status`); получить актуальные `.github/`, `infra/`, `pom.xml` при
   несовпадении старых архивов.
2. Построить machine-readable `data-map.yaml` и граф FK из `pg_catalog`, source migrations, JPA, raw JDBC,
   JSONB, Redis, S3, provider API, logs, audit.
3. Согласовать legal retention/hold/backup и peer-review модели прав L/M/H/C.
4. Список существующих feature flags/DB worker privileges, состав профилей приложения, тестовое окружение
   без production секретов.
5. Подготовить security attack stories и точный OpenAPI `erasure-requests-v1.yaml`.
6. Выпустить R0 Security GO for **request registration only**; execution по-прежнему OFF.

### R1: минимальные функции, не запускающие стирание

1. Новые additive migrations и manifest review; entity/service/controller для case, immutable events,
   idempotency, scopes, approvals, preview store.
2. `AuthorizationPolicy` с persisted role/tenant/owner/scope проверкой и `IndependentApprovalPolicy` без
   доверия к client-declared roles.
3. Блокировка new endpoints при `OFF` и отсутствие связи с destructive worker до принятия R3+.
4. Открытые по ролям read-only endpoints, pagination, stable error codes, audit/event outbox без PII.
5. Unit + PostgreSQL IT: duplicate keys, cross-tenant, stale versions, role revocation, approvals race,
   hold, object deleted after preview.
6. Endpoint discovery/E2E tests и API contract fixtures; security config allowlist only expected
   methods/routes.
7. Предусмотреть migration dry-run и двойную авторизацию, но **не включать** реально удаляющие flags.

**R1 DoD:** API способен достоверно сказать «заявка получена», «на рассмотрении», «запрещено» и
«просрочено». **API не способен удалить ни один байт контента**. Это проверяется тестами отсутствия worker
dispatch.

---

## 17. Реестр исходников, на которые опирается проект

Ниже **только реально просмотренные** исходники из предоставленной кодовой выборки; назначение путей
относится к архивному snapshot, а не автоматически к текущему Git HEAD:

- `backend/src/main/java/ru/safeai/gateway/retention/RetentionManagementService.java`,
  `ErasureAdmissionPolicy.java`.
- `backend/src/main/java/ru/safeai/gateway/chat/controller/ChatLifecycleController.java`,
  `chat/service/ChatLifecycleService.java`, `chat/service/ChatErasureWorker.java`.
- `backend/src/main/java/ru/safeai/gateway/knowledge/controller/KnowledgeBaseDeletionController.java`,
  `KnowledgeDocumentDeletionController.java`, `KnowledgeDocumentDeletionCapabilityController.java`,
  `KnowledgeDeletionOperatorController.java`.
- `backend/src/main/java/ru/safeai/gateway/knowledge/service/KnowledgeAccessService.java`,
  `KnowledgeDocumentDeletionAcceptanceService.java`, `KnowledgeDocumentDeletionExecutor.java`,
  `KnowledgeBaseDeletionService.java`, `KnowledgeBaseDeletionExecutor.java`.
- `backend/src/main/java/ru/safeai/gateway/knowledge/storage/ObjectStorage.java`, `S3ObjectStorage.java`,
  `KnowledgeErasureTombstoneJournal.java`.
- `backend/src/main/java/ru/safeai/gateway/user/service/UserCommandOperations.java`,
  `UserAccessPolicy.java`; `user/repository/UserRepository.java`; `user/controller/UserController.java`.
- `backend/src/main/java/ru/safeai/gateway/organization/service/OrganizationCommandOperations.java`,
  `OrganizationAccessPolicy.java`; `organization/controller/OrganizationController.java`.
- `backend/src/main/java/ru/safeai/gateway/model/controller/ModelCatalogController.java`,
  `OrganizationModelPolicyController.java`; `model/service/ModelCatalogService.java`;
  `model/domain/ModelLifecycle.java`.
- `backend/src/main/java/ru/safeai/gateway/auth/security/SecurityConfig.java`, `UserStatusFilter.java`;
  `common/security/SafeAiUserPrincipal.java`.
- `backend/src/main/resources/db/migration/V45__model_control_plane.sql`,
  `V86__chat_archive_restore_erasure.sql`, `V102__platform_erasure_admission_policy.sql`,
  `db/acl/reviewed-migrations.sha256`, `db/acl/runtime-v90.sql`.
- `backend/src/main/resources/application.yml`, `application-prod.yml`.
- `frontend/src/app/App.tsx`, `frontend/src/features/admin/ui/RetentionWorkspace.tsx`,
  `features/chat/controller/ChatArchiveModal.tsx`, Knowledge deletion UI и APIs, модельные/пользовательские
  API.
- Тестовые файлы
  `backend/src/test/java/ru/safeai/gateway/{chat,knowledge,model,user,organization,retention}/...` и
  frontend retention/knowledge/model tests присутствуют в загруженных тестовых архивах; их существование
  **не заменяет** исполнения на staging.

## 18. Внешние требования и ограничения (отдельно от исходников)

- **OWASP ASVS 5.0.0**, веб-авторизация, безопасность операций: <https://asvs.owasp.org/>.
- **AWS S3 Object Lock**, delete markers vs version-specific deletion/Compliance/legal hold:
  <https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html>. Для другого S3-compatible
  провайдера обязательны собственные conformance tests.
- **NIST SP 800-88 Rev. 2** (финальная редакция, сентябрь 2025), принципы sanitization:
  <https://csrc.nist.gov/pubs/sp/800/88/r2/final>.
- **GDPR, Article 17**, только если применим к конкретному оператору/субъекту:
  <https://eur-lex.europa.eu/eli/reg/2016/679/oj/eng/>. Исключения и сроки хранения определяет юридическая
  функция, не одна настройка UI.
- Для РФ/иных стран требуется отдельный legal review действующего закона и договорных обязательств; этот
  документ не назначает универсальные юридические сроки.

---

## 19. Решение о старте

**APPROVE: R0 инвентаризация + R1 регистрация/обработка заявок, строго без физического удаления.**
**HOLD: R3–R8 destructive execution до приёмки соответствующих модулей/инфраструктуры.**
**NO-GO: массовое удаление организаций, SUPER_ADMIN, immutable model versions, обход S3 retention, стирание
чужого контента без независимой authority.**

**Итог:** из нового snapshot уже можно проектировать и поэтапно внедрять жизненный цикл, при этом «удаление»
перестаёт быть двусмысленным действием. Каждый конкретный объект проходит авторизацию, approval, явный
scope, технические ограничения, отдельное доказательство исполнения и правила хранения оставшихся данных.
Физический Production GO возможен только на базе испытаний реальной инфраструктуры и утверждённой
privacy/legal матрицы.


## 20. R2.4 — уведомления и контекстные обращения (кандидат)

- `GET /api/erasure/requests/notifications` — read-only проекция событий V105 и серверных счётчиков.
- Платформа: число зарегистрированных заявок без рассмотрения или в PENDING. ADMIN: число зарегистрированных заявок своей организации без раскрытия решений. REVIEW: число PENDING своей организации за исключением собственных заявок; LEGAL без REVIEW не получает эту ленту.
- Личные уведомления: последние 20 событий APPROVED_REVIEW_ONLY, REJECTED, NEEDS_INFORMATION только своего аккаунта. Лента согласующего: до 20 актуальных событий SUBMITTED/INFORMATION_PROVIDED. Счётчики не являются счётчиками непрочитанных; отдельные гарантии доставки, email/Telegram, read receipts не реализованы.
- Опрос каждые 30 секунд и при смене маршрута; проверка live roles и feature flags на каждом запросе.
- Контекстные ссылки чата/базы/документа предзаполняют существующую форму. Обязательны прежние preview, draft, submit и серверная проверка объекта.
- Приёмка: frontend CI, backend verify, runtime-role PostgreSQL, отсутствие межорганизационной утечки, отзыв REVIEW, отсутствие автоматических мутаций и физического удаления. R3–R8 остаются HOLD.
