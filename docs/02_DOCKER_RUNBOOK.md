# SafeAI Desk — Docker runbook

Документ описывает полный Docker-запуск.

Полный режим:

```text
PostgreSQL + Redis + Backend — в Docker Compose
```

## 1. Когда использовать

Используй этот режим:

- перед demo;
- после правок `Dockerfile`;
- после правок `docker-compose.yml`;
- после правок `pom.xml`;
- чтобы проверить запуск одной командой.

## 2. Запуск полного Docker-стека

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose --profile full up -d --build
```

## 3. Что делает `--build`

```text
пересобирает backend image
собирает jar
запускает safeai-backend
```

Используй `--build`, если менялись:

```text
Java-код
pom.xml
Dockerfile
application.yml
Flyway migrations
resources
```

## 4. Проверить контейнеры

```bat
docker compose ps
```

Ожидаемо:

```text
safeai-postgres   Up / healthy
safeai-redis      Up
safeai-backend    Up
```

## 5. Логи backend

```bat
docker compose logs -f backend
```

Выйти:

```text
Ctrl + C
```

Это не остановит контейнеры.

## 6. Проверить health

```bat
curl -i http://localhost:8080/actuator/health
```

Ожидаемо:

```text
HTTP/1.1 200
```

## 7. Важное правило

Не запускай одновременно:

```text
backend локально
backend в Docker
```

Оба занимают порт `8080`.

Если нужен локальный backend:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose stop backend
```

## 8. Остановка

```bat
docker compose stop
```

Удалить контейнеры, но оставить данные:

```bat
docker compose down
```

Удалить контейнеры и данные:

```bat
docker compose down -v
```

## 9. Успешный результат

```text
✅ docker compose --profile full up -d --build прошел без ошибок
✅ safeai-postgres healthy
✅ safeai-redis Up
✅ safeai-backend Up
✅ health возвращает 200
✅ backend внутри Docker видит PostgreSQL по postgres:5432
```
