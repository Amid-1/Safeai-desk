package ru.safeai.gateway.knowledge.storage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import io.minio.BucketExistsArgs;
import io.minio.GetBucketVersioningArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectLockConfigurationArgs;
import io.minio.GetObjectRetentionArgs;
import io.minio.StatObjectResponse;
import io.minio.messages.ObjectLockConfiguration;
import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import io.minio.messages.VersioningConfiguration;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Публикует детерминированные события удаления во внешнем S3-журнале.
 *
 * <p>Журнал хранится в отдельном бакете, который инфраструктура обязана
 * резервировать, версионировать и защищать механизмом Object Lock Compliance.
 * Содержимое уже существующего события сверяется побайтно при повторной записи:
 * несовпадение считается критической ошибкой, а не успешным повтором.</p>
 *
 * <p>Сериализация выполняется через Jackson 3, используемый Spring Boot 4.
 * Не требуется дополнительный бин устаревшего Jackson 2 ObjectMapper.</p>
 */
@Component
public class KnowledgeErasureTombstoneJournal implements ErasureTombstoneJournal {

    private final boolean enabled;
    private final Duration minimumRetention;
    private final String bucket;
    private final JsonMapper mapper;
    private final MinioClient client;

    public KnowledgeErasureTombstoneJournal(
            KnowledgeStorageProperties storage,
            JsonMapper mapper,
            @Value("${safeai.knowledge.erasure-journal.enabled:false}") boolean enabled,
            @Value("${safeai.knowledge.erasure-journal.bucket:safeai-erasure-journal}") String bucket,
            @Value("${safeai.knowledge.erasure-journal.object-lock-required:false}") boolean objectLockRequired,
            @Value("${safeai.knowledge.erasure-journal.min-retention:P7D}") Duration minimumRetention
    ) {
        this.enabled = enabled;
        this.minimumRetention = Objects.requireNonNull(minimumRetention, "minimumRetention");
        if (minimumRetention.compareTo(Duration.ofDays(1)) < 0
                || minimumRetention.compareTo(Duration.ofDays(36500)) > 0) {
            throw new IllegalArgumentException("Erasure journal minimum retention must be 1..36500 days");
        }
        this.bucket = Objects.requireNonNull(bucket, "bucket").strip();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (!enabled) {
            this.client = null;
            return;
        }
        if (!objectLockRequired) {
            throw new IllegalStateException(
                    "External erasure journal requires an independently verified Object Lock Compliance bucket");
        }
        if (storage.type() != KnowledgeStorageType.S3) {
            throw new IllegalStateException("Erasure journal requires S3 document storage configuration");
        }
        if (this.bucket.isBlank() || this.bucket.equals(storage.bucket())) {
            throw new IllegalStateException("Erasure journal must use a distinct, non-empty S3 bucket");
        }
        this.client = MinioClient.builder()
                .endpoint(storage.endpoint())
                .credentials(storage.accessKey(), storage.secretKey())
                .build();
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(this.bucket).build())) {
                throw new IllegalStateException("Erasure journal bucket must be provisioned by infrastructure");
            }
            VersioningConfiguration versioning = client.getBucketVersioning(
                    GetBucketVersioningArgs.builder().bucket(this.bucket).build());
            if (versioning == null || versioning.status() != VersioningConfiguration.Status.ENABLED) {
                throw new IllegalStateException("Erasure journal bucket must have active S3 versioning");
            }
            ObjectLockConfiguration lock = client.getObjectLockConfiguration(
                    GetObjectLockConfigurationArgs.builder().bucket(this.bucket).build());
            if (lock == null || lock.mode() != RetentionMode.COMPLIANCE
                    || lock.duration() == null) {
                throw new IllegalStateException(
                        "Erasure journal bucket must have DEFAULT Object Lock COMPLIANCE retention");
            }
        } catch (Exception failure) {
            closeClient();
            throw new IllegalStateException("Cannot verify the external erasure journal bucket", failure);
        }
    }

    @Override
    public void publishDocumentIntent(UUID operationId, UUID organizationId,
                                      UUID knowledgeBaseId, UUID documentId,
                                      UUID requestedByUserId, long expectedVersion) throws IOException {
        Map<String, Object> payload = event("DOCUMENT_ERASURE_INTENT", operationId,
                organizationId, knowledgeBaseId, documentId, null, null);
        payload.put("requestedByUserId", requestedByUserId.toString());
        payload.put("expectedVersion", expectedVersion);
        publish("v1/operations/" + operationId + "/00-intent.json", payload);
    }

    @Override
    public void publishObjectPurgeIntent(UUID operationId, UUID organizationId,
                                         UUID knowledgeBaseId, UUID documentId,
                                         String storageKey) throws IOException {
        Objects.requireNonNull(storageKey, "storageKey");
        String keyHash = sha256(storageKey.getBytes(StandardCharsets.UTF_8));
        publish("v1/operations/" + operationId + "/objects/" + keyHash + "-intent.json", event(
                "DOCUMENT_OBJECT_PURGE_INTENT", operationId, organizationId, knowledgeBaseId,
                documentId, storageKey, keyHash));
    }

    @Override
    public void publishObjectPurged(UUID operationId, UUID organizationId,
                                    UUID knowledgeBaseId, UUID documentId,
                                    String storageKey) throws IOException {
        Objects.requireNonNull(storageKey, "storageKey");
        String keyHash = sha256(storageKey.getBytes(StandardCharsets.UTF_8));
        publish("v1/operations/" + operationId + "/objects/" + keyHash + ".json", event(
                "DOCUMENT_OBJECT_PURGED", operationId, organizationId, knowledgeBaseId,
                documentId, storageKey, keyHash));
    }

    @Override
    public void publishDocumentCommitted(UUID operationId, UUID organizationId,
                                         UUID knowledgeBaseId, UUID documentId) throws IOException {
        publish("v1/operations/" + operationId + "/99-document-committed.json", event(
                "DOCUMENT_ERASURE_COMMITTED", operationId, organizationId, knowledgeBaseId,
                documentId, null, null));
    }

    @Override
    public void publishAttention(UUID operationId, UUID organizationId,
                                 UUID knowledgeBaseId, UUID documentId,
                                 String safeReasonCode) throws IOException {
        if (safeReasonCode == null || !safeReasonCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("safeReasonCode must be a safe uppercase code");
        }
        publish("v1/operations/" + operationId + "/98-attention-" + safeReasonCode + ".json", event(
                "DOCUMENT_ERASURE_ATTENTION", operationId, organizationId, knowledgeBaseId,
                documentId, null, safeReasonCode));
    }

    @Override
    public void publishBaseCommitted(UUID operationId, UUID organizationId,
                                     UUID knowledgeBaseId, UUID requestedByUserId,
                                     long expectedVersion) throws IOException {
        Map<String, Object> payload = event("KNOWLEDGE_BASE_ERASURE_COMMITTED", operationId,
                organizationId, knowledgeBaseId, null, null, null);
        payload.put("requestedByUserId", requestedByUserId.toString());
        payload.put("expectedVersion", expectedVersion);
        publish("v1/base-operations/" + operationId + "/99-base-committed.json", payload);
    }

    private Map<String, Object> event(String type, UUID operationId, UUID organizationId,
                                      UUID knowledgeBaseId, UUID documentId,
                                      String storageKey, String detail) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("eventType", type);
        value.put("operationId", operationId.toString());
        value.put("organizationId", organizationId.toString());
        value.put("knowledgeBaseId", knowledgeBaseId.toString());
        if (documentId != null) value.put("documentId", documentId.toString());
        if (storageKey != null) value.put("storageKey", storageKey);
        if (detail != null) value.put("detail", detail);
        return value;
    }

    private void publish(String objectName, Map<String, Object> event) throws IOException {
        if (!enabled || client == null) {
            throw new IOException("External erasure tombstone journal is disabled; physical deletion is fail-closed");
        }
        final byte[] body;
        try {
            body = mapper.writeValueAsBytes(event);
        } catch (JacksonException failure) {
            throw new IOException("Cannot serialize erasure tombstone event", failure);
        }
        if (exists(objectName)) {
            verifySameBody(objectName, body);
            verifyLockedCurrentVersion(objectName);
            return;
        }
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(objectName)
                    .stream(new ByteArrayInputStream(body), body.length, -1)
                    .contentType("application/json")
                    .build());
        } catch (Exception putFailure) {
            // При потере ответа или конкурентной записи перечитываем событие.
            // Успешным считается только побайтовое совпадение содержимого.
            try {
                verifySameBody(objectName, body);
                verifyLockedCurrentVersion(objectName);
                return;
            } catch (IOException verificationFailure) {
                putFailure.addSuppressed(verificationFailure);
                throw new IOException("External erasure tombstone write is not confirmed", putFailure);
            }
        }
        verifySameBody(objectName, body);
        verifyLockedCurrentVersion(objectName);
    }

    /**
     * Подтверждает режим COMPLIANCE у конкретной S3-версии, а не только
     * настройки бакета. Изменённый ACL, Governance и отсутствие возможности
     * прочитать retention запрещают считать доказательство сохранённым.
     */
    private void verifyLockedCurrentVersion(String objectName) throws IOException {
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(objectName).build());
            if (stat.versionId() == null || stat.versionId().isBlank()
                    || "null".equalsIgnoreCase(stat.versionId())) {
                throw new IOException("Erasure journal versionId is missing");
            }
            Retention retention = client.getObjectRetention(GetObjectRetentionArgs.builder()
                    .bucket(bucket).object(objectName).versionId(stat.versionId()).build());
            verifyComplianceRetention(retention, minimumRetention, Instant.now());
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Cannot verify Object Lock COMPLIANCE for erasure evidence", failure);
        }
    }

    /**
     * Чистая проверка политики для регрессионных тестов без сетевого доступа.
     * Допускается только COMPLIANCE с действительным сроком удержания.
     */
    static void verifyComplianceRetention(
            Retention retention, Duration minimumRetention, Instant now) throws IOException {
        if (!hasSufficientComplianceRetention(retention, minimumRetention, now)) {
            // Проверка всегда завершается отказом при недостаточном сроке,
            // неизвестном режиме или недоступных S3-атрибутах защиты.
            throw new IOException("Erasure journal is not protected by sufficient COMPLIANCE retention");
        }
    }

    /**
     * Чистый предикат для проверки времени и режима защиты S3-версии.
     *
     * <p>Граница намеренно строгая: retention должен закончиться ПОЗЖЕ,
     * чем now + minimumRetention. Равенство не обеспечивает запас времени
     * на сетевую задержку и часовую погрешность между системами.</p>
     */
    static boolean hasSufficientComplianceRetention(
            Retention retention, Duration minimumRetention, Instant now) {
        Objects.requireNonNull(minimumRetention, "minimumRetention");
        Objects.requireNonNull(now, "now");
        if (minimumRetention.isZero() || minimumRetention.isNegative()) {
            throw new IllegalArgumentException("Минимальный срок защиты должен быть положительным");
        }
        return retention != null
                && retention.mode() == RetentionMode.COMPLIANCE
                && retention.retainUntilDate() != null
                && retention.retainUntilDate().toInstant().isAfter(now.plus(minimumRetention));
    }

    private boolean exists(String objectName) throws IOException {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectName).build());
            return true;
        } catch (ErrorResponseException missing) {
            String code = missing.errorResponse() == null ? "" : missing.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NotFound".equals(code)) {
                return false;
            }
            throw new IOException("Cannot inspect external erasure journal object", missing);
        } catch (Exception failure) {
            throw new IOException("Cannot inspect external erasure journal object", failure);
        }
    }

    private void verifySameBody(String objectName, byte[] expected) throws IOException {
        try (InputStream input = client.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(objectName).build())) {
            byte[] actual = input.readAllBytes();
            if (!MessageDigest.isEqual(actual, expected)) {
                throw new IOException("External erasure journal contains a conflicting immutable event");
            }
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Cannot verify external erasure journal object", failure);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @PreDestroy
    void closeClient() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
                // Ошибка закрытия клиента не должна скрывать исходную причину остановки.
            }
        }
    }
}
