-- SafeAI-Desk/backend/src/main/resources/db/migration/V105__erasure_review_intake.sql
-- SafeAI Desk R1b: NON-DESTRUCTIVE, independently authorized review and legal holds.
-- APPROVED_REVIEW_ONLY is never a deletion permit or completion proof.
-- V102/V103/V104 are applied history and MUST NOT be modified.
-- This version is for databases where V105 has NOT yet been applied.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
SET LOCAL search_path = pg_catalog, pg_temp;

-- Provision the same 32-byte secret outside source control to this table and
-- the application property safeai.erasure.review.actor-proof-key (64 hex digits).
-- Empty table is a fail-closed default: no review mutation can be authorized.
-- safeai_app has neither SELECT nor INSERT/UPDATE/DELETE on this relation.
CREATE TABLE public.erasure_review_actor_keys_v105 (
    key_id smallint PRIMARY KEY CHECK (key_id = 1),
    secret bytea NOT NULL CHECK (octet_length(secret) = 32),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);
REVOKE ALL ON public.erasure_review_actor_keys_v105 FROM PUBLIC;

CREATE TABLE public.erasure_review_authorities_v105 (
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    authority varchar(12) NOT NULL CHECK (authority IN ('REVIEW','LEGAL')),
    enabled boolean NOT NULL DEFAULT false,
    granted_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (organization_id, user_id, authority),
    FOREIGN KEY (user_id, organization_id)
        REFERENCES public.users (id, organization_id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id) REFERENCES public.organizations(id)
);

CREATE TABLE public.erasure_reviews_v105 (
    request_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    requester_user_id uuid NOT NULL,
    review_state varchar(28) NOT NULL DEFAULT 'PENDING' CHECK (review_state IN (
        'PENDING','NEEDS_INFORMATION','APPROVED_REVIEW_ONLY','REJECTED','WITHDRAWN')),
    review_version bigint NOT NULL DEFAULT 0 CHECK (review_version >= 0),
    hold_revision bigint NOT NULL DEFAULT 0 CHECK (hold_revision >= 0),
    updated_by_user_id uuid NOT NULL,
    last_event_code varchar(32) NOT NULL DEFAULT 'SUBMITTED',
    last_key varchar(128),
    reason_code varchar(32),
    CHECK (reason_code IS NULL OR reason_code IN (
        'VERIFIED_OWNER_REQUEST','VALID_TENANT_REQUEST','INSUFFICIENT_AUTHORITY',
        'INSUFFICIENT_INFORMATION','RETENTION_REVIEW_REQUIRED','SECURITY_REVIEW_REQUIRED',
        'LEGAL_REQUEST','MANDATORY_RETENTION','SECURITY_REVIEW')),
    CHECK (last_key IS NULL OR last_key ~ '^[A-Za-z0-9._:-]{12,128}$'),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    FOREIGN KEY (request_id, organization_id)
        REFERENCES public.erasure_requests_v103(id, organization_id) ON DELETE RESTRICT,
    UNIQUE (request_id, organization_id),
    CHECK (last_event_code IN ('SUBMITTED','APPROVED_REVIEW_ONLY','REJECTED',
        'NEEDS_INFORMATION','INFORMATION_PROVIDED','HOLD_APPLIED','HOLD_RELEASED','WITHDRAWN'))
);
CREATE INDEX ix_erasure_reviews_queue_v105 ON public.erasure_reviews_v105
    (organization_id, review_state, updated_at DESC, request_id);

CREATE TABLE public.erasure_review_events_v105 (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    actor_user_id uuid NOT NULL,
    review_version bigint NOT NULL CHECK (review_version >= 0),
    event_code varchar(32) NOT NULL,
    action_key varchar(128),
    CHECK (action_key IS NULL OR action_key ~ '^[A-Za-z0-9._:-]{12,128}$'),
    reason_code varchar(32),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    FOREIGN KEY (request_id, organization_id)
        REFERENCES public.erasure_reviews_v105(request_id, organization_id) ON DELETE RESTRICT,
    UNIQUE (request_id, review_version),
    UNIQUE (request_id, action_key),
    CHECK (event_code IN ('SUBMITTED','APPROVED_REVIEW_ONLY','REJECTED',
        'NEEDS_INFORMATION','INFORMATION_PROVIDED','HOLD_APPLIED','HOLD_RELEASED','WITHDRAWN'))
);

