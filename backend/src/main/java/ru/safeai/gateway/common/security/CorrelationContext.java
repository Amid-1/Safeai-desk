package ru.safeai.gateway.common.security;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

import java.util.UUID;

/** Server-owned request ID; never derived from an incoming HTTP header. */
public final class CorrelationContext {

    private CorrelationContext() {
    }

    public static @Nullable UUID current() {
        String value = MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY);
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equals(value) ? id : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public static UUID currentOrNew() {
        UUID current = current();
        return current == null ? UUID.randomUUID() : current;
    }

    /** Restore a persisted operation in a worker or nested recovery scope. */
    public static Scope open(@Nullable UUID id) {
        String previous = MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY);
        String previousClient = MDC.get(RequestIdFilter.CLIENT_REQUEST_ID_MDC_KEY);
        restore(RequestIdFilter.REQUEST_ID_MDC_KEY, id == null ? null : id.toString());
        MDC.remove(RequestIdFilter.CLIENT_REQUEST_ID_MDC_KEY);
        return () -> {
            restore(RequestIdFilter.REQUEST_ID_MDC_KEY, previous);
            restore(RequestIdFilter.CLIENT_REQUEST_ID_MDC_KEY, previousClient);
        };
    }

    private static void restore(String key, @Nullable String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
