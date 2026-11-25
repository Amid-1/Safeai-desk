// Platform-wide production-bundle acceptance. Synthetic API data; not a backend ACL test.
const { test, expect } = require('@playwright/test')
const { URL } = require('node:url')

const sizes = [[320,640], [360,800], [390,844], [430,932], [768,1024], [1024,768],
  [1280,720], [1366,768], [1440,900], [1920,1080], [2560,1440], [3840,2160],
  [960,540], [720,450]] // last two: CSS reflow at effective 200% zoom, not browser zoom
const org = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const actor = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const kb = '11111111-1111-4111-8111-111111111111'
const chat = '33333333-3333-4333-8333-333333333333'
const date = '2026-11-18T10:00:00Z'
const company = {id: org, name: 'Компания тестирования', enabled: true, type: 'TENANT',
  protected: false, version: 1, createdAt: date, updatedAt: date}
const person = {id: actor, email: 'admin@example.test', fullName: 'Тестовый пользователь', roles: ['USER'],
  enabled: true, organizationId: org, version: 1, createdAt: date, updatedAt: date, lastLoginAt: null}
const base = {id: kb, organizationId: org, name: 'Тестовая база', description: 'Документы для проверки',
  visibility: 'ORGANIZATION', enabled: true, createdByUserId: actor, version: 1, createdAt: date, updatedAt: date}
const document = {id: '534f6ea3-595b-47be-a90a-6397a96d16b8', knowledgeBaseId: kb,
  name: 'Техническая архитектура', enabled: true, version: 1, currentVersionId: actor,
  searchableVersionId: actor, versionNumber: 1, originalFilename: 'architecture.pdf',
  mediaType: 'application/pdf', sizeBytes: 78286, status: 'PENDING', createdAt: date, updatedAt: date}
const amounts = {inputTokens: '9', outputTokens: '16', totalTokens: '25', costUsd: '0',
  currency: 'USD', usageComplete: true, pricingComplete: true}
const metrics = {responses: {assistantMessages: 1}, usage: {confirmedInputTokens: 9, confirmedOutputTokens: 16,
  availableUsageMessages: 1, partialUsageMessages: 0, missingUsageMessages: 0, usageNotApplicableMessages: 0},
  cost: {knownCostUsd: '0', currency: 'USD', pricedMessages: 1, freeMessages: 0, unpricedMessages: 0,
    pricingFailedMessages: 0, pricingNotApplicableMessages: 0}}
const runtime = {provider: 'mock', model: 'mock-safeai', enabled: true, routingMode: 'SINGLE_PROVIDER_STATIC',
  maxInputTokens: 64000, maxOutputTokens: 2048, toolsSupported: false, visionSupported: false,
  structuredOutputSupported: false, dataRetentionStatus: 'NOT_DECLARED', healthStatus: 'NOT_PROBED',
  pricingStatus: 'FREE', inputUsdPer1mTokens: '0', outputUsdPer1mTokens: '0', pricingVersion: 'mock-2026-01'}
const policy = {configured: false, id: null, organizationId: org, version: 0, enabled: false,
  allowModelKeys: [], denyModelKeys: [], defaultModelKey: null, maxInputTokens: null, maxOutputTokens: null,
  maxRequestCostUsd: null, monthlyBudgetUsd: null, budgetEnforcement: 'SOFT', requireCompletePricing: false,
  requireNoTraining: false, requireZeroDataRetention: false, createdByUserId: null, createdAt: null}
const auditActor = {actorUserId: actor, actorOrganizationId: org, actorEmail: person.email, actorDisplayName: person.fullName}
const event = {...auditActor, id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', targetOrganizationId: org,
  targetOrganizationName: company.name, eventType: 'USER_LOGIN_SUCCESS', details: {}, createdAt: date}
const stats = {total: 1, administrators: 0, superAdministrators: 0, users: 1, enabled: 1, disabled: 0}
const paged = content => ({content, page: 0, size: 50, totalElements: content.length,
  totalPages: content.length ? 1 : 0, first: true, last: true, hasNext: false, hasPrevious: false})

