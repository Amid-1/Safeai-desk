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

for (const width of [1440, 1280]) {
  test(`models overview and diagnostics keep primary actions visible at ${width}×900`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/admin/models')
    const readiness = page.locator('.models-overview-readiness')
    await expect(readiness).toBeVisible()
    await expect(readiness).not.toHaveAttribute('open', '')
    await expect(page.getByText('Требует внимания', { exact: true })).toBeInViewport()
    await expect(page.getByRole('button', { name: 'Открыть проверку правил' })).toBeInViewport({ ratio: 1 })
    await expect(page.getByText('Как устроено управление моделями?')).toHaveCount(0)
    await readiness.locator('summary').click()
    await expect(readiness).toHaveAttribute('open', '')
    await expect(readiness.locator('li')).toHaveCount(4)
    await readiness.locator('summary').click()
    await page.getByRole('tab', { name: 'Диагностика' }).click()
    const details = page.locator('.models-runtime-details')
    await expect(details).not.toHaveAttribute('open', '')
    await expect(page.getByText('Нет действующей записи')).toBeInViewport()
    await expect(page.getByText('Не выполнялась', { exact: true })).toBeInViewport()
    await expect(page.getByRole('link', { name: 'Открыть журнал аудита' })).toBeInViewport()
    await expect(page.getByRole('textbox', { name: 'Идентификатор решения безопасности (UUID)' })).toBeInViewport({ ratio: 1 })
    await expect(page.getByRole('button', { name: 'Найти решение' })).toBeInViewport({ ratio: 1 })
    await details.locator('summary').click()
    await expect(page.getByText('Лимиты токенов', { exact: true })).toBeVisible()
    await expect(page.getByText('Поддерживаемые возможности')).toBeVisible()
  })
}

