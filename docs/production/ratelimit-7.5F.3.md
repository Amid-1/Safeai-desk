# RateLimit — HMAC-эпоха, Redis и Sentinel (7.5F.3)

## Инварианты

- Production использует обязательный fail-closed барьер Redis Lua. Отсутствующий, повреждённый, временный или не совпадающий с runtime барьер не разрешает запись счётчиков. Клиентское приложение не создаёт и не восстанавливает барьер.
- Барьер связан с HMAC-секретом, нормализованным префиксом, версией ключа, `run_id` и `master_replid` primary. Смена версии без координации не обнуляет лимиты.
- Барьер проверяется в одной операции со всеми изменениями существующего fixed-window limiter, включая компенсации и обе фазы refresh. V1–V101 неизменны. Лимиты F1 не меняются.
- Production Redis Cluster здесь намеренно отклоняется: одна атомарная глобальная проверка между разными hash slots невозможна. Standalone и Sentinel с одним primary поддерживаются; существующий standalone/cluster контракт без барьера остаётся только для nonproduction. Новые самостоятельные Redis-скрипты (в том числе будущий RPM/TPM limiter) должны участвовать в том же протоколе до приёмки.
- Redis AOF: `appendonly yes`, `appendfsync always`, `no-appendfsync-on-rewrite no`, `aof-load-truncated no`, `noeviction`, ограничение памяти. AOF не заменяет резервирование данных, проверку диска и восстановления.

## Действия оператора

Все примеры выполняются из корня репозитория. Не передавайте секреты значениями аргументов. `--secret-file` — путь к файлу с точными UTF-8 байтами HMAC (не менее 32 байт, без завершающей новой строки). Используйте действующие префикс и версию. Не выводите содержимое файлов или Redis credentials. Для local используйте собственный файл в `.local-secrets/`, для production — защищённое хранилище и фактический файл секрета развёртывания.

Пример оболочки для local Redis:

```bash
epoch() {
  python3 infra/scripts/rate-limit-epoch.py "$@" --redis-cli \
    docker compose --env-file backend/.env -f infra/docker-compose.local.yml \
    exec -T redis sh -eu -c 'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli "$@"' sh
}
epoch status
```

Для production замените Compose options на параметры фактического развёртывания и используйте `sh -eu -c 'export REDISCLI_AUTH="$(tr -d "\r\n" < /run/secrets/redis_password)"; exec redis-cli "$@"' sh`. При Sentinel подключайте административный redis-cli к подтверждённому текущему primary (с TLS, если предусмотрен транспортом). Не подставляйте секрет в аргумент `-a`. `status` проверяет topology/state, но не совпадение HMAC с конфигурацией backend.

### Первое внедрение

1. Остановить все экземпляры backend, которые не знают об epoch fence, и исключить их автоматический перезапуск. Не допускать смешивания старого и нового runtime.
2. Проверить Redis память, AOF, свободное место, синхронизацию времени и резервное копирование. Для local `bash infra/scripts/check-redis-durability.sh --env-file backend/.env -f infra/docker-compose.local.yml`. Старая local конфигурация была без постоянного тома: пересоздание такого контейнера может потерять Redis-состояние; не делать это без отдельного плана и подтверждения.
3. Только на действительно новом Redis, никогда не обслуживавшем запросы, допустима инициализация:

```bash
epoch initialize-empty --prefix safeai:local --version v1 \
  --secret-file backend/.local-secrets/ratelimit-v1 \
  --confirm FIRST_INSTALLATION_NO_PRIOR_TRAFFIC
```

Для существующего Redis без барьера (или отсутствующего барьера после инцидента) используется `begin-missing` с теми же аргументами, но подтверждением `ALL_LEGACY_BACKENDS_STOPPED`; ждать полный drain прежде чем активировать. Не очищать Redis и не удалять счётчики для ускорения процедуры. Если существующий барьер повреждён, операция отклоняется: требуется расследование и доказуемое восстановление либо изолированное восстановление с полным drain; не перезаписывать его вручную в рамках штатного runbook.

4. В local барьер можно включить явно `SAFEAI_RATE_LIMIT_REDIS_EPOCH_FENCE_ENABLED=true` в окружении backend. В prod он обязателен независимо от этого флага. Проверить запросами из двух backend-инстансов (в тестовой среде) совпадение эпохи и сохранение ограничений.

### Ротация HMAC (консервативная, с остановкой admission)

```bash
epoch pause --prefix safeai:local --version v1 \
  --secret-file backend/.local-secrets/ratelimit-v1 \
  --confirm ALL_LEGACY_BACKENDS_STOPPED
epoch status
```

При `PAUSED:<ms>` допуск закрыт. Минимальная пауза — 24 часа и 10 секунд (максимальное поддерживаемое окно плюс margin refresh). Не сокращать её, не менять время Redis, не удалять ключи. При смене primary во время паузы повторить pause на новом подтверждённом primary и начать полный drain заново. После `PAUSED:0`, проверки отсутствия старых процессов и безопасного primary:

```bash
epoch activate --prefix safeai:local --version v1 \
  --secret-file backend/.local-secrets/ratelimit-v1 \
  --new-prefix safeai:local --new-version v2 \
  --new-secret-file backend/.local-secrets/ratelimit-v2 \
  --confirm DRAIN_AND_PRIMARY_VERIFIED
```

Запустить backend с новой конфигурацией. Старые экземпляры даже при ошибочном запуске не проходят барьер. Удаление старого секрета из хранилища — только после принятия новой версии; rollback не означает снятие барьера. Для обратной смены повторить полный цикл. Бесшовная ротация с dual-read/dual-write не заявляется.

### Sentinel / crash recovery

Репликация Sentinel асинхронна; наличие AOF и ответ WAIT не доказывают отсутствие потерянных записей. Новый primary блокирует допуск до подтверждения оператора. Перед rebind необходимо физически/сетевым способом исключить запись на старый primary, убедиться в уникальности авторитетного primary, проверить все активные счётчики и reservation/receipt sidecars по достоверному источнику, TTL, стабильность времени и состояние AOF. Без полного доказательства непрерывности выполнить pause и полный drain. Для утраченного барьера — begin-missing. Redis Sentinel не является consensus fencing, поэтому не считать `PRIMARY_CHANGED` автоматическим доказательством отсутствия split brain.

```bash
epoch rebind-preserved --prefix safeai:local --version v1 \
  --secret-file backend/.local-secrets/ratelimit-v1 \
  --confirm OLD_PRIMARY_FENCED_AND_COUNTERS_PROVEN
```

Это разрешает только ту же HMAC-эпоху. При неопределённом результате команды не повторять её автоматически: сначала прочитать status, сверить состояние, расследовать разрыв. Для будущего production Sentinel deployment: отдельные failure domains, аутентификация/TLS, AOF всех data nodes, `min-replicas-to-write` и `min-replicas-max-lag` по результатам нагрузочной и аварийной приёмки, независимое fencing бывшего primary. Существующий Compose остаётся standalone, не выдаётся за HA.

## Приёмка

Обязательны реальные Redis/Testcontainers тесты ротации, рестарта, прежних счётчиков, отказов, full Maven, ACL, performance, Nginx; отдельная изолированная Sentinel-топология с пустой тестовой БД и фактическим `SENTINEL FAILOVER`. Тесты не запускать на production: они изменяют топологию и тестовые данные. Операционные алерты, долгий chaos/нагрузочный прогон и общая deployment-приёмка выполняются в F4–F6/7.5C. Гарантия доступности без паузы не заявляется.
