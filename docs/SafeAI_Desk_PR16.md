# SafeAI Desk — структура проекта, назначение файлов и выполненные проверки

> **Справочник по текущему проекту на Mac.**  
> Состояние репозитория: ветка `main`, коммит `ec2596e` (слияние PR №16).  
> Подготовлено 12 октября 2026 года по скриншотам IntelliJ IDEA, содержимому репозитория и результатам команд, выполненных во время работы над PR №16.
>
> **Главное:** наличие файла ≠ его выполнение; успешная сборка ≠ полная проверка бизнеса; успешные локальные тесты ≠ подтвержденное развертывание на сервере.

## 1. Что это за проект и как устроены его части

SafeAI Desk — приложение с Java/Spring Boot сервером, React/TypeScript интерфейсом и внешними сервисами (PostgreSQL, Redis и объектное хранилище для документов). Docker Compose описывает совместный запуск контейнеров. Nginx принимает запросы браузера, отдает интерфейс и пересылает API-запросы в Java-приложение. Prometheus собирает технические метрики.

Упрощенная схема **production**:

```text
Пользователь / браузер
         |
       HTTPS
         |
  Nginx на сервере (TLS, сертификат, HSTS)
  Пример: infra/host-nginx/safeai.conf.example
         |
  HTTP на 127.0.0.1:8080 (только внутри сервера)
         |
  Nginx в Docker
  - отдает React SPA из /usr/share/nginx/html
  - запросы /api/ отправляет в backend:8080
  - не пропускает /actuator наружу
         |
  Spring Boot Backend (Java 21)
         |----------------------|
         |                      |
    PostgreSQL               Redis
    данные, миграции         кеш, блокировки, rate limit
         |
    Контролируемый доступ к БД

  Prometheus ---> внутренний Actuator backend:9091
```

**Отдельно:** frontend и Java находятся в одном Git-репозитории, но собираются разными средствами: frontend — через `npm` и Vite, backend — через Maven и JDK 21. Docker создает готовые образы для исполнения на сервере.

### Три режима, которые нельзя путать

| Режим | Зачем нужен | Главные файлы |
|---|---|---|
| Локальная разработка | Писать и отлаживать код на Mac | `backend/.env`, `application-local.yml`, `infra/docker-compose.local.yml` |
| Проверка и сборка | Найти ошибки до публикации | `./mvnw clean verify`, `npm run ci`, Docker build, тестовые скрипты |
| Production | Развернуть сервис на настоящем сервере с настоящими секретами | `backend/.env.prod`, `application-prod.yml`, `infra/docker-compose.yml`, `infra/scripts/deploy-prod.sh` |

## 2. Общая структура папок из IntelliJ IDEA

```text
SafeAI-Desk/
├── .idea/                         # Локальные настройки IntelliJ IDEA
├── .local-test-data/              # Локальные тестовые данные
├── backend/                       # Java / Spring Boot
│   ├── .local-secrets/            # Локальные секреты: не коммитить
│   ├── .mvn/wrapper/              # Maven Wrapper
│   ├── deploy/prometheus/         # Дополнительная конфигурация мониторинга
│   ├── docs/config/               # Примеры настроек backend
│   ├── src/main/java/             # Исходный Java-код приложения
│   ├── src/main/resources/        # YAML, Flyway SQL, логирование
│   ├── src/test/java/             # Java-тесты
│   ├── src/test/resources/        # Настройки и файлы для тестов
│   ├── target/                    # Результат Maven-сборки (генерируется)
│   ├── var/                       # Локальные рабочие файлы
│   ├── .env                       # Локальные переменные, обычно без Git
│   ├── .env.example               # Шаблон локальных переменных
│   ├── .env.prod                  # РЕАЛЬНЫЕ production-параметры, без Git
│   ├── .env.prod.example          # Шаблон production-параметров, в Git
│   ├── .dockerignore              # Исключения из контекста Docker-сборки
│   ├── .gitignore                 # Файлы backend, игнорируемые Git
│   ├── Dockerfile                 # Сборка Java Docker-образа
│   ├── mvnw / mvnw.cmd            # Запуск Maven (Unix / Windows)
│   └── pom.xml                    # Maven-зависимости и настройки сборки
├── frontend/                      # React / TypeScript / Vite
├── infra/                         # Docker, Nginx, PostgreSQL, скрипты сервера
│   ├── config/
│   ├── host-nginx/
│   ├── nginx/
│   ├── postgres/
│   ├── prometheus/
│   ├── scripts/
│   ├── secrets/
│   ├── systemd/
│   ├── docker-compose.local.yml
│   └── docker-compose.yml
├── scripts/                       # Общие утилиты, в том числе старые .bat
├── docs/                          # Документация и инструкции
├── backups/                       # Локальная папка; содержимое не оценивалось
├── .dockerignore                   # Защита КОРНЕВОГО Docker-контекста
├── .gitignore                      # Правила игнорирования Git
├── README*.md                      # Разные README проекта
└── ROADMAP*.md                     # Планы и этапы развития
```

