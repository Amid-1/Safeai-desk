/* Safeai-desk/backend/src/main/resources/db/migration/V17__ai_usage_and_pricing_metadata.sql */
-- V17: explicit AI usage and pricing semantics.
-- V17: explicit AI usage and pricing semantics.
--
-- If V17 is already occupied in the project, rename this file to the next
-- unused Flyway version before the first application.
-- Never edit a migration that has already been applied.

-- V17: explicit AI usage and pricing semantics.
--
-- If V17 is already occupied in the project, rename this file to the next
-- unused Flyway version before the first application.
-- Never edit a migration that has already been applied.

-- ---------------------------------------------------------------------------
-- 1. Add explicit AI usage and pricing metadata.
-- ---------------------------------------------------------------------------

alter table chat_messages
    add column usage_status varchar(32),
    add column pricing_status varchar(32),
    add column currency varchar(3),
    add column pricing_version varchar(64),
    add column pricing_calculated_at timestamptz,
    add column provider_message_id varchar(255),
    add column ai_response_status varchar(32),
    add column finish_reason varchar(100);

-- ---------------------------------------------------------------------------
-- 2. Remove AI metadata from rows for which provider usage is not applicable.
--
-- USER/SYSTEM messages and non-completed ASSISTANT messages must not
-- participate in usage or pricing analytics.
-- ---------------------------------------------------------------------------

update chat_messages
set model = null,
    provider_message_id = null,
    ai_response_status = null,
    finish_reason = null,
    input_tokens = null,
    output_tokens = null,
    cost_usd = null,
    currency = null,
    pricing_version = null,
    pricing_calculated_at = null
where role <> 'ASSISTANT'
   or status <> 'COMPLETED';

-- ---------------------------------------------------------------------------
-- 3. Backfill usage status for all existing rows.
-- ---------------------------------------------------------------------------

-- Intentional full-table backfill.
-- noinspection SqlWithoutWhere
update chat_messages
set usage_status = case
                       when role <> 'ASSISTANT'
                           or status <> 'COMPLETED'
                           then 'NOT_APPLICABLE'

                       when input_tokens is not null
                           and output_tokens is not null
                           then 'AVAILABLE'

                       when input_tokens is null
                           and output_tokens is null
                           then 'MISSING'

                       else 'PARTIAL'
    end;

-- ---------------------------------------------------------------------------
-- 4. Backfill pricing and provider-response metadata.
--
-- Existing positive costs are treated as legacy priced records only when
-- complete usage exists.
--
-- A zero-cost mock-safeai response is considered provably free only when
-- complete usage exists.
--
-- All other completed assistant responses are UNPRICED.
-- ---------------------------------------------------------------------------

-- Intentional full-table backfill.
-- noinspection SqlWithoutWhere
update chat_messages
set pricing_status = case
                         when role <> 'ASSISTANT'
                             or status <> 'COMPLETED'
                             then 'NOT_APPLICABLE'

                         when input_tokens is not null
                             and output_tokens is not null
                             and model = 'mock-safeai'
                             and cost_usd = 0
                             then 'FREE'

                         when input_tokens is not null
                             and output_tokens is not null
                             and cost_usd is not null
                             and cost_usd > 0
                             then 'PRICED'

                         else 'UNPRICED'
    end,

    currency = case
                   when role = 'ASSISTANT'
                       and status = 'COMPLETED'
                       and input_tokens is not null
                       and output_tokens is not null
                       and (
                            cost_usd > 0
                                or (
                                model = 'mock-safeai'
                                    and cost_usd = 0
                                )
                            )
                       then 'USD'
        end,

    pricing_version = case
                          when role = 'ASSISTANT'
                              and status = 'COMPLETED'
                              and input_tokens is not null
                              and output_tokens is not null
                              and (
                                   cost_usd > 0
                                       or (
                                       model = 'mock-safeai'
                                           and cost_usd = 0
                                       )
                                   )
                              then 'legacy-v1'
        end,

    pricing_calculated_at = case
                                when role = 'ASSISTANT'
                                    and status = 'COMPLETED'
                                    then created_at
        end,

    ai_response_status = case
                             when role = 'ASSISTANT'
                                 and status = 'COMPLETED'
                                 then 'COMPLETED'
        end;

