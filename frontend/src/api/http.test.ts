import {
    afterEach,
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    ApiError,
    apiRequest,
    buildApiUrl,
    parseRetryAfter,
    validateApiBasePath,
} from './http'

type FetchImplementation = (
    input: RequestInfo | URL,
    init?: RequestInit,
) => Promise<Response>

type FetchMock = ReturnType<
    typeof createFetchMock
>

function createFetchMock(
    implementation: FetchImplementation,
) {
    return vi.fn(implementation)
}

function requireFetchCall(
    fetchMock: FetchMock,
    index = 0,
): [
    input: RequestInfo | URL,
    init?: RequestInit,
] {
    const call =
        fetchMock.mock.calls[index]

    if (!call) {
        throw new Error(
            `Ожидался fetch-вызов с индексом ${index}`,
        )
    }

    return call
}

function requireRequestInit(
    fetchMock: FetchMock,
    index = 0,
): RequestInit {
    const [, init] =
        requireFetchCall(
            fetchMock,
            index,
        )

    if (!init) {
        throw new Error(
            `Fetch-вызов с индексом ${index} не содержит RequestInit`,
        )
    }

    return init
}

function jsonResponse(
    body: unknown,
    init: ResponseInit = {},
): Response {
    const headers =
        new Headers(init.headers)

    if (
        !headers.has('Content-Type')
    ) {
        headers.set(
            'Content-Type',
            'application/json',
        )
    }

    return new Response(
        JSON.stringify(body),
        {
            ...init,
            status: init.status ?? 200,
            headers,
        },
    )
}

function setCsrfCookie(
    value: string,
): void {
    document.cookie =
        `XSRF-TOKEN=${encodeURIComponent(value)}; Path=/`
}

function clearCookies(): void {
    document.cookie =
        'XSRF-TOKEN=; Max-Age=0; Path=/'
}

