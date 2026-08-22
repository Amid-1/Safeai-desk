/* Safeai-desk/backend/src/main/resources/db/migration/V41__durable_knowledge_ingestion_and_hybrid_retrieval.sql */
-- SafeAI Desk V41
-- Durable, fenced knowledge ingestion; immutable chunks; pgvector + FTS;
-- retrieval provenance for citations and Answer Passport.

create extension if not exists vector;

alter table knowledge_ingestion_jobs
    add column processing_token uuid,
    add column claimed_at timestamptz,
    add column lease_until timestamptz,
    add column next_attempt_at timestamptz not null default now(),
    add column extractor_version varchar(128),
    add column chunker_version varchar(128),
    add column embedding_model varchar(128),
    add column extracted_char_count integer,
    add column chunk_count integer;

-- No worker existed before V41, therefore an interrupted pre-V41 active state
-- cannot be owned safely and is returned to the queue.
update knowledge_ingestion_jobs
set status = 'PENDING',
    processing_token = null,
    claimed_at = null,
    lease_until = null,
    next_attempt_at = now()
where status in ('VALIDATING', 'EXTRACTING');

update knowledge_ingestion_jobs
set started_at = coalesce(started_at, created_at),
    finished_at = coalesce(finished_at, updated_at),
    attempt = greatest(attempt, 1)
where status in ('READY', 'FAILED');

alter table knowledge_ingestion_jobs
    drop constraint chk_knowledge_ingestion_jobs_status,
    drop constraint chk_knowledge_ingestion_jobs_dates;

alter table knowledge_ingestion_jobs
    add constraint chk_knowledge_ingestion_jobs_status
        check (status in (
            'PENDING',
            'VALIDATING',
            'EXTRACTING',
            'CHUNKING',
            'READY',
            'FAILED'
        )),
    add constraint chk_knowledge_ingestion_jobs_state
        check (
            (
                status = 'PENDING'
                and processing_token is null
                and lease_until is null
                and finished_at is null
            )
            or (
                status in ('VALIDATING', 'EXTRACTING', 'CHUNKING')
                and processing_token is not null
                and claimed_at is not null
                and lease_until is not null
                and started_at is not null
                and finished_at is null
            )
            or (
                status in ('READY', 'FAILED')
                and processing_token is null
                and lease_until is null
                and started_at is not null
                and finished_at is not null
            )
        ),
    add constraint chk_knowledge_ingestion_jobs_result_counts
        check (
            extracted_char_count is null
            or extracted_char_count >= 0
        ),
    add constraint chk_knowledge_ingestion_jobs_chunk_count
        check (chunk_count is null or chunk_count >= 0),
    add constraint chk_knowledge_ingestion_jobs_dates
        check (
            updated_at >= created_at
            and (claimed_at is null or claimed_at >= created_at)
            and (lease_until is null or claimed_at is not null)
            and (finished_at is null or started_at is not null)
        );

drop index idx_knowledge_ingestion_jobs_queue;

create index idx_knowledge_ingestion_jobs_queue
    on knowledge_ingestion_jobs (next_attempt_at, created_at, id)
    where status = 'PENDING';

create index idx_knowledge_ingestion_jobs_expired_lease
    on knowledge_ingestion_jobs (lease_until, id)
    where status in ('VALIDATING', 'EXTRACTING', 'CHUNKING');

create table knowledge_document_chunks (
    id uuid primary key,
    organization_id uuid not null,
    knowledge_base_id uuid not null,
    document_id uuid not null,
    document_version_id uuid not null,
    ordinal integer not null,
    content text not null,
    content_sha256 varchar(64) not null,
    estimated_tokens integer not null,
    page_from integer,
    page_to integer,
    heading varchar(500),
    extractor_version varchar(128) not null,
    chunker_version varchar(128) not null,
    embedding_model varchar(128) not null,
    embedding vector(384) not null,
    search_vector tsvector generated always as (
        to_tsvector('simple', content)
    ) stored,
    created_at timestamptz not null default now(),

    constraint uq_knowledge_document_chunks_version_ordinal
        unique (document_version_id, ordinal),
    constraint uq_knowledge_document_chunks_identity
        unique (id, knowledge_base_id, organization_id),
    constraint fk_knowledge_document_chunks_version_identity
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
        on delete cascade,
    constraint chk_knowledge_document_chunks_ordinal
        check (ordinal >= 0),
    constraint chk_knowledge_document_chunks_content
        check (length(content) between 1 and 20000),
    constraint chk_knowledge_document_chunks_sha
        check (content_sha256 ~ '^[0-9a-f]{64}$'),
    constraint chk_knowledge_document_chunks_tokens
        check (estimated_tokens > 0),
    constraint chk_knowledge_document_chunks_pages
        check (
            (page_from is null and page_to is null)
            or (
                page_from is not null
                and page_to is not null
                and page_from > 0
                and page_to >= page_from
            )
        )
);

