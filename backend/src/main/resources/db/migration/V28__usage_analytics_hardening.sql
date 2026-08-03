/* Safeai-desk/backend/src/main/resources/db/migration/V28__usage_analytics_hardening.sql */
-- Usage analytics correctness and rollup hardening.
-- Never edit after this migration has been applied.

-- ---------------------------------------------------------------------------
-- 1. SafeAI Desk exposes costUsd, therefore only USD is supported.
-- NOT VALID avoids an ACCESS EXCLUSIVE-style full validation in this step;
-- V30 validates historical rows after all structural changes are installed.
-- New and changed rows are checked immediately by PostgreSQL.
-- ---------------------------------------------------------------------------

alter table chat_messages
    add constraint chk_chat_messages_pricing_currency_usd
        check (
            currency is null
            or currency = 'USD'
        ) not valid;

alter table chat_messages
    add constraint chk_chat_messages_reserved_usage_model
        check (
            model is null
            or model <> '__unattributed__'
        ) not valid;

-- These tables were previously unused derivative storage. Their old rows do
-- not contain the new coverage/status semantics and must not be presented as
-- production analytics. Source chat_messages remains the source of truth and
-- the scheduler rebuilds the rollups idempotently.
truncate table
    usage_daily_org_model_rollups,
    usage_daily_user_model_rollups;

-- Aggregated known cost may be larger and requires the same scale used by the
-- AI pricing layer.
alter table usage_daily_org_model_rollups
    alter column cost_usd type numeric(30, 12);

alter table usage_daily_user_model_rollups
    alter column cost_usd type numeric(30, 12);

-- ---------------------------------------------------------------------------
-- 2. Rich rollup counters. input/output/total contain only AVAILABLE usage.
-- cost_usd contains only the known PRICED/FREE part of the cost.
-- ---------------------------------------------------------------------------

alter table usage_daily_org_model_rollups
    add column completed_response_count bigint not null default 0,
    add column refused_response_count bigint not null default 0,
    add column incomplete_response_count bigint not null default 0,
    add column partial_input_tokens bigint not null default 0,
    add column partial_output_tokens bigint not null default 0,
    add column available_usage_message_count bigint not null default 0,
    add column partial_usage_message_count bigint not null default 0,
    add column missing_usage_message_count bigint not null default 0,
    add column usage_not_applicable_message_count bigint not null default 0,
    add column priced_message_count bigint not null default 0,
    add column free_message_count bigint not null default 0,
    add column unpriced_message_count bigint not null default 0,
    add column pricing_failed_message_count bigint not null default 0,
    add column pricing_not_applicable_message_count bigint not null default 0;

alter table usage_daily_user_model_rollups
    add column completed_response_count bigint not null default 0,
    add column refused_response_count bigint not null default 0,
    add column incomplete_response_count bigint not null default 0,
    add column partial_input_tokens bigint not null default 0,
    add column partial_output_tokens bigint not null default 0,
    add column available_usage_message_count bigint not null default 0,
    add column partial_usage_message_count bigint not null default 0,
    add column missing_usage_message_count bigint not null default 0,
    add column usage_not_applicable_message_count bigint not null default 0,
    add column priced_message_count bigint not null default 0,
    add column free_message_count bigint not null default 0,
    add column unpriced_message_count bigint not null default 0,
    add column pricing_failed_message_count bigint not null default 0,
    add column pricing_not_applicable_message_count bigint not null default 0;

alter table usage_daily_org_model_rollups
    add constraint chk_usage_daily_org_model_extended_values_non_negative
        check (
            completed_response_count >= 0
            and refused_response_count >= 0
            and incomplete_response_count >= 0
            and partial_input_tokens >= 0
            and partial_output_tokens >= 0
            and available_usage_message_count >= 0
            and partial_usage_message_count >= 0
            and missing_usage_message_count >= 0
            and usage_not_applicable_message_count >= 0
            and priced_message_count >= 0
            and free_message_count >= 0
            and unpriced_message_count >= 0
            and pricing_failed_message_count >= 0
            and pricing_not_applicable_message_count >= 0
        ),
    add constraint chk_usage_daily_org_model_total_tokens_consistent
        check (total_tokens = input_tokens + output_tokens),
    add constraint chk_usage_daily_org_model_response_counts
        check (
            completed_response_count
            + refused_response_count
            + incomplete_response_count
            + failed_message_count
            <= assistant_message_count
        ),
    add constraint chk_usage_daily_org_model_usage_counts
        check (
            available_usage_message_count
            + partial_usage_message_count
            + missing_usage_message_count
            + usage_not_applicable_message_count
            = assistant_message_count
        ),
    add constraint chk_usage_daily_org_model_pricing_counts
        check (
            priced_message_count
            + free_message_count
            + unpriced_message_count
            + pricing_failed_message_count
            + pricing_not_applicable_message_count
            = assistant_message_count
        );

