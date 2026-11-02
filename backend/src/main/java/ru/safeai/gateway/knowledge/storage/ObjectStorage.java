package ru.safeai.gateway.knowledge.storage;

import java.io.IOException;
import java.io.InputStream;

public interface ObjectStorage {
    void put(String key, InputStream content) throws IOException;

    StoredObject get(String key) throws IOException;

    void delete(String key) throws IOException;

    /**
     * Destructive erasure requires verification of *all* physical versions.
     * A plain delete(key) must never be used as proof of physical erasure.
     * Providers not implementing this MUST fail closed.
     */
    default void purgeAllVersionsVerified(String key) throws IOException {
        throw new IOException("Verified physical erasure is not supported by this storage provider");
    }

    /**
     * Heartbeat-aware variant for long-running erasure. Implementations should
     * invoke heartbeat between pages/batches. The default preserves compatibility
     * with test doubles/providers that implement only the verified purge method.
     */
    default void purgeAllVersionsVerified(String key, Runnable heartbeat) throws IOException {
        if (heartbeat != null) heartbeat.run();
        purgeAllVersionsVerified(key);
    }
}
