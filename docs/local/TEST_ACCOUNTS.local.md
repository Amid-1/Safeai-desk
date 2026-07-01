# SafeAI Desk — локальная памятка для тестовых аккаунтов

> **Важно:** этот файл предназначен только для локальной разработки и ручного тестирования.  
> Не коммитить в Git. Не использовать в production. Не хранить здесь реальные пароли.

---

## Где хранить файл в проекте

Рекомендуемый путь:

```text
Safeai-desk/docs/local/TEST_ACCOUNTS.local.md
```

Папку `docs/local/` лучше добавить в `.gitignore`:

```gitignore
# Local development notes and test credentials
docs/local/
```

---

## Локальные адреса

```text
Frontend: http://localhost:5173
Backend:  http://localhost:8080
Health:   http://localhost:8080/actuator/health
```

---

## Demo organization

```text
Organization name: Demo Company
Organization ID:   aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
```

---

## Platform organization

```text
Organization name: SafeAI Platform
Organization ID:   00000000-0000-0000-0000-000000000001
```

Назначение:

```text
SafeAI Platform — служебная платформенная организация для SUPER_ADMIN.
Ее нельзя отключать или переименовывать через обычный organization-management flow.
```

---

## Супер админ

| Email | Password | Role | Комментарий |
|---|---|---|---|
| `superadmin@test.com` | `Admin_Dev_2026!Strong#91` | `SUPER_ADMIN` | Платформенный администратор. Используется для создания организаций и администраторов. |

---

## Администраторы

| № | Email | Password | Role | Полное имя / комментарий |
|---:|---|---|---|---|
| 1 | `admin@test.com` | `admin123wER!%` | `ADMIN` | Demo Admin Adminov. Demo admin из seed-миграции. |
| 2 | `ivan@mail.com` | `v2F4r1C4@$aD` | `ADMIN` | Ivan Ivanov. Создан вручную через frontend/admin users. |
| 3 | `vLAd@mail.ru` | `fhgkJFG@3%^&%` | `ADMIN` | Vlad Vladov. |
| 4 | `MiSha@proton.net` | `bvhgSI*7&@)++` | `ADMIN` | Misha Michov. |

---

## Обычные пользователи

| № | Email | Password | Role | Полное имя / комментарий |
|---:|---|---|---|---|
| 1 | `dimac@mail.com` | `v2F4r1C4!#SD` | `USER` | Dima Dmitriev. Создан вручную через frontend/admin users. |
| 2 | `user1@test.com` | `useR12345678%^` | `USER` | User Userov. Если пароль был изменен через reset password, использовать новый пароль. |
| 3 | `Vasiya@gmail.com` | `dfghjGH75!+Sdd` | `USER` | Vasiya Vasilliev. |
| 4 | `PetiyaDF@yandex.com` | `DGFGFNjghj&*46` | `USER` | Petiya Petrov. |

---

## Быстрый вход

### SUPER_ADMIN

```text
Email:    superadmin@test.com
Password: Admin_Dev_2026!Strong#91
```

### Seed ADMIN

```text
Email:    admin@test.com
Password: admin123wER!%
```

### Второй ADMIN

```text
Email:    ivan@mail.com
Password: v2F4r1C4@$aD
```

### USER

```text
Email:    dimac@mail.com
Password: v2F4r1C4!#SD
```

---

## Проверка ролей через UI

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
- создавать ADMIN/USER;
- смотреть audit по всей платформе;
- смотреть usage по всей платформе;
- фильтровать audit/usage по organizationId.
```

SUPER_ADMIN не должен:

```text
- создавать еще одного SUPER_ADMIN через обычную форму;
- случайно создавать пользователей в SafeAI Platform;
- отключать или переименовывать SafeAI Platform.
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
- назначать ADMIN, если backend policy запрещает ADMIN создавать других ADMIN.
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

Backend должен возвращать:

```text
403 FORBIDDEN
```

---

## Проверка ролей через backend

### Проверить список пользователей

