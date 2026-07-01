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
select
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
where not exists (
    select 1
    from users
    where lower(email) = lower('admin@test.com')
);


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
select
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
where not exists (
    select 1
    from users
    where lower(email) = lower('superadmin@test.com')
);


insert into user_roles (user_id, role_id)
select u.id, r.id
from users u
         join roles r on r.name = 'ADMIN'
where lower(u.email) = lower('admin@test.com')
on conflict (user_id, role_id) do nothing;


insert into user_roles (user_id, role_id)
select u.id, r.id
from users u
         join roles r on r.name = 'SUPER_ADMIN'
where lower(u.email) = lower('superadmin@test.com')
on conflict (user_id, role_id) do nothing;