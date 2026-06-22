/*backend/src/main/resources/db/migration/V16__fix_super_admin_password_and_user_email_unique.sql*/
update users
set password_hash = '$2a$10$b4R6mlei4AGTw40ihqvBu.W3SUB6kwZ7S2hN1eG.Wm5uuylgetl5.',
    token_version = token_version + 1
where email = 'superadmin@test.com';

alter table users
drop constraint if exists users_email_key;