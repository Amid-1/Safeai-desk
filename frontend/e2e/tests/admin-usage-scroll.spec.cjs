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
  const handle = page.getByRole('separator', {name: 'Высота таблицы'})
  await expect(handle).toBeVisible()
  const handleBox = await handle.boundingBox()
  await page.mouse.move(handleBox.x + handleBox.width / 2, handleBox.y + handleBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(handleBox.x + handleBox.width / 2, handleBox.y + handleBox.height / 2 - 60)
  await page.mouse.up()
  await expect.poll(() => viewport.evaluate(el => el.clientHeight)).toBeLessThan(box.height)
  expect((await page.locator('.usage-page__upper').boundingBox()).y).toBe(headerBefore.y)
  await handle.dblclick()
  await expect.poll(() => viewport.evaluate(el => el.clientHeight)).toBeGreaterThanOrEqual(box.height - 1)
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
    const surface = plot.locator('svg.recharts-surface').first()
    await expect(surface).toBeVisible()
    const normal = await plot.evaluate(el => el.clientHeight)
    const normalSvg = await surface.evaluate(el => el.getBoundingClientRect().height)
    await page.getByRole('button', {name: 'Развернуть', exact: true}).click()
    await expect.poll(() => plot.evaluate(el => el.clientHeight)).toBeGreaterThan(normal + 20)
    await expect.poll(() => surface.evaluate(el => el.getBoundingClientRect().height)).toBeGreaterThan(normalSvg + 20)
    await expect(page.getByText('100%', {exact: true})).toBeInViewport()
    await expect(page.getByRole('tablist')).toBeInViewport()
    await expect(page.getByRole('button', {name: 'Изменить фильтры'})).toBeInViewport()
    await expect(page.locator('.usage-results-footer')).toBeInViewport()
    await page.getByRole('button', {name: 'Свернуть', exact: true}).click()
    await expect.poll(() => plot.evaluate(el => el.clientHeight)).toBe(normal)
    await expect.poll(() => surface.evaluate(el => el.getBoundingClientRect().height)).toBe(normalSvg)
  })
}
