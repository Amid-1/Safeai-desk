// frontend/src/utils/organizations.ts

import { getOrganizations } from '../api/organizationApi'
import type { Organization } from '../api/organizationApi'
import { normalizePageResponse } from './page'

const ORGANIZATION_PAGE_SIZE = 200

export async function loadAllOrganizations(): Promise<Organization[]> {
    const organizations: Organization[] = []

    let page = 0
    let totalPages = 1

    while (page < totalPages) {
        const response = await getOrganizations(
            page,
            ORGANIZATION_PAGE_SIZE
        )

        const normalized = normalizePageResponse(response)

        organizations.push(...normalized.content)

        totalPages = Math.max(normalized.totalPages, 1)
        page += 1
    }

    return organizations
}