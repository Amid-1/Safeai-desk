/* Safeai-desk/backend/src/main/resources/db/migration/V44__knowledge_production_integrity.sql */
-- SafeAI Desk V44
-- Knowledge production integrity after already-applied V1..V43.
--
-- IMPORTANT:
--   * V1..V43 are immutable and must not be edited.
--   * V40 already closes the ingestion-job -> exact document-version identity gap.
--   * V41 adds durable fenced ingestion + chunks/retrieval.
--   * V42 adds RAG/Answer Passport/evaluation provenance.
--   * V43 adds chat archive metadata.
--
-- This migration strengthens the already-applied model without rewriting its
-- history: future creator/user identity is verified at INSERT time while
-- historical UUID provenance remains valid after a user is permanently deleted.

-- ---------------------------------------------------------------------------
-- 0. Baseline assertions: fail with a clear error if this is applied on the
--    wrong schema head.
-- ---------------------------------------------------------------------------

do $$
begin
    if to_regclass('public.knowledge_answer_passports') is null
       or to_regclass('public.knowledge_answer_citations') is null
       or to_regclass('public.knowledge_evaluation_runs') is null
       or to_regclass('public.knowledge_evaluation_cases') is null then
        raise exception
            'V44 requires the V42 Knowledge RAG/Answer Passport/evaluation schema';
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conrelid = 'public.knowledge_ingestion_jobs'::regclass
          and conname = 'fk_knowledge_ingestion_jobs_version_identity'
    ) then
        raise exception
            'V44 requires V40 fk_knowledge_ingestion_jobs_version_identity';
    end if;

    if not exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'chat_sessions'
          and column_name = 'archived_at'
    ) then
        raise exception
            'V44 requires the V43 chat archive schema';
    end if;
end
$$;

-- ---------------------------------------------------------------------------
-- 1. Audit dictionary. V38..V43 already created most rows; these INSERTs are
--    idempotent and also add events used by the hardened Java layer.
-- ---------------------------------------------------------------------------

insert into public.audit_event_types (name, description)
values
    ('KNOWLEDGE_BASE_CREATED', 'Knowledge base created'),
    ('KNOWLEDGE_BASE_UPDATED', 'Knowledge base updated'),
    ('KNOWLEDGE_BASE_MEMBER_ADDED', 'Knowledge base member added'),
    ('KNOWLEDGE_BASE_MEMBER_UPDATED', 'Knowledge base member access updated'),
    ('KNOWLEDGE_BASE_MEMBER_REMOVED', 'Knowledge base member removed'),
    ('KNOWLEDGE_DOCUMENT_CREATED', 'Knowledge document created'),
    ('KNOWLEDGE_DOCUMENT_UPDATED', 'Knowledge document metadata or enabled state updated'),
    ('KNOWLEDGE_DOCUMENT_VERSION_UPLOADED', 'Knowledge document version uploaded'),
    ('KNOWLEDGE_DOCUMENT_DOWNLOADED', 'Knowledge document downloaded'),
    ('KNOWLEDGE_REINDEX_REQUESTED', 'Knowledge document reindex requested'),
    ('KNOWLEDGE_INGESTION_READY', 'Knowledge document ingestion completed'),
    ('KNOWLEDGE_INGESTION_FAILED', 'Knowledge document ingestion failed'),
    ('KNOWLEDGE_RETRIEVAL_COMPLETED', 'Knowledge hybrid retrieval completed'),
    ('KNOWLEDGE_EVALUATION_COMPLETED', 'Knowledge retrieval evaluation completed'),
    ('KNOWLEDGE_ANSWER_GENERATED', 'RAG answer and Answer Passport persisted'),
    ('CHAT_ARCHIVED', 'Chat session archived by its owner')
on conflict (name) do nothing;