### Цвета папок в IntelliJ IDEA

- Зеленым обычно отображаются каталоги, обозначенные как **Test Sources / Test Resources** — файлы тестов.
- `target/` обычно помечен как **Excluded** (результат сборки, не редактируется вручную).
- Оранжевые/коричневые имена нередко связаны с исключениями Git, статусом файлов или пользовательскими цветами. **Точное значение цвета зависит от настроек IDE**. По одному цвету нельзя заключить, что файл поврежден.
- Если рядом с `backend` написано `[safeai-backend]`, IntelliJ распознала Maven-модуль.

## 3. Папка `backend/` — Java-сервер

| Файл или папка | Что означает | Что делать самому |
|---|---|---|
| `pom.xml` | Описание Maven-проекта: Java 21, Spring Boot, версии библиотек, плагины и тесты | Править при осознанном обновлении зависимостей |
| `mvnw` | Maven Wrapper для macOS/Linux: вызывает нужную конфигурацию Maven | Использовать: `./mvnw clean verify` |
| `mvnw.cmd` | То же для Windows | На Mac не нужен |
| `.mvn/wrapper/maven-wrapper.properties` | Настройки Wrapper, откуда брать Maven | Обычно не трогать |
| `Dockerfile` | Создает Java-образ: собирает JAR на JDK 21, переносит в JRE и запускает от UID/GID `10001` | Проверять при изменении Docker/Java |
| `.dockerignore` | Исключает `target`, `.env*`, локальные секреты, логи и ключи из Docker-контекста | Не удалять: это защита секретов |
| `.gitignore` | Запрещает случайное добавление определенных локальных файлов в Git | Поддерживать актуальным |
| `.env` | Локальные значения переменных окружения | Не публиковать |
| `.env.example` | Образец локального `.env` | Можно хранить в Git без реальных ключей |
| `.env.prod` | Настройки конкретного production-развертывания | Не отправлять в Git/чат без очистки |
| `.env.prod.example` | Публичный шаблон production. `REPLACE_ME_*` надо заменить на целевые значения | Редактировать шаблон при изменении контракта развертывания |
| `src/main/java/` | Java-контроллеры, сервисы, сущности, репозитории, безопасность | Основная разработка |
| `src/main/resources/` | YAML-настройки, SQL-миграции и конфигурация логирования | Осторожно менять; влияет на старт |
| `src/test/java/` | Unit-, integration-, contract- и architecture-тесты | Добавлять вместе с изменением логики |
| `src/test/resources/` | Тестовые YAML, SQL и образцы документов | Не путать с production |
| `target/` | Результат компиляции, отчеты тестов, JAR | Удаляется командой `clean`; в Git не нужен |
| `var/`, `.local-secrets/` | Рабочие локальные данные и секреты | Не отправлять в Git |
| `context-error.log` | Локальный журнал ошибки, виден на скриншоте | Использовать для диагностики; не публиковать без проверки данных |
| `commit-message.txt` | Вспомогательный файл, виден на скриншоте | Не является обязательной частью Spring Boot |
| `safeai-backend.iml` | Конфигурация модуля IntelliJ IDEA | Не является Java-бизнес-логикой |

### Папка `src/main/resources/`: чем различаются конфигурации

