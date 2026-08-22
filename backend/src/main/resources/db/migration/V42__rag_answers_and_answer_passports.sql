/* Safeai-desk/backend/src/main/resources/db/migration/V42__rag_answers_and_answer_passports.sql */
-- SafeAI Desk V42
-- Tenant-safe RAG execution identity, retrieval-to-ChatTurn provenance,
-- validated inline citations and immutable Answer Passports.

alter table chat_turns
    add column knowledge_base_id uuid,
    add column knowledge_mode varchar(32) not null default 'GENERAL';

alter table chat_turns
    add constraint chk_chat_turns_knowledge_mode
        check (knowledge_mode in (
            'GENERAL',
            'KNOWLEDGE_ASSISTED',
            'KNOWLEDGE_ONLY'
        )),
    add constraint chk_chat_turns_knowledge_scope
        check (
            (knowledge_mode = 'GENERAL' and knowledge_base_id is null)
            or (
                knowledge_mode in ('KNOWLEDGE_ASSISTED', 'KNOWLEDGE_ONLY')
                and knowledge_base_id is not null
            )
        ),
    add constraint fk_chat_turns_knowledge_base_tenant
        foreign key (knowledge_base_id, organization_id)
        references knowledge_bases (id, organization_id)
        on delete restrict;

alter table chat_turns
    add constraint uq_chat_turns_rag_identity
        unique (id, knowledge_base_id, organization_id, user_id);

alter table knowledge_retrieval_runs
    add column chat_turn_id uuid;

alter table knowledge_retrieval_runs
    add constraint uq_knowledge_retrieval_runs_chat_turn
        unique (chat_turn_id),
    add constraint uq_knowledge_retrieval_runs_rag_identity
        unique (id, knowledge_base_id, organization_id, user_id),
    add constraint fk_knowledge_retrieval_runs_chat_turn
        foreign key (
            chat_turn_id,
            knowledge_base_id,
            organization_id,
            user_id
        )
        references chat_turns (
            id,
            knowledge_base_id,
            organization_id,
            user_id
        )
        on delete restrict;

alter table knowledge_retrieval_hits
    add constraint uq_knowledge_retrieval_hits_citation_identity
        unique (
            retrieval_run_id,
            chunk_id,
            knowledge_base_id,
            organization_id
        );

-- Copy-on-write index generations keep cited chunks immutable during reindex.
-- The zero generation represents the V41 index without rewriting any row.
alter table knowledge_ingestion_jobs
    add column index_generation uuid not null
        default '00000000-0000-0000-0000-000000000000';

alter table knowledge_document_chunks
    add column index_generation uuid not null
        default '00000000-0000-0000-0000-000000000000',
    drop constraint uq_knowledge_document_chunks_version_ordinal,
    add constraint uq_knowledge_document_chunks_generation_ordinal
        unique (document_version_id, index_generation, ordinal);

create index idx_knowledge_document_chunks_active_generation
    on knowledge_document_chunks (
        organization_id,
        knowledge_base_id,
        document_version_id,
        index_generation,
        ordinal
    );

create table knowledge_answer_passports (
    id uuid primary key,
    organization_id uuid not null,
    knowledge_base_id uuid not null,
    user_id uuid not null,
    chat_id uuid not null,
    chat_turn_id uuid not null,
    retrieval_run_id uuid not null,
    assistant_message_id uuid not null,
    knowledge_mode varchar(32) not null,
    provider varchar(32) not null,
    requested_model varchar(100) not null,
    resolved_model varchar(100) not null,
    embedding_model varchar(128) not null,
    context_sha256 varchar(64) not null,
    answer_sha256 varchar(64) not null,
    evidence_sufficient boolean not null,
    citations_valid boolean not null,
    citation_count integer not null,
    created_at timestamptz not null,

    constraint uq_knowledge_answer_passports_turn
        unique (chat_turn_id),
    constraint uq_knowledge_answer_passports_retrieval
        unique (retrieval_run_id),
    constraint uq_knowledge_answer_passports_assistant
        unique (assistant_message_id),
    constraint uq_knowledge_answer_passports_identity
        unique (id, retrieval_run_id, knowledge_base_id, organization_id),
    constraint fk_knowledge_answer_passports_turn
        foreign key (
            chat_turn_id,
            knowledge_base_id,
            organization_id,
            user_id
        )
        references chat_turns (
            id,
            knowledge_base_id,
            organization_id,
            user_id
        )
        on delete restrict,
    constraint fk_knowledge_answer_passports_retrieval
        foreign key (
            retrieval_run_id,
            knowledge_base_id,
            organization_id,
            user_id
        )
        references knowledge_retrieval_runs (
            id,
            knowledge_base_id,
            organization_id,
            user_id
        )
        on delete restrict,
    constraint fk_knowledge_answer_passports_assistant
        foreign key (assistant_message_id, chat_id, organization_id)
        references chat_messages (id, session_id, organization_id)
        deferrable initially deferred,
    constraint chk_knowledge_answer_passports_mode
        check (knowledge_mode in ('KNOWLEDGE_ASSISTED', 'KNOWLEDGE_ONLY')),
    constraint chk_knowledge_answer_passports_hashes
        check (
            context_sha256 ~ '^[0-9a-f]{64}$'
            and answer_sha256 ~ '^[0-9a-f]{64}$'
        ),
    constraint chk_knowledge_answer_passports_citations
        check (citation_count >= 0)
);

