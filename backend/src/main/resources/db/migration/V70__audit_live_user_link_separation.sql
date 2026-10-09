-- backend/src/main/resources/db/migration/V70__audit_live_user_link_separation.sql
-- SafeAI Desk V70: immutable audit evidence and separately lifecycle-managed user links.
-- V1-V69 stay immutable, including the transitional V69 implementation.
-- Run with a privileged Flyway owner, not with the runtime application role.
-- This migration deliberately fails if dependent objects block DROP COLUMN.

DO $preflight$
BEGIN
    IF to_regclass('public.audit_events') IS NULL
       OR to_regclass('public.users') IS NULL
       OR to_regclass('public.audit_outbox') IS NULL THEN
        RAISE EXCEPTION 'V70 requires audit_events, users and audit_outbox';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'audit_events'
          AND column_name = 'user_id'
    ) THEN
        RAISE EXCEPTION 'V70 requires the legacy audit_events.user_id column';
    END IF;
END
$preflight$;

-- Only the live pointer is in this table. No actor identity or event details
-- may depend on it. Deletion of either parent removes the pointer, not evidence.
CREATE TABLE public.audit_event_user_links (
    audit_event_id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    CONSTRAINT fk_audit_live_link_event_v70
        FOREIGN KEY (audit_event_id)
        REFERENCES public.audit_events(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_audit_live_link_user_v70
        FOREIGN KEY (user_id)
        REFERENCES public.users(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_audit_event_user_links_user_v70
    ON public.audit_event_user_links(user_id, audit_event_id);

-- Backfill existing pointers without inventing links for deleted users.
INSERT INTO public.audit_event_user_links(audit_event_id, user_id)
SELECT e.id, e.user_id
FROM public.audit_events e
JOIN public.users u ON u.id = e.user_id
WHERE e.user_id IS NOT NULL;

-- Inserts into audit_events and live link happen in the SAME transaction.
-- The FK checks serialize insert versus concurrent user deletion.
CREATE FUNCTION public.create_audit_live_user_link_v70()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.actor_user_id IS NOT NULL THEN
        INSERT INTO public.audit_event_user_links(audit_event_id, user_id)
        SELECT NEW.id, u.id
        FROM public.users u
        WHERE u.id = NEW.actor_user_id;
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_create_audit_live_user_link_v70
AFTER INSERT ON public.audit_events
FOR EACH ROW
EXECUTE FUNCTION public.create_audit_live_user_link_v70();

-- Do not use CASCADE: dependent SQL views/functions must be explicitly migrated.
-- Dropping the legacy column also drops its ON DELETE SET NULL FK, eliminating
-- the conflict with the strict immutable-event trigger.
ALTER TABLE public.audit_events DROP COLUMN user_id;

-- Restore strict immutability even if V69 previously installed its exception.
CREATE OR REPLACE FUNCTION public.guard_audit_event_update_v68()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'V70 audit_events rows are immutable';
END
$function$;

COMMENT ON TABLE public.audit_event_user_links IS
'Non-evidentiary live user pointers; deleted by FK cascade on user or audit event deletion. Immutable actor snapshots remain in audit_events.';
COMMENT ON FUNCTION public.create_audit_live_user_link_v70() IS
'Atomically creates a live actor-user pointer after new immutable audit evidence is inserted.';
COMMENT ON FUNCTION public.guard_audit_event_update_v68() IS
'Strict immutable audit evidence: every UPDATE is forbidden; user links are held separately.';
