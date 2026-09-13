/* Safeai-desk/backend/src/main/resources/db/migration/V50__ai_execution_attempt_usage_evidence.sql */
/*
 * V50 — durable physical AI execution evidence.
 *
 * ChatTurn remains the lifecycle of a user-visible request.
 * ExecutionPlan represents one logical AI execution.
 * ExecutionAttempt represents exactly one physical provider call.
 *
 * V45–V49 are immutable and MUST NOT be edited.
 */

do $$
begin
    if to_regclass('public.chat_turns') is null
       or to_regclass('public.organizations') is null
       or to_regclass('public.model_route_decisions') is null then
        raise exception
            'Cannot apply V50: required Chat / Model Control Plane tables are missing';
    end if;
end
$$;

create table public.model_execution_plans (
    id uuid primary key,

    provider_operation_id uuid not null unique,
    chat_turn_id uuid not null unique
        references public.chat_turns(id)
        on delete restrict,

    organization_id uuid not null
        references public.organizations(id)
        on delete restrict,

    model_route_decision_id uuid
        references public.model_route_decisions(id)
        on delete restrict,

    requested_model varchar(100) not null,
    created_at timestamptz not null,

    constraint chk_model_execution_plans_requested_model_v50
        check (
            requested_model = btrim(requested_model)
            and length(requested_model) between 1 and 100
            and requested_model !~ '[[:cntrl:]]'
        )
);

create table public.model_execution_attempts (
    id uuid primary key,

    execution_plan_id uuid not null
        references public.model_execution_plans(id)
        on delete restrict,

    attempt_number integer not null,
    provider_attempt_id uuid not null unique,

    provider_type varchar(32) not null,
    provider_configuration_ref varchar(255) not null,
    provider_configuration_version varchar(64) not null,
    deployment_ref varchar(255) not null,
    deployment_version varchar(64) not null,

    requested_physical_model varchar(100) not null,
    resolved_physical_model varchar(100),

    started_at timestamptz not null,
    finished_at timestamptz,

    outcome varchar(24) not null,
    outcome_certainty varchar(32) not null,
    retry_safety varchar(40) not null,
    fallback_safety varchar(32) not null,

    provider_request_id varchar(255),
    provider_message_id varchar(255),
    failure_type varchar(64),

    usage_status varchar(32),
    input_tokens integer,
    cached_input_tokens integer,
    cache_write_input_tokens integer,
    output_tokens integer,

    specialized_dimensions_present boolean,
    specialized_dimensions_valid boolean,

    pricing_status varchar(32),
    cost_usd numeric(30, 12),
    currency varchar(3),
    price_book_version varchar(64),

    created_at timestamptz not null,

    constraint uq_model_execution_attempt_number_v50
        unique (execution_plan_id, attempt_number),

    constraint chk_model_execution_attempt_number_v50
        check (attempt_number >= 1),

    constraint chk_model_execution_attempt_times_v50
        check (
            created_at <= started_at
            and (
                finished_at is null
                or finished_at >= started_at
            )
        ),

    constraint chk_model_execution_attempt_non_negative_v50
        check (
            (input_tokens is null or input_tokens >= 0)
            and (cached_input_tokens is null or cached_input_tokens >= 0)
            and (cache_write_input_tokens is null or cache_write_input_tokens >= 0)
            and (output_tokens is null or output_tokens >= 0)
            and (cost_usd is null or cost_usd >= 0)
        ),

    constraint chk_model_execution_attempt_enums_v50
        check (
            outcome in (
                'STARTED',
                'SUCCEEDED',
                'FAILED',
                'AMBIGUOUS'
            )
            and outcome_certainty in (
                'KNOWN_NOT_EXECUTED',
                'KNOWN_REJECTED',
                'KNOWN_EXECUTED',
                'AMBIGUOUS'
            )
            and retry_safety in (
                'SAME_TARGET_RETRY_ALLOWED',
                'SAME_TARGET_RETRY_FORBIDDEN'
            )
            and fallback_safety in (
                'FALLBACK_ALLOWED',
                'FALLBACK_FORBIDDEN'
            )
            and (
                usage_status is null
                or usage_status in (
                    'AVAILABLE',
                    'PARTIAL',
                    'MISSING'
                )
            )
            and (
                pricing_status is null
                or pricing_status in (
                    'PRICED',
                    'FREE',
                    'UNPRICED',
                    'CALCULATION_FAILED'
                )
            )
        ),

    constraint chk_model_execution_attempt_usage_evidence_v50
        check (
            (
                usage_status is null
                and input_tokens is null
                and cached_input_tokens is null
                and cache_write_input_tokens is null
                and output_tokens is null
                and specialized_dimensions_present is null
                and specialized_dimensions_valid is null
            )
            or
            (
                usage_status = 'AVAILABLE'
                and input_tokens is not null
                and output_tokens is not null
                and specialized_dimensions_present is not null
                and specialized_dimensions_valid is not null
            )
            or
            (
                usage_status = 'PARTIAL'
                and (
                    (input_tokens is null and output_tokens is not null)
                    or
                    (input_tokens is not null and output_tokens is null)
                )
                and specialized_dimensions_present is not null
                and specialized_dimensions_valid is not null
            )
            or
            (
                usage_status = 'MISSING'
                and input_tokens is null
                and output_tokens is null
                and specialized_dimensions_present is not null
                and specialized_dimensions_valid is not null
            )
        ),

    constraint chk_model_execution_attempt_specialized_usage_v50
        check (
            (
                specialized_dimensions_present is null
                and specialized_dimensions_valid is null
                and cached_input_tokens is null
                and cache_write_input_tokens is null
            )
            or
            (
                specialized_dimensions_present = false
                and specialized_dimensions_valid = true
                and cached_input_tokens is null
                and cache_write_input_tokens is null
            )
            or
            specialized_dimensions_present = true
        ),

    constraint chk_model_execution_attempt_pricing_evidence_v50
        check (
            (
                pricing_status is null
                and cost_usd is null
                and currency is null
                and price_book_version is null
            )
            or
            (
                pricing_status in (
                    'UNPRICED',
                    'CALCULATION_FAILED'
                )
                and cost_usd is null
                and currency is null
                and price_book_version is null
            )
            or
            (
                pricing_status = 'FREE'
                and cost_usd = 0
                and currency = 'USD'
                and price_book_version = btrim(price_book_version)
                and length(price_book_version) between 1 and 64
            )
            or
            (
                pricing_status = 'PRICED'
                and cost_usd is not null
                and currency = 'USD'
                and price_book_version = btrim(price_book_version)
                and length(price_book_version) between 1 and 64
            )
        ),

    constraint chk_model_execution_attempt_lifecycle_v50
        check (
            (
                outcome = 'STARTED'
                and finished_at is null
                and resolved_physical_model is null
                and provider_request_id is null
                and provider_message_id is null
                and failure_type is null
                and usage_status is null
                and pricing_status is null
                and outcome_certainty = 'AMBIGUOUS'
                and retry_safety = 'SAME_TARGET_RETRY_FORBIDDEN'
                and fallback_safety = 'FALLBACK_FORBIDDEN'
            )
            or
            (
                outcome = 'SUCCEEDED'
                and finished_at is not null
                and resolved_physical_model is not null
                and failure_type is null
                and outcome_certainty = 'KNOWN_EXECUTED'
                and retry_safety = 'SAME_TARGET_RETRY_FORBIDDEN'
                and fallback_safety = 'FALLBACK_FORBIDDEN'
                and usage_status is not null
                and pricing_status is not null
            )
            or
            (
                outcome = 'FAILED'
                and finished_at is not null
                and resolved_physical_model is null
                and failure_type is not null
                and outcome_certainty in (
                    'KNOWN_NOT_EXECUTED',
                    'KNOWN_REJECTED'
                )
            )
            or
            (
                outcome = 'AMBIGUOUS'
                and finished_at is not null
                and resolved_physical_model is null
                and outcome_certainty = 'AMBIGUOUS'
                and retry_safety = 'SAME_TARGET_RETRY_FORBIDDEN'
                and fallback_safety = 'FALLBACK_FORBIDDEN'
            )
        )
);

