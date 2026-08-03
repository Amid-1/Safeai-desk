/* Safeai-desk/backend/src/main/resources/db/migration/V32__chat_turn_state_machine.sql */
-- Persistent chat-turn state machine, DB fencing and quota reservations.
-- Baseline: V31 from usage analytics patch.

-- ---------------------------------------------------------------------------
-- 1. Message metadata required by the production AI contract.
-- ---------------------------------------------------------------------------

alter table chat_messages
    add column if not exists requested_model varchar(100),
    add column if not exists provider_request_id varchar(255);

alter table chat_messages
    alter column cost_usd type numeric(30, 12);

comment on column chat_messages.requested_model is
    'Configured/requested model before provider-side resolution.';
comment on column chat_messages.model is
    'Resolved model actually reported/used by the provider.';
comment on column chat_messages.provider_request_id is
    'Provider HTTP/request correlation identifier; not an idempotency key.';

-- Existing V22 deployments already have this index. IF NOT EXISTS keeps the
-- invariant explicit for installations assembled from older branches.
create unique index if not exists ux_chat_messages_single_reply
    on chat_messages (reply_to_message_id)
    where reply_to_message_id is not null;

-- ---------------------------------------------------------------------------
-- 2. Persistent turn state. Redis is no longer the source of correctness.
-- ---------------------------------------------------------------------------

create table chat_turns (
    id uuid primary key,

    session_id uuid not null
        references chat_sessions(id) on delete cascade,
    organization_id uuid not null
        references organizations(id),
    user_id uuid not null
        references users(id),

    client_request_id uuid not null,
    request_content_hash varchar(64) not null,
    provider_operation_id uuid not null,

    user_message_id uuid not null,
    assistant_message_id uuid,

    state varchar(32) not null,
    processing_token uuid,
    lease_until timestamptz,
    provider_call_started_at timestamptz,

    provider varchar(32),
    requested_model varchar(100),
    resolved_model varchar(100),
    provider_request_id varchar(255),
    provider_error_type varchar(64),
    failure_code varchar(64),
    outcome_ambiguous boolean not null default false,

    created_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz,
    version bigint not null default 0,

    constraint uq_chat_turns_session_client_request
        unique (session_id, client_request_id),
    constraint uq_chat_turns_provider_operation
        unique (provider_operation_id),
    constraint uq_chat_turns_user_message
        unique (user_message_id),
    constraint uq_chat_turns_assistant_message
        unique (assistant_message_id),

    constraint fk_chat_turns_user_message
        foreign key (user_message_id)
        references chat_messages(id)
        deferrable initially immediate,
    constraint fk_chat_turns_assistant_message
        foreign key (assistant_message_id)
        references chat_messages(id)
        deferrable initially deferred,

    constraint chk_chat_turns_state
        check (state in (
            'NEW',
            'PROCESSING',
            'SUCCEEDED',
            'FAILED',
            'AMBIGUOUS'
        )),
    constraint chk_chat_turns_request_hash
        check (request_content_hash ~ '^[0-9a-f]{64}$'),
    constraint chk_chat_turns_version_non_negative
        check (version >= 0),
    constraint chk_chat_turns_timestamps
        check (
            updated_at >= created_at
            and (completed_at is null or completed_at >= created_at)
            and (provider_call_started_at is null
                or provider_call_started_at >= created_at)
            and (completed_at is null
                or provider_call_started_at is null
                or provider_call_started_at <= completed_at)
        ),
    constraint chk_chat_turns_state_metadata
        check (
            (
                state = 'NEW'
                and processing_token is null
                and lease_until is null
                and provider_call_started_at is null
                and assistant_message_id is null
                and completed_at is null
                and failure_code is null
                and outcome_ambiguous = false
            )
            or
            (
                state = 'PROCESSING'
                and processing_token is not null
                and lease_until is not null
                and provider is not null
                and length(trim(provider)) > 0
                and assistant_message_id is null
                and completed_at is null
                and failure_code is null
                and outcome_ambiguous = false
            )
            or
            (
                state = 'SUCCEEDED'
                and processing_token is null
                and lease_until is null
                and assistant_message_id is not null
                and provider_call_started_at is not null
                and provider is not null
                and requested_model is not null
                and resolved_model is not null
                and completed_at is not null
                and provider_error_type is null
                and failure_code is null
                and outcome_ambiguous = false
            )
            or
            (
                state = 'FAILED'
                and processing_token is null
                and lease_until is null
                and assistant_message_id is null
                and completed_at is not null
                and failure_code is not null
                and outcome_ambiguous = false
            )
            or
            (
                state = 'AMBIGUOUS'
                and processing_token is null
                and lease_until is null
                and assistant_message_id is null
                and provider_call_started_at is not null
                and completed_at is not null
                and failure_code is not null
                and outcome_ambiguous = true
            )
        ),
    constraint chk_chat_turns_processing_lease
        check (
            state <> 'PROCESSING'
            or lease_until > updated_at
        ),
    constraint chk_chat_turns_provider_not_blank
        check (provider is null or length(trim(provider)) > 0),
    constraint chk_chat_turns_failure_code_not_blank
        check (failure_code is null or length(trim(failure_code)) > 0)
);

