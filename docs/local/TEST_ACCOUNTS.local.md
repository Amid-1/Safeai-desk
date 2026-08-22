# SafeAI Desk — локальная памятка для тестовых аккаунтов

> **Важно:** файл предназначен только для локальной разработки и ручного тестирования.  
> Не коммитить в Git. Не использовать в production. Не хранить здесь реальные пароли, токены, API-ключи или production secrets.

---

## 1. Где хранить файл

Рекомендуемый путь внутри проекта:

```text
Safeai-desk/docs/local/TEST_ACCOUNTS.local.md
```

Рекомендуемое правило в `.gitignore`:

```gitignore
# Local development notes and test credentials
docs/local/
```

Перед каждым коммитом проверяй, что файл не попал в staged:

```bat
git status
```

Если файл случайно добавлен в staged:

```bat
git restore --staged docs/local/TEST_ACCOUNTS.local.md
```

---

## 2. Локальные адреса

```text
Frontend: http://localhost:5173
Backend:  http://localhost:8080
Health:   http://localhost:8080/actuator/health
```

---

## 3. Организации

### SafeAI Platform

```text
Organization name: SafeAI Platform
Organization ID:   00000000-0000-0000-0000-000000000001
```

Назначение:

```text
SafeAI Platform — служебная платформенная организация для SUPER_ADMIN.
Через обычный organization-management flow ее нельзя отключать или переименовывать.
Через обычный user-management flow в нее нельзя создавать пользователей.
```

### Demo Company

```text
Organization name: Demo Company
Organization ID:   aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
```

Назначение:

```text
Demo Company — тестовая customer organization для локальной разработки.
```

### Дополнительные локальные организации

Организации, созданные вручную через UI, могут отличаться после пересоздания БД.

| Организация     | Комментарий |
|-----------------|---|
| `ООО "Клевер"`  | Тестовая customer organization |
| `ООО "Ромашка"` | Тестовая customer organization |

---

## 4. Тестовые аккаунты

### 4.1 SUPER_ADMIN

| Email | Password | Role | Организация | Комментарий |
|---|---|---|---|---|
| `superadmin@test.com` | `Admin_Dev_2026!Strong#91` | `SUPER_ADMIN` | `SafeAI Platform` | Платформенный администратор. Используется для создания организаций и администраторов. |

### 4.2 ADMIN

> Если для аккаунта выполнялся **Reset password** через UI, актуальным является последний заданный пароль.  
> Старый пароль из этой таблицы после reset password больше не подойдет.

| № | Email         | Password             | Role | Организация    | Полное имя / комментарий                                               |
|---:|---------------|----------------------|---|----------------|------------------------------------------------------------------------|
| 1 | `admin@test.com` | `bvhgSI*7&@)++!@$HJ` | `ADMIN` | `Demo Company` | AdminTest AdminTestov. Seed/local admin.                               |
| 2 | `admin@klever.ru` | `bvhgSI*7&@)++!@$`   | `ADMIN` | `ООО "Клевер"` | AdminKlever AdminKleverov. Создан вручную через frontend/admin users.  |
| 3 | `ivanRomashka@mail.com` | `v2F4r1C4@$aD`       | `ADMIN` | `ООО "Ромашка"` | InanRomashka IvanRomashkov. Создан вручную через frontend/admin users. |
| 4 | `vladSokol@mail.ru` | `fhgkJFG@3%^&%`      | `ADMIN` | `ООО "Сокол"`  | VladSokol VladSokolov. В БД email нормализуется к lower-case.          |
| 5 | `mishaZil@proton.net` | `bvhgSI*7&@)++`      | `ADMIN` | `ООО "Зил"`    | MishaZil MichaZilov. В БД email нормализуется к lower-case.            |
| 5 | `nikitaZil@proton.net` | `bvhgSI*7&@)=_`      | `ADMIN` | `ООО "Космос"` | NikitaKosmos NikitaKosmosov. В БД email нормализуется к lower-case.    |
| 5 | `ADMIN-A@proton.net` | `bvhgSI*7&@)=_@#`    | `ADMIN` | `Organization A`  | ADMIN-A  |
| 5 | `ADMIN-B@proton.net` | `bvhgSI*7&@)=_*)`    | `ADMIN` | `Organization B`  |  ADMIN-B  |
| 5 | `@proton.net` | `bvhgSI*7&@)=_((`    | `ADMIN` | `` |    |
| 5 | `@proton.net` | `bvhgSI*7&@)=_*^`    | `ADMIN` | `` |    |

