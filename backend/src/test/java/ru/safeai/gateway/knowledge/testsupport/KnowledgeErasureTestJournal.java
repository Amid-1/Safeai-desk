package ru.safeai.gateway.knowledge.testsupport;

import ru.safeai.gateway.knowledge.storage.ErasureTombstoneJournal;

import java.util.UUID;

/**
 * Тестовая имитация внешнего журнала удаления.
 * Не регистрируется как Spring bean и не подходит для production: методы не сохраняют записи.
 * Используется исключительно в изолированных сценариях, проверяющих логику исполнения.
 */
public final class KnowledgeErasureTestJournal implements ErasureTombstoneJournal {
    public static final KnowledgeErasureTestJournal INSTANCE = new KnowledgeErasureTestJournal();

    private KnowledgeErasureTestJournal() {
    }

    @Override
    public void publishDocumentIntent(UUID op, UUID org, UUID base, UUID doc, UUID actor, long version) {
    }

    @Override
    public void publishObjectPurgeIntent(UUID op, UUID org, UUID base, UUID doc, String key) {
    }

    @Override
    public void publishObjectPurged(UUID op, UUID org, UUID base, UUID doc, String key) {
    }

    @Override
    public void publishDocumentCommitted(UUID op, UUID org, UUID base, UUID doc) {
    }

    @Override
    public void publishAttention(UUID op, UUID org, UUID base, UUID doc, String reason) {
    }

    @Override
    public void publishBaseCommitted(UUID op, UUID org, UUID base, UUID actor, long version) {
    }
}