async function fixture(page, role) {
  const requests = []
  await page.route('**/api/**', async route => {
    const {pathname: path, searchParams: query} = new URL(route.request().url())
    const method = route.request().method()
    requests.push(path)
    if (path === '/api/auth/csrf') return route.fulfill({status: 204, headers: {'set-cookie': 'XSRF-TOKEN=responsive-test; Path=/; SameSite=Lax'}})
    if (path === '/api/auth/me' || path === '/api/auth/refresh') {
      if (role && path.endsWith('/me')) return route.fulfill({json: {...person, roles: [role]}})
      if (path.endsWith('/refresh')) expect(route.request().headers()['x-xsrf-token']).toBe('responsive-test')
      return route.fulfill({status: 401, json: {status: 401, error: 'UNAUTHORIZED', message: 'Authentication required'}})
    }
    // No real or mocked write is allowed in the layout suite.
    if (method !== 'GET') return route.fulfill({status: 405, json: {message: 'Read-only fixture'}})
    let json
    const identity = {userId: actor, userEmail: person.email}
    const scope = query.get('organizationId') || (role === 'ADMIN' ? org : null)
    const period = {from: query.get('dateFrom')?.replace('.000Z','Z'), to: query.get('dateTo')?.replace('.000Z','Z'),
      organizationId: scope, aggregationBasis: 'PHYSICAL_ATTEMPT_STARTED_AT'}
    switch (path) {
      case '/api/admin/retention': json = {chatAllowed: true, knowledgeAllowed: false, version: 2,
        chat: {configured: true, workerInitialized: true},
        document: {configured: false, workerInitialized: false},
        base: {configured: false, workerInitialized: false}, userRetentionSeconds: 604800}; break
      case '/api/organizations': case '/api/organizations/search': json = paged([company]); break
      case '/api/organizations/directory': json = [company]; break
      case `/api/organizations/${org}`: json = company; break
      case '/api/users/search': json = paged([person]); break
      case '/api/users/statistics': case '/api/users/search/statistics': json = stats; break
      case `/api/users/${actor}`: json = {...person, organizationName: company.name}; break
      case '/api/admin/audit-event-types': json = ['USER_LOGIN_SUCCESS']; break
      case '/api/admin/audit-actors': json = [auditActor]; break
      case '/api/admin/audit-events/summary': json = {total: 1, successful: 1, denied: 0, critical: 0}; break
      case '/api/admin/audit-events/integrity': json = {state: 'NOT_CONFIGURED', archiveStatus: 'UNKNOWN'}; break
      case '/api/admin/audit-events': json = paged([event]); break
      case '/api/admin/models/runtime': json = runtime; break
      case '/api/admin/models/catalog': case '/api/admin/models/catalog/effective': json = []; break
      case `/api/admin/models/policies/${org}`: json = policy; break
      case '/api/admin/models/policies/budget-summary': json = {periodStart: '2026-11-01T00:00:00Z', periodEnd: '2026-12-01T00:00:00Z',
        asOf: date, total: 1, enabled: 1, within: 1, nearLimit: 0, exceeded: 0, noLimit: 0, unknown: 0, disabled: 0, unconfigured: 0}; break
      case '/api/admin/usage/summary': json = paged([{...amounts, ...identity, model: runtime.model}]); break
      case '/api/admin/usage/users': json = paged([{...amounts, ...identity}]); break
      case '/api/admin/usage/models': json = [{...amounts, model: runtime.model}]; break
      case '/api/admin/usage/daily': json = [{...(query.get('dateFrom') ? metrics : amounts), ...amounts,
        usageDate: query.get('dateFrom')?.slice(0,10) || '2026-11-15', aggregationZone: 'UTC'}]; break
      case '/api/admin/usage/organizations': json = paged([{...metrics, organizationId: org, organizationName: company.name}]); break
      case '/api/admin/usage/physical-attempts/report': json = {...period, planSelectionBasis: 'PLANS_WITH_PHYSICAL_START_IN_RANGE',
        currency: 'USD', physicalAttempts: '2', startedAttempts: '0', ambiguousAttempts: '0', knownExecutedAttempts: '2',
        knownNotExecutedAttempts: '0', knownRejectedAttempts: '0', costUnverifiableAttempts: '0', usageUnverifiableAttempts: '0',
        knownInputTokens: '9', knownOutputTokens: '16', knownCostUsd: '0.02', costComplete: true, usageComplete: true,
        plans: '1', costMatchedPlans: '0', costMismatchPlans: '1', unverifiablePlans: '0', fullyReconciled: false}; break
      case '/api/admin/usage/physical-attempts/providers': json = {...period, organizationId: query.get('organizationId'),
        model: query.get('model'), userId: query.get('userId'), rows: [{provider: 'mock', attempts: '2', unresolved: '0',
          unknownCost: '0', unknownUsage: '0', input: '9', output: '16', knownCostUsd: '0.02'}]}; break
      case '/api/chats/capabilities': json = {maxMessageChars: 16000, maxChatPageSize: 100, maxMessagePageSize: 100, detailsMessageLimit: 50}; break
      case '/api/chats': json = paged([{id: chat, title: 'Тестовый чат', createdAt: date, updatedAt: date}]); break
      case `/api/chats/${chat}`: json = {id: chat, title: 'Тестовый чат', createdAt: date, updatedAt: date, messages: []}; break
      case `/api/chats/${chat}/messages`: json = paged([]); break
      case '/api/knowledge-bases': case '/api/knowledge-bases/search': json = paged([base]); break
      case `/api/knowledge-bases/${kb}`: json = base; break
      case `/api/knowledge-bases/${kb}/access`: json = {knowledgeBaseId: kb, accessLevel: role === 'ADMIN' ? 'OWNER' : 'VIEWER', administrator: role === 'ADMIN',
        canEditDocuments: role === 'ADMIN', canManageBase: role === 'ADMIN', canManageMembers: role === 'ADMIN'}; break
      case `/api/knowledge-bases/${kb}/documents`: json = paged([document]); break
      case `/api/knowledge-bases/${kb}/documents/health`: json = {knowledgeBaseId: kb, state: 'HEALTHY', activeEmbeddingModel: 'mock',
        totalDocuments: 1, enabledDocuments: 1, searchableDocuments: 0, pendingDocuments: 1, processingDocuments: 0,
        failedDocuments: 0, staleEmbeddingDocuments: 0, activeChunks: 0, checkedAt: date}; break
      case `/api/knowledge-bases/${kb}/base-deletions/capability`: case `/api/knowledge-bases/${kb}/document-deletions/capability`: json = {allowed: false}; break
      case '/api/knowledge-bases/base-deletions': case `/api/knowledge-bases/${kb}/document-deletions`: json = []; break
      default: return route.fulfill({status: 404, json: {message: 'Not found'}})
    }
    return route.fulfill({json})
  })
  return requests
}

