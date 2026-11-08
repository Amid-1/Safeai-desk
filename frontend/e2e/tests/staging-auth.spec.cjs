const { test, expect } = require('@playwright/test')

test('@staging authenticates a provisioned test user against the approved backend', async ({ page }) => {
  test.skip(
    process.env.SAFEAI_E2E_APPROVED !== 'true',
    'Staging E2E requires explicit environment approval',
  )

  const email = process.env.SAFEAI_E2E_USER_EMAIL
  const password = process.env.SAFEAI_E2E_USER_PASSWORD
  if (!email || !password) {
    throw new Error('Staging credentials are required; refusing to run an anonymous staging test')
  }

  await page.goto('/login')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Пароль').fill(password)
  await page.getByRole('button', { name: 'Войти' }).click()

  await expect(page).toHaveURL(/\/(chat|admin\/users)$/)
  await expect(page.getByRole('button', { name: 'Выйти' })).toBeVisible()
})
