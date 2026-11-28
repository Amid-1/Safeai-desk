-- SafeAI-Desk/backend/src/main/resources/db/migration/V104__erasure_actor_detachment_guards.sql
-- SafeAI Desk V104: repair the V103 actor lifecycle without rewriting history.
-- R1a intake only. No destructive worker, dispatch, or tenant offboarding.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
SET LOCAL search_path = pg_catalog, pg_temp;

-- Historical requester UUIDs remain evidence, but an old preview or a
-- WITHDRAWN case must not permanently hold a live users FK hostage.
-- The request -> preview composite FK remains intact to prove same-tenant actor.
ALTER TABLE public.erasure_previews_v103
    DROP CONSTRAINT fk_erasure_preview_actor_v103;
ALTER TABLE public.erasure_requests_v103
    DROP CONSTRAINT fk_erasure_request_actor_v103;

-- Without the original live-user FK, prevent orphaned/cross-tenant NEW data.
-- FOR KEY SHARE serializes inserts with concurrent DELETE of the user row.
CREATE FUNCTION public.guard_erasure_actor_insert_v104()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp AS $$
DECLARE actor_id uuid;
BEGIN
    IF TG_TABLE_NAME = 'erasure_previews_v103' THEN
        actor_id := NEW.requester_user_id;
    ELSIF TG_TABLE_NAME = 'erasure_requests_v103' THEN
        actor_id := NEW.requested_by_user_id;
        IF NEW.state <> 'DRAFT' OR NEW.version <> 0
           OR NEW.submitted_at IS NOT NULL OR NEW.submit_key IS NOT NULL
           OR NEW.withdraw_key IS NOT NULL THEN
            RAISE EXCEPTION 'An erasure case must begin as a draft'
                USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM public.erasure_previews_v103 p
            WHERE p.id = NEW.preview_id
              AND p.organization_id = NEW.organization_id
              AND p.requester_user_id = NEW.requested_by_user_id
              AND p.preview_digest = NEW.preview_digest
              AND p.resource_type = NEW.resource_type
              AND p.resource_id = NEW.resource_id
              AND p.operation = NEW.operation
              AND p.scope = NEW.scope
              AND p.resource_version = NEW.expected_resource_version
              AND p.expires_at > transaction_timestamp()
        ) THEN
            RAISE EXCEPTION 'Erasure draft has no matching live preview'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'Unexpected erasure actor trigger target'
            USING ERRCODE = '23514';
    END IF;

    -- Fixed lock order matches login/user-management: organization, then user.
    PERFORM 1 FROM public.organizations o
        WHERE o.id = NEW.organization_id AND o.enabled FOR SHARE OF o;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Erasure organization must be enabled'
            USING ERRCODE = '23503';
    END IF;
    PERFORM 1 FROM public.users u
        WHERE u.id = actor_id AND u.organization_id = NEW.organization_id
          AND u.enabled FOR SHARE OF u;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Erasure requester must be a live tenant user'
            USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_erasure_preview_actor_v104
    BEFORE INSERT ON public.erasure_previews_v103
    FOR EACH ROW EXECUTE FUNCTION public.guard_erasure_actor_insert_v104();
CREATE TRIGGER trg_erasure_request_actor_v104
    BEFORE INSERT ON public.erasure_requests_v103
    FOR EACH ROW EXECUTE FUNCTION public.guard_erasure_actor_insert_v104();

-- A live DRAFT or SUBMITTED case still blocks deletion. WITHDRAWN cases and
-- standalone previews are historical evidence, not permanent live-FK blockers.
CREATE FUNCTION public.guard_erasure_user_delete_v104()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.erasure_requests_v103 r
         WHERE r.organization_id = OLD.organization_id
           AND r.requested_by_user_id = OLD.id
           AND r.state IN ('DRAFT', 'SUBMITTED')
    ) THEN
        RAISE EXCEPTION 'Active erasure request blocks user deletion'
            USING ERRCODE = '23503';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER trg_erasure_user_delete_v104
    BEFORE DELETE ON public.users
    FOR EACH ROW EXECUTE FUNCTION public.guard_erasure_user_delete_v104();