### 4.3 USER

| № | Email                 | Password                  | Role | Организация     | Полное имя / комментарий                                                                  |
|--:|-----------------------|---------------------------|---|-----------------|-------------------------------------------------------------------------------------------|
| 1 | `dimac@mail.com`      | `v2F4r1C4!#SD`            | `USER` | `Demo Company`  | Dima Dmitriev. Создан вручную через frontend/admin users.                                 |
| 2 | `user@test.com`       | `useR12345678%^A`         | `USER` | `Demo Company`  | UserTest UserTestov. Если пароль менялся через reset password, использовать новый пароль. |
| 3 | `vasiya@gmail.com`    | `dfghjGH75!+Sdd`          | `USER` | `ООО "Сокол"`   | VasiyaSokol VasiyaSokolov. В БД email нормализуется к lower-case.                         |
| 4 | `petiyadf@yandex.com` | `DGFGFNjghj&*46`          | `USER` | `ООО "Ромашка"` | PetiyaRomashka PetiyaRomashkov. В БД email нормализуется к lower-case.                    |
| 5 | `user1@klever.ru`     | `DGFGFNjghj&*46Dfg`       | `USER` | `ООО "Клевер"`  | User1Klever User1Kleverov. В БД email нормализуется к lower-case.                         |
| 6 | `user2@klever.ru`     | `DGFGFNjghj&*46Dfg!@`     | `USER` | `ООО "Клевер"`  | User2Klever User2Kleverov. В БД email нормализуется к lower-case.                         |
| 6 | `use12@zil.ru`        | `DGFGFNjghj&*46Dfg**`     | `USER` | `ООО "Зил"`     | User1Zil User1Zilov. В БД email нормализуется к lower-case.                               |
| 6 | `user2@zil.ru`        | `DGFGFNjghj&*46Df*&(`     | `USER` | `ООО "Зил"`     | User2Zil User2Zilov. В БД email нормализуется к lower-case.                               |
| 6 | `USER-A1@proton.ru`   | `DGFGFNjghj&*46Df&&&`     | `USER` | ``     | USER-A1                                                                                   |
| 6 | `USER-A2@proton.ru`   | `DGFGFNjghj&*46Df***`     | `USER` | ``     | USER-A2                                                                                   |
| 6 | `USER-B1@mail.ru`     | `DGFGFNjghj&*46D))*`      | `USER` | ``     | USER-B1                                                                                   |
| 6 | `user2@test.com`      | `DGFGFNjghj&*46Df*&df`    | `USER` | `Demo Company`     | UserTest2 UserTestov                                                                      |
| 6 | `user3@test.com`      | `DGFGFNjghj&*46Df*&(dd`   | `USER` | `Demo Company`     | UserTest3 UserTestov                                                                      |
| 6 | `user4@test.com`      | `DGFGFNjghj&*46Df*&++`    | `USER` | `Demo Company`     | UserTest4 UserTestov                                                                      |
| 6 | `user5@test.com`      | `DGFGFNjghj&*46Df*&+++`   | `USER` | `Demo Company`     | UserTest5 UserTestov                                                                      |
| 6 | `user6@test.com`      | `DGFGFNjghj&*46Df*===`    | `USER` | `Demo Company`     | UserTest6 UserTestov                                                                      |
| 6 | `user7@test.com`      | `DGFGFNjghj&*46Df++--))`  | `USER` | `Demo Company`     | UserTest7 UserTestov                                                                      |
| 6 | `user8@test.com`      | `DGFGFNjghj&*46Df====+++` | `USER` | `Demo Company`     | UserTest8 UserTestov                                                                      |

---

## 5. Быстрый вход

### SUPER_ADMIN

```text
Email:    superadmin@test.com
Password: Admin_Dev_2026!Strong#91
```

### ADMIN Demo Company

```text
Email:    admin@test.com
Password: bvhgSI*7&@)++!@$HJ
```

### ADMIN ООО "Клевер"

```text
Email:    admin@klever.ru
Password: bvhgSI*7&@)++!@$
```

### USER Demo Company

```text
Email:    dimac@mail.com
Password: v2F4r1C4!#SD
```

---

## 6. Проверка ролей через UI

### SUPER_ADMIN должен видеть

```text
/chat
/admin/users
/admin/organizations
/admin/audit
/admin/usage
```

