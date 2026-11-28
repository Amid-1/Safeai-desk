package ru.safeai.gateway.ai.provider;

import java.util.function.Supplier;

/** Monotonic, thread-confined deadline inherited by the synchronous HTTP adapter. */
final class AiProviderDispatchDeadline {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private AiProviderDispatchDeadline() {}

    static long remaining(long localDeadline) {
        long now = System.nanoTime();
        Long inherited = CURRENT.get();
        long localRemaining = localDeadline - now;
        return inherited == null ? localRemaining : Math.min(localRemaining, inherited - now);
    }

    static <T> T within(long deadline, Supplier<T> action) {
        Long previous = CURRENT.get();
        long now = System.nanoTime();
        long effective = previous != null && previous - now < deadline - now ? previous : deadline;
        CURRENT.set(effective);
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
