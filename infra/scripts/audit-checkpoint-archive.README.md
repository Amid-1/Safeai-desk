# Stage 7.5B.4.2.2 — внешний архив аудита

## Границы защиты

Независимый оператор снимает хеши через существующий `audit-checkpoint.py`, подписывает архивный конверт Ed25519 и сохраняет его в выделенном AWS S3 Object Lock (COMPLIANCE). В PostgreSQL не хранятся приватные ключи, учётные данные S3 и доверенный указатель на последний снимок. Публикация не считает запись успешной до чтения точной версии объекта и проверки байтов, срока блокировки, подписи и отсутствия других версий/маркеров удаления. Старые снимки, включая утверждённые к удалению события, остаются в WORM.

Компрометация PostgreSQL после снимка выявляется при сравнении. Не защищены события, изменённые до независимого снимка, одновременная компрометация подписанта и независимой точки доверия, недостоверные системные часы и доступность AWS. Хеши и UUID могут быть чувствительными метаданными; ограничивать доступ. Архив — доказательство, не резервная копия содержимого событий. Ограничения: 128 MiB на манифест, 2000 переходов, 50000 удалений на разрешение, 1 млн идентификаторов удаления в истории. При достижении лимита или истечении WORM любой предшествующей версии проверка блокируется; заранее планировать новый отдельно утверждённый архивный период/продление retention. Никакого автоматического сброса цепочки.

Этот выпуск **не разрешает и не выполняет SQL DELETE**, не меняет действующие правила очистки, ACL или миграции. Разрешение retention — криптографический протокол доказательства точного набора, не автоматическая интеграция с очисткой PostgreSQL. До production необходимы отдельная транзакционная интеграция с проверкой разрешения, сверка политики/возраста записей и проверка отказов. Отдельная подпись retention означает решение уполномоченного оператора; утилита не проверяет правовую обоснованность его решения и не принимает решения об удалении.

## Хранилище и управление доверием

- Отдельный AWS account вне приложения, отдельный закрытый bucket (не bucket WORM удаления документов), Versioning Enabled, Object Lock Enabled, default COMPLIANCE; тег `SafeAIPurpose=audit-checkpoint`. Block Public Access, TLS, отдельные роли и мониторинг. Приложению и мигратору доступ не предоставлять.
- IAM роли публикации: минимальные `s3:GetBucketVersioning`, `s3:GetBucketObjectLockConfiguration`, `s3:GetBucketTagging`, `s3:ListBucketVersions` на bucket; `s3:GetObjectVersion`, `s3:GetObjectRetention`, `s3:PutObject`, `s3:PutObjectRetention` только на выделенный префикс. Ограничить ListBucketVersions префиксом. Роль аудитора только чтение. Явно запретить удаление, замену настроек bucket, lifecycle и bypass при помощи независимой политики; условие записи `s3:if-none-match=*`, `s3:object-lock-mode=COMPLIANCE`, SSE-S3. Перед использованием проверить фактическую IAM политику и права тестовых ролей. AWS CLI v2 с поддержкой `put-object --if-none-match` и `--expected-bucket-owner`, OpenSSL 3.x, Python 3.10+.
- Начальный manifest SHA-256, идентификатор dataset, отпечатки корневого и независимого retention-ключа, account/bucket, последний подтверждённый receipt сохранять вне обеих систем, под отдельным доступом. Доверенный реестр должен атомарно принимать только следующий номер от текущего pin (CAS), не принимать старый pin и не позволять его перезапись оператором публикации. Реестр и его резервирование разворачиваются оператором безопасного хранения; утилита сама не является реестром. Не брать последний pin из проверяемой БД или из одного списка S3. Контроль одиночного следующего слота обнаруживает неприёмку, но не заменяет внешний реестр.
- Секретные ключи хранить offline, с правами 0600; независимые роли подписания, решения retention и публикации. Приватные ключи не передавать через аргументы значением, Git, .env или чат. При компрометации ключа остановить подпись и провести расследование; обычная ротация не отменяет компрометацию старого ключа. Retention-ключ закреплён отдельно, его замена требует новой утверждённой точки доверия, автоматической замены нет.

## Команды (вне backend)

Подставить реальные пути, UUID, отпечатки, номер аккаунта и bucket. Генезис должен быть ранее проверенным и подписанным снимком Stage 7.5B.4.2.1 без предшественника. SHA доверия получать независимо; отпечаток ключа — SHA-256 DER SPKI (`openssl pkey -pubin -in public.pem -outform DER | openssl dgst -sha256`). `ROOT_FP`, `RETENTION_FP`, `GENESIS` — только 64 hex символа, не полный вывод команды.

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk
ARCHIVE=(python3 infra/scripts/audit-checkpoint-archive.py --dataset "$DATASET" \
  --root-key-sha256 "$ROOT_FP" --retention-key-sha256 "$RETENTION_FP" \
  --genesis-manifest-sha256 "$GENESIS")
