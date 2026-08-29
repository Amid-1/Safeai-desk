/* Safeai-desk/backend/src/main/resources/db/migration/V45__model_control_plane.sql */
/* SafeAI Desk V45 — Model Control Plane governance/evidence layer.
 *
 * V45 intentionally does NOT add a multi-provider runtime multiplexer.
 * A route is executable only when selected_provider/selected_provider_model_id
 * exactly match the single adapter exposed by RuntimeModelStatusService.
 */

-- ---------------------------------------------------------------------------
-- 0. Baseline preflight.
-- V45 must be applied only after the complete V44 production schema.
-- ---------------------------------------------------------------------------

do $$
begin
    if to_regclass('public.chat_sessions') is null
       or to_regclass('public.chat_turns') is null
       or to_regclass('public.knowledge_answer_passports') is null
       or to_regclass('public.audit_event_types') is null then
        raise exception
            'Cannot apply V45: required V44 baseline tables are missing';
    end if;

    if not exists (
        select 1
        from pg_attribute
        where attrelid = 'public.chat_turns'::regclass
          and attname = 'requested_model'
          and not attisdropped
    ) then
        raise exception
            'Cannot apply V45: chat_turns.requested_model is missing';
    end if;
end
$$;

-- ---------------------------------------------------------------------------
-- 1. Versioned immutable model catalog.
-- ---------------------------------------------------------------------------

