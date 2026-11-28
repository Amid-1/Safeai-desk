package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.chat.service.ChatErasureLimits;

import static org.assertj.core.api.Assertions.assertThat;

class ChatErasureLimitsTest {
    @Test void atomicAcceptanceAndWorkerBoundAreIdentical() {
        assertThat(ChatErasureLimits.BATCH_SIZE).isEqualTo(100);
        assertThat(ChatErasureLimits.MAX_BATCHES).isEqualTo(100);
        assertThat(ChatErasureLimits.MAX_ROWS_PER_KIND)
            .isEqualTo(ChatErasureLimits.BATCH_SIZE*ChatErasureLimits.MAX_BATCHES)
            .isEqualTo(10_000);
    }
}
