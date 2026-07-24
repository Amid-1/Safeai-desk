/* Safeai-desk/backend/src/main/resources/db/migration/V20__production_integrity_hardening.sql */
-- V20: production integrity hardening after already-applied V1...V19.
-- PostgreSQL.
--
-- IMPORTANT:
-- 1. Do not edit V1...V19 after they have been applied.
-- 2. This migration intentionally terminates all legacy active refresh
--    sessions once, because their issued security-version snapshot did not
--    exist and therefore cannot be reconstructed safely.
-- 3. V21...V23 build large indexes concurrently outside a transaction.

-- ---------------------------------------------------------------------------
-- 0. Preflight: the full V7 must already contain the user rollup table.
-- ---------------------------------------------------------------------------

do $$
begin
    if to_regclass('public.usage_daily_user_model_rollups') is null then
        raise exception
            'Cannot apply V20: public.usage_daily_user_model_rollups is missing. Use the complete V7 migration before rebuilding the database.';
    end if;
end
$$;

-- ---------------------------------------------------------------------------
-- 1. Repair V16 deterministically.
--
-- The composite FK (user_id, organization_id) is sufficient both to preserve
-- tenant integrity and to prevent physical user deletion. Any redundant
-- one-column chat_sessions -> users FK is removed, including an accidentally
-- retained CASCADE FK or the V16 RESTRICT FK.
-- ---------------------------------------------------------------------------

do $$
begin
    if exists (
        select 1
        from public.chat_sessions cs
        join public.users u on u.id = cs.user_id
        where cs.organization_id <> u.organization_id
    ) then
        raise exception
            'Cannot apply V20: chat_sessions contains cross-tenant user relationships';
    end if;
end
$$;

do $$
declare
    fk record;
    chat_user_attnum smallint;
    chat_org_attnum smallint;
    users_id_attnum smallint;
    users_org_attnum smallint;
begin
    select attnum into strict chat_user_attnum
    from pg_attribute
    where attrelid = 'public.chat_sessions'::regclass
      and attname = 'user_id'
      and not attisdropped;

    select attnum into strict chat_org_attnum
    from pg_attribute
    where attrelid = 'public.chat_sessions'::regclass
      and attname = 'organization_id'
      and not attisdropped;

    select attnum into strict users_id_attnum
    from pg_attribute
    where attrelid = 'public.users'::regclass
      and attname = 'id'
      and not attisdropped;

    select attnum into strict users_org_attnum
    from pg_attribute
    where attrelid = 'public.users'::regclass
      and attname = 'organization_id'
      and not attisdropped;

    if not exists (
        select 1
        from pg_constraint c
        where c.contype = 'u'
          and c.conrelid = 'public.users'::regclass
          and c.conkey = array[users_id_attnum, users_org_attnum]::smallint[]
    ) then
        alter table public.users
            add constraint uq_users_id_organization
                unique (id, organization_id);
    end if;

    for fk in
        select c.conname
        from pg_constraint c
        where c.contype = 'f'
          and c.conrelid = 'public.chat_sessions'::regclass
          and c.confrelid = 'public.users'::regclass
          and c.conkey = array[chat_user_attnum]::smallint[]
          and c.confkey = array[users_id_attnum]::smallint[]
    loop
        execute format(
            'alter table public.chat_sessions drop constraint %I',
            fk.conname
        );
    end loop;

    if not exists (
        select 1
        from pg_constraint c
        where c.contype = 'f'
          and c.conrelid = 'public.chat_sessions'::regclass
          and c.confrelid = 'public.users'::regclass
          and c.conkey = array[chat_user_attnum, chat_org_attnum]::smallint[]
          and c.confkey = array[users_id_attnum, users_org_attnum]::smallint[]
    ) then
        alter table public.chat_sessions
            add constraint fk_chat_sessions_user_organization
                foreign key (user_id, organization_id)
                    references public.users (id, organization_id)
                    on delete restrict;
    end if;
end
$$;

-- ---------------------------------------------------------------------------
-- 2. Canonical organization names and a dedicated normalized key.
-- ---------------------------------------------------------------------------