create table public.model_catalog_entries (
    id uuid primary key,
    model_key varchar(160) not null,
    version integer not null,
    provider varchar(32) not null,
    provider_model_id varchar(100) not null,
    display_name varchar(255) not null,
    lifecycle varchar(32) not null,

    max_input_tokens integer not null,
    max_output_tokens integer not null,

    tools_supported boolean not null default false,
    vision_supported boolean not null default false,
    structured_output_supported boolean not null default false,

    text_input_supported boolean not null default true,
    image_input_supported boolean not null default false,
    audio_input_supported boolean not null default false,
    text_output_supported boolean not null default true,
    audio_output_supported boolean not null default false,

    retention_status varchar(40) not null,
    retention_days integer,
    training_use_status varchar(40) not null,

    pricing_status varchar(32) not null,
    pricing_complete boolean not null default false,
    input_usd_per_1m_tokens numeric(30, 12),
    cached_input_usd_per_1m_tokens numeric(30, 12),
    cache_write_input_usd_per_1m_tokens numeric(30, 12),
    output_usd_per_1m_tokens numeric(30, 12),
    extra_pricing_json jsonb not null default '{}'::jsonb,
    pricing_version varchar(64),

    effective_from timestamptz not null,
    source varchar(32) not null,
    created_by_user_id uuid not null,
    created_at timestamptz not null,

    constraint uq_model_catalog_entries_key_version
        unique (model_key, version),
    constraint uq_model_catalog_entries_snapshot_identity
        unique (id, version, model_key, provider, provider_model_id),
    constraint chk_model_catalog_entries_key
        check (model_key ~ '^[a-z0-9][a-z0-9._:/-]{0,159}$'),
    constraint chk_model_catalog_entries_version
        check (version > 0),
    constraint chk_model_catalog_entries_provider
        check (
            length(btrim(provider)) between 1 and 32
            and provider = btrim(provider)
        ),
    constraint chk_model_catalog_entries_provider_model
        check (
            length(btrim(provider_model_id)) between 1 and 100
            and provider_model_id = btrim(provider_model_id)
        ),
    constraint chk_model_catalog_entries_display_name
        check (
            length(btrim(display_name)) between 1 and 255
            and display_name = btrim(display_name)
        ),
    constraint chk_model_catalog_entries_lifecycle
        check (lifecycle in ('ACTIVE', 'DEPRECATED', 'DISABLED', 'RETIRED')),
    constraint chk_model_catalog_entries_limits
        check (max_input_tokens > 0 and max_output_tokens > 0),
    constraint chk_model_catalog_entries_modalities
        check (
            (text_input_supported or image_input_supported or audio_input_supported)
            and (text_output_supported or audio_output_supported)
        ),
    constraint chk_model_catalog_entries_retention
        check (
            retention_status in (
                'NOT_DECLARED', 'STANDARD', 'ZERO_DATA_RETENTION', 'CUSTOM'
            )
            and (retention_days is null or retention_days >= 0)
            and (
                retention_status <> 'ZERO_DATA_RETENTION'
                or coalesce(retention_days, 0) = 0
            )
        ),
    constraint chk_model_catalog_entries_training
        check (training_use_status in (
            'NOT_DECLARED', 'NOT_USED', 'MAY_BE_USED', 'CONTRACTUAL_NO_TRAINING'
        )),
    constraint chk_model_catalog_entries_pricing_status
        check (pricing_status in ('UNPRICED', 'FREE', 'CONFIGURED', 'INCOMPLETE')),
    constraint chk_model_catalog_entries_prices_non_negative
        check (
            (input_usd_per_1m_tokens is null or input_usd_per_1m_tokens >= 0)
            and (cached_input_usd_per_1m_tokens is null or cached_input_usd_per_1m_tokens >= 0)
            and (cache_write_input_usd_per_1m_tokens is null or cache_write_input_usd_per_1m_tokens >= 0)
            and (output_usd_per_1m_tokens is null or output_usd_per_1m_tokens >= 0)
        ),
    constraint chk_model_catalog_entries_pricing_semantics
        check (
            (
                pricing_status = 'UNPRICED'
                and pricing_complete = false
                and input_usd_per_1m_tokens is null
                and cached_input_usd_per_1m_tokens is null
                and cache_write_input_usd_per_1m_tokens is null
                and output_usd_per_1m_tokens is null
                and extra_pricing_json = '{}'::jsonb
            )
            or
            (
                pricing_status = 'FREE'
                and pricing_complete = true
                and input_usd_per_1m_tokens = 0
                and output_usd_per_1m_tokens = 0
                and coalesce(cached_input_usd_per_1m_tokens, 0) = 0
                and coalesce(cache_write_input_usd_per_1m_tokens, 0) = 0
                and extra_pricing_json = '{}'::jsonb
            )
            or
            (
                pricing_status = 'CONFIGURED'
                and pricing_complete = true
                and input_usd_per_1m_tokens is not null
                and output_usd_per_1m_tokens is not null
                and pricing_version is not null
                and length(btrim(pricing_version)) > 0
                and extra_pricing_json = '{}'::jsonb
                and (
                    cached_input_usd_per_1m_tokens is null
                    or cached_input_usd_per_1m_tokens
                        <= input_usd_per_1m_tokens
                )
                and (
                    cache_write_input_usd_per_1m_tokens is null
                    or cache_write_input_usd_per_1m_tokens
                        <= input_usd_per_1m_tokens
                )
            )
            or
            (
                pricing_status = 'INCOMPLETE'
                and pricing_complete = false
            )
        ),
    constraint chk_model_catalog_entries_extra_pricing
        check (jsonb_typeof(extra_pricing_json) = 'object'),
    constraint chk_model_catalog_entries_pricing_version
        check (
            pricing_version is null
            or (
                length(btrim(pricing_version)) between 1 and 64
                and pricing_version = btrim(pricing_version)
            )
        ),
    constraint chk_model_catalog_entries_source
        check (source in ('MANUAL', 'RUNTIME_IMPORT', 'MIGRATED'))
);

create index idx_model_catalog_entries_runtime
    on public.model_catalog_entries (
        provider,
        provider_model_id,
        version desc
    );

create index idx_model_catalog_entries_effective
    on public.model_catalog_entries (
        model_key,
        effective_from desc,
        version desc
    );

-- ---------------------------------------------------------------------------
-- 2. Append-only tenant policy versions.
-- ---------------------------------------------------------------------------