| Файл | Назначение |
|---|---|
| `application.yml` | Базовая конфигурация Spring Boot, общие свойства и переменные окружения |
| `application-local.yml` | Переопределения для локальной разработки |
| `application-local-nginx.yml` | Локальный профиль с учетом Nginx |
| `application-prod.yml` | Production-параметры: безопасность, отдельный порт Actuator, БД, миграции, HSTS и пр. |
| `application-prod-knowledge-reconciliation.example.yml` | **Пример**, а не автоматически включаемый профиль; настройки восстановления/сверки хранилища документов |
| `logback-spring.xml` | Формат и маршрутизация логов приложения |
| `META-INF/additional-spring-configuration-metadata.json` | Метаданные для подсказок и описаний свойств в IDE |
| `db/migration/V1__...sql` … `V72__...sql` | Версионные миграции Flyway: создание/изменение схемы БД, ограничения, индексы |
| `db/local-migration/` | Миграции/данные для локального окружения; не равны production-миграциям |
| `db/README_DB.md` | Документация по базе данных |

**Почему два-три YAML с похожими именами — нормально:** базовый `application.yml` загружается всегда, профильный `application-prod.yml` — при активном профиле `prod`, а файлы с `.example` — это образцы для настройки. Они не должны автоматически считаться реальными секретами.

### Что лежит в `src/test/resources/`

- `application-test.yml` — настройки для тестового режима.
- `application-auth-postgres-it.yml` — настройки тестов авторизации с PostgreSQL.
- `knowledge-fixtures/positive/` — корректные тестовые PDF, DOCX, HTML, TXT и др.
- `knowledge-fixtures/negative/` — заведомо поврежденные, неподдерживаемые и некорректные файлы, чтобы проверять отказоустойчивость.
- `knowledge-evaluation/` — контрольные данные для оценки работы Knowledge/RAG.
- `sql/` — проверочные SQL-скрипты.

**Их не нужно очищать только потому, что часть файлов «неправильные».** Поврежденные образцы сделаны такими намеренно.

## 4. Папка `infra/` — инфраструктура и production

### `infra/docker-compose.yml`

Главный production Compose-файл. Он описывает не исходники, а **как запустить сервисы вместе**:

| Сервис | Назначение |
|---|---|
| `backend` | Java API, внутренний порт 8080, отдельный Actuator 9091 |
| `nginx` | React-интерфейс и обратный прокси; хостовый порт `127.0.0.1:8080` |
| `postgres` | PostgreSQL с `pgvector`, долговременные данные |
| `db-bootstrap` | Одноразовая операция создания и настройки БД, ролей и разрешений |
| `redis` | Redis для связанных с приложением оперативных данных |
| `prometheus` | Сбор внутренних метрик и вычисление правил оповещения |

Также там настраиваются `networks`, `volumes`, `secrets`, ограничения CPU/RAM, `healthcheck`, политика перезапуска и порядок запуска (`depends_on`).

**Важно:** Compose не является установщиком PostgreSQL «в исходный код». Он описывает контейнер PostgreSQL и то, как остальные контейнеры к нему обращаются.

### `infra/docker-compose.local.yml`

Отдельный Compose-файл для локальной разработки. В текущем варианте содержит `postgres`, `redis`, `minio`, а также `backend`/`prometheus` в профиле `full`. В отличие от production здесь предусмотрены локальные переменные и локальные Docker-тома.

**Не подменять production-файл локальным.** У них разная модель безопасности и разные настройки.

### `infra/config/`

- `pricing.yml.example` — **образец** таблицы цен/моделей для учета стоимости AI-запросов. Для production нужен отдельный реальный `pricing.yml`, на который ссылается конфигурация сервера. `validate-prod.sh` проверяет его наличие, владельца и отсутствие плейсхолдеров.

### `infra/host-nginx/`

- `safeai.conf.example` — пример **внешнего Nginx на самом сервере**, который принимает HTTPS, использует сертификат и перенаправляет запросы в Docker Nginx на `127.0.0.1:8080`.
- В примере присутствует `Strict-Transport-Security: max-age=31536000` (HSTS). Включать `includeSubDomains` следует только при готовности всех поддоменов к HTTPS.

**Разница двух Nginx:** внешний завершает TLS/HTTPS, контейнерный отдает React и маршрутизирует `/api/` к backend. Мы локально проверяли контейнерный Nginx, **не внешний боевой HTTPS**.

### `infra/nginx/`

| Файл | Назначение | Что проверили |
|---|---|---|
| `Dockerfile` | Сначала собирает React на Node 24, затем кладет `dist/` в Nginx-образ | Образ реально собран |
| `nginx.conf` | Маршруты `/`, `/assets/`, `/api/`, `/healthz`; блокировка `/actuator`; прокси-заголовки и лимиты | `nginx -t` и HTTP smoke-test прошли |
| `security-headers.conf` | CSP, `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy` | Включение в образ и основные заголовки проверены |

