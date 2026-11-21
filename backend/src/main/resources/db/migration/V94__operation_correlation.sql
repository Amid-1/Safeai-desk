-- SafeAI-Desk/backend/src/main/resources/db/migration/V94__operation_correlation.sql
-- Diagnostic identifiers, not authorization, idempotency or integrity keys.
-- Historical rows remain NULL; never fabricate historical correlations.
-- Transactional DDL, fail closed on contention. Run in a maintenance window.
-- The limits apply only to this Flyway transaction, not to application sessions.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE public.audit_events ADD COLUMN correlation_id uuid;
ALTER TABLE public.audit_outbox ADD COLUMN correlation_id uuid;
ALTER TABLE public.model_route_decisions ADD COLUMN correlation_id uuid;
ALTER TABLE public.chat_turns ADD COLUMN correlation_id uuid;
ALTER TABLE public.knowledge_ingestion_jobs ADD COLUMN correlation_id uuid;

CREATE INDEX idx_audit_events_correlation_v94
    ON public.audit_events (correlation_id, created_at DESC, id DESC)
    WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_audit_events_org_correlation_v94
    ON public.audit_events (organization_id, correlation_id, created_at DESC, id DESC)
    WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_model_route_correlation_v94
    ON public.model_route_decisions (organization_id, correlation_id)
    WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_chat_turn_correlation_v94
    ON public.chat_turns (organization_id, correlation_id)
    WHERE correlation_id IS NOT NULL;

-- Preserve all existing V68/V45 guards. Add an independent narrow guard
-- for the new column on otherwise mutable rows.
CREATE FUNCTION public.guard_correlation_update_v94()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V94 correlation ID is immutable';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.guard_correlation_update_v94() FROM PUBLIC;
CREATE TRIGGER trg_audit_outbox_correlation_v94
    BEFORE UPDATE ON public.audit_outbox
    FOR EACH ROW EXECUTE FUNCTION public.guard_correlation_update_v94();
CREATE TRIGGER trg_chat_turn_correlation_v94
    BEFORE UPDATE ON public.chat_turns
    FOR EACH ROW EXECUTE FUNCTION public.guard_correlation_update_v94();
CREATE TRIGGER trg_ingestion_correlation_v94
    BEFORE UPDATE ON public.knowledge_ingestion_jobs
    FOR EACH ROW EXECUTE FUNCTION public.guard_correlation_update_v94();

COMMENT ON COLUMN public.audit_events.correlation_id IS
    'Server-owned diagnostic operation ID; NULL for historical evidence.';
COMMENT ON COLUMN public.model_route_decisions.correlation_id IS
    'Diagnostic link only; not part of the existing canonical decision hash.';