-- ---------------------------------------------------------------------------
-- 2. Insert-time actor/creator validation WITHOUT foreign keys.
--
-- We intentionally do not add creator/user FKs to historical provenance:
-- permanent user deletion must not destroy or invalidate old Knowledge records.
-- New writes, however, must point to a user that belongs to the same tenant.
-- ---------------------------------------------------------------------------

create or replace function public.validate_knowledge_creator_v44()
returns trigger
language plpgsql
as $$
begin
    if not exists (
        select 1
        from public.users app_user
        where app_user.id = new.created_by_user_id
          and app_user.organization_id = new.organization_id
    ) then
        raise exception using
            errcode = '23514',
            message = format(
                '%s creator must exist in organization %s',
                tg_table_name,
                new.organization_id
            );
    end if;

    return new;
end
$$;

drop trigger if exists trg_knowledge_documents_validate_creator_v44
    on public.knowledge_documents;

create trigger trg_knowledge_documents_validate_creator_v44
before insert on public.knowledge_documents
for each row
execute function public.validate_knowledge_creator_v44();

drop trigger if exists trg_knowledge_document_versions_validate_creator_v44
    on public.knowledge_document_versions;

create trigger trg_knowledge_document_versions_validate_creator_v44
before insert on public.knowledge_document_versions
for each row
execute function public.validate_knowledge_creator_v44();

create or replace function public.validate_knowledge_actor_v44()
returns trigger
language plpgsql
as $$
begin
    if not exists (
        select 1
        from public.users app_user
        where app_user.id = new.user_id
          and app_user.organization_id = new.organization_id
    ) then
        raise exception using
            errcode = '23514',
            message = format(
                '%s user must exist in organization %s',
                tg_table_name,
                new.organization_id
            );
    end if;

    return new;
end
$$;

drop trigger if exists trg_knowledge_retrieval_runs_validate_user_v44
    on public.knowledge_retrieval_runs;

create trigger trg_knowledge_retrieval_runs_validate_user_v44
before insert on public.knowledge_retrieval_runs
for each row
execute function public.validate_knowledge_actor_v44();

drop trigger if exists trg_knowledge_evaluation_runs_validate_user_v44
    on public.knowledge_evaluation_runs;

create trigger trg_knowledge_evaluation_runs_validate_user_v44
before insert on public.knowledge_evaluation_runs
for each row
execute function public.validate_knowledge_actor_v44();

-- ---------------------------------------------------------------------------
-- 3. Identity columns that the application treats as immutable.
-- ---------------------------------------------------------------------------

create or replace function public.enforce_knowledge_base_identity_v44()
returns trigger
language plpgsql
as $$
begin
    if new.organization_id is distinct from old.organization_id
       or new.created_by_user_id is distinct from old.created_by_user_id then
        raise exception using
            errcode = '23514',
            message = 'knowledge_bases organization/creator identity is immutable';
    end if;

    return new;
end
$$;

drop trigger if exists trg_knowledge_bases_identity_immutable_v44
    on public.knowledge_bases;

create trigger trg_knowledge_bases_identity_immutable_v44
before update of organization_id, created_by_user_id
on public.knowledge_bases
for each row
execute function public.enforce_knowledge_base_identity_v44();

create or replace function public.enforce_knowledge_document_identity_v44()
returns trigger
language plpgsql
as $$
begin
    if new.organization_id is distinct from old.organization_id
       or new.knowledge_base_id is distinct from old.knowledge_base_id
       or new.created_by_user_id is distinct from old.created_by_user_id then
        raise exception using
            errcode = '23514',
            message = 'knowledge_documents tenant/parent/creator identity is immutable';
    end if;

    return new;
end
$$;

drop trigger if exists trg_knowledge_documents_identity_immutable_v44
    on public.knowledge_documents;

create trigger trg_knowledge_documents_identity_immutable_v44
before update of organization_id, knowledge_base_id, created_by_user_id
on public.knowledge_documents
for each row
execute function public.enforce_knowledge_document_identity_v44();

