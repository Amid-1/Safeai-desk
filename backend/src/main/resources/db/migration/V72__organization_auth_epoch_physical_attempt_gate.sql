-- backend/src/main/resources/db/migration/V72__organization_auth_epoch_physical_attempt_gate.sql
-- SafeAI Desk V72: epoch-bound ChatTurn reservation and DB-linearized provider-attempt gate.
-- V1..V71 are immutable. This migration deliberately does not rewrite historical
-- ChatTurn provenance: old rows have NULL epoch and cannot start NEW calls.

ALTER TABLE public.chat_turns
    ADD COLUMN authorized_organization_auth_version bigint;

-- The default only provides compatibility for SQL test fixtures which create
-- brand-new chat turns without binding an explicit authenticated principal.
-- Application-created rows always pass the epoch from SafeAiUserPrincipal.
-- Pre-migration rows remain NULL (ALTER DEFAULT is non-retroactive).
ALTER TABLE public.chat_turns
    ALTER COLUMN authorized_organization_auth_version SET DEFAULT 0;

ALTER TABLE public.chat_turns
    ADD CONSTRAINT chk_chat_turn_authorized_org_auth_epoch_v72
    CHECK (authorized_organization_auth_version IS NULL
           OR authorized_organization_auth_version >= 0);

-- Compare the RESERVATION-TIME authenticated epoch with the currently locked
-- tenant epoch. Organization disable takes FOR UPDATE of this row. FOR SHARE
-- is held until the ChatTurn INSERT transaction commits. No new turn can be
-- reserved across a committed disable or a disable/re-enable epoch change.
CREATE FUNCTION public.guard_chat_turn_reservation_epoch_v72()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_enabled boolean;
    v_current_epoch bigint;
BEGIN
    SELECT o.enabled, o.auth_version
      INTO v_enabled, v_current_epoch
      FROM public.organizations o
     WHERE o.id = NEW.organization_id
       FOR SHARE OF o;

    IF NOT FOUND OR v_enabled IS DISTINCT FROM TRUE
       OR NEW.authorized_organization_auth_version IS NULL
       OR NEW.authorized_organization_auth_version IS DISTINCT FROM v_current_epoch THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V72 ChatTurn requires enabled organization and current authenticated epoch';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_a_chat_turn_reservation_epoch_v72
BEFORE INSERT ON public.chat_turns
FOR EACH ROW
EXECUTE FUNCTION public.guard_chat_turn_reservation_epoch_v72();

-- DB-level immutability (also protected in ORM with updatable=false).
CREATE FUNCTION public.guard_chat_turn_epoch_immutable_v72()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.authorized_organization_auth_version
          IS DISTINCT FROM OLD.authorized_organization_auth_version THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V72 ChatTurn authorized organization epoch is immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_a_chat_turn_epoch_immutable_v72
BEFORE UPDATE OF authorized_organization_auth_version ON public.chat_turns
FOR EACH ROW
EXECUTE FUNCTION public.guard_chat_turn_epoch_immutable_v72();

-- The critical authorization LINEARIZATION POINT: BEFORE INSERT of every
-- new physical STARTED attempt. Lock the organization row through commit;
-- disable's FOR UPDATE cannot commit between verification and STARTED commit.
-- An attempt pre-authorized before disable may already be in flight; its
-- evidence must be finalized without inventing cancellation or retry.
CREATE FUNCTION public.guard_physical_attempt_organization_epoch_v72()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_enabled boolean;
    v_current_epoch bigint;
    v_authorized_epoch bigint;
    v_plan_org_id uuid;
    v_turn_org_id uuid;
BEGIN
    SELECT o.enabled,
           o.auth_version,
           t.authorized_organization_auth_version,
           p.organization_id,
           t.organization_id
      INTO v_enabled,
           v_current_epoch,
           v_authorized_epoch,
           v_plan_org_id,
           v_turn_org_id
      FROM public.model_execution_plans p
      JOIN public.chat_turns t ON t.id = p.chat_turn_id
      JOIN public.organizations o ON o.id = p.organization_id
     WHERE p.id = NEW.execution_plan_id
       FOR SHARE OF o;

    IF NOT FOUND OR v_enabled IS DISTINCT FROM TRUE
       OR v_plan_org_id IS DISTINCT FROM v_turn_org_id
       OR v_authorized_epoch IS NULL
       OR v_authorized_epoch IS DISTINCT FROM v_current_epoch THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V72 physical AI attempt denied: organization disabled or authorization epoch revoked';
    END IF;

    RETURN NEW;
END
$$;

-- Alphabetical trigger order puts this gate before the V53/V54/V65 attempt
-- triggers, so it cannot leave an earlier attempt-side effect on rejection.
CREATE TRIGGER trg_a_physical_attempt_organization_epoch_v72
BEFORE INSERT ON public.model_execution_attempts
FOR EACH ROW
EXECUTE FUNCTION public.guard_physical_attempt_organization_epoch_v72();

COMMENT ON COLUMN public.chat_turns.authorized_organization_auth_version IS
'Immutable organization auth epoch presented at ChatTurn reservation; NULL historical rows cannot start new AI attempts.';
COMMENT ON FUNCTION public.guard_physical_attempt_organization_epoch_v72() IS
'Enforces organization enabled + exact authenticated reservation epoch, holding organization FOR SHARE through physical STARTED commit.';
