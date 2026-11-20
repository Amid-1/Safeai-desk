// Dashboard production-bundle browser regressions; no real tenant data.
const { test, expect } = require('@playwright/test')
const { URL: NodeURL } = require('node:url')

const orgId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const userId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const usage = {
  responses: { assistantMessages: 1 },
  usage: { confirmedInputTokens: 9, confirmedOutputTokens: 16, availableUsageMessages: 1,
    partialUsageMessages: 0, missingUsageMessages: 0, usageNotApplicableMessages: 0 },
  cost: { knownCostUsd: '0', currency: 'USD', pricedMessages: 1, freeMessages: 0,
    unpricedMessages: 0, pricingFailedMessages: 0, pricingNotApplicableMessages: 0 },
}
async function mockDashboard(page, role) {
  const requests = []
  await page.route('**/api/**', route => {
    const url = new NodeURL(route.request().url())
    requests.push({ path: url.pathname, query: url.searchParams, method: route.request().method() })
    switch (url.pathname) {
      case '/api/auth/me': return route.fulfill({json: {id: userId, organizationId: orgId,
        email: 'admin@example.test', fullName: 'Admin', enabled: true, roles: [role]}})
      case '/api/auth/csrf': return route.fulfill({status: 204, headers: {'set-cookie': 'XSRF-TOKEN=test; Path=/; SameSite=Lax'}})
      case '/api/organizations': return route.fulfill({json: {page: 0, size: 1, totalElements: 17, totalPages: 17,
        content: [{id: orgId, name: 'Demo Company', type: 'TENANT', enabled: true, protected: false,
          version: 1, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z'}]}})
      case '/api/users/search/statistics': return route.fulfill({json: {total: 42, administrators: 2, users: 40, enabled: 35, disabled: 7}})
      case '/api/admin/models/runtime': return route.fulfill({json: {provider: 'mock', model: 'mock-safeai', enabled: true,
        routingMode: 'SINGLE_PROVIDER_STATIC', maxInputTokens: 64000, maxOutputTokens: 2048,
        toolsSupported: false, visionSupported: false, structuredOutputSupported: false, dataRetentionStatus: 'NOT_DECLARED',
        healthStatus: 'NOT_PROBED', pricingStatus: 'FREE', inputUsdPer1mTokens: '0', outputUsdPer1mTokens: '0', pricingVersion: 'mock-2026-01'}})
      case '/api/admin/usage/daily': return route.fulfill({json: [{...usage, usageDate: url.searchParams.get('dateFrom').slice(0, 10), aggregationZone: 'UTC'}]})
      case '/api/admin/models/policies/budget-summary': return route.fulfill({json: {periodStart: '2026-11-01T00:00:00Z', periodEnd: '2026-12-01T00:00:00Z', asOf: '2026-11-20T00:00:00Z', total: 17, enabled: 15, within: 1, nearLimit: 2, exceeded: 0, noLimit: 4, unknown: 1, disabled: 1, unconfigured: 8}})
      case '/api/admin/usage/organizations': return route.fulfill({json: {page: 0, size: Number(url.searchParams.get('size')), first: true, last: true,
        hasNext: false, hasPrevious: false, content: url.searchParams.get('budgetStatus') === 'EXCEEDED' ? [] : [{...usage, organizationId: orgId, organizationName: 'Demo Company'}]}})
      default: return route.fulfill({status: 404, json: {message: 'Not found'}})
    }
  })
  return requests
}

for (const [width, height] of [[1280, 720], [390, 844]]) {
  test(`dashboard uses global aggregates and bounded server ranking at ${width}×${height}`, async ({page}) => {
    await page.setViewportSize({width, height})
    const requests = await mockDashboard(page, 'SUPER_ADMIN')
    await page.goto('/admin/dashboard')
    await expect(page.getByRole('heading', {name: 'Обзор платформы'})).toBeVisible()
    await expect(page.locator('header.admin-page-header')).toHaveCount(1)
    await expect(page.locator('.usage-kpi__label').filter({hasText: 'Попытки ответа AI'})).toBeVisible()
    await expect(page.getByRole('region', {name: 'Организации', exact: true})).toContainText('17')
    await expect(page.getByRole('region', {name: 'Пользователи'})).toContainText('42')
    const chart = page.getByRole('region', {name: 'График использования AI по дням'})
    await expect(chart.locator('svg.recharts-surface').first()).toBeVisible()
    const plot = chart.locator('.usage-chart-viewport')
    const originalHeight = (await plot.boundingBox()).height
    await chart.getByRole('button', {name: 'Развернуть график'}).click()
    await expect.poll(async () => (await plot.boundingBox()).height).toBeGreaterThan(originalHeight)
    await chart.getByRole('button', {name: 'Свернуть график'}).click()
    const rank = page.getByRole('region', {name: 'Организации по подтверждённым токенам'})
    await expect(rank.getByRole('link', {name: 'Demo Company'})).toHaveAttribute('href', new RegExp('organizationId=' + orgId))
    expect(requests.find(r => r.path === '/api/admin/usage/organizations').query.get('size')).toBe('5')
    expect(requests.find(r => r.path === '/api/admin/usage/organizations').query.get('order')).toBe('total')
    if (width >= 1280) {
      const scroll = chart.locator('.usage-daily-scroll')
      await expect.poll(() => scroll.evaluate(el => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(1)
    }
    await page.getByLabel('Период', {exact: true}).selectOption('7')
    await expect.poll(() => requests.filter(r => r.path === '/api/admin/usage/daily').length).toBe(2)
    await expect(page.getByRole('link', {name: 'Полный отчёт'})).toHaveAttribute('href', /report=daily&dateFrom=/)
    expect(requests.some(r => r.path.endsWith('/probe') || r.method !== 'GET')).toBe(false)
    expect(await page.evaluate(() => globalThis.document.documentElement.scrollWidth)).toBeLessThanOrEqual(width + 1)
    const picker = chart.locator('details.usage-chart-links--compact-period')
    await expect(picker).toBeVisible()
    await expect(picker).not.toHaveAttribute('open', '')
    await picker.locator('summary').click()
    await expect(picker.getByRole('button')).toHaveCount(7)
    const populated = picker.getByRole('button', {name: /Есть записи/})
    const empty = picker.getByRole('button', {name: /Нет записей/})
    await expect(populated).toHaveCount(1)
    await expect(empty).toHaveCount(6)
    await expect(populated).toHaveCSS('background-color', 'rgb(238, 244, 255)')
    await expect(empty.first()).toHaveCSS('background-color', 'rgb(255, 255, 255)')
    await populated.focus()
    await populated.press('Enter')
    await expect(page).toHaveURL(/\/admin\/usage\?report=summary&dateFrom=/)
  })
}

test('tenant administrator cannot open dashboard or request platform aggregates', async ({page}) => {
  const requests = await mockDashboard(page, 'ADMIN')
  await page.goto('/admin/dashboard')
  await expect(page.getByRole('alert')).toContainText('Недостаточно прав')
  await expect(page.getByRole('link', {name: 'Обзор'})).toHaveCount(0)
  expect(requests.filter(r => !r.path.startsWith('/api/auth/'))).toHaveLength(0)
})

test('superadmin logo, root and authenticated login lead to overview', async ({page}) => {
  await mockDashboard(page, 'SUPER_ADMIN')
  for (const path of ['/', '/login']) {
    await page.goto(path)
    await expect(page).toHaveURL(/\/admin\/dashboard$/)
    await expect(page.getByRole('heading', {name: 'Обзор платформы'})).toBeVisible()
  }
  await page.getByRole('link', {name: 'Посмотреть бюджеты организаций'}).click()
  await expect(page).toHaveURL(/\/admin\/usage/)
  await page.getByTitle('На главную').click()
  await expect(page).toHaveURL(/\/admin\/dashboard$/)
  await expect(page.getByRole('heading', {name: 'Обзор платформы'})).toBeVisible()
})

test('budget drill-down sends server filter even for a zero category, and clears it', async ({page}) => {
  const requests = await mockDashboard(page, 'SUPER_ADMIN')
  await page.goto('/admin/dashboard')
  await page.getByRole('link', {name: /Превысили лимит: 0/}).click()
  await expect(page).toHaveURL(/report=organizations.*budgetStatus=EXCEEDED/)
  await expect(page.getByRole('region', {name: 'Фильтр бюджета'})).toBeVisible()
  await expect(page.getByText('Нет организаций в этой категории')).toBeVisible()
  expect(requests.some(r => r.path === '/api/admin/usage/organizations' && r.query.get('budgetStatus') === 'EXCEEDED')).toBe(true)
  await page.getByRole('button', {name: 'Снять фильтр бюджета'}).click()
  await expect(page).not.toHaveURL(/budgetStatus=/)
  await expect(page.getByRole('region', {name: 'Фильтр бюджета'})).toHaveCount(0)
})

test('dashboard search preserves scroll position and layout while results change', async ({page}) => {
  await page.setViewportSize({width: 1440, height: 900})
  await mockDashboard(page, 'SUPER_ADMIN')
  const matches = query => query === 'many' ? 10 : query === 'one' ? 1 : 0
  await page.route('**/api/organizations/search?*', route => {
    const query = new NodeURL(route.request().url()).searchParams.get('query')
    const count = matches(query)
    return route.fulfill({json: {page: 0, size: 1, totalElements: count, totalPages: count,
      content: count ? [{id: orgId, name: 'Demo Company', type: 'TENANT', enabled: true, protected: false,
        version: 1, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z'}] : []}})
  })
  await page.route('**/api/admin/usage/organizations?*', async route => {
    const query = new NodeURL(route.request().url()).searchParams.get('organizationQuery')
    await new Promise(resolve => globalThis.setTimeout(resolve, 350))
    if (!query) return route.fulfill({json: {page: 0, size: 5, first: true, last: true, hasNext: false, hasPrevious: false,
      content: [{...usage, organizationId: orgId, organizationName: 'Demo Company'},
        {...usage, organizationId: userId, organizationName: 'Organization A'}]}})
    const count = matches(query)
    return route.fulfill({json: {page: 0, size: 10, first: true, last: true, hasNext: false, hasPrevious: false,
      content: Array.from({length: count}, (_, i) => ({...usage,
        organizationId: 'aaaaaaaa-aaaa-4aaa-8aaa-' + String(i + 1).padStart(12, '0'), organizationName: 'Компания ' + (i + 1)}))}})
  })
  await page.goto('/admin/dashboard')
  const region = page.getByRole('region', {name: 'Организации по подтверждённым токенам'})
  const results = region.getByRole('region', {name: 'Результаты поиска организаций'})
  const input = region.getByRole('searchbox')
  const footer = page.getByRole('region', {name: 'Безопасность и управление'})
  const pager = region.locator('.platform-dashboard__search-pager')
  const table = results.getByRole('table')
  await expect(region.getByRole('link', {name: 'Demo Company'})).toBeVisible()
  await expect(page.locator('.usage-kpis--four')).toBeVisible()
  await expect(results.getByRole('button', {name: 'Показать на графике'})).toHaveCount(2)
  await expect(pager).toBeHidden()
  const compactHeight = (await results.boundingBox()).height
  // The initial viewport has only its natural table height.
  expect(Math.abs(compactHeight - (await table.boundingBox()).height)).toBeLessThan(2)
  await footer.scrollIntoViewIfNeeded()
  await input.scrollIntoViewIfNeeded()
  const locators = [input, results, footer]
  const compact = await Promise.all(locators.map(locator => locator.boundingBox()))
  await input.fill('many')
  await expect(results).toHaveAttribute('aria-busy', 'true')
  await expect(region).toHaveClass(/platform-dashboard__panel--search-active/)
  await expect(pager).not.toHaveAttribute('hidden', '')
  expect(Math.abs((await input.boundingBox()).y - compact[0].y)).toBeLessThan(2)
  expect((await results.boundingBox()).height).toBeGreaterThan(compactHeight)
  // One expansion is intentional; the layout must not move again during search.
  const baseline = await Promise.all(locators.map(locator => locator.boundingBox()))
  const sameLayout = async () => {
    const current = await Promise.all(locators.map(locator => locator.boundingBox()))
    for (let i = 0; i < baseline.length; i++) {
      expect(Math.abs(current[i].y - baseline[i].y)).toBeLessThan(2)
      expect(Math.abs(current[i].height - baseline[i].height)).toBeLessThan(2)
    }
  }
  for (const [query, count] of [['many', 10], ['one', 1], ['none', 0]]) {
    if (query !== 'many') await input.fill(query)
    await expect(results).toHaveAttribute('aria-busy', 'true')
    await sameLayout()
    await expect(region.getByText('Найдено: ' + count, {exact: true})).toBeVisible()
    await expect(results).toHaveAttribute('aria-busy', 'false')
    await expect(results.getByRole('button', {name: 'Показать на графике'})).toHaveCount(count)
    await sameLayout()
    if (query === 'many') {
      expect(await results.evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true)
    }
  }
  await expect(results.getByText('Организации не найдены.')).toBeVisible()
  await region.getByRole('button', {name: 'Сбросить'}).click()
  await expect(results).toHaveAttribute('aria-busy', 'true')
  expect(Math.abs((await results.boundingBox()).height - baseline[1].height)).toBeLessThan(2)
  await expect(results.getByRole('link', {name: 'Demo Company'})).toBeVisible()
  await expect(pager).toBeHidden()
  expect(Math.abs((await results.boundingBox()).height - compactHeight)).toBeLessThan(2)
  // Clearing by keyboard also returns to the natural ranking height.
  await input.fill('one')
  await expect(region.getByText('Найдено: 1', {exact: true})).toBeVisible()
  await input.fill('')
  await expect(results).toHaveAttribute('aria-busy', 'true')
  expect(Math.abs((await results.boundingBox()).height - baseline[1].height)).toBeLessThan(2)
  await expect(results.getByRole('link', {name: 'Demo Company'})).toBeVisible()
  await expect(pager).toBeHidden()
  expect(Math.abs((await results.boundingBox()).height - compactHeight)).toBeLessThan(2)
})
