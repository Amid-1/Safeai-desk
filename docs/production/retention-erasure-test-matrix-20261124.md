# SafeAI Desk — Erasure security acceptance matrix

Статус: **план тестирования, NOT RUN**. Для каждого теста нужны SHA, окружение, дата, артефакт и reviewer.

| ID | Проверка | Обязательное ожидание |
|---|---|---|
| E-001 | USER просит удалить свой документ без OWNER | Только заявка на рассмотрение, нет destructive intent |
| E-002 | OWNER удаляет документ из своей базы | One durable operation, exact tenant+version, полный purge receipt |
| E-003 | ADMIN пытается удалить чужую базу | 403/404 без раскрытия existence, нет новых DB rows |
| E-004 | ADMIN просит удалить чужой чат | DENY, owner fence не обходится |
| E-005 | Обращение пришло по email | Не допускать к исполнению до verification actor/source |
| E-006 | Смена tenant после approval | Approval отозван/invalidated, новый доступ запрещён |
| E-007 | Revoked admin после approval | 403, состояние не переходит в ADMITTED |
| E-008 | Один reviewer утверждает собственный high-impact запрос | DENY, второй независимый reviewer обязателен |
| E-009 | Тот же idempotency key и тот же payload | Возвращается прежний requestId/operationId |
| E-010 | Тот же key для другого объекта | 409, никаких новых side effects |
| E-011 | Stale ETag | 412, нет удаления |
| E-012 | Global chat/knowledge policy OFF | DENY для соответствующего типа, другого типа не касается |
| E-013 | Worker flags OFF | Новая destructive операция не принята, не ложная очередь |
| E-014 | Hold на объекте | Ничего не purged, BLOCKED_BY_RETENTION |
| E-015 | S3 versioned object + delete marker | Проверены все версии и delete markers, нет false COMPLETED |
| E-016 | S3 Object Lock compliance/legal hold | Purge отклонён и зарегистрирован, не обходится privileged override |
| E-017 | Crash между внешним purge и SQL commit | Durable retry/review; доказательство не потеряно |
| E-018 | Одновременно два worker | Один lease, fencing token, без double-commit |
| E-019 | Сбой append-only journal | Fail closed до/после purge по безопасному протоколу |
| E-020 | Восстановление после рестарта/нового pod | Продолжение или NEEDS_REVIEW, не ложное COMPLETED |
| E-021 | Пользователь отключён менее срока хранения | User permanent deletion DENY |
| E-022 | Есть активный refresh/FK зависимости | User deletion DENY, без частичного результата |
| E-023 | Delete organization | 404/405/not implemented, только disable доступно |
| E-024 | Audit snapshot privacy | Полный provenance без raw PII/content/секретов |
| E-025 | Переключение V102 policy | Existing operation unaffected, new admits correctly gated |
| E-026 | Смена роли/организации | Expired authorization on next action; не доверять frontend state |
| E-027 | Массовый запрос | Fingerprint цели, explicit size cap, dual control, no hidden recursive deletion |
| E-028 | API status по чужому requestId | Никакого раскрытия cross-tenant данных |
| E-029 | Backup/restore | Deleted-active не выдаётся за полное уничтожение backups, сроки отражены |
| E-030 | Monitoring | Alert на зависшие NEEDS_REVIEW и journal unavailable, операционная инструкция |

## Приёмка по каждой строке

Структурированные evidence должны ссылаться на CI-тест (включая SQL и Docker), immutable SHA артефакта,
временную метку, reviewer (не инициатора). Записи `SKIPPED`, `NOT_RUN`, `UNKNOWN` запрещены для production GO.
Временное отключение Object Lock/hold в тестовой или production среде ради зелёного результата недопустимо.
