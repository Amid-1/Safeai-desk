/* Safeai-desk/backend/src/main/resources/db/migration/V47__knowledge_chunk_generation_uniqueness.sql */
-- V47: make immutable chunk identity generation-aware for safe reindex.
-- Existing V44/V45/V46 migrations remain immutable.

alter table knowledge_document_chunks
    drop constraint if exists uq_knowledge_document_chunks_version_ordinal;

alter table knowledge_document_chunks
    add constraint uq_knowledge_document_chunks_version_generation_ordinal
        unique (
            document_version_id,
            index_generation,
            ordinal
        );

create index if not exists idx_knowledge_document_chunks_active_generation
    on knowledge_document_chunks (
        organization_id,
        knowledge_base_id,
        document_version_id,
        index_generation,
        ordinal
    );
