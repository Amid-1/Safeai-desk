// ============================================================
// frontend/src/pages/AdminModelsPage.tsx
// ============================================================
import {
    useCallback,
    useEffect,
    useLayoutEffect,
    useMemo,
    useRef,
    useState,
} from 'react'
import {
    createModelCatalogVersion,
    createOrganizationModelPolicyVersion,
    getEffectiveModelCatalog,
    getModelCatalog,
    getModelRouteDecision,
    getOrganizationModelPolicy,
    getRuntimeModelStatus,
    importRuntimeModelCatalog,
    probeRuntimeModel,
    previewOrganizationModelPolicy,
} from '../api/modelApi'
import type {
    CreateModelCatalogVersionRequest,
    CreateOrganizationModelPolicyVersionRequest,
    ModelCatalogEntry,
    ModelRouteDecision,
    ModelPolicyPreview,
    OrganizationModelPolicy,
    RuntimeModelProbe,
    RuntimeModelStatus,
} from '../api/modelApi'
import { getApiErrorMessage } from '../api/http'
import {
    getOrganizationDetails,
    searchOrganizationDirectory,
} from '../api/organizationApi'
import type {
    OrganizationDirectoryItem,
} from '../api/organizationApi'
import { useAuth } from '../auth/useAuth'
import ResizableScrollRegion from '../components/ResizableScrollRegion'
import {
    ErrorState,
    LoadingState,
} from '../components/StateBlock'
import {
    CatalogTable,
    PolicyCard,
    RouteDecisionEvidence,
    RuntimeCard,
} from '../components/admin/models/ModelControlPlaneViews'
import { ModelCatalogHistoryModal } from '../components/admin/models/ModelCatalogHistoryModal'
import { ModelCatalogVersionModal } from '../components/admin/models/ModelCatalogVersionModal'
import { ModelPolicyModal } from '../components/admin/models/ModelPolicyModal'
import './AdminModelsPage.css'
import './AdminModelsPageVisualRefresh.css'

const UUID_PATTERN =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function connectionModeLabel(
    value: string,
) {
    switch (value) {
        case 'SINGLE_PROVIDER_STATIC':
            return 'Один фиксированный провайдер и модель'
        default:
            return 'Runtime сконфигурирован'
    }
}

type CatalogEmptyStateProps = {
    isSuperAdmin: boolean
    mutationPending: boolean
    onImportRuntime: () => void
    onCreateModel: () => void
}

function CatalogEmptyState({
    isSuperAdmin,
    mutationPending,
    onImportRuntime,
    onCreateModel,
}: CatalogEmptyStateProps) {
    return (
        <section
            className="models-empty-catalog"
            role="status"
            aria-live="polite"
        >
            <div
                className="models-empty-catalog__badge"
                aria-hidden="true"
            >
                <svg
                    viewBox="0 0 32 32"
                    focusable="false"
                >
                    <circle cx="7" cy="9" r="2" />
                    <circle cx="7" cy="16" r="2" />
                    <circle cx="7" cy="23" r="2" />
                    <path d="M12 9H25" />
                    <path d="M12 16H25" />
                    <path d="M12 23H25" />
                </svg>
            </div>

            <div className="models-empty-catalog__content">
                <span className="models-empty-catalog__eyebrow">
                    Каталог моделей
                </span>

                <h2>Каталог моделей пока пуст</h2>

                <p>
                    {isSuperAdmin
                        ? 'Добавьте текущий Runtime в каталог управления или создайте первую неизменяемую версию модели вручную. Сам каталог не подключает провайдера к серверу.'
                        : 'Каталог ещё не настроен администратором. После заполнения здесь появятся последняя и действующая версии, лимиты, стоимость и параметры управления.'}
                </p>

                <div className="models-empty-catalog__info">
                    <div>
                        <strong>Что будет показано здесь</strong>
                        <small>
                            Последняя и действующая версии моделей, статус каталога,
                            лимиты, возможности, стоимость и дата вступления
                            версии в силу.
                        </small>
                    </div>
                    <div>
                        <strong>Как это связано с Runtime</strong>
                        <small>
                            Каталог описывает сохранённую версию параметров модели.
                            Физический провайдер и модель настраиваются отдельно
                            в Runtime на сервере.
                        </small>
                    </div>
                </div>

                {isSuperAdmin && (
                    <div className="models-empty-catalog__actions">
                        <button
                            type="button"
                            disabled={mutationPending}
                            onClick={onImportRuntime}
                        >
                            Добавить Runtime в каталог
                        </button>

                        <button
                            type="button"
                            className="btn-primary"
                            disabled={mutationPending}
                            onClick={onCreateModel}
                        >
                            Создать модель
                        </button>
                    </div>
                )}
            </div>
        </section>
    )
}