create or replace function public.enforce_knowledge_membership_identity_v44()
returns trigger
language plpgsql
as $$
begin
    if new.knowledge_base_id is distinct from old.knowledge_base_id
       or new.organization_id is distinct from old.organization_id
       or new.user_id is distinct from old.user_id then
        raise exception using
            errcode = '23514',
            message = 'knowledge_base_memberships resource identity is immutable';
    end if;

    return new;
end
$$;

drop trigger if exists trg_knowledge_memberships_identity_immutable_v44
    on public.knowledge_base_memberships;

create trigger trg_knowledge_memberships_identity_immutable_v44
before update of knowledge_base_id, organization_id, user_id
on public.knowledge_base_memberships
for each row
execute function public.enforce_knowledge_membership_identity_v44();

-- ---------------------------------------------------------------------------
-- 4. Ingestion lifecycle hardening. V40 already supplies the exact
--    job/version/document/KB/org composite FK; do not recreate or rename it.
-- ---------------------------------------------------------------------------

alter table public.knowledge_ingestion_jobs
    add constraint chk_knowledge_ingestion_jobs_ownership_lifecycle_v44
    check (
        (
            status in ('PENDING', 'READY', 'FAILED')
            and claimed_at is null
        )
        or status in ('VALIDATING', 'EXTRACTING', 'CHUNKING')
    ) not valid,
    add constraint chk_knowledge_ingestion_jobs_lease_order_v44
    check (
        lease_until is null
        or (
            claimed_at is not null
            and lease_until > claimed_at
        )
    ) not valid,
    add constraint chk_knowledge_ingestion_jobs_processing_attempt_v44
    check (
        status = 'PENDING'
        or attempt >= 1
    ) not valid,
    add constraint chk_knowledge_ingestion_jobs_time_order_v44
    check (
        (started_at is null or started_at >= created_at)
        and (
            finished_at is null
            or (
                started_at is not null
                and finished_at >= started_at
            )
        )
    ) not valid;

alter table public.knowledge_ingestion_jobs
    validate constraint chk_knowledge_ingestion_jobs_ownership_lifecycle_v44;
alter table public.knowledge_ingestion_jobs
    validate constraint chk_knowledge_ingestion_jobs_lease_order_v44;
alter table public.knowledge_ingestion_jobs
    validate constraint chk_knowledge_ingestion_jobs_processing_attempt_v44;
alter table public.knowledge_ingestion_jobs
    validate constraint chk_knowledge_ingestion_jobs_time_order_v44;

-- ---------------------------------------------------------------------------
-- 5. Retrieval provenance snapshot.
--
-- document.name is mutable. Persisting it on the immutable retrieval hit keeps
-- old citations/context provenance stable after a later document rename.
-- ---------------------------------------------------------------------------

alter table public.knowledge_retrieval_hits
    add column document_name_snapshot varchar(255);

update public.knowledge_retrieval_hits retrieval_hit
set document_name_snapshot = document.name
from public.knowledge_document_chunks chunk
join public.knowledge_documents document
  on document.id = chunk.document_id
 and document.knowledge_base_id = chunk.knowledge_base_id
 and document.organization_id = chunk.organization_id
where chunk.id = retrieval_hit.chunk_id
  and chunk.knowledge_base_id = retrieval_hit.knowledge_base_id
  and chunk.organization_id = retrieval_hit.organization_id
  and retrieval_hit.document_name_snapshot is null;

do $$
begin
    if exists (
        select 1
        from public.knowledge_retrieval_hits
        where document_name_snapshot is null
    ) then
        raise exception
            'V44 cannot backfill knowledge_retrieval_hits.document_name_snapshot';
    end if;
end
$$;

alter table public.knowledge_retrieval_hits
    alter column document_name_snapshot set not null;