async function noPageOverflow(page) {
  await expect.poll(() => page.evaluate(() => {
    const root = globalThis.document.documentElement
    const main = globalThis.document.querySelector('main')
    return Math.max(root.scrollWidth - root.clientWidth,
      globalThis.document.body.scrollWidth - root.clientWidth,
      main ? main.scrollWidth - main.clientWidth : 0)
  })).toBeLessThanOrEqual(1)
}

async function reachable(control) {
  await expect(control).toBeVisible()
  await control.scrollIntoViewIfNeeded()
  // Playwright checks stability, pointer hit target and overlays, without force.
  await control.click({trial: true})
  await expect(control).toBeInViewport({ratio: 0.9})
}

async function openNavigation(page) {
  await expect(page.locator('.topbar')).toBeVisible()
  const menu = page.getByRole('button', {name: 'Меню', exact: true})
  if (await menu.isVisible() && await menu.getAttribute('aria-expanded') !== 'true') await menu.click()
  return page.getByRole('navigation', {name: 'Основная навигация'})
}

async function closeNavigation(page) {
  const menu = page.getByRole('button', {name: 'Меню', exact: true})
  if (await menu.isVisible() && await menu.getAttribute('aria-expanded') === 'true') await menu.click()
}

async function tableEdges(table) {
  const row = table.getByRole('row').last()
  const cells = row.getByRole('cell')
  await expect(cells.first()).toBeVisible()
  for (const cell of [cells.last(), cells.first()]) {
    await cell.scrollIntoViewIfNeeded()
    await expect(cell).toBeInViewport({ratio: 0.5})
  }
}

async function closeModal(page, dialog) {
  await expect(dialog).toBeVisible()
  const box = await dialog.boundingBox()
  const viewport = page.viewportSize()
  expect(box.x).toBeGreaterThanOrEqual(-1)
  expect(box.x + box.width).toBeLessThanOrEqual(viewport.width + 1)
  expect(box.y).toBeGreaterThanOrEqual(-1)
  expect(box.y + box.height).toBeLessThanOrEqual(viewport.height + 1)
  const close = dialog.getByRole('button', {name: 'Закрыть окно'})
  await reachable(close)
  await noPageOverflow(page)
  await close.click()
  await expect(dialog).toHaveCount(0)
}

