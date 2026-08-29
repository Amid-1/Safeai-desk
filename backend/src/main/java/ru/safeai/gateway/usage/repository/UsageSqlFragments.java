package ru.safeai.gateway.usage.repository;

final class UsageSqlFragments {

    private UsageSqlFragments() {
    }

    static final String UNATTRIBUTED_MODEL =
            "__unattributed__";

    static final String AGGREGATE_COLUMNS = """
            cast(coalesce(sum(assistant_message_count), 0) as bigint)
                as assistant_message_count,
            cast(coalesce(sum(completed_response_count), 0) as bigint)
                as completed_response_count,
            cast(coalesce(sum(refused_response_count), 0) as bigint)
                as refused_response_count,
            cast(coalesce(sum(incomplete_response_count), 0) as bigint)
                as incomplete_response_count,
            cast(coalesce(sum(failed_message_count), 0) as bigint)
                as failed_message_count,
            cast(coalesce(sum(input_tokens), 0) as bigint)
                as input_tokens,
            cast(coalesce(sum(output_tokens), 0) as bigint)
                as output_tokens,
            cast(coalesce(sum(partial_input_tokens), 0) as bigint)
                as partial_input_tokens,
            cast(coalesce(sum(partial_output_tokens), 0) as bigint)
                as partial_output_tokens,
            cast(coalesce(sum(available_usage_message_count), 0) as bigint)
                as available_usage_message_count,
            cast(coalesce(sum(partial_usage_message_count), 0) as bigint)
                as partial_usage_message_count,
            cast(coalesce(sum(missing_usage_message_count), 0) as bigint)
                as missing_usage_message_count,
            cast(coalesce(sum(usage_not_applicable_message_count), 0) as bigint)
                as usage_not_applicable_message_count,
            coalesce(sum(cost_usd), 0) as known_cost_usd,
            cast(coalesce(sum(priced_message_count), 0) as bigint)
                as priced_message_count,
            cast(coalesce(sum(free_message_count), 0) as bigint)
                as free_message_count,
            cast(coalesce(sum(unpriced_message_count), 0) as bigint)
                as unpriced_message_count,
            cast(coalesce(sum(pricing_failed_message_count), 0) as bigint)
                as pricing_failed_message_count,
            cast(coalesce(sum(pricing_not_applicable_message_count), 0) as bigint)
                as pricing_not_applicable_message_count
            """;

    static final String LIVE_AGGREGATE_COLUMNS = """
            count(*)::bigint as assistant_message_count,
            count(*) filter (
                where m.status = 'COMPLETED'
                  and m.ai_response_status = 'COMPLETED'
            )::bigint as completed_response_count,
            count(*) filter (
                where m.status = 'COMPLETED'
                  and m.ai_response_status = 'REFUSED'
            )::bigint as refused_response_count,
            count(*) filter (
                where m.status = 'COMPLETED'
                  and m.ai_response_status = 'INCOMPLETE'
            )::bigint as incomplete_response_count,
            count(*) filter (
                where m.status = 'FAILED'
            )::bigint as failed_message_count,
            coalesce(sum(
                case
                    when m.usage_status = 'AVAILABLE'
                        then coalesce(m.input_tokens, 0)
                    else 0
                end
            ), 0)::bigint as input_tokens,
            coalesce(sum(
                case
                    when m.usage_status = 'AVAILABLE'
                        then coalesce(m.output_tokens, 0)
                    else 0
                end
            ), 0)::bigint as output_tokens,
            coalesce(sum(
                case
                    when m.usage_status = 'PARTIAL'
                        then coalesce(m.input_tokens, 0)
                    else 0
                end
            ), 0)::bigint as partial_input_tokens,
            coalesce(sum(
                case
                    when m.usage_status = 'PARTIAL'
                        then coalesce(m.output_tokens, 0)
                    else 0
                end
            ), 0)::bigint as partial_output_tokens,
            count(*) filter (
                where m.usage_status = 'AVAILABLE'
            )::bigint as available_usage_message_count,
            count(*) filter (
                where m.usage_status = 'PARTIAL'
            )::bigint as partial_usage_message_count,
            count(*) filter (
                where m.usage_status = 'MISSING'
            )::bigint as missing_usage_message_count,
            count(*) filter (
                where m.usage_status = 'NOT_APPLICABLE'
            )::bigint as usage_not_applicable_message_count,
            coalesce(sum(
                case
                    when m.pricing_status in ('PRICED', 'FREE')
                        then coalesce(m.cost_usd, 0)
                    else 0
                end
            ), 0) as cost_usd,
            count(*) filter (
                where m.pricing_status = 'PRICED'
            )::bigint as priced_message_count,
            count(*) filter (
                where m.pricing_status = 'FREE'
            )::bigint as free_message_count,
            count(*) filter (
                where m.pricing_status = 'UNPRICED'
            )::bigint as unpriced_message_count,
            count(*) filter (
                where m.pricing_status = 'CALCULATION_FAILED'
            )::bigint as pricing_failed_message_count,
            count(*) filter (
                where m.pricing_status = 'NOT_APPLICABLE'
            )::bigint as pricing_not_applicable_message_count
            """;