SUPER_ADMIN может:

```text
- создавать организации;
- видеть все организации;
- создавать ADMIN и USER в customer organizations;
- смотреть audit по всей платформе;
- смотреть usage по всей платформе;
- фильтровать audit/usage по organizationId;
- управлять USER и ADMIN, но не SUPER_ADMIN.
```

SUPER_ADMIN не должен:

```text
- создавать еще одного SUPER_ADMIN через обычную форму;
- создавать пользователей в SafeAI Platform;
- отключать или переименовывать SafeAI Platform;
- редактировать platform admin через обычный user-management flow.
```

### ADMIN должен видеть

```text
/chat
/admin/users
/admin/audit
/admin/usage
```

ADMIN может:

```text
- видеть пользователей только своей организации;
- создавать USER внутри своей организации;
- редактировать USER своей организации;
- сбрасывать пароль USER своей организации;
- включать/отключать USER своей организации;
- смотреть audit только своей организации;
- смотреть usage только своей организации;
- пользоваться чатом.
```

ADMIN не должен:

```text
- видеть /admin/organizations;
- создавать организации;
- видеть пользователей другой организации;
- смотреть audit/usage другой организации;
- назначать SUPER_ADMIN;
- создавать ADMIN;
- управлять другим ADMIN.
```

### USER должен видеть

```text
/chat
```

USER не должен иметь доступ к:

```text
/admin/users
/admin/organizations
/admin/audit
/admin/usage
```

Ожидаемая backend-реакция при прямом запросе к admin endpoints:

```text
403 FORBIDDEN
```

---

## 7. SQL-проверки

### Зайти в PostgreSQL

```bat
docker exec -it safeai-postgres psql -U safeai -d safeai
```

### Проверить конкретных пользователей

```sql
select
    u.email,
    u.enabled as user_enabled,
    u.token_version,
    o.name as organization_name,
    o.enabled as organization_enabled,
    array_agg(r.name order by r.name) as roles
from users u
join organizations o on o.id = u.organization_id
join user_roles ur on ur.user_id = u.id
join roles r on r.id = ur.role_id
where lower(u.email) in (
    lower('admin@test.com'),
    lower('admin@klever.ru'),
    lower('superadmin@test.com')
)
group by
    u.email,
    u.enabled,
    u.token_version,
    o.name,
    o.enabled
order by u.email;
```

Ожидаемо для активного ADMIN:

```text
user_enabled = true
organization_enabled = true
roles содержит ADMIN
```

### Проверить список пользователей

```sql
select
    u.email,
    u.enabled,
    u.token_version,
    o.name as organization_name,
    o.enabled as organization_enabled,
    string_agg(r.name, ', ' order by r.name) as roles
from users u
join organizations o on o.id = u.organization_id
left join user_roles ur on ur.user_id = u.id
left join roles r on r.id = ur.role_id
group by
    u.email,
    u.enabled,
    u.token_version,
    o.name,
    o.enabled
order by u.email;
```

### Проверить superadmin

```sql
select
    u.id,
    u.email,
    u.enabled,
    u.organization_id,
    o.name as organization_name,
    o.enabled as organization_enabled,
    u.token_version
from users u
join organizations o on o.id = u.organization_id
where lower(u.email) = lower('superadmin@test.com');
```

### Проверить роль superadmin

```sql
select
    u.email,
    r.name as role
from users u
join user_roles ur on ur.user_id = u.id
join roles r on r.id = ur.role_id
where lower(u.email) = lower('superadmin@test.com');
```

Ожидаемый результат:

```text
superadmin@test.com | SUPER_ADMIN
```

### Проверить Flyway migrations

```sql
select
    installed_rank,
    version,
    description,
    success
from flyway_schema_history
order by installed_rank;
```

---

## 8. Диагностика проблем входа

### Ситуация: неверный пароль

В DevTools → Network:

```text
POST /api/auth/login = 401
```

Типичный response:

```json
{
  "status": 401,
  "error": "UNAUTHORIZED",
  "message": "Требуется авторизация",
  "path": "/api/auth/login"
}
```

Что означает:

```text
email/password не приняты backend.
Нужно проверить пароль или сделать Reset password через SUPER_ADMIN.
```

Решение:

```text
1. Войти под SUPER_ADMIN.
2. Открыть /admin/users.
3. Нажать Reset password у нужного пользователя.
4. Задать новый пароль.
5. Войти под этим пользователем с новым паролем.
```

