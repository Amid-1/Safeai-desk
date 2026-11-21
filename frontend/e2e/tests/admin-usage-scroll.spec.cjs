// Real layout and navigation regression: the global workspace CSS must not clip usage.
const { test, expect } = require('@playwright/test')
const { URL: NodeURL } = require('node:url')

const orgId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const userId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const identity = { userId, userEmail: 'admin@example.test' }
const amounts = { inputTokens: '9', outputTokens: '16', totalTokens: '25',
  costUsd: '0', currency: 'USD', usageComplete: true, pricingComplete: true }
const model = 'mock-safeai'
const dates = 'dateFrom=2026-11-01&dateTo=2026-11-16'

test.beforeEach(async ({ page }) => {
  await page.route('**/api/**', route => {
    const { pathname } = new NodeURL(route.request().url())
    const slice = row => ({ content: [row], page: 0, size: 50, hasPrevious: false, hasNext: false })
    let json
    switch (pathname) {
      case '/api/auth/me': json = { id: userId, organizationId: orgId, email: identity.userEmail,
        fullName: 'Admin', enabled: true, roles: ['ADMIN'] }; break
      case '/api/auth/csrf': return route.fulfill({ status: 204, headers: { 'set-cookie': 'XSRF-TOKEN=test; Path=/; SameSite=Lax' } })
      case '/api/admin/usage/summary': json = slice({ ...amounts, ...identity, model }); break
      case '/api/admin/usage/users': json = slice({ ...amounts, ...identity }); break
      case '/api/admin/usage/models': json = [{ ...amounts, model }]; break
      case '/api/admin/usage/daily': json = [{ ...amounts, usageDate: '2026-11-15', aggregationZone: 'UTC' }]; break
      default: return route.fulfill({ status: 404, json: { message: 'Not found' } })
    }
    return route.fulfill({ json })
  })
})

test('comparison is a separate super-admin tab, retries, switches reports and restores filters', async ({page}) => {
  await page.route('**/api/auth/me', route => route.fulfill({json: {id: userId, organizationId: orgId,
    email: identity.userEmail, fullName: 'Super Admin', enabled: true, roles: ['SUPER_ADMIN']}}))
  let unavailable = true
  await page.route('**/api/admin/usage/organizations?**', route => {
    if (unavailable) return route.fulfill({status: 503, json: {message: 'internal'}})
    return route.fulfill({json: {page: 0, size: 50, first: true, last: true, hasNext: false, hasPrevious: false, content: [{
      organizationId: orgId, organizationName: 'Demo Company', responses: {assistantMessages: 1},
      usage: {confirmedInputTokens: 9, confirmedOutputTokens: 16, availableUsageMessages: 1, partialUsageMessages: 0,
        missingUsageMessages: 0, usageNotApplicableMessages: 0},
      cost: {knownCostUsd: '0', currency: 'USD', pricedMessages: 1, freeMessages: 0, unpricedMessages: 0,
        pricingFailedMessages: 0, pricingNotApplicableMessages: 0},
    }]}})
  })
  await page.goto(`/admin/usage?report=organizations&${dates}`)
  await expect(page.getByText(/Сравнение организаций недоступно/)).toBeVisible()
  await page.getByRole('tab', {name: 'По пользователям'}).click()
  await expect(page).toHaveURL(/report=users/)
  await expect(page.getByRole('columnheader', {name: 'Пользователь'})).toBeVisible()
  await page.getByRole('tab', {name: 'По организациям'}).click()
  await expect(page).toHaveURL(/report=organizations/)
  await expect(page.getByRole('button', {name: 'Повторить'})).toBeVisible()
  unavailable = false
  await page.getByRole('button', {name: 'Повторить'}).click()
  await expect(page.getByRole('button', {name: 'Demo Company'})).toBeVisible()
  await page.getByRole('button', {name: 'График', exact: true}).click()
  const plot = page.locator('.usage-breakdown-chart__frame')
  const surface = plot.getByRole('application')
  await expect(surface).toBeVisible()
  const normal = await plot.evaluate(el => el.clientHeight)
  const svg = await surface.evaluate(el => el.viewBox.baseVal.height)
  await page.getByRole('button', {name: 'Развернуть', exact: true}).click()
  await expect.poll(() => plot.evaluate(el => el.clientHeight)).toBeGreaterThan(normal + 20)
  await expect.poll(() => surface.evaluate(el => el.viewBox.baseVal.height)).toBeGreaterThan(svg + 20)
  await expect.poll(() => surface.evaluate(el => Math.abs(el.viewBox.baseVal.height - el.getBoundingClientRect().height))).toBeLessThanOrEqual(1)
  await expect(page.locator('.usage-results-footer')).toBeInViewport()
  await page.getByRole('button', {name: 'Свернуть', exact: true}).click()
  await expect.poll(() => surface.evaluate(el => el.viewBox.baseVal.height)).toBe(svg)
  const comparisonBeforeSelection = page.url()
  const activeLabel = plot.locator('text.usage-chart-action-tick').filter({hasText: /^Demo Company$/})
  await activeLabel.click()
  const activeSelection = page.getByRole('region', {name: 'Выбранная запись графика'})
  await expect(activeSelection).toContainText('Всего: 25')
  await expect(activeSelection).toBeInViewport()
  await expect(page).toHaveURL(comparisonBeforeSelection)
  await activeSelection.getByRole('button', {name: 'Закрыть сведения'}).click()
  const inputBar = surface.locator('path[fill="#356de8"]').first()
  await expect(inputBar).toBeVisible()
  await inputBar.click()
  await expect(activeSelection).toContainText('Demo Company')
  await expect(page).toHaveURL(comparisonBeforeSelection)
  await activeSelection.getByRole('button', {name: 'Закрыть сведения'}).click()
  await page.getByText('Детализация и карточки · доступно с клавиатуры').click()
  await page.getByRole('button', {name: 'Demo Company'}).click()
  const comparisonUrl = page.url()
  const comparisonIndex = await page.evaluate(() => globalThis.history.state.idx)
  await page.getByRole('button', {name: 'Детализация использования'}).click()
  await expect(page).toHaveURL(new RegExp(`organizationId=${orgId}`))
  await expect(page.getByRole('tab', {name: 'Детализация'})).toHaveAttribute('aria-selected', 'true')
  const detailUrl = page.url()
  expect(await page.evaluate(() => globalThis.history.state.idx)).toBe(comparisonIndex + 1)
  await page.goBack()
  await expect(page).toHaveURL(comparisonUrl)
  await expect(page.getByRole('tab', {name: 'По организациям'})).toHaveAttribute('aria-selected', 'true')
  await expect(page).toHaveURL(/report=organizations/)
  await expect(page.locator('.usage-scope')).toContainText('Все организации')
  await page.goForward()
  await expect(page).toHaveURL(detailUrl)
  await expect(page.getByRole('tab', {name: 'Детализация'})).toHaveAttribute('aria-selected', 'true')
})

