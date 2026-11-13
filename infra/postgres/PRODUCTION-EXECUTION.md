# SafeAI-Desk: Production release и переход PostgreSQL на Ubuntu Noble

Это регламент **реального** выпуска. Наличие зелёного CI или файла отчёта
не является разрешением менять рабочую БД.

## Текущие правила

- `production-release.yml` публикует три immutable image, SBOM и attestations.
- `production-deploy.yml` использует конкретный успешный `publish_run_id`.
- `deploy-prod.sh` отказывает при смене образа поверх старого PGDATA.
- `rehearse-restore.sh` восстанавливает backup в одноразовом volume.
- Существующий Compose привязан к имени volume `safeai_postgres_data`.
- Автоматического безопасного cutover на новый production volume **пока нет**.
- Workflow должен оставаться закрытым до готовности отдельной процедуры cutover.

## 0. Подтверждение исходного состояния

На Mac, в репозитории:

```bash
git status --short
git rev-parse HEAD
git branch --show-current
```

На доверенном сервере (только чтение Docker-метаданных):

```bash
bash infra/postgres/production-host-inventory.sh --read-only
```

Запиши вне Git: адрес сервера, Docker context, image текущей БД,
текущую структуру volumes, версии PostgreSQL и расширений, размер БД,
последний успешный backup, RPO и окно обслуживания. Не отправляй сюда
имена секретов с их содержимым, дампы, ключи или `.env.prod`.

Если реального Production ещё нет, этот факт меняет процедуру:
**новая установка** не требует миграции существующего PGDATA. Однако
обязательны проверка настройки, новый backup и испытание восстановления.

## 1. Проверка существующих backup и последняя резервная копия

На production **без отключения приложения** установи, где реально находятся:

- зашифрованный `*.dump.age` и соответствующий `*.sha256`;
- private `age` identity, доступный только оператору;
- копия глобальных ролей и критичных PostgreSQL-настроек;
- версия источника, параметры locale, provider и extensions;
- актуальный S3/WORM-журнал, legal hold, retention и tombstones.

`infra/scripts/backup-postgres.sh` делает `pg_dump -Fc`, шифрует через age,
записывает SHA-256 и копирует в удалённое хранилище через rclone.
Проверь выполнение backup, удалённую копию и процедуру дешифровки.
Запуск backup на Production требует проверки объёма и нагрузки.

`source-db-settings.sql` собирает параметры исходной БД.
`source-row-counts.sql` собирает точные количества строк.
Важно: подсчёт строк и `pg_dump` должны относиться **к одному snapshot**.
Два независимых запроса при продолжающихся записях не дают такого доказательства.
Для финального cutover планируй остановку записей или coordinated snapshot.

## 2. Проверка PR и публикация immutable images

В GitHub PR №19 должны быть успешны **все** обязательные проверки.
Перед слиянием проверь, что миграционные скрипты Flyway не переписаны,
обновлённые workflows прошли actionlint, а исходники прошли review.

Если остаётся требование испытать backup **до** merge, проведи предварительную
репетицию на локально собранном candidate image. Это не финальный digest.

После разрешённого merge в `main` и зелёных CI на **HEAD main**:

1. GitHub → Actions → `Production release` → Run workflow → `main`.
2. Дождись SUCCESS: Trivy, SBOM, provenance, GHCR и manifest.
3. Зафиксируй номер run (`publish_run_id`) и SHA `main`.
4. Скачай артефакт `safeai-published-images-<SHA>`.
5. Проверь digest трёх образов и provenance в успешном publish run.
6. **Не запускай `Production deploy` сейчас.** Публикация не деплоит.

Повторная публикация может создать другие digest. В деплой должен идти
именно манифест проверенной публикации, а не последний tag в GHCR.

## 3. Изолированная репетиция на реальном backup

На отдельном доверенном Docker-хосте, не на Production volume:

```bash
bash infra/postgres/rehearse-restore.sh \
  --image 'ghcr.io/OWNER/safeai-postgres@sha256:EXACT_DIGEST' \
  --backup '/secure/backup.dump.age' \
  --identity '/secure/age-identity.txt' \
  --source-db-settings '/secure/source-db-settings.tsv' \
  --source-counts '/secure/source-row-counts.tsv' \
  --report-dir '/secure/new-restore-report'
```

Значения примера заменить на проверенные локальные пути и **64-hex digest**.
Скрипт не принимает обычный tag. Результаты храни вне Git.