const routes = [
  {path: '/admin/dashboard', link: 'Обзор', roles: ['SUPER_ADMIN'], exercise: async page => {
    const chart = page.getByRole('region', {name: 'График использования AI по дням'})
    await expect(chart.locator('svg.recharts-surface').first()).toBeVisible()
    await expect(page.getByRole('region', {name: 'Организации', exact: true})).toContainText('1')
    await chart.getByRole('button', {name: 'Развернуть график'}).click()
    await reachable(chart.getByRole('button', {name: 'Свернуть график'}))
    await chart.getByRole('button', {name: 'Свернуть график'}).click()
  }},
  {path: '/admin/users', link: 'Пользователи', roles: ['ADMIN','SUPER_ADMIN'], exercise: async (page, role, width, height) => {
    await expect(page.getByRole('row').filter({hasText: person.email})).toBeVisible()
    const search = page.getByRole('searchbox')
    await reachable(search)
    const filterRequest = page.waitForRequest(request => {
      const url = new URL(request.url())
      return url.pathname === '/api/users/search' && url.searchParams.get('query') === person.email
    })
    await search.fill(person.email)
    await filterRequest
    const suggestions = page.locator('#users-directory-suggestions')
    await expect(suggestions.getByRole('button').filter({hasText: person.email})).toBeVisible()
    const grip = page.getByRole('separator', {name: 'Изменить размер области: список пользователей'})
    if (width > 1024 && height > 620 && height <= 780) await expect(grip).toBeHidden()
    // Click the actual row while the inline suggestions are still open.
    // Blur must not move the target before the click is dispatched.
    const row = page.getByRole('row').filter({hasText: person.email})
    await expect(row).toBeVisible()
    const details = row.getByRole('button', {name: `Подробнее о пользователе ${person.email}`})
    await reachable(details)
    const detailResponse = page.waitForResponse(response =>
      new URL(response.url()).pathname === `/api/users/${actor}` && response.request().method() === 'GET')
    await details.click()
    expect((await detailResponse).status()).toBe(200)
    await expect(suggestions).toHaveCount(0)
    const dialog = page.getByRole('dialog', {name: 'Карточка пользователя'})
    await expect(dialog).toContainText(person.email)
    await closeModal(page, dialog)
    if (width > 1024 && height > 620) await expect(grip).toBeVisible()
  }},
  {path: '/admin/organizations', link: 'Организации', roles: ['SUPER_ADMIN'], exercise: async (page, role, width, height) => {
    const row = page.getByRole('row').filter({hasText: company.name})
    const details = row.getByRole('button', {name: 'Подробнее'})
    const grip = page.getByRole('separator', {name: 'Изменить размер области: список организаций'})
    await expect(row).toBeVisible()
    if (width <= 900 || height <= 620) {
      await expect(grip).toBeHidden()
      // The sticky header and a row action must fit inside the actual
      // scrollport; a nominally visible but clipped button is not enough.
      await expect.poll(() => page.locator('.organizations-table-scroll').evaluate(el => {
        const header = el.querySelector('thead')
        const action = el.querySelector('tbody .org-actions-compact > button')
        return header && action ? el.clientHeight - header.getBoundingClientRect().height -
          action.getBoundingClientRect().height : -Infinity
      })).toBeGreaterThanOrEqual(0)
    } else {
      await expect(grip).toBeVisible()
    }
    await reachable(details)
    const detailResponse = page.waitForResponse(response =>
      new URL(response.url()).pathname === `/api/organizations/${org}` && response.request().method() === 'GET')
    await details.click()
    expect((await detailResponse).status()).toBe(200)
    const dialog = page.getByRole('dialog', {name: 'Карточка организации'})
    await expect(dialog).toContainText(company.name)
    await closeModal(page, dialog)
  }},
  {path: '/admin/audit', link: 'Аудит', roles: ['ADMIN','SUPER_ADMIN'], exercise: async (page, role, width, height) => {
    await expect(page.getByRole('region', {name: 'Сводка аудита'}).getByRole('button', {name: /Всего событий 1/})).toBeVisible()
    const details = page.getByRole('table').getByRole('button', {name: 'Подробнее'})
    const grip = page.getByRole('separator', {name: 'Изменить размер области: журнал событий'})
    if (height <= 620) {
      await expect(grip).toBeHidden()
      await expect.poll(() => page.locator('.audit-page .admin-table-wrapper').evaluate(el => {
        const header = el.querySelector('thead')
        const action = el.querySelector('tbody .audit-details-action button')
        return header && action ? el.clientHeight - header.getBoundingClientRect().height -
          action.getBoundingClientRect().height : -Infinity
      })).toBeGreaterThanOrEqual(0)
    }
    await reachable(details)
    await details.click()
    await closeModal(page, page.getByRole('dialog'))
  }},
  {path: '/admin/usage', link: 'Использование', roles: ['ADMIN','SUPER_ADMIN'], exercise: async (page, role, width, height) => {
    await expect(page.getByRole('table').getByText(person.email, {exact: false}).first()).toBeVisible()
    await tableEdges(page.getByRole('table'))
    const physical = page.getByRole('region', {name: 'Физические вызовы и сверка расходов'})
    await physical.getByRole('button', {name: 'Показать сверку'}).click()
    await expect(physical.getByRole('alert')).toContainText('Обнаружены финансовые расхождения: 1')
    await expect(physical.getByText('Сверка пройдена', {exact: false})).toHaveCount(0)
    await physical.getByRole('button', {name: 'Свернуть', exact: true}).click()
    await page.getByRole('button', {name: 'Изменить фильтры'}).click()
    await reachable(page.getByLabel('Начало периода'))
    await page.getByRole('button', {name: 'Свернуть фильтры'}).click()
    const grip = page.getByRole('separator', {name: 'Высота области отчёта'})
    if (width > 900 && height > 620) {
      await expect(grip).toBeInViewport()
      await grip.press('End')
      await expect(grip).toHaveAttribute('aria-valuenow', '100')
      await grip.press('Home')
    }
    await page.getByRole('button', {name: 'График', exact: true}).click()
    await expect(page.locator('#usage-breakdown-chart-content')).toBeVisible()
    await noPageOverflow(page)
    await page.getByRole('button', {name: 'Таблица', exact: true}).click()
    if (role === 'SUPER_ADMIN') await expect(page.getByRole('tab', {name: 'По организациям'})).toBeVisible()
    else await expect(page.getByRole('tab', {name: 'По организациям'})).toHaveCount(0)
    await page.getByRole('tab', {name: 'По провайдерам'}).click()
    await expect(page.getByRole('table').getByText('mock', {exact: true})).toBeVisible()
    await tableEdges(page.getByRole('table'))
  }},
  {path: '/admin/models', link: 'Модели', roles: ['ADMIN','SUPER_ADMIN'], exercise: async (page, role) => {
    await page.getByRole('tab', {name: 'Каталог'}).click()
    if (role === 'SUPER_ADMIN') {
      await page.getByRole('button', {name: 'Создать модель'}).click()
      const dialog = page.getByRole('dialog')
      await dialog.getByLabel('Название', {exact: true}).fill('Responsive model')
      await dialog.getByRole('tab', {name: 'Возможности модели'}).click()
      await reachable(dialog.getByRole('checkbox', {name: 'Инструменты'}))
      await dialog.getByRole('tab', {name: 'Основные данные'}).click()
      await expect(dialog.getByLabel('Название', {exact: true})).toHaveValue('Responsive model')
      page.once('dialog', async confirmation => {
        expect(confirmation.message()).toBe('Отменить несохранённые изменения версии каталога?')
        await confirmation.accept()
      })
      await closeModal(page, dialog)
    } else await expect(page.getByRole('button', {name: 'Создать модель'})).toHaveCount(0)
    await page.getByRole('tab', {name: 'Доступ'}).click()
    await expect(page.getByRole('heading', {name: 'Правила использования моделей'})).toBeVisible()
  }},
  {path: '/chat', link: 'Чат', roles: ['ADMIN','USER'], exercise: async page => {
    await page.getByRole('button', {name: 'Создать чат'}).click()
    const dialog = page.getByRole('dialog', {name: 'Создать чат'})
    await dialog.getByLabel('Название чата').fill('Проверка адаптивности')
    await closeModal(page, dialog)
    await page.getByRole('button', {name: 'Открыть чат «Тестовый чат»'}).click()
    const input = page.getByPlaceholder('Введите сообщение. Ctrl+Enter — отправить')
    await input.fill('Проверка ввода без отправки')
    await expect(input).toHaveValue('Проверка ввода без отправки')
    await reachable(input)
  }},
  {path: '/knowledge', link: 'Базы знаний', roles: ['ADMIN','USER'], exercise: async (page, role) => {
    await expect(page.getByRole('link', {name: base.name})).toBeVisible()
    const search = page.getByRole('searchbox', {name: 'Поиск по названию'})
    await reachable(search)
    const filterRequest = page.waitForRequest(request => {
      const url = new URL(request.url())
      return url.pathname === '/api/knowledge-bases/search' && url.searchParams.get('query') === base.name
    })
    await search.fill(base.name)
    await filterRequest
    await expect(page.getByRole('link', {name: base.name})).toBeVisible()
    if (role === 'ADMIN') {
      await page.getByRole('button', {name: 'Создать базу знаний'}).click()
      await closeModal(page, page.getByRole('dialog'))
    } else await expect(page.getByRole('button', {name: 'Создать базу знаний'})).toHaveCount(0)
    await page.getByRole('link', {name: base.name}).click()
    await expect(page).toHaveURL(new RegExp('/knowledge/' + kb))
    await expect(page.getByRole('heading', {name: base.name})).toBeVisible()
    await expect(page.getByText(document.name, {exact: true})).toBeVisible()
    await noPageOverflow(page)
  }},
]