create or replace function public.canonical_organization_name(value text)
    returns text
    language sql
    immutable
    strict
    parallel safe
as $$
    select regexp_replace(btrim(value), '[[:space:]]+', ' ', 'g')
$$;

create or replace function public.normalize_organization_name(value text)
    returns text
    language sql
    immutable
    strict
    parallel safe
as $$
    select lower(public.canonical_organization_name(value))
$$;

do $$
begin
    if exists (
        select 1
        from public.organizations
        group by public.normalize_organization_name(name)
        having count(*) > 1
    ) then
        raise exception
            'Cannot apply V20: organizations contains duplicate names after trim/whitespace/case normalization';
    end if;
end
$$;

alter table public.organizations
    add column normalized_name varchar(255);

update public.organizations
set name = public.canonical_organization_name(name),
    normalized_name = public.normalize_organization_name(name);

alter table public.organizations
    alter column normalized_name set not null;

alter table public.organizations
    drop constraint if exists chk_organizations_name_trimmed;

alter table public.organizations
    add constraint chk_organizations_name_canonical
        check (name = public.canonical_organization_name(name)),
    add constraint chk_organizations_normalized_name_consistency
        check (
            normalized_name = public.normalize_organization_name(name)
        );

create or replace function public.sync_organization_name()
    returns trigger
    language plpgsql
as $$
begin
    new.name := public.canonical_organization_name(new.name);

    if new.name is null or new.name = '' then
        raise exception using
            errcode = '23514',
            message = 'Organization name must not be blank';
    end if;

    new.normalized_name := public.normalize_organization_name(new.name);
    return new;
end
$$;

drop trigger if exists trg_organizations_normalize_name
    on public.organizations;

create trigger trg_organizations_normalize_name
    before insert or update
    on public.organizations
    for each row
execute function public.sync_organization_name();

-- ---------------------------------------------------------------------------
-- 3. Refresh-token security snapshots, absolute family lifetime and terminal
--    revocation semantics.
-- ---------------------------------------------------------------------------

alter table public.refresh_tokens
    add column issued_token_version bigint,
    add column family_created_at timestamptz,
    add column family_expires_at timestamptz,
    add column revocation_reason varchar(40);

do $$
begin
    if exists (
        select 1
        from public.refresh_tokens
        group by lower(token_hash)
        having count(*) > 1
    ) then
        raise exception
            'Cannot apply V20: refresh_tokens contains token hashes that collide after lowercase normalization';
    end if;

    if exists (
        select 1
        from public.refresh_tokens
        where length(token_hash) <> 64
           or token_hash !~ '^[0-9A-Fa-f]{64}$'
    ) then
        raise exception
            'Cannot apply V20: refresh_tokens.token_hash must contain a SHA-256 hexadecimal digest (64 characters)';
    end if;
end
$$;

update public.refresh_tokens
set token_hash = lower(token_hash)
where token_hash <> lower(token_hash);

update public.refresh_tokens token
set issued_token_version = app_user.token_version
from public.users app_user
where app_user.id = token.user_id;

with family_bounds as (
    select token_family_id,
           min(created_at) as family_created_at,
           max(expires_at) as family_expires_at
    from public.refresh_tokens
    group by token_family_id
)
update public.refresh_tokens token
set family_created_at = bounds.family_created_at,
    family_expires_at = bounds.family_expires_at
from family_bounds bounds
where bounds.token_family_id = token.token_family_id;

update public.refresh_tokens
set revocation_reason = case
    when replaced_by_token_id is not null then 'ROTATED'
    else 'LEGACY_REVOKED'
end
where revoked_at is not null;

-- Legacy active tokens have no trustworthy issued security snapshot.
-- Terminating them is the only fail-closed migration strategy.
update public.refresh_tokens
set revoked_at = greatest(current_timestamp, created_at),
    revocation_reason = 'SECURITY_STATE_CHANGED'
where revoked_at is null;

alter table public.refresh_tokens
    alter column token_hash type varchar(64),
    alter column issued_token_version set not null,
    alter column family_created_at set not null,
    alter column family_expires_at set not null;