-- Physical states and arbitrary transitions are deliberately impossible in R1a.
CREATE FUNCTION public.guard_erasure_request_transition_v104()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.requested_by_user_id IS DISTINCT FROM OLD.requested_by_user_id
       OR NEW.resource_type IS DISTINCT FROM OLD.resource_type
       OR NEW.resource_id IS DISTINCT FROM OLD.resource_id
       OR NEW.operation IS DISTINCT FROM OLD.operation
       OR NEW.scope IS DISTINCT FROM OLD.scope
       OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
       OR NEW.preview_id IS DISTINCT FROM OLD.preview_id
       OR NEW.preview_digest IS DISTINCT FROM OLD.preview_digest
       OR NEW.expected_resource_version IS DISTINCT FROM OLD.expected_resource_version
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.request_digest IS DISTINCT FROM OLD.request_digest
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.version <> OLD.version + 1
       OR NOT (
           (OLD.state = 'DRAFT' AND NEW.state IN ('SUBMITTED', 'WITHDRAWN'))
           OR (OLD.state = 'SUBMITTED' AND NEW.state = 'WITHDRAWN')
       ) THEN
        RAISE EXCEPTION 'Illegal erasure case transition'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.state = 'SUBMITTED' THEN
        IF NEW.submit_key IS NULL OR NEW.withdraw_key IS DISTINCT FROM OLD.withdraw_key
           OR NEW.submitted_at IS NULL THEN
            RAISE EXCEPTION 'Incomplete erasure submission'
                USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM public.erasure_previews_v103 p
            WHERE p.id = NEW.preview_id AND p.expires_at > transaction_timestamp()
        ) THEN
            RAISE EXCEPTION 'Erasure preview expired before submission'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.withdraw_key IS NULL
           OR NEW.submit_key IS DISTINCT FROM OLD.submit_key
           OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at THEN
            RAISE EXCEPTION 'Incomplete erasure withdrawal'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_erasure_request_transition_v104
    BEFORE UPDATE ON public.erasure_requests_v103
    FOR EACH ROW EXECUTE FUNCTION public.guard_erasure_request_transition_v104();

-- Stop a direct INSERT from fabricating a success or another actor's event.
CREATE FUNCTION public.guard_erasure_event_insert_v104()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp AS $$
DECLARE row_state text;
DECLARE row_version bigint;
DECLARE requester uuid;
BEGIN
    SELECT r.state, r.version, r.requested_by_user_id
      INTO row_state, row_version, requester
      FROM public.erasure_requests_v103 r
     WHERE r.id = NEW.request_id AND r.organization_id = NEW.organization_id
     FOR SHARE OF r;
    IF NOT FOUND OR requester IS DISTINCT FROM NEW.actor_user_id
       OR row_version IS DISTINCT FROM NEW.version
       OR NOT (
           (NEW.version = 0 AND row_state = 'DRAFT' AND NEW.event_type = 'DRAFT_CREATED')
           OR (NEW.version > 0 AND row_state = 'SUBMITTED' AND NEW.event_type = 'SUBMITTED')
           OR (NEW.version > 0 AND row_state = 'WITHDRAWN' AND NEW.event_type = 'WITHDRAWN')
       ) THEN
        RAISE EXCEPTION 'Event does not match the live case transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_erasure_event_insert_v104
    BEFORE INSERT ON public.erasure_request_events_v103
    FOR EACH ROW EXECUTE FUNCTION public.guard_erasure_event_insert_v104();

-- Explicit least-privilege posture for guards; triggers run as invoker.
REVOKE ALL ON FUNCTION public.guard_erasure_actor_insert_v104() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_erasure_user_delete_v104() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_erasure_request_transition_v104() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_erasure_event_insert_v104() FROM PUBLIC;