-- One provider operation per chat at a time, even if Redis expires or a JVM
-- pauses longer than its lock TTL.
create unique index ux_chat_turns_one_processing_per_session
    on chat_turns (session_id)
    where state = 'PROCESSING';

comment on table chat_turns is
    'Persistent idempotency, lease, fencing and provider-outcome state for one logical chat turn.';
comment on column chat_turns.provider_operation_id is
    'Stable internal operation ID created and committed before provider I/O.';
comment on column chat_turns.processing_token is
    'DB fencing token. Terminal updates must match this token and PROCESSING state.';
comment on column chat_turns.lease_until is
    'Persistent processing lease; expired turns are reconciled and never auto-retried.';
comment on column chat_turns.provider_call_started_at is
    'Committed immediately before provider I/O; distinguishes safe pre-call crashes from ambiguous outcomes.';

-- ---------------------------------------------------------------------------
-- 3. Deferred cross-table invariants. Deferred validation allows the fenced
-- SUCCEEDED transition to reference a generated assistant_message_id before
-- the assistant row is inserted in the same transaction.
-- ---------------------------------------------------------------------------

create or replace function validate_chat_turn_links()
returns trigger
language plpgsql
as $$
declare
    session_organization_id uuid;
    session_user_id uuid;

    user_message_session_id uuid;
    user_message_organization_id uuid;
    user_message_role varchar(50);
    user_message_client_request_id uuid;

    assistant_message_session_id uuid;
    assistant_message_organization_id uuid;
    assistant_message_role varchar(50);
    assistant_message_status varchar(50);
    assistant_message_reply_to_id uuid;