for (const [width, height] of sizes) {
  test.describe(`responsive ${width}×${height}`, () => {
    const touch = width <= 430 || (width === 768 && height === 1024) || (width === 1024 && height === 768)
    test.use({viewport: {width,height}, hasTouch: touch, isMobile: touch})
    for (const entry of routes) for (const role of entry.roles) {
      test(`${role} ${entry.path} usable without page overflow`, async ({page}) => {
        await fixture(page, role)
        await page.goto(entry.path)
        const nav = await openNavigation(page)
        const link = nav.getByRole('link', {name: entry.link, exact: true})
        await expect(link).toHaveAttribute('aria-current', 'page')
        await reachable(link)
        await closeNavigation(page)
        await entry.exercise(page, role, width, height)
        await noPageOverflow(page)
      })
    }
    test('anonymous login form, protected redirect and not-found page', async ({page}) => {
      const requests = await fixture(page, null)
      await page.goto('/login')
      await expect(page.getByRole('heading', {name: 'Вход в SafeAI Desk'})).toBeVisible()
      await page.getByLabel('Email').fill('user@example.test')
      await page.getByLabel('Пароль').fill('responsive-test-password')
      await reachable(page.getByRole('button', {name: 'Войти'}))
      await noPageOverflow(page)
      await page.goto('/admin/usage')
      await expect(page).toHaveURL(/\/login$/)
      await expect(page.getByRole('heading', {name: 'Вход в SafeAI Desk'})).toBeVisible()
      await page.goto('/responsive-route-not-found')
      await expect(page.getByRole('heading', {name: 'Страница не найдена'})).toBeVisible()
      await expect(page.getByRole('navigation', {name: 'Основная навигация'})).toHaveCount(0)
      await noPageOverflow(page)
      expect(requests.filter(path => !path.startsWith('/api/auth/'))).toEqual([])
    })
    for (const role of ['USER','ADMIN','SUPER_ADMIN']) {
      test(`${role} forbidden routes do not request protected data`, async ({page}) => {
        const requests = await fixture(page, role)
        for (const entry of routes.filter(item => !item.roles.includes(role))) {
          await page.goto(entry.path)
          await expect(page.getByRole('alert')).toContainText('Недостаточно прав')
          const nav = await openNavigation(page)
          await expect(nav.getByRole('link', {name: entry.link, exact: true})).toHaveCount(0)
          await noPageOverflow(page)
        }
        expect(requests.filter(path => !path.startsWith('/api/auth/'))).toEqual([])
        const allowed = routes.find(item => item.roles.includes(role))
        const nav = await openNavigation(page)
        await nav.getByRole('link', {name: allowed.link, exact: true}).click()
        await expect(page).toHaveURL(new RegExp(`${allowed.path}$`))
        await noPageOverflow(page)
      })
    }
  })
}