Проверяй вывод и `cleanup-status.txt`: удалены только временные ресурсы.
При ошибке очистки считай запуск проваленным и проверь оставшийся volume.
Не очищай `safeai_postgres_data` и не запускай `docker compose down -v`.

Технический отчёт репетиции **не** заменяет дополнительные проверки:

- настоящий `Flyway validate` тем же backend и драйвером;
- проверка `pgvector`, HNSW и запросов RAG с реальными данными;
- исключения индексов, foreign keys, CHECK и uniqueness;
- сравнение источника и восстановленной копии из одного snapshot;
- корректность collation/ICU/glibc и порядка сортировки;
- read-only сверка внешнего S3 WORM-журнала и истории удалений;
- прохождение smoke/E2E без записи в рабочие внешние сервисы;
- подготовленный и испытанный rollback со временем восстановления.

**Нельзя** вызывать `REFRESH VERSION` вместо пересоздания затронутых
индексов. При логическом restore индексы создаются заново, но изменения
семантики сортировки всё равно проверяются DBA, особенно уникальные ключи.

## 4. Отдельный cutover на НОВЫЙ volume

Это отдельная контролируемая операция с окном без записи.
Текущий `docker-compose.yml` фиксирует имя `safeai_postgres_data`.
**Существующий volume нельзя повторно использовать под Noble без плана.**

До выполнения cutover нужен проверенный и прошедший тест на копии сценарий,
который делает всё следующее без удаления старого volume:

1. Переводит приложение и workers в maintenance/read-only; ждёт завершения jobs.
2. Записывает независимые WORM/tombstone checkpoints и source snapshot.
3. Выполняет последний консистентный `pg_dump -Fc` + backup глобальных ролей.
4. Создаёт новый production volume и запускает там **тот же** digest Noble.
5. Восстанавливает базу, роли и права, затем проверяет Flyway и инварианты.
6. Устанавливает новые креды, Compose volume mapping и сетевые проверки.
7. Сначала проверяет cutover с заблокированным внешним трафиком.
8. Переключает на новую БД только после ручного GO с журналом изменений.
9. Проверяет write/read, наблюдаемость, lag/ошибки, S3 и RAG.
10. Сохраняет **старый** volume неизменённым до конца rollback window.

Нельзя объявлять это завершённым на основании одного `rehearse-restore.sh`:
он по назначению **удаляет** свой временный volume после проверки.
Не запускай `infra/scripts/restore-postgres.sh` на действующем контуре
для репетиции: он разрушительный, хотя и оставляет приложение offline.

При ошибке до открытия трафика: остаться в maintenance, вернуться на
неизменённый старый контур по проверенному rollback runbook.
После новых записей на Noble возврат на старую БД потребует анализа
расхождения данных: простое переключение обратно может потерять записи.

## 5. Финальный допуск и Production deploy

Запрещено продолжать при любой неопределённости в предыдущих пунктах.
Отдельно нужны: DBA review, owner приложения, независимая S3/WORM сверка,
подтверждение rollback, артефакты технического restore и согласование RPO/RTO.

В защищённом GitHub Environment `production` установить только после
review переменную `SAFEAI_NOBLE_RESTORE_APPROVED_IMAGE` равной **точному**
`ghcr.io/OWNER/safeai-postgres@sha256:<64hex>` проверенного image.

Лишь после проверенного cutover на новый production volume разрешён запуск:

GitHub → Actions → `Production deploy` → branch `main` → `publish_run_id`.

Workflow повторно проверяет SHA, manifest, происхождение образов и digest.
SSH-сценарий и `deploy-prod.sh` должны отказать, если существует контейнер
со старым PostgreSQL image или неизвестный существующий volume.

Новые security-проверки нельзя выключать ради зелёного результата.

## 6. Необходимые доказательства GO

| Gate | Минимальное доказательство | Сейчас |
| --- | --- | --- |
| PR CI | Все восемь workflow SUCCESS | Проверено для 7de7995 |
| Publish | Успешный run на SHA `main`, 3 digest, SBOM | Нет |
| Backup | Реальный age, SHA-256, проверено дешифрование | Нет |
| Restore | Отчёт, cleanup VERIFIED, matching source | Нет |
| DB | Flyway validate, индексная и collation сверка | Нет |
| RAG/S3 | Тесты и внешний WORM/tombstone audit | Нет |
| Cutover | Новый production volume и rollback | Нет |
| Approval | Защищённый Environment, reviewed exact digest | Нет |

Состояния «Нет» означают **BLOCKED**, а не «по умолчанию OK».
Хранить чувствительные доказательства только в доверенном закрытом контуре.
