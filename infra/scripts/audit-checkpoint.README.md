# Stage 7.5B.4.2.1 — независимый контрольный снимок аудита

Первый шаг криптографической целостности: SHA-256 каждой неизменяемой строки
`audit_events`, подписанный Ed25519 снимок, связь со снимком-предшественником
и обязательный независимый fingerprint последнего принятого снимка. Без
миграций, без изменения retention, без новых прав приложения.

## Модель доверия и ограничения

- Снимок охватывает все 11 полей V94 `audit_events`, в том числе nullable
  actor snapshots, `details`, `created_at`, `correlation_id`. Live user links
  и ещё не доставленные outbox-события не входят в этот формат.
- Снимок содержит UUID и SHA-256, но не тексты событий. Хеши и UUID всё равно
  считать чувствительной информацией. Экспорт исходных строк проходит по
  TLS в память доверенного процесса; не сохранять его на диск и не логировать.
- Подписанные снимки, подписи, доверенный публичный ключ, dataset UUID и
  последний принятый SHA-256 хранятся за пределами БД и её администраторов
  (например, отдельный защищённый immutable storage с независимым IAM).
  Приватный ключ — на физически или административно отдельном signer host.
  Не передавать его в backend, PostgreSQL, CI, архивы или Git.
- Первый снимок фиксирует только исходное состояние, а не доказывает его
  историческую подлинность. Новые события остаются неподтверждёнными до
  следующего принятого снимка; компрометированный источник может скрыть
  новые события или подменить ответы SELECT. Требуется независимая проверка
  источника/резервной копии и последующее внедрение внешней доставки событий.
- Сравнение обнаруживает исчезновение/изменение ранее подписанных событий.
  Любое удаление, включая законный retention, обнаруживается: подпись нового
  снимка блокируется. Не отключать retention и не создавать новый genesis
  для обхода. До production необходим отдельный протокол подписанного
  разрешённого удаления, согласованный с требованиями хранения данных.
- Нет непрерывного обнаружения, внешнего WORM-хранилища, ротации ключей,
  автоматических оповещений или подтверждения доставки в этом выпуске.
  До их реализации/приёмки это не полная защита от компрометации PostgreSQL.
- Формат зависит от PostgreSQL 16 JSONB text и UTF-8; при изменении схемы
  или major-версии необходим отдельный reviewed format. Полный экспорт
  ограничен 128 MiB манифеста; для большого журнала измерить нагрузку, время
  и память, проводить чтение с отдельной доверенной реплики/снимка. Блокировки
  ограничены 5 секундами, SQL statement timeout — 10 минут на запрос.

## Подготовка (только после приёмки на отдельном стенде)

DBA создаёт отдельный логин со своим паролем, не членом других ролей.
Выполнять в целевой БД, не в `safeai_app`. Проверить сетевой доступ и
эффективные привилегии; не добавлять роль в runtime ACL или миграцию.

```sql
CREATE ROLE safeai_audit_verifier LOGIN NOSUPERUSER NOCREATEROLE
    NOCREATEDB NOREPLICATION NOBYPASSRLS NOINHERIT;
GRANT USAGE ON SCHEMA public TO safeai_audit_verifier;
GRANT SELECT ON public.audit_events TO safeai_audit_verifier;
ALTER ROLE safeai_audit_verifier SET default_transaction_read_only = on;
```

В `psql` задать пароль через `\password safeai_audit_verifier`; если
CONNECT отозван у PUBLIC, DBA явно разрешает CONNECT только в нужной БД.
Доверенный клиент `psql` должен подключаться напрямую с TLS `verify-full`,
проверяемым сертификатом, используя защищённый PGPASSFILE (0600) либо
утверждённый secret manager. Все параметры подключения через PGSERVICE
и стандартный libpq, без паролей в аргументах. Пример ниже предполагает
заранее настроенный сервис `safeai-audit-verifier`. Не применять реальные
production-ключи или роли в тестах.

## Первое использование

На независимом signer host (OpenSSL 3.x, Python 3):

```bash
umask 077
openssl genpkey -algorithm ED25519 -out checkpoint-key.pem
openssl pkey -in checkpoint-key.pem -pubout -out checkpoint-public.pem
```

Получить и независимо зафиксировать один постоянный UUID набора данных
(`uuidgen`), обозначенный ниже `$DATASET`. Независимо проверить первоначальный
снимок БД и только затем создать первую подпись. На читателе:

```bash
PGSERVICE=safeai-audit-verifier python3 infra/scripts/audit-checkpoint.py capture \
    --dataset "$DATASET" --output first.jsonl
```

Передать только `first.jsonl` на signer host. Там:

```bash
python3 audit-checkpoint.py sign --dataset "$DATASET" --genesis \
    --manifest first.jsonl --signature first.sig \
    --private-key checkpoint-key.pem --public-key checkpoint-public.pem
```

Сохранить `first.jsonl`, `first.sig` и SHA-256, выведенный командой, в
защищённом внешнем хранилище. `$PIN` далее берётся из этого хранилища,
не вычисляется по потенциально подменённому локальному файлу.

## Следующая проверка

```bash
PGSERVICE=safeai-audit-verifier python3 infra/scripts/audit-checkpoint.py capture \
    --dataset "$DATASET" --previous-sha256 "$PIN" --output next.jsonl
python3 infra/scripts/audit-checkpoint.py verify --dataset "$DATASET" \
    --checkpoint first.jsonl --signature first.sig --public-key checkpoint-public.pem \
    --expected-sha256 "$PIN" --current next.jsonl
```

Коды: 0 — ранее закреплённые события совпадают (new_unanchored не подтверждены),
1 — невалидная подпись, pin, контекст, ошибка ввода/экспорта, 2 — исчезновение
или изменение. При 1/2 сохранять доказательства и расследовать; не подписывать.
После независимой проверки новых событий на signer host:

```bash
python3 audit-checkpoint.py sign --dataset "$DATASET" \
    --manifest next.jsonl --signature next.sig --private-key checkpoint-key.pem \
    --public-key checkpoint-public.pem --previous first.jsonl \
    --previous-signature first.sig --expected-previous-sha256 "$PIN"
```

Операции принятия снимка сериализуются внешним оператором/хранилищем:
нельзя принять два дочерних снимка от одного pin; обновлять указатель последнего
pin только после надёжного сохранения снимка и подписи. Иначе возможна развилка.
Отсутствие подписи, ошибка захвата, несовпадение pin или неопределённость
состояния — NO GO, не зелёный результат. Файлы не перезаписываются.
