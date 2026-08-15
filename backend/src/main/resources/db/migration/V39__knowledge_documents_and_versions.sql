/* Safeai-desk/backend/src/main/resources/db/migration/V39__knowledge_documents_and_versions.sql */
-- SafeAI Desk V39: tenant-scoped documents, immutable file versions and ingestion jobs.
-- V38 is intentionally not modified.

create table knowledge_documents (
    id uuid primary key,
    organization_id uuid not null,
    knowledge_base_id uuid not null,
    name varchar(255) not null,
    enabled boolean not null default true,
    current_version_id uuid,
    created_by_user_id uuid not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint uq_knowledge_documents_id_org unique (id, organization_id),
    constraint uq_knowledge_documents_id_kb_org unique (id, knowledge_base_id, organization_id),
    constraint fk_knowledge_documents_kb_org foreign key (knowledge_base_id, organization_id)
        references knowledge_bases (id, organization_id) on delete cascade,
    constraint chk_knowledge_documents_name check (name = btrim(name) and length(name) > 0 and name !~ '[[:cntrl:]]'),
    constraint chk_knowledge_documents_version check (version >= 0),
    constraint chk_knowledge_documents_dates check (updated_at >= created_at)
);

create unique index ux_knowledge_documents_kb_name_lower
    on knowledge_documents (knowledge_base_id, lower(name));
create index idx_knowledge_documents_kb_updated
    on knowledge_documents (organization_id, knowledge_base_id, enabled, updated_at desc, id desc);

create table knowledge_document_versions (
    id uuid primary key,
    organization_id uuid not null,
    knowledge_base_id uuid not null,
    document_id uuid not null,
    version_number integer not null,
    original_filename varchar(255) not null,
    media_type varchar(127) not null,
    size_bytes bigint not null,
    sha256 char(64) not null,
    storage_key varchar(1024) not null,
    created_by_user_id uuid not null,
    created_at timestamptz not null default now(),
    constraint uq_knowledge_document_versions_id_org unique (id, organization_id),
    constraint uq_knowledge_document_versions_identity unique (id, document_id, knowledge_base_id, organization_id),
    constraint uq_knowledge_document_versions_doc_number unique (document_id, version_number),
    constraint uq_knowledge_document_versions_storage_key unique (storage_key),
    constraint fk_knowledge_document_versions_doc_org foreign key (document_id, knowledge_base_id, organization_id)
        references knowledge_documents (id, knowledge_base_id, organization_id) on delete cascade,
    constraint chk_knowledge_document_versions_number check (version_number > 0),
    constraint chk_knowledge_document_versions_size check (size_bytes > 0),
    constraint chk_knowledge_document_versions_sha check (sha256 ~ '^[0-9a-f]{64}$'),
    constraint chk_knowledge_document_versions_filename check (original_filename = btrim(original_filename) and length(original_filename) > 0)
);

alter table knowledge_documents
    add constraint fk_knowledge_documents_current_version
    foreign key (current_version_id, id, knowledge_base_id, organization_id)
    references knowledge_document_versions (id, document_id, knowledge_base_id, organization_id)
    deferrable initially deferred;

create index idx_knowledge_document_versions_doc
    on knowledge_document_versions (organization_id, document_id, version_number desc);

create table knowledge_ingestion_jobs (
    id uuid primary key,
    organization_id uuid not null,
    knowledge_base_id uuid not null,
    document_id uuid not null,
    document_version_id uuid not null,
    status varchar(32) not null default 'PENDING',
    attempt integer not null default 0,
    error_code varchar(64),
    error_message varchar(2000),
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint uq_knowledge_ingestion_jobs_version unique (document_version_id),
    constraint fk_knowledge_ingestion_jobs_version_org foreign key (document_version_id, organization_id)
        references knowledge_document_versions (id, organization_id) on delete cascade,
    constraint fk_knowledge_ingestion_jobs_doc_org foreign key (document_id, knowledge_base_id, organization_id)
        references knowledge_documents (id, knowledge_base_id, organization_id) on delete cascade,
    constraint chk_knowledge_ingestion_jobs_status check (status in ('PENDING','VALIDATING','EXTRACTING','READY','FAILED')),
    constraint chk_knowledge_ingestion_jobs_attempt check (attempt >= 0),
    constraint chk_knowledge_ingestion_jobs_version check (version >= 0),
    constraint chk_knowledge_ingestion_jobs_dates check (updated_at >= created_at and (finished_at is null or started_at is not null))
);

create index idx_knowledge_ingestion_jobs_queue
    on knowledge_ingestion_jobs (status, created_at) where status in ('PENDING','FAILED');

create trigger trg_knowledge_documents_updated_at before update on knowledge_documents
    for each row execute function set_updated_at();
create trigger trg_knowledge_ingestion_jobs_updated_at before update on knowledge_ingestion_jobs
    for each row execute function set_updated_at();

create function reject_knowledge_document_version_update()
returns trigger language plpgsql as $$
begin
    raise exception 'knowledge document versions are immutable';
end;
$$;

create trigger trg_knowledge_document_versions_immutable
    before update on knowledge_document_versions
    for each row execute function reject_knowledge_document_version_update();

insert into audit_event_types (name, description) values
    ('KNOWLEDGE_DOCUMENT_CREATED', 'Knowledge document created'),
    ('KNOWLEDGE_DOCUMENT_VERSION_UPLOADED', 'Knowledge document version uploaded'),
    ('KNOWLEDGE_DOCUMENT_DOWNLOADED', 'Knowledge document downloaded')
on conflict (name) do nothing;

comment on table knowledge_document_versions is 'Immutable metadata for uploaded originals; UPDATE and DELETE are denied by application policy.';
comment on column knowledge_document_versions.storage_key is 'Opaque object-storage key; never supplied by a client.';
