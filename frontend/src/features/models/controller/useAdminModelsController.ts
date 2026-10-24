// frontend/src/features/models/controller/useAdminModelsController.ts
// ============================================================
// frontend/src/features/models/controller/useAdminModelsController.ts
// ============================================================
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import {
    createModelCatalogVersion,
    createOrganizationModelPolicyVersion,
    getEffectiveModelCatalog,
    getModelCatalog,
    getOrganizationModelPolicy,
    getRuntimeModelStatus,
    importRuntimeModelCatalog,
    previewOrganizationModelPolicy,
} from '../../../entities/model/api/modelApi'
import type {
    CreateModelCatalogVersionRequest,
    CreateOrganizationModelPolicyVersionRequest,
    ModelCatalogEntry,
    ModelPolicyPreview,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../../entities/model/api/modelApi'
import { getApiErrorMessage } from '../../../shared/api/http'
import { useAdminModelsOrganizationSearch } from './useAdminModelsOrganizationSearch'
import { useAdminModelsDiagnostics } from './useAdminModelsDiagnostics'
import { getOrganizationDetails } from '../../../entities/organization/api/organizationApi'
import { useAuth } from '../../auth/state/useAuth'

export function useAdminModelsController() {
    const {
        currentUser,
    } = useAuth()

    const isSuperAdmin =
        currentUser?.roles.includes(
            'SUPER_ADMIN',
        ) ?? false

    const [runtime, setRuntime] =
        useState<RuntimeModelStatus | null>(
            null,
        )

    const [catalog, setCatalog] =
        useState<ModelCatalogEntry[]>([])

    const [effectiveCatalog, setEffectiveCatalog] =
        useState<ModelCatalogEntry[]>([])

    const [policy, setPolicy] =
        useState<OrganizationModelPolicy | null>(
            null,
        )

    const [
        targetOrganizationId,
        setTargetOrganizationId,
    ] = useState(
        currentUser?.organizationId ?? '',
    )

    const [
        selectedOrganization,
        setSelectedOrganization,
    ] = useState<{
        id: string
        name: string
    } | null>(null)

    const selectedOrganizationName =
        selectedOrganization !== null
        && selectedOrganization.id === targetOrganizationId
            ? selectedOrganization.name
            : null

    const [loading, setLoading] =
        useState(true)
    // Организация, для которой последний раз загружены согласованные данные.
    const [loadedOrganizationId, setLoadedOrganizationId] = useState<string | null>(null)

    const [error, setError] =
        useState('')

    const [notice, setNotice] =
        useState('')

    const [importError, setImportError] = useState('')

    const [catalogPermissionNotice, setCatalogPermissionNotice] =
        useState(false)

    const [catalogModal, setCatalogModal] =
        useState<{
            base: ModelCatalogEntry | null
        } | null>(null)

    const [historyModalEntry, setHistoryModalEntry] =
        useState<ModelCatalogEntry | null>(null)

    const [
        policyModalOpen,
        setPolicyModalOpen,
    ] = useState(false)

    const [mutationPending, setMutationPending] =
        useState(false)

    // Блокировка действует синхронно, до следующего рендера React.
    const mutationInFlightRef = useRef(false)
    const selectedOrganizationIdRef = useRef(targetOrganizationId)
    useLayoutEffect(() => {
        selectedOrganizationIdRef.current = targetOrganizationId
    }, [targetOrganizationId])

    const {
        organizationQuery, setOrganizationQuery, handleOrganizationQueryChange,
        cancelOrganizationSearch, organizationResults, setOrganizationResults,
        organizationSearchPending, organizationSearchStatus, organizationSearchError, handleOrganizationSearch,
    } = useAdminModelsOrganizationSearch(isSuperAdmin)

    const {
        routeDecisionId, handleRouteDecisionIdChange, routeDecision,
        routePending, routeError, runtimeProbe, runtimeProbePending,
        handleRuntimeProbe, handleRouteLookup,
    } = useAdminModelsDiagnostics(isSuperAdmin, setError)


    useEffect(() => {
        if (!currentUser) {
            return
        }

        setTargetOrganizationId(
            currentUser.organizationId,
        )
        setSelectedOrganization(null)
    }, [currentUser])

    /*
     * Название tenant — presentation metadata. Его отдельная загрузка
     * не должна делать весь Model Control Plane недоступным при сбое
     * directory/details endpoint. UUID остаётся authoritative fallback.
     */
    useEffect(() => {
        if (
            !currentUser
            || !targetOrganizationId
        ) {
            return
        }

        const controller =
            new AbortController()

        void getOrganizationDetails(
            targetOrganizationId,
            {
                signal: controller.signal,
            },
        )
            .then((organization) => {
                if (controller.signal.aborted) {
                    return
                }

                setSelectedOrganization({
                    id: organization.id,
                    name: organization.name,
                })
            })
            .catch(() => {
                /*
                 * Не повышаем presentation failure до page-level error.
                 * Policy/runtime/catalog продолжают работать по UUID.
                 */
            })

        return () => {
            controller.abort()
        }
    }, [
        currentUser,
        targetOrganizationId,
    ])

    useEffect(() => {
        if (!currentUser) {
            return
        }

        const controller =
            new AbortController()

        setLoading(true)
        setError('')

        void Promise.all([
            getRuntimeModelStatus(
                controller.signal,
            ),
            getModelCatalog({
                signal: controller.signal,
            }),
            getEffectiveModelCatalog({
                signal: controller.signal,
            }),
            getOrganizationModelPolicy(
                targetOrganizationId
                    || currentUser.organizationId,
                {
                    signal: controller.signal,
                },
            ),
        ])
            .then(([
                runtimeResponse,
                catalogResponse,
                effectiveCatalogResponse,
                policyResponse,
            ]) => {
                if (controller.signal.aborted) {
                    return
                }

                setRuntime(runtimeResponse)
                setCatalog(catalogResponse)
                setEffectiveCatalog(
                    effectiveCatalogResponse,
                )
                setPolicy(policyResponse)
                setLoadedOrganizationId(targetOrganizationId || currentUser.organizationId)
            })
            .catch((failure) => {
                if (!controller.signal.aborted) {
                    setError(
                        getApiErrorMessage(
                            failure,
                            'Не удалось загрузить настройки моделей.',
                        ),
                    )
                }
            })
            .finally(() => {
                if (!controller.signal.aborted) {
                    setLoading(false)
                }
            })

        return () => {
            controller.abort()
        }
    }, [
        currentUser,
        targetOrganizationId,
    ])

    const catalogByKey =
        useMemo(() =>
            new Map(
                catalog.map(
                    (entry) => [
                        entry.modelKey,
                        entry,
                    ],
                ),
            ), [catalog])

    const refreshCatalog =
        useCallback(async () => {
            // Оба среза должны быть успешно прочитаны. Нельзя отображать
            // новую latest-версию вместе с устаревшей effective-версией.
            const [latest, effective] = await Promise.all([
                getModelCatalog(),
                getEffectiveModelCatalog(),
            ])
            setCatalog(latest)
            setEffectiveCatalog(effective)
        }, [])

    const handleImportRuntime =
        useCallback(async () => {
            if (mutationInFlightRef.current || !isSuperAdmin) {
                return
            }
            mutationInFlightRef.current = true
            setMutationPending(true)
            setImportError('')
            setNotice('')

            let imported: ModelCatalogEntry

            try {
                imported =
                    await importRuntimeModelCatalog()
            } catch (failure) {
                setImportError(getApiErrorMessage(
                    failure,
                    'Не удалось добавить подключённую модель в каталог.',
                ))
                mutationInFlightRef.current = false
                setMutationPending(false)
                return
            }

            try {
                await refreshCatalog()

                setNotice(
                    `Подключённая модель добавлена или синхронизирована с каталогом: ${imported.modelKey}, версия ${imported.version}.`,
                )
            } catch {
                setNotice(
                    `Подключённая модель сохранена в каталоге: ${imported.modelKey}, версия ${imported.version}. Не удалось обновить список — перезагрузите страницу. Повторно добавлять модель не нужно.`,
                )
            } finally {
                mutationInFlightRef.current = false
                setMutationPending(false)
            }
        }, [refreshCatalog, isSuperAdmin])

    const handleCreateCatalogVersion =
        useCallback(async (
            request: CreateModelCatalogVersionRequest,
        ) => {
            if (!isSuperAdmin) {
                throw new Error('Создавать версии каталога может только SUPER_ADMIN.')
            }
            if (mutationInFlightRef.current) {
                throw new Error('Дождитесь завершения текущего изменения каталога.')
            }
            mutationInFlightRef.current = true
            setMutationPending(true)
            setError('')
            setNotice('')

            try {
                const created =
                    await createModelCatalogVersion(
                        request,
                    )

                // POST уже committed: modal больше не должен предлагать обычный retry.
                setCatalogModal(null)

                try {
                    await refreshCatalog()
                    setNotice(
                        `Сохранена модель ${created.modelKey}, версия ${created.version}.`,
                    )
                } catch {
                    setNotice(
                        `Модель ${created.modelKey}, версия ${created.version}, сохранена успешно, но обновить представление каталога не удалось. Перезагрузите данные страницы; повторно сохранять версию не нужно.`,
                    )
                }
            } finally {
                mutationInFlightRef.current = false
                setMutationPending(false)
            }
        }, [refreshCatalog, isSuperAdmin])

    const handleCreatePolicyVersion =
        useCallback(async (
            request: CreateOrganizationModelPolicyVersionRequest,
        ) => {
            if (mutationInFlightRef.current) {
                throw new Error('Дождитесь завершения текущего изменения.')
            }
            mutationInFlightRef.current = true
            setMutationPending(true)
            setError('')
            setNotice('')
            const submittingOrganizationId = targetOrganizationId

            try {
                const created =
                    await createOrganizationModelPolicyVersion(
                        targetOrganizationId,
                        request,
                    )

                // POST мог уже зафиксироваться: при смене организации
                // нельзя показывать её старую политику в новой области.
                setPolicyModalOpen(false)
                if (created.organizationId !== submittingOrganizationId) {
                    setNotice(
                        'Ответ сервера относится к другой организации. ' +
                        'Не повторяйте сохранение; обновите страницу и проверьте версию.',
                    )
                    return
                }
                if (selectedOrganizationIdRef.current !== submittingOrganizationId) {
                    return
                }
                setPolicy(created)
                setNotice(
                    `Правила организации сохранены. Текущая версия: ${created.version}.`,
                )
            } finally {
                mutationInFlightRef.current = false
                setMutationPending(false)
            }
        }, [targetOrganizationId])

    const handlePreviewPolicy =
        useCallback(async (
            request: CreateOrganizationModelPolicyVersionRequest,
        ): Promise<ModelPolicyPreview> =>
            previewOrganizationModelPolicy(
                targetOrganizationId,
                request,
            ), [targetOrganizationId])

    return {
        currentUser,
        isSuperAdmin,
        runtime,
        catalog,
        effectiveCatalog,
        policy,
        targetOrganizationId,
        setTargetOrganizationId,
        selectedOrganizationName,
        setSelectedOrganization,
        loading,
        loadedOrganizationId,
        error,
        notice,
        importError,
        catalogPermissionNotice,
        setCatalogPermissionNotice,
        catalogModal,
        setCatalogModal,
        historyModalEntry,
        setHistoryModalEntry,
        policyModalOpen,
        setPolicyModalOpen,
        mutationPending,
        organizationQuery,
        setOrganizationQuery,
        handleOrganizationQueryChange,
        cancelOrganizationSearch,
        organizationResults,
        setOrganizationResults,
        organizationSearchPending,
        organizationSearchStatus,
        organizationSearchError,
        routeDecisionId,
        handleRouteDecisionIdChange,
        routeDecision,
        routePending,
        routeError,
        runtimeProbe,
        runtimeProbePending,
        catalogByKey,
        handleImportRuntime,
        handleCreateCatalogVersion,
        handleCreatePolicyVersion,
        handlePreviewPolicy,
        handleOrganizationSearch,
        handleRuntimeProbe,
        handleRouteLookup,
    }
}

export type AdminModelsController = ReturnType<typeof useAdminModelsController>
