-- SafeAI-Desk/backend/src/main/resources/db/migration/V102__platform_erasure_admission_policy.sql
-- Platform-wide admission policy for new erasure requests.
-- Does not enable worker infrastructure or override retention holds.
-- Preserve existing behavior for already provisioned installations.

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
SET LOCAL search_path = pg_catalog, pg_temp;

CREATE TABLE public.platform_erasure_policy_v102 (
    singleton boolean PRIMARY KEY DEFAULT true,
    chat_allowed boolean NOT NULL,
    knowledge_allowed boolean NOT NULL,
    version bigint NOT NULL DEFAULT 0,

    CONSTRAINT chk_platform_erasure_singleton_v102
        CHECK (singleton IS TRUE),

    CONSTRAINT chk_platform_erasure_version_v102
        CHECK (version >= 0)
);

INSERT INTO public.platform_erasure_policy_v102 (
    singleton,
    chat_allowed,
    knowledge_allowed,
    version
)
VALUES (true, true, true, 0);

COMMENT ON TABLE public.platform_erasure_policy_v102 IS
    'Platform-wide admission policy for new content erasure requests';

COMMENT ON COLUMN public.platform_erasure_policy_v102.chat_allowed IS
    'Allows new chat erasure requests subject to server safety checks';

COMMENT ON COLUMN public.platform_erasure_policy_v102.knowledge_allowed IS
    'Allows new knowledge document and base erasure requests';

COMMENT ON COLUMN public.platform_erasure_policy_v102.version IS
    'Optimistic concurrency control version';

REVOKE ALL
    ON TABLE public.platform_erasure_policy_v102
    FROM PUBLIC;

GRANT SELECT
    ON TABLE public.platform_erasure_policy_v102
    TO safeai_app;

GRANT UPDATE (chat_allowed, knowledge_allowed, version)
    ON TABLE public.platform_erasure_policy_v102
    TO safeai_app;

INSERT INTO public.audit_event_types (name, description)
VALUES (
    'RETENTION_POLICY_CHANGED',
    'Platform admission policy for new content erasure requests changed'
)
ON CONFLICT (name) DO NOTHING;