create index idx_knowledge_document_chunks_version
    on knowledge_document_chunks (
        organization_id,
        knowledge_base_id,
        document_version_id,
        ordinal
    );

create index idx_knowledge_document_chunks_fts
    on knowledge_document_chunks using gin (search_vector);

create index idx_knowledge_document_chunks_embedding_hnsw
    on knowledge_document_chunks
    using hnsw (embedding vector_cosine_ops);

create table knowledge_retrieval_runs (
    id uuid primary key,
    organization_id uuid not null,
    knowledge_base_id uuid not null,
    user_id uuid not null,
    query_text varchar(4000) not null,
    query_sha256 varchar(64) not null,
    embedding_model varchar(128) not null,
    top_k integer not null,
    candidate_limit integer not null,
    rrf_k integer not null,
    started_at timestamptz not null,
    completed_at timestamptz not null,

    constraint uq_knowledge_retrieval_runs_id_org
        unique (id, organization_id),
    constraint uq_knowledge_retrieval_runs_identity
        unique (id, knowledge_base_id, organization_id),
    constraint fk_knowledge_retrieval_runs_kb_org
        foreign key (knowledge_base_id, organization_id)
        references knowledge_bases (id, organization_id)
        on delete restrict,
    constraint chk_knowledge_retrieval_runs_query
        check (length(btrim(query_text)) between 1 and 4000),
    constraint chk_knowledge_retrieval_runs_query_sha
        check (query_sha256 ~ '^[0-9a-f]{64}$'),
    constraint chk_knowledge_retrieval_runs_limits
        check (top_k between 1 and 100 and candidate_limit >= top_k),
    constraint chk_knowledge_retrieval_runs_rrf
        check (rrf_k > 0),
    constraint chk_knowledge_retrieval_runs_dates
        check (completed_at >= started_at)
);

create index idx_knowledge_retrieval_runs_user_created
    on knowledge_retrieval_runs (
        organization_id,
        user_id,
        completed_at desc,
        id desc
    );

create table knowledge_retrieval_hits (
    id uuid primary key,
    retrieval_run_id uuid not null,
    organization_id uuid not null,
    knowledge_base_id uuid not null,
    chunk_id uuid not null,
    rank integer not null,
    fused_score double precision not null,
    lexical_rank integer,
    semantic_rank integer,
    lexical_score real,
    cosine_similarity real,
    created_at timestamptz not null default now(),

    constraint uq_knowledge_retrieval_hits_run_rank
        unique (retrieval_run_id, rank),
    constraint uq_knowledge_retrieval_hits_run_chunk
        unique (retrieval_run_id, chunk_id),
    constraint fk_knowledge_retrieval_hits_run_identity
        foreign key (
            retrieval_run_id,
            knowledge_base_id,
            organization_id
        )
        references knowledge_retrieval_runs (
            id,
            knowledge_base_id,
            organization_id
        )
        on delete cascade,
    constraint fk_knowledge_retrieval_hits_chunk_identity
        foreign key (chunk_id, knowledge_base_id, organization_id)
        references knowledge_document_chunks (
            id,
            knowledge_base_id,
            organization_id
        )
        on delete restrict,
    constraint chk_knowledge_retrieval_hits_rank
        check (rank > 0),
    constraint chk_knowledge_retrieval_hits_component_ranks
        check (
            (lexical_rank is null or lexical_rank > 0)
            and (semantic_rank is null or semantic_rank > 0)
        ),
    constraint chk_knowledge_retrieval_hits_scores
        check (
            fused_score > 0
            and (
                cosine_similarity is null
                or cosine_similarity between -1 and 1
            )
        )
);

create index idx_knowledge_retrieval_hits_run
    on knowledge_retrieval_hits (retrieval_run_id, rank);

create function reject_knowledge_chunk_update()
returns trigger
language plpgsql
as $$
begin
    raise exception
        'knowledge_document_chunks are immutable; rebuild the version instead';
end;
$$;

create trigger trg_knowledge_document_chunks_immutable
before update on knowledge_document_chunks
for each row execute function reject_knowledge_chunk_update();

insert into audit_event_types (name, description)
values
    ('KNOWLEDGE_INGESTION_READY', 'Knowledge document ingestion completed'),
    ('KNOWLEDGE_INGESTION_FAILED', 'Knowledge document ingestion failed'),
    ('KNOWLEDGE_RETRIEVAL_COMPLETED', 'Knowledge hybrid retrieval completed')
on conflict (name) do nothing;

comment on table knowledge_document_chunks is
    'Immutable retrieval units derived from one exact document version.';

comment on table knowledge_retrieval_runs is
    'Retrieval provenance: query, profile and execution identity.';

comment on table knowledge_retrieval_hits is
    'Ranked immutable chunk selections for citations and Answer Passport.';