test('live portrait, landscape and desktop reflow preserves chat draft and navigation', async ({page}) => {
  await fixture(page, 'USER')
  await page.setViewportSize({width: 390, height: 844})
  await page.goto('/chat')
  await page.getByRole('button', {name: 'Открыть чат «Тестовый чат»'}).click()
  const composer = page.getByPlaceholder('Введите сообщение. Ctrl+Enter — отправить')
  await composer.fill('Несохранённый черновик')
  for (const [width,height] of [[844,390], [1440,900], [360,800]]) {
    await page.setViewportSize({width,height})
    await expect(composer).toHaveValue('Несохранённый черновик')
    await reachable(composer)
    await reachable(page.getByRole('button', {name: 'Отправить', exact: true}))
    await expect(composer).toHaveValue('Несохранённый черновик')
    await noPageOverflow(page)
  }
  const nav = await openNavigation(page)
  await nav.getByRole('link', {name: 'Базы знаний'}).click()
  await expect(page.getByRole('link', {name: base.name})).toBeVisible()
  await noPageOverflow(page)
})

test('knowledge catalog failure does not send chat in another mode', async ({page}) => {
  await fixture(page, 'USER')
  let catalogAvailable = false
  await page.route('**/api/knowledge-bases?*', async route => {
    if (!catalogAvailable) await route.fulfill({status: 503, json: {message: 'Unavailable'}})
    else await route.fallback()
  })
  await page.goto('/chat')
  await page.getByRole('button', {name: 'Открыть чат «Тестовый чат»'}).click()
  const composer = page.getByPlaceholder('Введите сообщение. Ctrl+Enter — отправить')
  await composer.fill('Черновик вопроса')
  await page.getByLabel('Режим ответа').selectOption('KNOWLEDGE_ONLY')
  await expect(page.getByText('Не удалось загрузить базы знаний.')).toBeVisible()
  const send = page.getByRole('button', {name: 'Отправить', exact: true})
  await expect(send).toBeDisabled()
  catalogAvailable = true
  await page.getByRole('button', {name: 'Повторить загрузку'}).click()
  await page.getByLabel('База знаний', {exact: true}).selectOption({label: base.name})
  await expect(send).toBeEnabled()
  await expect(composer).toHaveValue('Черновик вопроса')
})

