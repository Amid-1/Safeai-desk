/* Safeai-desk/backend/src/main/resources/db/migration/V7__usage_quotas_and_rollups.sql */

create table organization_ai_quotas (
                                        organization_id uuid primary key references organizations(id),

                                        monthly_message_limit bigint,
                                        monthly_token_limit bigint,
                                        monthly_cost_limit_usd numeric(12, 2),

                                        enabled boolean not null default true,

                                        created_at timestamptz not null,
                                        updated_at timestamptz not null,
                                        version bigint not null default 0,

                                        constraint chk_organization_ai_quotas_monthly_message_limit
                                            check (monthly_message_limit is null or monthly_message_limit >= 0),

                                        constraint chk_organization_ai_quotas_monthly_token_limit
                                            check (monthly_token_limit is null or monthly_token_limit >= 0),

                                        constraint chk_organization_ai_quotas_monthly_cost_limit_usd
                                            check (monthly_cost_limit_usd is null or monthly_cost_limit_usd >= 0),

                                        constraint chk_organization_ai_quotas_updated_after_created
                                            check (updated_at >= created_at),

                                        constraint chk_organization_ai_quotas_version_non_negative
                                            check (version >= 0)
);


create table user_ai_quotas (
                                user_id uuid primary key references users(id) on delete cascade,

                                hourly_message_limit int,
                                daily_message_limit int,
                                daily_token_limit bigint,
                                monthly_message_limit bigint,
                                monthly_token_limit bigint,
                                monthly_cost_limit_usd numeric(12, 2),

                                enabled boolean not null default true,

                                created_at timestamptz not null,
                                updated_at timestamptz not null,
                                version bigint not null default 0,

                                constraint chk_user_ai_quotas_hourly_message_limit
                                    check (hourly_message_limit is null or hourly_message_limit >= 0),

                                constraint chk_user_ai_quotas_daily_message_limit
                                    check (daily_message_limit is null or daily_message_limit >= 0),

                                constraint chk_user_ai_quotas_daily_token_limit
                                    check (daily_token_limit is null or daily_token_limit >= 0),

                                constraint chk_user_ai_quotas_monthly_message_limit
                                    check (monthly_message_limit is null or monthly_message_limit >= 0),

                                constraint chk_user_ai_quotas_monthly_token_limit
                                    check (monthly_token_limit is null or monthly_token_limit >= 0),

                                constraint chk_user_ai_quotas_monthly_cost_limit_usd
                                    check (monthly_cost_limit_usd is null or monthly_cost_limit_usd >= 0),

                                constraint chk_user_ai_quotas_updated_after_created
                                    check (updated_at >= created_at),

                                constraint chk_user_ai_quotas_version_non_negative
                                    check (version >= 0)
);


create table usage_daily_org_model_rollups (
                                               usage_date date not null,

                                               organization_id uuid not null references organizations(id),

                                               model varchar(100) not null,

                                               input_tokens bigint not null default 0,
                                               output_tokens bigint not null default 0,
                                               total_tokens bigint not null default 0,

                                               cost_usd numeric(12, 6) not null default 0,

                                               assistant_message_count bigint not null default 0,
                                               failed_message_count bigint not null default 0,

                                               created_at timestamptz not null,
                                               updated_at timestamptz not null,

                                               primary key (usage_date, organization_id, model),

                                               constraint chk_usage_daily_org_model_not_blank
                                                   check (length(trim(model)) > 0),

                                               constraint chk_usage_daily_org_model_values_non_negative
                                                   check (
                                                       input_tokens >= 0
                                                           and output_tokens >= 0
                                                           and total_tokens >= 0
                                                           and cost_usd >= 0
                                                           and assistant_message_count >= 0
                                                           and failed_message_count >= 0
                                                       ),

                                               constraint chk_usage_daily_org_model_updated_after_created
                                                   check (updated_at >= created_at)
);


create table usage_daily_user_model_rollups (
                                                usage_date date not null,

                                                organization_id uuid not null references organizations(id),
                                                user_id uuid not null references users(id),

                                                model varchar(100) not null,

                                                input_tokens bigint not null default 0,
                                                output_tokens bigint not null default 0,
                                                total_tokens bigint not null default 0,

                                                cost_usd numeric(12, 6) not null default 0,

                                                assistant_message_count bigint not null default 0,
                                                failed_message_count bigint not null default 0,

                                                created_at timestamptz not null,
                                                updated_at timestamptz not null,

                                                primary key (usage_date, organization_id, user_id, model),

                                                constraint chk_usage_daily_user_model_not_blank
                                                    check (length(trim(model)) > 0),

                                                constraint chk_usage_daily_user_model_values_non_negative
                                                    check (
                                                        input_tokens >= 0
                                                            and output_tokens >= 0
                                                            and total_tokens >= 0
                                                            and cost_usd >= 0
                                                            and assistant_message_count >= 0
                                                            and failed_message_count >= 0
                                                        ),

                                                constraint chk_usage_daily_user_model_updated_after_created
                                                    check (updated_at >= created_at)
);


create index idx_organization_ai_quotas_enabled
    on organization_ai_quotas (enabled);


create index idx_user_ai_quotas_enabled
    on user_ai_quotas (enabled);


create index idx_usage_daily_org_model_organization_date
    on usage_daily_org_model_rollups (organization_id, usage_date desc);


create index idx_usage_daily_org_model_model_date
    on usage_daily_org_model_rollups (model, usage_date desc);


create index idx_usage_daily_user_model_organization_user_date
    on usage_daily_user_model_rollups (organization_id, user_id, usage_date desc);


create index idx_usage_daily_user_model_user_date
    on usage_daily_user_model_rollups (user_id, usage_date desc);


create index idx_usage_daily_user_model_model_date
    on usage_daily_user_model_rollups (model, usage_date desc);


create trigger trg_organization_ai_quotas_updated_at
    before update on organization_ai_quotas
    for each row
    execute function set_updated_at();


create trigger trg_user_ai_quotas_updated_at
    before update on user_ai_quotas
    for each row
    execute function set_updated_at();


create trigger trg_usage_daily_org_model_rollups_updated_at
    before update on usage_daily_org_model_rollups
    for each row
    execute function set_updated_at();


create trigger trg_usage_daily_user_model_rollups_updated_at
    before update on usage_daily_user_model_rollups
    for each row
    execute function set_updated_at();