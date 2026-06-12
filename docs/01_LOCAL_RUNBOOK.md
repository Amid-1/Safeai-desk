# SafeAI Desk — local runbook

Документ описывает ежедневный запуск на Windows.

Режим разработки:

```text
PostgreSQL + Redis — в Docker
Backend — локально через mvnw.cmd или IntelliJ
```

## 1. Пути

```text
Проект:  D:\Java projects\Safeai-desk
Backend: D:\Java projects\Safeai-desk\backend
Infra:   D:\Java projects\Safeai-desk\infra
```

## 2. Проверить Docker

```bat
docker ps
```

Если Docker Desktop работает, команда вернет список контейнеров или пустую таблицу.

## 3. Запустить PostgreSQL и Redis

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose up -d postgres redis
docker compose ps
```

Ожидаемо:

```text
safeai-postgres   Up / healthy
safeai-redis      Up
```

## 4. Проверить порты

PostgreSQL:

```text
localhost:5432
```

Redis:

```text
localhost:6379
```

## 5. Остановить backend-контейнер, если он мешает

Если ранее запускался полный Docker-режим:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose ps
docker compose stop backend
```

## 6. Запустить backend локально

```bat
cd /d "D:\Java projects\Safeai-desk\backend"
set SAFEAI_JWT_SECRET=safeai-local-development-secret-key-change-this-value-please-123456789
set SAFEAI_JWT_EXPIRATION_MINUTES=60
mvnw.cmd spring-boot:run
```

Ожидаемо в логах:

```text
Tomcat started on port 8080
Started SafeaiBackendApplication
```

## 7. Проверить health

```bat
curl -i http://localhost:8080/actuator/health
```

Ожидаемо:

```text
HTTP/1.1 200
```

```json
{"status":"UP"}
```

## 8. Остановить инфраструктуру

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose stop
```

Удалить контейнеры, но оставить данные:

```bat
docker compose down
```

Удалить контейнеры и данные PostgreSQL:

```bat
docker compose down -v
```

## 9. Частые команды

Логи PostgreSQL:

```bat
docker logs safeai-postgres
```

Логи Redis:

```bat
docker logs safeai-redis
```

Проверка контейнеров проекта:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose ps
```

## 10. Успешный результат

```text
✅ PostgreSQL запущен
✅ Redis запущен
✅ backend стартовал на 8080
✅ /actuator/health возвращает UP
✅ Flyway не падает
✅ Hibernate не падает
```
