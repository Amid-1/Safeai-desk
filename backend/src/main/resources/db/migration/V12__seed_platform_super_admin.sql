/* Safeai-desk/backend/src/main/resources/db/migration/V12__seed_platform_super_admin.sql */

insert into organizations (
    id,
    name,
    created_at,
    version
)
values (
           '00000000-0000-0000-0000-000000000001',
           'SafeAI Platform',
           now(),
           0
       )
on conflict (id) do nothing;

insert into roles (id, name)
values ('33333333-3333-3333-3333-333333333333', 'SUPER_ADMIN')
on conflict (name) do nothing;

insert into users (
    id,
    organization_id,
    email,
    password_hash,
    full_name,
    enabled,
    created_at,
    token_version,
    version
)
values (
           '00000000-0000-0000-0000-000000000101',
           '00000000-0000-0000-0000-000000000001',
           'superadmin@test.com',
           '$2a$10$REPLACE_WITH_REAL_BCRYPT_HASH',
           'SafeAI Platform Admin',
           true,
           now(),
           0,
           0
       )
on conflict (email) do nothing;

insert into user_roles (user_id, role_id)
select u.id, r.id
from users u
         cross join roles r
where u.email = 'superadmin@test.com'
  and r.name = 'SUPER_ADMIN'
on conflict do nothing;

delete from user_roles ur
    using users u, roles r
where ur.user_id = u.id
  and ur.role_id = r.id
  and u.email = 'admin@test.com'
  and r.name = 'SUPER_ADMIN';