### Ситуация: login прошел, но пользователя выбрасывает обратно

В DevTools → Network:

```text
POST /api/auth/login = 200
GET  /api/auth/me    = 401 или 403
```

Возможные причины:

```text
- пользователь отключен;
- организация отключена;
- tokenVersion в JWT не совпадает с БД;
- устаревший Redis cache security-state;
- cookie/CSRF mismatch после ручных тестов.
```

Проверить состояние в БД:

```sql
select
    u.email,
    u.enabled as user_enabled,
    u.token_version,
    o.name as organization_name,
    o.enabled as organization_enabled,
    array_agg(r.name order by r.name) as roles
from users u
join organizations o on o.id = u.organization_id
join user_roles ur on ur.user_id = u.id
join roles r on r.id = ur.role_id
where lower(u.email) = lower('ВСТАВЬ_EMAIL')
group by
    u.email,
    u.enabled,
    u.token_version,
    o.name,
    o.enabled;
```

Очистить Redis в local/dev:

```bat
docker exec -it safeai-redis redis-cli -a safeai_redis_password FLUSHALL
```

После этого перезапустить backend.

---

## 9. Redis

### Проверить переменные контейнера Redis

```bat
docker inspect safeai-redis --format "{{range .Config.Env}}{{println .}}{{end}}"
```

### Проверить команду запуска Redis

```bat
docker inspect safeai-redis --format "{{json .Config.Cmd}}"
```

### Очистить Redis полностью

```bat
docker exec -it safeai-redis redis-cli -a safeai_redis_password FLUSHALL
```

### Очистить только текущую Redis DB

```bat
docker exec -it safeai-redis redis-cli -a safeai_redis_password FLUSHDB
```

Для local/dev допустимо использовать `FLUSHALL`, если Redis не содержит ничего важного.

---

## 10. Пароли

Пароли в БД хранятся как BCrypt hash, а не как обычный текст.

Это значит:

```text
- настоящий пароль из БД восстановить нельзя;
- можно только проверить введенный пароль через backend login;
- если пароль забыт — нужно задать новый пароль через Reset password или создать нового пользователя.
```

Минимальные требования password policy:

```text
минимум 12 символов
строчная буква
заглавная буква
цифра
спецсимвол
не более 72 символов
```

Примеры локальных паролей:

```text
Admin_Dev_2026!Strong#91
v2F4r1C4!#SD
v2F4r1C4@$aD
```

---

## 11. Полезные команды

### Запустить инфраструктуру

```bat
cd /d "D:\Java projects\Safeai-desk"
docker compose -f infra/docker-compose.yml up -d postgres redis
```

Если используется local compose из папки `infra`:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose -f docker-compose.local.yml up -d postgres redis
```

### Остановить инфраструктуру без удаления данных

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose -f docker-compose.local.yml down
```

### Пересоздать local DB и Redis volume

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose -f docker-compose.local.yml down -v
docker compose -f docker-compose.local.yml up -d postgres redis
```

### Запустить backend локально

```bat
cd /d "D:\Java projects\Safeai-desk\backend"

set SPRING_PROFILES_ACTIVE=local
set SAFEAI_JWT_SECRET=safeai-local-development-secret-key-change-this-value-please-123456789
set REDIS_PASSWORD=safeai_redis_password

mvnw.cmd spring-boot:run
```

### Запустить frontend локально

```bat
cd /d "D:\Java projects\Safeai-desk\frontend"
npm install
npm run dev
```

### Проверить backend tests

```bat
cd /d "D:\Java projects\Safeai-desk\backend"
mvnw.cmd test
```

### Проверить frontend build

```bat
cd /d "D:\Java projects\Safeai-desk\frontend"
npm run build
```

---

## 12. API-проверки через curl

Browser-flow использует cookies и CSRF. Для ручной проверки через curl удобно сохранять cookies в файл.

### Получить CSRF cookie

```bat
curl -i -c cookies.txt http://localhost:8080/api/auth/csrf
```

После этого нужно взять значение `XSRF-TOKEN` из `cookies.txt` и подставить в header `X-XSRF-TOKEN`.

### Login через cookie jar

```bat
curl -i -b cookies.txt -c cookies.txt ^
  -X POST http://localhost:8080/api/auth/login ^
  -H "Content-Type: application/json" ^
  -H "X-XSRF-TOKEN: ВСТАВЬ_XSRF_TOKEN" ^
  -d "{\"email\":\"superadmin@test.com\",\"password\":\"Admin_Dev_2026!Strong#91\"}"
