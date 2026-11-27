// Production-bundle UX checks. Real PostgreSQL and JWT checks are in ErasureRegistryHttpIT.
const { test, expect } = require('@playwright/test')
const { URL } = require('node:url')
const org = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const actor = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const requestId = '4cc9a902-47a2-46f7-9594-49261e6dd90e'
const item = { requestId, organization: 'Demo Company', requester: null, resourceType: 'CHAT', state: 'PENDING',
    intakeState: 'SUBMITTED', reviewState: 'PENDING', reviewVisible: true, holdActive: false, updatedAt: '2026-11-25T00:00:00Z' }
const empty = { items: [], page: 0, pageSize: 25, hasNext: false }
async function fixture(page, role) {
    const platform = role === 'SUPER_ADMIN'
    const calls = []
    await page.route('**/api/**', async route => {
        const url = new URL(route.request().url())
        calls.push({ path: url.pathname, state: url.searchParams.get('state'), query: url.searchParams.get('query'), method: route.request().method() })
        if (route.request().method() !== 'GET') return route.fulfill({ status: 405, json: { message: 'Read-only fixture' } })
        switch (url.pathname) {
            case '/api/auth/csrf': return route.fulfill({ status: 204, headers: { 'set-cookie': 'XSRF-TOKEN=registry-test; Path=/; SameSite=Lax' } })
            case '/api/auth/me': return route.fulfill({ json: { id: actor, organizationId: org, email: 'admin@example.test', fullName: 'Test Admin', enabled: true, roles: [role] } })
            case '/api/erasure/requests/capabilities': return route.fulfill({ json: { platform, tenantRegistry: !platform, personalRequests: !platform, reviewQueue: false, requestsEnabled: true, approvalsEnabled: false } })
            case '/api/erasure/requests/notifications': return route.fulfill({ json: { canRequest: !platform, registryCount: 1, reviewCount: null, personal: [], review: [] } })
            case '/api/admin/erasure/registry-summary': return route.fulfill({ json: platform
                ? { scope: 'PLATFORM', total: 1, registered: 1, withdrawn: 0, drafts: null, reviewed: 1, pending: 1, needsInformation: 0, held: 0 }
                : { scope: 'TENANT', total: 1, registered: 1, withdrawn: 0, drafts: 0, reviewed: null, pending: null, needsInformation: null, held: null } })
            case '/api/admin/erasure/registry': {
                const state = url.searchParams.get('state')
                const row = platform ? item : { ...item, requester: 'Test Owner', state: 'SUBMITTED', reviewState: null, reviewVisible: false }
                return route.fulfill({ json: { ...empty, items: (platform ? ['ALL', 'PENDING', 'ATTENTION'] : ['ALL', 'SUBMITTED']).includes(state) ? [row] : [] } })
            }
            case `/api/admin/erasure/registry/${requestId}`: return route.fulfill({ json: {
                requestId, organization: item.organization, resourceType: 'CHAT', intakeState: 'SUBMITTED',
                reviewState: platform ? 'PENDING' : null, reviewVisible: platform, holdActive: platform ? false : null,
                createdAt: item.updatedAt, updatedAt: item.updatedAt, executionState: 'NOT_AVAILABLE', completionProven: false } })
            case '/api/admin/erasure/history': return platform
                ? route.fulfill({ json: { ...empty, items: [{ requestId, organization: item.organization, eventCode: 'SUBMITTED', createdAt: item.updatedAt, version: 1, eventId: actor, source: 'REVIEW' }] } })
                : route.fulfill({ status: 403, json: { message: 'Forbidden' } })
            default: return route.fulfill({ status: 404, json: { message: 'Not found' } })
        }
    })
    return calls
}
async function noOverflow(page) {
    await expect.poll(() => page.evaluate(() => {
        const root = globalThis.document.documentElement
        const main = globalThis.document.querySelector('main')
        return Math.max(root.scrollWidth - root.clientWidth, main ? main.scrollWidth - main.clientWidth : 0)
    })).toBeLessThanOrEqual(1)
}
for (const width of [390, 1440]) {
    test(`platform registry uses real-count contract and empty states at ${width}px`, async ({ page }) => {
        const calls = await fixture(page, 'SUPER_ADMIN')
        await page.setViewportSize({ width, height: 844 })
        await page.goto('/admin/erasure/registry?state=PENDING')
        const metrics = page.getByRole('region', { name: 'Сводка реестра' })
        await expect(metrics.getByRole('button', { name: 'Всего в реестре 1' })).toBeVisible()
        await expect(page.getByRole('cell', { name: 'Ожидает рассмотрения', exact: true })).toBeVisible()
        await expect(page.getByText('Рассмотрение заявок отключено', { exact: true })).toBeVisible()
        await expect(page.getByRole('columnheader', { name: 'Заявитель' })).toHaveCount(0)
        await expect(page.getByRole('button', { name: 'Вперёд', exact: true })).toHaveCount(0)
        await expect(page.getByRole('button', { name: 'Назад', exact: true })).toHaveCount(0)
        const select = page.getByRole('combobox', { name: 'Состояние заявки' })
        for (const state of ['NEEDS_INFORMATION', 'HELD', 'REJECTED']) {
            await select.selectOption(state)
            await expect(page.getByText('Заявок не найдено.', { exact: true })).toBeVisible()
            await expect(page.getByRole('heading', { name: `Состояние: ${{ NEEDS_INFORMATION: 'Нужно уточнение', HELD: 'Удержания', REJECTED: 'Отказы' }[state]}`, exact: true })).toBeVisible()
            await expect(page.getByRole('alert')).toHaveCount(0)
            await expect(page.getByRole('link', { name: 'Открыть карточку' })).toHaveCount(0)
            expect(calls.some(call => call.path === '/api/admin/erasure/registry' && call.state === state)).toBe(true)
            await noOverflow(page)
        }
        await page.getByRole('button', { name: 'Сбросить фильтры' }).click()
        await expect(page.getByRole('link', { name: 'Открыть карточку' })).toBeVisible()
        await noOverflow(page)
        expect(calls.every(call => call.method === 'GET')).toBe(true)
    })
    test(`tenant registry keeps review data outside its scope at ${width}px`, async ({ page }) => {
        const calls = await fixture(page, 'ADMIN')
        await page.setViewportSize({ width, height: 844 })
        await page.goto('/admin/erasure/intake?state=HELD')
        await expect(page.getByRole('cell', { name: 'Test Owner' })).toBeVisible()
        const select = page.getByRole('combobox', { name: 'Состояние заявки' })
        await expect(select).toHaveValue('ALL')
        await expect(select.getByRole('option')).toHaveCount(4)
        await expect(page.getByRole('columnheader', { name: 'Рассмотрение' })).toHaveCount(0)
        await expect(page.getByRole('columnheader', { name: 'Удержание' })).toHaveCount(0)
        await select.selectOption('DRAFT')
        await expect(page.getByText('Заявок не найдено.', { exact: true })).toBeVisible()
        expect(calls.filter(call => call.path === '/api/admin/erasure/registry').every(call => ['ALL', 'DRAFT'].includes(call.state))).toBe(true)
        await noOverflow(page)
        expect(calls.every(call => call.method === 'GET')).toBe(true)
    })
}

