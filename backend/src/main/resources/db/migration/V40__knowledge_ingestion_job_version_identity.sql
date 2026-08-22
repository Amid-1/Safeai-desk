/* Safeai-desk/backend/src/main/resources/db/migration/V40__knowledge_ingestion_job_version_identity.sql */
-- A job's document version must belong to the exact document, knowledge base
-- and organization recorded by the job.  The two V39 foreign keys validated
-- those references independently, but could not validate that relationship.
-- V39 used CHAR(64), while the JPA String mapping is VARCHAR(64).  A SHA-256
-- digest has a fixed application-level length; VARCHAR avoids PostgreSQL's
-- blank-padded CHAR semantics and keeps schema validation portable.
alter table knowledge_document_versions
    alter column sha256 type varchar(64)
    using sha256::varchar(64);

do $$
begin
    if exists (
        select 1
        from knowledge_ingestion_jobs job
        join knowledge_document_versions document_version
          on document_version.id = job.document_version_id
        where document_version.document_id <> job.document_id
           or document_version.knowledge_base_id <> job.knowledge_base_id
           or document_version.organization_id <> job.organization_id
    ) then
        raise exception
            'Cannot add ingestion-job version identity constraint: inconsistent existing jobs found';
    end if;
end;
$$;

alter table knowledge_ingestion_jobs
    add constraint fk_knowledge_ingestion_jobs_version_identity
        foreign key (
            document_version_id,
            document_id,
            knowledge_base_id,
            organization_id
        )
        references knowledge_document_versions (
            id,
            document_id,
            knowledge_base_id,
            organization_id
        )
        on delete cascade;