**SPA fallback:** переход на `/chats/что-то` должен отдавать `index.html`, чтобы дальнейшую навигацию выполнил React Router. Это было проверено в контейнере.

### `infra/postgres/`

| Файл | Назначение | Что проверили |
|---|---|---|
| `Dockerfile` | Строит PostgreSQL-образ с `pgvector`, создает/использует группу чтения секретов `21001` и копирует bootstrap в `/opt/safeai/10-create-app-roles.sh` | Docker-сборка успешна |
| `init/10-create-app-roles.sh` | Создает роли `safeai_migrator` и `safeai_app`, задает пароли, владельцев и разрешения, готовит расширение `vector` | Отдельный тест PostgreSQL прошел, включая повторный запуск |

**Роли БД:** `bootstrap` — административная первичная настройка; `migrator` — применение Flyway-миграций; `app` — работа приложения с ограниченными правами. Такое разделение уменьшает последствия ошибок и компрометации runtime-учетной записи.

### `infra/prometheus/`

- `prometheus.yml` — говорит Prometheus опрашивать внутренний `/actuator/prometheus` на `backend:9091` примерно каждые 15 секунд.
- `rules/safeai-audit-outbox.yml` — правила тревог: наличие записей `dead letter` в Audit Outbox и слишком долго ожидающие события.

**Что это дает:** можно обнаружить проблемы с доставкой событий аудита еще до жалобы пользователя. Но в рамках PR №16 сами оповещения на реальном Prometheus не проверялись.

### `infra/secrets/`

- `jwt-keys.yml.example` — **пример** структуры набора JWT-ключей: `active-key-id`, `public-key`, `private-key`, возможность ротации ключей.
- `README.md` — напоминает, что реальные ключи не должны находиться в репозитории.

Настоящие production-секреты предполагается хранить **вне Git**, например в `/etc/safeai/secrets/`. Нельзя заполнять `.example` настоящими приватными ключами и коммитить его.

### `infra/systemd/`

| Файл | Для чего |
|---|---|
| `safeai-backup.service` | Описание фонового задания Linux: разово выполнить `backup-postgres.sh` от root |
| `safeai-backup.timer` | Расписание системного задания: ежедневно примерно в 02:30 плюс случайная задержка до 15 минут |

`systemd` — менеджер служб Linux, **не macOS**. Само наличие файлов не означает, что задания уже установлены и выполняются на сервере.

## 5. Все скрипты `infra/scripts/` со скриншотов

| Скрипт | Что выполняет | Статус нашей проверки |
|---|---|---|
| `backup-postgres.sh` | Делает `pg_dump`, шифрует архив через `age`, записывает SHA-256, отправляет через `rclone`, чистит старые локальные файлы | **Не проверяли восстановлением реального бэкапа** |
| `build-prod.sh` | Валидирует параметры сборки, требует чистый Git, прогоняет Maven и frontend CI, собирает три production-образа | Проверяли логику и синтаксис; **полный release-запуск с реальными digest не выполняли** |
| `check-frontend-routing.sh` | Проверяет, что React-маршрут отдает HTML, а `/api/**` не подменяется React-страницей | Специально этот скрипт не запускали; близкие HTTP-проверки делали вручную |
| `check-frontend-routing.ps1` | Вариант проверки роутинга на PowerShell | Не запускали |
| `deploy-prod.sh` | Валидирует production-конфигурацию, загружает образы, запускает PostgreSQL/Redis, ожидает `db-bootstrap`, затем backend/Nginx и проверяет readiness | Проверили Bash-синтаксис и Compose, **на сервере не запускали** |
| `init-prod-secrets.sh` | Инициализирует пароли, RSA-3072 JWT-ключи и secret-файлы с нужными правами; сохраняет существующие значения | Прошел отдельный интеграционный тест секретов |
| `restore-postgres.sh` | Расшифровывает резервную копию, запускает `pg_restore`, пытается повторно выполнить настройку ролей | **Не испытывали. Есть выявленное несоответствие — см. раздел 9** |
| `test-postgres-bootstrap.sh` | Изолированно поднимает PostgreSQL, проверяет создание ролей, права и повторный bootstrap | **Все 10 проверок прошли** |
| `test-prod-secrets.sh` | Изолированно проверяет генерацию секретов, RSA, права, идемпотентность и запрет симлинков | **Все 6 проверок прошли** |
| `validate-prod.sh` | Проверяет обязательные переменные, HSTS, шаблоны, secret-файлы, файловые права, образы, pricing и Compose | Синтаксис и отдельные контракты проверены; реальные production-файлы не валидировали |
| `verify-build.mjs` | Похож на проверку собранного frontend: секреты, sourcemap, Vite manifest и отдельный JS-чанк Admin Usage | **Именно эта копия не запускалась.** Рабочая сборка использует `scripts/verify-build.mjs` из корня |