for (const [width, height] of [[1280, 720], [1920, 1080]]) {
test(`an organization without usage stays in comparison and keeps its card and budget available at ${width}×${height}`, async ({page}) => {
  await page.setViewportSize({width, height})
  await page.route('**/api/auth/me', route => route.fulfill({json: {id: userId, organizationId: orgId,
    email: identity.userEmail, fullName: 'Super Admin', enabled: true, roles: ['SUPER_ADMIN']}}))
  await page.route('**/api/admin/usage/organizations?**', route => route.fulfill({json: {
    page: 0, size: 50, first: true, last: true, hasNext: false, hasPrevious: false,
    content: [{organizationId: orgId, organizationName: 'Сокол', responses: {assistantMessages: 0},
      usage: {confirmedInputTokens: 0, confirmedOutputTokens: 0, availableUsageMessages: 0, partialUsageMessages: 0,
        missingUsageMessages: 0, usageNotApplicableMessages: 0},
      cost: {knownCostUsd: null, currency: 'USD', pricedMessages: 0, freeMessages: 0, unpricedMessages: 0,
        pricingFailedMessages: 0, pricingNotApplicableMessages: 0}}],
  }}))
  await page.route(`**/api/admin/models/policies/${orgId}/budget-status`, route => route.fulfill({json: {
    organizationId: orgId, policyVersion: 0, policyEnabled: false, enforcement: 'SOFT',
    periodStart: '2026-11-01T00:00:00Z', periodEnd: '2026-12-01T00:00:00Z', asOf: '2026-11-19T12:00:00Z',
    monthlyBudgetUsd: null, knownActualUsd: '0', reservedUsd: '0', knownCommittedUsd: '0',
    unknownCommittedCostCount: 0, remainingUsd: null, status: 'UNCONFIGURED',
  }}))
  await page.goto(`/admin/usage?report=organizations&${dates}`)
  await expect(page.getByText('Нет использования за период')).toBeVisible()
  await expect(page.getByRole('button', {name: 'Сокол', exact: true})).toHaveCount(0)
  await page.getByRole('button', {name: 'Показать бюджет'}).click()
  await expect(page.getByText('Бюджет: Не задан')).toBeVisible()
  await page.getByRole('button', {name: 'График', exact: true}).click()
  // Recharts renders tick lines and labels separately; click the visible SVG
  // text, not the private tick-line container.
  const label = page.locator('.usage-breakdown-chart__plot text.usage-chart-action-tick').filter({hasText: /^Сокол$/})
  await expect(label).toBeVisible()
  await expect(label).toHaveClass(/\busage-chart-action-tick\b/)
  const viewport = page.locator('.usage-data-viewport')
  await expect(viewport).toHaveCSS('overflow-anchor', 'none')
  // A clipped label is not clickable until the user scrolls to it. Measure
  // the click itself, never Playwright's automatic scroll into view.
  if (height === 1080) {
    await expect(label).toBeInViewport({ratio: 1})
    expect(await viewport.evaluate(el => el.scrollTop)).toBe(0)
  } else {
    await label.scrollIntoViewIfNeeded()
    await expect(label).toBeInViewport({ratio: 1})
  }
  const scrollBefore = await viewport.evaluate(el => el.scrollTop)
  const labelBox = await label.boundingBox()
  await page.mouse.move(labelBox.x + labelBox.width / 2, labelBox.y + labelBox.height / 2)
  await page.mouse.down()
  expect(await viewport.evaluate(el => el.scrollTop)).toBe(scrollBefore)
  await page.mouse.up()
  await expect(page.getByRole('region', {name: 'Выбранная запись графика'})).toContainText('Нет использования за выбранный период')
  await expect(page.getByRole('region', {name: 'Выбранная запись графика'}).getByRole('link', {name: 'Карточка организации'})).toHaveAttribute('href', `/admin/organizations?organizationId=${orgId}`)
  await expect(page.getByRole('button', {name: 'Детализация использования'})).toHaveCount(0)
  await expect(page).toHaveURL(/report=organizations/)
  await expect(page).not.toHaveURL(/organizationId=/)
  await expect(page.getByRole('region', {name: 'Выбранная запись графика'})).toBeInViewport()
  expect(await viewport.evaluate(el => el.scrollTop)).toBe(scrollBefore)
  const selection = page.getByRole('region', {name: 'Выбранная запись графика'})
  await expect(selection).toHaveAttribute('tabindex', '0')
  await expect(selection).toHaveCSS('overflow-y', 'auto')
  await expect.poll(() => viewport.evaluate(el => {
    const card = el.querySelector('.usage-chart-selection--organization')
    return card ? Math.abs(parseFloat(el.ownerDocument.defaultView.getComputedStyle(card).maxHeight) - el.clientHeight) : Infinity
  })).toBeLessThanOrEqual(1)
  expect(await selection.evaluate(el => el.getBoundingClientRect().height)).toBeLessThanOrEqual(await viewport.evaluate(el => el.clientHeight + 1))
  const budgetButton = selection.getByRole('button', {name: 'Показать бюджет'})
  // Reaching an action in the sticky card is a separate interaction. If the
  // compact scroller requires positioning it, do so before taking its baseline.
  await budgetButton.scrollIntoViewIfNeeded()
  // Fractional CSS pixels can be rounded differently by the scrollport and
  // IntersectionObserver (especially on Retina). Permit at most one CSS pixel
  // at each edge, not an arbitrary visible fraction of the button.
  await expect.poll(() => budgetButton.evaluate(el => new Promise(resolve => {
    const observer = new el.ownerDocument.defaultView.IntersectionObserver(([entry]) => {
      observer.disconnect()
      const bounds = entry.boundingClientRect
      const visible = entry.intersectionRect
      resolve(entry.isIntersecting ? Math.max(0, visible.left - bounds.left, visible.top - bounds.top,
        bounds.right - visible.right, bounds.bottom - visible.bottom) : Infinity)
    })
    observer.observe(el)
  }))).toBeLessThanOrEqual(1)
  await expect(budgetButton).toBeEnabled()
  await expect.poll(() => budgetButton.evaluate(el => {
    const box = el.getBoundingClientRect()
    return el.contains(el.ownerDocument.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2))
  })).toBe(true)
  const budgetBox = await budgetButton.boundingBox()
  const budgetScrollBefore = await viewport.evaluate(el => el.scrollTop)
  // A real pointer click cannot silently auto-scroll an obstructed action.
  await page.mouse.click(budgetBox.x + budgetBox.width / 2, budgetBox.y + budgetBox.height / 2)
  await expect(selection).toContainText('Бюджет: Не задан')
  expect(await viewport.evaluate(el => el.scrollTop)).toBe(budgetScrollBefore)
  expect(await selection.evaluate(el => el.getBoundingClientRect().height)).toBeLessThanOrEqual(await viewport.evaluate(el => el.clientHeight + 1))
  const budgetStatus = selection.getByText('Бюджет: Не задан', {exact: true})
  await budgetStatus.scrollIntoViewIfNeeded()
  await expect(budgetStatus).toBeInViewport()
  const close = page.getByRole('button', {name: 'Закрыть сведения'})
  await expect(close).toHaveClass(/\bsecondary-button\b/)
  await close.click()
  await expect(page.getByRole('region', {name: 'Выбранная запись графика'})).toHaveCount(0)
  await page.getByText('Детализация и карточки · доступно с клавиатуры').click()
  const choice = page.getByRole('button', {name: 'Сокол', exact: true})
  await expect(choice).toHaveAttribute('aria-pressed', 'false')
  await expect(choice).toHaveCSS('background-color', 'rgb(244, 247, 252)')
  await choice.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('region', {name: 'Выбранная запись графика'})).toContainText('Нет использования за выбранный период')
  await expect(choice).toHaveAttribute('aria-pressed', 'true')
  await expect(choice).toHaveCSS('background-color', 'rgb(40, 91, 230)')
  await expect(page.getByRole('button', {name: 'Детализация использования'})).toHaveCount(0)
  await expect(page).not.toHaveURL(/organizationId=/)
})
}

