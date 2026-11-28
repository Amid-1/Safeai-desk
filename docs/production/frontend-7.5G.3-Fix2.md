# SafeAI Desk — чат: ошибки, архив и удаление

## Пользовательские сценарии

- Компактная шапка, больше пространства для сообщений.
- Тестовые ответы сохраняют маркировку; сведения о защите не являются результатом
  проверки фактического подключения или исполнения запроса.
- Отказ показан в области переписки, а не отдельной большой панелью над ней.
- «Редактировать сообщение» возвращает исходный текст в черновик без отправки.
- «Создать новый чат» не повторяет запрос. Старый чат остаётся в истории.
- Номер решения доступен в раскрывающихся сведениях для поддержки.
- Счётчик поля ввода обозначает символы, а не токены полного серверного запроса.

## INPUT_LIMIT_EXCEEDED

Backend учитывает суммарный вход и резерв дополнений, а не только текст сообщения.
На диагностическом снимке: 41 183 единицы входа, из них 40 960 — резерв дополнений.
Алгоритм UTF8_STRUCTURAL_UNITS_V2 не является токенизатором провайдера.
Эффективный вход ограничивается минимумом runtime, каталога и применимых правил
организации: ModelRoutingCostPolicy.effectiveInputLimit.

Новый чат может уменьшить историю, но не обязательно уменьшит резерв RAG.
Отказ проверять в «Модели → Диагностика» по номеру решения, затем сверять
актуальные правила, лимиты подключения и каталога. Не повышать лимиты вслепую.

## Архив и удаление

ADMIN может открыть меню ⋯ → «Удалить содержимое…».
Сначала требуется подтверждение архивирования. После успешного архива открывается
выбранный чат. Кнопка удаления проверяет capability на сервере.
При отказе capability отображается понятная причина; DELETE не отправляется.
USER может архивировать и восстановить собственный чат, но не запрашивать стирание.
Даже наличие роли ADMIN не даёт права стирать чужие чаты или обходить политику хранения.

При завершённых отказах FAILED и QUOTA_BLOCKED разрешается архивирование.
Локальный pending и черновик удаляются лишь после успешного POST архивации.
При активной операции или неизвестном результате архивирование блокируется.
Несохранённый черновик не входит в историю архива.

## Где находится реализация

Backend: backend/src/main/java/ru/safeai/gateway/chat/

- controller/ChatLifecycleController.java — HTTP API жизненного цикла чата;
- service/ChatLifecycleService.java — владелец, организация, роль, допуск и версия;
- service/ChatErasureWorker.java — отдельный исполнитель операции стирания;
- config/ChatErasureWorkerDatabaseConfig.java — отдельное подключение исполнителя;
- service/ChatErasureLimits.java — ограничения атомарной операции;
- entity/ChatErasureState.java — состояния операции.

Миграции: V86__chat_archive_restore_erasure.sql,
V87__chat_archive_owner_online_index.sql[.conf],
V88__chat_erasure_validate_constraints.sql.

Frontend: frontend/src/features/chat/

- ui/ChatHistoryRow.tsx — меню рабочего чата;
- controller/ChatSidebar.tsx — переход к удалению через архив;
- controller/ChatArchiveModal.tsx — проверка допуска, подтверждение и статус;
- api/chat/chatArchiveClient.ts — server-owned API, If-Match и Idempotency-Key;
- controller/operations/chatDirectoryActions.ts — безопасное архивирование.

Контракт:

- POST /api/chats/{id}/archive — архивировать;
- POST /api/chats/{id}/restore — восстановить;
- GET /api/chats/{id}/erasure/capability — проверить возможность;
- DELETE /api/chats/{id}/erasure — принять запрос удаления (202);
- GET /api/chats/{id}/erasure — сверить состояние;
- GET /api/chats/erasure-operations — история операций.

202 означает принятие операции, не доказательство завершения.
Перед DELETE требуются точное название, фраза УДАЛИТЬ НАВСЕГДА и свежая версия.
При неопределённом результате операция сверяется; новый ключ не генерируется.
По умолчанию accept-enabled, executor-enabled, retention-approved равны false
в пространстве safeai.chat.erasure. Включение требует отдельной подготовки
политики хранения, DB-роли исполнителя и тестов на выделенной среде.

Стирается содержимое в обслуживаемом хранилище, но остаются необходимые
аудиторские/финансовые метаданные. Резервные копии и данные внешнего провайдера
не считаются удалёнными на основании этой операции.

## Тесты

- ChatSidebar.test.tsx, ChatHistoryRow.test.tsx — роли, действия, запреты.
- ChatArchiveModal.test.tsx — серверный отказ, версия, подтверждение, 202, сверка.
- ChatMessageViews.pending.test.tsx — ошибки, сведения и действия без повторной отправки.
- chatDirectoryActions.test.ts — архивация после отказа без потери до ответа сервера.
- chatTurnOperations.test.ts — безопасность отправки и сохранение номера решения.
- platform-responsive.spec.cjs — ADMIN/USER и отказ capability в браузере.

Backend-приёмка удаления: ChatErasureFeatureGateTest, ChatLifecycleVersionTest,
ChatErasureStateTest, ChatContentErasureV86IntegrationTest,
ChatErasureSecurityV86IntegrationTest, ChatErasureBatchBoundaryV86IntegrationTest,
ChatErasureOperatorV86IntegrationTest, ChatErasureLimitsTest.
Эти backend-сценарии не заменяются frontend-моками.
