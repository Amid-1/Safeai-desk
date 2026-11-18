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
    const { pathname } = new NodeURL(route.request().url())
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
      case '/api/users/search': json = { content: users, page: 0, size: 50, totalPages: 1, totalElements: users.length }; break
      case '/api/users/statistics': json = { total: 21, administrators: 0, users: 21, enabled: 21, disabled: 0 }; break
      default:
        if (method === 'GET' && pathname.startsWith('/api/organizations/')) {
          json = organizations.find(org => org.id === pathname.split('/').at(-1))
          if (json) break
        }
        return route.fulfill({ status: 404, json: { message: 'Not found' } })
    }
    return route.fulfill({ json })
  })
})

for (const [width, height] of [[1440, 900], [1280, 720]]) {
  test(`directory fills the workspace at ${width}×${height}`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    await page.goto('/admin/users')
    await expect(page.getByRole('row')).toHaveCount(22)
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
    await page.keyboard.press('ArrowDown')
    await expect.poll(async () => Number(await separator.getAttribute('aria-valuenow'))).toBeLessThan(original)
    await separator.dblclick()
    await expect.poll(async () => Number(await separator.getAttribute('aria-valuenow'))).toBe(original)
    await page.goto('/admin/organizations')
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
    const details = page.getByRole('dialog', { name: 'Сведения об организации' })
    await expect(details.getByText('Технические сведения и полный ID')).toBeVisible()
    await expect(details.getByRole('button', { name: /Отключить|Переименовать/ })).toHaveCount(0)
    await details.getByText('Технические сведения и полный ID').click()
    await expect(details.getByText(platformId, { exact: true })).toBeVisible()
  })
}
