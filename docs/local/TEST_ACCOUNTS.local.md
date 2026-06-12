# SafeAI Desk — локальная памятка для тестовых аккаунтов

> ВАЖНО: этот файл только для локальной разработки и ручного тестирования.
> Не коммитить в Git. Не использовать для production. Не хранить здесь реальные пароли.

## Где хранить в проекте

Рекомендуемый путь:

```text
Safeai-desk/docs/local/TEST_ACCOUNTS.local.md
```

Папку `docs/local/` лучше добавить в `.gitignore`.

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

## Администраторы

| № | Email | Password | Role | Комментарий |
|---|---|---|---|---|
| 1 | admin@test.com | admin123 | ADMIN | Demo admin из seed-миграции |
| 2 | ivan@mail.com | v2F4r1C4@$ | ADMIN | Создан вручную через frontend/admin users |

---

## Обычные пользователи

| № | Email | Password | Role | Комментарий |
|---|---|---|---|---|
| 1 | dimac@mail.com | v2F4r1C4!# | USER | Создан вручную через frontend/admin users |
| 2 | user1@test.com | UNKNOWN | USER | Пароль не сохранён в открытом виде; если нужен — создать нового USER или сделать отдельный reset password |

---

## Быстрый вход

### Админ

```text
Email:    admin@test.com
Password: admin123
```

### Второй админ

```text
Email:    ivan@mail.com
Password: v2F4r1C4@$
```

### Обычный пользователь

```text
Email:    dimac@mail.com
Password: v2F4r1C4!#
```

---

## Проверка ролей

### ADMIN должен видеть

```text
/chat
/admin/users
/admin/audit
/admin/usage
```

### USER должен видеть

```text
/chat
```

### USER не должен иметь доступ к admin endpoints

```text
/admin/users
/admin/audit
/admin/usage
```

Backend должен возвращать:

```text
403 FORBIDDEN
```

---

## Напоминание по паролям

Сейчас правила пароля на backend минимальные:

```text
min length: 6
max length: 100
```

Пока НЕ требуется:

```text
uppercase
lowercase
digit
special character
```

Но для тестовых аккаунтов лучше использовать более сильный формат:

```text
v2F4r1C4!#
v2F4r1C4@$
Admin123!
User123!
```

---

## Почему нельзя посмотреть забытый пароль

Пароли в БД хранятся как BCrypt hash, а не как обычный текст.

Это значит:

```text
настоящий пароль восстановить нельзя;
можно только проверить введённый пароль;
если пароль забыт — нужно задать новый пароль или создать нового пользователя.
```

---

## Полезные команды

### Login admin через curl

```bat
curl -X POST http://localhost:8080/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"admin@test.com\",\"password\":\"admin123\"}"
```

### Проверить текущего пользователя

```bat
curl http://localhost:8080/api/auth/me ^
  -H "Authorization: Bearer %TOKEN%"
```

### Проверить список пользователей

```bat
curl http://localhost:8080/api/users ^
  -H "Authorization: Bearer %TOKEN%"
```

---

## Что делать, если пароль user1@test.com нужен

Варианты:

```text
1. Создать нового USER через /admin/users.
2. Добавить отдельную backend-функцию Reset password.
3. В локальной БД вручную обновить password_hash, если это только dev-среда.
```

Правильный следующий функциональный шаг:

```text
POST /api/admin/users/{id}/reset-password
```

И audit event:

```text
USER_PASSWORD_RESET
```
