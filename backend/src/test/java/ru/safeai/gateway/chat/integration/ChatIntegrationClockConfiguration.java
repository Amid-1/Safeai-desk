package ru.safeai.gateway.chat.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An injected, deterministic test clock. It behaves as a fixed clock unless a
 * test explicitly advances it to simulate expired leases. Never use in prod.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ChatIntegrationClockConfiguration {

    @Bean
    @Primary
    Clock chatIntegrationClock() {
        return new AdjustableClock(
                AbstractChatPostgresIntegrationTest.NOW,
                ZoneOffset.UTC
        );
    }

    public static final class AdjustableClock extends Clock {

        private final Instant initialInstant;
        private final ZoneId zone;
        private final AtomicReference<Instant> current;

        private AdjustableClock(Instant initialInstant, ZoneId zone) {
            this.initialInstant = Objects.requireNonNull(initialInstant);
            this.zone = Objects.requireNonNull(zone);
            this.current = new AtomicReference<>(initialInstant);
        }

        public void reset() {
            current.set(initialInstant);
        }

        public void advanceTo(Instant next) {
            Objects.requireNonNull(next, "next");
            current.updateAndGet(previous -> {
                if (!next.isAfter(previous)) {
                    throw new IllegalArgumentException(
                            "Test clock must advance strictly forward"
                    );
                }
                return next;
            });
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            AdjustableClock result = new AdjustableClock(initialInstant, zone);
            result.current.set(instant());
            return result;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