test('platform case card exposes the full ID and a returnable filtered history', async ({ page }) => {
    const calls = await fixture(page, 'SUPER_ADMIN')
    await page.goto('/admin/erasure/registry')
    await page.getByText(`Номер ${requestId.slice(0, 8)}`, { exact: true }).click()
    await expect(page.getByRole('button', { name: `Скопировать номер заявки ${requestId}`, exact: true })).toBeVisible()
    await page.getByRole('link', { name: 'Открыть карточку' }).click()
    await expect(page.getByRole('button', { name: `Скопировать номер заявки ${requestId}`, exact: true })).toBeVisible()
    await page.getByRole('link', { name: 'История этой заявки' }).click()
    await expect(page).toHaveURL(new RegExp(`/admin/erasure/history\\?query=${requestId}&page=0$`))
    await expect(page.getByText('Создана запись рассмотрения', { exact: true })).toBeVisible()
    expect(calls.some(call => call.path === '/api/admin/erasure/history' && call.query === requestId)).toBe(true)
    await page.getByRole('link', { name: /К карточке заявки/ }).click()
    await expect(page.getByRole('heading', { name: 'Карточка заявки' })).toBeVisible()
    await expect(page.getByRole('button', { name: `Скопировать номер заявки ${requestId}`, exact: true })).toBeVisible()
    expect(calls.every(call => call.method === 'GET')).toBe(true)
})
test('tenant case card does not link to or request platform history', async ({ page }) => {
    const calls = await fixture(page, 'ADMIN')
    await page.goto('/admin/erasure/intake')
    await page.getByRole('link', { name: 'Открыть карточку' }).click()
    await expect(page.getByRole('button', { name: `Скопировать номер заявки ${requestId}`, exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: 'История этой заявки' })).toHaveCount(0)
    expect(calls.some(call => call.path === '/api/admin/erasure/history')).toBe(false)
    expect(calls.every(call => call.method === 'GET')).toBe(true)
})
