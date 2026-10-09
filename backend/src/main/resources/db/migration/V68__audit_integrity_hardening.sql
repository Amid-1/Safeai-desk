-- backend/src/main/resources/db/migration/V68__audit_integrity_hardening.sql
-- SafeAI Desk V68: Audit integrity hardening.
--
-- IMPORTANT:
-- * V1-V67 must remain immutable.
-- * If the previously prepared V66/V67 package has NOT been merged/applied,
--   rename THIS migration to the next unused version BEFORE it is ever applied.
-- * Never rename or edit it after Flyway has applied it.
--
-- Goals:
-- 1. audit_events is append-only evidence (DELETE remains available to retention).
-- 2. audit_outbox separates immutable audit intent from mutable delivery metadata.
-- 3. outbox event types are validated at enqueue time.
-- 4. actor snapshot text is canonical/valid at the DB boundary.
-- 5. details is always a JSON object.

DO $migration$
BEGIN
    IF to_regclass('public.audit_events') IS NULL
       OR to_regclass('public.audit_outbox') IS NULL
       OR to_regclass('public.audit_event_types') IS NULL THEN
        RAISE EXCEPTION
            'V68 requires public.audit_events, public.audit_outbox and public.audit_event_types';
    END IF;
END
$migration$;

-- ---------------------------------------------------------------------------
-- Preflight / legacy normalization
-- ---------------------------------------------------------------------------

UPDATE public.audit_outbox
SET actor_email = NULL
WHERE actor_email IS NOT NULL
  AND btrim(actor_email) = '';

UPDATE public.audit_outbox
SET actor_email = lower(btrim(actor_email))
WHERE actor_email IS NOT NULL
  AND actor_email IS DISTINCT FROM lower(btrim(actor_email));

UPDATE public.audit_outbox
SET actor_display_name = NULL
WHERE actor_display_name IS NOT NULL
  AND btrim(actor_display_name) = '';

UPDATE public.audit_outbox
SET actor_display_name = btrim(actor_display_name)
WHERE actor_display_name IS NOT NULL
  AND actor_display_name IS DISTINCT FROM btrim(actor_display_name);

UPDATE public.audit_events
SET actor_display_name = btrim(actor_display_name)
WHERE actor_display_name IS NOT NULL
  AND actor_display_name IS DISTINCT FROM btrim(actor_display_name);

DO $preflight$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.audit_outbox o
        LEFT JOIN public.audit_event_types t
          ON t.name = o.event_type
        WHERE t.name IS NULL
    ) THEN
        RAISE EXCEPTION
            'Cannot apply V68: audit_outbox contains unknown event_type';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.audit_events
        WHERE jsonb_typeof(details) IS DISTINCT FROM 'object'
    ) THEN
        RAISE EXCEPTION
            'Cannot apply V68: audit_events.details contains non-object JSON';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.audit_outbox
        WHERE jsonb_typeof(details) IS DISTINCT FROM 'object'
    ) THEN
        RAISE EXCEPTION
            'Cannot apply V68: audit_outbox.details contains non-object JSON';
    END IF;
END
$preflight$;

-- ---------------------------------------------------------------------------
-- Canonical snapshot / payload constraints
-- ---------------------------------------------------------------------------

ALTER TABLE public.audit_outbox
    ADD CONSTRAINT fk_audit_outbox_event_type_v68
    FOREIGN KEY (event_type)
    REFERENCES public.audit_event_types(name)
    ON UPDATE RESTRICT
    ON DELETE RESTRICT
    NOT VALID;

ALTER TABLE public.audit_outbox
    VALIDATE CONSTRAINT fk_audit_outbox_event_type_v68;

ALTER TABLE public.audit_outbox
    ADD CONSTRAINT chk_audit_outbox_actor_email_v68
    CHECK (
        actor_email IS NULL
        OR (
            actor_email = lower(btrim(actor_email))
            AND char_length(actor_email) BETWEEN 1 AND 255
            AND actor_email !~ '[[:cntrl:]]'
        )
    ) NOT VALID;

ALTER TABLE public.audit_outbox
    VALIDATE CONSTRAINT chk_audit_outbox_actor_email_v68;

ALTER TABLE public.audit_events
    ADD CONSTRAINT chk_audit_events_actor_email_v68
    CHECK (
        actor_email IS NULL
        OR (
            actor_email = lower(btrim(actor_email))
            AND char_length(actor_email) BETWEEN 1 AND 255
            AND actor_email !~ '[[:cntrl:]]'
        )
    ) NOT VALID;

ALTER TABLE public.audit_events
    VALIDATE CONSTRAINT chk_audit_events_actor_email_v68;

ALTER TABLE public.audit_outbox
    ADD CONSTRAINT chk_audit_outbox_actor_display_name_v68
    CHECK (
        actor_display_name IS NULL
        OR (
            actor_display_name = btrim(actor_display_name)
            AND char_length(actor_display_name) BETWEEN 1 AND 255
            AND actor_display_name !~ '[[:cntrl:]]'
        )
    ) NOT VALID;

