# SafeAI Desk --- Knowledge Base Core: отчёт о реализации и проверке

## Название файла

SafeAI_Desk_Knowledge_Base_Core_Security_Audit_Report.md

## 1. Назначение документа

Документ описывает реализованный модуль Knowledge Base в SafeAI Desk,
структуру V38, проведённые функциональные проверки и результаты
security-тестирования.

## 2. Реализованный модуль

Создан модуль **Knowledge Base Core (V38)**.

Реализовано:

-   корпоративные базы знаний;
-   tenant-aware архитектура;
-   привязка баз к организации;
-   видимость ORGANIZATION и MEMBERS;
-   управление участниками базы;
-   уровни доступа VIEWER / EDITOR / OWNER;
-   optimistic locking через version;
-   аудитные события.

## 3. Основная модель данных

### knowledge_bases

Хранит:

-   id;
-   organization_id;
-   название;
-   описание;
-   visibility;
-   enabled;
-   created_by_user_id;
-   version;
-   created_at / updated_at.

Особенность: created_by_user_id хранится без FK, чтобы сохранить историю
происхождения базы после удаления пользователя.

### knowledge_base_memberships

Хранит ресурсные права:

-   knowledge_base_id;
-   organization_id;
-   user_id;
-   access_level;
-   version.

Ограничения:

-   пользователь и база должны принадлежать одной организации;
-   один пользователь не может иметь несколько memberships в одной базе;
-   разрешены уровни VIEWER, EDITOR, OWNER.

## 4. Проведённые проверки

### Tenant isolation

Проверено разделение данных между организациями.

Результат: PASS.

Пользователь одной организации не получает данные другой организации.

### GET чужой KB

ADMIN-B пытался получить KB организации A.

Результат: PASS.

Backend отказал в доступе.

Фактический ответ: 404 Not Found.

### UPDATE собственной KB

Проверено изменение своей базы знаний.

Результат: PASS.

Изменяются данные базы и увеличивается version.

### Optimistic locking

Проверен конфликт версий.

Сценарий:

-   актуальная version = 2;
-   UPDATE с устаревшей expectedVersion.

Результат: PASS.

Backend возвращает 409 Conflict.

### UPDATE чужой KB

ADMIN-B отправил UPDATE запрос к KB организации A.

Результат: PASS.

Изменение заблокировано.

Ответ: 403 Forbidden.

Чужая база не была изменена.

## 5. Membership проверки

Проверены:

-   просмотр участников;
-   изменение доступа;
-   удаление доступа;
-   поведение после удаления membership.

Результаты: PASS.

## 6. Проверка OWNER

В миграции V38 предусмотрено значение OWNER:

VIEWER / EDITOR / OWNER

Однако автоматическое назначение создателю базы роли OWNER в миграции не
обнаружено.

Вывод:

-   схема поддерживает OWNER;
-   автоматическое создание OWNER membership должно быть реализовано
    отдельно, если это требуется бизнес-логикой.

## 7. Оставшиеся проверки

Не завершены:

-   ADD foreign membership;
-   UPDATE foreign membership;
-   DELETE foreign membership.

Цель: подтвердить невозможность изменения permissions чужой организации.

## 8. Итоговая оценка

На текущем этапе модуль обеспечивает:

-   tenant isolation;
-   защиту чтения чужих KB;
-   защиту изменения чужих KB;
-   optimistic locking;
-   resource-level permissions;
-   membership lifecycle;
-   аудитную основу.

Следующий этап:

-   документы;
-   версии документов;
-   RAG;
-   embeddings;
-   расширенные права EDITOR/OWNER.
