// Real browser layout regression checks; deterministic mock API, no real tenant data.
const { test, expect } = require('@playwright/test')
const { URL: NodeURL } = require('node:url')

const orgId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const userId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const decisionId = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
const actor = { actorUserId: userId, actorOrganizationId: orgId,
  actorEmail: 'admin@example.test', actorDisplayName: 'Admin' }
const event = { ...actor, id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', targetOrganizationId: orgId,
  targetOrganizationName: 'Test tenant', eventType: 'MODEL_ROUTE_DENIED',
  details: { decisionId, reason: 'MODEL_NOT_ALLOWED', outcome: 'DENIED', monthlyCommitmentState: 'NOT_EVALUATED' },
  createdAt: '2026-11-15T17:39:20Z' }
const decision = { id: decisionId, organizationId: orgId, userId,
  chatId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', chatTurnId: null,
  clientRequestId: 'ffffffff-ffff-4fff-8fff-ffffffffffff', requestContentHash: 'a'.repeat(64),
  requestedModelKey: null, selectedCatalogEntryId: null, selectedCatalogVersion: null,
  selectedModelKey: null, selectedProvider: null, selectedProviderModelId: null, policyId: null, policyVersion: null,
  requiredCapabilities: [], inputAccountingVersion: 'UTF8_STRUCTURAL_UNITS_V2', additionalInputUnitUpperBound: 8192,
  estimatedInputTokens: null, estimatedOutputTokens: null, estimatedMaxCostUsd: null,
  monthlyBudgetUsd: null, monthlySpentUsd: null, monthlyProjectedUsd: null, monthlyCostKnown: false,
  monthlyCostState: 'NOT_EVALUATED', budgetEnforcement: null, budgetExceeded: false, pricingComplete: false,
  outcome: 'DENIED', reason: 'MODEL_NOT_ALLOWED', decisionIntegrityVersion: 3,
  decisionSha256: 'b'.repeat(64), createdAt: event.createdAt }
const runtime = { provider: 'mock', model: 'mock-safeai', enabled: true, routingMode: 'SINGLE_PROVIDER_STATIC',
  maxInputTokens: 64000, maxOutputTokens: 2048, toolsSupported: false, visionSupported: false,
  structuredOutputSupported: false, dataRetentionStatus: 'NOT_DECLARED', healthStatus: 'NOT_PROBED',
  pricingStatus: 'FREE', inputUsdPer1mTokens: '0', outputUsdPer1mTokens: '0', pricingVersion: 'mock-2026-01' }
const policy = { configured: false, id: null, organizationId: orgId, version: 0, enabled: false,
  allowModelKeys: [], denyModelKeys: [], defaultModelKey: null, maxInputTokens: null, maxOutputTokens: null,
  maxRequestCostUsd: null, monthlyBudgetUsd: null, budgetEnforcement: 'SOFT', requireCompletePricing: false,
  requireNoTraining: false, requireZeroDataRetention: false, createdByUserId: null, createdAt: null }

test.beforeEach(async ({ page }) => {
  await page.route('**/api/**', async route => {
    const { pathname, searchParams } = new NodeURL(route.request().url())
    let json
    switch (pathname) {
      case '/api/auth/me': json = { id: userId, organizationId: orgId, email: actor.actorEmail,
        fullName: actor.actorDisplayName, enabled: true, roles: ['ADMIN'] }; break
      case '/api/auth/csrf': return route.fulfill({ status: 204, headers: { 'set-cookie': 'XSRF-TOKEN=test; Path=/; SameSite=Lax' } })
      case '/api/admin/audit-event-types': json = ['MODEL_ROUTE_DENIED', 'USER_LOGOUT', 'USER_LOGIN_SUCCESS']; break
      case '/api/admin/audit-actors': json = [actor]; break
      case '/api/admin/audit-events': {
        const content = searchParams.get('eventType') === 'USER_LOGOUT' ? [] : [event]
        json = { content, page: 0, size: 50, totalPages: content.length, totalElements: content.length }; break
      }
      case '/api/admin/models/runtime': json = runtime; break
      case '/api/admin/models/catalog':
      case '/api/admin/models/catalog/effective': json = []; break
      case `/api/admin/models/policies/${orgId}`: json = policy; break
      case `/api/admin/models/route-decisions/${decisionId}`: json = decision; break
      default: return route.fulfill({ status: 404, json: { message: 'Not found' } })
    }
    return route.fulfill({ json })
  })
})

