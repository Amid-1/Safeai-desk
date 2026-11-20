package ru.safeai.gateway.model.service;

import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.dto.ModelPolicyPreviewItemResponse;
import ru.safeai.gateway.model.dto.ModelPolicyPreviewResponse;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Версионированное, однозначное кодирование: null, длина, значение; списки отсортированы. */
final class ModelPolicyPreviewFingerprint {
    private final MessageDigest digest;

    private ModelPolicyPreviewFingerprint(String domain) {
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        add(domain);
    }

    static String token(UUID token) {
        return new ModelPolicyPreviewFingerprint("safeai-policy-token-v1").add(token).finish();
    }

    static String draft(CreateOrganizationModelPolicyVersionRequest r) {
        var f = new ModelPolicyPreviewFingerprint("safeai-policy-draft-v1")
                .add(r.expectedPreviousVersion()).add(r.enabled()).keys(r.allowModelKeys()).keys(r.denyModelKeys())
                .add(r.defaultModelKey()).add(r.maxInputTokens()).add(r.maxOutputTokens())
                .add(r.maxRequestCostUsd()).add(r.monthlyBudgetUsd()).add(r.budgetEnforcement())
                .add(r.requireCompletePricing()).add(r.requireNoTraining()).add(r.requireZeroDataRetention());
        var rub = r.rubBudget();
        f.add(rub != null);
        if (rub != null) f.add(rub.maxRequestCostRub()).add(rub.monthlyBudgetRub())
                .add(rub.rubPerUsd()).add(rub.quoteDate()).add(rub.effectiveDate());
        return f.finish();
    }

    static String context(ModelPolicyPreviewResponse r) {
        var f = new ModelPolicyPreviewFingerprint("safeai-policy-context-v1")
                .add(r.organizationId()).add(r.basePolicyVersion()).add(r.enabled())
                .add(r.runtimeProvider()).add(r.runtimeModel()).add(r.automaticOutcome())
                .add(r.automaticReason()).add(r.automaticModelKey()).add(r.executableModelCount())
                .add(r.wouldLockOutOrganization()).add(r.models().size());
        // Бюджетные суммы меняются естественно; значимые решения, состояния и лимиты — нет.
        r.models().stream().sorted(Comparator.comparing(ModelPolicyPreviewItemResponse::modelKey)).forEach(m -> f
                .add(m.modelKey()).add(m.catalogVersion()).add(m.provider()).add(m.providerModelId())
                .add(m.lifecycle()).add(m.outcome()).add(m.reason()).add(m.effectiveInputLimit())
                .add(m.effectiveOutputLimit()).add(m.pricingComplete()).add(m.estimatedMaxCostUsd())
                .add(m.monthlyBudgetUsd()).add(m.monthlyCostKnown()).add(m.monthlyCostState())
                .add(m.monthlyCommitmentState()).add(m.budgetExceeded()));
        return f.finish();
    }

    private ModelPolicyPreviewFingerprint keys(Set<String> values) {
        if (values == null) return add(null);
        add(values.size());
        values.stream().sorted(Comparator.nullsFirst(Comparator.naturalOrder())).forEach(this::add);
        return this;
    }

    private ModelPolicyPreviewFingerprint add(Object value) {
        byte[] bytes = value == null ? null : (value instanceof BigDecimal number
                ? number.stripTrailingZeros().toString() : value.toString()).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) digest.update(bytes);
        return this;
    }

    private String finish() { return HexFormat.of().formatHex(digest.digest()); }
}