begin
    select
        chat_session.organization_id,
        chat_session.user_id
    into
        session_organization_id,
        session_user_id
    from chat_sessions chat_session
    where chat_session.id = new.session_id;

    if not found then
        raise exception
            'chat_turn session does not exist: %',
            new.session_id;
    end if;

    if session_organization_id is distinct from new.organization_id
            or session_user_id is distinct from new.user_id then
        raise exception
            'chat_turn tenant/user does not match chat_session';
    end if;

    select
        user_message.session_id,
        user_message.organization_id,
        user_message.role,
        user_message.client_request_id
    into
        user_message_session_id,
        user_message_organization_id,
        user_message_role,
        user_message_client_request_id
    from chat_messages user_message
    where user_message.id = new.user_message_id;

    if not found then
        raise exception
            'chat_turn user_message does not exist: %',
            new.user_message_id;
    end if;

    if user_message_session_id is distinct from new.session_id
            or user_message_organization_id is distinct from new.organization_id
            or user_message_role is distinct from 'USER'
            or user_message_client_request_id is distinct from new.client_request_id then
        raise exception
            'chat_turn user_message link is invalid';
    end if;

    if new.assistant_message_id is not null then
        select
            assistant_message.session_id,
            assistant_message.organization_id,
            assistant_message.role,
            assistant_message.status,
            assistant_message.reply_to_message_id
        into
            assistant_message_session_id,
            assistant_message_organization_id,
            assistant_message_role,
            assistant_message_status,
            assistant_message_reply_to_id
        from chat_messages assistant_message
        where assistant_message.id = new.assistant_message_id;

        if not found then
            raise exception
                'chat_turn assistant_message does not exist: %',
                new.assistant_message_id;
        end if;

        if assistant_message_session_id is distinct from new.session_id
                or assistant_message_organization_id is distinct from new.organization_id
                or assistant_message_role is distinct from 'ASSISTANT'
                or assistant_message_status is distinct from 'COMPLETED'
                or assistant_message_reply_to_id is distinct from new.user_message_id then
            raise exception
                'chat_turn assistant_message link is invalid';
        end if;
    end if;

    return new;
end;
$$;

create constraint trigger ctrg_chat_turns_validate_links
    after insert or update on chat_turns
    deferrable initially deferred
    for each row
    execute function validate_chat_turn_links();

create or replace function validate_chat_message_reply_v32()
returns trigger
language plpgsql
as $$
declare
    target_session_id uuid;
    target_organization_id uuid;
    target_role varchar(50);
begin
    if new.reply_to_message_id is null then
        return new;
    end if;

    select
        target_message.session_id,
        target_message.organization_id,
        target_message.role
    into
        target_session_id,
        target_organization_id,
        target_role
    from chat_messages target_message
    where target_message.id = new.reply_to_message_id;

    if not found then
        raise exception
            'assistant reply target does not exist: %',
            new.reply_to_message_id;
    end if;

    if new.role is distinct from 'ASSISTANT'
            or target_role is distinct from 'USER'
            or target_session_id is distinct from new.session_id
            or target_organization_id is distinct from new.organization_id then
        raise exception
            'assistant reply target must be USER in the same session and tenant';
    end if;

    return new;
end;
$$;

create constraint trigger ctrg_chat_messages_reply_target_v32
    after insert or update of reply_to_message_id, session_id,
        organization_id, role
    on chat_messages
    deferrable initially deferred
    for each row
    execute function validate_chat_message_reply_v32();

-- ---------------------------------------------------------------------------
-- 4. Backfill existing linear chat history into terminal chat turns.
-- Existing USER messages without an idempotency key receive their own message
-- UUID as a stable legacy key. The content hash uses normalized line endings,
-- matching ChatContentNormalizer. Orphan/unfinished legacy requests are marked
-- AMBIGUOUS conservatively and are never auto-retried.
-- ---------------------------------------------------------------------------

do $$
begin
    if exists (
        select 1
        from chat_messages legacy_user
        join chat_messages existing_key
          on existing_key.session_id = legacy_user.session_id
         and existing_key.role = 'USER'
         and existing_key.client_request_id = legacy_user.id
         and existing_key.id <> legacy_user.id
        where legacy_user.role = 'USER'
          and legacy_user.client_request_id is null
    ) then
        raise exception
            'Cannot derive legacy client_request_id from message id: collision detected';
    end if;
end;
$$;

update chat_messages
   set client_request_id = id
 where role = 'USER'
   and client_request_id is null;

update chat_messages
   set requested_model = model
 where role = 'ASSISTANT'
   and status = 'COMPLETED'
   and requested_model is null
   and model is not null;