test('retention policy remains usable on phone and desktop, cancel sends no mutation', async ({page}) => {
  const requests = await fixture(page, 'SUPER_ADMIN')
  await page.setViewportSize({width: 320, height: 640})
  await page.goto('/admin/retention')
  await expect(page.getByRole('heading', {name: 'Хранение и удаление', exact: true})).toBeVisible()
  const pause = page.getByRole('button', {name: 'Приостановить приём запросов на удаление чатов', exact: true})
  await expect(pause).toBeEnabled()
  await expect(page.getByRole('button', {name: 'Возобновить приём запросов на удаление документов и баз', exact: true})).toBeDisabled()
  for (const [width,height] of [[320,640], [1280,720]]) {
    await page.setViewportSize({width,height})
    await noPageOverflow(page)
    await pause.click()
    const dialog = page.getByRole('dialog', {name: 'Приостановить приём запросов на удаление чатов?', exact: true})
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', {name: 'Отмена'}).click()
    await expect(dialog).toHaveCount(0)
  }
  expect(requests.filter(path => path === '/api/admin/retention')).toHaveLength(1)
})

test('chat deletion entry point archives first and respects server refusal', async ({page}) => {
  await fixture(page, 'ADMIN')
  const item = {id: chat, title: 'Тестовый чат', createdAt: date, updatedAt: date}
  let archived = false
  let deleteRequests = 0
  await page.route('**/api/chats/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() === 'DELETE') {
      deleteRequests++
      return route.fulfill({status: 403})
    }
    if (path === `/api/chats/${chat}/archive`) {
      expect(route.request().method()).toBe('POST')
      archived = true
      return route.fulfill({status: 204})
    }
    if (path === '/api/chats/archived') return route.fulfill({json: paged(archived ? [item] : [])})
    if (path === '/api/chats/erasure-operations') return route.fulfill({json: []})
    if (path === `/api/chats/${chat}/archived`) return route.fulfill({json: {...item, messages: []}})
    if (path === `/api/chats/${chat}/erasure`) return route.fulfill({status: 404})
    if (path === `/api/chats/${chat}/erasure/capability`) {
      return route.fulfill({json: {allowed: false, reason: 'FEATURE_DISABLED', version: 1}})
    }
    return route.fallback()
  })
  await page.goto('/chat')
  await page.getByRole('button', {name: 'Действия с чатом «Тестовый чат»'}).click()
  await page.getByRole('button', {name: 'Удалить содержимое…', exact: true}).click()
  await page.getByRole('button', {name: 'Архивировать и продолжить'}).click()
  const archive = page.getByRole('dialog', {name: 'Архив чатов'})
  await expect(archive.getByRole('heading', {name: item.title})).toBeVisible()
  await archive.getByRole('button', {name: 'Удалить содержимое чата…'}).click()
  await expect(archive.getByRole('alert')).toContainText('Удаление отключено на сервере.')
  expect(archived).toBe(true)
  expect(deleteRequests).toBe(0)
})

