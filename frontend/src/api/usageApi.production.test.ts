// ============================================================
// frontend/src/api/usageApi.production.test.ts
// Регрессионные проверки контрактов отчётности Usage.
// ============================================================
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest, API_TIMEOUTS } from './http'
import {
    getUsageSummary, getUsageByUsers, getUsageByOrganization,
    getOrganizationUsageUsers, getUsageByModels, getOrganizationUsageModels,
    getUsageDaily, getOrganizationUsageDaily, parseUsageSummary,
    parseUsageUserSummary, parseUsageModelSummary, parseUsageDailySummary,
    parseUsagePageResponse,
} from './usageApi'

vi.mock('./http', async (importOriginal) => {
    const actual = await importOriginal<typeof import('./http')>()
    return { ...actual, apiRequest: vi.fn() }
})
const request = vi.mocked(apiRequest)
const ORG = '22222222-2222-4222-8222-222222222222'
const USER = '11111111-1111-4111-8111-111111111111'
const summary = () => ({
    userId: USER, userEmail: 'user@example.test', model: 'model-v1',
    responses: { assistantMessages: 3, completedResponses: 2, refusedResponses: 0,
        incompleteResponses: 0, failedMessages: 1, unclassifiedMessages: 0 },
    usage: { confirmedInputTokens: '9007199254740993', confirmedOutputTokens: '7',
        confirmedTotalTokens: '9007199254741000', partialKnownInputTokens: 2,
        partialKnownOutputTokens: 3, partialKnownTotalTokens: 5,
        availableUsageMessages: 2, partialUsageMessages: 1,
        missingUsageMessages: 0, usageNotApplicableMessages: 0,
        usageComplete: false },
    cost: { knownCostUsd: '0.000000000001', currency: 'USD', pricedMessages: 1,
        freeMessages: 1, unpricedMessages: 1, pricingFailedMessages: 0,
        pricingNotApplicableMessages: 0, pricingComplete: false },
})
const slice = (content: unknown[] = []) => ({
    content, page: 0, size: 50, first: true, last: true,
    hasNext: false, hasPrevious: false,
})

beforeEach(() => { vi.resetAllMocks() })

describe('usageApi — маршруты и сигналы', () => {
    it('обслуживает все восемь публичных GET с отчётным deadline', async () => {
        request.mockResolvedValueOnce(slice()).mockResolvedValueOnce(slice())
            .mockResolvedValueOnce(slice()).mockResolvedValueOnce(slice())
            .mockResolvedValueOnce([]).mockResolvedValueOnce([])
            .mockResolvedValueOnce([]).mockResolvedValueOnce([])
        await getUsageSummary()
        await getUsageByUsers()
        await getUsageByOrganization(ORG)
        await getOrganizationUsageUsers(ORG)
        await getUsageByModels()
        await getOrganizationUsageModels(ORG)
        await getUsageDaily()
        await getOrganizationUsageDaily(ORG)
        expect(request.mock.calls.map(([path]) => path)).toEqual([
            '/api/admin/usage/summary?page=0&size=50',
            '/api/admin/usage/users?page=0&size=50',
            `/api/admin/usage/by-organization/${ORG}?page=0&size=50`,
            `/api/admin/usage/by-organization/${ORG}/users?page=0&size=50`,
            '/api/admin/usage/models',
            `/api/admin/usage/by-organization/${ORG}/models`,
            '/api/admin/usage/daily',
            `/api/admin/usage/by-organization/${ORG}/daily`,
        ])
        for (const [, options] of request.mock.calls) {
            expect(options).toEqual(expect.objectContaining({ method: 'GET', timeoutMs: API_TIMEOUTS.report }))
        }
    })
    it('сохраняет параметры дат, модели, ограничения страницы и AbortSignal', async () => {
        request.mockResolvedValue(slice())
        const controller = new AbortController()
        await getUsageSummary(2, 999, { dateFrom: ' 2026-08-01 ', dateTo: '2026-08-31', model: ' a:b ' }, { signal: controller.signal })
        const [url, options] = request.mock.calls[0]
        expect(url).toContain('page=2')
        expect(url).toContain('size=100')
        expect(url).toContain('dateFrom=2026-08-01')
        expect(url).toContain('dateTo=2026-08-31')
        expect(url).toContain('model=a%3Ab')
        expect(options?.signal).toBe(controller.signal)
    })
    it('не отправляет запрос при некорректном UUID организации', async () => {
        await expect(getUsageByOrganization('../other-tenant')).rejects.toThrow()
        expect(request).not.toHaveBeenCalled()
    })
    it('не принимает объект вместо массива модельного отчёта', async () => {
        request.mockResolvedValue({})
        await expect(getUsageByModels()).rejects.toThrow('массивом')
    })
})

