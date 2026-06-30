/* Safeai-desk/backend/src/main/resources/db/local-migration/V1000__seed_local_demo_data.sql */
insert into organizations (
    id,
    name,
    enabled,
    created_at,
    updated_at,
    version
)
values (
           'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
           'Demo Company',
           true,
           now(),
           now(),
           0
       )
on conflict (id) do nothing;


insert into users (
    id,
    organization_id,
    email,
    password_hash,
    full_name,
    enabled,
    created_at,
    updated_at,
    token_version,
    version
)
values (
           'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
           'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
           'admin@test.com',
           '$2a$10$qcWB2wkTGlA7MvSoYdPFy.7R7BZzvifDywN4hOUd9ipijInEF7CjG',
           'Demo Admin',
           true,
           now(),
           now(),
           0,
           0
       )
on conflict do nothing;


insert into users (
    id,
    organization_id,
    email,
    password_hash,
    full_name,
    enabled,
    created_at,
    updated_at,
    token_version,
    version
)
values (
           '00000000-0000-0000-0000-000000000101',
           '00000000-0000-0000-0000-000000000001',
           'superadmin@test.com',
           '$2a$10$yJB.CHsf1cPn3lYot0djHuiBE4Dk7o8iFkZdCNLAoaCy5TFvIxS36',
           'SafeAI Platform Admin',
           true,
           now(),
           now(),
           0,
           0
       )
on conflict do nothing;


insert into user_roles (user_id, role_id)
select u.id, r.id
from users u
         join roles r on r.name = 'ADMIN'
where u.email = 'admin@test.com'
on conflict do nothing;


insert into user_roles (user_id, role_id)
select u.id, r.id
from users u
         join roles r on r.name = 'USER'
where u.email = 'admin@test.com'
on conflict do nothing;


insert into user_roles (user_id, role_id)
select u.id, r.id
from users u
         join roles r on r.name = 'SUPER_ADMIN'
where u.email = 'superadmin@test.com'
on conflict do nothing;