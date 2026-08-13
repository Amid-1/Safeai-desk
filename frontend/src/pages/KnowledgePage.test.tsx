import {
    render,
    screen,
} from '@testing-library/react'
import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import type {
    AuthUser,
} from '../api/authApi'
import {
    getKnowledgeBases,
} from '../api/knowledgeApi'
import KnowledgePage from './KnowledgePage'

const authMock = vi.hoisted(() => ({
    currentUser: null as AuthUser | null,
}))

vi.mock('../auth/AuthContext', () => ({
    useAuth: () => authMock,
}))

vi.mock('../api/knowledgeApi', () => ({
    getKnowledgeBases: vi.fn(),
    createKnowledgeBase: vi.fn(),
    updateKnowledgeBase: vi.fn(),
    getKnowledgeBaseMembers: vi.fn(),
    searchKnowledgeMemberCandidates:
        vi.fn(),
    addKnowledgeBaseMember: vi.fn(),
    updateKnowledgeBaseMember: vi.fn(),
    removeKnowledgeBaseMember: vi.fn(),
}))

const getKnowledgeBasesMock =
    vi.mocked(getKnowledgeBases)

function user(
    role: 'ADMIN' | 'USER',
): AuthUser {
    return {
        id:
            '11111111-1111-4111-8111-111111111111',
        organizationId:
            'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        email:
            'user@safeai.test',
        fullName: null,
        enabled: true,
        roles: [role],
    }
}

describe('KnowledgePage', () => {
    beforeEach(() => {
        vi.clearAllMocks()

        getKnowledgeBasesMock
            .mockResolvedValue({
                content: [],
                page: 0,
                size: 24,
                totalElements: 0,
                totalPages: 0,
            })
    })

    it('ADMIN видит создание базы знаний', async () => {
        authMock.currentUser =
            user('ADMIN')

        render(<KnowledgePage />)

        expect(
            await screen.findByRole(
                'button',
                {
                    name:
                        'Создать базу знаний',
                },
            ),
        ).toBeInTheDocument()
    })

    it('USER получает read-only Knowledge UI', async () => {
        authMock.currentUser =
            user('USER')

        render(<KnowledgePage />)

        expect(
            await screen.findByText(
                'Вам пока не доступна ни одна база знаний.',
            ),
        ).toBeInTheDocument()

        expect(
            screen.queryByRole(
                'button',
                {
                    name:
                        'Создать базу знаний',
                },
            ),
        ).not.toBeInTheDocument()
    })
})