for (const width of [1440, 760]) {
  test(`audit dropdowns overlay the form without layout shifts at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/admin/audit')
    await expect(page.getByRole('button', { name: 'Подробнее' })).toBeVisible()
    for (const name of ['Событие', 'Пользователь']) {
      const field = page.getByRole('combobox', { name, exact: true })
      await field.scrollIntoViewIfNeeded()
      const closed = await field.boundingBox()
      const layout = [page.locator('.audit-filters'), page.locator('.audit-date-presets'),
        page.locator('.audit-table-card'), page.getByRole('button', { name: 'Показать события' })]
      const before = await Promise.all(layout.map(element => element.boundingBox()))
      await field.click()
      const list = page.getByRole('listbox', { name, exact: true })
      await expect(list).toBeVisible()
      const expanded = await field.boundingBox()
      const popup = page.locator('.audit-search-picker__results')
      const options = await popup.boundingBox()
      const after = await Promise.all(layout.map(element => element.boundingBox()))
      for (const key of ['x', 'y', 'width', 'height']) {
        expect(Math.abs(expanded[key] - closed[key])).toBeLessThan(2)
        for (let i = 0; i < before.length; i++) expect(Math.abs(after[i][key] - before[i][key])).toBeLessThan(2)
      }
      expect(options.y >= expanded.y + expanded.height - 1 || options.y + options.height <= expanded.y + 1).toBeTruthy()
      expect(options.y + options.height).toBeLessThanOrEqual(900)
      await expect(popup).toHaveCSS('position', 'fixed')
      expect(await popup.evaluate(element => element.parentElement === element.ownerDocument.body)).toBeTruthy()
      await field.press('ArrowDown')
      await expect(field).toHaveAttribute('aria-activedescendant', /-option-0$/)
      await field.press('Escape')
      await expect(list).toHaveCount(0)
    }
    await page.getByRole('combobox', { name: 'Событие', exact: true }).click()
    await page.getByRole('option', { name: /Выход пользователя/ }).click()
    await page.getByRole('button', { name: 'Показать события' }).click()
    await expect(page.getByText('Событий не найдено')).toBeVisible()
    await expect(page.getByRole('separator', { name: /журнал событий/ })).toHaveCount(0)
    await expect(page.getByRole('table')).toHaveCount(0)
  })
}

test('audit modal preserves controls and opens historical decision directly', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.goto('/admin/audit')
  await page.getByRole('button', { name: 'Подробнее' }).click()
  const modal = page.getByRole('dialog')
  const closeTop = modal.getByRole('button', { name: 'Закрыть окно' })
  await modal.getByText('Сведения для технической поддержки').click()
  const headerBefore = await closeTop.boundingBox()
  await modal.locator('.modal-body').evaluate(element => { element.scrollTop = element.scrollHeight })
  expect(Math.abs((await closeTop.boundingBox()).y - headerBefore.y)).toBeLessThan(3)
  await expect(modal.getByRole('button', { name: 'Закрыть', exact: true })).toBeInViewport()
  await modal.getByRole('link', { name: 'Открыть диагностику запроса' }).click()
  await expect(page).toHaveURL(new RegExp('decisionId=' + decisionId))
  await expect(page.getByText('Правила доступа к AI не были настроены.')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Проверка решения по запросу' })).toBeInViewport()
  const technical = page.getByText('UTF8_STRUCTURAL_UNITS_V2', { exact: true })
  await expect(technical).toBeHidden()
  await page.getByText('Технические сведения решения', { exact: true }).click()
  await expect(technical).toBeVisible()
})

test('clicking the actor filters the whole journal via server without leaving audit', async ({ page }) => {
  await page.goto('/admin/audit?eventType=MODEL_ROUTE_DENIED')
  const actorButton = page.getByRole('button', { name: 'Показать события: admin@example.test' })
  await expect(actorButton).toBeVisible()
  const response = page.waitForResponse(r => {
    const url = new NodeURL(r.url())
    return url.pathname === '/api/admin/audit-events' && url.searchParams.get('userId') === userId
  })
  await actorButton.click()
  const requestUrl = new NodeURL((await response).url())
  expect(requestUrl.searchParams.has('eventType')).toBeFalsy()
  await expect(page).toHaveURL(new RegExp('actorUserId=' + userId))
  await expect(page.getByRole('combobox', { name: 'Пользователь' })).toHaveValue(/admin@example.test/)
})

for (const width of [1280, 390]) {
  test(`audit support report never overlaps its event identifier at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 })
    await page.goto('/admin/audit')
    await page.getByRole('button', { name: 'Подробнее' }).click()
    const modal = page.getByRole('dialog')
    await expect(modal.locator('.audit-json')).toBeHidden()
    await modal.getByText('Сведения для технической поддержки').click()
    const support = modal.locator('.audit-technical-details')
    const button = support.getByRole('button', { name: 'Копировать данные для поддержки' })
    await expect(support.getByRole('button')).toHaveCount(1)
    const identifier = support.locator('.audit-support-list code')
    await button.scrollIntoViewIfNeeded()
    const value = await identifier.boundingBox()
    const control = await button.boundingBox()
    expect(value.y + value.height <= control.y + 1).toBeTruthy()
    await button.focus()
    await identifier.click()
    const after = await button.boundingBox()
    expect(Math.abs(after.x - control.x)).toBeLessThan(2)
    await expect(modal.locator('.audit-json')).toBeHidden()
    await modal.getByText('Диагностический отчёт (JSON)').click()
    await expect(modal.locator('.audit-json')).toBeVisible()
    expect(await modal.locator('.modal-body').evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBeTruthy()
    await expect(modal.getByRole('button', { name: 'Закрыть', exact: true })).toBeInViewport()
  })
}
