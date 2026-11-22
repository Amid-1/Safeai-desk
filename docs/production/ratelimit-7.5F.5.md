# 7.5F.5 — RateLimit: нагрузочная и Security-приёмка

Статус: комплект проверок подготовлен. Результаты Maven/Testcontainers,
promtool, edge HTTP и реальной топологии должны быть получены на конечном
commit; наличие сценариев не означает Production GO.

## Контур проверки

Новые тесты используют настоящие сервисы LoginRateLimitService,
RefreshRateLimitService и RedisRateLimitService, две независимые
LettuceConnectionFactory, Redis Testcontainers с паролем, AOF always,
noeviction, maxmemory 192 MB и реально включённым production epoch fence.
Ни внешние Redis endpoints, ни рабочие секреты не используются.

| Сценарий | Обязательная проверка |
|---|---|
| 128 login, 16 workers | 20 разрешено, 108 отклонено; нормализация email, идентификаторы receipt уникальны, оба счётчика = 128, один audit marker |
| 64 login receipts, 128 cleanup | Повторная компенсация не удаляет ранее зафиксированную неудачную попытку за общим NAT |
| 128 refresh | 30 разрешено, 98 отклонено; счётчик = 128, абсолютная дата истечения не сдвигается |
| 128 AI admission, 4 пользователя | Организация получает ровно 25 допусков; сумма пользовательских счётчиков = 25; другой tenant независим |
| 128 AI admission, один пользователь | 7 допусков; после отказа счётчик организации не расходуется |
| Глобальная allocation capacity | Заполненные guard ledger для login/refresh: 32 отказа каждого типа, 0 новых identity keys |
| HMAC mismatch / missing fence | Все три сервиса fail-closed; отсутствуют новые ключи и audit-события отказа по лимиту |
| Redis остановлен | Все три сервиса на обеих инстанциях fail-closed |

Проверяется точная сверка прикладных результатов со счётчиками
Redis и Micrometer. Сценарии конкурентности стартуют синхронно;
каждая future обязана завершиться за общий 75-секундный дедлайн,
ошибки не превращаются в отказ 429. Выводятся elapsed, p95/p99
времени итерации для диагностики. Это не SLA целевого VPS и не
полноценная HTTP нагрузка на production.

Глобальная capacity в новом сервисном тесте заполнена фикстурой;
существующий тест distributedAttackCannotExceedSharedAllocationCapacityAcrossInstances
обязательно выполняется дополнительно и проверяет реальное создание
резервов до границы. Старые тесты также покрывают 10 000 fresh-email,
OOM/noeviction, битые счётчики, TTL, restart/rebind,
epoch rotation, мониторинг и HTTP 429/503 + Retry-After.

## Запуск

Из корня репозитория (Docker и JDK 21 обязательны):

```bash
bash infra/scripts/verify-ratelimit-acceptance.sh
bash infra/scripts/test-ratelimit-nginx.sh
bash infra/scripts/check-prometheus-config.sh
```

Первый скрипт выполняет clean test по десяти классам и проверяет
50 обязательных методов. Если любой отчёт или метод отсутствует,
помечен skipped, failed, error, содержит rerun/flaky или повреждён,
завершение ненулевое. Также выполняются десять Python-сценариев
для самого XML-gate.

Для нового service IT можно отдельно указать точный проверяемый
Redis image digest, не передавая реальные пароли:

```bash
SAFEAI_RATELIMIT_TEST_REDIS_IMAGE='redis@sha256:<digest>' \
  bash infra/scripts/verify-ratelimit-acceptance.sh
```

Остальные исторические тесты сохраняют собственные test image tags.
Нельзя выдавать этот прогон за проверку всех контейнеров конкретной поставки.

Backend CI проверяет gate после полного clean verify, а Performance CI
добавляет отдельный чистый job с Redis и Nginx. Production release
уже вызывает Performance CI; существующие PostgreSQL, ACL, security
и release gates не ослаблены.

## Оставшиеся обязательные условия

- **F.2:** физические provider calls (RPM, TPM, concurrent leases,
  retry/fallback, probe/embeddings, recovery) не представлены
  в переданных исходниках ratelimit; существующее AI message
  admission не заменяет их. До предъявления реализации и отдельной
  доказательной матрицы завершать весь 7.5F нельзя.
- **F.3:** внешний Sentinel failover и, если нужен для целевой топологии,
  Cluster контракт — только в выделенном тестовом окружении.
  Epoch fence production рассчитан на standalone/Sentinel, не Redis Cluster.
  Standalone Compose не является HA.
- **F.4:** реальные promtool, scrape, alert delivery. Grafana,
  Redis Exporter, Alertmanager и доставка — этап 7.5C.
- Полный Maven clean verify, Testcontainers, Flyway V1–V101,
  clean/upgrade Runtime ACL на проверяемых образах и успешные GitHub checks
  на конечном SHA. Новых SQL/CONF ресурсов этот пакет не содержит.
- Staging: подтверждение TLS/egress, реального Redis image digest,
  целевых HTTP latency/throughput и поведения при memory pressure.
- Не переинициализировать рабочий Redis, не сбрасывать лимиты,
  не запускать внешние failover IT по production-адресам.

Решение: локальная приёмка Redis — отдельный проверяемый результат,
не сертификат общей Production Ready.