test('a distant organization selection remains visible without scrolling to the chart end', async ({page}) => {
  await page.setViewportSize({width: 1440, height: 900})
  await page.route('**/api/auth/me', route => route.fulfill({json: {id: userId, organizationId: orgId,
    email: identity.userEmail, fullName: 'Super Admin', enabled: true, roles: ['SUPER_ADMIN']}}))
  await page.route('**/api/admin/usage/organizations?**', route => route.fulfill({json: {
    page: 0, size: 50, first: true, last: true, hasNext: false, hasPrevious: false,
    content: Array.from({length: 50}, (_, i) => ({
      organizationId: `aaaaaaaa-aaaa-4aaa-8aaa-${String(i+1).padStart(12,'0')}`, organizationName: `Организация ${i+1}`,
      responses: {assistantMessages: 0}, usage: {confirmedInputTokens: 0, confirmedOutputTokens: 0, availableUsageMessages: 0,
        partialUsageMessages: 0, missingUsageMessages: 0, usageNotApplicableMessages: 0},
      cost: {knownCostUsd: null, currency: 'USD', pricedMessages: 0, freeMessages: 0, unpricedMessages: 0,
        pricingFailedMessages: 0, pricingNotApplicableMessages: 0},
    })),
  }}))
  await page.goto(`/admin/usage?report=organizations&${dates}`)
  await page.getByRole('button', {name: 'График', exact: true}).click()
  await page.getByLabel('Строк на графике').selectOption('50')
  const plot = page.locator('.usage-breakdown-chart__frame')
  const surface = plot.getByRole('application')
  const label = plot.locator('text.usage-chart-action-tick').filter({hasText: /^Организация 20$/})
  // Recharts applies the new rows and ResizeObserver dimensions asynchronously.
  // Do not interact with a tick from the intermediate SVG tree.
  await expect.poll(() => surface.evaluate(el => el.viewBox.baseVal.height)).toBeGreaterThanOrEqual(50 * 58)
  await expect.poll(() => surface.evaluate(el => Math.abs(el.viewBox.baseVal.height - el.getBoundingClientRect().height))).toBeLessThanOrEqual(1)
  await expect(plot.locator('text.usage-chart-action-tick')).toHaveCount(50)
  // A final render can replace the tick during scrollIntoViewIfNeeded.
  // Retry only the pre-click positioning with a fresh locator, never the assertions after the real click.
  await expect(async () => {
    await label.scrollIntoViewIfNeeded({timeout: 1000})
    await expect(label).toBeInViewport({timeout: 1000})
  }).toPass({timeout: 7500})
  const viewport = page.locator('.usage-data-viewport')
  const before = await viewport.evaluate(el => el.scrollTop)
  expect(before).toBeGreaterThan(500)
  await expect(label).toBeInViewport()
  const labelBox = await label.boundingBox()
  await page.mouse.move(labelBox.x + labelBox.width / 2, labelBox.y + labelBox.height / 2)
  await page.mouse.down()
  expect(await viewport.evaluate(el => el.scrollTop)).toBe(before)
  await page.mouse.up()
  const panel = page.getByRole('region', {name: 'Выбранная запись графика'})
  await expect(panel).toContainText('Организация 20')
  await expect(panel).toBeInViewport()
  await expect(page).not.toHaveURL(/organizationId=/)
  await expect(page).toHaveURL(/report=organizations/)
  const after = await viewport.evaluate(el => el.scrollTop)
  expect(after).toBe(before)
})

