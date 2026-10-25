// frontend/src/features/audit/ui/auditCsv.ts
import type { AuditEvent } from '../api/adminAuditApi'
import { getAuditEventTypeLabel } from '../constants/auditEvents'
import { formatDateTime } from '../../../shared/lib/format'

/** Exports only the audit events currently rendered in the page. */
export function downloadAuditCsv(events: AuditEvent[]): void {
    const rows = [
        ['Дата и время', 'Целевая организация', 'ID организации', 'Инициатор', 'ID инициатора', 'Тип события', 'Код события', 'ID события'],
        ...events.map((event) => [
            formatDateTime(event.createdAt),
            event.targetOrganizationName ?? '',
            event.targetOrganizationId,
            event.actorEmail ?? event.actorDisplayName ?? 'Система/исторический инициатор',
            event.actorUserId ?? '',
            getAuditEventTypeLabel(event.eventType),
            event.eventType,
            event.id,
        ]),
    ]
    const csv = rows.map((row) => row.map(escapeAuditCsvCell).join(';')).join('\r\n')
    const objectUrl = URL.createObjectURL(new Blob([`\uFEFF${csv}`], {type: 'text/csv;charset=utf-8'}))
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = 'safeai-audit-current-page.csv'
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1_000)
}

/**
 * Spreadsheet formulas must remain inert. Quoting a CSV cell alone does not
 * prevent Excel/LibreOffice from interpreting attacker-controlled text.
 * Changes affect only exported bytes, never persisted audit evidence.
 */
export function escapeAuditCsvCell(value: string): string {
    const raw = String(value)
    // A spreadsheet may ignore leading whitespace, control or format chars.
    const formula = /^[\p{Cc}\p{Cf}\p{Z}\s]*[=+\-@]/u.test(raw)
    // NUL and other nonprinting control chars should not reach CSV parsers.
    const printable = raw.replace(/[\p{Cc}]/gu, (character) => {
        const code = character.charCodeAt(0)
        // Preserve tab, LF, CR and C1 control characters.
        return code <= 8 || code === 11 || code === 12
            || (code >= 14 && code <= 31) || code === 127
            ? ' '
            : character
    })
    const safe = formula ? "'" + printable : printable
    return `"${safe.replaceAll('"', '""')}"`
}