-- A missing pricing rule or failed calculation must not be represented
-- as a real zero monetary cost.
update chat_messages
set cost_usd = null,
    currency = null,
    pricing_version = null
where pricing_status in (
                         'UNPRICED',
                         'CALCULATION_FAILED'
    );

-- ---------------------------------------------------------------------------
-- 5. Make the new status columns mandatory.
-- ---------------------------------------------------------------------------

alter table chat_messages
    alter column usage_status set not null,
    alter column pricing_status set not null;

-- ---------------------------------------------------------------------------
-- 6. Add status-domain and metadata-consistency constraints.
-- ---------------------------------------------------------------------------

alter table chat_messages
    add constraint chk_chat_messages_usage_status
        check (
            usage_status in (
                             'NOT_APPLICABLE',
                             'AVAILABLE',
                             'MISSING',
                             'PARTIAL'
                )
            ),

    add constraint chk_chat_messages_pricing_status
        check (
            pricing_status in (
                               'NOT_APPLICABLE',
                               'PRICED',
                               'FREE',
                               'UNPRICED',
                               'CALCULATION_FAILED'
                )
            ),

    add constraint chk_chat_messages_ai_response_status
        check (
            ai_response_status is null
                or ai_response_status in (
                                          'COMPLETED',
                                          'REFUSED',
                                          'INCOMPLETE'
                )
            ),

    add constraint chk_chat_messages_currency
        check (
            currency is null
                or currency ~ '^[A-Z]{3}$'
            ),

    add constraint chk_chat_messages_usage_consistency
        check (
            (
                usage_status = 'NOT_APPLICABLE'
                    and input_tokens is null
                    and output_tokens is null
                )
                or
            (
                usage_status = 'AVAILABLE'
                    and input_tokens is not null
                    and output_tokens is not null
                )
                or
            (
                usage_status = 'MISSING'
                    and input_tokens is null
                    and output_tokens is null
                )
                or
            (
                usage_status = 'PARTIAL'
                    and (
                    (input_tokens is null)
                        <>
                    (output_tokens is null)
                    )
                )
            ),

    add constraint chk_chat_messages_pricing_consistency
        check (
            (
                pricing_status = 'NOT_APPLICABLE'
                    and cost_usd is null
                    and currency is null
                    and pricing_version is null
                    and pricing_calculated_at is null
                )
                or
            (
                pricing_status in (
                                   'PRICED',
                                   'FREE'
                    )
                    and usage_status = 'AVAILABLE'
                    and cost_usd is not null
                    and currency is not null
                    and pricing_version is not null
                    and pricing_calculated_at is not null
                )
                or
            (
                pricing_status in (
                                   'UNPRICED',
                                   'CALCULATION_FAILED'
                    )
                    and cost_usd is null
                    and currency is null
                    and pricing_version is null
                    and pricing_calculated_at is not null
                )
            ),

    add constraint chk_chat_messages_free_zero
        check (
            pricing_status <> 'FREE'
                or cost_usd = 0
            ),

    add constraint chk_chat_messages_completed_assistant_response_status
        check (
            role <> 'ASSISTANT'
                or status <> 'COMPLETED'
                or ai_response_status is not null
            ),

    add constraint chk_chat_messages_non_ai_metadata
        check (
            (
                role = 'ASSISTANT'
                    and status = 'COMPLETED'
                )
                or
            (
                usage_status = 'NOT_APPLICABLE'
                    and pricing_status = 'NOT_APPLICABLE'
                    and model is null
                    and provider_message_id is null
                    and ai_response_status is null
                    and finish_reason is null
                )
            );

-- ---------------------------------------------------------------------------
-- 7. Add partial indexes for monitoring incomplete usage and pricing states.
-- ---------------------------------------------------------------------------

create index idx_chat_messages_usage_status_created_at
    on chat_messages (
                      usage_status,
                      created_at
        )
    where role = 'ASSISTANT'
        and status = 'COMPLETED';

create index idx_chat_messages_pricing_status_created_at
    on chat_messages (
                      pricing_status,
                      created_at
        )
    where role = 'ASSISTANT'
        and status = 'COMPLETED';

