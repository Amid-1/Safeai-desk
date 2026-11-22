# 7.5G.3-Fix3 — Chat & Knowledge Lifecycle

SUPER_ADMIN → Обзор → Хранение и удаление данных. Общая политика
платформы: чаты и Knowledge можно приостанавливать независимо. Базы и
документы используют одну политику, чтобы удаление базы не обходило запрет.
GET/PUT /api/admin/retention; PUT принимает chatAllowed, knowledgeAllowed,
expectedVersion. Сервер проверяет роль в PostgreSQL, версию и готовность
при возобновлении. Изменения аудируются (RETENTION_POLICY_CHANGED).

V102 сохраняет прежний допуск по умолчанию, не включает флаги инфраструктуры.
Приостановка действует только на новые запросы; уже принятые операции
продолжаются. Статус конфигурации не является live-health исполнителя.

## Однократная подготовка локального контура

Остановите backend, примените V102 штатным мигратором и обновлённый runtime
ACL. Для локальной Docker DB предусмотрен infra/scripts/apply-local-runtime-acl.sh.
Проверьте очередь до запуска исполнителей:

```sql
SELECT rolname, rolcanlogin, rolsuper FROM pg_roles
WHERE rolname IN ('safeai_chat_erasure_worker','safeai_knowledge_erasure_worker');
SELECT state, count(*) FROM public.chat_erasure_operations_v86 GROUP BY state;
SELECT state, count(*) FROM public.knowledge_document_deletions_v78 GROUP BY state;
SELECT state, count(*) FROM public.knowledge_base_deletions_v82 GROUP BY state;
```

Если есть ранее принятые операции, согласуйте их продолжение: worker
может выполнить их даже при приостановленном допуске. Роли должны быть
LOGIN, NOSUPERUSER с существующими ограниченными правами. Назначьте
отдельные пароли через административный psql (команда \password для каждой
роли). Не используйте пароль приложения или postgres, не храните в Git.

### Чаты

После проверки удержания и утверждения локальной политики настройте
однократно Run Configuration IntelliJ: SPRING_APPLICATION_JSON, секрет
CHAT_ERASURE_WORKER_PASSWORD. Только для изолированного тестового контура:

```json
{"safeai":{"chat":{"erasure":{"accept-enabled":true,"executor-enabled":true,"retention-approved":true,"worker-jdbc-url":"${spring.datasource.url}","worker-username":"safeai_chat_erasure_worker","worker-password":"${CHAT_ERASURE_WORKER_PASSWORD}"}}}}
```

Перезапустите backend. Далее управляйте допуском из UI без YAML и
перезапуска. ADMIN удаляет содержимое своего архивного чата при
разрешении сервера. Неизвестный статус отправки требует проверки или
повтора прежней операции, а не принудительного обхода.

### Базы знаний и документы

Нужны S3 и отдельный бакет журнала с версионированием, Object Lock
COMPLIANCE и default retention. Не переключайте S3 поверх существующих
локальных файлов: требуется миграция/сверка либо отдельный чистый тестовый
контур. Production требует существующих release-evidence, restore,
provider/derived-data проверок. Не подменяйте WORM фиктивными флагами.

После подготовки S3 и роли добавьте раздел safeai.knowledge в ту же
SPRING_APPLICATION_JSON, сохраняя chat, и задайте секрет
KNOWLEDGE_ERASURE_WORKER_PASSWORD. Пример для тестового контура:

```json
{"safeai":{"knowledge":{"deletion":{"accept-enabled":true,"executor-enabled":true,"worker-jdbc-url":"${spring.datasource.url}","worker-username":"safeai_knowledge_erasure_worker","worker-password":"${KNOWLEDGE_ERASURE_WORKER_PASSWORD}"},"base-deletion":{"accept-enabled":true,"executor-enabled":true},"erasure-journal":{"enabled":true,"object-lock-required":true,"bucket":"safeai-erasure-journal","min-retention":"P7D"}}}}
```

Конфигурацию storage берите из существующего
application-prod-knowledge.example.yml и штатной инфраструктуры. Нужен
реально созданный отдельный journal bucket и согласованный срок.
После перезапуска управляйте допуском из UI.

### Пользователи и организации

Необратимое удаление пользователя уже реализовано. SUPER_ADMIN выбирает
отключённую учётную запись, сервер проверяет срок удержания (по умолчанию
7 дней), защищённые роли, сеансы и связи; требуется подтверждение email.
Не сокращайте срок ради обхода защиты.

Организация: только отключение с проверкой последствий. Удаление требует
отдельного backend workflow и плана зависимостей. UI не обещает
несуществующую функцию.

## За пределами этапа

Автоматическое стирание по сроку, политика каждой организации,
общая live-health/очередь всех worker replicas, массовое удаление и
удаление организаций — отдельные серверные задачи, не заглушки UI.