### Как связаны основные production-скрипты

```text
init-prod-secrets.sh       # подготовить секреты (отдельно, осторожно)
          |
validate-prod.sh           # проверить настройки до запуска
          |
build-prod.sh              # проверить исходники и собрать образы
          |
   публикация образов       # отдельная операция: registry, digest, проверки
          |
deploy-prod.sh             # pull -> postgres/redis -> db-bootstrap -> backend/nginx
          |
  health/readiness         # убедиться, что сервисы запустились
          |
backup-postgres.sh         # регулярные резервные копии на сервере
restore-postgres.sh        # аварийное восстановление, сейчас требует ревизии
```

Это **логическая последовательность**, а не команда запустить все скрипты подряд на Mac.

## 6. Корневая папка `scripts/`: почему еще одна папка с таким именем

Есть **два разных каталога**:

1. `infra/scripts/` — преимущественно сопровождение и deployment.
2. `scripts/` в корне репозитория — общие вспомогательные команды, включая Windows `.bat`.

| Корневой файл | Для чего |
|---|---|
| `scripts/verify-build.mjs` | **Используется frontend-сборкой:** проверяет готовый Vite bundle, отсутствие типовых секретов и sourcemap, разделение чанков |
| `scripts/check-health.bat` | Старый Windows bat для `curl` к `/actuator/health` |
| `scripts/full-docker-up.bat` | Старый Windows сценарий запуска Compose |
| `scripts/run-infra.bat` | Старый Windows запуск инфраструктуры |
| `scripts/stop-infra.bat` | Старый Windows останов инфраструктуры |
| `scripts/run-backend-local.bat` | Старый Windows запуск Java backend с локальными переменными |
| `scripts/psql-audit-events.bat` | Вспомогательный Windows-скрипт для работы с событиями аудита; содержимое не проверяли |

**На Mac `.bat` обычно не запускаются напрямую.** В нескольких батниках прописан старый путь вида `D:\Java projects\Safeai-desk\...`; они явно ориентированы на другую систему и не использовались в наших успешных macOS-тестах.

**Еще одна тонкость:** обе копии `verify-build.mjs` похожи по содержимому, но относительный путь `../frontend/dist/` корректен из корневой `scripts/` и указывает на другое место из `infra/scripts/`. Поэтому ориентируемся на **`scripts/verify-build.mjs`**, вызываемый через `frontend/package.json`. Дублирование стоит отдельно разобрать, не удаляя файл без проверки ссылок.

## 7. Файлы `docs/`, `backend/docs/`, `backend/deploy/`

- `docs/` — пользовательские и технические руководства: архитектура, локальный старт, Docker, JWT, организация/пользователи, Chat, AI, Audit, SQL, тесты и диагностика.
- `docs/runMacBook/` — инструкции и локальные скрипты именно для Mac (`start-local.sh`, `stop-local.sh`, Git-команды).
- `backend/docs/config/` — примеры `application-prod-knowledge*.yml`, `application-security-example.yaml`. Это справочные шаблоны, а не еще один автоматически включаемый набор production-настроек.
- `backend/deploy/prometheus/` — дополнительное расположение Prometheus-конфигурации в backend; основная production Compose ссылается на `infra/prometheus/`.
- `README*.md`, `ROADMAP*.md` — несколько существующих документов по описанию/планам проекта; их наличие не означает, что все версии одинаково актуальны.

Наличие похожих файлов в разных каталогах **не обязательно ошибка**, но важно знать, какая копия реально используется конкретной командой.

## 8. Что мы фактически изменили в PR №16

PR №16: **«Усиление безопасности и надежности развертывания в production»**.  
Исходная ветка: `fix/production-deployment-contract`.  
Коммит с изменениями: `7226d90`.  
Коммит слияния в `main`: `ec2596e`.  
Всего: **17 файлов, 675 добавлений и 45 удалений**.

### Перечень изменений

