/* Safeai-desk/backend/src/main/resources/db/migration/V24__user_security_and_audit_outbox_hardening.sql */
/*
 * Safeai-desk/backend/src/main/resources/db/migration/
 * V24__user_security_and_audit_outbox_hardening.sql
 *
 * PostgreSQL. Apply after V20...V23. Never edit already-applied migrations.
 */

-- ---------------------------------------------------------------------------
-- 1. Canonical email as a real storage invariant backed by ordinary UNIQUE.
-- ---------------------------------------------------------------------------

do $$
begin
    if exists (
        select 1
        from public.users
        group by lower(btrim(email))
        having count(*) > 1
    ) then
        raise exception
            'Cannot apply V24: users contains duplicate emails after lower(trim(email)) normalization';
    end if;
end
$$;

update public.users
set email = lower(btrim(email))
where email is distinct from lower(btrim(email));

-- V13 created this expression index. Canonical storage now permits a normal
-- unique constraint/index, which is usable by WHERE email = ?.
drop index if exists public.ux_users_email_normalized;
drop index if exists public.ux_users_email_lower;

alter table public.users
    drop constraint if exists uq_users_email;

alter table public.users
    add constraint uq_users_email unique (email);

-- Keep the pre-existing V13 canonical check and V4 non-blank check. Assert
-- their semantics explicitly for installations rebuilt from merged history.
do $$
begin
    if exists (
        select 1
        from public.users
        where email <> lower(btrim(email))
           or length(email) = 0
    ) then
        raise exception
            'Cannot apply V24: users.email is not canonical';
    end if;
end
$$;

-- ---------------------------------------------------------------------------
-- 2. One mutually-exclusive business role per user.
--
-- Priority only resolves legacy multi-role rows during migration:
-- SUPER_ADMIN > ADMIN > USER. Application code may never assign SUPER_ADMIN
-- through ordinary user-management endpoints.
-- ---------------------------------------------------------------------------

with ranked_roles as (
    select ur.user_id,
           ur.role_id,
           row_number() over (
               partition by ur.user_id
               order by case r.name
                   when 'SUPER_ADMIN' then 1
                   when 'ADMIN' then 2
                   when 'USER' then 3
                   else 100
               end,
               ur.role_id
           ) as role_rank
    from public.user_roles ur
    join public.roles r on r.id = ur.role_id
)
delete from public.user_roles ur
using ranked_roles ranked
where ranked.user_id = ur.user_id
  and ranked.role_id = ur.role_id
  and ranked.role_rank > 1;

do $$
begin
    if exists (
        select 1
        from public.users app_user
        where not exists (
            select 1
            from public.user_roles ur
            where ur.user_id = app_user.id
        )
    ) then
        raise exception
            'Cannot apply V24: at least one user has no role';
    end if;

    if exists (
        select 1
        from public.user_roles
        group by user_id
        having count(*) > 1
    ) then
        raise exception
            'Cannot apply V24: at least one user still has multiple roles';
    end if;
end
$$;

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conrelid = 'public.user_roles'::regclass
          and conname = 'uq_user_roles_one_role_per_user'
    ) then
        alter table public.user_roles
            add constraint uq_user_roles_one_role_per_user
                unique (user_id)
                deferrable initially deferred;
    end if;
end
$$;

-- V20 deferred triggers already guarantee at least one role. Together with
-- uq_user_roles_one_role_per_user this yields exactly one role at commit.

-- ---------------------------------------------------------------------------
-- 3. Explicit disable timestamp for retention-based permanent deletion.
-- ---------------------------------------------------------------------------

alter table public.users
    add column if not exists disabled_at timestamptz;

update public.users
set disabled_at = coalesce(disabled_at, updated_at, current_timestamp)
where enabled = false
  and disabled_at is null;

update public.users
set disabled_at = null
where enabled = true
  and disabled_at is not null;

create or replace function public.sync_user_disabled_at()
    returns trigger
    language plpgsql
as $$
begin
    if new.enabled then
        new.disabled_at := null;
    elsif new.disabled_at is null then
        new.disabled_at := current_timestamp;
    end if;

    return new;
end
$$;

drop trigger if exists trg_users_sync_disabled_at
    on public.users;

create trigger trg_users_sync_disabled_at
    before insert or update of enabled, disabled_at
    on public.users
    for each row
execute function public.sync_user_disabled_at();

alter table public.users
    drop constraint if exists chk_users_disabled_at_consistency;

alter table public.users
    add constraint chk_users_disabled_at_consistency
        check (
            (enabled = true and disabled_at is null)
            or
            (enabled = false and disabled_at is not null)
        );

-- ---------------------------------------------------------------------------
-- 4. Stable user-list pagination support.
-- ---------------------------------------------------------------------------

create index if not exists idx_users_created_at_id_desc
    on public.users (created_at desc, id desc);

create index if not exists idx_users_organization_created_at_id_desc
    on public.users (organization_id, created_at desc, id desc);

-- ---------------------------------------------------------------------------
-- 5. Transactional audit outbox.
--
-- The business mutation and outbox row commit atomically. A local worker then
-- copies the immutable snapshot into audit_events. organization_id and
-- actor_user_id intentionally have no FK so historical intent survives entity
-- lifecycle changes. event_type remains constrained by reference data.
-- ---------------------------------------------------------------------------

create table if not exists public.audit_outbox (
    id uuid primary key,
    actor_user_id uuid,
    actor_email varchar(255),
    actor_display_name varchar(255),
    organization_id uuid not null,
    event_type varchar(100) not null,
    details jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default current_timestamp,

    constraint fk_audit_outbox_event_type
        foreign key (event_type)
        references public.audit_event_types(name),

    constraint chk_audit_outbox_actor_email_not_blank
        check (
            actor_email is null
            or length(btrim(actor_email)) > 0
        ),

    constraint chk_audit_outbox_actor_email_canonical
        check (
            actor_email is null
            or actor_email = lower(btrim(actor_email))
        ),

    constraint chk_audit_outbox_actor_display_name_not_blank
        check (
            actor_display_name is null
            or length(btrim(actor_display_name)) > 0
        )
);

create index if not exists idx_audit_outbox_created_at_id
    on public.audit_outbox (created_at, id);

create index if not exists idx_audit_outbox_actor_user_created_at
    on public.audit_outbox (actor_user_id, created_at)
    where actor_user_id is not null;

-- ---------------------------------------------------------------------------
-- 6. Final assertions for security counters and reference roles.
-- ---------------------------------------------------------------------------

do $$
begin
    if exists (
        select 1
        from public.users
        where token_version < 0
           or version < 0
    ) then
        raise exception
            'Cannot apply V24: negative users token_version/version exists';
    end if;

    if exists (
        select 1
        from public.roles
        where name not in ('SUPER_ADMIN', 'ADMIN', 'USER')
    ) then
        raise exception
            'Cannot apply V24: unknown system role exists';
    end if;
end
$$;

