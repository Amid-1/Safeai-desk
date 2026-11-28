// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/erasure/ChatExecutionPermitVerifierTest.java
package ru.safeai.gateway.erasure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatExecutionPermitVerifierTest {
    @Test
    void signedEnvelopeAndEvidenceFailClosedAcrossTamperingAndMissingFacts() throws Exception {
        assertThat(ChatExecutionPermitChecks.runAll()).isGreaterThanOrEqualTo(100);
    }
}