| Файл/группа | Что именно сделали |
|---|---|
| `.dockerignore` | Создали корневой allowlist: в Docker-контекст попадает только необходимое для frontend/PostgreSQL; локальные файлы и ключи исключаются |
| `backend/.dockerignore` | Закрыли передачу локальных `.env*`, ключей, `target/`, `var/` и секретов в образ backend |
| `backend/.env.prod.example` | Добавили переменные для сборки и закрепления образов, loopback bind, группу `21001`, настройки HSTS и паролей |
| `ProductionSecurityInvariantValidator.java` | Добавили защиту от запуска production без объявленного управления HSTS |
| `application-prod.yml` | Добавили `safeai.security.hsts.edge-managed` |
| `ProductionSecurityInvariantValidatorTest.java` | Расширили unit-тесты HSTS |
| `ProductionSecurityStartupContractTest.java` | Расширили проверки отказа запуска с небезопасной конфигурацией |
| `ProductionJwtConfigDataTest.java` | Создали новый тест загрузки внешнего `jwt-keys.yml` в Spring Boot |
| `infra/docker-compose.yml` | Перенесли `db-bootstrap` на встроенный в образ скрипт, убрали старый bind mount, параметризовали loopback port и путь к `.env` |
| `infra/nginx/Dockerfile` | Добавили копирование `security-headers.conf` в Nginx-образ |
| `infra/postgres/Dockerfile` | Исправили размещение bootstrap вне автоматического `initdb.d`, проверили группу чтения секретов |
| `infra/scripts/build-prod.sh` | Исправили ARG для Node/Nginx/Postgres, добавили frontend CI и audit, проверки digest и чистого Git |
| `infra/scripts/deploy-prod.sh` | Исправили порядок запуска, ожидание `db-bootstrap`, проверку кода завершения и readiness, единый `.env` |
| `infra/scripts/init-prod-secrets.sh` | Добавили RSA-3072 ключи, управление secret-файлами, отказ от перезаписи, проверки прав/симлинков |
| `infra/scripts/validate-prod.sh` | Усилили fail-fast проверки production-переменных и secret-файлов |
| `infra/scripts/test-postgres-bootstrap.sh` | Новый интеграционный тест инициализации PostgreSQL |
| `infra/scripts/test-prod-secrets.sh` | Новый интеграционный тест безопасной генерации секретов |

**Что не меняли этим PR:** бизнес-логику `ratelimit`, `usage`, `audit`, `chat`, `ai`, `user`, `organization`, а также содержимое SQL-миграций. Их существующие тесты попали в общий прогон Java, но это не отдельное углубленное ревью семи модулей.

## 9. Важные замечания, выявленные при подготовке справочника

### 9.1. `restore-postgres.sh` пока нельзя считать готовым к production

**Обнаружено конкретное расхождение:** `infra/scripts/restore-postgres.sh` по-прежнему пытается вызвать в контейнере PostgreSQL:

```bash
bash /docker-entrypoint-initdb.d/10-create-app-roles.sh
```

После PR №16 bootstrap намеренно помещен в **другой путь**:

```text
/opt/safeai/10-create-app-roles.sh
```

Причем повторную инициализацию теперь корректнее координировать через отдельный сервис **`db-bootstrap`**, а не просто менять путь в команде внутри `postgres`: у этих контейнеров отличаются окружение, назначение и доступ к secret-файлам.

**Вывод:** восстановление из бэкапа **не проверялось** и требует отдельного исправления с тестом на чистом временном окружении. **Не запускать `restore-postgres.sh` на настоящей БД до этого исправления.**

### 9.2. Скрипты backup/restore требуют сверки с новой схемой `.env`

В PR №16 `deploy-prod.sh` и `validate-prod.sh` были согласованы с переменной `SAFEAI_RUNTIME_ENV_FILE`. Вспомогательные `backup-postgres.sh` и `restore-postgres.sh` отдельно на это не проверялись. Если пользователь передает альтернативный `.env` файл, надо проверить, что каждый Compose-вызов действительно использует его и как CLI, и как env-файл контейнера.

### 9.3. Два `verify-build.mjs`

Рабочий путь frontend: **`scripts/verify-build.mjs`**. В `infra/scripts/verify-build.mjs` существует вторая копия с относительными путями, рассчитанными на другое расположение. В рамках PR №16 этот дубликат не исправляли.

### 9.4. Старые `.bat` не заменяют инструкции для macOS

