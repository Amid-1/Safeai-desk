-- SafeAI-Desk/backend/src/main/resources/db/migration/V84__knowledge_erasure_terminal_audit.sql
-- SafeAI Desk V84: transactional terminal/attention-state audit intents.
-- Append-only migration; V1-V83, retention fences and deletion flags unchanged.
-- The actual outbox insert is part of the SAME PostgreSQL transaction as the
-- state transition, even when the executor runs outside Spring-managed proxies.

INSERT INTO public.audit_event_types(name, description) VALUES
  ('KNOWLEDGE_DOCUMENT_DELETION_COMPLETED', 'Verified document erasure committed'),
  ('KNOWLEDGE_DOCUMENT_DELETION_REVIEW_REQUIRED', 'Document erasure entered operator review'),
  ('KNOWLEDGE_DOCUMENT_DELETION_RETENTION_BLOCKED', 'Verified document Object Lock blocked erasure'),
  ('KNOWLEDGE_BASE_DELETION_COMPLETED', 'Fully verified base erasure committed'),
  ('KNOWLEDGE_BASE_DELETION_REVIEW_REQUIRED', 'Base erasure entered operator review'),
  ('KNOWLEDGE_BASE_DELETION_RETENTION_BLOCKED', 'A base erasure was blocked by retention')
ON CONFLICT (name) DO NOTHING;

CREATE FUNCTION public.enqueue_knowledge_erasure_terminal_audit_v84()
RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE
    v_kind text;
    v_event text;
    v_base_id uuid;
    v_document_id uuid;
    v_at timestamptz;
BEGIN
    IF NEW.state IS NOT DISTINCT FROM OLD.state
       OR NEW.state NOT IN ('DELETED', 'NEEDS_REVIEW', 'BLOCKED_BY_RETENTION') THEN
        RETURN NEW;
    END IF;
    IF OLD.state <> 'DELETING' THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V84: invalid erasure transition to audited state';
    END IF;

    IF TG_TABLE_NAME = 'knowledge_document_deletions_v78' THEN
        v_kind := 'KNOWLEDGE_DOCUMENT_DELETION_';
        v_document_id := NEW.document_id;
    ELSIF TG_TABLE_NAME = 'knowledge_base_deletions_v82' THEN
        v_kind := 'KNOWLEDGE_BASE_DELETION_';
        v_document_id := NULL;
    ELSE
        RAISE EXCEPTION 'V84: unsupported erasure audit source';
    END IF;
    v_base_id := NEW.knowledge_base_id;
    v_event := v_kind || CASE NEW.state
        WHEN 'DELETED' THEN 'COMPLETED'
        WHEN 'NEEDS_REVIEW' THEN 'REVIEW_REQUIRED'
        ELSE 'RETENTION_BLOCKED' END;
    v_at := clock_timestamp();

    -- No object keys, file names, source text, or provider error messages.
    -- NULL actor denotes a system worker. Actor of the original request is
    -- preserved in the immutable V78/V82 erasure intent separately.
    INSERT INTO public.audit_outbox
      (id, actor_user_id, organization_id, event_type, details,
       occurred_at, created_at, attempt_count, next_attempt_at)
    VALUES
      (gen_random_uuid(), NULL, NEW.organization_id, v_event,
       jsonb_build_object('operationId', NEW.id, 'knowledgeBaseId', v_base_id,
           'documentId', v_document_id, 'state', NEW.state,
           'previousState', OLD.state, 'attempts', NEW.attempts,
           'errorCode', NEW.last_error_code),
       v_at, v_at, 0, v_at);
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_document_erasure_terminal_audit_v84
AFTER UPDATE OF state ON public.knowledge_document_deletions_v78
FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM NEW.state)
EXECUTE FUNCTION public.enqueue_knowledge_erasure_terminal_audit_v84();

CREATE TRIGGER trg_base_erasure_terminal_audit_v84
AFTER UPDATE OF state ON public.knowledge_base_deletions_v82
FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM NEW.state)
EXECUTE FUNCTION public.enqueue_knowledge_erasure_terminal_audit_v84();

COMMENT ON FUNCTION public.enqueue_knowledge_erasure_terminal_audit_v84() IS
 'Mandatory audit_outbox intent in the same transaction as each terminal/attention transition; no external calls.';
