import {
    useRef,
    useState,
} from 'react'
import {
    createOrganization,
    normalizeOrganizationName,
} from '../api/organizationApi'
import {
    getApiErrorMessage,
} from '../api/http'

type UseAdminOrganizationCreateOptions = {
    requestReloadFromFirstPage: () => void
    setSuccess: (message: string) => void
}

export function useAdminOrganizationCreate({
    requestReloadFromFirstPage,
    setSuccess,
}: UseAdminOrganizationCreateOptions) {
    const [name, setName] = useState('')
    const [creating, setCreating] =
        useState(false)
    const [createError, setCreateError] =
        useState('')

    const creatingRef = useRef(false)

    function changeName(value: string) {
        setName(value)
        if (createError) {
            setCreateError('')
        }
    }

    async function submitCreateOrganization() {
        if (creatingRef.current) {
            return
        }

        const normalizedName =
            normalizeOrganizationName(name)

        if (!normalizedName) {
            setCreateError(
                'Введите название организации.',
            )
            return
        }

        creatingRef.current = true
        setCreating(true)
        setCreateError('')
        setSuccess('')

        try {
            const created =
                await createOrganization({
                    name: normalizedName,
                })

            setName('')
            setSuccess(
                `Организация «${created.name}» создана.`,
            )
            requestReloadFromFirstPage()
        } catch (error) {
            setCreateError(
                getApiErrorMessage(
                    error,
                    'Не удалось создать организацию.',
                ),
            )
        } finally {
            creatingRef.current = false
            setCreating(false)
        }
    }

    return {
        name,
        setName: changeName,
        creating,
        createError,
        submitCreateOrganization,
    }
}