insert into chat_turns (
    id,
    session_id,
    organization_id,
    user_id,
    client_request_id,
    request_content_hash,
    provider_operation_id,
    user_message_id,
    assistant_message_id,
    state,
    processing_token,
    lease_until,
    provider_call_started_at,
    provider,
    requested_model,
    resolved_model,
    provider_request_id,
    provider_error_type,
    failure_code,
    outcome_ambiguous,
    created_at,
    updated_at,
    completed_at,
    version
)
select
    user_message.id,
    user_message.session_id,
    user_message.organization_id,
    chat_session.user_id,
    user_message.client_request_id,
    encode(
        sha256(
            convert_to(
                replace(
                    replace(user_message.content, E'\r\n', E'\n'),
                    E'\r',
                    E'\n'
                ),
                'UTF8'
            )
        ),
        'hex'
    ),
    user_message.id,
    user_message.id,
    assistant_message.id,
    'SUCCEEDED',
    null,
    null,
    greatest(
        assistant_message.created_at,
        user_message.created_at
    ),
    'legacy',
    case
        when nullif(trim(assistant_message.requested_model), '') is not null
            then trim(assistant_message.requested_model)
        when nullif(trim(assistant_message.model), '') is not null
            then trim(assistant_message.model)
        else 'legacy'
    end,
    case
        when nullif(trim(assistant_message.model), '') is not null
            then trim(assistant_message.model)
        when nullif(trim(assistant_message.requested_model), '') is not null
            then trim(assistant_message.requested_model)
        else 'legacy'
    end,
    assistant_message.provider_request_id,
    null,
    null,
    false,
    user_message.created_at,
    greatest(
        assistant_message.created_at,
        user_message.created_at
    ),
    greatest(
        assistant_message.created_at,
        user_message.created_at
    ),
    0
from chat_messages user_message
join chat_sessions chat_session
  on chat_session.id = user_message.session_id
 and chat_session.organization_id = user_message.organization_id
join chat_messages assistant_message
  on assistant_message.reply_to_message_id = user_message.id
 and assistant_message.session_id = user_message.session_id
 and assistant_message.organization_id = user_message.organization_id
 and assistant_message.role = 'ASSISTANT'
 and assistant_message.status = 'COMPLETED'
where user_message.role = 'USER'
  and user_message.status = 'COMPLETED';

-- Legacy turns with a persisted non-completed ASSISTANT reply are terminal
-- failures. The failed message is not linked as assistant_message_id because
-- chat_turns.assistant_message_id is reserved for a successful persisted reply.
insert into chat_turns (
    id,
    session_id,
    organization_id,
    user_id,
    client_request_id,
    request_content_hash,
    provider_operation_id,
    user_message_id,
    assistant_message_id,
    state,
    processing_token,
    lease_until,
    provider_call_started_at,
    provider,
    requested_model,
    resolved_model,
    provider_request_id,
    provider_error_type,
    failure_code,
    outcome_ambiguous,
    created_at,
    updated_at,
    completed_at,
    version
)
select
    user_message.id,
    user_message.session_id,
    user_message.organization_id,
    chat_session.user_id,
    user_message.client_request_id,
    encode(
        sha256(
            convert_to(
                replace(
                    replace(user_message.content, E'\r\n', E'\n'),
                    E'\r',
                    E'\n'
                ),
                'UTF8'
            )
        ),
        'hex'
    ),
    user_message.id,
    user_message.id,
    null,
    'FAILED',
    null,
    null,
    greatest(
        assistant_message.created_at,
        user_message.created_at
    ),
    'legacy',
    null,
    null,
    assistant_message.provider_request_id,
    'LEGACY_PROVIDER_FAILURE',
    'LEGACY_PROVIDER_FAILURE',
    false,
    user_message.created_at,
    greatest(
        assistant_message.created_at,
        user_message.created_at
    ),
    greatest(
        assistant_message.created_at,
        user_message.created_at
    ),
    0
from chat_messages user_message
join chat_sessions chat_session
  on chat_session.id = user_message.session_id
 and chat_session.organization_id = user_message.organization_id
join chat_messages assistant_message
  on assistant_message.reply_to_message_id = user_message.id
 and assistant_message.session_id = user_message.session_id
 and assistant_message.organization_id = user_message.organization_id
 and assistant_message.role = 'ASSISTANT'
 and assistant_message.status <> 'COMPLETED'
