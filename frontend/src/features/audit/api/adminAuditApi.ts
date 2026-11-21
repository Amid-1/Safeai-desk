// ============================================================
// frontend/src/features/audit/api/adminAuditApi.ts
// Публичный фасад: сохраняет прежние импорты потребителей.
// ============================================================

export type { JsonPrimitive, JsonValue, AuditEvent, AuditEventFilter, AuditActorDirectoryItem, AuditTargetOrganizationDirectoryItem } from './adminAudit/auditTypes'
export { getAuditEvents, getAuditEventTypes, searchAuditActors, searchAuditTargetOrganizations } from './adminAudit/auditClient'
export { getAuditSummary } from './adminAudit/auditClient'
export { AUDIT_CATEGORIES, AUDIT_METRICS } from './adminAudit/auditTypes'
export type { AuditCategory, AuditMetric, AuditSummary } from './adminAudit/auditTypes'
export { parseAuditEvent } from './adminAudit/auditParsers'
export { parseAuditDetails } from './adminAudit/auditSanitizer'