-- Hold mutations carry the idempotency keys. A DB trigger performs the matching
-- review update in the very same transaction; application code must NOT write
-- its own HOLD_* review state or journal event.
CREATE TABLE public.erasure_review_holds_v105 (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    reason_code varchar(32) NOT NULL CHECK
        (reason_code IN ('LEGAL_REQUEST','MANDATORY_RETENTION','SECURITY_REVIEW')),
    applied_by_user_id uuid NOT NULL,
    apply_key varchar(128) NOT NULL CHECK (apply_key ~ '^[A-Za-z0-9._:-]{12,128}$'),
    applied_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    released_by_user_id uuid,
    release_key varchar(128),
    released_at timestamptz,
    FOREIGN KEY (request_id, organization_id)
        REFERENCES public.erasure_reviews_v105(request_id, organization_id) ON DELETE RESTRICT,
    CHECK ((released_at IS NULL) = (released_by_user_id IS NULL)),
    CHECK ((released_at IS NULL) = (release_key IS NULL)),
    CHECK (release_key IS NULL OR release_key ~ '^[A-Za-z0-9._:-]{12,128}$'),
    CHECK (released_by_user_id IS NULL OR released_by_user_id <> applied_by_user_id),
    UNIQUE (request_id, apply_key),
    UNIQUE (request_id, release_key)
);
CREATE UNIQUE INDEX uq_erasure_active_hold_v105
    ON public.erasure_review_holds_v105 (request_id) WHERE released_at IS NULL;

-- HMAC-SHA256 over transaction id, tenant, actor, case, action, resulting
-- review version and action key. Both secrets must be provisioned independently.
-- This binds a DB operation to the principal already authenticated by Java.
-- A custom GUC without HMAC verification WOULD NOT authenticate a user.
-- PostgreSQL 16 pg_catalog.sha256 is used; no extra extension is needed.
CREATE FUNCTION public.assert_erasure_review_actor_v105(
    p_actor uuid, p_org uuid, p_request uuid, p_action text,
    p_new_version bigint, p_key text
) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_key bytea;
    v_signature text;
    v_expected text;
    v_payload text;
    v_ipad bytea := decode(repeat('36',64),'hex');
    v_opad bytea := decode(repeat('5c',64),'hex');
    v_i integer;