create table public.organization_model_policies (
    id uuid primary key,
    organization_id uuid not null
        references public.organizations(id) on delete restrict,
    version integer not null,
    enabled boolean not null default true,

    allow_model_keys text[] not null default '{}'::text[],
    deny_model_keys text[] not null default '{}'::text[],
    default_model_key varchar(160),

    max_input_tokens integer,
    max_output_tokens integer,
    max_request_cost_usd numeric(30, 12),
    monthly_budget_usd numeric(30, 12),
    budget_enforcement varchar(16) not null,

    require_complete_pricing boolean not null default false,
    require_no_training boolean not null default false,
    require_zero_data_retention boolean not null default false,

    created_by_user_id uuid not null,
    created_at timestamptz not null,

    constraint uq_organization_model_policies_version
        unique (organization_id, version),
    constraint uq_organization_model_policies_identity
        unique (id, organization_id, version),
    constraint chk_organization_model_policies_version
        check (version > 0),
    constraint chk_organization_model_policies_default_key
        check (
            default_model_key is null
            or default_model_key ~ '^[a-z0-9][a-z0-9._:/-]{0,159}$'
        ),
    constraint chk_organization_model_policies_list_sizes
        check (
            cardinality(allow_model_keys) <= 200
            and cardinality(deny_model_keys) <= 200
        ),
    constraint chk_organization_model_policies_list_nulls
        check (
            array_position(allow_model_keys, null) is null
            and array_position(deny_model_keys, null) is null
        ),
    constraint chk_organization_model_policies_allow_keys
        check (
            cardinality(allow_model_keys) = 0
            or array_to_string(allow_model_keys, ',')
                ~ '^[a-z0-9][a-z0-9._:/-]{0,159}(,[a-z0-9][a-z0-9._:/-]{0,159})*$'
        ),
    constraint chk_organization_model_policies_deny_keys
        check (
            cardinality(deny_model_keys) = 0
            or array_to_string(deny_model_keys, ',')
                ~ '^[a-z0-9][a-z0-9._:/-]{0,159}(,[a-z0-9][a-z0-9._:/-]{0,159})*$'
        ),
    constraint chk_organization_model_policies_lists_disjoint
        check (not (allow_model_keys && deny_model_keys)),
    constraint chk_organization_model_policies_default_membership
        check (
            default_model_key is null
            or (
                not (default_model_key = any(deny_model_keys))
                and (
                    cardinality(allow_model_keys) = 0
                    or default_model_key = any(allow_model_keys)
                )
            )
        ),
    constraint chk_organization_model_policies_limits
        check (
            (max_input_tokens is null or max_input_tokens > 0)
            and (max_output_tokens is null or max_output_tokens > 0)
            and (max_request_cost_usd is null or max_request_cost_usd >= 0)
            and (monthly_budget_usd is null or monthly_budget_usd >= 0)
        ),
    constraint chk_organization_model_policies_enforcement
        check (budget_enforcement in ('SOFT', 'HARD'))
);

create index idx_organization_model_policies_latest
    on public.organization_model_policies (
        organization_id,
        version desc
    );

-- ---------------------------------------------------------------------------
-- 3. Immutable deterministic routing evidence.
-- ---------------------------------------------------------------------------