for (const width of [1440, 390]) {
  test(`models shortcuts open distinct targets and never run a probe at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    let probeRequests = 0
    await page.route('**/api/admin/models/runtime/probe', route => {
      probeRequests++
      return route.fulfill({ status: 403, json: { message: 'Forbidden' } })
    })
    await page.goto('/admin/models')
    await page.getByRole('button', { name: /Подключение модели/ }).click()
    await expect(page).toHaveURL(/focus=connection/)
    await expect(page.locator('#models-runtime-connection')).toBeFocused()
    // Click the tile's heading, rather than the small action text, to verify the full hit area.
    const characteristics = page.locator('.models-runtime-action-tile').filter({ hasText: 'Провайдер / модель' })
    await characteristics.click({ position: { x: 12, y: 12 } })
    await expect(page.locator('.models-runtime-details')).toHaveAttribute('open', '')
    await characteristics.locator('.models-runtime-tile-indicator').click()
    await expect(page.locator('.models-runtime-details')).not.toHaveAttribute('open', '')
    await expect(characteristics.getByRole('button')).toHaveAttribute('aria-expanded', 'false')
    await characteristics.locator('.models-runtime-tile-indicator').click()
    await expect(page.locator('.models-runtime-details')).toHaveAttribute('open', '')
    await page.getByRole('button', { name: 'Условия обработки данных' }).click()
    await expect(page.getByText('Хранение и использование данных')).toBeVisible()
    await page.getByRole('tab', { name: 'Обзор' }).click()
    await page.getByRole('button', { name: /Проверка соединения/ }).click()
    await expect(page).toHaveURL(/focus=probe/)
    const permission = page.locator('#models-runtime-probe')
    await expect(permission).toBeFocused()
    await expect(permission).toBeInViewport({ ratio: 1 })
    await expect(page.getByRole('button', { name: 'Проверить доступность' })).toHaveCount(0)
    expect(probeRequests).toBe(0)
  })
}

for (const width of [1440, 390]) {
  test(`catalog history stays over diagnostics and uses the full modal width at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const effective = { id: '11111111-1111-4111-8111-111111111111', modelKey: 'mock:mock-safeai',
      version: 1, provider: 'mock', providerModelId: 'mock-safeai', displayName: 'mock-safeai',
      lifecycle: 'ACTIVE', maxInputTokens: 64000, maxOutputTokens: 2048, capabilities: [],
      inputModalities: ['TEXT'], outputModalities: ['TEXT'], retentionStatus: 'NOT_DECLARED',
      retentionDays: null, trainingUseStatus: 'NOT_DECLARED', pricingStatus: 'FREE', pricingComplete: true,
      inputUsdPer1mTokens: '0', outputUsdPer1mTokens: '0', cachedInputUsdPer1mTokens: '0',
      cacheWriteInputUsdPer1mTokens: '0', extraPricingJson: '{}', pricingVersion: 'mock-2026-01',
      effectiveFrom: '2026-08-28T10:00:00Z', source: 'MANUAL', createdByUserId: userId,
      createdAt: '2026-08-28T10:00:00Z' }
    const latest = { ...effective, id: '22222222-2222-4222-8222-222222222222', version: 2 }
    await page.route(/\/api\/admin\/models\/catalog(?:\/|\?|$)/, route => {
      const { pathname } = new NodeURL(route.request().url())
      return route.fulfill({ json: pathname.endsWith('/versions') ? [latest, effective]
        : pathname.endsWith('/effective') ? [effective] : [latest] })
    })
    await page.goto('/admin/models?section=diagnostics')
    const input = page.getByRole('textbox', { name: 'Идентификатор решения безопасности (UUID)' })
    await input.fill('local draft')
    const historyButton = page.getByRole('button', { name: 'Открыть историю версий' })
    await historyButton.click()
    await expect(page).toHaveURL(/section=diagnostics.*versionId=/)
    const modal = page.getByRole('dialog')
    const selected = modal.locator('[data-navigation-target="true"]')
    await expect(selected).toContainText('Версия 1')
    const intro = await modal.locator('.models-history-modal__intro').boundingBox()
    const list = await modal.locator('.models-history-list').boundingBox()
    expect(intro.y + intro.height).toBeLessThanOrEqual(list.y + 1)
    expect(Math.abs(intro.width - list.width)).toBeLessThan(2)
    expect(await modal.locator('.modal-body').evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBeTruthy()
    await modal.getByRole('button', { name: 'Закрыть окно' }).click()
    await expect(modal).toHaveCount(0)
    await expect(page).toHaveURL(/section=diagnostics$/)
    await expect(page.getByRole('tab', { name: 'Диагностика' })).toHaveAttribute('aria-selected', 'true')
    await expect(input).toHaveValue('local draft')
    await expect(historyButton).toBeFocused()
    await historyButton.click()
    await expect(selected).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(modal).toHaveCount(0)
    await expect(page).toHaveURL(/section=diagnostics$/)
  })
}

