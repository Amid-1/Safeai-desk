/* Safeai-desk/backend/src/main/resources/db/migration/V3__seed_demo_admin.sql */
insert into organizations (id, name, created_at)
values (
           'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
           'Demo Company',
           now()
       )
    on conflict (id) do nothing;


insert into users (
    id,
    organization_id,
    email,
    password_hash,
    full_name,
    enabled,
    created_at
)
values (
           'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
           'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
           'admin@test.com',
           '$2y$10$qcWB2wkTGlA7MvSoYdPFy.7R7BZzvifDywN4hOUd9ipijInEF7CjG',
           'Demo Admin',
           true,
           now()
       )
    on conflict (email) do nothing;


insert into user_roles (user_id, role_id)
select u.id, r.id
from users u
         cross join roles r
where u.email = 'admin@test.com'
  and r.name = 'ADMIN'
    on conflict do nothing;