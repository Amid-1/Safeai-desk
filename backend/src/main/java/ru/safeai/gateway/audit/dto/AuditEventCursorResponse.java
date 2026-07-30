package ru.safeai.gateway.audit.dto;

import java.util.List;

public record AuditEventCursorResponse(
        List<AuditEventResponse> items,
        String nextCursor,
        boolean hasNext
) {
    public AuditEventCursorResponse {
        items = List.copyOf(items);
    }
}
