// frontend/src/features/audit/ui/auditCsv.test.ts
import { describe, expect, it } from 'vitest'
import { escapeAuditCsvCell } from './auditCsv'

describe('audit CSV spreadsheet safety', () => {
    it.each([
        '=1+1', '+SUM(A1:A3)', '-2+3', '@HYPERLINK("https://example.test")',
        '  =1+1', '\t+1+1', '\n-12+4', '\u200b@SUM(A1:A3)',
        '\ufeff=1+1', '\u00a0=1+1',
    ])('neutralizes a spreadsheet formula even after hidden leading chars', (value) => {
        expect(escapeAuditCsvCell(value)).toMatch(/^"'/)
    })

    it('preserves ordinary text, escaping both quotes and separators', () => {
        expect(escapeAuditCsvCell('Пользователь; "admin"'))
            .toBe('"Пользователь; ""admin"""')
        expect(escapeAuditCsvCell('admin@test.com')).toBe('"admin@test.com"')
    })

    it('does not leave NUL and terminal control chars in the exported text', () => {
        expect(escapeAuditCsvCell('safe\u0000text')).toBe('"safe text"')
        expect(escapeAuditCsvCell('test\u001bname')).toBe('"test name"')
    })
})
