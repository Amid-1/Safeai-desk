# SafeAI Desk — локальный запуск на macOS

Инструкция предназначена для проекта:

```text
~/Workspace/Projects/Products/SafeAI-Desk
```

Структура проекта:

```text
SafeAI-Desk/
├── backend/
├── frontend/
├── infra/
├── docs/
└── scripts/
```

## 1. Требования

На Mac должны быть установлены:

- Docker Desktop;
- Java нужной проекту версии;
- Node.js и npm;
- IntelliJ IDEA;
- Git.

Проверка:

```bash
docker --version
java -version
node --version
npm --version
git --version
```

Перед запуском проекта открой Docker Desktop и дождись состояния `Engine running`.

## 2. Быстрый запуск одной командой

Скрипты находятся в каталоге проекта:

```text
scripts/start-local.sh
scripts/stop-local.sh
```

Один раз выдай им право на выполнение:

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk/backend
chmod +x mvnw
```

Запуск всего проекта:

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk
./scripts/start-local.sh
```

Скрипт:

1. проверяет Docker, Java и npm;
2. запускает PostgreSQL и Redis;
3. запускает backend;
4. устанавливает frontend-зависимости, когда `node_modules` ещё нет;
5. запускает frontend;
6. сохраняет логи и PID-файлы в `.local-run`.

Backend:

```text
http://localhost:8080
```

Адрес frontend показывает Vite в логе:

```bash
tail -f .local-run/frontend.log
```

Просмотр backend-лога:

```bash
tail -f .local-run/backend.log
```

Остановка всего проекта:

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk
./scripts/stop-local.sh
```

Скрипт останавливает backend, frontend, PostgreSQL и Redis. Данные локальной PostgreSQL при этом сохраняются.

## 3. Ручной запуск инфраструктуры

Перейди в корень проекта:

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk
```

Запусти PostgreSQL и Redis:

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk

docker compose \
  --env-file backend/.env \
  -f infra/docker-compose.local.yml \
  up -d postgres redis minio
```

Проверь состояние:

```bash
docker compose \
  -f infra/docker-compose.local.yml \
  ps
```

Ожидается состояние `healthy` у обоих контейнеров.

Дополнительная проверка:

```bash
docker ps
```

Логи PostgreSQL:

```bash
docker compose \
  -f infra/docker-compose.local.yml \
  logs --tail=100 postgres


```

Логи Redis:

```bash
docker compose \
  -f infra/docker-compose.local.yml \
  logs --tail=100 redis
```

## 4. Ручной запуск backend

Открой отдельную вкладку Terminal и выполни:

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk/backend
```

При необходимости очисти результаты предыдущей сборки:

```bash
./mvnw clean
```

Тесты?
```bash
cd backend
./mvnw clean test
```

Установи переменные окружения:

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk/backend

unset SAFEAI_JWT_SECRET

set -a
source ./.env
set +a

export SAFEAI_JWT_PUBLIC_KEY="$(cat .local-secrets/jwt-public.pem)"
export SAFEAI_JWT_PRIVATE_KEY="$(cat .local-secrets/jwt-private.pem)"

./mvnw spring-boot:run
```

Запусти Spring Boot:

```bash
./mvnw spring-boot:run
```

Backend должен быть доступен по адресу:

```text
http://localhost:8080
```

Остановка backend в текущем окне Terminal:

```text
Control + C
```

## 5. Ручной запуск frontend

Открой ещё одну вкладку Terminal:

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk/frontend
```

При первом запуске установи зависимости:

```bash
npm install
```

Запусти frontend:

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk/frontend
npm run dev
```

Vite покажет адрес, обычно:

```text
4
```

Остановка frontend:

```text
Control + C
```

## 6. Запуск backend-тестов

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk/backend
./mvnw clean test
```

Очистка backend-сборки:

```bash
./mvnw clean
```

## 7. Остановка и повторный запуск Docker-инфраструктуры

Остановить PostgreSQL и Redis без удаления контейнеров и данных:

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk
docker compose -f infra/docker-compose.local.yml stop postgres redis
```

Запустить их повторно:

```bash
docker compose -f infra/docker-compose.local.yml start postgres redis
```

Удалить контейнеры и сеть, сохранив данные PostgreSQL:

```bash
docker compose -f infra/docker-compose.local.yml down
```

В следующий раз:

```bash
docker compose -f infra/docker-compose.local.yml up -d postgres redis
```

## 8. Полное удаление локальной базы данных

> Внимание: следующая команда удаляет контейнеры, сеть и Docker volumes проекта. Все данные локальной PostgreSQL будут потеряны.

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk
docker compose -f infra/docker-compose.local.yml down -v
```

После этого новая пустая база создастся при следующем запуске:

```bash
docker compose -f infra/docker-compose.local.yml up -d postgres redis
```

## 9. Подключение PostgreSQL в IntelliJ IDEA

Параметры:

```text
Host: localhost
Port: 5432
Database: safeai
User: safeai
Password: значение POSTGRES_PASSWORD из infra/docker-compose.local.yml
```

Проверить параметры контейнера:

```bash
grep -nE "POSTGRES_(USER|PASSWORD|DB)" \
  ~/Workspace/Projects/Products/SafeAI-Desk/infra/docker-compose.local.yml
```

## 10. Открытие проекта в IntelliJ IDEA

Открывай корень проекта:

```bash
open -a "IntelliJ IDEA" ~/Workspace/Projects/Products/SafeAI-Desk
```

В IntelliJ IDEA должна быть открыта папка:

```text
~/Workspace/Projects/Products/SafeAI-Desk
```

Не открывай только `backend/pom.xml` как отдельный проект, если требуется одновременно работать с backend, frontend, infra и документацией.

## 11. Используемые порты

| Сервис | Адрес/порт |
|---|---:|
| Backend | `http://localhost:8080` |
| Frontend | обычно `http://localhost:5173` |
| PostgreSQL | `localhost:5432` |
| Redis | `localhost:6379` |

## 12. Ежедневная схема работы

Запуск:

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk
./scripts/start-local.sh
```

Проверка логов:

```bash
tail -f .local-run/backend.log
tail -f .local-run/frontend.log
```

Остановка:

```bash
./scripts/stop-local.sh
```

# MinIO в локальной разработке SafeAI Desk

http://localhost:9001

Локальные данные для входа:
```text
Login:    safeai
Password: safeai-local-change-me
```

## Что такое MinIO

MinIO — это локальное объектное хранилище с S3-compatible API.

В SafeAI Desk оно используется для хранения самих файлов Knowledge Base:

- PDF
- DOCX
- TXT
- MD
- других загружаемых документов

При этом PostgreSQL хранит не сами файлы, а их метаданные:

- идентификатор документа
- принадлежность к Knowledge Base
- имя файла
- версию
- статус обработки
- размер
- storage/object key
- другую служебную информацию

Схема:

```text
SafeAI Backend
     │
     ├── PostgreSQL
     │     └── metadata документов
     │
     └── MinIO
           └── реальные байты файлов
```

```text
SafeAI Backend
     │
     ├── PostgreSQL :5432
     │     └── users, organizations, knowledge_documents,
     │         knowledge_document_versions, jobs, metadata...
     │
     ├── Redis :6379
     │     └── cache, locks, rate limits
     │
     └── MinIO :9000
           └── bucket safeai-knowledge
                 └── реальные загруженные файлы
```