```sql
select
    u.email,
    u.enabled,
    o.name as organization_name,
    string_agg(r.name, ', ' order by r.name) as roles
from users u
join organizations o on o.id = u.organization_id
left join user_roles ur on ur.user_id = u.id
left join roles r on r.id = ur.role_id
group by u.email, u.enabled, o.name
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

---

## Напоминание по паролям

Пароли в БД хранятся как BCrypt hash, а не как обычный текст.

Это значит:

```text
- настоящий пароль восстановить нельзя;
- можно только проверить введенный пароль;
- если пароль забыт — нужно задать новый пароль или создать нового пользователя.
```

Для локальных тестовых аккаунтов лучше использовать сильный формат:

```text
минимум 12 символов
строчная буква
заглавная буква
цифра
спецсимвол
```

Примеры:

```text
Admin_Dev_2026!Strong#91
v2F4r1C4!#SD
v2F4r1C4@$aD
```

---

## Что делать, если пароль забыт

Варианты для local/dev:

```text
1. Зайти под ADMIN/SUPER_ADMIN и сделать Reset password через /admin/users.
2. Создать нового USER через /admin/users.
3. В локальной БД вручную обновить password_hash, если это только dev-среда.
```

---

## Полезные команды

### Запустить инфраструктуру

```bat
cd /d "D:\Java projects\Safeai-desk"
docker compose -f infra/docker-compose.yml up -d postgres redis
```

### Зайти в PostgreSQL

```bat
docker exec -it safeai-postgres psql -U safeai -d safeai
```

### Проверить пользователей

```sql
select email, enabled, organization_id
from users
order by email;
```

### Проверить Flyway migrations

```sql
select version, description, success
from flyway_schema_history
order by installed_rank;
```

### Очистить Redis rate-limit

Redis может требовать пароль. Если Redis настроен с паролем, команда без `-a` вернет:

```text
NOAUTH Authentication required
```

Сначала посмотри переменные контейнера:

```bat
docker inspect safeai-redis --format "{{range .Config.Env}}{{println .}}{{end}}"
```

Или команду запуска:

```bat
docker inspect safeai-redis --format "{{json .Config.Cmd}}"
```

Потом очисти Redis:

```bat
docker exec -it safeai-redis redis-cli -a REDIS_PASSWORD FLUSHDB
```

---

## API-проверки через curl

Browser-flow использует cookies и CSRF. Для ручной проверки через curl удобнее сохранять cookies в файл.

### Получить CSRF cookie

```bat
curl -i -c cookies.txt http://localhost:8080/api/auth/csrf
```

После этого возьми значение `XSRF-TOKEN` из `cookies.txt` и подставь в header `X-XSRF-TOKEN`.

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

## Быстрый ручной regression checklist

### Auth

```text
[ ] SUPER_ADMIN входит успешно.
[ ] ADMIN входит успешно.
[ ] USER входит успешно.
[ ] Неверный пароль возвращает ошибку.
[ ] После нескольких неверных попыток срабатывает login rate-limit.
[ ] Logout очищает сессию.
```

### Organizations

```text
[ ] SUPER_ADMIN видит Organizations.
[ ] SUPER_ADMIN создает новую организацию.
[ ] SafeAI Platform не имеет кнопок Rename/Disable.
[ ] ADMIN не видит Organizations в меню.
```

### Users

```text
[ ] SUPER_ADMIN видит пользователей.
[ ] ADMIN видит только пользователей своей организации.
[ ] USER не видит Users.
[ ] Reset password работает.
[ ] Enable/Disable работает.
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
```

---

## Коммит и пуш с файлом длинного сообщения

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
git push -u origin имя-ветки
```

---

## Важно перед коммитом

Проверь, что файл с тестовыми аккаунтами не попал в staged:

```bat
git status
```

В staged не должно быть:

```text
backend/.env
docs/local/TEST_ACCOUNTS.local.md
```

Если попало — убрать из staged:

```bat
git restore --staged backend/.env
git restore --staged docs/local/TEST_ACCOUNTS.local.md
```
