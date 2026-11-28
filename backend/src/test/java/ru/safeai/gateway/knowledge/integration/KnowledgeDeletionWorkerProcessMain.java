package ru.safeai.gateway.knowledge.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import ru.safeai.gateway.knowledge.testsupport.KnowledgeErasureTestJournal;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseDeletionExecutor;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionExecutor;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageProperties;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageType;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;
import ru.safeai.gateway.knowledge.storage.S3ObjectStorage;
import ru.safeai.gateway.knowledge.storage.StoredObject;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/**
 * Separate JVM for the Testcontainers-only OS-process crash/restart test.
 * Not a Spring application and never registered as a bean or production entry point.
 * Refuses URLs or buckets that do not look like disposable test infrastructure.
 */
public final class KnowledgeDeletionWorkerProcessMain {

    private KnowledgeDeletionWorkerProcessMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !("crash".equals(args[0]) || "recover".equals(args[0]))) {
            throw new IllegalArgumentException("Expected crash or recover");
        }
        String mode = args[0];
        String jdbcUrl = required("SAFEAI_SIGKILL_TEST_JDBC_URL");
        String endpoint = required("SAFEAI_SIGKILL_TEST_S3_ENDPOINT");
        String bucket = required("SAFEAI_SIGKILL_TEST_S3_BUCKET");
        String expectedKey = required("SAFEAI_SIGKILL_TEST_KEY");
        String nonce = required("SAFEAI_SIGKILL_TEST_NONCE");
        UUID childOperationId = requiredUuid("SAFEAI_SIGKILL_TEST_CHILD_OPERATION_ID");
        UUID baseOperationId = requiredUuid("SAFEAI_SIGKILL_TEST_BASE_OPERATION_ID");
        if (!"DISPOSABLE_TESTCONTAINERS_ONLY".equals(required("SAFEAI_SIGKILL_TEST_GUARD"))
                || !jdbcUrl.matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/safeai_test(?:\\?.*)?")
                || !endpoint.matches("http://(?:localhost|127\\.0\\.0\\.1):[0-9]+")
                || !bucket.matches("safeai-process-crash-[a-f0-9]{32}")
                || !expectedKey.matches("safeai-e2e-kill/[a-f0-9-]{36}/target")
                || !nonce.matches("[a-f0-9-]{36}")) {
            throw new IllegalStateException("Refusing worker outside disposable test infrastructure");
        }

        DriverManagerDataSource source = new DriverManagerDataSource(
                jdbcUrl, required("SAFEAI_SIGKILL_TEST_DB_USER"),
                required("SAFEAI_SIGKILL_TEST_DB_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(source);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(source);
        S3ObjectStorage realStorage = new S3ObjectStorage(new KnowledgeStorageProperties(
                KnowledgeStorageType.S3, null, 26_214_400L, endpoint,
                required("SAFEAI_SIGKILL_TEST_S3_USER"),
                required("SAFEAI_SIGKILL_TEST_S3_PASSWORD"), bucket));
        try {
            ObjectStorage storage = "crash".equals(mode)
                    ? new HaltAfterVerifiedPurge(realStorage, expectedKey, nonce)
                    : realStorage;
            KnowledgeDocumentDeletionExecutor documentExecutor =
                    new KnowledgeDocumentDeletionExecutor(
                            jdbc, transactionManager, storage, null, KnowledgeErasureTestJournal.INSTANCE);
            documentExecutor.processOperation(childOperationId);
            if ("crash".equals(mode)) {
                throw new IllegalStateException(
                        "Crash phase returned without observed purge for operation " + childOperationId);
            }
            KnowledgeBaseDeletionExecutor baseExecutor =
                    new KnowledgeBaseDeletionExecutor(
                            jdbc, transactionManager, true, null, KnowledgeErasureTestJournal.INSTANCE);
            baseExecutor.processOperation(baseOperationId);
            System.out.println("SAFEAI_TEST_RECOVERED " + nonce);
            System.out.flush();
        } finally {
            // Only test resources: production S3ObjectStorage.closeClient is package-private.
            Method close = S3ObjectStorage.class.getDeclaredMethod("closeClient");
            close.setAccessible(true);
            close.invoke(realStorage);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing disposable-test setting: " + name);
        }
        return value;
    }

    private static UUID requiredUuid(String name) {
        String value = required(name);
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalStateException("Invalid disposable-test UUID setting: " + name);
        }
        return UUID.fromString(value);
    }

    private record HaltAfterVerifiedPurge(S3ObjectStorage delegate, String expectedKey, String nonce)
            implements ObjectStorage {
        @Override public void put(String key, InputStream content) {
            throw new UnsupportedOperationException("Test worker is purge-only");
        }
        @Override public StoredObject get(String key) {
            throw new UnsupportedOperationException("Test worker is purge-only");
        }
        @Override public void delete(String key) {
            throw new UnsupportedOperationException("Test worker is purge-only");
        }
        @Override public void purgeAllVersionsVerified(String key) throws IOException {
            if (!expectedKey.equals(key)) {
                throw new IllegalStateException("Unexpected purge key in disposable test");
            }
            // Actual, version-aware S3 deletion; delegate returns only after verification.
            delegate.purgeAllVersionsVerified(key);
            // The controlling JUnit JVM observes this line, verifies durable state,
            // then forcibly kills this OS process. Do not throw/retry here.
            System.out.println("SAFEAI_TEST_PURGED " + nonce);
            System.out.flush();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Unexpected interruption before SIGKILL", interrupted);
            }
        }
    }
}
