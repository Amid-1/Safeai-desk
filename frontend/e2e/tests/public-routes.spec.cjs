// Browser UI tests use a complete, deterministic anonymous-session contract.
// Real backend, CSRF and tenant isolation are verified separately on staging.
const { test, expect } = require('@playwright/test')

const CSRF_TOKEN = 'safeai-e2e-anonymous-csrf'

function rejectedSession(status = 401, code = 'UNAUTHORIZED') {
  return {
    status,
    contentType: 'application/json',
    body: JSON.stringify({
      status,
      error: code,
      message: status === 401 ? 'Authentication required' : 'Service unavailable',
    }),
  }
}

test.beforeEach(async ({ page }) => {
  // /me returns 401 for an anonymous visitor, including the refresh probe.
  await page.route('**/api/auth/me', async (route) => {
    if (route.request().method() !== 'GET') {
      return route.fulfill(rejectedSession(405, 'METHOD_NOT_ALLOWED'))
    }
    return route.fulfill(rejectedSession())
  })

  // A real CSRF bootstrap sets an XSRF-TOKEN cookie. The test must do the
  // same rather than bypassing the application's CSRF protection.
  await page.route('**/api/auth/csrf', async (route) => {
    if (route.request().method() !== 'GET') {
      return route.fulfill(rejectedSession(405, 'METHOD_NOT_ALLOWED'))
    }
    return route.fulfill({
      status: 204,
      headers: {
        'set-cookie': `XSRF-TOKEN=${CSRF_TOKEN}; Path=/; SameSite=Lax`,
      },
    })
  })

  // The refresh endpoint rejects an anonymous session. A missing/incorrect
  // CSRF header gets 403, so the UI test cannot accidentally bypass CSRF.
  await page.route('**/api/auth/refresh', async (route) => {
    if (route.request().method() !== 'POST') {
      return route.fulfill(rejectedSession(405, 'METHOD_NOT_ALLOWED'))
    }
    if (route.request().headers()['x-xsrf-token'] !== CSRF_TOKEN) {
      return route.fulfill(rejectedSession(403, 'CSRF_TOKEN_INVALID'))
    }
    return route.fulfill(rejectedSession())
  })
})

test('public login route renders the accessible sign-in form after rejected refresh', async ({ page }) => {
  const refreshRequest = page.waitForRequest(
    (request) => request.url().endsWith('/api/auth/refresh') && request.method() === 'POST',
  )
  await page.goto('/login')

  await expect(page.getByRole('heading', { name: 'Вход в SafeAI Desk' })).toBeVisible()
  await expect(page.getByLabel('Email')).toBeVisible()
  await expect(page.getByLabel('Пароль')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Войти' })).toBeVisible()

  const request = await refreshRequest
  expect(request.headers()['x-xsrf-token']).toBe(CSRF_TOKEN)
})

for (const path of ['/chat', '/knowledge', '/admin/users']) {
  test(`anonymous visitor cannot open protected route ${path}`, async ({ page }) => {
    await page.goto(path)

    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByRole('heading', { name: 'Вход в SafeAI Desk' })).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Основная навигация' })).toHaveCount(0)
  })
}

test('auth API outage fails closed, without treating a server error as logout', async ({ page }) => {
  await page.unroute('**/api/auth/me')
  await page.route('**/api/auth/me', (route) =>
    route.fulfill(rejectedSession(503, 'SERVICE_UNAVAILABLE')),
  )

  await page.goto('/chat')

  await expect(page.getByRole('heading', { name: 'Сервис временно недоступен' })).toBeVisible()
  await expect(page).toHaveURL(/\/chat$/)
  await expect(page.getByRole('navigation', { name: 'Основная навигация' })).toHaveCount(0)
})

test('CSRF bootstrap outage fails closed without revealing protected content', async ({ page }) => {
  await page.unroute('**/api/auth/csrf')
  await page.route('**/api/auth/csrf', (route) =>
    route.fulfill(rejectedSession(503, 'SERVICE_UNAVAILABLE')),
  )

  await page.goto('/knowledge')

  await expect(page.getByRole('heading', { name: 'Сервис временно недоступен' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Основная навигация' })).toHaveCount(0)
})

test('unknown route shows not found without protected navigation', async ({ page }) => {
  await page.goto('/route-that-does-not-exist')

  await expect(page.getByRole('heading', { name: 'Страница не найдена' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Основная навигация' })).toHaveCount(0)
})