test('missing organization endpoint is an error, not zero data; admin cannot request the report', async ({page}) => {
  let role = 'SUPER_ADMIN'
  let requests = 0
  await page.route('**/api/auth/me', route => route.fulfill({json: {id: userId, organizationId: orgId,
    email: identity.userEmail, fullName: 'Admin', enabled: true, roles: [role]}}))
  await page.route('**/api/admin/usage/organizations?**', route => {
    requests++
    return route.fulfill({status: 404, json: {message: 'internal diagnostic must not be displayed'}})
  })
  await page.goto(`/admin/usage?report=organizations&${dates}`)
  await expect(page.getByText(/Сервер не предоставляет отчёт по организациям/)).toBeVisible()
  await expect(page.getByText('Отчёт недоступен', {exact: true})).toBeVisible()
  await expect(page.getByText('internal diagnostic must not be displayed')).toHaveCount(0)
  await expect(page.getByRole('button', {name: 'Скачать CSV страницы'})).toBeDisabled()
  const requestsBeforeSwitch = requests
  expect(requestsBeforeSwitch).toBeGreaterThan(0)
  await page.getByRole('tab', {name: 'По дням'}).click()
  await expect(page.getByRole('columnheader', {name: 'Дата'})).toBeVisible()
  expect(requests).toBe(requestsBeforeSwitch)
  role = 'ADMIN'
  await page.goto(`/admin/usage?report=organizations&${dates}`)
  await expect(page.getByRole('tab', {name: 'По организациям'})).toHaveCount(0)
  await expect(page.getByRole('table')).toBeVisible()
  expect(requests).toBe(requestsBeforeSwitch)
})

