# SafeAI Desk — локальный справочник разработки и тестирования

> **Личный файл, не для Git.** Содержит тестовые пароли в открытом виде.
> Только локальная/изолированная среда. Не размещать в issue, CI-логах, чатах команды,
> публичных репозиториях и production. Не использовать эти пароли в реальных аккаунтах.
> **Источник:** существующая памятка. Логины, пароли, написание имён сохранены как в источнике;
> наличие записей в текущей БД и актуальность паролей необходимо проверить.

## Навигация

1. [Безопасность и расположение](#1-безопасность-и-расположение)
2. [Адреса и быстрый старт](#2-адреса-и-быстрый-старт)
3. [Реестр организаций](#3-реестр-организаций)
4. [Реестр аккаунтов и паролей](#4-реестр-аккаунтов-и-паролей)
5. [Правила тестирования ролей](#5-правила-тестирования-ролей)
6. [Проверка PostgreSQL](#6-проверка-postgresql)
7. [Диагностика авторизации](#7-диагностика-авторизации)
8. [Запуск, тесты и Redis](#8-запуск-тесты-и-redis)
9. [Проверки API через curl](#9-проверки-api-через-curl)
10. [Регрессионный чек-лист](#10-регрессионный-чек-лист)
11. [Перед коммитом](#11-перед-коммитом)
12. [Журнал изменений тестовых данных](#12-журнал-изменений-тестовых-данных)

---

## 1. Безопасность и расположение

**Расположение:** `docs/local/TEST_ACCOUNTS.local.md` от корня SafeAI-Desk.

Добавить в корневой `.gitignore`:

```gitignore
# Локальные данные и пароли; никогда не публиковать
docs/local/
```

Проверить перед коммитом:

```bash
git check-ignore -v docs/local/TEST_ACCOUNTS.local.md
git ls-files --error-unmatch docs/local/TEST_ACCOUNTS.local.md
# Вторая команда должна завершиться ошибкой: файл не отслеживается Git.
git diff --cached --name-only
```

Если файл уже отслеживается, **после создания резервной копии** выполнить:

```bash
git rm --cached -- docs/local/TEST_ACCOUNTS.local.md
```

Это прекращает отслеживание, но **не удаляет пароли из прошлой истории Git**.
Если секреты когда-либо попадали в удалённый Git, считать их раскрытыми и сменить.
На macOS/Linux ограничить доступ к личному файлу: `chmod 600 docs/local/TEST_ACCOUNTS.local.md`.
Старайся не отправлять эту памятку в AI-чаты целиком: она содержит открытые пароли.

## 2. Адреса и быстрый старт

| Назначение | Адрес |
|---|---|
| Frontend | `http://localhost:5173` |
| Backend API | `http://localhost:8080` |
| Health | `http://localhost:8080/actuator/health` |
| PostgreSQL | `localhost:5432`, БД `safeai` |

**Порядок:** инфраструктура → backend → frontend → login → `GET /api/auth/me`.
Убедиться, что включён локальный профиль, а не production.

## 3. Реестр организаций

`UUID` ниже сохранены только там, где были известны в исходном документе.
У остальных организаций ID следует скопировать из UI/БД и записать вручную.

| Организация | UUID / идентификатор | Назначение |
|---|---|---|
| SafeAI Platform | `00000000-0000-0000-0000-000000000001` | Platform / SUPER_ADMIN |
| Demo Company | `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa` | Демонстрационный tenant |
| ООО "Клевер" | — | Тестовый tenant |
| ООО "Ромашка" | — | Тестовый tenant |
| ООО "Сокол" | — | Тестовый tenant |
| ООО "Зил" | — | Тестовый tenant |
| ООО "Космос" | — | Тестовый tenant |
| Organization A | — | Проверка tenant isolation |
| Organization B | — | Проверка tenant isolation |
| [новая организация] | — | [для чего создана] |
| [новая организация] | — | [для чего создана] |
| [новая организация] | — | [для чего создана] |
| [новая организация] | — | [для чего создана] |
| [новая организация] | — | [для чего создана] |

**Важно:** SafeAI Platform — служебная организация. Не проверять на ней обычные
операции создания пользователей, переименования или отключения tenant.

## 4. Реестр аккаунтов и паролей

Таблицы сохранены в исходном формате: `№ / Email / Password / Role / Организация / Комментарий`.
Внутри каждой роли записи сгруппированы по организации. Номера последовательные и уникальные.
Все существующие значения перенесены без изменений; `—` означает неизвестную организацию.

> После **Reset password** нужно обновить пароль в этом локальном файле. Записи в памятке
> не подтверждают наличие аккаунтов в текущей базе и актуальность указанных паролей.

### 4.1 SUPER_ADMIN

| № | Email                 | Password                   | Role          | Организация       | Полное имя / комментарий     |
|:--|-----------------------|----------------------------|---------------|-------------------|------------------------------|
| 1 | `superadmin@test.com` | `Admin_Dev_2026!Strong#91` | `SUPER_ADMIN` | `SafeAI Platform` | Платформенный администратор. |


### 4.2 ADMIN

| № | Email                   | Password             | Role    | Организация      | Полное имя / комментарий     |
|:--|-------------------------|----------------------|---------|------------------|------------------------------|
| 1 | `admin@test.com`        | `bvhgSI*7&@)++!@$HJ` | `ADMIN` | `Demo Company`   | AdminTest AdminTestov.       |
| 2 | `ADMIN-A@proton.net`    | `bvhgSI*7&@)=_@#`    | `ADMIN` | `Organization A` | ADMIN-A                      |
| 3 | `ADMIN-B@proton.net`    | `bvhgSI*7&@)=_*)`    | `ADMIN` | `Organization B` | ADMIN-B                      |
| 4 | `mishaZil@proton.net`   | `bvhgSI*7&@)++`      | `ADMIN` | `ООО "Зил"`      | MishaZil MichaZilov.         |
| 5 | `nikitaZil@proton.net`  | `bvhgSI*7&@)=_`      | `ADMIN` | `ООО "Космос"`   | NikitaKosmos NikitaKosmosov. |
| 6 | `admin@klever.ru`       | `bvhgSI*7&@)++!@$`   | `ADMIN` | `ООО "Клевер"`   | AdminKlever AdminKleverov.   |
| 7 | `ivanRomashka@mail.com` | `v2F4r1C4@$aD`       | `ADMIN` | `ООО "Ромашка"`  | InanRomashka IvanRomashkov.  |
| 8 | `vladSokol@mail.ru`     | `fhgkJFG@3%^&%`      | `ADMIN` | `ООО "Сокол"`    | VladSokol VladSokolov.       |
|   | `@proton.net` | `bvhgSI*7&@)=_((`      | `ADMIN` | `` |    |
|   | `@proton.net` | `bvhgSI*7&@)=_*^`      | `ADMIN` | `` |    |

### 4.3 USER

|  № | Email                 | Password                  | Role   | Организация     | Полное имя / комментарий        |
|:---|-----------------------|---------------------------|--------|-----------------|---------------------------------|
|  1 | `dimac@mail.com`      | `v2F4r1C4!#SD`            | `USER` | `Demo Company`  | Dima Dmitriev.                  |
|  2 | `user@test.com`       | `useR12345678%^A`         | `USER` | `Demo Company`  | UserTest UserTestov.            |
|  3 | `user2@test.com`      | `DGFGFNjghj&*46Df*&df`    | `USER` | `Demo Company`  | UserTest2 UserTestov            |
|  4 | `user3@test.com`      | `DGFGFNjghj&*46Df*&(dd`   | `USER` | `Demo Company`  | UserTest3 UserTestov            |
|  5 | `user4@test.com`      | `DGFGFNjghj&*46Df*&++`    | `USER` | `Demo Company`  | UserTest4 UserTestov            |
|  6 | `user5@test.com`      | `DGFGFNjghj&*46Df*&+++`   | `USER` | `Demo Company`  | UserTest5 UserTestov            |
|  7 | `user6@test.com`      | `DGFGFNjghj&*46Df*===`    | `USER` | `Demo Company`  | UserTest6 UserTestov            |
|  8 | `user7@test.com`      | `DGFGFNjghj&*46Df++--))`  | `USER` | `Demo Company`  | UserTest7 UserTestov            |
|  9 | `user8@test.com`      | `DGFGFNjghj&*46Df====+++` | `USER` | `Demo Company`  | UserTest8 UserTestov            |
| 10 | `USER-A1@proton.ru`   | `DGFGFNjghj&*46Df&&&`     | `USER` | —               | USER-A1                         |
| 11 | `USER-A2@proton.ru`   | `DGFGFNjghj&*46Df***`     | `USER` | —               | USER-A2                         |
| 12 | `USER-B1@mail.ru`     | `DGFGFNjghj&*46D))*`      | `USER` | —               | USER-B1                         |
| 13 | `use12@zil.ru`        | `DGFGFNjghj&*46Dfg**`     | `USER` | `ООО "Зил"`     | User1Zil User1Zilov.            |
| 14 | `user2@zil.ru`        | `DGFGFNjghj&*46Df*&(`     | `USER` | `ООО "Зил"`     | User2Zil User2Zilov.            |
| 15 | `user1@klever.ru`     | `DGFGFNjghj&*46Dfg`       | `USER` | `ООО "Клевер"`  | User1Klever User1Kleverov.      |
| 16 | `user2@klever.ru`     | `DGFGFNjghj&*46Dfg!@`     | `USER` | `ООО "Клевер"`  | User2Klever User2Kleverov.      |
| 17 | `petiyadf@yandex.com` | `DGFGFNjghj&*46`          | `USER` | `ООО "Ромашка"` | PetiyaRomashka PetiyaRomashkov. |
| 18 | `vasiya@gmail.com`    | `dfghjGH75!+Sdd`          | `USER` | `ООО "Сокол"`   | VasiyaSokol VasiyaSokolov.      |

---

### 4.4 Новые аккаунты (для расширения)

| № | Email | Password | Role | Организация | Полное имя / комментарий |
|---:|---|---|---|---|---|
| 1 | — | — | `ADMIN` | — | — |
| 2 | — | — | `ADMIN` | — | — |
| 3 | — | — | `ADMIN` | — | — |
| 4 | — | — | `USER` | — | — |
| 5 | — | — | `USER` | — | — |
| 6 | — | — | `USER` | — | — |
| 7 | — | — | `USER` | — | — |
| 8 | — | — | `USER` | — | — |

Если нужен ещё один аккаунт, добавляй строку в таблицу соответствующей роли и организации.
Для нового tenant предварительно заполни реестр организаций в разделе 3.

Пароли здесь **только тестовые**. В БД хранятся BCrypt-хеши; исходный пароль
из хеша восстановить нельзя. Для забытого пароля нужен reset.
Точный контракт длины: BCrypt ограничивает **72 байтами UTF-8**, не 72 символами.
Перед созданием проверять актуальную `PasswordPolicy` в backend.

## 5. Правила тестирования ролей

| Проверка | SUPER_ADMIN | ADMIN | USER |
|---|---|---|---|
| Чат обычного пользователя | нет (tenant-chat закрыт) | да | да |
| Организации (управление) | да | нет | нет |
| Пользователи других tenants | по правам платформы | нет | нет |
| Создание tenant USER | да | в своём tenant | нет |
| Глобальный audit / usage | да | нет | нет |
| Audit / usage своего tenant | глобальный scope | да | нет |

- Проверить **API-отказ 403**, а не только скрытую кнопку интерфейса.
- Пользователь организации A не видит чаты, документы и отчёты организации B.
- ADMIN не создаёт SUPER_ADMIN и не меняет чужую организацию.
- Роль и включённость проверяются backend; frontend-guard — лишь UX.
- Отключённый пользователь / tenant не должен получить новую рабочую сессию.
- При изменении auth/security epoch прежняя сессия не должна оставаться действующей.

## 6. Проверка PostgreSQL

Подключение к локальному контейнеру (имя контейнера проверить через `docker ps`):

```bash
docker exec -it safeai-postgres psql -U safeai -d safeai
```

**Организации:**

```sql
SELECT id, name, enabled, auth_version, version
FROM public.organizations
ORDER BY name;
```

**Пользователи, роли и владельцы:**

```sql
SELECT u.id, u.email, u.enabled AS user_enabled, u.token_version,
       o.name AS organization, o.enabled AS organization_enabled,
       string_agg(r.name, ', ' ORDER BY r.name) AS roles
FROM public.users u
JOIN public.organizations o ON o.id = u.organization_id
LEFT JOIN public.user_roles ur ON ur.user_id = u.id
LEFT JOIN public.roles r ON r.id = ur.role_id
GROUP BY u.id, u.email, u.enabled, u.token_version, o.name, o.enabled
ORDER BY o.name, u.email;
```

**Flyway V77 и последняя установленная версия:**

```sql
SELECT installed_rank, version, description, success
FROM public.flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 12;
```

**Состояние названий чатов:**

```sql
SELECT title_source, title, count(*) AS chats
FROM public.chat_sessions
GROUP BY title_source, title
ORDER BY chats DESC, title_source, title
LIMIT 30;
```

Не использовать прямой SQL `UPDATE` для сброса паролей: backend должен
обеспечить BCrypt, security epoch, аудит и отзыв старых сессий.

## 7. Диагностика авторизации

| Симптом | Что проверить |
|---|---|
| Login `401` | Email/пароль, статус аккаунта, rate-limit |
| Login `200`, затем `/me` `401/403` | Cookie, CSRF, user/org enabled, security epochs |
| ADMIN видит чужих пользователей | Backend tenant scope, RBAC и фильтры SQL |
| Чат не переименовывается | `title_source`, первый USER turn, V77, обновление UI |
| Ошибка Flyway | Первая SQL-ошибка в логе и `flyway_schema_history` |
| Запрос AI завис / дублируется | ChatTurn state, idempotency, lease/fencing |

При auth-проблеме сначала проверить серверный ответ в DevTools → Network.
Не выполнять `FLUSHALL` как стандартное решение: это удаляет **весь Redis state**.
После локального reset пользовательского пароля обновить таблицу и журнал.

## 8. Запуск, тесты и Redis

### macOS / Linux (текущий путь проекта)

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk
docker compose -f infra/docker-compose.local.yml up -d postgres redis

cd backend
set -a
source ./.env
set +a
export SAFEAI_JWT_PUBLIC_KEY="$(cat .local-secrets/jwt-public.pem)"
export SAFEAI_JWT_PRIVATE_KEY="$(cat .local-secrets/jwt-private.pem)"
./mvnw spring-boot:run
```

В другом терминале:

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk/frontend
npm ci
npm run dev
```

**Команды проверки:**

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk/backend
./mvnw test
./mvnw -DskipTests compile

cd ../frontend
npm run build
npm test
```

### Windows (старый путь из оригинальной памятки)

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose -f docker-compose.local.yml up -d postgres redis
cd /d "D:\Java projects\Safeai-desk\backend"
mvnw.cmd spring-boot:run
```

### Redis — только диагностика

```bash
docker ps
docker exec -it safeai-redis redis-cli PING
```

Для Redis с аутентификацией использовать подходящие credentials из local `.env`.
Не выводить пароль в history/логи без необходимости и не использовать публичный Redis.
`FLUSHDB`/`FLUSHALL` — **разрушительные команды**, только для выделенной local БД
после проверки номера базы, контейнера и того, что данные не нужны.

### Пересоздание инфраструктуры

```bash
# НЕ ВЫПОЛНЯТЬ без проверки томов и бэкапа:
# docker compose -f infra/docker-compose.local.yml down -v
```

`down` без `-v` останавливает контейнеры без намеренного удаления volume.

## 9. Проверки API через curl

Browser-flow использует HttpOnly cookie и CSRF. Cookie-файлы — локальные секреты;
не добавлять их в Git. Ниже шаблон без пароля в аргументах shell.

```bash
# В локальной среде:
curl -i -c /tmp/safeai-cookies.txt http://localhost:8080/api/auth/csrf
# Затем извлечь XSRF-TOKEN из cookie jar и передать как X-XSRF-TOKEN.
# Не копировать чужие cookies и токены в документацию.
```

Ключевые endpoints для ручной диагностики:

```text
POST  /api/auth/login
GET   /api/auth/me
POST  /api/auth/logout
GET   /api/chats
POST  /api/chats
POST  /api/chats/{chatId}/messages
GET   /api/chats/{chatId}
```

Проверять формы и названия маршрутов по актуальным controllers в checkout.

## 10. Регрессионный чек-лист

### Auth / tenants

- [ ] SUPER_ADMIN входит и видит платформенный scope.
- [ ] ADMIN и USER входят в свой tenant.
- [ ] Неверный пароль и rate-limit дают ожидаемую ошибку.
- [ ] Logout завершает сессию; старый refresh нельзя использовать.
- [ ] ADMIN не получает чужих пользователей или tenant-данные.
- [ ] Disable user/tenant блокирует доступ и новые разрешения на provider I/O.

### Chat / V77

- [ ] Пустой новый чат отображается как «Новый чат».
- [ ] После первого принятого USER message появляется осмысленное название.
- [ ] Последующие сообщения не перезаписывают `AUTO`.
- [ ] Ручное переименование защищает `USER` от автоматической замены.
- [ ] Retry с тем же `clientRequestId` не создаёт второе переименование.
- [ ] При rollback название и turn меняются атомарно.
- [ ] Записи со старым «Новый чат» обработаны согласно безопасной политике V77.
- [ ] V77 не отключила trigger V59 и не изменила другие title.
- [ ] Изоляция чатов по пользователю и организации работает.

### AI / квоты / lease

- [ ] Route-bound `ChatQuotaReservation` сохраняет доказуемость upper bound.
- [ ] Unknown pricing не превращается в FREE или стоимость `$0`.
- [ ] Watchdog продлевает lease по времени PostgreSQL.
- [ ] Stale `processing_token` не продлевает lease и не финализирует turn.
- [ ] `AMBIGUOUS` не приводит к blind retry provider.

### Docs / usage / audit

- [ ] Required audit не превращён в best-effort.
- [ ] Knowledge ACL не открывает cross-tenant документы.
- [ ] Отчёты usage не смешивают организации.
- [ ] Список чатов в UI обновляет заголовок после отправки сообщения.

## 11. Перед коммитом

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk
git status
git check-ignore -v docs/local/TEST_ACCOUNTS.local.md
git add -A
git restore --staged commit-message.txt
git diff --cached --name-only
git diff --cached --stat
git diff --cached
git commit -F commit-message.txt
git log -1 --stat
git push
```

Не продолжать, если `git diff --cached --name-only` содержит `.env`, приватные ключи,
локальные cookie-файлы, эту памятку или иные credentials.

## 12. Журнал изменений тестовых данных

| Дата | Объект / email | Действие | Результат / замечание |
|---|---|---|---|
| — | — | — | — |
| — | — | — | — |
| — | — | — | — |
| — | — | — | — |

**Порядок ведения:** после изменения пароля, роли, организации или статуса
сразу обновить строку в реестре и добавить короткую запись в этот журнал.