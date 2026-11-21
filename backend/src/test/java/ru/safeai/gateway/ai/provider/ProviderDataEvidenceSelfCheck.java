package ru.safeai.gateway.ai.provider;

import java.time.Instant;

/** Dependency-free cross-check for environments unable to resolve Maven artifacts. */
public final class ProviderDataEvidenceSelfCheck {
    public static void main(String[] args) {
        Instant now = Instant.parse("2026-11-17T12:00:00Z");
        String binding = ProviderDataEvidence.fingerprint("chat", "openai",
                "https://api.openai.com/v1", "test-model", "test-key", "false");
        var proof = new ProviderDataEvidence("DOC-TEST", now, now.plusSeconds(60), binding, true, true);
        check(proof.evaluate(now, binding, true).zeroDataRetention(), "matching");
        check(!proof.evaluate(now.minusNanos(1), binding, true).noTraining(), "future proof");
        check(!proof.evaluate(now.plusSeconds(60), binding, true).noTraining(), "expired");
        check(!proof.evaluate(now, binding, false).zeroDataRetention(), "incompatible store");
        check(proof.evaluate(now, binding, false).noTraining(), "independent training");
        check(!proof.evaluate(now, "0".repeat(64), true).noTraining(), "mismatched binding");
        check(!proof.toString().contains(binding), "redacted binding");
        check(!ProviderDataEvidence.fingerprint("ab", "c").equals(
                ProviderDataEvidence.fingerprint("a", "bc")), "length separation");
        if (args.length == 1) check(binding.equals(args[0]), "operator tool parity");
        System.out.println("Provider data evidence self-check passed (9 checks with operator parity).");
    }

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }
}