describe('usageApi — точность и границы DTO', () => {
    it('не округляет счётчики > MAX_SAFE_INTEGER и финансовые decimal', () => {
        const result = parseUsageSummary(summary())
        expect(result.totalTokens).toBe('9007199254741000')
        expect(result.inputTokens).toBe('9007199254740993')
        expect(result.partialTotalTokens).toBe('5')
        expect(result.costUsd).toBe('0.000000000001')
        expect(result.coverage.pricingComplete).toBe(false)
    })
    it('различает нулевую известную сумму и неполное покрытие', () => {
        const value = summary(); value.cost.knownCostUsd = '0'
        expect(parseUsageSummary(value)).toMatchObject({ costUsd: '0',
            coverage: { pricingComplete: false, unpricedMessages: '1' } })
    })
    it('сохраняет прежний плоский DTO и nullable-признаки покрытия', () => {
        const result = parseUsageSummary({ userId: USER, userEmail: 'u@x.test', model: 'm',
            inputTokens: 2, outputTokens: 3, totalTokens: 5, costUsd: 0 })
        expect(result).toMatchObject({ inputTokens: '2', outputTokens: '3', totalTokens: '5', costUsd: '0',
            coverage: { usageComplete: null, pricingComplete: null } })
    })
    it('parsers пользователей, моделей и UTC daily разделены', () => {
        const value = summary()
        expect(parseUsageUserSummary(value).userId).toBe(USER)
        expect(parseUsageModelSummary(value).model).toBe('model-v1')
        expect(parseUsageDailySummary({ ...value, usageDate: '2026-08-10', aggregationZone: 'UTC' }).aggregationZone).toBe('UTC')
    })
    it.each([
        [{ usage: {}, ...{ cost: null } }, /объектом/],
        [{ ...summary(), cost: null }, /объектом/],
        [{ ...summary(), usage: null }, /объектом/],
        [{ ...summary(), usage: { ...summary().usage, confirmedTotalTokens: 13 } }, /не равен сумме/],
        [{ ...summary(), usage: { ...summary().usage, usageComplete: true } }, /usageComplete противоречит/],
        [{ ...summary(), cost: { ...summary().cost, pricingComplete: true } }, /pricingComplete противоречит/],
        [{ ...summary(), cost: { ...summary().cost, knownCostUsd: '-0.1' } }, /неотрицательным/],
        [{ ...summary(), usage: { ...summary().usage, confirmedInputTokens: Number.MAX_SAFE_INTEGER + 1 } }, /неотрицательным/],
    ])('отклоняет неполный или противоречивый вложенный DTO', (invalid, error) => {
        expect(() => parseUsageSummary({ userId: USER, userEmail: 'u@x', model: 'm', ...invalid })).toThrow(error)
    })
    it('отклоняет не-UTC агрегирование', () => {
        expect(() => parseUsageDailySummary({ ...summary(), usageDate: '2026-08-10', aggregationZone: 'Europe/Moscow' })).toThrow('UTC')
    })
    it('сохраняет custom Slice без выдумывания exact COUNT', () => {
        const parsed = parseUsagePageResponse({ ...slice([summary()]), last: false, hasNext: true }, parseUsageSummary)
        expect(parsed.content).toHaveLength(1)
        expect(parsed.totalPages).toBe(2)
    })
    it.each([
        { page: -1 }, { page: 1, first: true, hasPrevious: true },
        { last: true, hasNext: true }, { size: 101 },
        { page: Number.MAX_SAFE_INTEGER + 1 },
        { hasPrevious: true },
    ])('не исправляет ошибочные Slice-метаданные молча', change => {
        expect(() => parseUsagePageResponse({ ...slice(), ...change }, parseUsageSummary)).toThrow()
    })
})
