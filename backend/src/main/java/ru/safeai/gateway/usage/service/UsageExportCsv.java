// backend/src/main/java/ru/safeai/gateway/usage/service/UsageExportCsv.java
package ru.safeai.gateway.usage.service;

import ru.safeai.gateway.usage.dto.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/** CSV с точными числами и защитой текстовых ячеек от формул электронных таблиц. */
final class UsageExportCsv {
    static final int MAX_ROWS = 10_000;
    static final int MAX_BYTES = 8 * 1024 * 1024;

    private UsageExportCsv() {}

    static byte[] encode(UsageExportRequest request, List<?> rows, Instant generatedAt) {
        if (rows.size() > MAX_ROWS) throw new LimitExceeded();
        var out = new ByteArrayOutputStream();
        append(out, "\ufeff");
        line(out, "report", "date_from_inclusive", "date_to_exclusive", "organization_id", "user_id",
                "model", "budget_status", "budget_month_utc", "order", "generated_at", "rows", "currency");
        String order = switch (request.kind()) {
            case DAILY -> "date-desc";
            case MODELS -> "model-asc";
            case PROVIDERS -> "provider-asc";
            default -> request.order() == null ? "tokens" : request.order();
        };
        line(out, request.kind(), request.dateFrom(), request.dateTo(), request.organizationId(),
                request.userId(), request.model(), request.budgetStatus(),
                request.budgetStatus() == null ? null : YearMonth.from(generatedAt.atOffset(ZoneOffset.UTC)),
                order, generatedAt, rows.size(), "USD");
        if (request.kind() == UsageExportRequest.Kind.PROVIDERS) {
            line(out, "provider", "attempts", "unresolved", "unknown_cost", "unknown_usage",
                    "known_input", "known_output", "known_cost_usd");
            for (Object value : rows) {
                var row = (UsageProviderRow) value;
                line(out, row.provider(), row.attempts(), row.unresolved(), row.unknownCost(),
                        row.unknownUsage(), row.unknownUsage().equals(row.attempts()) ? null : row.input(),
                        row.unknownUsage().equals(row.attempts()) ? null : row.output(),
                        row.unknownCost().equals(row.attempts()) ? null : row.knownCostUsd());
            }
        } else {
            line(out, "id", "label", "model", "assistant_messages", "completed", "refused", "incomplete",
                    "failed", "unclassified", "confirmed_input", "confirmed_output", "confirmed_total",
                    "partial_input", "partial_output", "partial_total", "usage_available", "usage_partial",
                    "usage_missing", "usage_not_applicable", "usage_complete", "known_cost_usd", "priced",
                    "free", "unpriced", "pricing_failed", "pricing_not_applicable", "pricing_complete");
            for (Object value : rows) {
                switch (value) {
                    case UsageSummaryResponse r -> amounts(out, r.userId(), r.currentUserEmail(), r.model(),
                            r.responses(), r.usage(), r.cost());
                    case UsageUserSummaryResponse r -> amounts(out, r.userId(), r.currentUserEmail(), null,
                            r.responses(), r.usage(), r.cost());
                    case UsageModelSummaryResponse r -> amounts(out, null, null, r.model(),
                            r.responses(), r.usage(), r.cost());
                    case UsageDailySummaryResponse r -> amounts(out, r.usageDate(), null, null,
                            r.responses(), r.usage(), r.cost());
                    case UsageOrganizationSummaryResponse r -> {
                        var c = r.cost();
                        var u = r.usage();
                        var a = r.responses();
                        amounts(out, r.organizationId(), r.organizationName(), null, a, u,
                                c.knownCostUsd(), c.pricedMessages(), c.freeMessages(), c.unpricedMessages(),
                                c.pricingFailedMessages(), c.pricingNotApplicableMessages(), c.pricingComplete());
                    }
                    default -> throw new IllegalArgumentException("Неизвестный тип отчёта");
                }
            }
        }
        return out.toByteArray();
    }

    private static void amounts(ByteArrayOutputStream out, Object id, String label, String model,
                                UsageResponseSummary a, UsageTokenSummary u, UsageCostSummary c) {
        String known = c.pricedMessages() > 0 || c.freeMessages() > 0
                || (c.pricingComplete() && c.pricingNotApplicableMessages() > 0)
                ? c.knownCostUsd().toPlainString() : null;
        amounts(out, id, label, model, a, u, known, c.pricedMessages(), c.freeMessages(), c.unpricedMessages(),
                c.pricingFailedMessages(), c.pricingNotApplicableMessages(), c.pricingComplete());
    }

    private static void amounts(ByteArrayOutputStream out, Object id, String label, String model,
                                UsageResponseSummary a, UsageTokenSummary u, String cost, long priced,
                                long free, long unpriced, long failed, long notApplicable, boolean complete) {
        line(out, id, label, model, a.assistantMessages(), a.completedResponses(), a.refusedResponses(),
                a.incompleteResponses(), a.failedMessages(), a.unclassifiedMessages(),
                u.confirmedInputTokens(), u.confirmedOutputTokens(), u.confirmedTotalTokens(),
                u.partialKnownInputTokens(), u.partialKnownOutputTokens(), u.partialKnownTotalTokens(),
                u.availableUsageMessages(), u.partialUsageMessages(), u.missingUsageMessages(),
                u.usageNotApplicableMessages(), u.usageComplete(), cost, priced, free, unpriced, failed,
                notApplicable, complete);
    }

    private static void line(ByteArrayOutputStream out, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) append(out, ";");
            append(out, cell(values[index]));
        }
        append(out, "\r\n");
    }

    static String cell(Object value) {
        String text = value == null ? "" : value.toString();
        int first = text.codePoints().dropWhile(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)
                || Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                .findFirst().orElse(-1);
        if ((!text.isEmpty() && "\t\r\n".indexOf(text.charAt(0)) >= 0)
                || (first >= 0 && "=+-@＝＋－＠".indexOf(first) >= 0)) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static void append(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES - out.size()) throw new LimitExceeded();
        out.writeBytes(bytes);
    }

    static final class LimitExceeded extends RuntimeException {}
}
