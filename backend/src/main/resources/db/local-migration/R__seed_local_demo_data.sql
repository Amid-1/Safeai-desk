/* Safeai-desk/backend/src/main/resources/db/local-migration/R__seed_local_demo_data.sql */
/*
 * SafeAI Desk — local-only deterministic demo seed.
 *
 * IMPORTANT:
 * - this file is a Flyway repeatable migration (R__);
 * - it must be loaded only by the local profile;
 * - it is NOT production reference data;
 * - fixed privileged identities are verified before role reconciliation;
 * - this seed never trusts an email match alone.
 *
 * Current schema assumptions:
 * - V20 provides canonical/normalized organization-name functions and
 *   normalized_name NOT NULL semantics;
 * - V24 enforces exactly one business role per user at commit;
 * - V25 provides organizations.auth_version;
 * - later production migrations may evolve unrelated schema, but this local
 *   seed intentionally depends only on the identity/security baseline above.
 */

-- ---------------------------------------------------------------------------
-- 0. Production-schema prerequisites.
-- Repeatable migrations run after versioned migrations, so the local seed may
-- require the current production identity/security baseline.
-- ---------------------------------------------------------------------------

do $$
begin
    if to_regclass('public.organizations') is null
       or to_regclass('public.users') is null
       or to_regclass('public.roles') is null
       or to_regclass('public.user_roles') is null then
        raise exception
            'Local seed prerequisite is missing: identity tables are unavailable';
    end if;

    if to_regprocedure('public.normalize_organization_name(text)') is null then
        raise exception
            'Local seed prerequisite is missing: normalize_organization_name(text)';
    end if;

    if not exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'organizations'
          and column_name = 'normalized_name'
          and is_nullable = 'NO'
    ) then
        raise exception
            'Local seed prerequisite is missing: organizations.normalized_name NOT NULL';
    end if;

    if not exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'organizations'
          and column_name = 'auth_version'
    ) then
        raise exception
            'Local seed prerequisite is missing: organizations.auth_version';
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conrelid = 'public.user_roles'::regclass
          and conname = 'uq_user_roles_one_role_per_user'
    ) then
        raise exception
            'Local seed prerequisite is missing: exactly-one-role constraint';
    end if;

    if not exists (
        select 1
        from public.organizations
        where id = '00000000-0000-0000-0000-000000000001'::uuid
          and name = 'SafeAI Platform'
          and normalized_name =
              public.normalize_organization_name('SafeAI Platform')
    ) then
        raise exception
            'Local seed prerequisite is missing or inconsistent: SafeAI Platform organization';
    end if;

    if not exists (
        select 1
        from public.roles
        where name = 'ADMIN'
    ) then
        raise exception
            'Local seed prerequisite is missing: ADMIN role';
    end if;

    if not exists (
        select 1
        from public.roles
        where name = 'SUPER_ADMIN'
    ) then
        raise exception
            'Local seed prerequisite is missing: SUPER_ADMIN role';
    end if;
end
$$;

-- ---------------------------------------------------------------------------
-- 1. Reject identity/name/email collisions before creating privileged users.
--
-- A fixed UUID is the authoritative local-seed identity. An existing row with
-- that UUID must match the expected tenant/email. Conversely, the expected
-- normalized organization name/email must not belong to a different UUID.
-- ---------------------------------------------------------------------------

do $$
begin
    if exists (
        select 1
        from public.organizations
        where id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid
          and (
              name <> 'Demo Company'
              or normalized_name <>
                  public.normalize_organization_name('Demo Company')
          )
    ) then
        raise exception
            'Local seed conflict: demo organization UUID is already used by another identity';
    end if;

    if exists (
        select 1
        from public.organizations
        where id <> 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid
          and normalized_name =
              public.normalize_organization_name('Demo Company')
    ) then
        raise exception
            'Local seed conflict: normalized organization name "Demo Company" belongs to another UUID';
    end if;

    if exists (
        select 1
        from public.users
        where id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid
          and (
              organization_id <>
                  'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid
              or email <> 'admin@test.com'
          )
    ) then
        raise exception
            'Local seed conflict: demo admin UUID is already used by another identity';
    end if;

    if exists (
        select 1
        from public.users
        where id <> 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid
          and email = 'admin@test.com'
    ) then
        raise exception
            'Local seed conflict: admin@test.com belongs to another UUID';
    end if;

    if exists (
        select 1
        from public.users
        where id = '00000000-0000-0000-0000-000000000101'::uuid
          and (
              organization_id <>
                  '00000000-0000-0000-0000-000000000001'::uuid
              or email <> 'superadmin@test.com'
          )
    ) then
        raise exception
            'Local seed conflict: platform superadmin UUID is already used by another identity';
    end if;

    if exists (
        select 1
        from public.users
        where id <> '00000000-0000-0000-0000-000000000101'::uuid
          and email = 'superadmin@test.com'
    ) then
        raise exception
            'Local seed conflict: superadmin@test.com belongs to another UUID';
    end if;
end
$$;

-- ---------------------------------------------------------------------------
-- 2. Demo organization.
--
-- normalized_name is listed explicitly even though V20 also maintains it via
-- BEFORE INSERT/UPDATE trigger. This keeps the seed self-describing and avoids
-- false IDE warnings about a NOT NULL column without a DEFAULT.
--
-- auth_version is also explicit because it is part of the current organization
-- security epoch introduced by V25.
-- ---------------------------------------------------------------------------