S3=(--bucket "$AUDIT_BUCKET" --account "$AUDIT_ACCOUNT" --retention-days 1095)
"${ARCHIVE[@]}" seal --manifest genesis.jsonl --private-key root.pem \
  --genesis-signature genesis.sig --genesis-sha256 "$GENESIS" --output genesis.bundle
"${ARCHIVE[@]}" publish "${S3[@]}" --record genesis.bundle --output-pin genesis.pin
```

После проверки независимый оператор принимает `genesis.pin`. Снимки экспортировать предыдущей утилитой `capture --dataset "$DATASET" --previous-sha256 "$PREVIOUS_MANIFEST_SHA" --output fresh.jsonl`, где previous — SHA **манифеста**, не конверта/pin. Сверять свежий снимок, полученный после архивации предыдущего, затем запечатать следующее состояние:

```bash
"${ARCHIVE[@]}" verify "${S3[@]}" --pin latest.pin --current fresh.jsonl --output latest.bundle
"${ARCHIVE[@]}" seal --manifest fresh.jsonl --previous latest.bundle --previous-pin latest.pin \
  --private-key root.pem --output next.bundle
"${ARCHIVE[@]}" publish "${S3[@]}" --record next.bundle --previous-pin latest.pin --output-pin next.pin
```

Для ротации вместо команды `seal` использовать `--private-key rotated.pem --old-private-key root.pem` (прочие аргументы прежние). Новый ключ подписывает конверт, старый подтверждает переход. Корневой отпечаток остаётся прежним. Принять новый pin независимо лишь после проверки; сохранить старый публичный ключ, вывести старый приватный ключ из эксплуатации по утверждённой процедуре. При обрыве публикации после PUT команда `recover` принимает те же аргументы, что `publish`, плюс точный `--version-id`, и сверяет байты и оставшийся срок. Если срок недостаточен или версия не совпадает — остановиться, не переписывать объект.

## Защищённый retention (только доказательство)

Внешний уполномоченный оператор сверяет policy, сроки и архив, получает свежий полный `before.jsonl` с previous SHA последнего манифеста. `items.json` — отсортированный массив `[ ["event-uuid", "event-sha256"], ... ]`, только ранее архивированные события. `POLICY_SHA` — независимый хеш утверждённого решения. TTL максимум 7 суток.

```bash
"${ARCHIVE[@]}" permit --manifest before.jsonl --previous latest.bundle --previous-pin latest.pin \
  --private-key retention.pem --items items.json --policy-evidence-sha256 "$POLICY_SHA" \
  --expires-at "$EXPIRES_UTC" --output permit.bundle
"${ARCHIVE[@]}" publish "${S3[@]}" --record permit.bundle --previous-pin latest.pin --output-pin permit.pin
```

Только после независимого утверждения точной WORM-версии разрешения и будущей интеграции в транзакционную очистку допускается рассматривать удаление. Для последующей проверки: получить `after.jsonl` после сохранения разрешения (минимум одна секунда, до expiry), с previous SHA последнего принятого манифеста. `seal --manifest after.jsonl --previous latest.bundle --previous-pin latest.pin --permit permit.bundle --permit-pin permit.pin --private-key current.pem --output next.bundle`, затем `publish` как выше. Утилита отклоняет изменённые записи, лишние или недостающие удаления, исчезновение новых записей из before, повторное появление удалённых ID и переход без разрешения при наличии разрешённого слота. При незавершённом/истёкшем разрешении остановиться; разрешение нельзя перезаписать; нужен контролируемый разбор инцидента.

## Приёмка

Модульные тесты с настоящим OpenSSL и имитацией S3: `.github/scripts/test_audit_checkpoint_archive.py`. Реальная проверка S3 — `infra/tests/acceptance/audit_archive_probe.py`: только отдельный staging bucket с тегом `SafeAITestOnly=true`, указанным owner и согласованным retention не менее суток. Создаёт четыре синтетических заблокированных объекта, не удаляет их, потребует явное подтверждение `SAFEAI_AUDIT_ARCHIVE_STAGING_APPROVAL=I_ACCEPT_IMMUTABLE_STAGING_OBJECTS`. Не запускать против production. Отдельно подтвердить отказ изменения/удаления версии, запрет изменения конфигурации, политики IAM и независимый реестр; без этих проверок, проверки восстановления и полного CI — NO GO.
