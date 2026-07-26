package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.config.AuditOutboxProperties;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditOutboxProcessorTest {

    @Mock
    private AuditOutboxRepository auditOutboxRepository;

    @Mock
    private AuditEventRepository auditEventRepository;

    @Test
    void copiesBeforeDeletingOutboxRow() {
        AuditOutboxEntity item = new AuditOutboxEntity();
        item.setId(UUID.randomUUID());

        when(auditOutboxRepository.findBatchForUpdate(100))
                .thenReturn(List.of(item));

        AuditOutboxProcessor processor = new AuditOutboxProcessor(
                auditOutboxRepository,
                auditEventRepository,
                new AuditOutboxProperties(100)
        );

        assertThat(processor.processBatch()).isEqualTo(1);

        InOrder order = inOrder(
                auditEventRepository,
                auditOutboxRepository
        );
        order.verify(auditEventRepository)
                .insertFromOutbox(item.getId());
        order.verify(auditOutboxRepository).delete(item);
    }
}
