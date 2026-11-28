-- SafeAI-Desk/backend/src/test/resources/model/commitment-ledger-v101-fixture.sql
-- Isolated PostgreSQL fixture for the V62 view only. Full-schema upgrade
-- and authoritative admission triggers are tested in independent suites.
-- Include mandatory production metadata so inserts do not rely on missing
-- defaults. Synthetic hashes identify fixture data, not signed decisions.
SET TIME ZONE 'UTC';
CREATE TABLE public.organization_model_policies (
    id uuid PRIMARY KEY, version integer NOT NULL, organization_id uuid NOT NULL,
    enabled boolean NOT NULL, max_request_cost_usd numeric(30,12),
    budget_enforcement varchar(16) NOT NULL, created_by_user_id uuid NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE TABLE public.model_route_decisions (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL,
    user_id uuid NOT NULL, chat_id uuid NOT NULL, client_request_id uuid NOT NULL,
    chat_turn_id uuid UNIQUE,
    request_content_hash varchar(64) NOT NULL, reason varchar(64) NOT NULL,
    decision_sha256 varchar(64) NOT NULL,
    created_at timestamptz NOT NULL, outcome text NOT NULL,
    estimated_max_cost_usd numeric(30,12), pricing_complete boolean NOT NULL,
    budget_enforcement text NOT NULL, monthly_budget_usd numeric(30,12),
    policy_id uuid, policy_version integer
);
CREATE TABLE public.chat_messages (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL, session_id uuid NOT NULL,
    role varchar(50) NOT NULL, content text NOT NULL, created_at timestamptz NOT NULL,
    usage_status text NOT NULL, pricing_status text NOT NULL, cost_usd numeric(30,12)
);
CREATE TABLE public.chat_turns (
    id uuid PRIMARY KEY, model_route_decision_id uuid, state text NOT NULL,
    organization_id uuid NOT NULL, user_id uuid NOT NULL, session_id uuid NOT NULL,
    client_request_id uuid NOT NULL, request_content_hash varchar(64) NOT NULL,
    provider_operation_id uuid NOT NULL UNIQUE, user_message_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    provider_call_started_at timestamptz, assistant_message_id uuid
);
CREATE TABLE public.model_execution_plans (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL,
    model_route_decision_id uuid, chat_turn_id uuid NOT NULL UNIQUE,
    provider_operation_id uuid NOT NULL UNIQUE, requested_model varchar(100) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE TABLE public.model_execution_attempts (
    id uuid PRIMARY KEY, execution_plan_id uuid NOT NULL, attempt_number integer NOT NULL,
    provider_attempt_id uuid NOT NULL UNIQUE, provider_type varchar(32) NOT NULL,
    provider_configuration_ref varchar(255) NOT NULL, provider_configuration_version varchar(64) NOT NULL,
    deployment_ref varchar(255) NOT NULL, deployment_version varchar(64) NOT NULL,
    requested_physical_model varchar(100) NOT NULL, fallback_safety varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    started_at timestamptz NOT NULL, outcome text NOT NULL, outcome_certainty text NOT NULL,
    retry_safety text NOT NULL, usage_status text, specialized_dimensions_valid boolean,
    pricing_status text, cost_usd numeric(30,12),
    CONSTRAINT uq_model_execution_attempt_number_v50 UNIQUE (execution_plan_id, attempt_number)
);
CREATE INDEX idx_model_route_decisions_org_month
    ON public.model_route_decisions (organization_id, created_at DESC) WHERE outcome = 'ALLOWED';
CREATE UNIQUE INDEX ux_chat_turns_model_route_decision
    ON public.chat_turns (model_route_decision_id) WHERE model_route_decision_id IS NOT NULL;
CREATE INDEX idx_model_execution_plans_organization_id_v74
    ON public.model_execution_plans (organization_id, id);
CREATE INDEX idx_execution_attempts_monthly_cost_v60
    ON public.model_execution_attempts (execution_plan_id, started_at);
CREATE INDEX idx_model_execution_attempts_started_plan_v74
    ON public.model_execution_attempts (started_at, execution_plan_id);
CREATE INDEX idx_model_execution_attempts_plan_started_v97
    ON public.model_execution_attempts (execution_plan_id, started_at);

INSERT INTO public.organization_model_policies
    (id, version, organization_id, enabled, max_request_cost_usd,
     budget_enforcement, created_by_user_id, created_at)
VALUES (md5('policy')::uuid, 1, md5('org0')::uuid, true, 1,
        'SOFT', md5('policy-creator')::uuid, '2026-08-01T00:00:00Z');

INSERT INTO public.model_route_decisions
    (id, organization_id, created_at, outcome, estimated_max_cost_usd, pricing_complete,
     budget_enforcement, monthly_budget_usd, policy_id, policy_version,
     user_id, chat_id, client_request_id, request_content_hash, reason, decision_sha256, chat_turn_id)
SELECT md5('d'||g)::uuid, md5('org0')::uuid,
       CASE WHEN g = 15 THEN '2026-08-15'::timestamptz ELSE '2026-09-15'::timestamptz END,
       'ALLOWED',
       CASE g WHEN 2 THEN 0.50 WHEN 3 THEN 0.30 WHEN 4 THEN 0.40
              WHEN 5 THEN 0.60 WHEN 13 THEN 0.70 WHEN 12 THEN NULL ELSE 1 END,
       g <> 12, CASE WHEN g = 6 THEN 'HARD' ELSE 'SOFT' END,
       CASE WHEN g = 6 THEN 100 END,
       CASE WHEN g = 7 THEN md5('policy')::uuid END,
       CASE WHEN g = 7 THEN 1 END,
       md5('user'||g)::uuid, md5('session'||g)::uuid, md5('request'||g)::uuid,
       encode(sha256(convert_to('request:'||g, 'UTF8')), 'hex'), 'RUNTIME_ONLY_MATCH',
       encode(sha256(convert_to('fixture-decision:'||g, 'UTF8')), 'hex'), md5('t'||g)::uuid
FROM generate_series(1,15) g;
INSERT INTO public.chat_messages
    (id, usage_status, pricing_status, cost_usd, organization_id, session_id, role, content, created_at)
SELECT md5('m'||g)::uuid, 'AVAILABLE', 'PRICED', 0.08, md5('org0')::uuid,
       md5('session'||g)::uuid, 'ASSISTANT', 'Fixture response', '2026-09-15T00:00:00Z'
FROM generate_series(1,15) g;
INSERT INTO public.chat_turns
    (id, model_route_decision_id, state, provider_call_started_at, assistant_message_id,
     organization_id, user_id, session_id, client_request_id, request_content_hash,
     provider_operation_id, user_message_id, created_at, updated_at)
SELECT d.chat_turn_id, d.id,
       CASE WHEN g IN (1,8,9,14,15) THEN 'SUCCEEDED'
            WHEN g = 10 THEN 'FAILED' WHEN g = 11 THEN 'AMBIGUOUS' ELSE 'PROCESSING' END,
       '2026-09-15'::timestamptz, md5('m'||g)::uuid,
       d.organization_id, d.user_id, d.chat_id, d.client_request_id, d.request_content_hash,
       md5('operation'||g)::uuid, md5('user-message'||g)::uuid,
       d.created_at, '2026-09-15T00:00:00Z'
FROM generate_series(1,15) g
JOIN public.model_route_decisions d ON d.id = md5('d'||g)::uuid;
INSERT INTO public.model_execution_plans
    (id, organization_id, model_route_decision_id, chat_turn_id,
     provider_operation_id, requested_model, created_at)
SELECT md5('p'||g)::uuid, t.organization_id, t.model_route_decision_id, t.id,
       t.provider_operation_id, 'gpt-test', t.created_at
FROM generate_series(1,15) g
JOIN public.chat_turns t ON t.id = md5('t'||g)::uuid
WHERE g NOT IN (3,9,10,12,15);
INSERT INTO public.model_execution_attempts
    (id, execution_plan_id, attempt_number, started_at, outcome, outcome_certainty,
     retry_safety, usage_status, specialized_dimensions_valid, pricing_status, cost_usd,
     provider_attempt_id, provider_type, provider_configuration_ref, provider_configuration_version,
     deployment_ref, deployment_version, requested_physical_model, fallback_safety, created_at)
SELECT md5('a'||g)::uuid, md5('p'||g)::uuid, 1,
       CASE WHEN g = 2 THEN '2026-08-15'::timestamptz
            WHEN g = 14 THEN '2026-10-01'::timestamptz ELSE '2026-09-15'::timestamptz END,
       CASE WHEN g IN (1,14) THEN 'SUCCEEDED' WHEN g = 2 THEN 'STARTED'
            WHEN g = 11 THEN 'AMBIGUOUS' ELSE 'FAILED' END,
       CASE WHEN g IN (1,14) THEN 'KNOWN_EXECUTED'
            WHEN g IN (2,11) THEN 'AMBIGUOUS' ELSE 'KNOWN_NOT_EXECUTED' END,
       CASE WHEN g IN (5,6,7,13) THEN 'SAME_TARGET_RETRY_ALLOWED' ELSE 'SAME_TARGET_RETRY_FORBIDDEN' END,
       CASE WHEN g IN (1,14) THEN 'AVAILABLE' END,
       CASE WHEN g IN (1,14) THEN true END,
       CASE WHEN g IN (1,14) THEN 'PRICED' END,
       CASE WHEN g IN (1,14) THEN 0.02 END,
       md5('provider-attempt'||g||':1')::uuid, 'openai', 'static:openai', 'static-v1',
       'static:openai:gpt-test', 'static-v1', 'gpt-test', 'FALLBACK_FORBIDDEN',
       CASE WHEN g = 2 THEN '2026-08-15'::timestamptz
            WHEN g = 14 THEN '2026-10-01'::timestamptz ELSE '2026-09-15'::timestamptz END
FROM generate_series(1,15) g WHERE g IN (1,2,5,6,7,11,13,14);
-- Latest STARTED must suppress the prior retryable failure, not double reserve.
INSERT INTO public.model_execution_attempts
    (id, execution_plan_id, attempt_number, started_at, outcome, outcome_certainty, retry_safety,
     provider_attempt_id, provider_type, provider_configuration_ref, provider_configuration_version,
     deployment_ref, deployment_version, requested_physical_model, fallback_safety, created_at)
VALUES (md5('a13-second')::uuid, md5('p13')::uuid, 2, '2026-09-15', 'STARTED',
        'AMBIGUOUS', 'SAME_TARGET_RETRY_FORBIDDEN', md5('provider-attempt13:2')::uuid,
        'openai', 'static:openai', 'static-v1', 'static:openai:gpt-test', 'static-v1', 'gpt-test',
        'FALLBACK_FORBIDDEN', '2026-09-15');
