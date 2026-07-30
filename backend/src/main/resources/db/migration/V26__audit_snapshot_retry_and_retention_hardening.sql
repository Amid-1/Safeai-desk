/* Safeai-desk/backend/src/main/resources/db/migration/V26__audit_snapshot_retry_and_retention_hardening.sql */
/*
 * Apply after already-applied V25.
 * Never edit V20...V25.
 */

-- ---------------------------------------------------------------------------
-- 1. Immutable actor organization snapshot.
-- organization_id remains the target organization of the event.
-- ---------------------------------------------------------------------------

alter table public.audit_events
    add column if not exists actor_organization_id uuid;

alter table public.audit_outbox
    add column if not exists actor_organization_id uuid;

-- Best-effort historical backfill. No FK is added intentionally.
update public.audit_events audit_event
set actor_organization_id = app_user.organization_id
from public.users app_user
where audit_event.actor_organization_id is null
  and audit_event.actor_user_id = app_user.id;

update public.audit_outbox outbox
set actor_organization_id = app_user.organization_id
from public.users app_user
where outbox.actor_organization_id is null
  and outbox.actor_user_id = app_user.id;

-- ---------------------------------------------------------------------------
-- 2. Canonical actor email snapshot.
-- ---------------------------------------------------------------------------

update public.audit_events
set actor_email = lower(btrim(actor_email))
where actor_email is not null
  and actor_email is distinct from lower(btrim(actor_email));

alter table public.audit_events
    drop constraint if exists
        chk_audit_events_actor_email_canonical;

alter table public.audit_events
    add constraint chk_audit_events_actor_email_canonical
        check (
            actor_email is null
            or (
                length(btrim(actor_email)) > 0
                and actor_email = lower(btrim(actor_email))
            )
        ) not valid;

alter table public.audit_events
    validate constraint
        chk_audit_events_actor_email_canonical;

-- ---------------------------------------------------------------------------
-- 3. Outbox occurrence, retry and dead-letter metadata.
--
-- Additive/defaulted changes keep inserts from an older app version valid.
-- ---------------------------------------------------------------------------

alter table public.audit_outbox
    add column if not exists occurred_at timestamptz;

update public.audit_outbox
set occurred_at = created_at
where occurred_at is null;

alter table public.audit_outbox
    alter column occurred_at
        set default current_timestamp,
    alter column occurred_at
        set not null;

alter table public.audit_outbox
    add column if not exists attempt_count integer
        not null default 0,
    add column if not exists next_attempt_at timestamptz,
    add column if not exists last_error varchar(1000),
    add column if not exists dead_lettered_at timestamptz;

update public.audit_outbox
set next_attempt_at = created_at
where dead_lettered_at is null
  and next_attempt_at is null;

alter table public.audit_outbox
    alter column next_attempt_at
        set default current_timestamp;

alter table public.audit_outbox
    drop constraint if exists
        chk_audit_outbox_attempt_count_non_negative;

alter table public.audit_outbox
    add constraint
        chk_audit_outbox_attempt_count_non_negative
        check (attempt_count >= 0);

alter table public.audit_outbox
    drop constraint if exists
        chk_audit_outbox_delivery_state;

alter table public.audit_outbox
    add constraint chk_audit_outbox_delivery_state
        check (
            (
                dead_lettered_at is null
                and next_attempt_at is not null
            )
            or
            (
                dead_lettered_at is not null
                and next_attempt_at is null
                and attempt_count > 0
                and last_error is not null
            )
        );

-- ---------------------------------------------------------------------------
-- 4. Whole-details database safety limit.
-- ---------------------------------------------------------------------------

do $$
begin
    if exists (
        select 1
        from public.audit_events
        where octet_length(details::text) > 65536
    ) then
        raise exception
            'Cannot apply V26: audit_events contains details larger than 65536 bytes';
    end if;

    if exists (
        select 1
        from public.audit_outbox
        where octet_length(details::text) > 65536
    ) then
        raise exception
            'Cannot apply V26: audit_outbox contains details larger than 65536 bytes';
    end if;
end
$$;

alter table public.audit_events
    drop constraint if exists
        chk_audit_events_details_size;

alter table public.audit_events
    add constraint chk_audit_events_details_size
        check (
            octet_length(details::text) <= 65536
        );

alter table public.audit_outbox
    drop constraint if exists
        chk_audit_outbox_details_size;

alter table public.audit_outbox
    add constraint chk_audit_outbox_details_size
        check (
            octet_length(details::text) <= 65536
        );

