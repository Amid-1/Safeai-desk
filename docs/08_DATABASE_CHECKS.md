# SafeAI Desk — database checks

Команды SQL для проверки локальной PostgreSQL базы.

## 1. Flyway history

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select installed_rank, version, description, success from flyway_schema_history order by installed_rank;"
```

Ожидаемо:

```text
1 | 1 | init schema      | t
2 | 2 | seed roles       | t
3 | 3 | seed demo admin  | t
```

## 2. Таблицы

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "\dt"
```

Ожидаемо:

```text
organizations
users
roles
user_roles
chat_sessions
chat_messages
audit_events
flyway_schema_history
```

## 3. Roles

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select id, name from roles order by name;"
```

Ожидаемо:

```text
ADMIN
USER
```

## 4. Organizations

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select id, name, created_at from organizations order by created_at;"
```

Ожидаемо:

```text
aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa | Demo Company
```

## 5. Users

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select id, organization_id, email, password_hash, full_name, enabled, created_at from users order by created_at;"
```

Проверить:

```text
admin@test.com существует
password_hash не равен admin123
password_hash начинается с $2a$, $2b$ или $2y$
enabled = t
```

## 6. User roles

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select u.email, r.name from users u join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id order by u.email, r.name;"
```

Ожидаемо:

```text
admin@test.com | ADMIN
user1@test.com | USER
```

## 7. Chat sessions

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select id, user_id, title, created_at from chat_sessions order by created_at desc limit 10;"
```

## 8. Chat messages

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select session_id, role, content, model, input_tokens, output_tokens, cost_usd, created_at from chat_messages order by created_at desc limit 10;"
```

## 9. Audit events

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select event_type, user_id, details, created_at from audit_events order by created_at desc limit 10;"
```

## 10. Usage preview

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select model, sum(input_tokens) as input_tokens, sum(output_tokens) as output_tokens, sum(cost_usd) as cost_usd from chat_messages where role = 'ASSISTANT' group by model;"
```

Это пригодится для будущего Usage API.

## 11. Быстрый count всех таблиц

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select 'organizations' as table_name, count(*) from organizations union all select 'users', count(*) from users union all select 'roles', count(*) from roles union all select 'chat_sessions', count(*) from chat_sessions union all select 'chat_messages', count(*) from chat_messages union all select 'audit_events', count(*) from audit_events;"
```

## 12. Успешный результат

```text
✅ миграции success = t
✅ таблицы есть
✅ roles есть
✅ admin есть
✅ password_hash безопасный
✅ chat_sessions/chat_messages заполняются
✅ audit_events заполняется
```