alter table public.refresh_tokens
    add constraint ck_refresh_tokens_token_hash_sha256
        check (token_hash ~ '^[0-9a-f]{64}$'),
    add constraint ck_refresh_tokens_issued_version_non_negative
        check (issued_token_version >= 0),
    add constraint ck_refresh_tokens_family_order
        check (
            family_created_at <= created_at
            and family_created_at < family_expires_at
            and created_at < expires_at
            and expires_at <= family_expires_at
        ),
    add constraint ck_refresh_tokens_revocation_consistency
        check (
            (revoked_at is null and revocation_reason is null)
            or
            (revoked_at is not null and revocation_reason is not null)
        ),
    add constraint ck_refresh_tokens_revocation_reason
        check (
            revocation_reason is null
            or revocation_reason in (
                'ROTATED',
                'LOGOUT',
                'PASSWORD_RESET',
                'ROLE_CHANGED',
                'EMAIL_CHANGED',
                'USER_DISABLED',
                'ORGANIZATION_DISABLED',
                'SECURITY_STATE_CHANGED',
                'EXPIRED',
                'REUSE_DETECTED',
                'ADMIN_REVOKED',
                'LEGACY_REVOKED'
            )
        );

create index if not exists idx_refresh_tokens_user_family
    on public.refresh_tokens (user_id, token_family_id);

-- Do not create another token_family_id-only index: V1 already created
-- idx_refresh_tokens_token_family_id.

-- ---------------------------------------------------------------------------
-- 4. AI usage/pricing applicability and metadata normalization.
-- ---------------------------------------------------------------------------

update public.chat_messages
set usage_status = case
    when role = 'ASSISTANT' and status = 'COMPLETED' then
        case
            when input_tokens is not null and output_tokens is not null
                then 'AVAILABLE'
            when input_tokens is null and output_tokens is null
                then 'MISSING'
            else 'PARTIAL'
        end
    else 'NOT_APPLICABLE'
end
where usage_status = 'NOT_APPLICABLE'
   or role <> 'ASSISTANT'
   or status <> 'COMPLETED';

update public.chat_messages
set pricing_status = 'NOT_APPLICABLE'
where role <> 'ASSISTANT'
   or status <> 'COMPLETED';

update public.chat_messages
set pricing_status = 'UNPRICED',
    cost_usd = null,
    currency = null,
    pricing_version = null,
    pricing_calculated_at = coalesce(pricing_calculated_at, created_at)
where role = 'ASSISTANT'
  and status = 'COMPLETED'
  and pricing_status = 'NOT_APPLICABLE';

update public.chat_messages
set pricing_status = 'FREE'
where role = 'ASSISTANT'
  and status = 'COMPLETED'
  and pricing_status = 'PRICED'
  and cost_usd = 0;

update public.chat_messages
set provider_message_id = null
where provider_message_id is not null
  and btrim(provider_message_id) = '';

update public.chat_messages
set finish_reason = null
where finish_reason is not null
  and btrim(finish_reason) = '';

do $$
begin
    if exists (
        select 1
        from public.chat_messages
        where pricing_status in ('PRICED', 'FREE')
          and (pricing_version is null or btrim(pricing_version) = '')
    ) then
        raise exception
            'Cannot apply V20: priced/free chat messages contain a blank pricing_version';
    end if;
end
$$;

update public.chat_messages
set pricing_version = null
where pricing_version is not null
  and btrim(pricing_version) = '';

