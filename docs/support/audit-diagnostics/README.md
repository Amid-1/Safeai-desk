# SafeAI Desk — диагностика обращений по журналу событий

Практическая инструкция для администраторов, второй линии поддержки и разработчиков. Актуальна для backend ветки
`fix/knowledge-upload-confirmation-20261025` на 16.11.2026. Предназначена для внутреннего использования, не для конечных
пользователей.

## 1. Что делать при обращении пользователя

1. Уточнить время проблемы с часовым поясом, организацию, учётную запись, действие и видимый симптом. Не запрашивать
   пароль, JWT, API-ключ, полный текст переписки или документы.
2. Открыть **Журнал событий** (`/admin/audit`), установить период, тип события и при необходимости фильтр пользователя.
   `ADMIN` работает в пределах разрешённой ему организации; `SUPER_ADMIN` — в пределах своей серверной авторизации,
   включая выбор организации.
3. Найти событие, открыть **Подробнее**, прочитать понятное описание результата. Учитывать, что один запрос может
   порождать несколько событий.
4. Раскрыть **Сведения для технической поддержки** и нажать **Копировать данные для поддержки**. Вставить JSON в
   закрытую служебную заявку вместе с описанием симптома и временем. Если буфер обмена недоступен, раскрыть
   «Диагностический отчёт (JSON)» и скопировать вручную.
5. Для отказа в выборе модели открыть **Открыть диагностику запроса**, если ссылка присутствует. Это диагностика
   сохранённого решения на момент запроса, а не повторный вызов модели. При необходимости передать инженеру
   идентификатор события и отчёт.
6. Инженер проверяет соответствующую запись в PostgreSQL, состояние операции, решение маршрутизации, попытки провайдера
   и учёт. После диагностики сообщить пользователю понятную причину и дальнейшие действия, не возвращая внутренний JSON.

UUID — не секрет доступа и не доказательство права на объект. Не публиковать отчёты в общих чатах, публичных issues или
внешних сервисах. В деталях отдельных типов событий могут быть служебные или персональные данные. Передача только по
утверждённым каналам и в необходимом объёме.

## 2. Что означает отчёт

Кнопка копирует уже полученную и нормализованную frontend запись аудита, не делает новый API-запрос и не считывает базу.
JSON в раскрываемом разделе совпадает с содержимым буфера обмена. `reportVersion: 1` — версия формата отчёта интерфейса,
а не версия backend или миграции. Остальные имена верхнего уровня соответствуют полям `AuditEvent`.

- `id` — Идентификатор записи `audit_events.id` — отправная точка расследования.
- `eventType` — Внутреннее имя enum `AuditEventType`, например `AI_RESPONSE_RECEIVED`. Не HTTP-код и не номер ошибки.
- `createdAt` — Момент события, ISO 8601; сопоставление с журналами и временем обращения.
- `targetOrganizationId` — Целевая организация события, `audit_events.organization_id`.
- `actorOrganizationId`, `actorUserId` — Исторические идентификаторы инициатора. Могут быть `null` и отличаться от
  целевой организации.
- `detailsInvalid`, `detailsTruncated` — Признаки некорректного или ограниченного при нормализации payload. При
  `detailsInvalid=true` отчёт содержит `details: null`; при усечении `details` может быть неполным.
- `details` — Разрешённые и нормализованные детали именно этого типа события. Не произвольное содержимое БД и не полная
  трассировка запроса.

Email, имя инициатора и название организации отдельно в отчёт не дублируются. UUID сохраняются полностью, даже если
визуально отображены только в JSON.

### Для событий ИИ

- `chatId` — `chat_turns.session_id` / идентификатор чата.
- `turnId` — `chat_turns.id`: одна обработка пользовательского запроса.
- `clientRequestId` — `chat_turns.client_request_id`: клиентский идентификатор операции; анализ повторов и
  идемпотентности в пределах чата.
- `providerOperationId` — `chat_turns.provider_operation_id` и `model_execution_plans.provider_operation_id`: внутренняя
  операция исполнения.
- `providerRequestId` — Идентификатор запроса у провайдера, если известен; сопоставляется с
  `chat_turns.provider_request_id` и `model_execution_attempts.provider_request_id`.
- `userMessageId`, `assistantMessageId` — Идентификаторы соответствующих строк `chat_messages`; ответ может
  отсутствовать при ошибке.
- `decisionId` — `model_route_decisions.id` для события решения маршрутизации. Для событий ответа берётся из
  `chat_turns.model_route_decision_id`, если связь есть.
- `turnState`, `providerErrorType`, `failureCode`, `outcomeAmbiguous` — Состояние и диагностические признаки обработки.
- `requestedModel`, `resolvedModel`, `provider` — Запрошенная, фактически использованная модель и провайдер.
- `inputTokens`, `outputTokens`, `usageStatus`, `pricingStatus`, `costUsd`, `currency` — Учёт токенов, доступность учёта
  и стоимость. Отсутствие полей не означает нулевое потребление.

