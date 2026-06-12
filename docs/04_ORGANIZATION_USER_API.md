# SafeAI Desk — Organization and User API verification

Все команды требуют JWT с ролью `ADMIN`.

## 1. Подготовка

Получить token:

```bat
curl -i -X POST http://localhost:8080/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"admin@test.com\",\"password\":\"admin123\"}"
```

Сохранить:

```bat
set "TOKEN=PASTE_TOKEN_HERE"
```

## 2. Список организаций

```bat
curl -i http://localhost:8080/api/organizations ^
  -H "Authorization: Bearer %TOKEN%"
```

Ожидаемо:

```text
HTTP/1.1 200
```

Seed organization:

```text
aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa | Demo Company
```

## 3. Создать организацию

```bat
curl -i -X POST http://localhost:8080/api/organizations ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"Test Company\"}"
```

Ожидаемо:

```text
HTTP/1.1 200
```

## 4. Организация не найдена

```bat
curl -i http://localhost:8080/api/organizations/11111111-2222-3333-4444-555555555555 ^
  -H "Authorization: Bearer %TOKEN%"
```

Ожидаемо:

```text
HTTP/1.1 404
```

## 5. Пустое имя организации

```bat
curl -i -X POST http://localhost:8080/api/organizations ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"\"}"
```

Ожидаемо:

```text
HTTP/1.1 400
```

## 6. Список пользователей

```bat
curl -i http://localhost:8080/api/users ^
  -H "Authorization: Bearer %TOKEN%"
```

Ожидаемо:

```text
HTTP/1.1 200
```

Seed user:

```text
admin@test.com | ADMIN
```

## 7. Создать пользователя

```bat
curl -i -X POST http://localhost:8080/api/users ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"organizationId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"email\":\"user1@test.com\",\"password\":\"user12345\",\"fullName\":\"User One\",\"roles\":[\"USER\"]}"
```

Ожидаемо:

```text
HTTP/1.1 200
```

Проверить, что в ответе нет:

```text
password
passwordHash
```

## 8. Повторный email

Повторить создание `user1@test.com`.

Ожидаемо:

```text
HTTP/1.1 409
```

## 9. Несуществующая организация

```bat
curl -i -X POST http://localhost:8080/api/users ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"organizationId\":\"11111111-2222-3333-4444-555555555555\",\"email\":\"newadmin@test.com\",\"password\":\"admin123\",\"fullName\":\"Admin User\",\"roles\":[\"ADMIN\"]}"
```

Ожидаемо:

```text
HTTP/1.1 404
```

## 10. Некорректный UUID

```bat
curl -i -X POST http://localhost:8080/api/users ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"organizationId\":\"NOT_A_UUID\",\"email\":\"bad-uuid@test.com\",\"password\":\"admin123\",\"fullName\":\"Bad UUID\",\"roles\":[\"ADMIN\"]}"
```

Ожидаемо:

```text
HTTP/1.1 400
```

## 11. Успешный результат

```text
✅ organizations list работает
✅ organization create работает
✅ organization 404 работает
✅ validation 400 работает
✅ users list работает
✅ user create работает
✅ password не возвращается
✅ duplicate email возвращает 409
✅ bad organizationId возвращает 404
✅ bad UUID возвращает 400
```