ALTER TABLE public.audit_outbox
    VALIDATE CONSTRAINT chk_audit_outbox_actor_display_name_v68;

ALTER TABLE public.audit_events
    ADD CONSTRAINT chk_audit_events_actor_display_name_v68
    CHECK (
        actor_display_name IS NULL
        OR (
            actor_display_name = btrim(actor_display_name)
            AND char_length(actor_display_name) BETWEEN 1 AND 255
            AND actor_display_name !~ '[[:cntrl:]]'
        )
    ) NOT VALID;

ALTER TABLE public.audit_events
    VALIDATE CONSTRAINT chk_audit_events_actor_display_name_v68;

ALTER TABLE public.audit_events
    ADD CONSTRAINT chk_audit_events_target_organization_name_controls_v68
    CHECK (
        target_organization_name IS NULL
        OR target_organization_name !~ '[[:cntrl:]]'
    ) NOT VALID;

ALTER TABLE public.audit_events
    VALIDATE CONSTRAINT chk_audit_events_target_organization_name_controls_v68;

ALTER TABLE public.audit_outbox
    ADD CONSTRAINT chk_audit_outbox_target_organization_name_controls_v68
    CHECK (
        target_organization_name IS NULL
        OR target_organization_name !~ '[[:cntrl:]]'
    ) NOT VALID;

ALTER TABLE public.audit_outbox
    VALIDATE CONSTRAINT chk_audit_outbox_target_organization_name_controls_v68;

ALTER TABLE public.audit_events
    ADD CONSTRAINT chk_audit_events_details_object_v68
    CHECK (jsonb_typeof(details) = 'object')
    NOT VALID;

ALTER TABLE public.audit_events
    VALIDATE CONSTRAINT chk_audit_events_details_object_v68;

ALTER TABLE public.audit_outbox
    ADD CONSTRAINT chk_audit_outbox_details_object_v68
    CHECK (jsonb_typeof(details) = 'object')
    NOT VALID;

ALTER TABLE public.audit_outbox
    VALIDATE CONSTRAINT chk_audit_outbox_details_object_v68;

-- ---------------------------------------------------------------------------
-- audit_events: immutable after INSERT.
--
-- DELETE is intentionally not blocked: the configured retention job remains
-- the explicit data-lifecycle mechanism.
-- ---------------------------------------------------------------------------

CREATE FUNCTION public.guard_audit_event_update_v68()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'V68 audit_events rows are immutable';
END
$$;

CREATE TRIGGER trg_audit_event_no_update_v68
BEFORE UPDATE ON public.audit_events
FOR EACH ROW
EXECUTE FUNCTION public.guard_audit_event_update_v68();

-- ---------------------------------------------------------------------------
-- audit_outbox: intent is immutable; only delivery bookkeeping may evolve.
-- ---------------------------------------------------------------------------

CREATE FUNCTION public.guard_audit_outbox_update_v68()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.actor_user_id IS DISTINCT FROM OLD.actor_user_id
       OR NEW.actor_organization_id IS DISTINCT FROM OLD.actor_organization_id
       OR NEW.actor_email IS DISTINCT FROM OLD.actor_email
       OR NEW.actor_display_name IS DISTINCT FROM OLD.actor_display_name
       OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.target_organization_name IS DISTINCT FROM OLD.target_organization_name
       OR NEW.event_type IS DISTINCT FROM OLD.event_type
       OR NEW.details IS DISTINCT FROM OLD.details
       OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN

        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V68 audit_outbox immutable intent changed';
    END IF;

    IF NEW.attempt_count < OLD.attempt_count THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V68 audit_outbox attempt_count cannot decrease';
    END IF;

    IF OLD.dead_lettered_at IS NOT NULL THEN
        IF NEW.dead_lettered_at IS DISTINCT FROM OLD.dead_lettered_at
           OR NEW.next_attempt_at IS DISTINCT FROM OLD.next_attempt_at
           OR NEW.attempt_count IS DISTINCT FROM OLD.attempt_count
           OR NEW.last_error IS DISTINCT FROM OLD.last_error THEN

            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'V68 dead-letter audit_outbox row is terminal';
        END IF;
    END IF;

    IF OLD.dead_lettered_at IS NULL
       AND NEW.dead_lettered_at IS NOT NULL
       AND NEW.next_attempt_at IS NOT NULL THEN

        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V68 dead-letter audit_outbox row cannot remain scheduled';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_audit_outbox_intent_guard_v68
BEFORE UPDATE ON public.audit_outbox
FOR EACH ROW
EXECUTE FUNCTION public.guard_audit_outbox_update_v68();

COMMENT ON FUNCTION public.guard_audit_event_update_v68() IS
'Append-only audit evidence guard. Retention DELETE is intentionally allowed.';

COMMENT ON FUNCTION public.guard_audit_outbox_update_v68() IS
'Protects immutable audit intent while allowing retry/dead-letter delivery metadata transitions.';
