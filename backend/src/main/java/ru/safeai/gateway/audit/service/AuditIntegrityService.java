package ru.safeai.gateway.audit.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.dto.AuditIntegrityStatus;

import java.time.Clock;

@Service
public class AuditIntegrityService {
    private final Clock clock;
    private final String reportPath;
    private final String dataset;
    private final String publicKey;

    public AuditIntegrityService(Clock clock, MeterRegistry registry,
                                 @Value("${SAFEAI_AUDIT_INTEGRITY_REPORT_PATH:}") String reportPath,
                                 @Value("${SAFEAI_AUDIT_INTEGRITY_DATASET:}") String dataset,
                                 @Value("${SAFEAI_AUDIT_INTEGRITY_PUBLIC_KEY:}") String publicKey) {
        this.clock = clock;
        this.reportPath = reportPath;
        this.dataset = dataset;
        this.publicKey = publicKey;
        Gauge.builder("safeai.audit.integrity.state", this, service -> service.current().state().metricCode())
                .description("Independent signed audit verification state (0-6)")
                .register(registry);
    }

    public AuditIntegrityStatus current() {
        return AuditIntegrityReport.read(reportPath, dataset, publicKey, clock.instant());
    }
}
