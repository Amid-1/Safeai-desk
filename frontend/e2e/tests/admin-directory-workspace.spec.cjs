// Real browser regression for the administrative directory; mocked API only.
const { test, expect } = require('@playwright/test')
const { URL: NodeURL } = require('node:url')

const platformId = '00000000-0000-0000-0000-000000000001'
const date = '2026-11-18T10:00:00Z'
const tenant = n => ({ id: `aaaaaaaa-aaaa-4aaa-8aaa-${String(n).padStart(12, '0')}`,
  name: `Компания ${n}`, enabled: true, type: 'TENANT', protected: false,
  version: 0, createdAt: date, updatedAt: date })
const platform = { ...tenant(0), id: platformId, name: 'SafeAI Platform', type: 'PLATFORM', protected: true }
const user = n => ({ id: `bbbbbbbb-bbbb-4bbb-8bbb-${String(n).padStart(12, '0')}`,
  email: `user${n}@example.test`, fullName: `Пользователь ${n}`, roles: ['USER'], enabled: true,
  organizationId: tenant(1).id, createdAt: date, updatedAt: date, version: 0, lastLoginAt: null })
const users = Array.from({ length: 21 }, (_, n) => user(n + 1))

test.beforeEach(async ({ page }) => {
  const organizations = [...Array.from({ length: 16 }, (_, n) => tenant(n + 1)), platform]
  await page.route('**/api/**', async route => {
    const { pathname, searchParams } = new NodeURL(route.request().url())
    const method = route.request().method()
    let json
    switch (pathname) {
      case '/api/auth/me': json = { ...user(0), organizationId: platformId, roles: ['SUPER_ADMIN'] }; break
      case '/api/auth/csrf': return route.fulfill({ status: 204, headers: { 'set-cookie': 'XSRF-TOKEN=test; Path=/; SameSite=Lax' } })
      case '/api/organizations':
        if (method === 'POST') {
          const body = route.request().postDataJSON()
          expect(Object.keys(body)).toEqual(['name'])
          const created = { ...tenant(17), name: body.name }
          organizations.unshift(created)
          return route.fulfill({ status: 201, json: created })
        }
        json = { content: organizations, page: 0, size: 50, totalPages: 1, totalElements: organizations.length }; break
      case '/api/organizations/directory': json = organizations; break
      case '/api/organizations/search': {
        const matches = organizations.filter(org =>
          (!searchParams.get('query') || org.name.toLowerCase().includes(searchParams.get('query').toLowerCase())) &&
          (!searchParams.get('type') || org.type === searchParams.get('type')) &&
          (!searchParams.has('enabled') || String(org.enabled) === searchParams.get('enabled')))
        json = { content: matches, page: 0, size: 50, totalPages: matches.length ? 1 : 0, totalElements: matches.length }; break
      }
      case '/api/users/search': {
        const matches = users.filter(row => (!searchParams.get('organizationId') || row.organizationId === searchParams.get('organizationId')) &&
          (!searchParams.get('role') || row.roles.includes(searchParams.get('role'))))
        json = { content: matches, page: 0, size: 50, totalPages: matches.length ? 1 : 0, totalElements: matches.length }; break
      }
      case '/api/users/statistics': json = { total: 21, administrators: 0, users: 21, enabled: 21, disabled: 0 }; break
      case '/api/users/search/statistics': {
        const count = users.filter(row => !searchParams.get('organizationId') || row.organizationId === searchParams.get('organizationId')).length
        json = { total: count, administrators: 0, superAdministrators: 0, users: count, enabled: count, disabled: 0 }; break
      }
      default:
        if (method === 'GET' && pathname.startsWith('/api/users/')) {
          const match = users.find(row => row.id === pathname.split('/').at(-1))
          if (match) { json = {...match, organizationName: 'Компания 1'}; break }
        }
        if (method === 'GET' && pathname.startsWith('/api/organizations/')) {
          json = organizations.find(org => org.id === pathname.split('/').at(-1))
          if (json) break
        }
        return route.fulfill({ status: 404, json: { message: 'Not found' } })
    }
    return route.fulfill({ json })
  })
})

