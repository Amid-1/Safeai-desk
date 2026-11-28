-- SafeAI-Desk/backend/src/main/resources/db/migration/V93__model_policy_preview_confirmations.sql
-- Одно актуальное подтверждение на администратора и целевую организацию.
CREATE TABLE public.model_policy_preview_confirmations (
    organization_id uuid NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
    actor_id uuid NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    draft_hash varchar(64) NOT NULL CHECK (draft_hash ~ '^[0-9a-f]{64}$'),
    context_hash varchar(64) NOT NULL CHECK (context_hash ~ '^[0-9a-f]{64}$'),
    lockout boolean NOT NULL,
    expires_at timestamptz NOT NULL CHECK (isfinite(expires_at)),
    result_policy_id uuid REFERENCES public.organization_model_policies(id),
    PRIMARY KEY (organization_id, actor_id)
);
CREATE INDEX idx_model_policy_confirmation_expiry ON public.model_policy_preview_confirmations(expires_at);
REVOKE ALL ON public.model_policy_preview_confirmations FROM PUBLIC;
COMMENT ON TABLE public.model_policy_preview_confirmations IS
    'Краткоживущее подтверждение симуляции; хранится только хеш случайного идентификатора.';
