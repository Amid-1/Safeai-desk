-- SafeAI Desk V78: non-destructive foundation for document erasure.
-- This migration does NOT execute or expose physical purge.
-- It makes DELETING a monotonic, server-guarded state and records durable requests.
-- Physical cleanup must be implemented and tested separately before enabling DELETE API.

ALTER TABLE public.knowledge_documents
  ADD COLUMN lifecycle_state varchar(16) NOT NULL DEFAULT 'AVAILABLE';
ALTER TABLE public.knowledge_documents
  ADD CONSTRAINT chk_knowledge_document_lifecycle_v78
  CHECK (lifecycle_state IN ('AVAILABLE', 'DELETING'));

CREATE TABLE public.knowledge_document_deletions_v78 (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  knowledge_base_id uuid NOT NULL,
  document_id uuid NOT NULL,
  requested_by_user_id uuid NOT NULL,
  idempotency_key varchar(128) NOT NULL,
  expected_version bigint NOT NULL,
  state varchar(32) NOT NULL DEFAULT 'DELETING',
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  lease_token uuid,
  lease_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz,
  last_error_code varchar(64),
  CONSTRAINT uq_knowledge_delete_document_v78 UNIQUE (organization_id, document_id),
  CONSTRAINT uq_knowledge_delete_request_v78 UNIQUE (organization_id, idempotency_key),
  CONSTRAINT chk_knowledge_delete_expected_version_v78 CHECK (expected_version >= 0),
  CONSTRAINT chk_knowledge_delete_attempts_v78 CHECK (attempts >= 0),
  CONSTRAINT chk_knowledge_delete_status_v78
    CHECK (state IN ('DELETING','NEEDS_REVIEW','BLOCKED_BY_RETENTION','DELETED')),
  CONSTRAINT chk_knowledge_delete_lease_v78 CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
  CONSTRAINT chk_knowledge_delete_completion_v78 CHECK (
    (state = 'DELETED' AND completed_at IS NOT NULL)
    OR (state <> 'DELETED' AND completed_at IS NULL))
);
CREATE INDEX idx_knowledge_delete_pending_v78
  ON public.knowledge_document_deletions_v78 (next_attempt_at, id)
  WHERE state = 'DELETING';

-- Keep operation identity stable, including after the document row is gone.
CREATE FUNCTION public.guard_knowledge_document_deletion_identity_v78()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V78: deletion evidence must be retained';
  END IF;
  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
     OR NEW.knowledge_base_id IS DISTINCT FROM OLD.knowledge_base_id
     OR NEW.document_id IS DISTINCT FROM OLD.document_id
     OR NEW.requested_by_user_id IS DISTINCT FROM OLD.requested_by_user_id
     OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
     OR NEW.expected_version IS DISTINCT FROM OLD.expected_version
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V78: immutable deletion operation identity';
  END IF;
  IF OLD.state='DELETED' AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V78: completed deletion is immutable';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_document_deletion_identity_v78
BEFORE UPDATE OR DELETE ON public.knowledge_document_deletions_v78
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_document_deletion_identity_v78();

CREATE FUNCTION public.guard_knowledge_document_lifecycle_v78()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.lifecycle_state <> 'AVAILABLE' THEN
      RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V78: documents start AVAILABLE';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.lifecycle_state='DELETING' THEN
    IF NEW.lifecycle_state <> 'DELETING' OR NEW.enabled
       OR NEW.name IS DISTINCT FROM OLD.name
       OR (NEW.current_version_id IS DISTINCT FROM OLD.current_version_id
           AND NEW.current_version_id IS NOT NULL)
       OR (NEW.searchable_version_id IS DISTINCT FROM OLD.searchable_version_id
           AND NEW.searchable_version_id IS NOT NULL) THEN
      RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V78: cannot restore a deleting document';
    END IF;
  ELSIF NEW.lifecycle_state='DELETING' THEN
    IF NEW.enabled OR NOT EXISTS (
      SELECT 1 FROM public.knowledge_document_deletions_v78 deletion
      WHERE deletion.document_id=NEW.id
        AND deletion.knowledge_base_id=NEW.knowledge_base_id
        AND deletion.organization_id=NEW.organization_id
        AND deletion.state='DELETING'
    ) THEN
      RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V78: deletion must have a durable request and disable retrieval';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_document_lifecycle_v78
BEFORE INSERT OR UPDATE ON public.knowledge_documents
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_document_lifecycle_v78();


-- New versions must never be linked after deletion was accepted.
CREATE FUNCTION public.guard_knowledge_version_lifecycle_v78()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE state text;
BEGIN
  SELECT d.lifecycle_state INTO state
  FROM public.knowledge_documents d
  WHERE d.id=NEW.document_id AND d.knowledge_base_id=NEW.knowledge_base_id
    AND d.organization_id=NEW.organization_id
  FOR SHARE;
  IF state='DELETING' THEN
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V78: new version forbidden for deleting document';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_version_lifecycle_v78
BEFORE INSERT ON public.knowledge_document_versions
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_version_lifecycle_v78();

-- Fences ingestion claims/publication that started before deletion was accepted.
-- Row share lock serializes against the document's state-changing UPDATE.
CREATE FUNCTION public.guard_knowledge_ingestion_lifecycle_v78()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE state text;
BEGIN
  SELECT d.lifecycle_state INTO state
  FROM public.knowledge_documents d
  WHERE d.id=NEW.document_id AND d.knowledge_base_id=NEW.knowledge_base_id
    AND d.organization_id=NEW.organization_id
  FOR SHARE;
  IF state='DELETING' THEN
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V78: ingestion forbidden for deleting document';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_ingestion_lifecycle_v78
BEFORE INSERT OR UPDATE OF status, index_generation ON public.knowledge_ingestion_jobs
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_ingestion_lifecycle_v78();

-- Exclude late chunk writes even if the worker bypasses service-side checks.
CREATE FUNCTION public.guard_knowledge_chunk_publication_lifecycle_v78()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE state text;
BEGIN
  SELECT d.lifecycle_state INTO state
  FROM public.knowledge_documents d
  WHERE d.id=NEW.document_id AND d.knowledge_base_id=NEW.knowledge_base_id
    AND d.organization_id=NEW.organization_id
  FOR SHARE;
  IF state='DELETING' THEN
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V78: chunks forbidden for deleting document';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_chunk_publication_lifecycle_v78
BEFORE INSERT ON public.knowledge_document_chunks
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_chunk_publication_lifecycle_v78();

COMMENT ON COLUMN public.knowledge_documents.lifecycle_state IS
'AVAILABLE or DELETING; independent of enabled/disabled search participation.';
COMMENT ON TABLE public.knowledge_document_deletions_v78 IS
'Durable deletion intent and minimal audit identity. Purge executor intentionally not enabled by V78.';
