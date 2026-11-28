# RateLimit — эксплуатационный мониторинг (7.5F.4)

## Границы ответственности

Метрики описывают фактически выполненные проверки login, refresh и `ai_message`
и состояние Redis, наблюдаемое каждым backend. Они не заменяют счётчики допуска,
финансовый ledger, аудит или решение Lua. `ai_message` — логические проверки сообщений,
не физические вызовы AI и не метрика TPM/RPM. Наблюдатель не инициализирует epoch,
не очищает, не продлевает и не ремонтирует ключи. Алерт не даёт права на обход допуска.

Каждые 30 секунд, после начального ожидания 10 секунд, backend запрашивает один
read-only Lua snapshot с Redis. Команда использует настроенный Redis command timeout.
Prometheus scrape читает только снимок в памяти JVM. Ошибка Redis, неправильная
структура данных или неподдерживаемая топология дают `healthy=0`, числовые значения
`NaN`, состояние `unavailable`; время последнего успешного снимка не обновляется.
В production epoch обязателен независимо от локального флага. Redis Cluster не
является поддерживаемой топологией epoch fence; монитор не делает обход Cluster/Sentinel.

## Метрики

| Имя Prometheus | Значение |
| --- | --- |
| `safeai_rate_limit_allowed_total` | Разрешённые логические проверки, `type` |
| `safeai_rate_limit_rejected_total` | Отказы 429 по `type,dimension`; capacity — отдельная причина |
| `safeai_rate_limit_unavailable_total` | Ошибки Redis-проверок/компенсации (не исключительно число HTTP 503), `type` |
| `safeai_rate_limit_audit_publish_failed_total` | Сбои публикации уведомления об ограничении, `type` |
| `safeai_rate_limit_redis_duration_seconds_bucket` | Гистограмма Redis-операции, `type,operation,outcome,le` |
| `safeai_rate_limit_redis_observation_healthy` | 1 = успешно получен и разобран снимок, НЕ гарантия пригодности Redis |
| `safeai_rate_limit_redis_last_observation_timestamp` | Unix seconds последней успешной проверки; 0 до первого успеха |
| `safeai_rate_limit_redis_memory_accounted_bytes` | `max(0,used_memory-mem_not_counted_for_evict)` |
| `safeai_rate_limit_redis_memory_max_bytes` | `maxmemory`; 0 — ограничение отсутствует |
| `safeai_rate_limit_redis_memory_rss_bytes` | RSS Redis; не равно cgroup usage |
| `safeai_rate_limit_redis_noeviction` | 1 только при `noeviction` |
| `safeai_rate_limit_redis_evicted_keys` | Число evictions процесса Redis; gauge, сбрасывается после рестарта Redis |
| `safeai_rate_limit_redis_aof_healthy` | 1 только если AOF включён, запись и последний rewrite успешны, загрузка завершена |
| `safeai_rate_limit_redis_primary` | 1 только для primary |
| `safeai_rate_limit_redis_epoch_required` | 1 если epoch обязательный |
| `safeai_rate_limit_redis_epoch_state` | One-hot: active, disabled, missing, invalid, paused, mismatch, primary_changed, unavailable |
| `safeai_rate_limit_redis_allocation_active` | Число действующих reservations (`ZCOUNT` по Redis TIME), тип login/refresh |
| `safeai_rate_limit_redis_allocation_limit` | Лимит reservations из кода (4096 / 8192 соответственно) |

Типы, причины, операции и исходы ограничены allowlist; неизвестное — `unknown`.
Никаких user/organization UUID, email, IP, HMAC, run_id, ключей Redis, токенов,
текста запросов, адресов, деталей исключений в новых метриках. Scrape и Prometheus
не публиковать в Интернет. Не добавлять пользовательские идентификаторы в labels.

## Реакция на алерты

| Алерт (префикс SafeAiRateLimit) | Условие | Действие |
| --- | --- | --- |
| ScrapeUnavailable / InstrumentationMissing | Нет scrape или обязательных серий 2м | Проверить management endpoint, сеть, версию backend и регистрацию метрик |
| ObservationUnhealthy | Снимок не получен, старше 120с, либо часы впереди >30с; 1м | Проверить Redis, таймауты, scheduler и синхронизацию часов |
| EpochUnavailable | Обязательный epoch не active, 1м | Проверить состояние, остановить неконтролируемый rollout, следовать протоколу 7.5F.3 |
| RedisUnsafe | Нет maxmemory / noeviction / primary / здорового AOF, 1м | Исправить конфигурацию, проверить диск и права, не разрешать fail-open |
| RedisEvictions | Evictions >0 | Security-инцидент; проверить сохранность счётчиков; не делать FLUSHDB/CONFIG RESETSTAT для снятия тревоги |
| RedisMemoryHigh / RedisMemoryCritical | ≥80% 5м / ≥95% 1м | Ограничить входящий трафик, проверить RSS, cgroup, диск/AOF; увеличить ресурсы по согласованному плану |
| AllocationHigh / CapacityRejects | ≥80% 2м / ≥1 отказ capacity за 5м | Проверить Nginx и анонимный трафик; не удалять reservations |
| Unavailable | ≥1 ошибка за 5м | Проверить Redis и epoch, причину отказа по безопасным логам |
| AuditPublishFailed | ≥1 сбой за 5м | Проверить Audit/outbox; 429 остаётся 429 |
| RejectionRatio | ≥50% отказов и ≥20 отказов за 5м, 3м | Проверить нагрузку, распределённые атаки, лимиты; не поднимать лимиты автоматически |
| RedisSlow | p95 успешной проверки >250мс, ≥20 проверок за 5м, 3м | Проверить Redis latency, CPU, сеть и AOF fsync |

Общие Redis gauges из нескольких backend не суммировать: для единственного общего
Redis используйте `max` (и при расследовании смотрите каждую instance). Счётчики
запросов агрегируются через `sum(increase(...))`, а не sum накопленных значений;
`increase` учитывает перезапуск backend. Не смешивать capacity и Redis failure.

Снимок AOF не доказывает корректность fsync, непрерывность истории, Sentinel quorum
или отсутствие прежних evictions после рестарта. Для этого остаются обязательными
операторские проверки F.3, `check-redis-memory.sh`, отдельная Sentinel-приёмка и
проверка сохранности счётчиков. В распределённой топологии не переключать epoch
без fencing прежнего primary. Не менять HMAC/epoch для устранения алерта.

## Проверка deployment

`bash infra/scripts/check-prometheus-config.sh` проверяет реальную конфигурацию,
канонические YAML и правила, включая RateLimit. Performance CI также запускает
`promtool check rules` и `test rules` для этого набора. После развёртывания убедиться
в доступности scrape, наличии всех серий на каждой реплике, `healthy=1`, `active=1`
для обязательного epoch и отсутствии evictions.

Уведомления наружу требуют отдельной настройки Alertmanager, receiver, TLS, routing,
дедупликации, inhibition и проверки доставки до дежурного. Это Deployment Readiness
(7.5C), а не факт доставки оповещений на текущем этапе. Grafana dashboards и Redis
exporter/cgroup относятся к общей инфраструктуре 7.5C.