В ряде `.bat` прописаны Windows-пути и старые переменные. Эти сценарии не входят в проверенную цепочку на Mac и не должны считаться подтвержденной конфигурацией production.

### 9.5. Реальный TLS/HSTS еще не проверен

Тест Spring Boot проверяет **объявление ответственности за HSTS**, а реальный заголовок должен приходить от внешнего HTTPS-сервера. Для проверки нужен настроенный домен и сертификат; одних локальных Docker-тестов недостаточно.

## 10. Какие проверки мы уже выполнили и что означают результаты

| Проверка | Что сделано | Результат |
|---|---|---|
| Maven `./mvnw clean verify` | Чистая компиляция, тесты и формирование JAR | **2299 тестов, 0 Failures, 0 Errors, 3 Skipped; BUILD SUCCESS** |
| Frontend `npm run ci` | ESLint, Vitest с покрытием, TypeScript, Vite и проверка bundle | **347 тестов, 55 тестовых файлов; успешно** |
| `npm run audit:prod` | Проверка npm-зависимостей, необходимых в production | **0 найденных уязвимостей** |
| Общий `npm ci` | Установка всех зависимостей, включая разработческие | Обнаружены **8 предупреждений по уязвимостям** (3 moderate, 5 high); принудительные обновления не выполняли |
| PostgreSQL Docker | Настоящая сборка Docker-образа | Успешно |
| Bootstrap PostgreSQL | Чистая БД, повторный запуск, роли, расширение vector, права | **10 PASS** |
| Секреты | Генерация RSA, права, идемпотентность, защита от симлинков | **6 PASS** |
| JWT Config Data | Подключение внешнего YAML с тестовыми ключами к Spring Boot | **1 PASS** |
| HSTS Java-тесты | Безопасные и небезопасные варианты конфигурации | Проверки прошли |
| Docker Compose | `config --no-env-resolution --quiet`, путь `.env` | Успешно |
| Bash-скрипты | `bash -n`, `git diff --check` | Успешно |
| Nginx Docker | Сборка и `nginx -t` | Успешно |
| Nginx HTTP | `/healthz`, SPA fallback, заголовки, блокирование `/actuator` | Все smoke-тесты прошли |
| Backend Docker | Сборка JAR в контейнере, Java 21, UID/GID 10001, права на JAR | Успешно |
| Git | 17 файлов, коммит и push, PR №16 → `main` | Слияние в `ec2596e`, чистый `main` |

**Уточнения к таблице:**

- Выполненные Java-тесты не означают, что каждую production-интеграцию проверили на реальном сервере.
- Три теста помечены `Skipped`; отдельно не разбирали, почему именно они пропущены.
- Для внешних Redis Sentinel/Cluster тестов предусмотрен Maven-профиль `rate-limit-topology-it`. Он **не равен** обычному `clean verify` и требует отдельного окружения.
- Локально на Mac использовался Node.js 26, хотя `frontend/package.json` требует Node.js 24; Nginx Docker-сборка выполнялась на Node.js **24** и успешно прошла.
- Ни реальную публикацию release-образов в registry, ни реальный production `deploy-prod.sh`, ни восстановление БД из бэкапа в рамках этих тестов не выполняли.

## 11. А что с `ratelimit`, `usage`, `audit`, `chat`, `ai`, `user`, `organization`?

Состояние `main` после PR №16:

| Модуль | Основных Java-классов | Файлов тестов с именем `*Test*` / `*IT` | Примеры проверяемых вопросов |
|---|---:|---:|---|
| Rate Limit | 20 | 16 | Redis, лимиты, ошибки, Cluster/Sentinel |
| Usage | 37 | 24 | Учет токенов и денег, UTC, пересчеты, tenant isolation |
| Audit | 50 | 30 | Outbox, retention, авторизация, пагинация, изоляция |
| Chat | 75 | 34 | Конкурентность, блокировки, история, восстановление, состояние запроса |
| AI | 79 | 42 | Провайдеры OpenAI/Anthropic, таймауты, контракты, выполнение |
| User | 40 | 24 | Роли, статус, безопасность, транзакции |
| Organization | 24 | 10 | Доступ, статусы, обновления, безопасность |

**Все семь модулей находятся в Git и включены в Java-проект.** Но предыдущая работа была сосредоточена на deployment. Проверка наличия исходников + общий прогон 2299 тестов не заменяют детальный аудит бизнес-правил, гонок, авторизации и производительности каждого модуля.

