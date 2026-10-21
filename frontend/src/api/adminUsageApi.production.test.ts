// ============================================================
// frontend/src/api/adminUsageApi.production.test.ts
// Отдельный строгий контракт исторического adminUsage API.
// ============================================================
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest, API_TIMEOUTS } from './http'
import {
  getUsageSummary, getUsageByUsers, getUsageByUser, getUsageByOrganization,
  getUsageByModels, getUsageDaily, parseUsageSummary, parseUsageDailySummary,
  parseUsageModelSummary, parseUsageUserSummary,
} from './adminUsageApi'
vi.mock('./http', async importOriginal => ({ ...(await importOriginal<typeof import('./http')>()), apiRequest: vi.fn() }))
const request = vi.mocked(apiRequest)
const ID = '11111111-1111-4111-8111-111111111111'
const row = () => ({ userId: ID, userEmail: 'user@test.invalid', model: 'm',
    inputTokens: '9007199254740993', outputTokens: '7', totalTokens: '9007199254741000',
    costUsd: '0.000000000001', assistantMessageCount: 3,
    availableUsageMessageCount: 2, partialUsageMessageCount: 1,
    missingUsageMessageCount: 0, usageNotApplicableMessageCount: 0,
    pricedMessageCount: 2, freeMessageCount: 0, unpricedMessageCount: 1,
    pricingFailedMessageCount: 0, pricingNotApplicableMessageCount: 0,
    usageComplete: false, pricingComplete: false,
    ambiguousProviderOperationCount: '4' })
const page = { content: [], page: 0, size: 50, totalPages: 0, totalElements: 0 }
beforeEach(() => vi.resetAllMocks())

describe('adminUsageApi — маршруты', () => {
    it('вызывает все шесть HTTP-операций без изменения путей и timeout', async () => {
        request.mockResolvedValueOnce(page).mockResolvedValueOnce(page)
            .mockResolvedValueOnce(page).mockResolvedValueOnce(page)
            .mockResolvedValueOnce([]).mockResolvedValueOnce([])
        await getUsageSummary(0, 50)
        await getUsageByUsers(0, 50)
        await getUsageByUser(ID, 0, 50)
        await getUsageByOrganization(ID, 0, 50)
        await getUsageByModels()
        await getUsageDaily()
        expect(request.mock.calls.map(([u]) => u)).toEqual([
            '/api/admin/usage/summary?page=0&size=50',
            '/api/admin/usage/users?page=0&size=50',
            `/api/admin/usage/by-user/${ID}?page=0&size=50`,
            `/api/admin/usage/by-organization/${ID}?page=0&size=50`,
            '/api/admin/usage/models', '/api/admin/usage/daily',
        ])
        expect(request.mock.calls.every(([, o]) => o?.timeoutMs === API_TIMEOUTS.report)).toBe(true)
    })
    it('пробрасывает фильтры, нормализует size и AbortSignal', async () => {
        request.mockResolvedValue(page)
        const controller = new AbortController()
        await getUsageSummary(2, 500, {dateFrom:' 2026-01-01 ', dateTo:'2026-02-02',model:' x:y '},{signal:controller.signal})
        const [url, opts] = request.mock.calls[0]
        expect(url).toContain('size=200')
        expect(url).toContain('page=2')
        expect(url).toContain('model=x%3Ay')
        expect(opts?.signal).toBe(controller.signal)
    })
    it('не отправляет запрос при недопустимом ID', async () => {
        await expect(getUsageByUser('../escape', 0, 50)).rejects.toThrow()
        expect(request).not.toHaveBeenCalled()
    })
})

describe('adminUsageApi — строгое покрытие и финансовая точность', () => {
    it('не теряет большие значения и сохраняет неизвестную стоимость', () => {
        const result = parseUsageSummary(row())
        expect(result.totalTokens).toBe('9007199254741000')
        expect(result.costUsd).toBe('0.000000000001')
        expect(result.coverage.ambiguousProviderOperations).toBe('4')
        expect(parseUsageSummary({ ...row(), costUsd: null }).costUsd).toBeNull()
    })
    it('поддерживает разные проекции без подмены DTO', () => {
        expect(parseUsageUserSummary(row()).userId).toBe(ID)
        expect(parseUsageModelSummary(row()).model).toBe('m')
        expect(parseUsageDailySummary({...row(),usageDate:'2026-02-28'}).usageDate).toBe('2026-02-28')
    })
    it.each([
        [{ totalTokens: '9007199254740999' }, /не равен/],
        [{ inputTokens: Number.MAX_SAFE_INTEGER + 1 }, /safe integer/],
        [{ costUsd: '-0.000000000001' }, /отрицательным/],
        [{ costUsd: '0.0000000000001' }, /scale/],
        [{ usageComplete: true }, /противоречит coverage/],
        [{ pricingComplete: true }, /противоречит coverage/],
        [{ availableUsageMessageCount: 10 }, /не согласован/],
        [{ ambiguousProviderOperationCount: -1 }, /неотрицательным/],
    ])('отклоняет противоречивую запись', (delta, message) => {
        expect(() => parseUsageSummary({...row(), ...delta})).toThrow(message)
    })
    it.each(['2026-02-30','2025-02-29','0000-02-30','2026-13-01','2026-00-10'])('отвергает невозможную UTC дату %s', date => {
        expect(() => parseUsageDailySummary({...row(),usageDate:date})).toThrow()
    })
    it.each(['0004-02-29','0099-12-31','2024-02-29'])('принимает реальную календарную дату %s без ошибки Date.UTC(0..99)', date => {
        expect(parseUsageDailySummary({...row(),usageDate:date}).usageDate).toBe(date)
    })
})
