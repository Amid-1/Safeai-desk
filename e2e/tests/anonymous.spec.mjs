import { expect, test } from '@playwright/test'

// Browser tests: real React/Vite bundle + deterministic API stub.
// NOT an end-to-end verification of the Java backend.
async function unavailableSession(page) {
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/auth/me')) {
      return route.fulfill({ status: 401, contentType: 'application/json', body: '{"error":"UNAUTHORIZED"}' })
    }
    return route.fulfill({ status: 401, contentType: 'application/json', body: '{"error":"UNAUTHORIZED"}' })
  })
}

test('unauthenticated login form is accessible in a real browser', async ({ page }) => {
  await unavailableSession(page)
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'Вход в SafeAI Desk' })).toBeVisible()
  await expect(page.getByLabel('Email')).toBeVisible()
  await expect(page.getByLabel('Пароль')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Войти' })).toBeVisible()
})

test('anonymous users cannot view Knowledge or administrator UI', async ({ page }) => {
  await unavailableSession(page)
  for (const path of ['/knowledge', '/admin/users', '/admin/audit', '/chat']) {
    await page.goto(path)
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByRole('heading', { name: 'Вход в SafeAI Desk' })).toBeVisible()
  }
})
