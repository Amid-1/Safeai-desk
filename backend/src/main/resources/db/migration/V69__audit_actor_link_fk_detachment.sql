-- backend/src/main/resources/db/migration/V69__audit_actor_link_fk_detachment.sql
-- SafeAI Desk V69: Allow ONLY referential-action unlink of a live user pointer.
-- V1-V68 are immutable: do not modify previously applied Flyway scripts.
--
-- V68 protects audit_events as append-only evidence. However, the historical
-- user_id foreign key uses ON DELETE SET NULL. PostgreSQL implements that
-- referential action as an UPDATE; unconditional V68 guard rejects it and
-- prevents legitimate permanent deletion of users.
--
-- The mutable user_id is a convenience FK, NOT immutable audit identity.
-- The actor_user_id and all actor/target snapshots remain immutable.
-- This rule permits only the internal FK-triggered NULL detachment and rejects
-- direct SQL updates, including direct UPDATE user_id = NULL.

DO $preflight$
BEGIN
    IF to_regclass('public.audit_events') IS NULL
       OR to_regclass('public.users') IS NULL THEN
        RAISE EXCEPTION 'V69 requires public.audit_events and public.users';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint c
         WHERE c.conrelid = 'public.audit_events'::regclass
           AND c.contype = 'f'
           AND c.confrelid = 'public.users'::regclass
           AND c.confdeltype = 'n' -- PostgreSQL ON DELETE SET NULL
           AND 'user_id'::name = ANY (
                 SELECT a.attname
                   FROM pg_attribute a
                  WHERE a.attrelid = c.conrelid
                    AND a.attnum = ANY(c.conkey)
             )
    ) THEN
        RAISE EXCEPTION 'V69 requires audit_events.user_id ON DELETE SET NULL FK to users';
    END IF;
END
$preflight$;

CREATE OR REPLACE FUNCTION public.guard_audit_event_update_v68()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- PostgreSQL runs ON DELETE SET NULL as an UPDATE initiated by its
    -- referential-integrity trigger. The audit UPDATE trigger is then nested.
    -- This is a narrow compatibility rule, NOT general UPDATE permission.
    IF pg_trigger_depth() > 1
       AND OLD.user_id IS NOT NULL
       AND NEW.user_id IS NULL
       AND (to_jsonb(NEW) - 'user_id')
           IS NOT DISTINCT FROM (to_jsonb(OLD) - 'user_id') THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'V69 audit_events evidence is immutable (except FK-triggered user_id detachment)';
END
$$;

COMMENT ON FUNCTION public.guard_audit_event_update_v68() IS
'Append-only audit evidence. Only the nested PostgreSQL ON DELETE SET NULL referential action may detach user_id; all immutable actor/target snapshots remain unchanged. Retention DELETE remains allowed.';