alter table public.chat_messages
    add constraint chk_chat_messages_usage_applicability
        check (
            (
                role = 'ASSISTANT'
                and status = 'COMPLETED'
                and usage_status in (
                    'AVAILABLE',
                    'MISSING',
                    'PARTIAL'
                )
            )
            or
            (
                (role <> 'ASSISTANT' or status <> 'COMPLETED')
                and usage_status = 'NOT_APPLICABLE'
            )
        ) not valid,
    add constraint chk_chat_messages_pricing_applicability
        check (
            (
                role = 'ASSISTANT'
                and status = 'COMPLETED'
                and pricing_status in (
                    'PRICED',
                    'FREE',
                    'UNPRICED',
                    'CALCULATION_FAILED'
                )
            )
            or
            (
                (role <> 'ASSISTANT' or status <> 'COMPLETED')
                and pricing_status = 'NOT_APPLICABLE'
            )
        ) not valid,
    add constraint chk_chat_messages_priced_positive
        check (pricing_status <> 'PRICED' or cost_usd > 0) not valid,
    add constraint chk_chat_messages_provider_message_id_not_blank
        check (
            provider_message_id is null
            or length(btrim(provider_message_id)) > 0
        ) not valid,
    add constraint chk_chat_messages_finish_reason_not_blank
        check (
            finish_reason is null
            or length(btrim(finish_reason)) > 0
        ) not valid,
    add constraint chk_chat_messages_pricing_version_not_blank
        check (
            pricing_version is null
            or length(btrim(pricing_version)) > 0
        ) not valid;

alter table public.chat_messages
    validate constraint chk_chat_messages_usage_applicability;

alter table public.chat_messages
    validate constraint chk_chat_messages_pricing_applicability;

alter table public.chat_messages
    validate constraint chk_chat_messages_priced_positive;

alter table public.chat_messages
    validate constraint chk_chat_messages_provider_message_id_not_blank;

alter table public.chat_messages
    validate constraint chk_chat_messages_finish_reason_not_blank;

alter table public.chat_messages
    validate constraint chk_chat_messages_pricing_version_not_blank;

-- ---------------------------------------------------------------------------
-- 5. Chat reply integrity.
-- ---------------------------------------------------------------------------

do $$
begin
    if exists (
        select 1
        from public.chat_messages
        where reply_to_message_id = id
    ) then
        raise exception
            'Cannot apply V20: chat_messages contains a self-reply';
    end if;

    if exists (
        select 1
        from public.chat_messages reply
        left join public.chat_messages target
          on target.id = reply.reply_to_message_id
         and target.session_id = reply.session_id
        where reply.reply_to_message_id is not null
          and (target.id is null or target.role <> 'USER')
    ) then
        raise exception
            'Cannot apply V20: an assistant reply references a non-USER message';
    end if;

    if exists (
        select 1
        from public.chat_messages
        where reply_to_message_id is not null
        group by reply_to_message_id
        having count(*) > 1
    ) then
        raise exception
            'Cannot apply V20: more than one assistant response exists for one USER message';
    end if;
end
$$;

alter table public.chat_messages
    add constraint chk_chat_messages_not_reply_to_self
        check (
            reply_to_message_id is null
            or reply_to_message_id <> id
        );

create or replace function public.enforce_chat_message_identity_immutability()
    returns trigger
    language plpgsql
as $$
begin
    if new.session_id is distinct from old.session_id then
        raise exception using
            errcode = '23514',
            message = 'chat_messages.session_id is immutable';
    end if;

    if new.role is distinct from old.role then
        raise exception using
            errcode = '23514',
            message = 'chat_messages.role is immutable';
    end if;

    return new;
end
$$;

drop trigger if exists trg_chat_messages_identity_immutable
    on public.chat_messages;

create trigger trg_chat_messages_identity_immutable
    before update of session_id, role
    on public.chat_messages
    for each row
execute function public.enforce_chat_message_identity_immutability();

create or replace function public.enforce_chat_message_reply_target()
    returns trigger
    language plpgsql
as $$
declare
    target_role varchar(50);
begin
    if new.reply_to_message_id is null then
        return null;
    end if;

    if new.reply_to_message_id = new.id then
        raise exception using
            errcode = '23514',
            message = 'A chat message cannot reply to itself';
    end if;

    if new.role <> 'ASSISTANT' then
        raise exception using
            errcode = '23514',
            message = 'Only ASSISTANT messages may have reply_to_message_id';
    end if;

    select target.role
      into target_role
      from public.chat_messages target
     where target.id = new.reply_to_message_id
       and target.session_id = new.session_id;

    if not found then
        raise exception using
            errcode = '23503',
            message = 'reply_to_message_id must reference a message in the same session';
    end if;

    if target_role <> 'USER' then
        raise exception using
            errcode = '23514',
            message = 'An ASSISTANT message must reply to a USER message';
    end if;

    return null;