test('organization card opens a scoped users directory', async ({page}) => {
  await page.goto('/admin/organizations')
  const row = page.getByRole('row').filter({hasText: 'Компания 1'}).first()
  await row.getByRole('button', {name: 'Подробнее'}).click()
  const card = page.getByRole('dialog', {name: 'Карточка организации'})
  await expect(card.getByRole('link', {name: /Правила моделей/})).toHaveAttribute('href', /section=access&organizationId=/)
  await expect(card.getByRole('link', {name: /Журнал событий/})).toHaveAttribute('href', /targetOrganizationId=/)
  await card.getByRole('link', {name: /Пользователи организации/}).click()
  await expect(page).toHaveURL(new RegExp('organizationId=' + tenant(1).id))
  await expect(page.getByRole('button', {name: /Суперадминистраторы/})).toBeVisible()
  await expect(page.getByRole('row')).toHaveCount(22)
})

test('direct user link opens details and links to the organization', async ({page}) => {
  await page.goto('/admin/users?userId=' + user(1).id)
  const card = page.getByRole('dialog', {name: 'Карточка пользователя'})
  await expect(card).toBeVisible()
  await expect(card.getByRole('link', {name: 'Журнал событий'})).toHaveAttribute('href', /actorUserId=/)
  await expect(card.getByRole('link', {name: 'Использование и расходы'})).toHaveAttribute('href', /userId=/)
  await page.keyboard.press('Escape')
  await expect(card).toHaveCount(0)
  await expect(page).not.toHaveURL(/userId=/)
})