## 12. Безопасные команды для повседневной работы на Mac

Из корня репозитория:

```bash
cd /Users/amid/Workspace/Projects/Products/SafeAI-Desk

# Проверить текущую ветку и незакоммиченные изменения
git status -sb

# Обновить main, не создавать merge-коммит
git switch main
git pull --ff-only origin main

# Проверить Java-проект
cd backend
./mvnw clean verify
cd ..

# Проверить frontend
cd frontend
npm run ci
npm run audit:prod
cd ..

# Проверить синтаксис production-скриптов (без запуска production)
bash -n infra/scripts/build-prod.sh
bash -n infra/scripts/deploy-prod.sh
bash -n infra/scripts/validate-prod.sh

# Проверить, не осталось ли ошибок форматирования в рабочем diff
git diff --check
```

**Команды выше не осуществляют production-развертывание.** Обрати внимание, что `clean verify` может запускать тестовые контейнеры, если Docker доступен.

### Что НЕ нужно запускать просто для «проверки»

```text
infra/scripts/deploy-prod.sh       # реальное развертывание/изменение контейнеров
infra/scripts/restore-postgres.sh  # восстановление с потенциально разрушительным действием
infra/scripts/init-prod-secrets.sh # изменяет файлы секретов
infra/scripts/backup-postgres.sh   # работает с БД и удаленным хранилищем резервных копий
```

Запуск этих скриптов требует понятного окружения и предварительных процедур безопасности.

## 13. Что рекомендуем делать дальше

**Сначала:** отдельный фикс и интеграционный тест для `restore-postgres.sh`, а также проверка соответствия backup/restore новому deployment-контракту. Это особенно важно, потому что восстановление данных — обязательная часть эксплуатационной готовности.

**Параллельно:** разобрать дубль `infra/scripts/verify-build.mjs` и устаревшие Windows `.bat`, определить реально используемые файлы и привести документацию в соответствие со схемой запуска. Не удалять файлы автоматически без поиска их вызовов.

**После этого:** углубленный модульный аудит `ratelimit → usage → audit → chat → ai → user → organization` с отдельными тестами граничных случаев.

**Перед первым production:** проверить HTTPS/HSTS на внешнем прокси, действительные секреты, digest-образы, backups и тестовое восстановление, реальную сетевую топологию, readiness и мониторинг.

---

### Короткая памятка: что где искать

| Хочу найти… | Открывать… |
|---|---|
| Java-код бизнес-логики | `backend/src/main/java/` |
| Java-тесты | `backend/src/test/java/` |
| SQL-таблицы и индексы | `backend/src/main/resources/db/migration/` |
| Основные настройки приложения | `backend/src/main/resources/application.yml` |
| Production-настройки Spring | `backend/src/main/resources/application-prod.yml` |
| Пример переменных окружения | `backend/.env.prod.example` |
| React-код | `frontend/src/` |
| Как собирается Java-контейнер | `backend/Dockerfile` |
| Как собирается React/Nginx | `infra/nginx/Dockerfile` |
| Маршрутизация HTTP | `infra/nginx/nginx.conf` |
| Внешний HTTPS и HSTS | `infra/host-nginx/safeai.conf.example` |
| Какие контейнеры запускаются | `infra/docker-compose.yml` |
| Локальная Docker-разработка | `infra/docker-compose.local.yml` |
| Как создаются БД и права | `infra/postgres/init/10-create-app-roles.sh` |
| Как создаются секреты | `infra/scripts/init-prod-secrets.sh` |
| Как проверяются production-настройки | `infra/scripts/validate-prod.sh` |
| Как собираются release-образы | `infra/scripts/build-prod.sh` |
| Как выполняется развертывание | `infra/scripts/deploy-prod.sh` |
| Проверки секретов и PostgreSQL | `infra/scripts/test-prod-secrets.sh`, `test-postgres-bootstrap.sh` |
| Резервное копирование и восстановление | `infra/scripts/backup-postgres.sh`, `restore-postgres.sh` |
| Метрики и алерты | `infra/prometheus/` |
| Инструкции для Mac | `docs/runMacBook/` |

**Статус этой справки:** описание текущих файлов + результаты уже выполненных проверок + отдельно выделенные обнаруженные, но еще не исправленные проблемы. Это не сертификат полной производственной готовности проекта.