for (const width of [1440, 390]) {
  test(`access shortcuts open exact editor sections and return without mutations at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    let writes = 0
    await page.route(/\/api\/admin\/models\/policies\//, async route => {
      if (route.request().method() !== 'GET') {
        writes++
        return route.fulfill({ status: 500, json: { message: 'Unexpected mutation' } })
      }
      return route.fallback()
    })
    await page.goto('/admin/models?section=access')
    const shortcuts = [
      ['Модель по умолчанию', 'Доступ', '-default'],
      ['Разрешения и запреты', 'Доступ', '-access'],
      ['Лимиты токенов', 'Лимиты', '-limits'],
      ['Лимит стоимости запроса', 'Бюджет', '-request-cost'],
      ['Месячный бюджет', 'Бюджет', '-monthly-budget'],
      ['Требования к данным и стоимости', 'Требования', '-data'],
    ]
    for (const [name, tab, suffix] of shortcuts) {
      const button = page.getByRole('button', { name: `Настроить: ${name}` })
      const tile = page.locator('.models-policy-action-tile').filter({ has: button })
      await tile.click({ position: { x: 12, y: 12 } })
      const dialog = page.getByRole('dialog')
      await expect(dialog.getByRole('tab', { name: tab, exact: true })).toHaveAttribute('aria-selected', 'true')
      const formId = await dialog.locator('form').getAttribute('id')
      await expect(dialog.locator(`[id="${formId}${suffix}"]`)).toBeFocused()
      await expect(dialog.getByText('Изменений нет')).toBeVisible()
      await dialog.getByRole('button', { name: 'Закрыть окно' }).click()
      await expect(page.getByRole('dialog')).toHaveCount(0)
      await expect(button).toBeFocused()
      await expect(page.getByRole('tab', { name: 'Доступ' })).toHaveAttribute('aria-selected', 'true')
    }
    await page.getByRole('button', { name: 'Открыть проверку правил' }).click()
    await expect(page.getByRole('dialog').getByRole('tab', { name: 'Проверка', exact: true })).toHaveAttribute('aria-selected', 'true')
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).toHaveCount(0)
    expect(writes).toBe(0)
  })
}

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
        page.locator('.audit-table-card'), page.getByRole('button', { name: 'Показать события', exact: true })]
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
    await page.getByRole('button', { name: 'Показать события', exact: true }).click()
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
  await expect(page.getByLabel('Идентификатор решения безопасности (UUID)')).toHaveValue(decisionId)
  const heading = page.getByRole('heading', { name: 'Диагностика запроса' })
  await expect(heading).toBeInViewport({ ratio: 1 })
  await expect.poll(async () => (await heading.boundingBox()).y -
    (await page.locator('.topbar').boundingBox()).height).toBeGreaterThanOrEqual(0)
  const technical = page.getByText('UTF8_STRUCTURAL_UNITS_V2', { exact: true })
  await expect(technical).toBeHidden()
  await page.getByText('Технические сведения решения', { exact: true }).click()
  await expect(technical).toBeVisible()
})

test('direct historical decision link scrolls below the sticky header on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const response = page.waitForResponse(r => new NodeURL(r.url()).pathname === `/api/admin/models/route-decisions/${decisionId}`)
  await page.goto(`/admin/models?section=diagnostics&decisionId=${decisionId}`)
  expect((await response).ok()).toBeTruthy()
  await expect(page.getByLabel('Идентификатор решения безопасности (UUID)')).toHaveValue(decisionId)
  await expect(page.getByText('Правила доступа к AI не были настроены.')).toBeVisible()
  const heading = page.getByRole('heading', { name: 'Диагностика запроса' })
  await expect(heading).toBeInViewport({ ratio: 1 })
  await expect.poll(async () => (await heading.boundingBox()).y -
    (await page.locator('.topbar').boundingBox()).height).toBeGreaterThanOrEqual(0)
})

test('clicking the actor filters the whole journal via server without leaving audit', async ({ page }) => {
  await page.goto('/admin/audit?eventType=MODEL_ROUTE_DENIED')
  const actorButton = page.getByRole('button', { name: 'Показать события: admin@example.test' })
  await expect(actorButton).toBeVisible()
  // The default mock identity is tenant ADMIN, not SUPER_ADMIN.
  await expect(page.getByRole('link', { name: 'Карточка пользователя' })).toHaveCount(0)
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

test('super admin receives entity links and can drag audit divider to the dates and back', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 })
  await page.route('**/api/auth/me', route => route.fulfill({ json: {
    id: userId, organizationId: orgId, email: actor.actorEmail, fullName: actor.actorDisplayName,
    enabled: true, roles: ['SUPER_ADMIN'],
  } }))
  await page.route('**/api/admin/audit-organizations*', route => route.fulfill({ json: [{
    targetOrganizationId: orgId, targetOrganizationName: 'Test tenant',
  }] }))
  await page.goto('/admin/audit')
  await expect(page.getByRole('link', { name: 'Карточка пользователя' })).toHaveAttribute('href', '/admin/users?userId=' + userId)
  await expect(page.getByRole('link', { name: 'Test tenant' })).toHaveAttribute('href', '/admin/organizations?organizationId=' + orgId)
  const separator = page.getByRole('separator', { name: /журнал событий/ })
  const advanced = page.locator('.audit-advanced-filters')
  await advanced.locator('summary').click()
  await expect(advanced).toHaveAttribute('open', '')
  const height = async () => Number(await separator.getAttribute('aria-valuenow'))
  const automaticHeight = async () => page.locator('.audit-page .resizable-scroll-region').evaluate(root => {
    const upper = root.querySelector('.resizable-scroll-region__upper')
    const preferred = root.querySelector('[data-resizable-upper-preferred-boundary]')
    const divider = root.querySelector('[role="separator"]')
    const preferredHeight = Math.ceil(preferred.getBoundingClientRect().bottom - upper.getBoundingClientRect().top + upper.scrollTop + 8)
    return Math.max(Number(divider.getAttribute('aria-valuemin')),
      Math.min(Number(divider.getAttribute('aria-valuemax')), Math.round(root.getBoundingClientRect().height) - preferredHeight))
  })
  await expect.poll(async () => (await height()) - (await automaticHeight())).toBe(0)
  const initial = await height()
  await expect.poll(async () => Number(await separator.getAttribute('aria-valuemax')) - await height()).toBeGreaterThan(32)
  const origin = await separator.boundingBox()
  await page.mouse.move(origin.x + origin.width / 2, origin.y + origin.height / 2)
  await page.mouse.down()
  await page.mouse.move(origin.x + origin.width / 2, origin.y - 450, { steps: 12 })
  await page.mouse.up()
  await expect.poll(height).toBeGreaterThan(initial + 32)
  await page.locator('.audit-page .resizable-scroll-region__upper').evaluate(upper => { upper.scrollTop = 0 })
  const dates = await page.getByLabel('Конец периода').boundingBox()
  const top = await separator.boundingBox()
  expect(top.y).toBeGreaterThanOrEqual(dates.y + dates.height - 2)
  expect(top.y - dates.y - dates.height).toBeLessThan(35)
  const expandedHeight = await height()
  await page.mouse.move(top.x + top.width / 2, top.y + top.height / 2)
  await page.mouse.down()
  await page.mouse.move(top.x + top.width / 2, top.y + 250, { steps: 12 })
  await page.mouse.up()
  await expect.poll(height).toBeLessThan(expandedHeight)
  await advanced.locator('summary').scrollIntoViewIfNeeded()
  await expect(advanced).toHaveAttribute('open', '')
  await advanced.getByRole('searchbox').scrollIntoViewIfNeeded()
  await expect(advanced.getByRole('searchbox')).toBeInViewport()
  await expect(page.getByRole('button', { name: 'Подробнее' })).toBeVisible()
  await separator.dblclick()
  await expect.poll(height).toBe(initial)
})

for (const width of [1280, 390]) {
  test(`audit support report never overlaps its event identifier at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 })
    await page.goto('/admin/audit')
    const detail = page.getByRole('button', { name: 'Подробнее' })
    const viewport = page.locator('.audit-table-card .resizable-scroll-region__viewport')
    await expect.poll(async () => (await viewport.boundingBox()).height).toBeGreaterThan(100)
    if (width === 390) {
      const separator = page.getByRole('separator', { name: /журнал событий/ })
      const height = async () => Number(await separator.getAttribute('aria-valuenow'))
      const initial = await height()
      await separator.press('End')
      await expect.poll(height).toBeGreaterThan(initial)
      await separator.press('Home')
      await expect.poll(height).toBe(initial)
      await expect.poll(async () => (await viewport.boundingBox()).height).toBeGreaterThan(100)
    }
    await detail.click()
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
