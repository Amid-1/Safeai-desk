package ru.safeai.gateway.knowledge.storage.reconciliation;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KnowledgeStorageReconciliationSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void neverDeletesAnUnconfirmedIntention() {
        KnowledgeStorageUploadJournal journal = mock(KnowledgeStorageUploadJournal.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(journal.claimStoredOrphan(any())).thenReturn(null);
        when(journal.quarantineUncertain(any(), eq(20))).thenReturn(1);
        new KnowledgeStorageReconciliationScheduler(journal, storage, CLOCK).poll();
        verifyNoInteractions(storage);
    }

    @Test
    void deletesOnlyClaimedStoredOrphanThenRecordsCompletion() throws IOException {
        KnowledgeStorageUploadJournal journal = mock(KnowledgeStorageUploadJournal.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        var claim = new KnowledgeStorageUploadJournal.CleanupClaim(
                UUID.randomUUID(), "key", UUID.randomUUID());
        when(journal.claimStoredOrphan(any())).thenReturn(claim, (KnowledgeStorageUploadJournal.CleanupClaim) null);
        new KnowledgeStorageReconciliationScheduler(journal, storage, CLOCK).poll();
        var order = inOrder(journal, storage);
        order.verify(journal).quarantineUncertain(any(), eq(20));
        order.verify(journal).quarantineExpiredCleanup(any(), eq(20));
        order.verify(journal).reconcileLinked(any(), eq(20));
        order.verify(journal).claimStoredOrphan(any());
        order.verify(storage).delete("key");
        order.verify(journal).cleaned(claim);
    }

    @Test
    void ambiguousDeleteIsQuarantinedNotAutomaticallyRetried() throws IOException {
        KnowledgeStorageUploadJournal journal = mock(KnowledgeStorageUploadJournal.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        var claim = new KnowledgeStorageUploadJournal.CleanupClaim(
                UUID.randomUUID(), "key", UUID.randomUUID());
        when(journal.claimStoredOrphan(any())).thenReturn(claim, (KnowledgeStorageUploadJournal.CleanupClaim) null);
        doThrow(new IOException("ambiguous")).when(storage).delete("key");
        new KnowledgeStorageReconciliationScheduler(journal, storage, CLOCK).poll();
        verify(journal).cleanupUncertain(claim);
        verify(journal, never()).cleaned(claim);
        verify(storage, times(1)).delete("key");
    }
}