```

### Проверить текущего пользователя через cookies

```bat
curl -i -b cookies.txt http://localhost:8080/api/auth/me
```

### Проверить список пользователей через cookies

```bat
curl -i -b cookies.txt http://localhost:8080/api/users
```

---

## 13. Быстрый regression checklist

### Auth

```text
[ ] SUPER_ADMIN входит успешно.
[ ] ADMIN входит успешно.
[ ] USER входит успешно.
[ ] Неверный пароль возвращает понятную ошибку.
[ ] После нескольких неверных попыток срабатывает login rate-limit.
[ ] Logout очищает сессию.
```

### Organizations

```text
[ ] SUPER_ADMIN видит Organizations.
[ ] SUPER_ADMIN создает новую организацию.
[ ] SafeAI Platform не имеет кнопок Rename/Disable.
[ ] ADMIN не видит Organizations в меню.
[ ] USER не видит Organizations в меню.
```

### Users

```text
[ ] SUPER_ADMIN видит пользователей всех организаций.
[ ] SUPER_ADMIN вручную выбирает organization при создании пользователя.
[ ] ADMIN видит только пользователей своей организации.
[ ] ADMIN может создать только USER.
[ ] USER не видит Users.
[ ] Details работает.
[ ] Edit user работает.
[ ] Reset password работает.
[ ] Enable/Disable работает.
[ ] Make ADMIN доступен только SUPER_ADMIN.
[ ] Make USER доступен только SUPER_ADMIN для ADMIN.
[ ] Platform admin защищен от обычных действий.
[ ] Success-сообщение исчезает через несколько секунд.
```

### Chat

```text
[ ] USER создает чат.
[ ] USER отправляет сообщение.
[ ] ASSISTANT response появляется.
[ ] При ошибке AI provider появляется FAILED assistant message.
[ ] USER не видит чужие чаты.
```

### Audit / Usage

```text
[ ] ADMIN видит audit только своей организации.
[ ] SUPER_ADMIN видит global audit.
[ ] ADMIN видит usage только своей организации.
[ ] SUPER_ADMIN видит global usage.
[ ] Фильтры dateFrom/dateTo работают как UTC inclusive/exclusive range.
```

---

## 14. Коммит и пуш с файлом длинного сообщения

Если есть файл `commit-message.txt`:

```bat
cd /d "D:\Java projects\Safeai-desk"

git status
git add .

git restore --staged backend/.env
git restore --staged docs/local/TEST_ACCOUNTS.local.md

git commit -F commit-message.txt
git push
```

Если ветка новая:

```bat
git branch --show-current
git push -u origin ИМЯ_ВЕТКИ
```

---

## 15. Важно перед коммитом

В staged не должно быть:

```text
backend/.env
backend/.env.prod
docs/local/TEST_ACCOUNTS.local.md
```

Проверить:

```bat
git status
```

Убрать из staged:

```bat
git restore --staged backend/.env
git restore --staged backend/.env.prod
git restore --staged docs/local/TEST_ACCOUNTS.local.md
```

Если файл с тестовыми аккаунтами уже был случайно закоммичен, его нужно удалить из Git tracking:

```bat
git rm --cached docs/local/TEST_ACCOUNTS.local.md
git commit -m "chore: убрать локальную памятку с тестовыми аккаунтами из git"
```

---

## 16. Короткая памятка

```text
SUPER_ADMIN создает организации и первых ADMIN.
ADMIN создает USER только внутри своей организации.
USER работает только с Chat.
SafeAI Platform — только для SUPER_ADMIN.
Пароль нельзя восстановить из БД, только сбросить.
POST /api/auth/login = 401 означает неверные credentials.
POST /api/auth/login = 200, затем /api/auth/me = 401/403 означает проблему security-state/cookies/cache.
```



Выполни по порядку:

cd /d "D:\Java projects\Safeai-desk\frontend"

Удалить старые зависимости:

rmdir /s /q node_modules

Удалить старый lock-файл:

del package-lock.json

Установить всё заново:

npm install

После установки проверить Vitest:

npm list vitest

Должно появиться примерно:

safeai-desk-frontend@0.0.1
└── vitest@4.1.10

Затем:

npm run build

и:

npm test

Также проверь расположение setup.ts:

dir /s setup.ts