import {
    ApiError,
} from '../api/http'
import {
    getOrganizations,
} from '../api/organizationApi'
import type {
    Organization,
} from '../api/organizationApi'
import {
    normalizePageResponse,
} from './page'

const ORGANIZATION_PAGE_SIZE = 200
const DEFAULT_MAX_PAGES = 25
const DEFAULT_MAX_ITEMS = 5_000

type LoadAllOrganizationsOptions = {
    signal?: AbortSignal
    maxPages?: number
    maxItems?: number
}

/**
 * Legacy compatibility helper.
 *
 * Новые dropdown/autocomplete должны использовать
 * searchOrganizationDirectory(), а не полную выгрузку.
 */
export async function loadAllOrganizations(
    options: LoadAllOrganizationsOptions = {},
): Promise<Organization[]> {
    const maxPages = normalizePositiveLimit(
        options.maxPages,
        DEFAULT_MAX_PAGES,
    )
    const maxItems = normalizePositiveLimit(
        options.maxItems,
        DEFAULT_MAX_ITEMS,
    )

    const organizations: Organization[] = []
    const seenIds = new Set<string>()

    let page = 0
    let totalPages = 1

    while (page < totalPages) {
        if (options.signal?.aborted) {
            throw new ApiError(
                'Загрузка каталога отменена',
                {
                    status: 0,
                    error: 'REQUEST_ABORTED',
                    message:
                        'Загрузка каталога отменена',
                },
                0,
            )
        }

        if (page >= maxPages) {
            throw directoryLimitError(
                'Превышен лимит страниц каталога организаций',
            )
        }

        const response = await getOrganizations(
            page,
            ORGANIZATION_PAGE_SIZE,
            {
                signal: options.signal,
            },
        )

        const normalized =
            normalizePageResponse(response)

        for (
            const organization
            of normalized.content
        ) {
            if (!seenIds.has(organization.id)) {
                seenIds.add(organization.id)
                organizations.push(organization)
            }

            if (
                organizations.length > maxItems
            ) {
                throw directoryLimitError(
                    'Превышен лимит элементов каталога организаций',
                )
            }
        }

        totalPages = Math.max(
            normalized.totalPages,
            1,
        )
        page += 1
    }

    return organizations
}

function normalizePositiveLimit(
    value: number | undefined,
    fallback: number,
): number {
    if (
        value === undefined
        || !Number.isFinite(value)
        || value < 1
    ) {
        return fallback
    }

    return Math.trunc(value)
}

function directoryLimitError(
    message: string,
): ApiError {
    return new ApiError(
        message,
        {
            status: 0,
            error:
                'DIRECTORY_LIMIT_EXCEEDED',
            message,
        },
        0,
    )
}