describe('http API client', () => {
    beforeEach(() => {
        vi.restoreAllMocks()
        clearCookies()

        Object.defineProperty(
            navigator,
            'locks',
            {
                configurable: true,
                value: {
                    request: vi.fn(
                        async (
                            _name: string,
                            _options: unknown,
                            callback:
                                () => Promise<unknown>,
                        ) => callback(),
                    ),
                },
            },
        )
    })

    afterEach(() => {
        vi.useRealTimers()
        vi.unstubAllGlobals()
        clearCookies()
    })

    it.each([
        '/api/users',
        '/api/users?page=1',
    ])('принимает same-origin API path %s', (path) => {
        expect(buildApiUrl(path)).toBe(path)
    })

    it.each([
        'http://attacker.example',
        'https://attacker.example',
        '//attacker.example',
    ])('отклоняет внешний URL %s', (path) => {
        expect(() =>
            buildApiUrl(path),
        ).toThrow()
    })

    it('отклоняет внешний VITE API base', () => {
        expect(() =>
            validateApiBasePath(
                'https://attacker.example',
            ),
        ).toThrow(
            'same-origin path',
        )
    })

    it('не позволяет выйти из /api через ../', () => {
        expect(() =>
            buildApiUrl(
                '/api/users/../../collect',
            ),
        ).toThrow(
            'Некорректный API URL',
        )
    })

    it('fetch использует same-origin и credentials include', async () => {
        const fetchMock = createFetchMock(
            async () => jsonResponse({
                ok: true,
            }),
        )

        vi.stubGlobal('fetch', fetchMock)

        await apiRequest('/api/users')

        expect(fetchMock).toHaveBeenCalledWith(
            '/api/users',
            expect.objectContaining({
                mode: 'same-origin',
                credentials: 'include',
            }),
        )
    })

    it('unsafe request получает существующий CSRF header', async () => {
        setCsrfCookie('csrf-token')

        const fetchMock = createFetchMock(
            async () => new Response(null, {
                status: 204,
            }),
        )

        vi.stubGlobal('fetch', fetchMock)

        await apiRequest('/api/users', {
            method: 'POST',
            json: {
                name: 'User',
            },
        })

        const init = requireRequestInit(fetchMock)

        expect(
            new Headers(init.headers).get(
                'X-XSRF-TOKEN',
            ),
        ).toBe('csrf-token')
    })

    it('при отсутствии cookie вызывает CSRF bootstrap', async () => {
        const fetchMock = createFetchMock(
            async (input: RequestInfo | URL) => {
                if (
                    String(input)
                    === '/api/auth/csrf'
                ) {
                    setCsrfCookie('bootstrapped')
                    return new Response(null, {
                        status: 204,
                    })
                }

                return new Response(null, {
                    status: 204,
                })
            },
        )

        vi.stubGlobal('fetch', fetchMock)

        await apiRequest('/api/users', {
            method: 'POST',
            json: {
                name: 'User',
            },
        })

        expect(fetchMock).toHaveBeenCalledTimes(2)
        expect(requireFetchCall(fetchMock)[0]).toBe(
            '/api/auth/csrf',
        )
    })

    it('не отправляет mutation, если CSRF cookie не появилась', async () => {
        const fetchMock = createFetchMock(
            async () => new Response(null, {
                status: 204,
            }),
        )

        vi.stubGlobal('fetch', fetchMock)

        await expect(
            apiRequest('/api/users', {
                method: 'POST',
                json: {
                    name: 'User',
                },
            }),
        ).rejects.toMatchObject({
            errorCode: 'CSRF_TOKEN_MISSING',
        })

        expect(fetchMock).toHaveBeenCalledTimes(1)
        expect(requireFetchCall(fetchMock)[0]).toBe(
            '/api/auth/csrf',
        )
    })

    it('FormData не получает ручной Content-Type', async () => {
        setCsrfCookie('csrf-token')

        const fetchMock = createFetchMock(
            async () => new Response(null, {
                status: 204,
            }),
        )

        vi.stubGlobal('fetch', fetchMock)

        const formData = new FormData()

        formData.set('file', 'value')

        await apiRequest('/api/upload', {
            method: 'POST',
            body: formData,
        })

        const init = requireRequestInit(fetchMock)

        expect(
            new Headers(init.headers).has(
                'Content-Type',
            ),
        ).toBe(false)
    })

    it('повторяет stale CSRF только при специальном error code', async () => {
        setCsrfCookie('stale')

        let mutationCount = 0

        const fetchMock = createFetchMock(
            async (input: RequestInfo | URL) => {
                if (
                    String(input)
                    === '/api/auth/csrf'
                ) {
                    setCsrfCookie('fresh')
                    return new Response(null, {
                        status: 204,
                    })
                }

                mutationCount += 1

                if (mutationCount === 1) {
                    return jsonResponse(
                        {
                            error:
                                'CSRF_TOKEN_INVALID',
                            message:
                                'Invalid CSRF',
                        },
                        {
                            status: 403,
                        },
                    )
                }

                return new Response(null, {
                    status: 204,
                })
            },
        )

        vi.stubGlobal('fetch', fetchMock)

        await apiRequest('/api/users', {
            method: 'POST',
            json: {
                name: 'User',
            },
        })

        expect(mutationCount).toBe(2)
    })

    it('не повторяет обычный 403', async () => {
        setCsrfCookie('valid')

        const fetchMock = createFetchMock(
            async () => jsonResponse(
                {
                    error: 'ACCESS_DENIED',
                    message: 'Denied',
                },
                {
                    status: 403,
                },
            ),
        )

        vi.stubGlobal('fetch', fetchMock)

        await expect(
            apiRequest('/api/users', {
                method: 'POST',
                json: {
                    name: 'User',
                },
            }),
        ).rejects.toMatchObject({
            status: 403,
        })

        expect(fetchMock).toHaveBeenCalledTimes(1)
    })

    it('пять 401 в одной вкладке создают один refresh', async () => {
        setCsrfCookie('csrf-token')

        let refreshed = false
        let refreshCount = 0
        let probeCount = 0

        const fetchMock = createFetchMock(
            async (input: RequestInfo | URL) => {
                const url = String(input)

                if (url === '/api/auth/me') {
                    probeCount += 1
                    return new Response(null, {
                        status: refreshed
                            ? 200
                            : 401,
                    })
                }

                if (
                    url
                    === '/api/auth/refresh'
                ) {
                    refreshCount += 1
                    refreshed = true
                    return new Response(null, {
                        status: 204,
                    })
                }

                if (
                    url
                    === '/api/auth/csrf'
                ) {
                    setCsrfCookie('fresh-token')
                    return new Response(null, {
                        status: 204,
                    })
                }

                if (!refreshed) {
                    return new Response(null, {
                        status: 401,
                    })
                }

                return jsonResponse({
                    ok: true,
                })
            },
        )

        vi.stubGlobal('fetch', fetchMock)

        await Promise.all(
            Array.from(
                { length: 5 },
                () =>
                    apiRequest('/api/users'),
            ),
        )

        expect(refreshCount).toBe(1)
        expect(probeCount).toBe(1)
    })

    it('после lock повторно проверяет /me и не refresh-ит уже обновлённую сессию', async () => {
        setCsrfCookie('csrf-token')

        let usersCallCount = 0
        let refreshCount = 0
        let probeCount = 0

        const fetchMock = createFetchMock(
            async (input: RequestInfo | URL) => {
                const url = String(input)

                if (url === '/api/users') {
                    usersCallCount += 1

                    return usersCallCount === 1
                        ? new Response(null, {
                            status: 401,
                        })
                        : jsonResponse({
                            ok: true,
                        })
                }

                if (url === '/api/auth/me') {
                    probeCount += 1
                    return new Response(null, {
                        status: 200,
                    })
                }

                if (
                    url
                    === '/api/auth/refresh'
                ) {
                    refreshCount += 1
                }

                return new Response(null, {
                    status: 204,
                })
            },
        )

        vi.stubGlobal('fetch', fetchMock)

        await apiRequest('/api/users')

        expect(probeCount).toBe(1)
        expect(refreshCount).toBe(0)
        expect(usersCallCount).toBe(2)
    })

    it('external abort даёт REQUEST_ABORTED', async () => {
        const controller =
            new AbortController()

        const fetchMock = createFetchMock(
            (
                _input: RequestInfo | URL,
                init?: RequestInit,
            ) => new Promise<Response>(
                (_resolve, reject) => {
                    init?.signal
                        ?.addEventListener(
                            'abort',
                            () => {
                                reject(
                                    new DOMException(
                                        'Aborted',
                                        'AbortError',
                                    ),
                                )
                            },
                        )
                },
            ),
        )

        vi.stubGlobal('fetch', fetchMock)

        const request = apiRequest(
            '/api/users',
            {
                signal: controller.signal,
                timeoutMs: 0,
            },
        )

        controller.abort()

        await expect(request).rejects
            .toMatchObject({
                errorCode:
                    'REQUEST_ABORTED',
            })
    })

    it('timeout даёт REQUEST_TIMEOUT', async () => {
        vi.useFakeTimers()

        const fetchMock = createFetchMock(
            (
                _input: RequestInfo | URL,
                init?: RequestInit,
            ) => new Promise<Response>(
                (_resolve, reject) => {
                    init?.signal
                        ?.addEventListener(
                            'abort',
                            () => {
                                reject(
                                    init.signal?.reason
                                    ?? new DOMException(
                                        'Aborted',
                                        'AbortError',
                                    ),
                                )
                            },
                        )
                },
            ),
        )

        vi.stubGlobal('fetch', fetchMock)

        const request = apiRequest(
            '/api/users',
            {
                timeoutMs: 10,
            },
        )

        await vi.advanceTimersByTimeAsync(11)

        await expect(request).rejects
            .toMatchObject({
                errorCode:
                    'REQUEST_TIMEOUT',
            })
    })

    it('отклоняет некорректный timeout', async () => {
        await expect(
            apiRequest('/api/users', {
                timeoutMs: -1,
            }),
        ).rejects.toThrow(
            'неотрицательным числом',
        )
    })

    it('разбирает Retry-After seconds', () => {
        expect(parseRetryAfter('12.2')).toBe(13)
    })

    it('разбирает Retry-After HTTP-date', () => {
        vi.useFakeTimers()
        vi.setSystemTime(
            new Date(
                '2026-08-04T18:00:00Z',
            ),
        )

        expect(
            parseRetryAfter(
                'Tue, 04 Aug 2026 18:00:10 GMT',
            ),
        ).toBe(10)
    })

    it('сохраняет request ID из response header', async () => {
        const fetchMock = createFetchMock(
            async () => jsonResponse(
                {
                    error: 'HTTP_ERROR',
                    message: 'Failure',
                },
                {
                    status: 500,
                    headers: {
                        'X-Request-Id':
                            'request-123',
                    },
                },
            ),
        )

        vi.stubGlobal('fetch', fetchMock)

        await expect(
            apiRequest('/api/users'),
        ).rejects.toMatchObject({
            requestId: 'request-123',
        })
    })

    it('не показывает HTML error page пользователю целиком', async () => {
        const fetchMock = createFetchMock(
            async () => new Response(
                '<html lang="en"><body>proxy stack trace</body></html>',
                {
                    status: 502,
                    headers: {
                        'Content-Type':
                            'text/html',
                    },
                },
            ),
        )

        vi.stubGlobal('fetch', fetchMock)

        let error: unknown

        try {
            await apiRequest('/api/users')
        } catch (caught) {
            error = caught
        }

        expect(error).toBeInstanceOf(ApiError)
        expect(
            (error as ApiError).message,
        ).not.toContain(
            'proxy stack trace',
        )
        expect(
            (error as ApiError).errorCode,
        ).toBe(
            'INVALID_ERROR_RESPONSE',
        )
    })
})