# SafeAI-Desk: публикация, восстановление и Production-допуск

Этот порядок относится к PR №19 и последующим релизам. Автоматический
`docker compose up` нового PostgreSQL поверх существующего PGDATA запрещён.

## Где мы сейчас

- В PR №19 проверены CI, Trivy, Java style, JaCoCo и Vitest.
- Реальная БД **не восстанавливалась**. Отсутствуют закрытые доказательства
  совместимости исходных collation, Flyway checksums и data/WORM.
- Пока PR не слит, publish workflow на `main` использовать нельзя.
- `production-release.yml` **только публикует** образы. Релиз не деплоит.
- `production-deploy.yml` **только деплоит** ранее опубликованные образы.
  Он не запускает `docker build` и не создаёт новые image digests.

## Этап 1. Изолированное предварительное восстановление

До слияния PR можно проверить логику восстановления на проверенном локальном
образе, собранном из тех же исходников. Но эта предварительная проверка не
подтверждает финальный GHCR digest для Production.

На **доверенном изолированном** хосте с Docker, age и Python 3 сохранить:

- существующий зашифрованный backup `*.dump.age` и `.sha256` рядом;
- `age` identity отдельно, с ограниченными правами;
- исходные настройки БД (см. команду ниже);
- точные количества строк из *того же snapshot*, что и `pg_dump`;
- подтверждение источника, времени backup, внешних S3/WORM-инвариантов.

Пример получения настроек БД на **источнике**, только SELECT:

```bash
psql -X -At -F $'\t' -v ON_ERROR_STOP=1 \
  -f infra/postgres/source-db-settings.sql \
  > /secure/source-db-settings.tsv
```

Переменные подключения и способ доступа определяет доверенный оператор.
Не запускайте это под паролем, размещённым в командной строке. Если на
источнике идёт запись, обычный отдельный подсчёт строк не равен snapshot
дампа. Используйте согласованный snapshot или окно без записи.

## Этап 2. Публикация immutable-образов на main, без деплоя

Только после принятого review и разрешённого слияния PR выполнить вручную
GitHub Actions → `Production release` → `Run workflow`, branch `main`.
Все обязательные CI должны быть зелёными именно на HEAD `main`.

Workflow проверяет CI, собирает 3 образа, выполняет Trivy HIGH/CRITICAL,
проверку unfixed CRITICAL, формирует SBOM и 6 attestations, публикует
образы в GHCR, проверяет подписи и сохраняет один JSON-манифест.

Запишите **run ID** успешной публикации. Из Artifacts скачайте
`safeai-published-images-<SHA>`. В манифесте находятся точные digest.

Повторная публикация даже того же коммита может изменить digest. Поэтому
deploy никогда не пересобирает образы и принимает **конкретный run ID**.

## Этап 3. Реальная изолированная репетиция именно опубликованного образа

На отдельном хосте, **не на работающем Production volume**, с новой
директорией отчёта:

```bash
bash infra/postgres/rehearse-restore.sh \
  --image 'ghcr.io/OWNER/safeai-postgres@sha256:EXACT_DIGEST' \
  --backup '/secure/backup.dump.age' \
  --identity '/secure/age-identity.txt' \
  --source-db-settings '/secure/source-db-settings.tsv' \
  --source-counts '/secure/source-row-counts.tsv' \
  --report-dir '/secure/new-restore-report'
```

Это лишь **технический тест**. Отсутствующий source counts или locale
считается пробелом доказательств, а не успехом. Проверьте:

1. SHA-256 дампа, успешное дешифрование и целостность `pg_restore`.
2. Encoding, LC_COLLATE, LC_CTYPE, provider, ICU locale **источника и цели**.
3. PostgreSQL 16, pgvector, в том числе HNSW и реальные RAG-запросы.
4. Индексы, ограничения, внешние ключи, ошибки restore и бизнес-инварианты.
5. **Настоящий Flyway validate** приложения, а не только номера миграций.
6. SQL-количество строк из согласованного snapshot источника и цели.
7. S3 WORM, tombstone, retention/legal hold, инвентарь удалённых объектов.
8. `cleanup-status.txt`: временные container и volume удалены.

`datcollversion` может отличаться после перехода дистрибутива. При
логическом restore индексы создаются заново, но семантические изменения
сортировки и уникальности всё равно требуют отдельного DBA review.
Для физического PGDATA без логического переноса действуют иные риски.
`ALTER COLLATION ... REFRESH VERSION` не заменяет `REINDEX`.

## Этап 4. Контролируемый перенос рабочей базы

**ВАЖНО:** текущий `safeai_postgres_data` содержит физические файлы старого
PostgreSQL. Проверка логического restore не является разрешением на
замену образа, использующего этот физический volume.

`deploy-prod.sh` теперь блокирует новый image digest при обнаружении
существующего PostgreSQL container, а также неизвестный существующий
volume без контейнера. Для перехода требуется **отдельный runbook**:
окно без записи, финальный консистентный backup, restore в новый volume,
проверки, подготовка сетевых ролей и credentials, атомарно согласованное
переключение, мониторинг, план отката. Не выполнять это автоматически
из GitHub Actions до завершения проверки инфраструктуры и реальных данных.

## Этап 5. Финальный Production-допуск

После подписанного restore/DBA/S3 review владелец защищённого GitHub
Environment `production` задаёт переменную:

`SAFEAI_NOBLE_RESTORE_APPROVED_IMAGE` = **точный PostgreSQL image@sha256**.

Только после **отдельного** подтверждения завершённого переноса volume
можно вручную запустить `Production deploy` на HEAD `main` с точным
`publish_run_id`. Он проверяет:

- успешность исходного publish run на точно этом SHA и его JSON-манифест;
- криптографические attestations для **всех трёх** образов;
- соответствие одобренного PostgreSQL digest опубликованному;
- актуальность HEAD `main`, защищённое Environment и SSH host key;
- отказ при смене образа над существующим PostgreSQL volume.

Ни один из этих пунктов не считается выполненным без настоящего backup,
проверки S3/WORM и согласованного плана физического cutover.