where user_message.role = 'USER'
  and user_message.status = 'COMPLETED';

-- A completed legacy USER message without any ASSISTANT reply has an unknown
-- provider outcome. It is backfilled as AMBIGUOUS and is never retried
-- automatically.
insert into chat_turns (
    id,
    session_id,
    organization_id,
    user_id,
    client_request_id,
    request_content_hash,
    provider_operation_id,
    user_message_id,
    assistant_message_id,
    state,
    processing_token,
    lease_until,
    provider_call_started_at,
    provider,
    requested_model,
    resolved_model,
    provider_request_id,
    provider_error_type,
    failure_code,
    outcome_ambiguous,
    created_at,
    updated_at,
    completed_at,
    version
)
select
    user_message.id,
    user_message.session_id,
    user_message.organization_id,
    chat_session.user_id,
    user_message.client_request_id,
    encode(
        sha256(
            convert_to(
                replace(
                    replace(user_message.content, E'\r\n', E'\n'),
                    E'\r',
                    E'\n'
                ),
                'UTF8'
            )
        ),
        'hex'
    ),
    user_message.id,
    user_message.id,
    null,
    'AMBIGUOUS',
    null,
    null,
    user_message.created_at,
    'legacy',
    null,
    null,
    null,
    'LEGACY_OUTCOME_UNKNOWN',
    'LEGACY_ORPHAN_USER_MESSAGE',
    true,
    user_message.created_at,
    user_message.created_at,
    user_message.created_at,
    0
from chat_messages user_message
join chat_sessions chat_session
  on chat_session.id = user_message.session_id
 and chat_session.organization_id = user_message.organization_id
where user_message.role = 'USER'
  and user_message.status = 'COMPLETED'
  and not exists (
      select 1
      from chat_messages assistant_message
      where assistant_message.reply_to_message_id = user_message.id
        and assistant_message.session_id = user_message.session_id
        and assistant_message.organization_id = user_message.organization_id
        and assistant_message.role = 'ASSISTANT'
  );

-- ---------------------------------------------------------------------------
-- 5. Quota policy columns and durable reservation/settlement ledger.
-- Null limit means unlimited; enabled=false means policy disabled.
-- ---------------------------------------------------------------------------

alter table organization_ai_quotas
    add column if not exists monthly_request_limit bigint,
    add column if not exists monthly_input_token_limit bigint,
    add column if not exists monthly_output_token_limit bigint;

alter table organization_ai_quotas
    alter column monthly_cost_limit_usd
        type numeric(30, 12)
        using monthly_cost_limit_usd::numeric(30, 12);

alter table user_ai_quotas
    add column if not exists monthly_request_limit bigint,
    add column if not exists monthly_input_token_limit bigint,
    add column if not exists monthly_output_token_limit bigint;

alter table user_ai_quotas
    alter column monthly_cost_limit_usd
        type numeric(30, 12)
        using monthly_cost_limit_usd::numeric(30, 12);

alter table organization_ai_quotas
    add constraint chk_org_ai_quota_limits_non_negative
        check (
            (monthly_request_limit is null or monthly_request_limit >= 0)
            and (monthly_input_token_limit is null or monthly_input_token_limit >= 0)
            and (monthly_output_token_limit is null or monthly_output_token_limit >= 0)
            and (monthly_cost_limit_usd is null or monthly_cost_limit_usd >= 0)
        ) not valid;

alter table user_ai_quotas
    add constraint chk_user_ai_quota_limits_non_negative
        check (
            (monthly_request_limit is null or monthly_request_limit >= 0)
            and (monthly_input_token_limit is null or monthly_input_token_limit >= 0)
            and (monthly_output_token_limit is null or monthly_output_token_limit >= 0)
            and (monthly_cost_limit_usd is null or monthly_cost_limit_usd >= 0)
        ) not valid;

