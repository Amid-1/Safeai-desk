/* Safeai-desk/backend/src/main/resources/db/local-migration/R__seed_local_demo_data.sql */
/* Safeai-desk/backend/src/main/resources/db/local-migration/R__seed_local_demo_data.sql */
-- Local-only deterministic seed.
-- Fixed identities are validated before roles are assigned;
-- an email match alone is never considered sufficient.

-- ---------------------------------------------------------------------------
-- Prerequisites provided by production migrations
-- ---------------------------------------------------------------------------

do $$
begin
    if not exists (
        select 1
        from organizations
        where id = '00000000-0000-0000-0000-000000000001'
          and name = 'SafeAI Platform'
    ) then
        raise exception
            'Local seed prerequisite is missing: SafeAI Platform organization';
end if;

    if not exists (
        select 1
        from roles
        where name = 'ADMIN'
    ) then
        raise exception
            'Local seed prerequisite is missing: ADMIN role';
end if;

    if not exists (
        select 1
        from roles
        where name = 'SUPER_ADMIN'
    ) then
        raise exception
            'Local seed prerequisite is missing: SUPER_ADMIN role';
end if;
end
$$;

-- ---------------------------------------------------------------------------
-- Demo organization
-- ---------------------------------------------------------------------------

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

-- ---------------------------------------------------------------------------
-- Demo administrator
-- Legacy unprefixed BCrypt hash is intentional and is supported by
-- PasswordEncodingConfiguration fallback during the migration period.
-- ---------------------------------------------------------------------------

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
    on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- Platform super administrator
-- ---------------------------------------------------------------------------

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
    on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- Validate fixed identities before assigning privileged roles
-- ---------------------------------------------------------------------------

do $$
begin
    if not exists (
        select 1
        from organizations
        where id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
          and name = 'Demo Company'
    ) then
        raise exception
            'Local seed conflict: demo organization identity does not match expected values';
end if;

    if not exists (
        select 1
        from users
        where id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
          and organization_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
          and email = 'admin@test.com'
    ) then
        raise exception
            'Local seed conflict: demo admin identity does not match expected values';
end if;

    if not exists (
        select 1
        from users
        where id = '00000000-0000-0000-0000-000000000101'
          and organization_id = '00000000-0000-0000-0000-000000000001'
          and email = 'superadmin@test.com'
    ) then
        raise exception
            'Local seed conflict: platform superadmin identity does not match expected values';
end if;
end
$$;

-- ---------------------------------------------------------------------------
-- Role assignments
-- ---------------------------------------------------------------------------

insert into user_roles (
    user_id,
    role_id
)
select
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid,
    r.id
from roles r
where r.name = 'ADMIN'
  and exists (
    select 1
    from users u
    where u.id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
      and u.organization_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
      and u.email = 'admin@test.com'
)
    on conflict (user_id, role_id) do nothing;

insert into user_roles (
    user_id,
    role_id
)
select
    '00000000-0000-0000-0000-000000000101'::uuid,
    r.id
from roles r
where r.name = 'SUPER_ADMIN'
  and exists (
    select 1
    from users u
    where u.id = '00000000-0000-0000-0000-000000000101'
      and u.organization_id = '00000000-0000-0000-0000-000000000001'
      and u.email = 'superadmin@test.com'
)
    on conflict (user_id, role_id) do nothing;

-- ---------------------------------------------------------------------------
-- Final verification
-- ---------------------------------------------------------------------------

do $$
begin
    if not exists (
        select 1
        from user_roles ur
        join roles r
          on r.id = ur.role_id
        where ur.user_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
          and r.name = 'ADMIN'
    ) then
        raise exception
            'Local seed failed: ADMIN role was not assigned to demo admin';
end if;

    if not exists (
        select 1
        from user_roles ur
        join roles r
          on r.id = ur.role_id
        where ur.user_id = '00000000-0000-0000-0000-000000000101'
          and r.name = 'SUPER_ADMIN'
    ) then
        raise exception
            'Local seed failed: SUPER_ADMIN role was not assigned to platform superadmin';
end if;
end
$$;