test('regular user cannot enter permanent erasure from the chat menu', async ({page}) => {
  await fixture(page, 'USER')
  await page.goto('/chat')
  await page.getByRole('button', {name: 'Действия с чатом «Тестовый чат»'}).click()
  await expect(page.getByRole('button', {name: 'Архивировать', exact: true})).toBeVisible()
  await expect(page.getByRole('button', {name: 'Удалить содержимое…', exact: true})).toHaveCount(0)
})

test('mobile shell keyboard navigation and visual evidence', async ({page}, testInfo) => {
  await fixture(page, 'SUPER_ADMIN')
  await page.setViewportSize({width: 320, height: 640})
  await page.goto('/admin/organizations')
  const menu = page.getByRole('button', {name: 'Меню', exact: true})
  await expect(menu).toHaveAttribute('aria-expanded', 'false')
  const nav = page.locator('#main-navigation')
  await expect(nav).toBeHidden()
  const skip = page.getByRole('link', {name: 'Перейти к содержимому'})
  await skip.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('main')).toBeFocused()
  await menu.focus()
  await page.keyboard.press('Enter')
  await expect(nav).toBeVisible()
  await expect(nav.getByRole('link', {name: 'Организации'})).toHaveAttribute('aria-current', 'page')
  await noPageOverflow(page)
  await testInfo.attach('shell-mobile', {body: await page.screenshot(), contentType: 'image/png'})
  await page.keyboard.press('Escape')
  await expect(nav).toBeHidden()
  await expect(menu).toBeFocused()
  await page.keyboard.press('Enter')
  await nav.getByRole('link', {name: 'Пользователи'}).click()
  await expect(page).toHaveURL(/\/admin\/users$/)
  await expect(nav).toBeHidden()
  await expect(page.getByRole('main')).toBeFocused()
  await page.setViewportSize({width: 1440, height: 900})
  await expect(nav).toBeVisible()
  await noPageOverflow(page)
  await testInfo.attach('shell-desktop', {body: await page.screenshot(), contentType: 'image/png'})
})

test('shared organization dialog geometry is independent of visiting users', async ({page}) => {
  await fixture(page, 'SUPER_ADMIN')
  await page.setViewportSize({width: 1440, height: 900})
  await page.goto('/admin/organizations')
  async function measure() {
    const row = page.getByRole('row').filter({hasText: company.name})
    await row.getByRole('button', {name: 'Подробнее'}).click()
    const dialog = page.getByRole('dialog', {name: 'Карточка организации'})
    await expect(dialog).toContainText(company.name)
    const style = await dialog.evaluate(el => {
      const card = el.ownerDocument.defaultView.getComputedStyle(el)
      const body = el.ownerDocument.defaultView.getComputedStyle(el.querySelector('.modal-body'))
      return [card.display, card.borderRadius, card.padding, body.padding, body.overflowY]
    })
    await closeModal(page, dialog)
    return style
  }
  const initial = await measure()
  const nav = await openNavigation(page)
  await nav.getByRole('link', {name: 'Пользователи'}).click()
  await expect(page.getByRole('searchbox')).toBeVisible()
  await nav.getByRole('link', {name: 'Организации'}).click()
  expect(await measure()).toEqual(initial)
})