test('organization table grip is above rows and budget retries without affecting other tenants', async ({page}) => {
  await page.setViewportSize({width: 1440, height: 900})
  await page.route('**/api/auth/me', route => route.fulfill({json: {id: userId, organizationId: orgId,
    email: identity.userEmail, fullName: 'Super Admin', enabled: true, roles: ['SUPER_ADMIN']}}))
  await page.route('**/api/admin/usage/organizations?**', route => route.fulfill({json: {
    page: 0, size: 50, first: true, last: true, hasNext: false, hasPrevious: false,
    content: Array.from({length: 17}, (_, i) => ({
      organizationId: i === 0 ? orgId : `aaaaaaaa-aaaa-4aaa-8aaa-${String(i).padStart(12,'0')}`,
      organizationName: i === 0 ? 'Demo Company' : `Organization ${i}`,
      responses: {assistantMessages: 1},
      usage: {confirmedInputTokens: 9, confirmedOutputTokens: 16, availableUsageMessages: 1,
        partialUsageMessages: 0, missingUsageMessages: 0, usageNotApplicableMessages: 0},
      cost: {knownCostUsd: '0', currency: 'USD', pricedMessages: 1, freeMessages: 0,
        unpricedMessages: 0, pricingFailedMessages: 0, pricingNotApplicableMessages: 0},
    })),
  }}))
  let requests = 0
  await page.route(`**/api/admin/models/policies/${orgId}/budget-status`, route => {
    requests++
    if (requests === 1) return route.fulfill({status: 503, json: {message: 'internal'}})
    return route.fulfill({json: {
      organizationId: orgId, policyVersion: 2, policyEnabled: true, enforcement: 'HARD',
      periodStart: '2026-11-01T00:00:00Z', periodEnd: '2026-12-01T00:00:00Z', asOf: '2026-11-19T12:00:00Z',
      monthlyBudgetUsd: '10', knownActualUsd: '1', reservedUsd: '2', knownCommittedUsd: '3',
      unknownCommittedCostCount: 0, remainingUsd: '7', status: 'KNOWN',
    }})
  })
  await page.goto(`/admin/usage?report=organizations&${dates}`)
  await expect(page.getByRole('table').getByRole('row')).toHaveCount(18)
  const grip = page.getByRole('separator', {name: 'Высота области отчёта'})
  const viewport = page.locator('.usage-data-viewport')
  const footer = page.locator('.usage-results-footer')
  const before = await viewport.boundingBox()
  const handle = await grip.boundingBox()
  const bottom = await footer.boundingBox()
  expect(handle.y + handle.height).toBeLessThanOrEqual(before.y + 1)
  await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2)
  await page.mouse.down()
  await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2 - 80, {steps: 5})
  await page.mouse.up()
  await expect.poll(() => viewport.evaluate(el => el.clientHeight)).toBeGreaterThan(before.height)
  expect(Math.abs((await footer.boundingBox()).y - bottom.y)).toBeLessThanOrEqual(2)
  const row = page.getByRole('row', {name: /Demo Company/})
  await row.getByRole('button', {name: 'Показать бюджет'}).click()
  await expect(row.getByText('Бюджет недоступен')).toBeVisible()
  await row.getByRole('button', {name: 'Повторить'}).click()
  await expect(row.getByText('Бюджет: 10 USD')).toBeVisible()
  await expect(row.getByText('Остаток: 7 USD')).toBeVisible()
  expect(requests).toBe(2)
  await expect(page.getByRole('button', {name: 'Показать бюджет'})).toHaveCount(16)
})

test('50 ranked bars retain row space and expand the real SVG, with contained scrolling', async ({page}) => {
  await page.setViewportSize({width: 1600, height: 1000})
  await page.route('**/api/admin/usage/users?**', route => route.fulfill({json: {
    content: Array.from({length: 50}, (_, i) => ({
      ...amounts, userId: `bbbbbbbb-bbbb-4bbb-8bbb-${String(i+1).padStart(12,'0')}`,
      userEmail: `user-${i+1}@example.test`,
    })), page: 0, size: 50, hasPrevious: false, hasNext: true,
  }}))
  await page.goto(`/admin/usage?report=users&${dates}`)
  await page.getByRole('button', {name: 'График', exact: true}).click()
  await page.getByLabel('Строк на графике').selectOption('50')
  const plot = page.locator('.usage-breakdown-chart__frame')
  const surface = plot.getByRole('application')
  await expect.poll(() => surface.evaluate(el => el.viewBox.baseVal.height)).toBeGreaterThanOrEqual(50 * 58)
  const normal = await plot.evaluate(el => el.clientHeight)
  await page.getByRole('button', {name: 'Развернуть', exact: true}).click()
  await expect.poll(() => surface.evaluate(el => el.viewBox.baseVal.height)).toBeGreaterThan(normal + 20)
  await expect.poll(() => surface.evaluate(el => Math.abs(el.viewBox.baseVal.height - el.getBoundingClientRect().height))).toBeLessThanOrEqual(1)
  const viewport = page.locator('.usage-data-viewport')
  expect(await viewport.evaluate(el => el.scrollHeight - el.clientHeight)).toBeGreaterThan(1000)
  await viewport.evaluate(el => { el.scrollTop = el.scrollHeight })
  await expect(page.getByRole('tablist')).toBeInViewport()
  await expect(page.locator('.usage-results-footer')).toBeInViewport()
  await page.getByRole('button', {name: 'Свернуть', exact: true}).click()
  await expect.poll(() => surface.evaluate(el => el.viewBox.baseVal.height)).toBe(normal)
})

for (const [width, height] of [[1440, 800], [1280, 720]]) {
  test(`compact dashboard stays inside ${width}×${height}`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    await page.goto(`/admin/usage?report=summary&${dates}`)
    const workspace = page.locator('.usage-page')
    const viewport = page.locator('.usage-data-viewport')
    const table = page.getByRole('table')
    await expect(table).toBeVisible()
    await expect(workspace).toHaveCSS('overflow-y', 'hidden')
    expect(await workspace.evaluate(el => el.scrollHeight - el.clientHeight)).toBeLessThanOrEqual(1)
    await expect(page.getByText('Все записи показаны')).toBeInViewport()
    await expect(page.getByRole('button', {name: 'Таблица', exact: true})).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('#usage-breakdown-chart-content')).toHaveCount(0)
    await page.getByRole('button', {name: 'График', exact: true}).click()
    await expect(table).toHaveCount(0)
    await expect(page.locator('#usage-breakdown-chart-content')).toBeVisible()
    const frame = page.locator('.usage-breakdown-chart__frame')
    await expect(frame).toBeVisible()
    const normalHeight = await frame.evaluate(el => el.clientHeight)
    await page.getByRole('button', {name: 'Развернуть', exact: true}).click()
    await expect(workspace).toHaveClass(/usage-page--expanded/)
    await expect.poll(() => frame.evaluate(el => el.clientHeight)).toBeGreaterThan(normalHeight)
    await expect(page.getByText('100%', {exact: true})).toBeVisible()
    await expect(page.getByText('Все записи показаны')).toBeInViewport()
    await page.getByRole('button', {name: 'Свернуть', exact: true}).click()
    await page.getByRole('button', {name: 'Изменить фильтры'}).click()
    await expect(page.getByLabel('Начало периода')).toBeVisible()
    await page.getByRole('button', {name: 'Свернуть фильтры'}).click()
    await page.getByRole('button', {name: 'Таблица', exact: true}).click()
    await expect(table).toBeVisible()
    await expect(page.locator('#usage-breakdown-chart-content')).toHaveCount(0)
    await expect(page.getByRole('button', {name: 'Развернуть', exact: true})).toHaveCount(0)
    expect(await viewport.evaluate(el => el.clientHeight - el.querySelector('table').offsetHeight)).toBeLessThanOrEqual(2)
  })
}