create table public.model_route_decisions (
    id uuid primary key,
    organization_id uuid not null
        references public.organizations(id) on delete restrict,
    user_id uuid not null,
    chat_id uuid not null,
    chat_turn_id uuid,
    client_request_id uuid not null,
    request_content_hash varchar(64) not null,

    requested_model_key varchar(160),
    selected_catalog_entry_id uuid,
    selected_catalog_version integer,
    selected_model_key varchar(160),
    selected_provider varchar(32),
    selected_provider_model_id varchar(100),

    policy_id uuid,
    policy_version integer,

    required_capabilities text[] not null default '{}'::text[],
    estimated_input_tokens bigint,
    estimated_output_tokens bigint,
    estimated_max_cost_usd numeric(30, 12),

    monthly_budget_usd numeric(30, 12),
    monthly_spent_usd numeric(30, 12),
    monthly_projected_usd numeric(30, 12),
    monthly_cost_known boolean not null default true,
    budget_enforcement varchar(16),
    budget_exceeded boolean not null default false,
    pricing_complete boolean not null,

    outcome varchar(16) not null,
    reason varchar(64) not null,
    decision_sha256 varchar(64) not null,
    created_at timestamptz not null,

    constraint uq_model_route_decisions_request
        unique (chat_id, client_request_id),
    constraint uq_model_route_decisions_turn
        unique (chat_turn_id),
    constraint uq_model_route_decisions_turn_scope
        unique (
            id,
            chat_turn_id,
            organization_id,
            user_id,
            chat_id,
            client_request_id,
            request_content_hash,
            selected_provider,
            selected_provider_model_id
        ),
    constraint uq_model_route_decisions_passport_scope
        unique (
            id,
            chat_turn_id,
            organization_id,
            user_id,
            selected_provider,
            selected_provider_model_id
        ),
    constraint fk_model_route_decisions_chat_scope
        foreign key (chat_id, organization_id, user_id)
        references public.chat_sessions (id, organization_id, user_id)
        on delete restrict,
    constraint fk_model_route_decisions_catalog_snapshot
        foreign key (
            selected_catalog_entry_id,
            selected_catalog_version,
            selected_model_key,
            selected_provider,
            selected_provider_model_id
        )
        references public.model_catalog_entries (
            id,
            version,
            model_key,
            provider,
            provider_model_id
        )
        on delete restrict,
    constraint fk_model_route_decisions_policy_snapshot
        foreign key (policy_id, organization_id, policy_version)
        references public.organization_model_policies (id, organization_id, version)
        on delete restrict,
    constraint chk_model_route_decisions_request_hash
        check (request_content_hash ~ '^[0-9a-f]{64}$'),
    constraint chk_model_route_decisions_requested_key
        check (
            requested_model_key is null
            or requested_model_key ~ '^[a-z0-9][a-z0-9._:/-]{0,159}$'
        ),
    constraint chk_model_route_decisions_selected_text
        check (
            (selected_model_key is null or (
                length(btrim(selected_model_key)) between 1 and 160
                and selected_model_key = btrim(selected_model_key)
            ))
            and (selected_provider is null or (
                length(btrim(selected_provider)) between 1 and 32
                and selected_provider = btrim(selected_provider)
            ))
            and (selected_provider_model_id is null or (
                length(btrim(selected_provider_model_id)) between 1 and 100
                and selected_provider_model_id = btrim(selected_provider_model_id)
            ))
        ),
    constraint chk_model_route_decisions_outcome
        check (outcome in ('ALLOWED', 'DENIED')),
    constraint chk_model_route_decisions_turn_outcome
        check (
            (outcome = 'ALLOWED' and chat_turn_id is not null)
            or
            (outcome = 'DENIED' and chat_turn_id is null)
        ),
    constraint chk_model_route_decisions_reason
        check (reason in (
            'POLICY_DEFAULT',
            'RUNTIME_ONLY_MATCH',
            'LEGACY_RUNTIME_FALLBACK',
            'MODEL_NOT_ALLOWED',
            'MODEL_DENIED',
            'MODEL_NOT_FOUND',
            'MODEL_DISABLED',
            'RUNTIME_MISMATCH',
            'CAPABILITY_UNSUPPORTED',
            'INPUT_LIMIT_EXCEEDED',
            'OUTPUT_LIMIT_EXCEEDED',
            'PRICING_INCOMPLETE',
            'TRAINING_POLICY_UNSATISFIED',
            'RETENTION_POLICY_UNSATISFIED',
            'REQUEST_COST_LIMIT_EXCEEDED',
            'MONTHLY_BUDGET_EXCEEDED',
            'MONTHLY_BUDGET_UNVERIFIABLE'
        )),
    constraint chk_model_route_decisions_reason_semantics
        check (
            (
                outcome = 'ALLOWED'
                and reason in (
                    'POLICY_DEFAULT',
                    'RUNTIME_ONLY_MATCH',
                    'LEGACY_RUNTIME_FALLBACK'
                )
            )
            or
            (
                outcome = 'DENIED'
                and reason in (
                    'MODEL_NOT_ALLOWED',
                    'MODEL_DENIED',
                    'MODEL_NOT_FOUND',
                    'MODEL_DISABLED',
                    'RUNTIME_MISMATCH',
                    'CAPABILITY_UNSUPPORTED',
                    'INPUT_LIMIT_EXCEEDED',
                    'OUTPUT_LIMIT_EXCEEDED',
                    'PRICING_INCOMPLETE',
                    'TRAINING_POLICY_UNSATISFIED',
                    'RETENTION_POLICY_UNSATISFIED',
                    'REQUEST_COST_LIMIT_EXCEEDED',
                    'MONTHLY_BUDGET_EXCEEDED',
                    'MONTHLY_BUDGET_UNVERIFIABLE'
                )
            )
        ),
    constraint chk_model_route_decisions_capabilities
        check (
            array_position(required_capabilities, null) is null
            and cardinality(required_capabilities) <= 3
            and required_capabilities
                <@ array['TOOLS', 'VISION', 'STRUCTURED_OUTPUT']::text[]
        ),
    constraint chk_model_route_decisions_hash
        check (decision_sha256 ~ '^[0-9a-f]{64}$'),
    constraint chk_model_route_decisions_estimates
        check (
            (estimated_input_tokens is null or estimated_input_tokens >= 0)
            and (estimated_output_tokens is null or estimated_output_tokens >= 0)
            and (estimated_max_cost_usd is null or estimated_max_cost_usd >= 0)
            and (monthly_budget_usd is null or monthly_budget_usd >= 0)
            and (monthly_spent_usd is null or monthly_spent_usd >= 0)
            and (monthly_projected_usd is null or monthly_projected_usd >= 0)
        ),
    constraint chk_model_route_decisions_budget_enforcement
        check (budget_enforcement is null or budget_enforcement in ('SOFT', 'HARD')),
    constraint chk_model_route_decisions_policy_pair
        check ((policy_id is null) = (policy_version is null)),
    constraint chk_model_route_decisions_policy_budget_snapshot
        check (
            (policy_id is null and budget_enforcement is null)
            or
            (policy_id is not null and budget_enforcement is not null)
        ),
    constraint chk_model_route_decisions_catalog_pair
        check (
            (selected_catalog_entry_id is null) = (selected_catalog_version is null)
        ),
    constraint chk_model_route_decisions_catalog_snapshot
        check (
            selected_catalog_entry_id is null
            or (
                selected_catalog_version is not null
                and selected_model_key is not null
                and selected_provider is not null
                and selected_provider_model_id is not null
            )
        ),
    constraint chk_model_route_decisions_budget_snapshot
        check (
            (not budget_exceeded or (
                monthly_budget_usd is not null
                and monthly_projected_usd is not null
                and monthly_projected_usd > monthly_budget_usd
            ))
            and (
                monthly_cost_known
                or monthly_projected_usd is null
            )
            and (
                monthly_projected_usd is null
                or (
                    monthly_spent_usd is not null
                    and estimated_max_cost_usd is not null
                    and monthly_projected_usd =
                        monthly_spent_usd + estimated_max_cost_usd
                )
            )
        ),
    constraint chk_model_route_decisions_allowed_metadata
        check (
            outcome <> 'ALLOWED'
            or (
                selected_model_key is not null
                and selected_provider is not null
                and selected_provider_model_id is not null
                and estimated_input_tokens is not null
                and estimated_output_tokens is not null
            )
        )
);

