// Browser regression: catalog editor layout; deterministic API, no real mutations.
const { test, expect } = require('@playwright/test')
const { URL: NodeURL } = require('node:url')

const orgId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const userId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const runtime = { provider: 'mock', model: 'mock-safeai', enabled: true, routingMode: 'SINGLE_PROVIDER_STATIC',
  maxInputTokens: 64000, maxOutputTokens: 2048, toolsSupported: false, visionSupported: false,
  structuredOutputSupported: false, dataRetentionStatus: 'NOT_DECLARED', healthStatus: 'NOT_PROBED',
  pricingStatus: 'FREE', inputUsdPer1mTokens: '0.000000000000', outputUsdPer1mTokens: '0', pricingVersion: 'mock-2026-01' }
const policy = { configured: false, id: null, organizationId: orgId, version: 0, enabled: false,
  allowModelKeys: [], denyModelKeys: [], defaultModelKey: null, maxInputTokens: null, maxOutputTokens: null,
  maxRequestCostUsd: null, monthlyBudgetUsd: null, budgetEnforcement: 'SOFT', requireCompletePricing: false,
  requireNoTraining: false, requireZeroDataRetention: false, createdByUserId: null, createdAt: null }

test.beforeEach(async ({ page }) => {
  await page.route('**/api/**', async route => {
    const pathname = new NodeURL(route.request().url()).pathname
    let json
    switch (pathname) {
      case '/api/auth/me': json = { id: userId, organizationId: orgId, email: 'admin@example.test',
        fullName: 'Test Admin', enabled: true, roles: ['SUPER_ADMIN'] }; break
      case '/api/auth/csrf': return route.fulfill({ status: 204, headers: { 'set-cookie': 'XSRF-TOKEN=test; Path=/; SameSite=Lax' } })
      case '/api/admin/models/runtime': json = runtime; break
      case '/api/admin/models/catalog':
      case '/api/admin/models/catalog/effective': json = []; break
      case `/api/admin/models/policies/${orgId}`: json = policy; break
      case '/api/admin/models/policies/rub-quote': {
        const fetchedAt = new Date().toISOString()
        const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Moscow', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(fetchedAt))
        const date = ['year', 'month', 'day'].map(key => parts.find(part => part.type === key).value).join('-')
        json = { rubPerUsd: '82.5143', source: 'CBR', effectiveDate: date, quoteDate: date, fetchedAt }; break
      }
      default: return route.fulfill({ status: 404, json: { message: 'Not found' } })
    }
    return route.fulfill({ json })
  })
})

for (const width of [1440, 390]) {
  test(`catalog tabs remain usable at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/admin/models?section=catalog')
    await page.getByRole('button', { name: 'Создать модель' }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByRole('tablist')).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'Сохранить версию' })).toBeInViewport()
    await dialog.getByLabel('Название', { exact: true }).fill('Test model')
    await dialog.getByRole('tab', { name: 'Возможности модели' }).click()
    const checkbox = dialog.getByRole('checkbox', { name: 'Инструменты' })
    await expect(checkbox).toBeVisible()
    expect(await checkbox.evaluate(input => {
      const label = input.closest('label')
      const range = input.ownerDocument.createRange()
      range.selectNodeContents(label)
      const text = [...range.getClientRects()].at(-1)
      const box = input.getBoundingClientRect()
      return input.ownerDocument.defaultView.getComputedStyle(label).display === 'flex' && text.left >= box.right
        && text.top < box.bottom && text.bottom > box.top
    })).toBe(true)
    await dialog.getByRole('tab', { name: 'Тарификация' }).click()
    await expect(dialog.getByText('1 USD = 82,5143 RUB')).toBeVisible()
    await expect(dialog.getByLabel('Входные токены, USD / 1 млн')).toHaveValue('0')
    await expect(dialog.locator('details.models-form__advanced')).not.toHaveAttribute('open', '')
    await expect(dialog.getByRole('button', { name: 'Сохранить версию' })).toBeInViewport()
    expect(await dialog.locator('.modal-body').evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
    await dialog.getByRole('tab', { name: 'Основные данные' }).click()
    await expect(dialog.getByLabel('Название', { exact: true })).toHaveValue('Test model')
  })
}