end
$$;

drop trigger if exists ctrg_chat_messages_reply_target
    on public.chat_messages;

create constraint trigger ctrg_chat_messages_reply_target
    after insert or update
    on public.chat_messages
    deferrable initially deferred
    for each row
    when (new.reply_to_message_id is not null)
execute function public.enforce_chat_message_reply_target();

-- ---------------------------------------------------------------------------
-- 6. Every persisted user must have at least one role.
--
-- The deferred trigger permits INSERT user -> INSERT user_roles in one
-- transaction. Locking the user row serializes concurrent role removals.
-- ---------------------------------------------------------------------------

do $$
begin
    if exists (
        select 1
        from public.users u
        where not exists (
            select 1
            from public.user_roles ur
            where ur.user_id = u.id
        )
    ) then
        raise exception
            'Cannot apply V20: at least one existing user has no role';
    end if;
end
$$;

create or replace function public.assert_user_has_at_least_one_role(
    checked_user_id uuid
)
    returns void
    language plpgsql
as $$
begin
    if checked_user_id is null then
        return;
    end if;

    perform 1
    from public.users
    where id = checked_user_id
    for update;

    if not found then
        return;
    end if;

    if not exists (
        select 1
        from public.user_roles
        where user_id = checked_user_id
    ) then
        raise exception using
            errcode = '23514',
            message = format(
                'User %s must have at least one role',
                checked_user_id
            );
    end if;
end
$$;

create or replace function public.enforce_user_role_presence()
    returns trigger
    language plpgsql
as $$
begin
    if tg_table_name = 'users' then
        perform public.assert_user_has_at_least_one_role(new.id);
        return null;
    end if;

    if tg_op = 'DELETE' then
        perform public.assert_user_has_at_least_one_role(old.user_id);
        return null;
    end if;

    if tg_op = 'UPDATE'
       and old.user_id is distinct from new.user_id then
        perform public.assert_user_has_at_least_one_role(old.user_id);
    end if;

    perform public.assert_user_has_at_least_one_role(new.user_id);
    return null;
end
$$;

drop trigger if exists ctrg_users_require_role
    on public.users;

drop trigger if exists ctrg_user_roles_require_role
    on public.user_roles;

create constraint trigger ctrg_users_require_role
    after insert
    on public.users
    deferrable initially deferred
    for each row
execute function public.enforce_user_role_presence();

create constraint trigger ctrg_user_roles_require_role
    after insert or update or delete
    on public.user_roles
    deferrable initially deferred
    for each row
execute function public.enforce_user_role_presence();

-- ---------------------------------------------------------------------------
-- 7. V7 already contains the quota/rollup updated_at triggers. Assert that the
--    complete migration was applied instead of silently creating a divergent
--    schema.
-- ---------------------------------------------------------------------------

do $$
declare
    missing_triggers text[];
begin
    select array_agg(required.name order by required.name)
      into missing_triggers
      from (
          values
              (
                  'trg_organization_ai_quotas_updated_at',
                  'public.organization_ai_quotas'::regclass
              ),
              (
                  'trg_user_ai_quotas_updated_at',
                  'public.user_ai_quotas'::regclass
              ),
              (
                  'trg_usage_daily_org_model_rollups_updated_at',
                  'public.usage_daily_org_model_rollups'::regclass
              ),
              (
                  'trg_usage_daily_user_model_rollups_updated_at',
                  'public.usage_daily_user_model_rollups'::regclass
              )
      ) as required(name, relation_id)
     where not exists (
         select 1
         from pg_trigger trigger
         where trigger.tgname = required.name
           and trigger.tgrelid = required.relation_id
           and not trigger.tgisinternal
     );

    if missing_triggers is not null then
        raise exception
            'Cannot apply V20: required updated_at triggers are missing: %',
            array_to_string(missing_triggers, ', ');
    end if;
end
$$;