insert into public.organizations (
    id,
    name,
    normalized_name,
    enabled,
    created_at,
    updated_at,
    version,
    auth_version
)
values (
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid,
    'Demo Company',
    public.normalize_organization_name('Demo Company'),
    true,
    current_timestamp,
    current_timestamp,
    0,
    0
)
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- 3. Demo administrator.
--
-- Legacy unprefixed BCrypt hash is intentional and remains supported by the
-- application's password-encoding fallback during the migration period.
--
-- Existing fixed identities are intentionally not UPDATEd on repeatable rerun:
-- security epochs, optimistic-lock versions and a locally changed password are
-- not silently reset merely because the seed checksum changed.
-- ---------------------------------------------------------------------------

insert into public.users (
    id,
    organization_id,
    email,
    password_hash,
    full_name,
    enabled,
    disabled_at,
    last_login_at,
    created_at,
    updated_at,
    token_version,
    version
)
values (
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid,
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid,
    'admin@test.com',
    '$2a$10$qcWB2wkTGlA7MvSoYdPFy.7R7BZzvifDywN4hOUd9ipijInEF7CjG',
    'Demo Admin',
    true,
    null,
    null,
    current_timestamp,
    current_timestamp,
    0,
    0
)
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- 4. Platform super administrator.
-- ---------------------------------------------------------------------------

insert into public.users (
    id,
    organization_id,
    email,
    password_hash,
    full_name,
    enabled,
    disabled_at,
    last_login_at,
    created_at,
    updated_at,
    token_version,
    version
)
values (
    '00000000-0000-0000-0000-000000000101'::uuid,
    '00000000-0000-0000-0000-000000000001'::uuid,
    'superadmin@test.com',
    '$2a$10$yJB.CHsf1cPn3lYot0djHuiBE4Dk7o8iFkZdCNLAoaCy5TFvIxS36',
    'SafeAI Platform Admin',
    true,
    null,
    null,
    current_timestamp,
    current_timestamp,
    0,
    0
)
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- 5. Validate the fixed identities again before touching privileged roles.
-- ---------------------------------------------------------------------------

do $$
begin
    if not exists (
        select 1
        from public.organizations
        where id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid
          and name = 'Demo Company'
          and normalized_name =
              public.normalize_organization_name('Demo Company')
    ) then
        raise exception
            'Local seed failed: demo organization identity does not match expected values';
    end if;

    if not exists (
        select 1
        from public.users
        where id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid
          and organization_id =
              'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid
          and email = 'admin@test.com'
    ) then
        raise exception
            'Local seed failed: demo admin identity does not match expected values';
    end if;

    if not exists (
        select 1
        from public.users
        where id = '00000000-0000-0000-0000-000000000101'::uuid
          and organization_id =
              '00000000-0000-0000-0000-000000000001'::uuid
          and email = 'superadmin@test.com'
    ) then
        raise exception
            'Local seed failed: platform superadmin identity does not match expected values';
    end if;
end
$$;

-- ---------------------------------------------------------------------------
-- 6. Deterministic exactly-one-role reconciliation.
--
-- V24 enforces exactly one role per user. Because this is a repeatable local
-- seed, a previously modified local role must not cause an opaque unique-key
-- failure. For these two already-validated fixed identities only:
--
--   demo admin          -> ADMIN
--   platform superadmin -> SUPER_ADMIN
--
-- The role-presence checks are deferred by the production schema, so deleting
-- a stale role and inserting the expected role in the same Flyway transaction
-- remains valid at commit.
-- ---------------------------------------------------------------------------

delete from public.user_roles
where user_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid
  and role_id <> (
      select id
      from public.roles
      where name = 'ADMIN'
  );

insert into public.user_roles (
    user_id,
    role_id
)
select
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid,
    role.id
from public.roles role
where role.name = 'ADMIN'
on conflict (user_id, role_id) do nothing;

delete from public.user_roles
where user_id = '00000000-0000-0000-0000-000000000101'::uuid
  and role_id <> (
      select id
      from public.roles
      where name = 'SUPER_ADMIN'
  );

insert into public.user_roles (
    user_id,
    role_id
)
select
    '00000000-0000-0000-0000-000000000101'::uuid,
    role.id
from public.roles role
where role.name = 'SUPER_ADMIN'
on conflict (user_id, role_id) do nothing;

-- ---------------------------------------------------------------------------
-- 7. Final verification.
-- ---------------------------------------------------------------------------

do $$
declare
    demo_admin_role_count integer;
    platform_superadmin_role_count integer;
begin
    select count(*)
    into demo_admin_role_count
    from public.user_roles user_role
    join public.roles role
      on role.id = user_role.role_id
    where user_role.user_id =
              'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid
      and role.name = 'ADMIN';

    if demo_admin_role_count <> 1 then
        raise exception
            'Local seed failed: demo admin must have exactly ADMIN role';
    end if;

    if (
        select count(*)
        from public.user_roles
        where user_id =
            'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid
    ) <> 1 then
        raise exception
            'Local seed failed: demo admin must have exactly one role';
    end if;

    select count(*)
    into platform_superadmin_role_count
    from public.user_roles user_role
    join public.roles role
      on role.id = user_role.role_id
    where user_role.user_id =
              '00000000-0000-0000-0000-000000000101'::uuid
      and role.name = 'SUPER_ADMIN';

    if platform_superadmin_role_count <> 1 then
        raise exception
            'Local seed failed: platform superadmin must have exactly SUPER_ADMIN role';
    end if;

    if (
        select count(*)
        from public.user_roles
        where user_id =
            '00000000-0000-0000-0000-000000000101'::uuid
    ) <> 1 then
        raise exception
            'Local seed failed: platform superadmin must have exactly one role';
    end if;
end
$$;
