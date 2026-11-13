# SafeAI Desk: матрица доказательств production-допуска (07.11.2026)

**Статус:** автоматизация подготовлена, **не подтверждено исполнение** нового пакета на GitHub и в реальном staging. Никаких утверждений «production готов» до прохождения критериев ниже.

| Объект | Доказательство | Где | Автоматически в PR | Что ещё требуется |
|---|---|---|---|---|
| Java / 12 пакетов | `clean verify`, Surefire + локальные `*IT` | `backend-ci` | Да | Исправление обнаруженных логических ошибок, новые тесты на новые функции |
| Flyway V1..VN | Последовательное применение **каждой** миграции на чистой PostgreSQL pgvector/pg16, итоговый `validate` | `FlywaySequentialUpgradeIT` | Да | Восстановленный снимок реальной старой БД и upgrade rehearsal |
| Исторические миграции | Последовательность V1..VN, запрет изменения SQL, уже вошедших в `main` | `check-flyway-migrations.py` | Да | Никогда не использовать `repair` для скрытия ошибки |
| Coverage Java | JaCoCo XML; baseline каждого модуля, обязательный запрет регрессии >1 процентного пункта | `check-backend-coverage.py` | Да, **после фиксации baseline** | Оценить исходный процент, не выдавать 0% identity за покрытие |
| React | Vitest 1245 тестов; текущий coverage floor 70% | `frontend-ci` | Да | Повышать порог после новых тестов |
| Browser anonymous | Настоящий Chromium и React UI с управляемыми stub-ответами API | `browser-ci` | Да, **после генерации e2e/package-lock.json** | Это не Java API E2E |
| Browser+backend | Авторизация, навигация Knowledge, запрет админки для USER с реальным staging backend | `browser-ci` manual | Нет | Staging URL, выделенный тестовый USER, protected Environment |
| Redis standalone | PostgreSQL + Redis Testcontainers и auth/concurrency | `backend-ci` | Да | Следить за версиями образов |
| Redis Cluster/Sentinel | Реальная топология и failover | `redis-topology-ci` manual | Нет | Выделенный **не-production** Redis, защита Environment |
| OpenAI/Anthropic upstream | Один минимальный вызов каждого провайдера с JSON contract check | `acceptance-staging` manual | Нет | Секреты/модели/ограничение расходов; **не** проверка routing SafeAI |
| S3 Object Lock | Доказательство COMPLIANCE+Legal Hold; есть контрольное успешное удаление версии | `acceptance-staging` manual | Нет | Специальный бакет `SafeAITestOnly=true`; COMPLIANCE-объект остаётся до истечения срока |
| Audit/Chat/Knowledge | Существующие тесты SQL/lease/fencing/RAG/deletion | `backend-ci` | Да | Реальные multi-JVM и сбои между SQL и S3; отдельный сценарий восстановления |
| PostgreSQL restore | Изолированный `pg_restore`, SHA256 архива, проверка Flyway истории | `restore-rehearsal.sh` manual | Нет | Необработанные данные в staging запрещены без политики обезличивания; проверить S3/replay отдельно |
| WORM provenance | Текущие production gates и immutable image digests | `production-release` | Ручной из `main` | IAM, retention, legal hold, backup restore, owner approval |

## Необходимые действия после установки

1. Docker Desktop включён. В корне `backend`: `./mvnw clean verify` (включая новый Flyway test).
2. Посмотреть реальный `backend/target/site/jacoco/index.html` и список 12 модулей.
3. Один раз создать исходную линию: `python3 .github/scripts/check-backend-coverage.py --capture`, прочитать `backend/coverage-baseline.json`, **включить его в коммит**. Потом `--check`. Это измерение, а не обещание 100% покрытия.
4. В каталоге `e2e`: `npm install --package-lock-only`, `npm ci`, `npx playwright install chromium`, затем запустить Vite preview и `npm test`; **закоммитить `e2e/package-lock.json`**. Не разрешать CI установку пакета без lockfile.
5. Staging: настроить `acceptance-staging` с approval и secrets; отдельно провести Redis, Object Lock, реальные providers, browser E2E.
6. Обязательно добавить job `Playwright / публичные и защищённые маршруты` в Ruleset `main` (release preflight включает его автоматически после установки патча).
7. Результаты каждой ручной проверки хранить в утверждённом журнале приёмки с SHA, версией образов, средой, датой, исполнителем и ссылкой на артефакты.

## Заранее известные пределы

- 85 миграций в исходном пакете; при будущих V86+ тест подхватывает их автоматически. **Никакой тест не доказывает возможность обновиться от любой исторической версии со всеми возможными пользовательскими данными**.
- `identity` сегодня имеет 0 собственных тестовых классов. Это явный повод добавить бизнес-тесты и повысить JaCoCo baseline, а не скрывать модуль.
- COMPLIANCE и Legal Hold защищают **версии объектов**. Простое удаление ключа без `versionId` может создать delete marker, а не стереть защищённую версию. Проверка намеренно использует `versionId`.
- Provider API checks не используют пользовательские документы, но несут небольшой расход. Не включать их на каждом PR без разрешения.
- Реальный multi-JVM failure injection и восстановление S3-триггеров на консистентном снимке отсутствуют как готовая поддержанная площадка; считать их **открытым production-блокером** до внешнего прогона.