alter table public.knowledge_retrieval_hits
    add constraint chk_knowledge_retrieval_hits_document_name_snapshot_v44
    check (
        length(btrim(document_name_snapshot)) between 1 and 255
        and document_name_snapshot !~ '[[:cntrl:]]'
    ) not valid,
    add constraint chk_knowledge_retrieval_hits_components_v44
    check (
        (lexical_rank is not null or semantic_rank is not null)
        and ((lexical_rank is null) = (lexical_score is null))
        and ((semantic_rank is null) = (cosine_similarity is null))
        and fused_score > 0.0
        and fused_score <= 1.0
    ) not valid;

alter table public.knowledge_retrieval_hits
    validate constraint chk_knowledge_retrieval_hits_document_name_snapshot_v44;
alter table public.knowledge_retrieval_hits
    validate constraint chk_knowledge_retrieval_hits_components_v44;

alter table public.knowledge_retrieval_runs
    add constraint chk_knowledge_retrieval_runs_bounds_v44
    check (
        top_k between 1 and 100
        and candidate_limit between top_k and 1000
        and rrf_k between 1 and 1000
        and length(btrim(embedding_model)) between 1 and 128
    ) not valid;

alter table public.knowledge_retrieval_runs
    validate constraint chk_knowledge_retrieval_runs_bounds_v44;

create or replace function public.reject_knowledge_retrieval_provenance_update_v44()
returns trigger
language plpgsql
as $$
begin
    raise exception using
        errcode = '23514',
        message = format('%s rows are immutable provenance', tg_table_name);
end
$$;

drop trigger if exists trg_knowledge_retrieval_runs_immutable_v44
    on public.knowledge_retrieval_runs;

create trigger trg_knowledge_retrieval_runs_immutable_v44
before update on public.knowledge_retrieval_runs
for each row
execute function public.reject_knowledge_retrieval_provenance_update_v44();

drop trigger if exists trg_knowledge_retrieval_hits_immutable_v44
    on public.knowledge_retrieval_hits;

create trigger trg_knowledge_retrieval_hits_immutable_v44
before update on public.knowledge_retrieval_hits
for each row
execute function public.reject_knowledge_retrieval_provenance_update_v44();

-- ---------------------------------------------------------------------------
-- 6. Answer Passport: bind it to the exact chat session, assistant message,
--    knowledge mode and retrieval run.
--
-- V42 already binds tenant/user/KB identities. These compact additional keys
-- close the remaining "same ids independently, but not the same logical row"
-- provenance gap without a very wide redundant index.
-- ---------------------------------------------------------------------------

do $$
begin
    if exists (
        select 1
        from public.knowledge_answer_passports passport
        join public.chat_turns turn
          on turn.id = passport.chat_turn_id
        where turn.session_id is distinct from passport.chat_id
           or turn.assistant_message_id is distinct from passport.assistant_message_id
           or turn.knowledge_mode is distinct from passport.knowledge_mode
           or turn.state is distinct from 'SUCCEEDED'
           or turn.provider is distinct from passport.provider
           or turn.requested_model is distinct from passport.requested_model
           or turn.resolved_model is distinct from passport.resolved_model
    ) then
        raise exception
            'V44 preflight: Answer Passport does not match its exact ChatTurn';
    end if;

    if exists (
        select 1
        from public.knowledge_answer_passports passport
        join public.knowledge_retrieval_runs retrieval
          on retrieval.id = passport.retrieval_run_id
        where retrieval.chat_turn_id is distinct from passport.chat_turn_id
           or retrieval.embedding_model is distinct from passport.embedding_model
    ) then
        raise exception
            'V44 preflight: Answer Passport does not match its retrieval run';
    end if;

    if exists (
        select 1
        from public.knowledge_answer_citations citation
        where citation.ordinal < 1
           or citation.ordinal > 999
           or citation.citation_label <> ('C' || citation.ordinal::text)
    ) then
        raise exception
            'V44 preflight: Answer citation label/ordinal mismatch';
    end if;

    if exists (
        select 1
        from public.knowledge_answer_passports passport
        where passport.citation_count <> (
            select count(*)
            from public.knowledge_answer_citations citation
            where citation.answer_passport_id = passport.id
        )
           or (
               passport.evidence_sufficient
               and (
                   not passport.citations_valid
                   or passport.citation_count = 0
               )
           )
    ) then
        raise exception
            'V44 preflight: Answer Passport citation_count/evidence invariant violated';
    end if;