BEGIN
    SELECT secret INTO v_key FROM public.erasure_review_actor_keys_v105 WHERE key_id=1;
    v_signature := current_setting('safeai.erasure.review.actor_proof_v105', true);
    IF v_key IS NULL OR v_signature IS NULL OR v_signature !~ '^[0-9a-f]{64}$'
       OR p_actor IS NULL OR p_org IS NULL OR p_request IS NULL
       OR p_action IS NULL OR p_new_version IS NULL OR p_key IS NULL THEN
        RAISE EXCEPTION 'Authenticated erasure review actor proof required'
            USING ERRCODE='42501';
    END IF;
    -- RFC 2104: the 32-byte HMAC key is zero-padded to SHA-256's 64-byte block.
    v_key := v_key || decode(repeat('00',32),'hex');
    v_payload := 'v1|' || txid_current()::text || '|'
        || p_actor::text || '|' || p_org::text || '|' || p_request::text
        || '|' || p_action || '|' || p_new_version::text || '|' || p_key;
    FOR v_i IN 0..63 LOOP
        v_ipad := set_byte(v_ipad, v_i, get_byte(v_ipad,v_i) # get_byte(v_key,v_i));
        v_opad := set_byte(v_opad, v_i, get_byte(v_opad,v_i) # get_byte(v_key,v_i));
    END LOOP;
    v_expected := encode(sha256(v_opad || sha256(v_ipad || convert_to(v_payload,'UTF8'))),'hex');
    IF v_signature <> v_expected THEN
        RAISE EXCEPTION 'Invalid erasure review actor proof' USING ERRCODE='42501';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION public.assert_erasure_review_actor_v105(uuid,uuid,uuid,text,bigint,text)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.assert_erasure_review_actor_v105(uuid,uuid,uuid,text,bigint,text)
    TO safeai_app;

-- Reviewed request INSERT and R1a withdrawal follow the existing base-case
-- transition and must work even though app has no INSERT on reviews.
CREATE FUNCTION public.sync_erasure_review_v105()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.state = 'SUBMITTED' AND OLD.state <> 'SUBMITTED' THEN
        INSERT INTO public.erasure_reviews_v105
            (request_id, organization_id, requester_user_id, updated_by_user_id)
        VALUES (NEW.id, NEW.organization_id, NEW.requested_by_user_id,
            NEW.requested_by_user_id)
        ON CONFLICT (request_id) DO NOTHING;
    ELSIF NEW.state = 'WITHDRAWN' AND OLD.state = 'SUBMITTED' THEN
        UPDATE public.erasure_reviews_v105
        SET review_state='WITHDRAWN', review_version=review_version+1,
            updated_at=transaction_timestamp(), updated_by_user_id=NEW.requested_by_user_id,
            last_event_code='WITHDRAWN',last_key=NULL,reason_code=NULL
        WHERE request_id=NEW.id AND organization_id=NEW.organization_id
            AND review_state <> 'WITHDRAWN';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_erasure_review_sync_v105
    AFTER UPDATE OF state ON public.erasure_requests_v103
    FOR EACH ROW EXECUTE FUNCTION public.sync_erasure_review_v105();

-- Backfill is explicitly after the trigger function creation, before guards;
-- this is safe only inside the migration transaction.
INSERT INTO public.erasure_reviews_v105
    (request_id,organization_id,requester_user_id,updated_by_user_id)
SELECT id,organization_id,requested_by_user_id,requested_by_user_id
FROM public.erasure_requests_v103 WHERE state='SUBMITTED';

CREATE FUNCTION public.guard_erasure_review_v105()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_base_state text;
    v_purpose text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT state INTO v_base_state FROM public.erasure_requests_v103
        WHERE id=NEW.request_id AND organization_id=NEW.organization_id
          AND requested_by_user_id=NEW.requester_user_id;
        IF v_base_state IS DISTINCT FROM 'SUBMITTED'
           OR NEW.review_state IS DISTINCT FROM 'PENDING'
           OR NEW.review_version IS DISTINCT FROM 0
           OR NEW.hold_revision IS DISTINCT FROM 0
           OR NEW.last_key IS NOT NULL
           OR NEW.reason_code IS NOT NULL
           OR NEW.last_event_code IS DISTINCT FROM 'SUBMITTED'
           OR NEW.updated_by_user_id IS DISTINCT FROM NEW.requester_user_id
           OR NEW.created_at IS DISTINCT FROM transaction_timestamp()
           OR NEW.updated_at IS DISTINCT FROM transaction_timestamp() THEN
            RAISE EXCEPTION 'Illegal erasure review creation' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.request_id IS DISTINCT FROM OLD.request_id
       OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.requester_user_id IS DISTINCT FROM OLD.requester_user_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.review_version IS DISTINCT FROM OLD.review_version + 1
       OR NEW.updated_at IS DISTINCT FROM transaction_timestamp() THEN
        RAISE EXCEPTION 'Illegal erasure review mutation' USING ERRCODE='23514';
    END IF;
    SELECT state INTO v_base_state FROM public.erasure_requests_v103
    WHERE id=OLD.request_id AND organization_id=OLD.organization_id FOR SHARE;

    -- Withdrawal may only arrive from the already authorized R1a UPDATE
    -- trigger chain, after its parent row has become WITHDRAWN.
    IF NEW.last_event_code = 'WITHDRAWN' THEN
        IF pg_trigger_depth() < 2
           OR v_base_state IS DISTINCT FROM 'WITHDRAWN'
           OR OLD.review_state = 'WITHDRAWN'
           OR NEW.review_state IS DISTINCT FROM 'WITHDRAWN'
           OR NEW.hold_revision IS DISTINCT FROM OLD.hold_revision
           OR NEW.last_key IS NOT NULL OR NEW.reason_code IS NOT NULL
           OR NEW.updated_by_user_id IS DISTINCT FROM OLD.requester_user_id THEN
            RAISE EXCEPTION 'Withdrawal not confirmed' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;

    IF v_base_state IS DISTINCT FROM 'SUBMITTED'
       OR OLD.review_state IN ('REJECTED','WITHDRAWN') THEN
        RAISE EXCEPTION 'Erasure case is not reviewable' USING ERRCODE='23514';
    END IF;
    IF NEW.last_key IS NULL THEN
        RAISE EXCEPTION 'Review action key is required' USING ERRCODE='23514';
    END IF;

    IF NEW.last_event_code = 'INFORMATION_PROVIDED' THEN
        IF OLD.review_state IS DISTINCT FROM 'NEEDS_INFORMATION'
           OR NEW.review_state IS DISTINCT FROM 'PENDING'
           OR NEW.hold_revision IS DISTINCT FROM OLD.hold_revision
           OR NEW.reason_code IS NOT NULL
           OR NEW.updated_by_user_id IS DISTINCT FROM OLD.requester_user_id
           OR EXISTS (SELECT 1 FROM public.erasure_review_holds_v105
                WHERE request_id=OLD.request_id AND released_at IS NULL)
           OR NOT EXISTS (SELECT 1 FROM public.users u
                JOIN public.organizations o ON o.id=u.organization_id
                WHERE u.id=NEW.updated_by_user_id AND u.organization_id=NEW.organization_id
                  AND u.enabled AND o.enabled) THEN
            RAISE EXCEPTION 'Invalid requester information update' USING ERRCODE='23514';
        END IF;
        PERFORM public.assert_erasure_review_actor_v105(NEW.updated_by_user_id,
            NEW.organization_id, NEW.request_id, NEW.last_event_code,
            NEW.review_version, NEW.last_key);
        RETURN NEW;
    END IF;

    IF NEW.last_event_code IN ('HOLD_APPLIED','HOLD_RELEASED') THEN
        -- HOLD_* reviews can ONLY be produced from a legal-hold trigger.
        IF pg_trigger_depth() < 2 OR NEW.review_state IS DISTINCT FROM 'PENDING'
           OR NEW.hold_revision IS DISTINCT FROM OLD.hold_revision + 1 THEN
            RAISE EXCEPTION 'Illegal hold review mutation' USING ERRCODE='23514';
        END IF;
        IF NEW.last_event_code = 'HOLD_APPLIED' AND NOT EXISTS (
            SELECT 1 FROM public.erasure_review_holds_v105 h
            WHERE h.request_id=NEW.request_id AND h.organization_id=NEW.organization_id
              AND h.released_at IS NULL AND h.applied_by_user_id=NEW.updated_by_user_id
              AND h.apply_key=NEW.last_key AND h.reason_code=NEW.reason_code
              AND h.applied_at=transaction_timestamp()
        ) THEN
            RAISE EXCEPTION 'Hold application has no matching record' USING ERRCODE='23514';
        END IF;
        IF NEW.last_event_code = 'HOLD_RELEASED' AND (
            NEW.reason_code IS NOT NULL
            OR EXISTS (SELECT 1 FROM public.erasure_review_holds_v105
                WHERE request_id=NEW.request_id AND released_at IS NULL)
            OR NOT EXISTS (SELECT 1 FROM public.erasure_review_holds_v105 h
                WHERE h.request_id=NEW.request_id AND h.organization_id=NEW.organization_id
                  AND h.released_by_user_id=NEW.updated_by_user_id
                  AND h.release_key=NEW.last_key AND h.released_at=transaction_timestamp())
        ) THEN
            RAISE EXCEPTION 'Hold release has no matching record' USING ERRCODE='23514';
        END IF;
        v_purpose := 'LEGAL';
    ELSE
        v_purpose := 'REVIEW';
        IF OLD.review_state IS DISTINCT FROM 'PENDING'
           OR NEW.hold_revision IS DISTINCT FROM OLD.hold_revision
           OR NOT (
              (NEW.last_event_code='APPROVED_REVIEW_ONLY'
                AND NEW.review_state='APPROVED_REVIEW_ONLY'
                AND NEW.reason_code IN ('VERIFIED_OWNER_REQUEST','VALID_TENANT_REQUEST'))
              OR (NEW.last_event_code='REJECTED' AND NEW.review_state='REJECTED'
                AND NEW.reason_code IN ('INSUFFICIENT_AUTHORITY','INSUFFICIENT_INFORMATION',
                    'RETENTION_REVIEW_REQUIRED','SECURITY_REVIEW_REQUIRED'))
              OR (NEW.last_event_code='NEEDS_INFORMATION'
                AND NEW.review_state='NEEDS_INFORMATION'
                AND NEW.reason_code IN ('INSUFFICIENT_INFORMATION','RETENTION_REVIEW_REQUIRED',
                    'SECURITY_REVIEW_REQUIRED'))
           ) OR EXISTS (SELECT 1 FROM public.erasure_review_holds_v105
                WHERE request_id=OLD.request_id AND released_at IS NULL) THEN
            RAISE EXCEPTION 'Illegal independent review decision' USING ERRCODE='23514';
        END IF;
    END IF;

    IF NEW.updated_by_user_id = OLD.requester_user_id
       OR NOT EXISTS (
          SELECT 1 FROM public.erasure_review_authorities_v105 a
          JOIN public.users u ON u.id=a.user_id AND u.organization_id=a.organization_id
          JOIN public.organizations o ON o.id=a.organization_id
          WHERE a.organization_id=OLD.organization_id
            AND a.user_id=NEW.updated_by_user_id AND a.authority=v_purpose
            AND a.enabled AND u.enabled AND o.enabled
          FOR SHARE OF a,u,o
       ) THEN
        RAISE EXCEPTION 'Independent erasure reviewer required' USING ERRCODE='23503';
    END IF;
    PERFORM public.assert_erasure_review_actor_v105(NEW.updated_by_user_id,
        NEW.organization_id, NEW.request_id, NEW.last_event_code,
        NEW.review_version, NEW.last_key);
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_erasure_review_guard_v105
    BEFORE INSERT OR UPDATE ON public.erasure_reviews_v105
    FOR EACH ROW EXECUTE FUNCTION public.guard_erasure_review_v105();

-- Only the definer trigger has INSERT rights to this immutable journal.
CREATE FUNCTION public.journal_erasure_review_v105()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    INSERT INTO public.erasure_review_events_v105
      (id,request_id,organization_id,actor_user_id,review_version,event_code,action_key,reason_code)
    VALUES (gen_random_uuid(),NEW.request_id,NEW.organization_id,NEW.updated_by_user_id,
      NEW.review_version,NEW.last_event_code,NEW.last_key,NEW.reason_code);
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_erasure_review_journal_v105
    AFTER INSERT OR UPDATE ON public.erasure_reviews_v105
    FOR EACH ROW EXECUTE FUNCTION public.journal_erasure_review_v105();

-- Initial events for legacy submitted cases were not covered by the journal
-- trigger during the backfill. Only a privileged migrator can insert them.
INSERT INTO public.erasure_review_events_v105
    (id,request_id,organization_id,actor_user_id,review_version,event_code)
SELECT gen_random_uuid(),request_id,organization_id,requester_user_id,0,'SUBMITTED'
FROM public.erasure_reviews_v105
ON CONFLICT (request_id,review_version) DO NOTHING;

CREATE FUNCTION public.guard_erasure_review_event_insert_v105()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp AS $$
DECLARE v public.erasure_reviews_v105%ROWTYPE;
BEGIN
    SELECT * INTO v FROM public.erasure_reviews_v105 x
    WHERE x.request_id=NEW.request_id AND x.organization_id=NEW.organization_id FOR SHARE;
    IF pg_trigger_depth() < 2 OR NOT FOUND
       OR NEW.review_version IS DISTINCT FROM v.review_version
       OR NEW.actor_user_id IS DISTINCT FROM v.updated_by_user_id
       OR NEW.event_code IS DISTINCT FROM v.last_event_code
       OR NEW.action_key IS DISTINCT FROM v.last_key
       OR NEW.reason_code IS DISTINCT FROM v.reason_code THEN
        RAISE EXCEPTION 'Forged erasure review event' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_erasure_review_event_insert_v105
    BEFORE INSERT ON public.erasure_review_events_v105
    FOR EACH ROW EXECUTE FUNCTION public.guard_erasure_review_event_insert_v105();
CREATE TRIGGER trg_erasure_review_event_immutable_v105
    BEFORE UPDATE OR DELETE ON public.erasure_review_events_v105
    FOR EACH ROW EXECUTE FUNCTION public.reject_erasure_event_change_v103();

CREATE FUNCTION public.guard_erasure_hold_v105()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_legal uuid;
    v_review public.erasure_reviews_v105%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Legal hold history is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.released_at IS NOT NULL OR NEW.released_by_user_id IS NOT NULL
           OR NEW.release_key IS NOT NULL OR NEW.applied_at IS DISTINCT FROM transaction_timestamp() THEN
            RAISE EXCEPTION 'A new legal hold must be active' USING ERRCODE='23514';
        END IF;
        v_legal := NEW.applied_by_user_id;
    ELSE
        IF OLD.released_at IS NOT NULL OR OLD.released_by_user_id IS NOT NULL
           OR OLD.release_key IS NOT NULL
           OR NEW.released_at IS DISTINCT FROM transaction_timestamp()
           OR NEW.released_at <= OLD.applied_at OR NEW.release_key IS NULL
           OR NEW.request_id IS DISTINCT FROM OLD.request_id
           OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
           OR NEW.applied_by_user_id IS DISTINCT FROM OLD.applied_by_user_id
           OR NEW.apply_key IS DISTINCT FROM OLD.apply_key
           OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
           OR NEW.applied_at IS DISTINCT FROM OLD.applied_at
           OR NEW.id IS DISTINCT FROM OLD.id
           OR NEW.released_by_user_id IS NULL
           OR NEW.released_by_user_id=OLD.applied_by_user_id THEN
            RAISE EXCEPTION 'Illegal hold release' USING ERRCODE='23514';
        END IF;
        v_legal := NEW.released_by_user_id;
    END IF;

    SELECT * INTO v_review FROM public.erasure_reviews_v105 r
    WHERE r.request_id=NEW.request_id AND r.organization_id=NEW.organization_id
    FOR SHARE;
    IF NOT FOUND OR v_review.review_state IN ('WITHDRAWN','REJECTED')
       OR NOT EXISTS (SELECT 1 FROM public.erasure_requests_v103 b
           WHERE b.id=NEW.request_id AND b.organization_id=NEW.organization_id
             AND b.state='SUBMITTED')
       OR (TG_OP='INSERT' AND EXISTS (SELECT 1 FROM public.erasure_review_holds_v105 h
           WHERE h.request_id=NEW.request_id AND h.released_at IS NULL)) THEN
        RAISE EXCEPTION 'Erasure hold is not available' USING ERRCODE='23514';
    END IF;
    IF v_legal = v_review.requester_user_id OR NOT EXISTS (
        SELECT 1 FROM public.erasure_review_authorities_v105 a
        JOIN public.users u ON u.id=a.user_id AND u.organization_id=a.organization_id
        JOIN public.organizations o ON o.id=a.organization_id
        WHERE a.user_id=v_legal AND a.organization_id=NEW.organization_id
          AND a.authority='LEGAL' AND a.enabled AND u.enabled AND o.enabled
        FOR SHARE OF a,u,o
    ) THEN
        RAISE EXCEPTION 'Independent legal authority required' USING ERRCODE='23503';
    END IF;
    PERFORM public.assert_erasure_review_actor_v105(v_legal,NEW.organization_id,
        NEW.request_id, CASE WHEN TG_OP='INSERT' THEN 'HOLD_APPLIED' ELSE 'HOLD_RELEASED' END,
        v_review.review_version+1, CASE WHEN TG_OP='INSERT' THEN NEW.apply_key ELSE NEW.release_key END);
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_erasure_hold_guard_v105
    BEFORE INSERT OR UPDATE OR DELETE ON public.erasure_review_holds_v105
    FOR EACH ROW EXECUTE FUNCTION public.guard_erasure_hold_v105();

-- Derived legal-hold events: the database, not a separate application UPDATE,
-- increments review_version and creates the matching history entry atomically.
CREATE FUNCTION public.sync_erasure_review_hold_v105()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        UPDATE public.erasure_reviews_v105
           SET review_state='PENDING', review_version=review_version+1,
               hold_revision=hold_revision+1, updated_by_user_id=NEW.applied_by_user_id,
               last_event_code='HOLD_APPLIED',last_key=NEW.apply_key,
               reason_code=NEW.reason_code,updated_at=transaction_timestamp()
         WHERE request_id=NEW.request_id AND organization_id=NEW.organization_id;
    ELSE
        UPDATE public.erasure_reviews_v105
           SET review_state='PENDING', review_version=review_version+1,
               hold_revision=hold_revision+1, updated_by_user_id=NEW.released_by_user_id,
               last_event_code='HOLD_RELEASED',last_key=NEW.release_key,
               reason_code=NULL,updated_at=transaction_timestamp()
         WHERE request_id=NEW.request_id AND organization_id=NEW.organization_id;
    END IF;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Missing review for legal hold' USING ERRCODE='23503';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_erasure_hold_sync_v105
    AFTER INSERT OR UPDATE OF released_at,released_by_user_id,release_key
    ON public.erasure_review_holds_v105
    FOR EACH ROW EXECUTE FUNCTION public.sync_erasure_review_hold_v105();

REVOKE ALL ON FUNCTION public.assert_erasure_review_actor_v105(uuid,uuid,uuid,text,bigint,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.sync_erasure_review_v105() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_erasure_review_v105() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.journal_erasure_review_v105() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_erasure_review_event_insert_v105() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_erasure_hold_v105() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.sync_erasure_review_hold_v105() FROM PUBLIC;

REVOKE ALL ON public.erasure_review_authorities_v105,
    public.erasure_reviews_v105,public.erasure_review_events_v105,
    public.erasure_review_holds_v105,public.erasure_review_actor_keys_v105 FROM PUBLIC;
GRANT SELECT ON public.erasure_review_authorities_v105 TO safeai_app;
GRANT SELECT ON public.erasure_reviews_v105 TO safeai_app;
GRANT UPDATE (review_state,review_version,hold_revision,updated_by_user_id,
    last_event_code,last_key,reason_code,updated_at) ON public.erasure_reviews_v105 TO safeai_app;
GRANT SELECT ON public.erasure_review_events_v105 TO safeai_app;
GRANT SELECT,INSERT ON public.erasure_review_holds_v105 TO safeai_app;
GRANT UPDATE (released_at,released_by_user_id,release_key)
    ON public.erasure_review_holds_v105 TO safeai_app;