create table knowledge_answer_citations (
    id uuid primary key,
    answer_passport_id uuid not null,
    retrieval_run_id uuid not null,
    organization_id uuid not null,
    knowledge_base_id uuid not null,
    chunk_id uuid not null,
    citation_label varchar(16) not null,
    ordinal integer not null,
    created_at timestamptz not null,

    constraint uq_knowledge_answer_citations_label
        unique (answer_passport_id, citation_label),
    constraint uq_knowledge_answer_citations_chunk
        unique (answer_passport_id, chunk_id),
    constraint fk_knowledge_answer_citations_passport
        foreign key (
            answer_passport_id,
            retrieval_run_id,
            knowledge_base_id,
            organization_id
        )
        references knowledge_answer_passports (
            id,
            retrieval_run_id,
            knowledge_base_id,
            organization_id
        )
        on delete restrict,
    constraint fk_knowledge_answer_citations_hit
        foreign key (
            retrieval_run_id,
            chunk_id,
            knowledge_base_id,
            organization_id
        )
        references knowledge_retrieval_hits (
            retrieval_run_id,
            chunk_id,
            knowledge_base_id,
            organization_id
        )
        on delete restrict,
    constraint chk_knowledge_answer_citations_label
        check (citation_label ~ '^C[1-9][0-9]{0,2}$'),
    constraint chk_knowledge_answer_citations_ordinal
        check (ordinal > 0)
);

create table knowledge_evaluation_runs (
    id uuid primary key,
    organization_id uuid not null,
    knowledge_base_id uuid not null,
    user_id uuid not null,
    dataset_name varchar(255) not null,
    case_count integer not null,
    mean_recall double precision not null,
    mean_reciprocal_rank double precision not null,
    mean_ndcg double precision not null,
    created_at timestamptz not null,
    constraint fk_knowledge_evaluation_runs_kb
        foreign key (knowledge_base_id, organization_id)
        references knowledge_bases (id, organization_id)
        on delete restrict,
    constraint chk_knowledge_evaluation_runs_values
        check (
            case_count > 0
            and mean_recall between 0 and 1
            and mean_reciprocal_rank between 0 and 1
            and mean_ndcg between 0 and 1
        )
);

create table knowledge_evaluation_cases (
    id uuid primary key,
    evaluation_run_id uuid not null
        references knowledge_evaluation_runs(id) on delete cascade,
    retrieval_run_id uuid not null unique
        references knowledge_retrieval_runs(id) on delete restrict,
    ordinal integer not null,
    expected_version_ids varchar(4000) not null,
    recall double precision not null,
    reciprocal_rank double precision not null,
    ndcg double precision not null,
    constraint uq_knowledge_evaluation_cases_ordinal
        unique (evaluation_run_id, ordinal),
    constraint chk_knowledge_evaluation_cases_values
        check (
            ordinal > 0
            and recall between 0 and 1
            and reciprocal_rank between 0 and 1
            and ndcg between 0 and 1
        )
);

create index idx_knowledge_answer_passports_chat
    on knowledge_answer_passports (
        organization_id,
        user_id,
        chat_id,
        created_at desc
    );

create function reject_knowledge_answer_provenance_update()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Knowledge Answer Passport provenance is immutable';
end;
$$;

create trigger trg_knowledge_answer_passports_immutable
before update or delete on knowledge_answer_passports
for each row execute function reject_knowledge_answer_provenance_update();

create trigger trg_knowledge_answer_citations_immutable
before update or delete on knowledge_answer_citations
for each row execute function reject_knowledge_answer_provenance_update();

insert into audit_event_types (name, description)
values
    ('KNOWLEDGE_ANSWER_GENERATED', 'RAG answer and Answer Passport persisted'),
    ('KNOWLEDGE_REINDEX_REQUESTED', 'Knowledge document reindex requested')
on conflict (name) do nothing;

comment on table knowledge_answer_passports is
    'Immutable proof linking one ChatTurn, retrieval run, model response and validated citations.';

comment on table knowledge_answer_citations is
    'Immutable citation links from an Answer Passport to exact ranked chunks.';