end
$$;

create unique index ux_chat_turns_answer_passport_link_v44
    on public.chat_turns (
        id,
        session_id,
        assistant_message_id,
        knowledge_mode
    );

create unique index ux_knowledge_retrieval_runs_turn_embedding_v44
    on public.knowledge_retrieval_runs (
        id,
        chat_turn_id,
        embedding_model
    );

alter table public.knowledge_answer_passports
    add constraint fk_knowledge_answer_passports_turn_link_v44
        foreign key (
            chat_turn_id,
            chat_id,
            assistant_message_id,
            knowledge_mode
        )
        references public.chat_turns (
            id,
            session_id,
            assistant_message_id,
            knowledge_mode
        )
        on delete restrict
        deferrable initially deferred
        not valid,
    add constraint fk_knowledge_answer_passports_retrieval_link_v44
        foreign key (
            retrieval_run_id,
            chat_turn_id,
            embedding_model
        )
        references public.knowledge_retrieval_runs (
            id,
            chat_turn_id,
            embedding_model
        )
        on delete restrict
        deferrable initially deferred
        not valid,
    add constraint chk_knowledge_answer_passports_semantics_v44
        check (
            citation_count between 0 and 999
            and length(btrim(provider)) between 1 and 32
            and length(btrim(requested_model)) between 1 and 100
            and length(btrim(resolved_model)) between 1 and 100
            and length(btrim(embedding_model)) between 1 and 128
            and (
                not evidence_sufficient
                or (
                    citations_valid
                    and citation_count > 0
                )
            )
        ) not valid;

alter table public.knowledge_answer_passports
    validate constraint fk_knowledge_answer_passports_turn_link_v44;
alter table public.knowledge_answer_passports
    validate constraint fk_knowledge_answer_passports_retrieval_link_v44;
alter table public.knowledge_answer_passports
    validate constraint chk_knowledge_answer_passports_semantics_v44;

alter table public.knowledge_answer_citations
    add constraint chk_knowledge_answer_citations_label_ordinal_v44
        check (
            ordinal between 1 and 999
            and citation_label = ('C' || ordinal::text)
        ) not valid;

alter table public.knowledge_answer_citations
    validate constraint chk_knowledge_answer_citations_label_ordinal_v44;

create or replace function public.validate_knowledge_answer_passport_v44()
returns trigger
language plpgsql
as $$
declare
    turn_row record;
    retrieval_row record;
    actual_citation_count integer;
