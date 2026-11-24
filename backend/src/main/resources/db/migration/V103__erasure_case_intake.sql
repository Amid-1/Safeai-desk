-- SafeAI-Desk/backend/src/main/resources/db/migration/V103__erasure_case_intake.sql
-- R1a: durable case intake ONLY. No destructive worker, trigger or dispatch.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
SET LOCAL search_path = pg_catalog, pg_temp;

CREATE TABLE public.erasure_previews_v103 (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES public.organizations(id),
    requester_user_id uuid NOT NULL,
    resource_type varchar(32) NOT NULL,
    resource_id uuid NOT NULL,
    operation varchar(32) NOT NULL,
    scope varchar(64) NOT NULL,
    resource_version bigint NOT NULL CHECK (resource_version >= 0),
    preview_digest char(64) NOT NULL CHECK (preview_digest ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    expires_at timestamptz NOT NULL,
    CONSTRAINT uq_erasure_preview_identity_v103 UNIQUE
        (id, organization_id, requester_user_id),
    CONSTRAINT fk_erasure_preview_actor_v103 FOREIGN KEY
        (requester_user_id, organization_id)
        REFERENCES public.users(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT ck_erasure_preview_expiry_v103 CHECK (expires_at > created_at)
);

CREATE TABLE public.erasure_requests_v103 (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES public.organizations(id),
    requested_by_user_id uuid NOT NULL,
    resource_type varchar(32) NOT NULL,
    resource_id uuid NOT NULL,
    operation varchar(32) NOT NULL,
    scope varchar(64) NOT NULL,
    reason_code varchar(32) NOT NULL,
    preview_id uuid NOT NULL UNIQUE,
    preview_digest char(64) NOT NULL,
    expected_resource_version bigint NOT NULL CHECK (expected_resource_version >= 0),
    idempotency_key varchar(128) NOT NULL,
    submit_key varchar(128),
    withdraw_key varchar(128),
    request_digest char(64) NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'DRAFT',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    submitted_at timestamptz,
    CONSTRAINT fk_erasure_request_preview_v103 FOREIGN KEY
        (preview_id, organization_id, requested_by_user_id)
        REFERENCES public.erasure_previews_v103
        (id, organization_id, requester_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_erasure_request_actor_v103 FOREIGN KEY
        (requested_by_user_id, organization_id)
        REFERENCES public.users(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT uq_erasure_request_id_org_v103 UNIQUE (id, organization_id),
    CONSTRAINT uq_erasure_request_idempotency_v103 UNIQUE
        (organization_id, requested_by_user_id, idempotency_key),
    CONSTRAINT ck_erasure_request_digests_v103 CHECK
        (preview_digest ~ '^[a-f0-9]{64}$'
         AND request_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_erasure_request_state_v103 CHECK
        (state IN ('DRAFT', 'SUBMITTED', 'WITHDRAWN')),
    CONSTRAINT ck_erasure_request_version_v103 CHECK (version >= 0),
    CONSTRAINT ck_erasure_request_submission_v103 CHECK
        ((state = 'SUBMITTED') = (submitted_at IS NOT NULL)
         OR state = 'WITHDRAWN'),
    CONSTRAINT ck_erasure_request_scope_v103 CHECK (
        (resource_type='CHAT' AND operation='REDACT_CONTENT'
            AND scope='FULL_CHAT_CONTENT') OR
        (resource_type='USER_ACCOUNT' AND operation='DELETE_ACCOUNT'
            AND scope='PERSONAL_DATA_AND_ACCESS') OR
        (resource_type='KNOWLEDGE_DOCUMENT' AND operation='DELETE_OBJECT'
            AND scope='FULL_DOCUMENT') OR
        (resource_type='KNOWLEDGE_BASE' AND operation='DELETE_OBJECT'
            AND scope='FULL_BASE') OR
        (resource_type='ORGANIZATION' AND operation='OFFBOARD_TENANT'
            AND scope='TENANT_CLOSURE')
    )
);
CREATE INDEX ix_erasure_requests_self_v103
    ON public.erasure_requests_v103
    (organization_id, requested_by_user_id, created_at DESC, id DESC);
CREATE INDEX ix_erasure_requests_queue_v103
    ON public.erasure_requests_v103
    (organization_id, state, created_at DESC, id DESC);

CREATE TABLE public.erasure_request_events_v103 (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    actor_user_id uuid NOT NULL,
    event_type varchar(24) NOT NULL CHECK
        (event_type IN ('DRAFT_CREATED', 'SUBMITTED', 'WITHDRAWN')),
    version bigint NOT NULL CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT fk_erasure_event_request_v103 FOREIGN KEY (request_id, organization_id)
        REFERENCES public.erasure_requests_v103(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT uq_erasure_event_version_v103 UNIQUE (request_id, version)
);
CREATE INDEX ix_erasure_events_request_v103
    ON public.erasure_request_events_v103 (request_id, version);

CREATE FUNCTION public.reject_erasure_event_change_v103()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Erasure case history is append-only';
END
$$;
CREATE TRIGGER trg_erasure_events_immutable_v103
    BEFORE UPDATE OR DELETE ON public.erasure_request_events_v103
    FOR EACH ROW EXECUTE FUNCTION public.reject_erasure_event_change_v103();

REVOKE ALL ON public.erasure_previews_v103,
    public.erasure_requests_v103, public.erasure_request_events_v103 FROM PUBLIC;
GRANT SELECT, INSERT ON public.erasure_previews_v103 TO safeai_app;
GRANT SELECT, INSERT ON public.erasure_requests_v103 TO safeai_app;
GRANT UPDATE (state, version, updated_at, submitted_at,
    submit_key, withdraw_key)
    ON public.erasure_requests_v103 TO safeai_app;
GRANT SELECT, INSERT ON public.erasure_request_events_v103 TO safeai_app;
