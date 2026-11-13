package ru.safeai.gateway.knowledge.storage;

import java.io.IOException;
import java.util.UUID;

/**
 * Контракт внешнего добавляемого журнала доказательств удаления.
 * Журнал хранится отдельно от резервной копии PostgreSQL.
 * Продуктовые реализации должны гарантировать долговечность записи,
 * повторяемость идемпотентного вызова и отказ при неопределённом результате.
 */
public interface ErasureTombstoneJournal {
    void publishDocumentIntent(UUID operationId, UUID organizationId,
                               UUID knowledgeBaseId, UUID documentId,
                               UUID requestedByUserId, long expectedVersion) throws IOException;

    void publishObjectPurgeIntent(UUID operationId, UUID organizationId,
                                  UUID knowledgeBaseId, UUID documentId,
                                  String storageKey) throws IOException;

    void publishObjectPurged(UUID operationId, UUID organizationId,
                             UUID knowledgeBaseId, UUID documentId,
                             String storageKey) throws IOException;

    void publishDocumentCommitted(UUID operationId, UUID organizationId,
                                  UUID knowledgeBaseId, UUID documentId) throws IOException;

    void publishAttention(UUID operationId, UUID organizationId,
                          UUID knowledgeBaseId, UUID documentId,
                          String safeReasonCode) throws IOException;

    void publishBaseCommitted(UUID operationId, UUID organizationId,
                              UUID knowledgeBaseId, UUID requestedByUserId,
                              long expectedVersion) throws IOException;
}