test('wheel scrolls only the data rows while header and footer stay fixed', async ({ page }) => {
  await page.setViewportSize({width: 1440, height: 800})
  await page.route('**/api/admin/usage/users?**', route => route.fulfill({json: {
    content: Array.from({length: 50}, (_, i) => ({
      ...amounts, userId: `bbbbbbbb-bbbb-4bbb-8bbb-${String(i+1).padStart(12,'0')}`,
      userEmail: `user-${i+1}@example.test`,
    })), page: 0, size: 50, hasPrevious: false, hasNext: true,
  }}))
  await page.goto(`/admin/usage?report=users&${dates}`)
  const viewport = page.locator('.usage-data-viewport')
  const footer = page.locator('.usage-results-footer')
  await expect(page.getByRole('table').getByRole('row')).toHaveCount(51)
  const footerBefore = await footer.boundingBox()
  const box = await viewport.boundingBox()
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.wheel(0, 600)
  await expect.poll(() => viewport.evaluate(el => el.scrollTop)).toBeGreaterThan(0)
  expect(await page.locator('.usage-page').evaluate(el => el.scrollTop)).toBe(0)
  expect((await footer.boundingBox()).y).toBe(footerBefore.y)
  await expect(page.getByRole('tab', {name: 'По пользователям'})).toBeInViewport()
  await expect(page.getByRole('button', {name: 'Вперёд'})).toBeInViewport()
  await expect(page.getByRole('columnheader', {name: 'Пользователь'})).toBeInViewport()
  const headerBefore = await page.locator('.usage-page__upper').boundingBox()
  const handle = page.getByRole('separator', {name: 'Высота области отчёта'})
  await expect(handle).toBeVisible()
  const handleBox = await handle.boundingBox()
  expect(handleBox.y + handleBox.height).toBeLessThanOrEqual(box.y + 1)
  expect(handleBox.y + handleBox.height).toBeLessThanOrEqual((await page.locator('.usage-results-toolbar').boundingBox()).y + 1)
  await page.mouse.move(handleBox.x + handleBox.width / 2, handleBox.y + handleBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(handleBox.x + handleBox.width / 2, handleBox.y + handleBox.height / 2 - 60)
  await page.mouse.up()
  await expect.poll(() => viewport.evaluate(el => el.clientHeight)).toBeGreaterThan(box.height)
  expect((await page.locator('.usage-page__upper').boundingBox()).y).toBe(headerBefore.y)
  expect(Math.abs((await footer.boundingBox()).y - footerBefore.y)).toBeLessThanOrEqual(2)
  const moved = await handle.boundingBox()
  expect(moved.y).toBeLessThan(handleBox.y)
  const expandedHeight = await viewport.evaluate(el => el.clientHeight)
  await page.mouse.move(moved.x + moved.width / 2, moved.y + moved.height / 2)
  await page.mouse.down()
  await page.mouse.move(moved.x + moved.width / 2, moved.y + moved.height / 2 + 48)
  await page.mouse.up()
  await expect.poll(() => viewport.evaluate(el => el.clientHeight)).toBeLessThan(expandedHeight)
  expect(Math.abs((await footer.boundingBox()).y - footerBefore.y)).toBeLessThanOrEqual(2)
  await handle.focus()
  const beforeKeyboard = await viewport.evaluate(el => el.clientHeight)
  await handle.press('ArrowUp')
  await expect.poll(() => viewport.evaluate(el => el.clientHeight)).toBeGreaterThan(beforeKeyboard)
  await handle.dblclick()
  await expect.poll(() => viewport.evaluate((el, initialHeight) => Math.abs(el.clientHeight - initialHeight), box.height)).toBeLessThanOrEqual(2)
})

test('narrow viewport has a reachable page without vertical nested scroll', async ({page}) => {
  await page.setViewportSize({width: 760, height: 650})
  await page.goto(`/admin/usage?report=summary&${dates}`)
  await expect(page.locator('.usage-page')).toHaveCSS('overflow-y', 'auto')
  await expect(page.getByRole('table')).toBeVisible()
  await page.getByRole('button', {name: 'График', exact: true}).click()
  await expect(page.locator('#usage-breakdown-chart-content')).toBeVisible()
  await page.getByText('Все записи показаны').scrollIntoViewIfNeeded()
  await expect(page.getByText('Все записи показаны')).toBeInViewport()
})

test('usage tabs request different groupings and keep the chosen model and period', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(`/admin/usage?report=summary&${dates}&model=${model}`)
  const table = page.getByRole('table')
  await expect(table.getByRole('columnheader', { name: 'Пользователь' })).toBeVisible()
  await expect(table.getByRole('columnheader', { name: 'Модель' })).toBeVisible()
  for (const [name, endpoint, header, absent] of [
    ['По пользователям', 'users', 'Пользователь', 'Модель'],
    ['По моделям', 'models', 'Модель', 'Пользователь'],
    ['По дням', 'daily', 'Дата', 'Модель'],
    ['Детализация', 'summary', 'Пользователь', 'Дата'],
  ]) {
    await page.getByRole('tab', { name, exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`report=${endpoint}`))
    await expect(table.getByRole('columnheader', { name: header, exact: true })).toBeVisible()
    await expect(table.getByRole('columnheader', { name: absent, exact: true })).toHaveCount(0)
    await expect(table.getByRole('cell', { name: '25', exact: true })).toBeVisible()
    await expect(page).toHaveURL(/model=mock-safeai/)
    await expect(page).toHaveURL(/dateFrom=2026-11-01/)
  }
})