    static final String AGGREGATE_COLUMNS_WITHOUT_ASSISTANT = """
            cast(coalesce(sum(completed_response_count), 0) as bigint)
                as completed_response_count,
            cast(coalesce(sum(refused_response_count), 0) as bigint)
                as refused_response_count,
            cast(coalesce(sum(incomplete_response_count), 0) as bigint)
                as incomplete_response_count,
            cast(coalesce(sum(failed_message_count), 0) as bigint)
                as failed_message_count,
            cast(coalesce(sum(input_tokens), 0) as bigint)
                as input_tokens,
            cast(coalesce(sum(output_tokens), 0) as bigint)
                as output_tokens,
            cast(coalesce(sum(partial_input_tokens), 0) as bigint)
                as partial_input_tokens,
            cast(coalesce(sum(partial_output_tokens), 0) as bigint)
                as partial_output_tokens,
            cast(coalesce(sum(available_usage_message_count), 0) as bigint)
                as available_usage_message_count,
            cast(coalesce(sum(partial_usage_message_count), 0) as bigint)
                as partial_usage_message_count,
            cast(coalesce(sum(missing_usage_message_count), 0) as bigint)
                as missing_usage_message_count,
            cast(coalesce(sum(usage_not_applicable_message_count), 0) as bigint)
                as usage_not_applicable_message_count,
            coalesce(sum(cost_usd), 0) as known_cost_usd,
            cast(coalesce(sum(priced_message_count), 0) as bigint)
                as priced_message_count,
            cast(coalesce(sum(free_message_count), 0) as bigint)
                as free_message_count,
            cast(coalesce(sum(unpriced_message_count), 0) as bigint)
                as unpriced_message_count,
            cast(coalesce(sum(pricing_failed_message_count), 0) as bigint)
                as pricing_failed_message_count,
            cast(coalesce(sum(pricing_not_applicable_message_count), 0) as bigint)
                as pricing_not_applicable_message_count
            """;

    static final String LIVE_AGGREGATE_COLUMNS_WITHOUT_ASSISTANT = """
            count(*) filter (
                where m.status = 'COMPLETED'
                  and m.ai_response_status = 'COMPLETED'
            )::bigint as completed_response_count,
            count(*) filter (
                where m.status = 'COMPLETED'
                  and m.ai_response_status = 'REFUSED'
            )::bigint as refused_response_count,
            count(*) filter (
                where m.status = 'COMPLETED'
                  and m.ai_response_status = 'INCOMPLETE'
            )::bigint as incomplete_response_count,
            count(*) filter (
                where m.status = 'FAILED'
            )::bigint as failed_message_count,
            coalesce(sum(
                case
                    when m.usage_status = 'AVAILABLE'
                        then coalesce(m.input_tokens, 0)
                    else 0
                end
            ), 0)::bigint as input_tokens,
            coalesce(sum(
                case
                    when m.usage_status = 'AVAILABLE'
                        then coalesce(m.output_tokens, 0)
                    else 0
                end
            ), 0)::bigint as output_tokens,
            coalesce(sum(
                case
                    when m.usage_status = 'PARTIAL'
                        then coalesce(m.input_tokens, 0)
                    else 0
                end
            ), 0)::bigint as partial_input_tokens,
            coalesce(sum(
                case
                    when m.usage_status = 'PARTIAL'
                        then coalesce(m.output_tokens, 0)
                    else 0
                end
            ), 0)::bigint as partial_output_tokens,
            count(*) filter (
                where m.usage_status = 'AVAILABLE'
            )::bigint as available_usage_message_count,
            count(*) filter (
                where m.usage_status = 'PARTIAL'
            )::bigint as partial_usage_message_count,
            count(*) filter (
                where m.usage_status = 'MISSING'
            )::bigint as missing_usage_message_count,
            count(*) filter (
                where m.usage_status = 'NOT_APPLICABLE'
            )::bigint as usage_not_applicable_message_count,
            coalesce(sum(
                case
                    when m.pricing_status in ('PRICED', 'FREE')
                        then coalesce(m.cost_usd, 0)
                    else 0
                end
            ), 0) as cost_usd,
            count(*) filter (
                where m.pricing_status = 'PRICED'
            )::bigint as priced_message_count,
            count(*) filter (
                where m.pricing_status = 'FREE'
            )::bigint as free_message_count,
            count(*) filter (
                where m.pricing_status = 'UNPRICED'
            )::bigint as unpriced_message_count,
            count(*) filter (
                where m.pricing_status = 'CALCULATION_FAILED'
            )::bigint as pricing_failed_message_count,
            count(*) filter (
                where m.pricing_status = 'NOT_APPLICABLE'
            )::bigint as pricing_not_applicable_message_count
            """;
}