create index idx_model_execution_attempts_plan_finished_v50
    on public.model_execution_attempts (
        execution_plan_id,
        finished_at,
        attempt_number
    );

create index idx_model_execution_attempts_provider_finished_v50
    on public.model_execution_attempts (
        provider_type,
        deployment_ref,
        finished_at
    )
    where outcome <> 'STARTED';

create or replace function public.enforce_model_execution_plan_link_v50()
returns trigger
language plpgsql
as $$
declare
    turn_operation uuid;
    turn_organization uuid;
    turn_route uuid;
begin
    select
        provider_operation_id,
        organization_id,
        model_route_decision_id
    into
        turn_operation,
        turn_organization,
        turn_route
    from public.chat_turns
    where id = new.chat_turn_id;

    if not found
       or turn_operation <> new.provider_operation_id
       or turn_organization <> new.organization_id then
        raise exception
            'V50 execution plan must match its ChatTurn identity';
    end if;

    if new.model_route_decision_id
            is distinct from turn_route then
        raise exception
            'V50 execution plan route decision must match its ChatTurn';
    end if;

    return new;
end
$$;

create trigger trg_model_execution_plan_link_v50
before insert or update
on public.model_execution_plans
for each row
execute function public.enforce_model_execution_plan_link_v50();

create or replace function public.enforce_model_execution_plan_immutability_v50()
returns trigger
language plpgsql
as $$
begin
    raise exception
        'V50 execution plans are immutable';
end
$$;

create trigger trg_model_execution_plan_immutability_v50
before update or delete
on public.model_execution_plans
for each row
execute function public.enforce_model_execution_plan_immutability_v50();

create or replace function public.enforce_model_execution_attempt_sequence_v50()
returns trigger
language plpgsql
as $$
declare
    expected_attempt_number integer;
begin
    perform 1
    from public.model_execution_plans
    where id = new.execution_plan_id
    for update;

    if not found then
        raise exception
            'V50 execution plan is missing';
    end if;

    select coalesce(max(attempt_number), 0) + 1
    into expected_attempt_number
    from public.model_execution_attempts
    where execution_plan_id = new.execution_plan_id;

    if new.attempt_number <> expected_attempt_number then
        raise exception
            'V50 execution attempts must be sequential';
    end if;

    return new;
end
$$;

create trigger trg_model_execution_attempt_sequence_v50
before insert
on public.model_execution_attempts
for each row
execute function public.enforce_model_execution_attempt_sequence_v50();

create or replace function public.enforce_model_execution_attempt_immutability_v50()
returns trigger
language plpgsql
as $$
begin
    if old.outcome <> 'STARTED' then
        raise exception
            'V50 terminal execution attempts are immutable';
    end if;

    if new.outcome = 'STARTED' then
        raise exception
            'V50 execution attempt cannot return to STARTED';
    end if;

    return new;
end
$$;

create trigger trg_model_execution_attempt_immutability_v50
before update or delete
on public.model_execution_attempts
for each row
execute function public.enforce_model_execution_attempt_immutability_v50();

comment on table public.model_execution_plans is
    'One immutable logical AI execution plan for one ChatTurn.';

comment on table public.model_execution_attempts is
    'Canonical immutable physical provider-call evidence; never infer provider spend from chat_messages.';