import {
    useEffect,
    useRef,
    useState,
} from 'react'
import {
    disableOrganization,
    enableOrganization,
    getOrganizationDisableImpact,
    normalizeOrganizationConfirmation,
    normalizeOrganizationName,
    updateOrganizationName,
} from '../api/organizationApi'
import type {
    Organization,
} from '../api/organizationApi'
import {
    getApiErrorMessage,
} from '../api/http'
import {
    useAutoClearMessage,
} from '../hooks/useAutoClearMessage'
import {
    canMutateOrganization,
    getProtectionError,
    isOrganizationRequestAborted,
    isOrganizationVersionConflict,
} from './adminOrganizationsSupport'
import type {
    DisableOrganizationDialogState,
    PendingOrganizationAction,
} from './adminOrganizationsSupport'

const SUCCESS_MESSAGE_TIMEOUT_MS = 4_000

type UseAdminOrganizationMutationsOptions = {
    requestReloadFromFirstPage: () => void
}

export function useAdminOrganizationMutations({
    requestReloadFromFirstPage,
}: UseAdminOrganizationMutationsOptions) {
    const [mutationError, setMutationError] =
        useState('')
    const [renameError, setRenameError] =
        useState('')
    const [success, setSuccess] =
        useState('')

    const [pendingAction, setPendingAction] =
        useState<PendingOrganizationAction>(null)
    const [impactLoadingId, setImpactLoadingId] =
        useState<string | null>(null)

    const [renameOrganization, setRenameOrganization] =
        useState<Organization | null>(null)
    const [renameValue, setRenameValue] =
        useState('')
    const [enableOrganizationTarget, setEnableOrganizationTarget] =
        useState<Organization | null>(null)
    const [disableDialog, setDisableDialog] =
        useState<DisableOrganizationDialogState>(null)

    const impactSequenceRef = useRef(0)
    const impactControllerRef =
        useRef<AbortController | null>(null)
    const pendingActionRef =
        useRef<PendingOrganizationAction>(null)

    const hasPendingAction =
        pendingAction !== null

    useEffect(() => {
        pendingActionRef.current = pendingAction
    }, [pendingAction])

    useEffect(() => {
        return () => {
            impactControllerRef.current?.abort()
        }
    }, [])

    useAutoClearMessage(
        success,
        setSuccess,
        SUCCESS_MESSAGE_TIMEOUT_MS,
    )

    function openRenameModal(
        organization: Organization,
    ) {
        if (!canMutateOrganization(organization)) {
            setMutationError(
                getProtectionError(organization),
            )
            return
        }

        setRenameOrganization(organization)
        setRenameValue(organization.name)
        setRenameError('')
        setMutationError('')
        setSuccess('')
    }

    function changeRenameValue(value: string) {
        setRenameValue(value)
        if (renameError) {
            setRenameError('')
        }
    }

    function closeRenameModal() {
        if (pendingActionRef.current) {
            return
        }

        setRenameOrganization(null)
        setRenameValue('')
        setRenameError('')
    }

    async function submitRenameOrganization() {
        if (!renameOrganization) {
            return
        }

        const normalizedName =
            normalizeOrganizationName(
                renameValue,
            )

        if (!normalizedName) {
            setRenameError(
                'Введите новое название организации.',
            )
            return
        }

        if (
            normalizedName
            === renameOrganization.name
        ) {
            closeRenameModal()
            return
        }

        await runOrganizationAction(
            {
                organizationId:
                    renameOrganization.id,
                type: 'RENAME',
            },
            async () => {
                const updated =
                    await updateOrganizationName(
                        renameOrganization.id,
                        {
                            name: normalizedName,
                            expectedVersion:
                                renameOrganization.version,
                        },
                    )

                setRenameOrganization(null)
                setRenameValue('')
                setSuccess(
                    `Организация переименована в «${updated.name}».`,
                )

                // Rename can change backend sort order.
                requestReloadFromFirstPage()
            },
            setRenameError,
            'Не удалось переименовать организацию.',
        )
    }

    async function openDisableDialog(
        organization: Organization,
    ) {
        if (!canMutateOrganization(organization)) {
            setMutationError(
                getProtectionError(organization),
            )
            return
        }

        if (!organization.enabled) {
            return
        }

        const expectedVersion =
            organization.version
        const sequence =
            ++impactSequenceRef.current

        impactControllerRef.current?.abort()

        const controller =
            new AbortController()

        impactControllerRef.current = controller

        setImpactLoadingId(organization.id)
        setMutationError('')
        setSuccess('')

        try {
            const impact =
                await getOrganizationDisableImpact(
                    organization.id,
                    {
                        signal:
                            controller.signal,
                    },
                )

            if (
                sequence
                !== impactSequenceRef.current
            ) {
                return
            }

            if (
                impact.organizationVersion
                !== expectedVersion
            ) {
                setMutationError(
                    'Организация изменилась до подтверждения. '
                    + 'Список будет обновлён.',
                )
                requestReloadFromFirstPage()
                return
            }

            setDisableDialog({
                organization,
                impact,
                confirmationName: '',
            })
        } catch (error) {
            if (
                sequence
                === impactSequenceRef.current
                && !isOrganizationRequestAborted(
                    error,
                )
            ) {
                setMutationError(
                    getApiErrorMessage(
                        error,
                        'Не удалось оценить последствия отключения.',
                    ),
                )
            }
        } finally {
            if (
                sequence
                === impactSequenceRef.current
            ) {
                setImpactLoadingId(null)
            }
        }
    }

    function closeDisableDialog() {
        if (pendingActionRef.current) {
            return
        }

        setDisableDialog(null)
    }

    function setDisableConfirmationName(
        value: string,
    ) {
        if (mutationError) {
            setMutationError('')
        }

        setDisableDialog(
            (current) =>
                current
                    ? {
                        ...current,
                        confirmationName: value,
                    }
                    : current,
        )
    }

    async function confirmDisableOrganization() {
        if (!disableDialog) {
            return
        }

        const {
            organization,
            impact,
            confirmationName,
        } = disableDialog

        if (
            normalizeOrganizationConfirmation(
                confirmationName,
            )
            !== normalizeOrganizationConfirmation(
                organization.name,
            )
        ) {
            setMutationError(
                'Название организации не совпадает. '
                + 'Регистр, кавычки и лишние пробелы '
                + 'можно не повторять, но слова должны '
                + 'совпадать без опечаток.',
            )
            return
        }

        await runOrganizationAction(
            {
                organizationId:
                    organization.id,
                type: 'DISABLE',
            },
            async () => {
                const updated =
                    await disableOrganization(
                        organization.id,
                        {
                            expectedVersion:
                                impact.organizationVersion,
                            // Send the operator's real input. Backend must
                            // independently validate the confirmation value.
                            confirmationName,
                        },
                    )

                setDisableDialog(null)
                setSuccess(
                    `Для организации «${updated.name}» запущено отключение `
                    + 'и отзыв активных сессий.',
                )
                requestReloadFromFirstPage()
            },
            setMutationError,
            'Не удалось отключить организацию.',
        )
    }

    function openEnableDialog(
        organization: Organization,
    ) {
        if (!canMutateOrganization(organization)) {
            setMutationError(
                getProtectionError(organization),
            )
            return
        }

        if (organization.enabled) {
            return
        }

        setMutationError('')
        setSuccess('')
        setEnableOrganizationTarget(
            organization,
        )
    }

    function closeEnableDialog() {
        if (pendingActionRef.current) {
            return
        }

        setEnableOrganizationTarget(null)
    }

    async function confirmEnableOrganization() {
        if (!enableOrganizationTarget) {
            return
        }

        const organization =
            enableOrganizationTarget

        await runOrganizationAction(
            {
                organizationId:
                    organization.id,
                type: 'ENABLE',
            },
            async () => {
                const updated =
                    await enableOrganization(
                        organization.id,
                        {
                            expectedVersion:
                                organization.version,
                        },
                    )

                setEnableOrganizationTarget(null)
                setSuccess(
                    `Организация «${updated.name}» включена.`,
                )
                requestReloadFromFirstPage()
            },
            setMutationError,
            'Не удалось включить организацию.',
        )
    }

    async function runOrganizationAction(
        action: Exclude<
            PendingOrganizationAction,
            null
        >,
        operation: () => Promise<void>,
        setError: (message: string) => void,
        fallback: string,
    ) {
        if (pendingActionRef.current) {
            return
        }

        // Keep the ref in sync synchronously so two rapid user actions
        // cannot enter the mutation section before React commits state.
        pendingActionRef.current = action
        setPendingAction(action)
        setError('')
        setSuccess('')

        try {
            await operation()
        } catch (error) {
            if (
                isOrganizationVersionConflict(
                    error,
                )
            ) {
                setError(
                    'Организация была изменена другим администратором. '
                    + 'Загружены свежие данные; повторите решение.',
                )
                requestReloadFromFirstPage()
            } else {
                setError(
                    getApiErrorMessage(
                        error,
                        fallback,
                    ),
                )
            }
        } finally {
            pendingActionRef.current = null
            setPendingAction(null)
        }
    }

    return {
        mutationError,
        setMutationError,
        success,
        setSuccess,
        hasPendingAction,
        impactLoadingId,
        renameOrganization,
        renameValue,
        setRenameValue: changeRenameValue,
        renameError,
        openRenameModal,
        closeRenameModal,
        submitRenameOrganization,
        disableDialog,
        setDisableConfirmationName,
        openDisableDialog,
        closeDisableDialog,
        confirmDisableOrganization,
        enableOrganizationTarget,
        openEnableDialog,
        closeEnableDialog,
        confirmEnableOrganization,
    }
}