begin
    select
        turn.state,
        turn.session_id,
        turn.assistant_message_id,
        turn.knowledge_mode,
        turn.organization_id,
        turn.user_id,
        turn.knowledge_base_id,
        turn.provider,
        turn.requested_model,
        turn.resolved_model
    into turn_row
    from public.chat_turns turn
    where turn.id = new.chat_turn_id;

    if not found then
        raise exception
            'Answer Passport ChatTurn does not exist: %',
            new.chat_turn_id;
    end if;

    if turn_row.state is distinct from 'SUCCEEDED'
       or turn_row.session_id is distinct from new.chat_id
       or turn_row.assistant_message_id is distinct from new.assistant_message_id
       or turn_row.knowledge_mode is distinct from new.knowledge_mode
       or turn_row.organization_id is distinct from new.organization_id
       or turn_row.user_id is distinct from new.user_id
       or turn_row.knowledge_base_id is distinct from new.knowledge_base_id
       or turn_row.provider is distinct from new.provider
       or turn_row.requested_model is distinct from new.requested_model
       or turn_row.resolved_model is distinct from new.resolved_model then
        raise exception
            'Answer Passport does not match the exact terminal ChatTurn';
    end if;

    select
        retrieval.chat_turn_id,
        retrieval.organization_id,
        retrieval.user_id,
        retrieval.knowledge_base_id,
        retrieval.embedding_model
    into retrieval_row
    from public.knowledge_retrieval_runs retrieval
    where retrieval.id = new.retrieval_run_id;

    if not found then
        raise exception
            'Answer Passport retrieval run does not exist: %',
            new.retrieval_run_id;
    end if;

    if retrieval_row.chat_turn_id is distinct from new.chat_turn_id
       or retrieval_row.organization_id is distinct from new.organization_id
       or retrieval_row.user_id is distinct from new.user_id
       or retrieval_row.knowledge_base_id is distinct from new.knowledge_base_id
       or retrieval_row.embedding_model is distinct from new.embedding_model then
        raise exception
            'Answer Passport does not match the exact retrieval run';
    end if;

    select count(*)
    into actual_citation_count
    from public.knowledge_answer_citations citation
    where citation.answer_passport_id = new.id;

    if actual_citation_count <> new.citation_count then
        raise exception
            'Answer Passport citation_count mismatch: expected %, actual %',
            new.citation_count,
            actual_citation_count;
    end if;

    return null;
end
$$;

drop trigger if exists ctrg_knowledge_answer_passport_semantics_v44
    on public.knowledge_answer_passports;

create constraint trigger ctrg_knowledge_answer_passport_semantics_v44
after insert on public.knowledge_answer_passports
deferrable initially deferred
for each row
execute function public.validate_knowledge_answer_passport_v44();

create or replace function public.validate_knowledge_answer_citation_count_v44()
returns trigger
language plpgsql
as $$
declare
    expected_count integer;
    actual_count integer;
begin
    select passport.citation_count
    into expected_count
    from public.knowledge_answer_passports passport
    where passport.id = new.answer_passport_id;

    if not found then
        return null;
    end if;

    select count(*)
    into actual_count
    from public.knowledge_answer_citations citation
    where citation.answer_passport_id = new.answer_passport_id;

    if actual_count <> expected_count then
        raise exception
            'Answer Passport citation_count mismatch after citation insert: expected %, actual %',
            expected_count,
            actual_count;
    end if;

    return null;
end
$$;

drop trigger if exists ctrg_knowledge_answer_citation_count_v44
    on public.knowledge_answer_citations;

create constraint trigger ctrg_knowledge_answer_citation_count_v44
after insert on public.knowledge_answer_citations
deferrable initially deferred
for each row
execute function public.validate_knowledge_answer_citation_count_v44();

-- ---------------------------------------------------------------------------
-- 7. Evaluation provenance. Each case now carries the tenant/KB/user identity
--    of its evaluation run and exact retrieval run.
-- ---------------------------------------------------------------------------

alter table public.knowledge_evaluation_cases
    add column organization_id uuid,
    add column knowledge_base_id uuid,
    add column user_id uuid;

update public.knowledge_evaluation_cases evaluation_case
set organization_id = evaluation_run.organization_id,
    knowledge_base_id = evaluation_run.knowledge_base_id,
    user_id = evaluation_run.user_id
from public.knowledge_evaluation_runs evaluation_run
where evaluation_run.id = evaluation_case.evaluation_run_id;

do $$
begin
    if exists (
        select 1
        from public.knowledge_evaluation_cases
        where organization_id is null
           or knowledge_base_id is null
           or user_id is null
    ) then
        raise exception
            'V44 cannot backfill Knowledge evaluation case identity';
    end if;
end
$$;

alter table public.knowledge_evaluation_cases
    alter column organization_id set not null,
    alter column knowledge_base_id set not null,
    alter column user_id set not null;