create index idx_model_route_decisions_org_month
    on public.model_route_decisions (
        organization_id,
        created_at desc
    )
    where outcome = 'ALLOWED';

create index idx_model_route_decisions_user
    on public.model_route_decisions (
        organization_id,
        user_id,
        created_at desc
    );

-- ---------------------------------------------------------------------------
-- 4. Bind new ChatTurns to the exact route decision. Nullable is required for
--    V1–V44 rows and rolling deployment; V45 Java creates new turns with it.
-- ---------------------------------------------------------------------------

alter table public.chat_turns
    add column model_route_decision_id uuid;

alter table public.chat_turns
    add constraint chk_chat_turns_route_metadata_v45
        check (
            model_route_decision_id is null
            or (
                provider is not null
                and length(trim(provider)) > 0
                and requested_model is not null
                and length(trim(requested_model)) > 0
            )
        ) not valid;

alter table public.chat_turns
    validate constraint chk_chat_turns_route_metadata_v45;

create unique index ux_chat_turns_model_route_decision
    on public.chat_turns(model_route_decision_id)
    where model_route_decision_id is not null;

alter table public.chat_turns
    add constraint fk_chat_turns_model_route_decision_v45
        foreign key (
            model_route_decision_id,
            id,
            organization_id,
            user_id,
            session_id,
            client_request_id,
            request_content_hash,
            provider,
            requested_model
        )
        references public.model_route_decisions (
            id,
            chat_turn_id,
            organization_id,
            user_id,
            chat_id,
            client_request_id,
            request_content_hash,
            selected_provider,
            selected_provider_model_id
        )
        deferrable initially immediate
        not valid;

alter table public.chat_turns
    validate constraint fk_chat_turns_model_route_decision_v45;