function AdminModelsPage() {
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

    const [error, setError] =
        useState('')

    const [notice, setNotice] =
        useState('')

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

    const [
        organizationQuery,
        setOrganizationQuery,
    ] = useState('')

    const [
        organizationResults,
        setOrganizationResults,
    ] = useState<
        OrganizationDirectoryItem[]
    >([])

    const [
        organizationSearchPending,
        setOrganizationSearchPending,
    ] = useState(false)

    const [routeDecisionId, setRouteDecisionId] =
        useState('')

    const [routeDecision, setRouteDecision] =
        useState<ModelRouteDecision | null>(
            null,
        )

    const [routePending, setRoutePending] =
        useState(false)

    const [routeError, setRouteError] =
        useState('')

    const [runtimeProbe, setRuntimeProbe] =
        useState<RuntimeModelProbe | null>(
            null,
        )

    const [
        runtimeProbePending,
        setRuntimeProbePending,
    ] = useState(false)

    const heroRef =
        useRef<HTMLElement | null>(null)

    const [
        catalogMinUpperHeight,
        setCatalogMinUpperHeight,
    ] = useState(120)

    useLayoutEffect(() => {
        const hero = heroRef.current

        if (!hero) {
            return
        }

        const updateMinimumUpperHeight = () => {
            const parent = hero.parentElement
            const parentStyles = parent
                ? window.getComputedStyle(parent)
                : null
            const rawGap = parentStyles
                ? parentStyles.rowGap
                    || parentStyles.gap
                : ''
            const parsedGap =
                Number.parseFloat(rawGap)
            const gap =
                Number.isFinite(parsedGap)
                    ? parsedGap
                    : 12
            const heroHeight =
                hero.getBoundingClientRect().height

            setCatalogMinUpperHeight(
                Math.max(
                    96,
                    Math.ceil(
                        heroHeight + gap,
                    ),
                ),
            )
        }

        updateMinimumUpperHeight()

        if (
            typeof ResizeObserver
                === 'undefined'
        ) {
            window.addEventListener(
                'resize',
                updateMinimumUpperHeight,
            )

            return () => {
                window.removeEventListener(
                    'resize',
                    updateMinimumUpperHeight,
                )
            }
        }

        const observer =
            new ResizeObserver(
                updateMinimumUpperHeight,
            )

        observer.observe(hero)

        return () => {
            observer.disconnect()
        }
    }, [runtime, policy])

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
            const [latest, effective] =
                await Promise.allSettled([
                    getModelCatalog(),
                    getEffectiveModelCatalog(),
                ])

            if (latest.status === 'fulfilled') {
                setCatalog(latest.value)
            }
            if (effective.status === 'fulfilled') {
                setEffectiveCatalog(effective.value)
            }

            if (latest.status === 'rejected') {
                throw latest.reason
            }

            if (effective.status === 'rejected') {
                throw effective.reason
            }
        }, [])

    const handleImportRuntime =
        useCallback(async () => {
            setMutationPending(true)
            setError('')
            setNotice('')

            let imported: ModelCatalogEntry

            try {
                imported =
                    await importRuntimeModelCatalog()
            } catch (failure) {
                setError(
                    getApiErrorMessage(
                        failure,
                        'Не удалось добавить Runtime в каталог.',
                    ),
                )
                setMutationPending(false)
                return
            }

            try {
                await refreshCatalog()

                setNotice(
                    `Runtime добавлен или синхронизирован с каталогом: ${imported.modelKey}, версия ${imported.version}.`,
                )
            } catch {
                setNotice(
                    `Runtime сохранён в каталоге: ${imported.modelKey}, версия ${imported.version}. Обновить представление каталога не удалось — перезагрузите данные страницы. Повторно добавлять Runtime не нужно.`,
                )
            } finally {
                setMutationPending(false)
            }
        }, [refreshCatalog])

    const handleCreateCatalogVersion =
        useCallback(async (
            request: CreateModelCatalogVersionRequest,
        ) => {
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
                setMutationPending(false)
            }
        }, [refreshCatalog])

    const handleCreatePolicyVersion =
        useCallback(async (
            request: CreateOrganizationModelPolicyVersionRequest,
        ) => {
            setMutationPending(true)
            setError('')
            setNotice('')

            try {
                const created =
                    await createOrganizationModelPolicyVersion(
                        targetOrganizationId,
                        request,
                    )

                setPolicy(created)
                setPolicyModalOpen(false)

                setNotice(
                    `Правила организации сохранены. Текущая версия: ${created.version}.`,
                )
            } finally {
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

    const handleOrganizationSearch =
        useCallback(async () => {
            const query =
                organizationQuery.trim()

            if (!query) {
                setOrganizationResults([])
                return
            }

            setOrganizationSearchPending(true)
            setError('')

            try {
                const results =
                    await searchOrganizationDirectory(
                        query,
                        12,
                    )

                setOrganizationResults(results)
            } catch (failure) {
                setError(
                    getApiErrorMessage(
                        failure,
                        'Не удалось найти организацию.',
                    ),
                )
            } finally {
                setOrganizationSearchPending(false)
            }
        }, [organizationQuery])

    const handleRuntimeProbe =
        useCallback(async () => {
            if (!isSuperAdmin) {
                return
            }

            setRuntimeProbePending(true)
            setError('')

            try {
                const result =
                    await probeRuntimeModel()

                setRuntimeProbe(result)
            } catch (failure) {
                setError(
                    getApiErrorMessage(
                        failure,
                        'Не удалось проверить доступность runtime-модели.',
                    ),
                )
            } finally {
                setRuntimeProbePending(false)
            }
        }, [isSuperAdmin])

    const handleRouteLookup =
        useCallback(async () => {
            const decisionId =
                routeDecisionId.trim()

            setRouteDecision(null)
            setRouteError('')

            if (!UUID_PATTERN.test(decisionId)) {
                setRouteError(
                    'Введите корректный ID решения (UUID).',
                )
                return
            }

            setRoutePending(true)

            try {
                setRouteDecision(
                    await getModelRouteDecision(
                        decisionId,
                    ),
                )
            } catch (failure) {
                setRouteError(
                    getApiErrorMessage(
                        failure,
                        'Решение маршрутизации не найдено.',
                    ),
                )
            } finally {
                setRoutePending(false)
            }
        }, [routeDecisionId])

    if (!currentUser) {
        return null
    }

    if (loading) {
        return (
            <div className="page models-page">
                <LoadingState message="Загрузка настроек моделей..." />
            </div>
        )
    }

    if (
        error
        && (!runtime || !policy)
    ) {
        return (
            <div className="page models-page">
                <ErrorState message={error} />
            </div>
        )
    }

    if (!runtime || !policy) {
        return (
            <div className="page models-page">
                <ErrorState message="Сервер вернул неполные данные о настройках моделей." />
            </div>
        )
    }

    const upper = (
        <div className="models-page__upper-content">
            <header
                ref={heroRef}
                className="models-hero"
            >
                <div className="models-hero__content">
                    <span className="models-eyebrow">
                        УПРАВЛЕНИЕ МОДЕЛЯМИ
                    </span>

                    <h1>
                        Модели и маршрутизация
                    </h1>

                    <p>
                        Runtime показывает физически подключённую модель.
                        Каталог хранит неизменяемые версии её параметров.
                        Правила ограничивают использование для выбранной
                        организации.
                    </p>
                </div>

                <div className="models-runtime-badge">
                    <span>Runtime настроен на</span>
                    <strong>
                        {runtime.provider} · {runtime.model}
                    </strong>
                    <small>
                        {connectionModeLabel(
                            runtime.routingMode,
                        )}
                    </small>
                </div>
            </header>

            {error && (
                <ErrorState
                    message={error}
                    variant="inline"
                />
            )}

            {notice && (
                <div
                    className="models-notice"
                    role="status"
                >
                    {notice}
                </div>
            )}

            <section className="models-overview-grid">
                <RuntimeCard
                    runtime={runtime}
                    effectiveCatalog={effectiveCatalog}
                    probe={runtimeProbe}
                    probePending={runtimeProbePending}
                    canProbe={isSuperAdmin}
                    onProbe={() => {
                        void handleRuntimeProbe()
                    }}
                />

                <PolicyCard
                    policy={policy}
                    catalog={catalog}
                    runtime={runtime}
                    organizationId={targetOrganizationId}
                    organizationName={selectedOrganizationName}
                    isSuperAdmin={isSuperAdmin}
                    organizationQuery={organizationQuery}
                    organizationResults={organizationResults}
                    organizationSearchPending={
                        organizationSearchPending
                    }
                    onOrganizationQueryChange={
                        setOrganizationQuery
                    }
                    onOrganizationSearch={
                        handleOrganizationSearch
                    }
                    onOrganizationSelect={(
                        organization,
                    ) => {
                        setTargetOrganizationId(
                            organization.id,
                        )
                        setOrganizationQuery(
                            organization.name,
                        )
                        setSelectedOrganization({
                            id: organization.id,
                            name: organization.name,
                        })
                        setOrganizationResults([])
                    }}
                    onEdit={() => {
                        setPolicyModalOpen(true)
                    }}
                />
            </section>

            <section className="models-catalog-toolbar">
                <div className="models-catalog-toolbar__copy">
                    <h2>Каталог моделей</h2>
                    <p>
                        В таблице показана последняя версия каждой модели;
                        полная цепочка открывается через «История версий».
                        Каталог не подключает провайдера к серверу:
                        в маршрутизации участвует действующая версия,
                        определённая сервером и совместимая с Runtime.
                    </p>
                </div>

                {isSuperAdmin && (
                    <div className="models-control-row__actions">
                        <button
                            type="button"
                            disabled={mutationPending}
                            onClick={() => {
                                void handleImportRuntime()
                            }}
                        >
                            Добавить Runtime в каталог
                        </button>

                        <button
                            type="button"
                            className="btn-primary"
                            disabled={mutationPending}
                            onClick={() => {
                                setCatalogModal({
                                    base: null,
                                })
                            }}
                        >
                            Создать модель
                        </button>
                    </div>
                )}
            </section>

            <details className="models-evidence-panel">
                <summary>
                    <span>
                        Диагностика маршрутизации
                    </span>
                    <small>
                        Для разбора конкретного запроса
                    </small>
                </summary>

                <p className="models-evidence-panel__hint">
                    По ID решения можно увидеть,
                    почему модель была выбрана или
                    отклонена. ID можно взять из
                    аудита или сообщения об ошибке.
                </p>

                <form
                    className="models-evidence-search"
                    onSubmit={(event) => {
                        event.preventDefault()
                        void handleRouteLookup()
                    }}
                >
                    <label>
                        ID решения
                        <input
                            type="text"
                            value={routeDecisionId}
                            placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                            autoComplete="off"
                            onChange={(event) => {
                                setRouteDecisionId(
                                    event.target.value,
                                )
                            }}
                        />
                    </label>

                    <button
                        type="submit"
                        disabled={routePending}
                    >
                        {routePending
                            ? 'Проверяем...'
                            : 'Проверить'}
                    </button>
                </form>

                {routeError && (
                    <ErrorState
                        message={routeError}
                        variant="inline"
                    />
                )}

                {routeDecision && (
                    <RouteDecisionEvidence
                        decision={routeDecision}
                    />
                )}
            </details>
        </div>
    )

    const footer = (
        <div className="pagination pagination--single models-catalog-footer">
            <div className="pagination__summary">
                <strong>
                    Сводка
                </strong>
                <span>
                    Моделей: {catalog.length}
                    {' · '}
                    Действующих версий: {effectiveCatalog.length}
                    {' · '}
                    Runtime: {runtime.provider}/{runtime.model}
                    {' · '}
                    Правила:{' '}
                    {policy.configured
                        ? `версия ${policy.version}`
                        : 'не настроены'}
                </span>
            </div>
        </div>
    )

    return (
        <div className="page models-page">
            <ResizableScrollRegion
                upper={upper}
                footer={footer}
                storageKey="safeai:models-catalog-height:v4"
                label="каталог моделей"
                upperClassName="models-page__upper"
                lowerClassName="models-page__lower"
                viewportClassName="models-catalog-scroll"
                footerClassName="models-page__footer"
                defaultHeight={180}
                minHeight={64}
                maxHeight={980}
                minUpperHeight={catalogMinUpperHeight}
            >
                {catalog.length === 0 ? (
                    <CatalogEmptyState
                        isSuperAdmin={isSuperAdmin}
                        mutationPending={mutationPending}
                        onImportRuntime={() => {
                            void handleImportRuntime()
                        }}
                        onCreateModel={() => {
                            setCatalogModal({
                                base: null,
                            })
                        }}
                    />
                ) : (
                    <CatalogTable
                        entries={catalog}
                        effectiveEntries={effectiveCatalog}
                        runtime={runtime}
                        canEdit={isSuperAdmin}
                        onCreateVersion={(entry) => {
                            setCatalogModal({
                                base: entry,
                            })
                        }}
                        onOpenHistory={(entry) => {
                            setHistoryModalEntry(entry)
                        }}
                    />
                )}
            </ResizableScrollRegion>

            {historyModalEntry && (
                <ModelCatalogHistoryModal
                    modelKey={historyModalEntry.modelKey}
                    displayName={historyModalEntry.displayName}
                    runtime={runtime}
                    onClose={() => {
                        setHistoryModalEntry(null)
                    }}
                />
            )}

            {catalogModal && (
                <ModelCatalogVersionModal
                    base={catalogModal.base}
                    runtime={runtime}
                    catalogByKey={catalogByKey}
                    pending={mutationPending}
                    onClose={() => {
                        setCatalogModal(null)
                    }}
                    onSubmit={
                        handleCreateCatalogVersion
                    }
                />
            )}

            {policyModalOpen && (
                <ModelPolicyModal
                    policy={policy}
                    catalog={catalog}
                    effectiveCatalog={effectiveCatalog}
                    runtime={runtime}
                    organizationId={targetOrganizationId}
                    organizationName={selectedOrganizationName}
                    pending={mutationPending}
                    onClose={() => {
                        setPolicyModalOpen(false)
                    }}
                    onPreview={
                        handlePreviewPolicy
                    }
                    onSubmit={
                        handleCreatePolicyVersion
                    }
                />
            )}
        </div>
    )
}

export default AdminModelsPage