create unique index ux_knowledge_evaluation_runs_identity_v44
    on public.knowledge_evaluation_runs (
        id,
        knowledge_base_id,
        organization_id,
        user_id
    );

do $$
begin
    if exists (
        select 1
        from public.knowledge_evaluation_cases evaluation_case
        join public.knowledge_retrieval_runs retrieval
          on retrieval.id = evaluation_case.retrieval_run_id
        where retrieval.organization_id is distinct from evaluation_case.organization_id
           or retrieval.knowledge_base_id is distinct from evaluation_case.knowledge_base_id
           or retrieval.user_id is distinct from evaluation_case.user_id
           or retrieval.chat_turn_id is not null
    ) then
        raise exception
            'V44 preflight: Knowledge evaluation case references an incompatible retrieval run';
    end if;

    if exists (
        select 1
        from public.knowledge_evaluation_cases evaluation_case
        where evaluation_case.expected_version_ids !~
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(,[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})*$'
    ) then
        raise exception
            'V44 preflight: malformed evaluation expected_version_ids';
    end if;

    if exists (
        select 1
        from public.knowledge_evaluation_runs evaluation_run
        where evaluation_run.case_count <> (
            select count(*)
            from public.knowledge_evaluation_cases evaluation_case
            where evaluation_case.evaluation_run_id = evaluation_run.id
        )
    ) then
        raise exception
            'V44 preflight: Knowledge evaluation case_count mismatch';
    end if;
end
$$;

alter table public.knowledge_evaluation_cases
    add constraint fk_knowledge_evaluation_cases_run_identity_v44
        foreign key (
            evaluation_run_id,
            knowledge_base_id,
            organization_id,
            user_id
        )
        references public.knowledge_evaluation_runs (
            id,
            knowledge_base_id,
            organization_id,
            user_id
        )
        on delete cascade
        not valid,
    add constraint fk_knowledge_evaluation_cases_retrieval_identity_v44
        foreign key (
            retrieval_run_id,
            knowledge_base_id,
            organization_id,
            user_id
        )
        references public.knowledge_retrieval_runs (
            id,
            knowledge_base_id,
            organization_id,
            user_id
        )
        on delete restrict
        not valid,
    add constraint chk_knowledge_evaluation_cases_expected_ids_v44
        check (
            expected_version_ids ~
                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(,[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})*$'
        ) not valid;

alter table public.knowledge_evaluation_cases
    validate constraint fk_knowledge_evaluation_cases_run_identity_v44;
alter table public.knowledge_evaluation_cases
    validate constraint fk_knowledge_evaluation_cases_retrieval_identity_v44;
alter table public.knowledge_evaluation_cases
    validate constraint chk_knowledge_evaluation_cases_expected_ids_v44;

create or replace function public.validate_knowledge_evaluation_run_v44()
returns trigger
language plpgsql
as $$
declare
    actual_count integer;
begin
    select count(*)
    into actual_count
    from public.knowledge_evaluation_cases evaluation_case
    where evaluation_case.evaluation_run_id = new.id;

    if actual_count <> new.case_count then
        raise exception
            'Knowledge evaluation case_count mismatch: expected %, actual %',
            new.case_count,
            actual_count;
    end if;

    return null;
end
$$;

drop trigger if exists ctrg_knowledge_evaluation_run_count_v44
    on public.knowledge_evaluation_runs;

create constraint trigger ctrg_knowledge_evaluation_run_count_v44
after insert on public.knowledge_evaluation_runs
deferrable initially deferred
for each row
execute function public.validate_knowledge_evaluation_run_v44();

create or replace function public.validate_knowledge_evaluation_case_v44()
returns trigger
language plpgsql
as $$
declare
    checked_evaluation_run_id uuid;
    retrieval_chat_turn_id uuid;
    expected_count integer;
    actual_count integer;