alter table usage_daily_user_model_rollups
    add constraint chk_usage_daily_user_model_extended_values_non_negative
        check (
            completed_response_count >= 0
            and refused_response_count >= 0
            and incomplete_response_count >= 0
            and partial_input_tokens >= 0
            and partial_output_tokens >= 0
            and available_usage_message_count >= 0
            and partial_usage_message_count >= 0
            and missing_usage_message_count >= 0
            and usage_not_applicable_message_count >= 0
            and priced_message_count >= 0
            and free_message_count >= 0
            and unpriced_message_count >= 0
            and pricing_failed_message_count >= 0
            and pricing_not_applicable_message_count >= 0
        ),
    add constraint chk_usage_daily_user_model_total_tokens_consistent
        check (total_tokens = input_tokens + output_tokens),
    add constraint chk_usage_daily_user_model_response_counts
        check (
            completed_response_count
            + refused_response_count
            + incomplete_response_count
            + failed_message_count
            <= assistant_message_count
        ),
    add constraint chk_usage_daily_user_model_usage_counts
        check (
            available_usage_message_count
            + partial_usage_message_count
            + missing_usage_message_count
            + usage_not_applicable_message_count
            = assistant_message_count
        ),
    add constraint chk_usage_daily_user_model_pricing_counts
        check (
            priced_message_count
            + free_message_count
            + unpriced_message_count
            + pricing_failed_message_count
            + pricing_not_applicable_message_count
            = assistant_message_count
        );

comment on column usage_daily_org_model_rollups.cost_usd is
    'Known USD cost only: PRICED + FREE rows. Inspect pricing counters for completeness.';
comment on column usage_daily_user_model_rollups.cost_usd is
    'Known USD cost only: PRICED + FREE rows. Inspect pricing counters for completeness.';
comment on column usage_daily_org_model_rollups.input_tokens is
    'Confirmed input tokens from AVAILABLE usage only.';
comment on column usage_daily_user_model_rollups.input_tokens is
    'Confirmed input tokens from AVAILABLE usage only.';

-- ---------------------------------------------------------------------------
-- 3. Separate quality rollup also covers failed/no-model assistant messages.
-- ---------------------------------------------------------------------------

create table usage_daily_quality_rollups (
    usage_date date not null,
    organization_id uuid not null references organizations(id),

    assistant_message_count bigint not null default 0,
    stored_completed_message_count bigint not null default 0,
    stored_failed_message_count bigint not null default 0,
    missing_model_message_count bigint not null default 0,

    input_tokens bigint not null default 0,
    output_tokens bigint not null default 0,
    partial_input_tokens bigint not null default 0,
    partial_output_tokens bigint not null default 0,
    cost_usd numeric(30, 12) not null default 0,

    available_usage_message_count bigint not null default 0,
    partial_usage_message_count bigint not null default 0,
    missing_usage_message_count bigint not null default 0,
    usage_not_applicable_message_count bigint not null default 0,

    priced_message_count bigint not null default 0,
    free_message_count bigint not null default 0,
    unpriced_message_count bigint not null default 0,
    pricing_failed_message_count bigint not null default 0,
    pricing_not_applicable_message_count bigint not null default 0,

    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,

    primary key (usage_date, organization_id),

    constraint chk_usage_daily_quality_values_non_negative
        check (
            assistant_message_count >= 0
            and stored_completed_message_count >= 0
            and stored_failed_message_count >= 0
            and missing_model_message_count >= 0
            and input_tokens >= 0
            and output_tokens >= 0
            and partial_input_tokens >= 0
            and partial_output_tokens >= 0
            and cost_usd >= 0
            and available_usage_message_count >= 0
            and partial_usage_message_count >= 0
            and missing_usage_message_count >= 0
            and usage_not_applicable_message_count >= 0
            and priced_message_count >= 0
            and free_message_count >= 0
            and unpriced_message_count >= 0
            and pricing_failed_message_count >= 0
            and pricing_not_applicable_message_count >= 0
        ),

    constraint chk_usage_daily_quality_stored_status_count
        check (
            stored_completed_message_count
            + stored_failed_message_count
            <= assistant_message_count
        ),

    constraint chk_usage_daily_quality_missing_model_count
        check (missing_model_message_count <= assistant_message_count),

    constraint chk_usage_daily_quality_usage_counts
        check (
            available_usage_message_count
            + partial_usage_message_count
            + missing_usage_message_count
            + usage_not_applicable_message_count
            = assistant_message_count
        ),

    constraint chk_usage_daily_quality_pricing_counts
        check (
            priced_message_count
            + free_message_count
            + unpriced_message_count
            + pricing_failed_message_count
            + pricing_not_applicable_message_count
            = assistant_message_count
        ),

    constraint chk_usage_daily_quality_updated_after_created
        check (updated_at >= created_at)
);

create trigger trg_usage_daily_quality_rollups_updated_at
    before update on usage_daily_quality_rollups
    for each row
    execute function set_updated_at();

-- ---------------------------------------------------------------------------
-- 4. Contiguous rollup watermark. NULL means historical backfill has not
-- completed yet; the query layer falls back to chat_messages in that case.
-- ---------------------------------------------------------------------------

create table usage_rollup_state (
    job_name varchar(100) primary key,
    last_completed_date date,
    updated_at timestamptz not null default current_timestamp,

    constraint chk_usage_rollup_state_job_name_not_blank
        check (length(trim(job_name)) > 0)
);

insert into usage_rollup_state (
    job_name,
    last_completed_date,
    updated_at
) values (
    'usage-daily-rollup',
    null,
    current_timestamp
);

create trigger trg_usage_rollup_state_updated_at
    before update on usage_rollup_state
    for each row
    execute function set_updated_at();
