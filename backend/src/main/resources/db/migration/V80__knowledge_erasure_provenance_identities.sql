-- SafeAI-Desk/backend/src/main/resources/db/migration/V80__knowledge_erasure_provenance_identities.sql
-- V80: keep referential provenance independently of erasure-eligible content.
-- No content is removed here. References are switched only after audited backfill.
CREATE TABLE public.knowledge_document_version_identities_v80 (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    CONSTRAINT uq_knowledge_version_identity_v80
      UNIQUE(id, document_id, knowledge_base_id, organization_id)
);

CREATE TABLE public.knowledge_chunk_identities_v80 (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    CONSTRAINT uq_knowledge_chunk_identity_v80 UNIQUE(id, knowledge_base_id, organization_id),
    CONSTRAINT fk_knowledge_chunk_identity_version_v80
      FOREIGN KEY (document_version_id, document_id, knowledge_base_id, organization_id)
      REFERENCES public.knowledge_document_version_identities_v80
               (id, document_id, knowledge_base_id, organization_id)
      ON DELETE RESTRICT
);

-- Fail before switching any historical FK if identity backfill is incomplete.
INSERT INTO public.knowledge_document_version_identities_v80
    (id, document_id, knowledge_base_id, organization_id)
SELECT id, document_id, knowledge_base_id, organization_id
FROM public.knowledge_document_versions;

INSERT INTO public.knowledge_chunk_identities_v80
    (id, document_id, document_version_id, knowledge_base_id, organization_id)
SELECT id, document_id, document_version_id, knowledge_base_id, organization_id
FROM public.knowledge_document_chunks;

CREATE FUNCTION public.record_knowledge_version_identity_v80()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO public.knowledge_document_version_identities_v80
        (id, document_id, knowledge_base_id, organization_id)
    VALUES (NEW.id, NEW.document_id, NEW.knowledge_base_id, NEW.organization_id);
    RETURN NULL;
END $$;
CREATE TRIGGER trg_knowledge_version_identity_v80
AFTER INSERT ON public.knowledge_document_versions
FOR EACH ROW EXECUTE FUNCTION public.record_knowledge_version_identity_v80();

CREATE FUNCTION public.record_knowledge_chunk_identity_v80()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO public.knowledge_chunk_identities_v80
        (id, document_id, document_version_id, knowledge_base_id, organization_id)
    VALUES (NEW.id, NEW.document_id, NEW.document_version_id,
            NEW.knowledge_base_id, NEW.organization_id);
    RETURN NULL;
END $$;
CREATE TRIGGER trg_knowledge_chunk_identity_v80
AFTER INSERT ON public.knowledge_document_chunks
FOR EACH ROW EXECUTE FUNCTION public.record_knowledge_chunk_identity_v80();

CREATE FUNCTION public.guard_knowledge_erasure_identity_v80()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION USING ERRCODE='23514',
        MESSAGE='V80: audit provenance identity is immutable';
END $$;
CREATE TRIGGER trg_guard_knowledge_version_identity_v80
BEFORE UPDATE OR DELETE ON public.knowledge_document_version_identities_v80
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_erasure_identity_v80();
CREATE TRIGGER trg_guard_knowledge_chunk_identity_v80
BEFORE UPDATE OR DELETE ON public.knowledge_chunk_identities_v80
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_erasure_identity_v80();

-- Preserve RESTRICT semantics while allowing eventual original-content erasure.
-- NOT VALID + VALIDATE makes the transition explicit and checks every existing row.
ALTER TABLE public.knowledge_retrieval_hits
  ADD CONSTRAINT fk_knowledge_retrieval_hits_provenance_v80
  FOREIGN KEY (chunk_id, knowledge_base_id, organization_id)
  REFERENCES public.knowledge_chunk_identities_v80(id, knowledge_base_id, organization_id)
  ON DELETE RESTRICT NOT VALID;
ALTER TABLE public.knowledge_retrieval_hits
  VALIDATE CONSTRAINT fk_knowledge_retrieval_hits_provenance_v80;
ALTER TABLE public.knowledge_retrieval_hits
  DROP CONSTRAINT fk_knowledge_retrieval_hits_chunk_identity;

ALTER TABLE public.knowledge_index_generations
  ADD CONSTRAINT fk_knowledge_generation_provenance_v80
  FOREIGN KEY (document_version_id, document_id, knowledge_base_id, organization_id)
  REFERENCES public.knowledge_document_version_identities_v80
             (id, document_id, knowledge_base_id, organization_id)
  ON DELETE RESTRICT NOT VALID;
ALTER TABLE public.knowledge_index_generations
  VALIDATE CONSTRAINT fk_knowledge_generation_provenance_v80;
ALTER TABLE public.knowledge_index_generations
  DROP CONSTRAINT fk_knowledge_index_generation_version_v57;

COMMENT ON TABLE public.knowledge_document_version_identities_v80 IS
  'Immutable version UUID and tenant lineage. No filename, original bytes, storage key or text.';
COMMENT ON TABLE public.knowledge_chunk_identities_v80 IS
  'Immutable chunk UUID lineage for citations. No content, heading or embeddings.';
