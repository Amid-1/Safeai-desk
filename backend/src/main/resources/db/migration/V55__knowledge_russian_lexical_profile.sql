/* Safeai-desk/backend/src/main/resources/db/migration/V55__knowledge_russian_lexical_profile.sql */
/* SafeAI Desk V55: additive Russian stemming for future hybrid retrieval.
 * V1-V54 must remain byte-for-byte unchanged. Existing retrieval runs/hits
 * stay immutable; historical runs retain lexical_profile=NULL (legacy simple).
 * Requires maintenance planning on large knowledge_document_chunks tables.
 */
DO $$
BEGIN
    IF to_regclass('public.knowledge_document_chunks') IS NULL
       OR to_regclass('public.knowledge_retrieval_runs') IS NULL THEN
        RAISE EXCEPTION 'V55 requires the V41 Knowledge retrieval schema';
    END IF;
END
$$;

ALTER TABLE public.knowledge_retrieval_runs
    ADD COLUMN lexical_profile varchar(64);

ALTER TABLE public.knowledge_retrieval_runs
    ADD CONSTRAINT chk_knowledge_retrieval_lexical_profile_v55
    CHECK (lexical_profile IS NULL OR lexical_profile = 'simple+russian-v2') NOT VALID;
ALTER TABLE public.knowledge_retrieval_runs
    VALIDATE CONSTRAINT chk_knowledge_retrieval_lexical_profile_v55;

/* Functional GIN index: the existing simple search_vector index is preserved.
 * The retrieval SQL uses the same immutable expression exactly.
 */
CREATE INDEX idx_knowledge_document_chunks_fts_russian_v55
    ON public.knowledge_document_chunks
    USING gin (to_tsvector('pg_catalog.russian'::regconfig, content));

COMMENT ON COLUMN public.knowledge_retrieval_runs.lexical_profile IS
'NULL is pre-V55 simple-profile provenance; simple+russian-v2 indicates union lexical candidates with max(normalized ts_rank_cd).';