Не путать `id`, `turnId`, `decisionId`, `providerOperationId`, `provider_attempt_id` и `providerRequestId`. У
mock-провайдера некоторые значения могут совпасть, это не обязательный контракт реальных провайдеров. Один план
исполнения может содержать несколько физических попыток.

## 3. Диагностика без доступа к БД

- `MODEL_ROUTE_DENIED`: проверить причину отказа и историческое решение через встроенную диагностику
  (`/admin/models?section=diagnostics&decisionId=...`). Это ограничение маршрутизации, не обязательно сбой провайдера.
  При отказе план исполнения или ChatTurn могут отсутствовать.
- `MODEL_ROUTE_DECIDED`: решение о маршрутизации принято; это ещё не успешный ответ модели.
- `AI_RESPONSE_RECEIVED`: backend зафиксировал успешную финализацию ответа. Само событие не доказывает доставку
  HTTP-ответа и отображение сообщения в браузере.
- `AI_RESPONSE_FAILED`: разобрать причину, состояние, признак неопределённого результата. Не выполнять повторный вызов
  наугад, если результат неизвестен: возможны дублирование и дополнительная стоимость.
- `mock` и `pricingStatus=FREE`: тестовый провайдер, нулевая стоимость в данном событии. `MISSING`/`UNPRICED` не равны
  нулю; см. конкретные статусы.

Существующий API: `GET /api/admin/audit-events`, `GET /api/admin/audit-events/cursor`,
`GET /api/admin/audit-events/users/{userId}` (`AuditController`). Поисковые параметры: `eventType`, `userEmail`,
`userId`, `dateFrom`, `dateTo`, `organizationId` с проверкой доступа на сервере. В текущем контракте нет отдельного GET
по `eventId` и фильтра `eventId`. Точный поиск по UUID события выполняется инженером в БД; не передавать его в
диагностику модели вместо `decisionId`.

## 4. Диагностика в PostgreSQL (вторая линия / инженер)

Использовать штатное подключение к нужной среде через утверждённую учётную запись с правами чтения и соответствующим
доступом к организации. Не использовать привилегии bootstrap-суперпользователя ради обхода ограничений. Не создавать
новые права этой инструкцией. SQL ниже предназначен для `psql`; в IntelliJ IDEA/DataGrip замените параметры на
именованные bind-параметры IDE. Все запросы только читают данные, не выбирают содержание сообщений, пароли, ключи или
хэши.

### 4.1. Найти точное событие

Подставить полные значения из отчёта вместо примеров (не оставлять нулевые UUID). Сверить организацию и среду независимо
от отчёта.

```sql
\set org_id '00000000-0000-0000-0000-000000000001'
\set event_id '00000000-0000-0000-0000-000000000002'

BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';

SELECT id, event_type, created_at, organization_id,
       actor_user_id, actor_organization_id,
       details ->> 'turnId'              AS turn_id,
       details ->> 'decisionId'          AS decision_id,
       details ->> 'providerOperationId' AS operation_id,
       details ->> 'clientRequestId'     AS client_request_id,
       details ->> 'providerRequestId'   AS provider_request_id,
       details ->> 'turnState'           AS turn_state,
       details ->> 'reason'              AS reason,
       details ->> 'failureCode'         AS failure_code
FROM public.audit_events
WHERE organization_id = :'org_id'::uuid
  AND id = :'event_id'::uuid;
```

Если ничего не найдено, проверить среду, организацию, UUID, права доступа и политику хранения. Отсутствие строки само по
себе не доказывает отсутствие события. Не снимать ограничение организации ради поиска.

### 4.2. Проверить обработку запроса и сохранение ответа

Если в шаге 4.1 есть корректный `turn_id`, заменить значение:

```sql
\set turn_id '00000000-0000-0000-0000-000000000003'

SELECT id, session_id, user_id, client_request_id,
       model_route_decision_id, provider_operation_id,
       user_message_id, assistant_message_id, state,
       requested_model, resolved_model, provider, provider_request_id,
       provider_error_type, failure_code, outcome_ambiguous,
       created_at, provider_call_started_at, completed_at, updated_at
FROM public.chat_turns
WHERE organization_id = :'org_id'::uuid AND id = :'turn_id'::uuid;

SELECT m.id, m.role, m.status, m.ai_response_status, m.finish_reason,
       m.requested_model, m.model, m.input_tokens, m.output_tokens,
       m.usage_status, m.pricing_status, m.cost_usd, m.currency, m.created_at
FROM public.chat_turns t
JOIN public.chat_messages m
  ON m.organization_id = t.organization_id
 AND m.session_id = t.session_id
 AND m.id IN (t.user_message_id, t.assistant_message_id)
WHERE t.organization_id = :'org_id'::uuid AND t.id = :'turn_id'::uuid;
```

