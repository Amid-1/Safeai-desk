# SafeAI Desk — инструкция по запуску инфраструктуры на Windows

Эта инструкция описывает, как запустить локальную инфраструктуру проекта SafeAI Desk через Docker: PostgreSQL и Redis.

Проект находится здесь:

```text
D:\Java projects\Safeai-desk
```

Инфраструктурный файл Docker Compose находится здесь:

```text
D:\Java projects\Safeai-desk\infra\docker-compose.yml
```

---

## 1. Проверить, запущены ли контейнеры

Открой Windows CMD и выполни:

```bat
docker ps
```

Если контейнеры не запущены, вывод может быть пустым:

```text
CONTAINER ID   IMAGE     COMMAND   CREATED   STATUS    PORTS     NAMES
```

Это значит, что Docker работает, но контейнеры проекта сейчас не подняты.

---

## 2. Перейти в папку infra

Выполни:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
```

Что означает команда:

- `cd` — перейти в другую папку;
- `/d` — разрешить переход на другой диск, например с `C:` на `D:`;
- кавычки нужны, потому что в пути есть пробел: `Java projects`.

После команды ты должен оказаться здесь:

```text
D:\Java projects\Safeai-desk\infra>
```

---

## 3. Запустить PostgreSQL и Redis

В папке `infra` выполни:

```bat
docker compose up -d
```

Эта команда читает файл:

```text
D:\Java projects\Safeai-desk\infra\docker-compose.yml
```

и запускает сервисы, описанные в нем:

- `safeai-postgres` — база данных PostgreSQL;
- `safeai-redis` — Redis.

Флаг `-d` означает detached mode — контейнеры будут работать в фоне, а терминал освободится.

Нормальный вывод может выглядеть так:

```text
time="2026-05-24T21:16:56+03:00" level=warning msg="No services to build"
[+] up 2/2
 ✔ Container safeai-postgres Running
 ✔ Container safeai-redis    Running
```

Предупреждение:

```text
No services to build
```

не является ошибкой. Оно означает, что Docker не собирает свои образы из Dockerfile, а просто запускает готовые образы `postgres:16` и `redis:7`.

---

## 4. Проверить, что контейнеры запущены

Можно проверить общей командой:

```bat
docker ps
```

Ожидаемый результат:

```text
NAMES
safeai-postgres
safeai-redis
```

Также можно проверить именно контейнеры текущего `docker-compose.yml`:

```bat
docker compose ps
```

Ожидаемый результат:

```text
NAME              IMAGE         SERVICE    STATUS
safeai-postgres   postgres:16   postgres   Up
safeai-redis      redis:7       redis      Up
```

Для PostgreSQL должен быть проброшен порт:

```text
0.0.0.0:5432->5432/tcp
```

Для Redis должен быть проброшен порт:

```text
0.0.0.0:6379->6379/tcp
```

---

## 5. Полная последовательность команд для запуска инфраструктуры

Если Docker Desktop уже запущен, используй такую последовательность:

```bat
docker ps
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose up -d
docker compose ps
```

Если всё хорошо, ты увидишь:

```text
safeai-postgres   Up
safeai-redis      Up
```

---

## 6. Запуск backend после инфраструктуры

Когда PostgreSQL и Redis запущены, можно запускать backend.

Перейти в backend:

```bat
cd /d "D:\Java projects\Safeai-desk\backend"
```

Запустить Spring Boot:

```bat
mvnw.cmd spring-boot:run
```

Если backend не может подключиться к базе и пишет ошибку вроде:

```text
Подсоединение по адресу localhost:5432 отклонено
```

значит PostgreSQL не запущен или Docker Desktop не работает. Тогда вернись к шагам 1–4.

---

## 7. Остановка инфраструктуры

Чтобы остановить контейнеры, перейди в папку `infra`:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
```

Остановить контейнеры:

```bat
docker compose stop
```

Это остановит PostgreSQL и Redis, но не удалит данные.

---

## 8. Полное удаление контейнеров без удаления данных volume

```bat
docker compose down
```

Контейнеры будут удалены, но данные PostgreSQL останутся в Docker volume, если volume не удалять отдельно.

---

## 9. Полное удаление контейнеров вместе с данными базы

Осторожно: эта команда удалит данные локальной базы PostgreSQL.

```bat
docker compose down -v
```

Использовать только если нужно полностью пересоздать базу с нуля.

---

## 10. Частые команды

Проверить все запущенные контейнеры:

```bat
docker ps
```

Проверить контейнеры проекта:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose ps
```

Запустить инфраструктуру:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose up -d
```

Остановить инфраструктуру:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose stop
```

Посмотреть логи PostgreSQL:

```bat
docker logs safeai-postgres
```

Посмотреть логи Redis:

```bat
docker logs safeai-redis
```

Запустить backend:

```bat
cd /d "D:\Java projects\Safeai-desk\backend"
mvnw.cmd spring-boot:run
```

---

## 11. Что должно работать после запуска

После `docker compose up -d` должны быть доступны:

PostgreSQL:

```text
Host: localhost
Port: 5432
Database: safeai
Username: safeai
Password: safeai_password
```

Redis:

```text
Host: localhost
Port: 6379
```

Backend после запуска должен быть доступен здесь:

```text
http://localhost:8080
```