create table chat_quota_reservations (
    turn_id uuid primary key
        references chat_turns(id) on delete cascade,
    organization_id uuid not null
        references organizations(id),
    user_id uuid not null
        references users(id),
    period_start date not null,
    state varchar(32) not null,

    reserved_input_tokens bigint not null,
    reserved_output_tokens bigint not null,
    reserved_cost_usd numeric(30, 12) not null,

    actual_input_tokens bigint,
    actual_output_tokens bigint,
    actual_cost_usd numeric(30, 12),
    usage_status varchar(32),
    pricing_status varchar(32),

    created_at timestamptz not null,
    updated_at timestamptz not null,
    settled_at timestamptz,

    constraint chk_chat_quota_reservation_state
        check (state in (
            'RESERVED',
            'SETTLED',
            'UNPRICED',
            'AMBIGUOUS',
            'RELEASED'
        )),
    constraint chk_chat_quota_reserved_non_negative
        check (
            reserved_input_tokens >= 0
            and reserved_output_tokens >= 0
            and reserved_cost_usd >= 0
        ),
    constraint chk_chat_quota_actual_non_negative
        check (
            (actual_input_tokens is null or actual_input_tokens >= 0)
            and (actual_output_tokens is null or actual_output_tokens >= 0)
            and (actual_cost_usd is null or actual_cost_usd >= 0)
        ),
    constraint chk_chat_quota_timestamps
        check (
            updated_at >= created_at
            and (settled_at is null or settled_at >= created_at)
        ),
    constraint chk_chat_quota_settlement
        check (
            (state = 'RESERVED' and settled_at is null)
            or (state <> 'RESERVED' and settled_at is not null)
        ),
    constraint chk_chat_quota_usage_status
        check (
            usage_status is null
            or usage_status in (
                'AVAILABLE',
                'PARTIAL',
                'MISSING',
                'NOT_APPLICABLE'
            )
        ),
    constraint chk_chat_quota_pricing_status
        check (
            pricing_status is null
            or pricing_status in (
                'PRICED',
                'FREE',
                'UNPRICED',
                'CALCULATION_FAILED',
                'NOT_APPLICABLE'
            )
        ),
    constraint chk_chat_quota_state_metadata
        check (
            (
                state = 'RESERVED'
                and actual_input_tokens is null
                and actual_output_tokens is null
                and actual_cost_usd is null
                and usage_status is null
                and pricing_status is null
            )
            or
            (
                state = 'SETTLED'
                and usage_status is not null
                and pricing_status in ('PRICED', 'FREE')
                and actual_cost_usd is not null
            )
            or
            (
                state = 'UNPRICED'
                and usage_status is not null
                and pricing_status in ('UNPRICED', 'CALCULATION_FAILED')
                and actual_cost_usd is null
            )
            or
            (
                state in ('AMBIGUOUS', 'RELEASED')
                and actual_input_tokens is null
                and actual_output_tokens is null
                and actual_cost_usd is null
                and usage_status is null
                and pricing_status is null
            )
        )
);

create or replace function validate_chat_quota_reservation_scope_v32()
returns trigger
language plpgsql
as $$
declare
    turn_organization_id uuid;
    turn_user_id uuid;
begin
    select
        chat_turn.organization_id,
        chat_turn.user_id
    into
        turn_organization_id,
        turn_user_id
    from chat_turns chat_turn
    where chat_turn.id = new.turn_id;

    if not found then
        raise exception
            'quota reservation chat turn does not exist: %',
            new.turn_id;
    end if;

    if turn_organization_id is distinct from new.organization_id
            or turn_user_id is distinct from new.user_id then
        raise exception
            'quota reservation tenant/user does not match chat turn';
    end if;

    return new;
end;
$$;

create constraint trigger ctrg_chat_quota_reservation_scope_v32
    after insert or update of turn_id, organization_id, user_id
    on chat_quota_reservations
    deferrable initially deferred
    for each row
    execute function validate_chat_quota_reservation_scope_v32();