begin
    if tg_op = 'DELETE' then
        checked_evaluation_run_id := old.evaluation_run_id;

        -- A cascade caused by deleting the parent evaluation run is allowed.
        select evaluation_run.case_count
        into expected_count
        from public.knowledge_evaluation_runs evaluation_run
        where evaluation_run.id = checked_evaluation_run_id;

        if not found then
            return null;
        end if;
    else
        checked_evaluation_run_id := new.evaluation_run_id;

        select retrieval.chat_turn_id
        into retrieval_chat_turn_id
        from public.knowledge_retrieval_runs retrieval
        where retrieval.id = new.retrieval_run_id
          and retrieval.knowledge_base_id = new.knowledge_base_id
          and retrieval.organization_id = new.organization_id
          and retrieval.user_id = new.user_id;

        if not found then
            raise exception
                'Knowledge evaluation retrieval identity does not exist';
        end if;

        if retrieval_chat_turn_id is not null then
            raise exception
                'Knowledge evaluation must use a standalone retrieval run';
        end if;

        select evaluation_run.case_count
        into expected_count
        from public.knowledge_evaluation_runs evaluation_run
        where evaluation_run.id = checked_evaluation_run_id;

        if not found then
            return null;
        end if;
    end if;

    select count(*)
    into actual_count
    from public.knowledge_evaluation_cases evaluation_case
    where evaluation_case.evaluation_run_id = checked_evaluation_run_id;

    if actual_count <> expected_count then
        raise exception
            'Knowledge evaluation case_count mismatch: expected %, actual %',
            expected_count,
            actual_count;
    end if;

    return null;
end
$$;

drop trigger if exists ctrg_knowledge_evaluation_case_v44
    on public.knowledge_evaluation_cases;

create constraint trigger ctrg_knowledge_evaluation_case_v44
after insert or delete on public.knowledge_evaluation_cases
deferrable initially deferred
for each row
execute function public.validate_knowledge_evaluation_case_v44();

create or replace function public.reject_knowledge_evaluation_update_v44()
returns trigger
language plpgsql
as $$
begin
    raise exception using
        errcode = '23514',
        message = format('%s rows are immutable evaluation provenance', tg_table_name);
end
$$;

drop trigger if exists trg_knowledge_evaluation_runs_immutable_v44
    on public.knowledge_evaluation_runs;

create trigger trg_knowledge_evaluation_runs_immutable_v44
before update on public.knowledge_evaluation_runs
for each row
execute function public.reject_knowledge_evaluation_update_v44();

drop trigger if exists trg_knowledge_evaluation_cases_immutable_v44
    on public.knowledge_evaluation_cases;

create trigger trg_knowledge_evaluation_cases_immutable_v44
before update on public.knowledge_evaluation_cases
for each row
execute function public.reject_knowledge_evaluation_update_v44();

-- ---------------------------------------------------------------------------
-- 8. Documentation comments for the final semantics.
-- ---------------------------------------------------------------------------

comment on column public.knowledge_retrieval_hits.document_name_snapshot is
    'Immutable document display-name snapshot captured at retrieval time; protects historical citation/context provenance across later document renames.';

comment on column public.knowledge_bases.created_by_user_id is
    'Historical creator UUID. No FK by design; INSERT is tenant-validated and provenance survives user deletion.';

comment on column public.knowledge_documents.created_by_user_id is
    'Historical creator UUID. No FK by design; INSERT is tenant-validated and provenance survives user deletion.';

comment on column public.knowledge_document_versions.created_by_user_id is
    'Historical uploader UUID. No FK by design; INSERT is tenant-validated and provenance survives user deletion.';

comment on table public.knowledge_answer_passports is
    'Immutable RAG provenance bound to one exact SUCCEEDED ChatTurn, retrieval run, assistant response and validated citation set.';

comment on table public.knowledge_evaluation_cases is
    'Immutable evaluation case provenance with explicit tenant/KB/user scope and a standalone retrieval run.';
