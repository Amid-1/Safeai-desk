package ru.safeai.gateway.organization.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.audit.spi.AuditTargetOrganizationSnapshotProvider;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrganizationAuditSnapshotProvider
        implements AuditTargetOrganizationSnapshotProvider {

    private final OrganizationRepository organizationRepository;

    @Override
    public Optional<String> findName(
            UUID organizationId
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        return organizationRepository
                .findById(organizationId)
                .map(OrganizationEntity::getName)
                .map(String::trim)
                .filter(name -> !name.isEmpty());
    }
}
