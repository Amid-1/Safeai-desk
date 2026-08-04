// ============================================================
// frontend/src/api/authApi.test.ts
// ============================================================
import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    getCurrentUser,
    login,
    parseAuthUser,
} from './authApi'
import {
    ApiError,
    apiRequest,
    ensureCsrfToken,
    rotateCsrfToken,
} from './http'

vi.mock('./http', async (importOriginal) => {
    const actual =
        await importOriginal<typeof import('./http')>()

    return {
        ...actual,
        apiRequest: vi.fn(),
        ensureCsrfToken: vi.fn(),
        rotateCsrfToken: vi.fn(),
    }
})

const apiRequestMock =
    vi.mocked(apiRequest)
const ensureCsrfTokenMock =
    vi.mocked(ensureCsrfToken)
const rotateCsrfTokenMock =
    vi.mocked(rotateCsrfToken)

const USER_RESPONSE = {
    id: '11111111-1111-1111-1111-111111111111',
    organizationId:
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    email: 'user@safeai.test',
    fullName: 'User',
    enabled: true,
    roles: ['USER'],
}

describe('authApi', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        ensureCsrfTokenMock.mockResolvedValue(
            'anonymous-csrf',
        )
    })

    it('trim/lowercase email и не изменяет password', async () => {
        apiRequestMock.mockResolvedValue(
            USER_RESPONSE,
        )
        rotateCsrfTokenMock.mockResolvedValue(
            'new-token',
        )

        await login({
            email: '  USER@SAFEAI.TEST  ',
            password: '  Secret Password  ',
        })

        expect(apiRequestMock).toHaveBeenCalledWith(
            '/api/auth/login',
            expect.objectContaining({
                method: 'POST',
                json: {
                    email: 'user@safeai.test',
                    password:
                        '  Secret Password  ',
                },
            }),
        )
    })

    it('после login требует ротацию CSRF', async () => {
        apiRequestMock.mockResolvedValue(
            USER_RESPONSE,
        )
        rotateCsrfTokenMock.mockResolvedValue(
            'new-token',
        )

        await login({
            email: 'user@safeai.test',
            password: 'secret',
        })

        expect(
            rotateCsrfTokenMock,
        ).toHaveBeenCalledWith(
            'anonymous-csrf',
        )
    })

    it('не считает login успешным при ошибке CSRF rotation', async () => {
        apiRequestMock.mockResolvedValue(
            USER_RESPONSE,
        )
        rotateCsrfTokenMock.mockRejectedValue(
            new ApiError(
                'CSRF rotation failed',
                {
                    status: 0,
                    error:
                        'CSRF_TOKEN_NOT_ROTATED',
                },
                0,
            ),
        )

        await expect(
            login({
                email: 'user@safeai.test',
                password: 'secret',
            }),
        ).rejects.toMatchObject({
            errorCode:
                'CSRF_TOKEN_NOT_ROTATED',
        })
    })

    it('проверяет AuthUser во время выполнения', async () => {
        apiRequestMock.mockResolvedValue({
            ...USER_RESPONSE,
            roles: null,
        })

        await expect(
            getCurrentUser(),
        ).rejects.toMatchObject({
            errorCode:
                'INVALID_AUTH_RESPONSE',
        })
    })

    it('отклоняет disabled user в успешном /me', () => {
        expect(() =>
            parseAuthUser({
                ...USER_RESPONSE,
                enabled: false,
            }),
        ).toThrow(
            'некорректные данные пользователя',
        )
    })
})