for (const [width, height] of [[1440, 900], [390, 844]]) {
  test(`profile window controls match the light modal shell at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    for (const [url, name] of [
      ['/admin/organizations?organizationId=' + tenant(1).id, 'Карточка организации'],
      ['/admin/users?userId=' + user(1).id, 'Карточка пользователя'],
    ]) {
      await page.goto(url)
      await page.mouse.move(0, 0)
      const dialog = page.getByRole('dialog', { name })
      const maximize = dialog.getByRole('button', { name: 'Развернуть окно на весь экран' })
      const close = dialog.getByRole('button', { name: 'Закрыть окно' })
      await expect(maximize).toBeVisible()
      await expect(close).toBeVisible()
      for (const button of [maximize, close]) {
        await expect(button).toHaveCSS('background-color', 'rgb(255, 255, 255)')
        await expect(button).toHaveCSS('border-top-style', 'solid')
        await expect(button).toHaveCSS('color', 'rgb(31, 42, 59)')
      }
      const first = await maximize.boundingBox()
      const second = await close.boundingBox()
      expect(first.width).toBeGreaterThanOrEqual(29)
      expect(Math.abs(first.width - second.width)).toBeLessThan(1)
      expect(Math.abs(first.height - second.height)).toBeLessThan(1)
      expect(Math.abs(first.y - second.y)).toBeLessThan(1)
      expect(second.x - first.x - first.width).toBeGreaterThanOrEqual(4)
      await expect(maximize.locator('span')).toHaveCSS('border-top-style', 'solid')
      expect((await maximize.locator('span').boundingBox()).width).toBeGreaterThan(8)
      await maximize.click()
      const restore = dialog.getByRole('button', { name: 'Восстановить размер окна' })
      await expect(restore).toHaveAttribute('aria-pressed', 'true')
      await expect(dialog).toHaveClass(/modal-card--maximized/)
      await restore.click()
      await expect(maximize).toHaveAttribute('aria-pressed', 'false')
      await page.keyboard.press('Tab')
      await close.focus()
      await expect(close).toHaveCSS('outline-style', 'solid')
      await close.click()
      await expect(dialog).toHaveCount(0)
    }
  })
}

for (const [width, height] of [[1920, 1080], [1440, 900], [1280, 720]]) {
  test(`directory fills the workspace at ${width}×${height}`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    await page.goto('/admin/users')
    await expect(page.getByRole('row')).toHaveCount(22)
    await expect(page.locator('.admin-page-header')).toHaveCount(1)
    await expect(page.locator('.users-page')).not.toHaveCSS('background-color', 'rgb(255, 241, 242)')
    await expect(page.locator('.users-page')).not.toHaveCSS('outline-width', '3px')
    await expect(page.locator('.current-user')).toContainText('Суперадминистратор')
    await expect.poll(() => page.evaluate(() => globalThis.document.documentElement.scrollWidth <= globalThis.innerWidth)).toBe(true)
    const table = page.locator('.users-table-card')
    const upper = page.locator('.users-page__upper')
    await expect.poll(async () => {
      const bounds = await table.boundingBox()
      const filters = await upper.boundingBox()
      return bounds.y - (filters.y + filters.height)
    }).toBeLessThan(45)
    const separator = page.getByRole('separator', { name: /список пользователей/ })
    const original = Number(await separator.getAttribute('aria-valuenow'))
    await separator.focus()
    await expect(separator).toHaveCSS('outline-style', 'solid')
    await page.keyboard.press('ArrowDown')
    await expect.poll(async () => Number(await separator.getAttribute('aria-valuenow'))).toBeLessThan(original)
    await separator.dblclick()
    await expect.poll(async () => Number(await separator.getAttribute('aria-valuenow'))).toBe(original)
    await page.goto('/admin/organizations')
    await expect(page.locator('.organizations-page')).not.toHaveCSS('background-color', 'rgb(255, 241, 242)')
    await expect(page.locator('.organizations-page')).not.toHaveCSS('outline-width', '3px')
    await expect(page.getByRole('row')).toHaveCount(18)
    await expect(page.getByRole('row').filter({ hasText: 'Компания 1' }).first()).toBeInViewport()
    await expect(page.getByRole('columnheader', { name: /ID|Версия/ })).toHaveCount(0)
    await expect(page.getByText('Показано 17 из 17')).toBeVisible()
    await page.getByRole('button', { name: '＋ Создать организацию' }).click()
    const dialog = page.getByRole('dialog', { name: 'Создать организацию' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'Создать организацию' })).toBeDisabled()
    await dialog.getByLabel('Название организации').fill('Новый клиент')
    await dialog.getByRole('button', { name: 'Создать организацию' }).click()
    await expect(dialog).toHaveCount(0)
    await expect(page.getByText('Показано 18 из 18')).toBeVisible()
    const protectedRow = page.getByRole('row').filter({ hasText: 'SafeAI Platform' })
    await expect(protectedRow.getByRole('button', { name: /Отключить|Переименовать/ })).toHaveCount(0)
    await protectedRow.getByRole('button', { name: 'Подробнее' }).click()
    const details = page.getByRole('dialog', { name: 'Карточка организации' })
    await expect(details.getByText('Технические сведения и полный ID')).toBeVisible()
    await expect(details.getByRole('button', { name: /Отключить|Переименовать/ })).toHaveCount(0)
    await details.getByText('Технические сведения и полный ID').click()
    await expect(details.getByText(platformId, { exact: true })).toBeVisible()
  })
}

test('mobile directory retains filters, accessible card and contained scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/admin/users')
  await expect(page.getByRole('heading', { name: 'Пользователи' })).toBeVisible()
  await expect(page.getByRole('button', { name: /Суперадминистраторы/ })).toBeVisible()
  await expect(page.getByLabel('Организация', { exact: true })).toBeVisible()
  await page.goto('/admin/organizations')
  await expect(page.getByRole('heading', { name: 'Организации' })).toBeVisible()
  await page.getByRole('row').filter({ hasText: 'Компания 1' }).first().getByRole('button', { name: 'Подробнее' }).click()
  const card = page.getByRole('dialog', { name: 'Карточка организации' })
  await expect(card.getByRole('link', { name: /Пользователи организации/ })).toBeVisible()
  await expect(card.getByRole('link', { name: /Журнал событий/ })).toBeVisible()
  await expect.poll(() => page.evaluate(() => globalThis.document.documentElement.scrollWidth <= globalThis.innerWidth)).toBe(true)
  await page.keyboard.press('Escape')
  await expect(card).toHaveCount(0)
})