test('drilldown and independent chips work at mobile width', async ({ page }) => {
  await page.setViewportSize({width: 390, height: 844})
  await page.goto(`/admin/usage?report=users&${dates}`)
  await page.getByRole('button', {name: 'admin@example.test →'}).click()
  await expect(page).toHaveURL(/report=summary/)
  await expect(page).toHaveURL(new RegExp(`userId=${userId}`))
  await expect(page.getByRole('button', {name: 'admin@example.test →'})).toHaveCount(0)
  await page.getByRole('button', {name: 'mock-safeai →'}).click()
  await expect(page).toHaveURL(/model=mock-safeai/)
  await expect(page.getByRole('button', {name: 'mock-safeai →'})).toHaveCount(0)
  await page.getByRole('tab', {name: 'По дням'}).click()
  await page.getByRole('button', {name: '15.11.2026 →'}).click()
  await expect(page).toHaveURL(/dateFrom=2026-11-15/)
  await page.getByRole('button', {name: 'Сбросить фильтр по модели'}).click()
  await expect(page).not.toHaveURL(/model=/)
  await expect(page).toHaveURL(new RegExp(`userId=${userId}`))
  await page.getByRole('button', {name: 'Сбросить фильтр по пользователю'}).click()
  await expect(page).not.toHaveURL(/userId=/)
  expect(await page.locator('.usage-page').evaluate(el => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(1)
  const table = page.locator('.usage-data-viewport')
  expect(await table.evaluate(el => el.scrollWidth)).toBeGreaterThan(await table.evaluate(el => el.clientWidth))
})

for (const tab of ['summary', 'users', 'models', 'daily']) {
  test(`table fits its rows and chart expands without hiding controls on ${tab}`, async ({page}) => {
    await page.setViewportSize({width: 1600, height: 1000})
    await page.goto(`/admin/usage?report=${tab}&${dates}`)
    const data = page.locator('.usage-data-viewport')
    const table = page.getByRole('table')
    await expect(table).toBeVisible()
    expect(await data.evaluate(el => el.clientHeight - el.querySelector('table').offsetHeight)).toBeLessThanOrEqual(2)
    await expect(table.getByRole('cell').first()).toHaveCSS('font-size', '16px')
    await page.getByRole('button', {name: 'График', exact: true}).click()
    const plot = page.locator(tab === 'daily' ? '.usage-chart-viewport' : '.usage-breakdown-chart__frame')
    await expect(plot).toBeVisible()
    // Recharts uses the same SVG class for the chart and the 14px legend icons.
    // Select only the main accessible chart surface, never a legend symbol.
    const surface = plot.getByRole('application')
    await expect(surface).toHaveCount(1)
    await expect(surface).toBeVisible()
    const viewBoxHeight = () => surface.evaluate(el => el.viewBox.baseVal.height)
    const normal = await plot.evaluate(el => el.clientHeight)
    await expect.poll(viewBoxHeight).toBeGreaterThanOrEqual(180)
    await expect.poll(() => surface.evaluate(el => Math.abs(el.viewBox.baseVal.height - el.getBoundingClientRect().height))).toBeLessThanOrEqual(1)
    const normalSvg = await surface.evaluate(el => el.getBoundingClientRect().height)
    const normalViewBox = await viewBoxHeight()
    await page.getByRole('button', {name: 'Развернуть', exact: true}).click()
    await expect.poll(() => plot.evaluate(el => el.clientHeight)).toBeGreaterThan(normal + 20)
    await expect.poll(() => surface.evaluate(el => el.getBoundingClientRect().height)).toBeGreaterThan(normalSvg + 20)
    await expect.poll(viewBoxHeight).toBeGreaterThan(normalViewBox + 20)
    await expect(page.getByText('100%', {exact: true})).toBeInViewport()
    await expect(page.getByRole('tablist')).toBeInViewport()
    await expect(page.getByRole('button', {name: 'Изменить фильтры'})).toBeInViewport()
    await expect(page.locator('.usage-results-footer')).toBeInViewport()
    await page.getByRole('button', {name: 'Свернуть', exact: true}).click()
    await expect.poll(() => plot.evaluate(el => el.clientHeight)).toBe(normal)
    await expect.poll(() => surface.evaluate(el => el.getBoundingClientRect().height)).toBe(normalSvg)
    await expect.poll(viewBoxHeight).toBe(normalViewBox)
  })
}

for (const [width, height] of [[1920, 1080], [1280, 720]]) {
  test(`shared report grip expands all five tabs and both views at ${width}×${height}`, async ({page}) => {
    test.setTimeout(60_000)
    await page.setViewportSize({width, height})
    await page.route('**/api/auth/me', route => route.fulfill({json: {id: userId, organizationId: orgId,
      email: identity.userEmail, fullName: 'Super Admin', enabled: true, roles: ['SUPER_ADMIN']}}))
    await page.route('**/api/admin/usage/organizations?**', route => route.fulfill({json: {
      page: 0, size: 50, first: true, last: true, hasNext: false, hasPrevious: false, content: [{
        organizationId: orgId, organizationName: 'Demo Company', responses: {assistantMessages: 1},
        usage: {confirmedInputTokens: 9, confirmedOutputTokens: 16, availableUsageMessages: 1,
          partialUsageMessages: 0, missingUsageMessages: 0, usageNotApplicableMessages: 0},
        cost: {knownCostUsd: '0', currency: 'USD', pricedMessages: 1, freeMessages: 0,
          unpricedMessages: 0, pricingFailedMessages: 0, pricingNotApplicableMessages: 0},
      }],
    }}))
    await page.goto(`/admin/usage?report=summary&${dates}`)
    const grip = page.getByRole('separator', {name: 'Высота области отчёта'})
    const tabs = page.getByRole('tablist')
    const panel = page.locator('.usage-results-panel')
    const summary = page.locator('.usage-summary-viewport')
    const viewport = page.locator('.usage-data-viewport')
    const footer = page.locator('.usage-results-footer')
    const foldToFooter = async () => {
      const handle = await grip.boundingBox()
      const bottom = await page.locator('.usage-page').boundingBox()
      const x = handle.x + handle.width / 2
      await page.mouse.move(x, handle.y + handle.height / 2)
      await page.mouse.down()
      await page.mouse.move(x, bottom.y + bottom.height - 3, {steps: 10})
      await page.mouse.up()
      await expect(grip).toHaveAttribute('aria-valuenow', '-100')
      await expect(viewport).toBeHidden()
      await expect(footer).toBeInViewport({ratio: 1})
      const foldedGrip = await grip.boundingBox()
      const foldedFooter = await footer.boundingBox()
      expect(Math.abs(foldedFooter.y - foldedGrip.y - foldedGrip.height)).toBeLessThanOrEqual(2)
      await expect(summary).toBeVisible()
      // Restore by dragging the same grip back up, without touching the footer.
      await page.mouse.move(x, foldedGrip.y + foldedGrip.height / 2)
      await page.mouse.down()
      await page.mouse.move(x, (await tabs.boundingBox()).y - 5, {steps: 10})
      await page.mouse.up()
      await expect(grip).toHaveAttribute('aria-valuenow', '100')
      await expect(viewport).toBeInViewport()
      await grip.press('Home')
    }
    for (const name of ['Детализация', 'По пользователям', 'По моделям', 'По дням', 'По организациям']) {
      await page.getByRole('tab', {name, exact: true}).click()
      await page.getByRole('button', {name: 'Таблица', exact: true}).click()
      await expect(page.getByRole('table')).toBeVisible()
      await expect(grip).toBeVisible()
      await foldToFooter()
      await expect(page.getByRole('table')).toBeVisible()
      const before = await panel.boundingBox()
      const tabsBefore = await tabs.boundingBox()
      const handle = await grip.boundingBox()
      await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2)
      await page.mouse.down()
      await page.mouse.move(handle.x + handle.width / 2, tabsBefore.y + tabsBefore.height - 40, {steps: 6})
      await page.mouse.up()
      await expect(grip).toHaveAttribute('aria-valuenow', '100')
      await expect(summary).toBeHidden()
      expect((await panel.boundingBox()).y).toBeLessThan(before.y)
      expect((await panel.boundingBox()).y - tabsBefore.y - tabsBefore.height).toBeLessThanOrEqual(14)
      expect(await tabs.boundingBox()).toEqual(tabsBefore)
      await expect(page.locator('.usage-results-footer')).toBeInViewport()
      await expect(viewport).toBeInViewport()
      await page.getByRole('button', {name: 'График', exact: true}).click()
      const plot = page.locator(name === 'По дням' ? '.usage-chart-viewport' : '.usage-breakdown-chart__frame')
      await expect(plot).toBeVisible()
      await expect(grip).toHaveAttribute('aria-valuenow', '100')
      const enlarged = await plot.evaluate(el => el.clientHeight)
      await grip.press('Home')
      await expect(grip).toHaveAttribute('aria-valuenow', '0')
      await expect(summary).toBeVisible()
      await expect.poll(() => plot.evaluate(el => el.clientHeight)).toBeLessThan(enlarged)
      const chartHandle = await grip.boundingBox()
      await page.mouse.move(chartHandle.x + chartHandle.width / 2, chartHandle.y + chartHandle.height / 2)
      await page.mouse.down()
      await page.mouse.move(chartHandle.x + chartHandle.width / 2, tabsBefore.y + tabsBefore.height - 40, {steps: 6})
      await page.mouse.up()
      await expect(grip).toHaveAttribute('aria-valuenow', '100')
      await expect.poll(() => plot.evaluate(el => el.clientHeight)).toBe(enlarged)
      await grip.dblclick()
      await expect(grip).toHaveAttribute('aria-valuenow', '0')
      await foldToFooter()
      await expect(plot).toBeVisible()
    }
    await grip.press('PageDown')
    await expect(grip).toHaveAttribute('aria-valuenow', '-100')
    await expect(viewport).toBeHidden()
    await page.setViewportSize({width: 390, height: 844})
    await expect(grip).toBeHidden()
    await expect(summary).toBeVisible()
    await expect(viewport).toBeVisible()
  })
}
