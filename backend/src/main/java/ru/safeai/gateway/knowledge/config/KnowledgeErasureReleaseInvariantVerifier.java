package ru.safeai.gateway.knowledge.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.common.security.ProductionEnvironmentCondition;

/**
 * Запрещает случайно включить разрушительные действия без подписанного
 * эксплуатационного допуска в конфигурации production.
 *
 * <p>Значения флагов — подтверждение ответственного персонала, а НЕ
 * автоматическое доказательство выполнения S3/restore тестов. Проверки
 * журналирования, прав и состояния БД выполняются независимо.</p>
 */
@Component
@Conditional(ProductionEnvironmentCondition.class)
public class KnowledgeErasureReleaseInvariantVerifier {

    private final boolean documentAccept;
    private final boolean documentExecutor;
    private final boolean baseAccept;
    private final boolean baseExecutor;
    private final boolean journalEnabled;
    private final boolean objectLockRequired;
    private final boolean restoreDrillApproved;
    private final boolean providerBoundaryApproved;
    private final boolean derivedDataScopeApproved;
    private final boolean objectLockDrillApproved;
    private final boolean multiReplicaDrillApproved;
    private final String releaseApprovalId;

    public KnowledgeErasureReleaseInvariantVerifier(
            @Value("${safeai.knowledge.deletion.accept-enabled:false}") boolean documentAccept,
            @Value("${safeai.knowledge.deletion.executor-enabled:false}") boolean documentExecutor,
            @Value("${safeai.knowledge.base-deletion.accept-enabled:false}") boolean baseAccept,
            @Value("${safeai.knowledge.base-deletion.executor-enabled:false}") boolean baseExecutor,
            @Value("${safeai.knowledge.erasure-journal.enabled:false}") boolean journalEnabled,
            @Value("${safeai.knowledge.erasure-journal.object-lock-required:false}") boolean objectLockRequired,
            @Value("${safeai.knowledge.erasure-release.restore-drill-approved:false}") boolean restoreDrillApproved,
            @Value("${safeai.knowledge.erasure-release.provider-boundary-approved:false}") boolean providerBoundaryApproved,
            @Value("${safeai.knowledge.erasure-release.derived-data-scope-approved:false}") boolean derivedDataScopeApproved,
            @Value("${safeai.knowledge.erasure-release.object-lock-drill-approved:false}") boolean objectLockDrillApproved,
            @Value("${safeai.knowledge.erasure-release.multi-replica-drill-approved:false}") boolean multiReplicaDrillApproved,
            @Value("${safeai.knowledge.erasure-release.approval-id:}") String releaseApprovalId
    ) {
        this.documentAccept = documentAccept;
        this.documentExecutor = documentExecutor;
        this.baseAccept = baseAccept;
        this.baseExecutor = baseExecutor;
        this.journalEnabled = journalEnabled;
        this.objectLockRequired = objectLockRequired;
        this.restoreDrillApproved = restoreDrillApproved;
        this.providerBoundaryApproved = providerBoundaryApproved;
        this.derivedDataScopeApproved = derivedDataScopeApproved;
        this.objectLockDrillApproved = objectLockDrillApproved;
        this.multiReplicaDrillApproved = multiReplicaDrillApproved;
        this.releaseApprovalId = releaseApprovalId;
    }

    @PostConstruct
    void verify() {
        assertReleaseReady(documentAccept || documentExecutor || baseAccept || baseExecutor,
                journalEnabled, objectLockRequired, restoreDrillApproved,
                providerBoundaryApproved, derivedDataScopeApproved,
                objectLockDrillApproved, multiReplicaDrillApproved, releaseApprovalId);
    }

    /** Проверка без Spring-контекста для отказных unit-тестов. */
    static void assertReleaseReady(boolean deletionActive,
                                   boolean journalEnabled,
                                   boolean objectLockRequired,
                                   boolean restoreDrillApproved,
                                   boolean providerBoundaryApproved,
                                   boolean derivedDataScopeApproved,
                                   boolean objectLockDrillApproved,
                                   boolean multiReplicaDrillApproved,
                                   String approvalId) {
        if (!deletionActive) {
            return;
        }
        if (!journalEnabled || !objectLockRequired
                || !restoreDrillApproved || !providerBoundaryApproved
                || !derivedDataScopeApproved || !objectLockDrillApproved
                || !multiReplicaDrillApproved || approvalId == null
                || !approvalId.matches("[A-Za-z0-9][A-Za-z0-9._/-]{7,127}")) {
            throw new IllegalStateException(
                    "Production erasure is blocked: release evidence and approvals are incomplete");
        }
    }
}
