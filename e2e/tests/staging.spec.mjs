import { expect, test } from '@playwright/test'

// Real staging: actual browser -> deployed Nginx -> backend -> PostgreSQL/Redis.
// Requires dedicated disposable USER identity and approved staging environment.
test('real user login, authorization and knowledge UI @staging', async ({ page }) => {
  const email = process.env.SAFEAI_E2E_USER_EMAIL
  const password = process.env.SAFEAI_E2E_USER_PASSWORD
  const base = process.env.SAFEAI_E2E_BASE_URL
  if (!base?.startsWith('https://') || !email || !password) {
    throw new Error('Requires HTTPS staging URL and dedicated USER credentials; no skipped green tests')
  }
  await page.goto('/login')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Пароль').fill(password)
  await page.getByRole('button', { name: 'Войти' }).click()
  await expect(page).toHaveURL(/\/chat(?:\?.*)?$/, { timeout: 30000 })

  await page.goto('/knowledge')
  await expect(page).toHaveURL(/\/knowledge(?:\?.*)?$/)
  await expect(page.getByRole('heading', { name: 'Вход в SafeAI Desk' })).toHaveCount(0)

  // Test that USER cannot see administration, regardless of frontend navigation.
  await page.goto('/admin/users')
  await expect(page.getByText('Недостаточно прав')).toBeVisible()
})
