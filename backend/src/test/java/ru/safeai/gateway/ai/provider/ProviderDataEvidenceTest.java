package ru.safeai.gateway.ai.provider;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ProviderDataEvidenceTest {
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private static final String BINDING = ProviderDataEvidence.fingerprint(
            "chat", "openai", "https://api.openai.com/v1", "test-model", "test-key", "false");

    @Test
    void exactBindingAndValidityWindowAreRequired() {
        var evidence = new ProviderDataEvidence("CONTRACT-42", NOW, NOW.plusSeconds(60),
                BINDING, true, true);
        assertFalse(evidence.evaluate(NOW.minusNanos(1), BINDING, true).noTraining());
        assertTrue(evidence.evaluate(NOW, BINDING, true).noTraining());
        assertTrue(evidence.evaluate(NOW.plusSeconds(59), BINDING, true).zeroDataRetention());
        assertFalse(evidence.evaluate(NOW.plusSeconds(60), BINDING, true).zeroDataRetention());
        assertFalse(evidence.evaluate(NOW, ProviderDataEvidence.fingerprint(
                "chat", "openai", "https://api.openai.com/v1", "test-model", "rotated-key", "false"), true).noTraining());
        assertFalse(evidence.evaluate(NOW, ProviderDataEvidence.fingerprint(
                "embedding", "openai", "https://api.openai.com/v1", "test-model", "test-key", "false"), true).zeroDataRetention());
        assertFalse(evidence.evaluate(NOW, ProviderDataEvidence.fingerprint(
                "chat", "openai", "https://api.openai.com/v1", "other-model", "test-key", "false"), true).noTraining());
        assertFalse(evidence.evaluate(NOW, ProviderDataEvidence.fingerprint(
                "chat", "openai", "https://other.example/v1", "test-model", "test-key", "false"), true).noTraining());
        assertTrue(evidence.evaluate(NOW, BINDING, false).noTraining());
        assertFalse(evidence.evaluate(NOW, BINDING, false).zeroDataRetention());
        assertEquals("CONTRACT-42", evidence.evaluate(NOW.plusSeconds(60), BINDING, true).source());
        assertFalse(evidence.toString().contains(BINDING));
    }

    @Test
    void invalidEvidenceFailsAtConfigurationBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderDataEvidence(
                "", NOW, NOW.plusSeconds(1), BINDING, true, true));
        assertThrows(IllegalArgumentException.class, () -> new ProviderDataEvidence(
                "DOC-REF", NOW, NOW, BINDING, true, true));
        assertThrows(IllegalArgumentException.class, () -> new ProviderDataEvidence(
                "DOC-REF", NOW, NOW.plus(Duration.ofDays(91)), BINDING, true, true));
        assertThrows(IllegalArgumentException.class, () -> new ProviderDataEvidence(
                "DOC-REF", NOW, NOW.plusSeconds(30), "bad", true, true));
        assertNotEquals(ProviderDataEvidence.fingerprint("ab", "c"),
                ProviderDataEvidence.fingerprint("a", "bc"));
    }
}