-- A committed ALLOWED decision must have the exact ChatTurn linked back to it.
create or replace function public.validate_allowed_model_route_turn_v45()
returns trigger
language plpgsql
as $$
declare
    linked_count integer;
begin
    if new.outcome <> 'ALLOWED' then
        return null;
    end if;

    select count(*)
    into linked_count
    from public.chat_turns turn_row
    where turn_row.id = new.chat_turn_id
      and turn_row.model_route_decision_id = new.id
      and turn_row.organization_id = new.organization_id
      and turn_row.user_id = new.user_id
      and turn_row.session_id = new.chat_id
      and turn_row.client_request_id = new.client_request_id
      and turn_row.request_content_hash = new.request_content_hash
      and turn_row.provider = new.selected_provider
      and turn_row.requested_model = new.selected_provider_model_id;

    if linked_count <> 1 then
        raise exception
            'ALLOWED model route decision is not linked to its exact ChatTurn: %',
            new.id;
    end if;

    return null;
end
$$;

create constraint trigger ctrg_model_route_decision_turn_v45
after insert on public.model_route_decisions
deferrable initially deferred
for each row
execute function public.validate_allowed_model_route_turn_v45();

-- ---------------------------------------------------------------------------
-- 5. Bind Answer Passport to the same immutable route evidence.
-- ---------------------------------------------------------------------------

alter table public.knowledge_answer_passports
    add column model_route_decision_id uuid;

create index idx_knowledge_answer_passports_route_v45
    on public.knowledge_answer_passports(model_route_decision_id)
    where model_route_decision_id is not null;

alter table public.knowledge_answer_passports
    add constraint fk_knowledge_answer_passports_route_v45
        foreign key (
            model_route_decision_id,
            chat_turn_id,
            organization_id,
            user_id,
            provider,
            requested_model
        )
        references public.model_route_decisions (
            id,
            chat_turn_id,
            organization_id,
            user_id,
            selected_provider,
            selected_provider_model_id
        )
        on delete restrict
        not valid;

alter table public.knowledge_answer_passports
    validate constraint fk_knowledge_answer_passports_route_v45;

-- ---------------------------------------------------------------------------
-- 6. Immutable control-plane provenance.
-- ---------------------------------------------------------------------------

create or replace function public.reject_model_control_plane_mutation_v45()
returns trigger
language plpgsql
as $$
begin
    raise exception using
        errcode = '23514',
        message = format('%s rows are immutable control-plane provenance', tg_table_name);
end
$$;

create trigger trg_model_catalog_entries_immutable_v45
before update or delete on public.model_catalog_entries
for each row
execute function public.reject_model_control_plane_mutation_v45();

create trigger trg_organization_model_policies_immutable_v45
before update or delete on public.organization_model_policies
for each row
execute function public.reject_model_control_plane_mutation_v45();

create trigger trg_model_route_decisions_immutable_v45
before update or delete on public.model_route_decisions
for each row
execute function public.reject_model_control_plane_mutation_v45();

-- ---------------------------------------------------------------------------
-- 7. Audit directory. audit_events.event_type has an FK to this table.
-- ---------------------------------------------------------------------------

insert into public.audit_event_types (name, description)
values
    ('MODEL_CATALOG_VERSION_CREATED', 'Immutable model catalog version created'),
    ('MODEL_POLICY_VERSION_CREATED', 'Immutable organization model policy version created'),
    ('MODEL_ROUTE_DECIDED', 'Deterministic model route allowed and persisted'),
    ('MODEL_ROUTE_DENIED', 'Model route denied by catalog, policy or budget governance')
on conflict (name) do nothing;

comment on table public.model_catalog_entries is
    'Append-only versioned model catalog: limits, capabilities, retention/training declarations and pricing dimensions.';
comment on table public.organization_model_policies is
    'Append-only per-tenant model governance policy versions.';
comment on table public.model_route_decisions is
    'Immutable deterministic model routing evidence created before ChatTurn reservation. V45 only allows routes executable by the active single runtime adapter.';
comment on column public.chat_turns.model_route_decision_id is
    'Exact V45+ governance decision used to reserve this ChatTurn; nullable only for historical/rolling-deploy rows.';
comment on column public.knowledge_answer_passports.model_route_decision_id is
    'Exact model route evidence used by the ChatTurn that produced this Answer Passport; nullable only for historical rows.';
