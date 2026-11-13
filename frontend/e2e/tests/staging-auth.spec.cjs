const { test, expect } = require('@playwright/test')
const { URL: NodeURL } = require('node:url')

// This run uses test credentials and real session cookies.
test.use({ trace: 'off', screenshot: 'off', video: 'off' })

test('@staging exercises the HTTPS browser session lifecycle', async ({ page, context, baseURL }) => {
  test.skip(
    process.env.SAFEAI_E2E_APPROVED !== 'true',
    'Staging E2E requires explicit environment approval',
  )

  if (!baseURL || NodeURL.parse(baseURL)?.protocol !== 'https:') {
    throw new Error('Staging authentication requires an HTTPS base URL')
  }

  const email = process.env.SAFEAI_E2E_USER_EMAIL
  const password = process.env.SAFEAI_E2E_USER_PASSWORD
  if (!email || !password) {
    throw new Error('Staging credentials are required; refusing to run an anonymous staging test')
  }

  const loginPage = await page.goto('/login')
  if (!loginPage) {
    throw new Error('Staging login page did not return an HTTP response')
  }
  expect(await loginPage.headerValue('strict-transport-security') ?? '')
    .toMatch(/(?:^|;)\s*max-age\s*=\s*[1-9]\d*\s*(?:;|$)/i)
  for (const endpoint of ['/actuator', '/actuator/health', '/actuator/prometheus']) {
    expect((await context.request.get(endpoint, { maxRedirects: 0 })).status()).toBe(404)
  }
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Пароль').fill(password)
  const login = page.waitForResponse(response =>
    response.url().endsWith('/api/auth/login') && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Войти' }).click()
  expect((await login).status()).toBe(200)

  await expect(page).toHaveURL(/\/(chat|admin\/users)$/)
  await expect(page.getByRole('button', { name: 'Выйти' })).toBeVisible()
  const cookieNames = {
    access: '__Host-safeai-access',
    refresh: '__Secure-safeai-refresh',
    csrf: '__Host-XSRF-TOKEN',
  }
  const cookies = await context.cookies()
  for (const [name, path, httpOnly] of [
    [cookieNames.access, '/', true],
    [cookieNames.refresh, '/api/auth', true],
    [cookieNames.csrf, '/', false],
  ]) {
    const cookie = cookies.find(item => item.name === name)
    expect(cookie && { path: cookie.path, secure: cookie.secure, httpOnly: cookie.httpOnly })
      .toEqual({ path, secure: true, httpOnly })
  }
  expect((await context.request.get('/api/auth/me')).status()).toBe(200)

  const authenticatedUrl = page.url()
  const secondTab = await context.newPage()
  await secondTab.goto(authenticatedUrl)
  await expect(secondTab.getByRole('button', { name: 'Выйти' })).toBeVisible()

  // Remove only the shared access cookie to reproduce two tabs receiving 401.
  await context.clearCookies({ name: cookieNames.access })
  const refreshStatuses = []
  context.on('response', response => {
    if (response.url().endsWith('/api/auth/refresh') && response.request().method() === 'POST') {
      refreshStatuses.push(response.status())
    }
  })
  await Promise.all([page.reload(), secondTab.reload()])
  await expect(page.getByRole('button', { name: 'Выйти' })).toBeVisible()
  await expect(secondTab.getByRole('button', { name: 'Выйти' })).toBeVisible()
  expect(refreshStatuses).toEqual([204])

  const beforeLogout = await context.cookies()
  const currentRefresh = beforeLogout.find(cookie => cookie.name === cookieNames.refresh)
  const originalRefresh = cookies.find(cookie => cookie.name === cookieNames.refresh)
  if (!currentRefresh?.value || !originalRefresh?.value) {
    throw new Error('Staging browser did not receive both refresh cookies')
  }
  expect(currentRefresh.value !== originalRefresh.value, 'Refresh cookie must rotate').toBe(true)

  const logout = page.waitForResponse(response =>
    response.url().endsWith('/api/auth/logout') && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Выйти' }).click()
  expect((await logout).status()).toBe(204)
  await expect(page).toHaveURL(/\/login$/)
  expect((await context.request.get('/api/auth/me')).status()).toBe(401)
  const remainingCookies = await context.cookies()
  expect(remainingCookies.some(cookie => [cookieNames.access, cookieNames.refresh].includes(cookie.name)))
    .toBe(false)

  // Restore the pre-logout refresh cookie in the test browser to verify server-side revocation.
  expect((await context.request.get('/api/auth/csrf')).status()).toBe(200)
  const csrf = (await context.cookies()).find(cookie => cookie.name === cookieNames.csrf)
  if (!csrf?.value) {
    throw new Error('Staging browser did not receive a CSRF cookie after logout')
  }
  await context.addCookies([currentRefresh])
  const revoked = await context.request.post('/api/auth/refresh', {
    headers: { 'X-XSRF-TOKEN': csrf.value },
  })
  expect(revoked.status()).toBe(401)
})