Если ответ сохранён, но пользователь увидел ошибку, проверить доставку HTTP-ответа, клиентскую обработку, повторное
открытие чата. Если операция `AMBIGUOUS`, сначала проверить её состояние и имеющиеся попытки, не запускать платный
запрос повторно.

### 4.3. Проверить решение маршрутизации

Взять `decision_id` из шага 4.1 либо `model_route_decision_id` из шага 4.2. Если значения нет, пропустить. Не
использовать `event_id` вместо него.

```sql
\set decision_id '00000000-0000-0000-0000-000000000004'

SELECT id, outcome, reason, requested_model_key, selected_model_key,
       selected_provider, selected_provider_model_id, policy_version,
       budget_enforcement, budget_exceeded, pricing_complete, created_at
FROM public.model_route_decisions
WHERE organization_id = :'org_id'::uuid AND id = :'decision_id'::uuid;
```

Решение относится к моменту выполнения, а не к текущим настройкам организации.

### 4.4. Проверить физические попытки провайдера

Выполнить, если найден ChatTurn (шаг 4.2). План может не существовать при раннем отказе.

```sql
SELECT p.id AS plan_id, p.provider_operation_id, p.model_route_decision_id,
       a.attempt_number, a.provider_attempt_id, a.provider_type,
       a.requested_physical_model, a.resolved_physical_model,
       a.started_at, a.finished_at, a.outcome, a.outcome_certainty,
       a.retry_safety, a.fallback_safety, a.provider_request_id,
       a.failure_type, a.response_observed, a.response_validation_status,
       a.provider_http_status, a.protocol_failure_code,
       a.usage_status, a.input_tokens, a.output_tokens,
       a.pricing_status, a.cost_usd, a.currency
FROM public.chat_turns t
JOIN public.model_execution_plans p
  ON p.chat_turn_id = t.id AND p.organization_id = t.organization_id
 AND p.provider_operation_id = t.provider_operation_id
LEFT JOIN public.model_execution_attempts a ON a.execution_plan_id = p.id
WHERE t.organization_id = :'org_id'::uuid AND t.id = :'turn_id'::uuid
ORDER BY a.attempt_number;

ROLLBACK;
```

`ROLLBACK` завершает read-only транзакцию. Если диагностика закончилась раньше, выполнить его сразу. Идентификаторы и
время можно искать в имеющихся защищённых логах, если соответствующий компонент действительно их пишет; наличие всех ID
в логах не гарантируется. При обращении к провайдеру использовать его `providerRequestId` только при необходимости, а не
передавать ему весь внутренний отчёт.

## 5. Пример: пользователь не увидел ответ ИИ

Администратор находит `AI_RESPONSE_RECEIVED`, в UI видит модель, токены, стоимость и копирует отчёт. Инженер
подтверждает, например, `turnState=SUCCEEDED`, находит сохранённый `assistantMessageId` и успешную попытку. Это сужает
поиск до отдачи ответа клиенту или отображения чата; не доказывает конкретную причину. Если вместо этого
`AI_RESPONSE_FAILED` или `AMBIGUOUS`, расследуется иная ветка: отказ маршрутизации, провайдер, протокол, неопределённый
результат и безопасная возможность повторной попытки.

## 6. Где это реализовано в коде

Все Java-пути ниже относительно `backend/src/main/java/ru/safeai/gateway/`:

- `audit/controller/AuditController.java` — API, допуск ADMIN/SUPER_ADMIN; `audit/service/AuditEventQueryService.java` —
  авторизованный поиск.
- `audit/entity/AuditEventEntity.java`, `audit/details/ChatTurnAuditDetails.java` — запись и имена полей аудита.
- `chat/service/ChatTurnFinalizationService.java`, `chat/service/ChatTurnAuditDetailsFactory.java` — финализация и
  формирование сведений о запросе ИИ.
- `chat/entity/ChatTurnEntity.java`, `chat/entity/ChatMessageEntity.java` — состояния обработки и сообщения.
- `model/repository/ModelRouteDecisionRepository.java` — сохранённые решения;
  `model/service/ModelRouteDecisionAuditDetails.java` — поля событий решений.
- `ai/execution/ModelExecutionPlanEntity.java`, `ai/execution/ModelExecutionAttemptEntity.java` — планы и физические
  попытки.
- `usage/repository/UsageFactQueryBuilder.java`, `usage/repository/UsagePhysicalSpendRepository.java` — учёт на уровне
  сообщений и физических попыток соответственно.

Frontend: `frontend/src/features/audit/ui/AuditDetailsModal.tsx` формирует отчёт из `AuditEvent`;
`frontend/src/features/audit/api/adminAudit/auditParsers.ts` и `auditSanitizer.ts` нормализуют данные. Права, контракт
API, миграции, immutable-свойства аудита и логика обработки запросов не изменяются этим UI-патчем.

Расширять эту инструкцию следует при изменении серверных контрактов. Не добавлять в отчёт произвольные поля
чувствительных данных, не превращать его в выгрузку таблиц и не использовать его для изменения записей аудита.
