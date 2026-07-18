// ============================================================
// frontend/src/hooks/admin/useAuditDirectories.ts
// ============================================================
import {
    useEffect,
    useRef,
    useState,
} from 'react'

import {
    getCurrentOrganization,
    getOrganizations,
} from '../../api/organizationApi'

import type {
    Organization,
} from '../../api/organizationApi'

import {
    getUsers,
} from '../../api/userApi'

import type {
    User,
} from '../../api/userApi'

import {
    getApiErrorMessage,
} from '../../api/http'

import {
    normalizePageResponse,
} from '../../utils/page'

import type {
    PageResponse,
} from '../../utils/page'

const DIRECTORY_PAGE_SIZE = 200

type UseAuditDirectoriesResult = {
    organizations: Organization[]
    users: User[]
    loading: boolean
    error: string
}

function useAuditDirectories(
    superAdmin: boolean,
): UseAuditDirectoriesResult {
    const [organizations, setOrganizations] =
        useState<Organization[]>([])

    const [users, setUsers] =
        useState<User[]>([])

    const [loading, setLoading] =
        useState(true)

    const [error, setError] =
        useState('')

    const requestSequenceRef =
        useRef(0)

    useEffect(() => {
        const sequence =
            ++requestSequenceRef.current

        async function loadDirectories(): Promise<void> {
            setLoading(true)
            setError('')

            try {
                const [
                    loadedOrganizations,
                    loadedUsers,
                ] = await Promise.all([
                    superAdmin
                        ? loadAllOrganizations()
                        : loadCurrentOrganization(),

                    loadAllUsers(),
                ])

                if (
                    sequence !==
                    requestSequenceRef.current
                ) {
                    return
                }

                setOrganizations(
                    loadedOrganizations,
                )
                setUsers(loadedUsers)
            } catch (loadError) {
                if (
                    sequence !==
                    requestSequenceRef.current
                ) {
                    return
                }

                setOrganizations([])
                setUsers([])

                setError(
                    getApiErrorMessage(
                        loadError,
                        'Не удалось загрузить пользователей и организации.',
                    ),
                )
            } finally {
                if (
                    sequence ===
                    requestSequenceRef.current
                ) {
                    setLoading(false)
                }
            }
        }

        void loadDirectories()

        return () => {
            requestSequenceRef.current += 1
        }
    }, [superAdmin])

    return {
        organizations,
        users,
        loading,
        error,
    }
}

async function loadCurrentOrganization(): Promise<Organization[]> {
    return [await getCurrentOrganization()]
}

async function loadAllOrganizations(): Promise<Organization[]> {
    return loadAllPages<Organization>((page) =>
        getOrganizations(
            page,
            DIRECTORY_PAGE_SIZE,
        ),
    )
}

async function loadAllUsers(): Promise<User[]> {
    return loadAllPages<User>((page) =>
        getUsers(
            page,
            DIRECTORY_PAGE_SIZE,
        ),
    )
}

async function loadAllPages<T>(
    loader: (page: number) => Promise<PageResponse<T>>,
): Promise<T[]> {
    const result: T[] = []

    let page = 0
    let totalPages = 1

    while (page < totalPages) {
        const response = await loader(page)
        const normalized =
            normalizePageResponse(response)

        result.push(...normalized.content)

        totalPages = normalized.totalPages
        page += 1
    }

    return result
}

export default useAuditDirectories

