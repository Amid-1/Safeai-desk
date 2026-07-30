package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.dto.AuditEventCursorResponse;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditEventCursorService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final AuditEventRepository repository;
    private final AuditCursorCodec cursorCodec;
    private final AuditEventQueryService mapper;

    @Transactional(readOnly = true)
    public AuditEventCursorResponse findAll(
            SafeAiUserPrincipal currentUser,
            AuditEventFilter filter,
            String cursor,
            Integer requestedLimit
    ) {
        AuditEventQueryPolicy.QueryScope scope =
                AuditEventQueryPolicy.resolve(
                        currentUser,
                        filter
                );

        int limit = requestedLimit == null
                ? DEFAULT_LIMIT
                : Math.clamp(
                        requestedLimit,
                        1,
                        MAX_LIMIT
                );

        AuditCursorCodec.AuditCursor decoded =
                cursorCodec.decode(cursor);

        List<AuditEventEntity> rows =
                repository.findByCursor(
                        scope.organizationId(),
                        scope.filter(),
                        decoded == null
                                ? null
                                : decoded.createdAt(),
                        decoded == null
                                ? null
                                : decoded.id(),
                        limit + 1
                );

        boolean hasNext = rows.size() > limit;

        List<AuditEventEntity> visibleRows =
                hasNext
                        ? rows.subList(0, limit)
                        : rows;

        List<AuditEventResponse> items =
                visibleRows.stream()
                        .map(mapper::toResponse)
                        .toList();

        String nextCursor = null;

        if (hasNext && !visibleRows.isEmpty()) {
            AuditEventEntity last =
                    visibleRows.getLast();

            nextCursor = cursorCodec.encode(
                    last.getCreatedAt(),
                    last.getId()
            );
        }

        return new AuditEventCursorResponse(
                items,
                nextCursor,
                hasNext
        );
    }
}
