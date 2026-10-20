// ============================================================
// frontend/src/components/admin/models/ModelPolicyModal.tsx
// ============================================================

import {
    useEffect,
    useId,
    useMemo,
    useRef,
    useState,
} from 'react'
import type {
    BudgetEnforcement,
    CreateOrganizationModelPolicyVersionRequest,
    ModelCatalogEntry,
    ModelPolicyPreview,
    ModelRouteReason,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../../api/modelApi'
import {
    BUDGET_ENFORCEMENTS,
} from '../../../api/modelApi'
import Modal from '../../Modal'
import type {
    ModalResizeOptions,
} from '../../Modal'
import {
    getApiErrorMessage,
} from '../../../api/http'
import {
    ErrorState,
} from '../../StateBlock'
import {
    DecimalInput,
} from './ModelFormControls'
import {
    DefaultModelSelector,
    ModelKeySelector,
} from './ModelPolicyModelSelectors'
import type {
    ModelKeySelectorInteractionState,
} from './ModelPolicyModelSelectors'
import {
    buildPolicyRequest,
    createPolicyDraft,
} from './modelControlPlaneSupport'
import type {
    PolicyDraft,
} from './modelControlPlaneSupport'
import './ModelControlPlaneModalShell.css'
import './ModelPolicyModal.css'
import './ModelPolicyProductionEnhancements.css'

const POLICY_MODAL_RESIZE:
    ModalResizeOptions = {
        initialWidth: 1480,
        initialHeight: 900,

        minWidth: 420,
        minHeight: 440,

        // Never shrink policy text; narrow layouts reflow and body scrolls.
        scaleContent: false,
        maximizable: true,
    }

type ModelPolicyModalProps = {
    policy:
        OrganizationModelPolicy

    catalog:
        ModelCatalogEntry[]

    effectiveCatalog:
        ModelCatalogEntry[]

    runtime:
        RuntimeModelStatus

    organizationId:
        string

    organizationName:
        string | null

    pending:
        boolean

    onClose:
        () => void

    onPreview: (
        request:
            CreateOrganizationModelPolicyVersionRequest,
    ) => Promise<ModelPolicyPreview>

    onSubmit: (
        request:
            CreateOrganizationModelPolicyVersionRequest,
    ) => Promise<void>
}

function InfoHint({
    text,
}: {
    text: string
}) {
    return (
        <span
            className="models-help"
            tabIndex={0}
            aria-label={text}
            data-tip={text}
        >
            ?
        </span>
    )
}

function budgetEnforcementLabel(
    value:
        BudgetEnforcement,
) {
    switch (
        value
    ) {
        case 'SOFT':
            return 'Мягкий — предупреждать'

        case 'HARD':
            return 'Жёсткий — блокировать'
    }
}


function previewReasonLabel(
    reason: ModelRouteReason,
): string {
    const labels: Partial<Record<ModelRouteReason, string>> = {
        REQUESTED_MODEL: 'Модель проходит проверку',
        POLICY_DEFAULT: 'Модель по умолчанию проходит проверку',
        RUNTIME_ONLY_MATCH: 'Runtime однозначно сопоставлен',
        LEGACY_RUNTIME_FALLBACK: 'Исторический legacy fallback',
        MODEL_NOT_ALLOWED: 'Модель не входит в allowlist',
        MODEL_DENIED: 'Модель находится в denylist',
        MODEL_NOT_FOUND: 'Действующая модель каталога не найдена',
        AMBIGUOUS_RUNTIME_MAPPING: 'Runtime неоднозначно сопоставлен с каталогом',
        MODEL_DISABLED: 'Модель отключена или выведена из эксплуатации',
        RUNTIME_MISMATCH: 'Модель не совпадает с текущим Runtime',
        CAPABILITY_UNSUPPORTED: 'Требуемая возможность не поддерживается',
        INPUT_LIMIT_EXCEEDED: 'Превышен входной лимит',
        OUTPUT_LIMIT_EXCEEDED: 'Превышен выходной лимит',
        PRICING_INCOMPLETE: 'Недостаточно данных о стоимости',
        TRAINING_POLICY_UNSATISFIED: 'Запрет обучения не подтверждён: требуется «Не используются» (NOT_USED) или договорный запрет (CONTRACTUAL_NO_TRAINING). Статус «Не заявлено» (NOT_DECLARED) не подходит',
        RETENTION_POLICY_UNSATISFIED: 'Не выполнено требование по хранению данных',
        REQUEST_COST_LIMIT_EXCEEDED: 'Превышен лимит стоимости запроса',
        MONTHLY_BUDGET_EXCEEDED: 'Превышен месячный бюджет',
        MONTHLY_BUDGET_UNVERIFIABLE: 'Месячный бюджет нельзя надёжно проверить',
    }

    return labels[reason] ?? reason
}

export function ModelPolicyModal({
    policy,
    catalog,
    effectiveCatalog,
    runtime,
    organizationId,
    organizationName,
    pending,
    onClose,
    onPreview,
    onSubmit,
}: ModelPolicyModalProps) {
    const formId =
        useId()

    const [
        draft,
        setDraft,
    ] =
        useState<PolicyDraft>(
            () =>
                createPolicyDraft(
                    policy,
                ),
        )

    const [initialDraftFingerprint] =
        useState(() =>
            JSON.stringify(
                createPolicyDraft(policy),
            ),
        )

    const [
        formError,
        setFormError,
    ] =
        useState(
            '',
        )

    const [
        policyPreview,
        setPolicyPreview,
    ] = useState<ModelPolicyPreview | null>(null)

    const [
        previewFingerprint,
        setPreviewFingerprint,
    ] = useState('')

    const [
        previewPending,
        setPreviewPending,
    ] = useState(false)

    const [
        previewError,
        setPreviewError,
    ] = useState('')

    const [
        lockoutAcknowledged,
        setLockoutAcknowledged,
    ] = useState(false)

    const [
        allowSelectorState,
        setAllowSelectorState,
    ] =
        useState<ModelKeySelectorInteractionState>({
            hasPendingInput:
                false,
            hasError:
                false,
        })

    const [
        denySelectorState,
        setDenySelectorState,
    ] =
        useState<ModelKeySelectorInteractionState>({
            hasPendingInput:
                false,
            hasError:
                false,
        })

    const accessControlRef =
        useRef<HTMLInputElement | null>(
            null,
        )

    const limitsControlRef =
        useRef<HTMLInputElement | null>(
            null,
        )

    const budgetControlRef =
        useRef<HTMLSelectElement | null>(
            null,
        )

    const dataControlRef =
        useRef<HTMLInputElement | null>(
            null,
        )

    const [activeSection, setActiveSection] =
        useState<'access' | 'limits' | 'budget' | 'data' | null>(null)

    // Explain catalog metadata only; routing and lockout decisions remain server-owned.
    const undeclaredTrainingModels = effectiveCatalog.filter((entry) =>
        entry.trainingUseStatus === 'NOT_DECLARED'
        && (entry.lifecycle === 'ACTIVE'
            || entry.lifecycle === 'DEPRECATED'
            || entry.modelKey === draft.defaultModelKey.trim().toLowerCase()),
    )

    const hasExecutableRuntimeCatalogEntry =
        useMemo(
            () =>
                effectiveCatalog.some(
                    (
                        entry,
                    ) =>
                        (
                            entry.lifecycle
                                === 'ACTIVE'
                            || entry.lifecycle
                                === 'DEPRECATED'
                        )
                        && entry.provider
                            === runtime.provider
                        && entry.providerModelId
                            === runtime.model,
                ),
            [
                effectiveCatalog,
                runtime.model,
                runtime.provider,
            ],
        )

    const activationWarning =
        draft.enabled
        && !hasExecutableRuntimeCatalogEntry


    const draftFingerprint = useMemo(
        () => JSON.stringify(draft),
        [draft],
    )

    const currentPreview =
        previewFingerprint === draftFingerprint
            ? policyPreview
            : null

    const serverTrainingRejections =
        currentPreview?.models.filter(
            (item) =>
                item.outcome === 'DENIED'
                && item.reason === 'TRAINING_POLICY_UNSATISFIED',
        ) ?? []

    const serverConfirmedTrainingLockout =
        draft.enabled
        && draft.requireNoTraining
        && currentPreview !== null
        && currentPreview.wouldLockOutOrganization
        && currentPreview.executableModelCount === 0
        && (
            currentPreview.automaticReason
                === 'TRAINING_POLICY_UNSATISFIED'
            || serverTrainingRejections.length > 0
        )

    const serverTrainingRejectedModelKeys =
        serverTrainingRejections.length > 0
            ? Array.from(
                new Set(
                    serverTrainingRejections.map(
                        (item) => item.modelKey,
                    ),
                ),
            )
            : currentPreview?.automaticReason
                === 'TRAINING_POLICY_UNSATISFIED'
                && currentPreview.automaticModelKey
                ? [currentPreview.automaticModelKey]
                : undeclaredTrainingModels.map(
                    (entry) => entry.modelKey,
                )

    useEffect(() => {
        setPreviewError('')
        setLockoutAcknowledged(false)
    }, [draftFingerprint])

    const isDirty =
        draftFingerprint !== initialDraftFingerprint

    const requestClose = () => {
        if (pending || previewPending) {
            return
        }

        if (
            isDirty
            && !window.confirm(
                'Отменить несохранённые изменения правил?',
            )
        ) {
            return
        }

        onClose()
    }

    const focusShortcut = (
        section: NonNullable<typeof activeSection>,
        target:
            HTMLElement | null,
    ) => {
        if (
            !target
        ) {
            return
        }

        setActiveSection(section)

        target.focus({
            preventScroll:
                true,
        })

        const block = target.closest<HTMLElement>('[data-policy-block]') ?? target
        if (typeof block.scrollIntoView === 'function') {
            block.scrollIntoView({
                behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
                    ? 'instant'
                    : 'smooth',
                block:
                    'center',
                inline:
                    'nearest',
            })
        }
    }

    const buildCurrentRequest =
        (): CreateOrganizationModelPolicyVersionRequest | null => {
            setFormError('')

            if (
                allowSelectorState.hasError
                || denySelectorState.hasError
            ) {
                setFormError(
                    'Исправьте ошибки в списках моделей перед сохранением.',
                )
                return null
            }

            if (
                allowSelectorState.hasPendingInput
                || denySelectorState.hasPendingInput
            ) {
                setFormError(
                    'Завершите добавление модели: выберите её из списка, нажмите Enter для ручного ключа или очистите строку поиска.',
                )
                return null
            }

            try {
                return buildPolicyRequest(
                    draft,
                    policy.version,
                )
            } catch (failure) {
                setFormError(
                    failure instanceof Error
                        ? failure.message
                        : 'Проверьте заполнение правил.',
                )
                return null
            }
        }

    const handlePreview = async () => {
        const request = buildCurrentRequest()
        if (!request) {
            return
        }

        const fingerprint = draftFingerprint
        setPreviewPending(true)
        setPreviewError('')
        setLockoutAcknowledged(false)

        try {
            const result = await onPreview(request)
            setPolicyPreview(result)
            setPreviewFingerprint(fingerprint)
        } catch (failure) {
            setPolicyPreview(null)
            setPreviewFingerprint('')
            setPreviewError(
                failure instanceof Error
                    ? getApiErrorMessage(failure, failure.message)
                    : 'Не удалось проверить правила.',
            )
        } finally {
            setPreviewPending(false)
        }
    }

    const handleSubmit = async () => {
        const request = buildCurrentRequest()
        if (!request) {
            return
        }

        if (draft.enabled && currentPreview === null) {
            setFormError(
                'Перед сохранением включённых правил выполните «Проверить правила». Проверка должна соответствовать текущему черновику.',
            )
            return
        }

        if (
            draft.enabled
            && currentPreview?.wouldLockOutOrganization
            && !lockoutAcknowledged
        ) {
            setFormError(
                'Проверка показала, что правила заблокируют все исполняемые модели. Подтвердите осознанную блокировку перед сохранением.',
            )
            return
        }

        try {
            await onSubmit(request)
        } catch (failure) {
            setFormError(
                failure instanceof Error
                    ? getApiErrorMessage(
                        failure,
                        failure.message,
                    )
                    : 'Не удалось сохранить правила.',
            )
        }
    }

    const versionMessage =
        policy.configured
            ? (
                `Сейчас действует версия ${policy.version}. `
                + `После сохранения появится версия ${policy.version + 1}; `
                + 'предыдущая останется неизменной.'
            )
            : (
                'Это первая настройка правил. '
                + 'После сохранения появится версия 1.'
            )

    const rulesStatusLabel =
        draft.enabled
            ? 'Правила включены'
            : 'Правила выключены'

    const rulesStatusHint =
        draft.enabled
            ? (
                'Ограничения и лимиты применяются '
                + 'к запросам этой организации.'
            )
            : policy.configured
                ? (
                    'Сохранённые правила существуют, '
                    + 'но сейчас не ограничивают маршрутизацию.'
                )
                : (
                    'Первая настройка не включится, '
                    + 'пока администратор не активирует её явно.'
                )

    const modalFooter = (
        <div className="models-policy-modal__footer">
            {isDirty && (
                <span className="models-policy-modal__dirty-notice" role="status">
                    Есть несохраненные изменения
                </span>
            )}
            <button
                type="button"
                disabled={
                    pending
                    || previewPending
                }
                onClick={() => {
                    void handlePreview()
                }}
            >
                {previewPending
                    ? 'Проверяем...'
                    : 'Проверить правила'}
            </button>

            <button
                type="submit"
                form={
                    formId
                }
                className="btn-primary"
                disabled={
                    pending
                    || previewPending
                }
            >
                {pending
                    ? 'Сохраняем...'
                    : 'Сохранить правила'}
            </button>
        </div>
    )

    return (
        <Modal
            title="Правила использования моделей"
            footer={
                modalFooter
            }
            onClose={
                requestClose
            }
            closeDisabled={
                pending
                || previewPending
            }
            closeOnBackdrop={
                false
            }
            closeOnEscape={
                true
            }
            size="lg"
            className="models-policy-modal"
            resize={
                POLICY_MODAL_RESIZE
            }
        >
            <form
                data-active-section={activeSection ?? undefined}
                id={
                    formId
                }
                className={
                    'models-form '
                    + 'models-policy-form'
                }
                onSubmit={(
                    event,
                ) => {
                    event.preventDefault()

                    void handleSubmit()
                }}
            >
                <div
                    className={
                        'models-policy-form__intro'
                    }
                >
                    <div
                        className={
                            'models-policy-form__organization'
                        }
                    >
                        <span
                            className={
                                'models-policy-form__meta-label'
                            }
                        >
                            Организация
                        </span>

                        <strong>
                            {organizationName
                                ?? 'Текущая организация'}
                        </strong>

                        <code
                            className={
                                'models-form__code'
                            }
                        >
                            {organizationId}
                        </code>
                    </div>

                    <p
                        className={
                            'models-form__hint'
                        }
                    >
                        {versionMessage}
                    </p>
                </div>

                {formError
                    && (
                        <ErrorState
                            message={
                                formError
                            }
                            variant="inline"
                        />
                    )}

                <div className="models-policy-form__overview-grid">
                    <div
                        className={
                            'models-policy-form__scope'
                        }
                    >
                    <div
                        className={
                            'models-policy-form__scope-copy'
                        }
                    >
                        <strong>
                            Что настраивает это окно
                        </strong>

                        <p
                            className={
                                'models-form__hint'
                            }
                        >
                            Здесь меняется только блок
                            {' '}
                            «Доступ и ограничения».
                            Фактический провайдер и модель
                            {' '}
                            задаются конфигурацией Runtime на сервере.
                            {' '}
                            Правила организации их не переключают.
                        </p>
                    </div>

                    <nav
                        className={
                            'models-policy-form__scope-tags'
                        }
                        aria-label={
                            'Быстрый переход по настройкам'
                        }
                    >
                        <button
                            type="button"
                            className={
                                'models-policy-form__scope-tag models-policy-form__scope-tag--access'
                            }
                            aria-pressed={activeSection === 'access'}
                            aria-controls={`${formId}-access ${formId}-default`}
                            onClick={() => {
                                focusShortcut(
                                    'access',
                                    accessControlRef.current,
                                )
                            }}
                        >
                            Доступ
                        </button>

                        <button
                            type="button"
                            className={
                                'models-policy-form__scope-tag models-policy-form__scope-tag--limits'
                            }
                            aria-pressed={activeSection === 'limits'}
                            aria-controls={`${formId}-input ${formId}-output`}
                            onClick={() => {
                                focusShortcut(
                                    'limits',
                                    limitsControlRef.current,
                                )
                            }}
                        >
                            Лимиты
                        </button>

                        <button
                            type="button"
                            className={
                                'models-policy-form__scope-tag models-policy-form__scope-tag--budget'
                            }
                            aria-pressed={activeSection === 'budget'}
                            aria-controls={`${formId}-budget ${formId}-request-cost ${formId}-monthly-budget`}
                            onClick={() => {
                                focusShortcut(
                                    'budget',
                                    budgetControlRef.current,
                                )
                            }}
                        >
                            Бюджет
                        </button>

                        <button
                            type="button"
                            className={
                                'models-policy-form__scope-tag models-policy-form__scope-tag--data'
                            }
                            aria-pressed={activeSection === 'data'}
                            aria-controls={`${formId}-data`}
                            onClick={() => {
                                focusShortcut(
                                    'data',
                                    dataControlRef.current,
                                )
                            }}
                        >
                            Требования к данным
                        </button>
                    </nav>
                    </div>

                    <div
                        className={
                            'models-policy-form__toggle '
                            + (draft.enabled
                                ? 'models-policy-form__toggle--enabled'
                                : 'models-policy-form__toggle--disabled')
                        }
                    >
                    <label
                        className={
                            'models-policy-form__switch-card'
                        }
                    >
                        <span
                            className={
                                'models-policy-form__switch'
                            }
                        >
                            <input
                                type="checkbox"
                                checked={
                                    draft.enabled
                                }
                                aria-label={
                                    rulesStatusLabel
                                }
                                onChange={(
                                    event,
                                ) => {
                                    setDraft(
                                        (
                                            current,
                                        ) => ({
                                            ...current,
                                            enabled:
                                                event
                                                    .target
                                                    .checked,
                                        }),
                                    )
                                }}
                            />

                            <span
                                className={
                                    'models-policy-form__switch-track'
                                }
                            >
                                <span
                                    className={
                                        'models-policy-form__switch-thumb'
                                    }
                                />
                            </span>
                        </span>

                        <span
                            className={
                                'models-policy-form__switch-copy'
                            }
                        >
                            <strong>
                                {rulesStatusLabel}
                            </strong>

                            <small>
                                {rulesStatusHint}
                            </small>
                        </span>
                    </label>

                    <p
                        className={
                            'models-policy-form__toggle-note'
                        }
                    >
                        {draft.enabled
                            ? (
                                'Сейчас ограничения этой '
                                + 'организации включены.'
                            )
                            : (
                                'Сейчас ограничения этой '
                                + 'организации отключены.'
                            )}
                    </p>
                    </div>
                </div>

                {activationWarning
                    && (
                        <div
                            className={
                                'models-policy-form__warning'
                            }
                            role="alert"
                        >
                            <strong>
                                Сейчас нет действующей
                                {' '}
                                записи каталога,
                                {' '}
                                совпадающей с Runtime.
                            </strong>

                            <p>
                                Если сохранить правила включёнными,
                                {' '}
                                запросы этой организации будут
                                {' '}
                                предсказуемо отклоняться до обращения
                                {' '}
                                к провайдеру. Перед сохранением включённых
                                {' '}
                                правил выполните серверную проверку черновика.
                            </p>
                        </div>
                    )}

                {previewError && (
                    <ErrorState
                        message={previewError}
                        variant="inline"
                    />
                )}

                {policyPreview && !currentPreview && (
                    <p className="models-policy-form__preview-stale" role="status">
                        Правила изменены после предыдущей проверки.
                        Запустите «Проверить правила» заново перед сохранением.
                    </p>
                )}

                {currentPreview && (
                    <section
                        className={
                            'models-policy-form__preview '
                            + (currentPreview.wouldLockOutOrganization
                                ? 'models-policy-form__preview--danger'
                                : 'models-policy-form__preview--ok')
                        }
                        aria-live="polite"
                    >
                        <div className="models-policy-form__preview-summary">
                            <div>
                                <strong>Серверная проверка черновика</strong>
                                <small>
                                    Runtime: {currentPreview.runtimeProvider}/{currentPreview.runtimeModel}
                                    {' · '}
                                    Исполняемых моделей: {currentPreview.executableModelCount}
                                    {' · '}
                                    Проверено сервером: {new Date(currentPreview.evaluatedAt).toLocaleString('ru-RU')}
                                </small>
                            </div>

                            <span>
                                {currentPreview.automaticOutcome === 'ALLOWED'
                                    ? 'Автовыбор разрешён'
                                    : 'Автовыбор отклонён'}
                                {' · '}
                                {previewReasonLabel(currentPreview.automaticReason)}
                            </span>
                        </div>

                        <div className="models-policy-form__preview-list">
                            {currentPreview.models.map((item) => (
                                <div
                                    key={`${item.modelKey}:${item.catalogVersion}`}
                                    className="models-policy-form__preview-row"
                                >
                                    <code>{item.modelKey}</code>
                                    <span>
                                        v{item.catalogVersion}
                                        {' · '}
                                        {item.outcome === 'ALLOWED' ? 'Разрешено' : 'Отклонено'}
                                    </span>
                                    <small>
                                        {item.reason
                                            ? previewReasonLabel(item.reason)
                                            : 'Проходит все проверки'}
                                        {' · '}
                                        Лимиты: {item.effectiveInputLimit.toLocaleString('ru-RU')}
                                        {' / '}{item.effectiveOutputLimit.toLocaleString('ru-RU')}
                                        {' · '}
                                        {item.pricingComplete
                                            ? 'Тариф подтвержден'
                                            : 'Данные о стоимости неполные'}
                                    </small>
                                </div>
                            ))}
                        </div>

                        {currentPreview.wouldLockOutOrganization && (
                            <label className="models-policy-form__lockout-confirm">
                                <input
                                    type="checkbox"
                                    checked={lockoutAcknowledged}
                                    onChange={(event) => {
                                        setLockoutAcknowledged(event.target.checked)
                                    }}
                                />
                                <span>
                                    Подтверждаю осознанную блокировку всех исполняемых моделей этой организации.
                                </span>
                            </label>
                        )}
                    </section>
                )}

                <div
                    id={`${formId}-access`}
                    data-policy-block="access"
                    className={
                        'models-policy-form__access-grid'
                    }
                >
                    <ModelKeySelector
                        label="Разрешённые модели"
                        hint={
                            'Если список пуст, разрешены все модели, '
                            + 'кроме явно запрещённых.'
                        }
                        kind="allow"
                        catalog={
                            catalog
                        }
                        effectiveCatalog={
                            effectiveCatalog
                        }
                        runtime={
                            runtime
                        }
                        value={
                            draft.allowModelKeys
                        }
                        conflictingValue={
                            draft.denyModelKeys
                        }
                        disabled={
                            pending
                        }
                        inputRef={
                            accessControlRef
                        }
                        onInteractionStateChange={
                            setAllowSelectorState
                        }
                        onChange={(
                            value,
                        ) => {
                            setDraft(
                                (
                                    current,
                                ) => ({
                                    ...current,
                                    allowModelKeys:
                                        value,
                                }),
                            )
                        }}
                    />

                    <ModelKeySelector
                        label="Запрещённые модели"
                        hint={
                            'Модель не может одновременно '
                            + 'быть в списке разрешённых и запрещённых.'
                        }
                        kind="deny"
                        catalog={
                            catalog
                        }
                        effectiveCatalog={
                            effectiveCatalog
                        }
                        runtime={
                            runtime
                        }
                        value={
                            draft.denyModelKeys
                        }
                        conflictingValue={
                            draft.allowModelKeys
                        }
                        disabled={
                            pending
                        }
                        onInteractionStateChange={
                            setDenySelectorState
                        }
                        onChange={(
                            value,
                        ) => {
                            setDraft(
                                (
                                    current,
                                ) => ({
                                    ...current,
                                    denyModelKeys:
                                        value,
                                }),
                            )
                        }}
                    />
                </div>

                <div
                    className={
                        'models-policy-form__settings-grid'
                    }
                >
                    <div
                        id={`${formId}-default`}
                        data-policy-block="access"
                        className={
                            'models-policy-form__setting-field models-policy-form__setting-field--access'
                        }
                    >
                        <span
                            className={
                                'models-label-row'
                            }
                        >
                            Модель по умолчанию

                            <InfoHint
                                text={
                                    'Используется, если запрос не выбрал модель явно. '
                                    + 'Статус рядом показывает связь последней и действующей '
                                    + 'версий каталога с текущим Runtime. '
                                    + 'Автоматический режим следует текущему Runtime; '
                                    + 'закрепленный ключ останется выбранным после смены Runtime '
                                    + 'и может перестать быть исполняемым.'
                                }
                            />
                        </span>

                        <DefaultModelSelector
                            catalog={
                                catalog
                            }
                            effectiveCatalog={
                                effectiveCatalog
                            }
                            runtime={
                                runtime
                            }
                            allowModelKeys={
                                draft.allowModelKeys
                            }
                            denyModelKeys={
                                draft.denyModelKeys
                            }
                            value={
                                draft.defaultModelKey
                            }
                            disabled={
                                pending
                            }
                            onChange={(
                                value,
                            ) => {
                                setDraft(
                                    (
                                        current,
                                    ) => ({
                                        ...current,
                                        defaultModelKey:
                                            value,
                                    }),
                                )
                            }}
                        />
                        <p className="models-policy-form__field-explainer">
                            Автоматически — использовать текущую модель сервера при каждом запросе.
                            Закрепить модель — сохранить ключ каталога; при смене Runtime
                            совместимость будет проверяться заново, а запрос может быть отклонен.
                        </p>
                    </div>

                    <label id={`${formId}-budget`} data-policy-block="budget" className="models-policy-form__setting-field models-policy-form__setting-field--budget">
                        <span
                            className={
                                'models-label-row'
                            }
                        >
                            Контроль бюджета

                            <InfoHint
                                text={
                                    'Мягкий режим фиксирует превышение, '
                                    + 'жёсткий блокирует запрос до обращения к провайдеру.'
                                }
                            />
                        </span>

                        <select
                            ref={
                                budgetControlRef
                            }
                            value={
                                draft.budgetEnforcement
                            }
                            onChange={(
                                event,
                            ) => {
                                setDraft(
                                    (
                                        current,
                                    ) => ({
                                        ...current,
                                        budgetEnforcement:
                                            event.target.value as BudgetEnforcement,
                                    }),
                                )
                            }}
                        >
                            {BUDGET_ENFORCEMENTS.map(
                                (
                                    value,
                                ) => (
                                    <option
                                        key={
                                            value
                                        }
                                        value={
                                            value
                                        }
                                    >
                                        {budgetEnforcementLabel(
                                            value,
                                        )}
                                    </option>
                                ),
                            )}
                        </select>
                    </label>

                    <label id={`${formId}-input`} data-policy-block="limits" className="models-policy-form__setting-field models-policy-form__setting-field--limits">
                        Входные токены, максимум

                        <input
                            ref={
                                limitsControlRef
                            }
                            inputMode="numeric"
                            placeholder="Например: 32000"
                            value={
                                draft.maxInputTokens
                            }
                            onChange={(
                                event,
                            ) => {
                                setDraft(
                                    (
                                        current,
                                    ) => ({
                                        ...current,
                                        maxInputTokens:
                                            event
                                                .target
                                                .value,
                                    }),
                                )
                            }}
                        />
                    </label>

                    <label id={`${formId}-output`} data-policy-block="limits" className="models-policy-form__setting-field models-policy-form__setting-field--limits">
                        Выходные токены, максимум

                        <input
                            inputMode="numeric"
                            placeholder="Например: 4096"
                            value={
                                draft.maxOutputTokens
                            }
                            onChange={(
                                event,
                            ) => {
                                setDraft(
                                    (
                                        current,
                                    ) => ({
                                        ...current,
                                        maxOutputTokens:
                                            event
                                                .target
                                                .value,
                                    }),
                                )
                            }}
                        />
                    </label>

                    <div id={`${formId}-request-cost`} data-policy-block="budget" className="models-policy-form__setting-field models-policy-form__setting-field--budget">
                        <DecimalInput
                            label="Стоимость запроса, USD"
                            placeholder="Без лимита"
                            value={
                                draft.maxRequestCostUsd
                            }
                            onChange={(
                                value,
                            ) => {
                                setDraft(
                                    (
                                        current,
                                    ) => ({
                                        ...current,
                                        maxRequestCostUsd:
                                            value,
                                    }),
                                )
                            }}
                        />
                    </div>

                    <div id={`${formId}-monthly-budget`} data-policy-block="budget" className="models-policy-form__setting-field models-policy-form__setting-field--budget">
                        <DecimalInput
                            label="Бюджет на месяц, USD"
                            placeholder="Не задан"
                            value={
                                draft.monthlyBudgetUsd
                            }
                            onChange={(
                                value,
                            ) => {
                                setDraft(
                                    (
                                        current,
                                    ) => ({
                                        ...current,
                                        monthlyBudgetUsd:
                                            value,
                                    }),
                                )
                            }}
                        />
                        {!draft.monthlyBudgetUsd.trim() && (
                            <p className="models-policy-form__field-explainer">
                                Бюджет не установлен: месячного порога нет.
                                Режим «{budgetEnforcementLabel(draft.budgetEnforcement)}»
                                начнет влиять на лимит после задания суммы.
                            </p>
                        )}
                        {draft.monthlyBudgetUsd.trim() && draft.budgetEnforcement === 'HARD' && (
                            <p className="models-policy-form__field-explainer">
                                Жесткий контроль проверяется сервером до исполнения запроса;
                                запросы с непроверяемой стоимостью могут отклоняться.
                                Итог проверьте через «Проверить правила».
                            </p>
                        )}
                    </div>
                </div>

                <fieldset
                    id={`${formId}-data`}
                    data-policy-block="data"
                    className={
                        'models-policy-form__requirements'
                    }
                >
                    <legend>
                        Дополнительные требования
                    </legend>

                    <p id={`${formId}-training-hint`} className="models-form__hint">
                        Запрет обучения требует подтверждения в действующей версии каталога:
                        «Не используются» (NOT_USED) или договорного запрета
                        (CONTRACTUAL_NO_TRAINING). «Не заявлено» (NOT_DECLARED)
                        не подтверждает запрет, даже для бесплатной модели.
                    </p>
                    <div aria-live="polite" aria-atomic="true">
                        {draft.requireNoTraining && undeclaredTrainingModels.length > 0 && (
                            <div
                                className={
                                    'models-policy-form__warning'
                                    + (serverConfirmedTrainingLockout
                                        ? ' models-policy-form__warning--danger'
                                        : '')
                                }
                                role={
                                    serverConfirmedTrainingLockout
                                        ? 'alert'
                                        : 'status'
                                }
                            >
                                <strong>
                                    {serverConfirmedTrainingLockout
                                        ? 'Правила блокируют доступные модели'
                                        : draft.enabled
                                            ? 'Несовместимость с требованием запрета обучения'
                                            : 'При включении правил возникнет несовместимость'}
                                </strong>

                                {serverConfirmedTrainingLockout
                                    ? (
                                        <p>
                                            Серверная проверка подтвердила блокировку:
                                            {' '}требованию «Не использовать данные для обучения»
                                            {' '}не соответствуют модели:
                                            {' '}{serverTrainingRejectedModelKeys.join(', ')}.
                                            {' '}Исполняемых моделей:
                                            {' '}{currentPreview?.executableModelCount ?? 0}.
                                        </p>
                                    )
                                    : (
                                        <p>
                                            В действующем каталоге использование данных для обучения
                                            не заявлено (NOT_DECLARED):
                                            {' '}{undeclaredTrainingModels.map((entry) => entry.modelKey).join(', ')}.
                                            {' '}Эти модели не соответствуют выбранному требованию.
                                            {' '}Проверьте черновик на сервере, чтобы увидеть итоговую
                                            доступность с учётом всех правил и текущей модели сервера.
                                        </p>
                                    )}
                            </div>
                        )}
                    </div>

                    <div
                        className={
                            'models-form__checks'
                        }
                    >
                        <label>
                            <input
                                ref={
                                    dataControlRef
                                }
                                type="checkbox"
                                checked={
                                    draft.requireCompletePricing
                                }
                                onChange={(
                                    event,
                                ) => {
                                    setDraft(
                                        (
                                            current,
                                        ) => ({
                                            ...current,
                                            requireCompletePricing:
                                                event
                                                    .target
                                                    .checked,
                                        }),
                                    )
                                }}
                            />

                            Полные данные о стоимости
                        </label>

                        <label>
                            <input
                                type="checkbox"
                                checked={
                                    draft.requireNoTraining
                                }
                                aria-describedby={`${formId}-training-hint`}
                                onChange={(
                                    event,
                                ) => {
                                    setDraft(
                                        (
                                            current,
                                        ) => ({
                                            ...current,
                                            requireNoTraining:
                                                event
                                                    .target
                                                    .checked,
                                        }),
                                    )
                                }}
                            />

                            Не использовать данные
                            {' '}
                            для обучения
                        </label>

                        <label>
                            <input
                                type="checkbox"
                                checked={
                                    draft.requireZeroDataRetention
                                }
                                onChange={(
                                    event,
                                ) => {
                                    setDraft(
                                        (
                                            current,
                                        ) => ({
                                            ...current,
                                            requireZeroDataRetention:
                                                event
                                                    .target
                                                    .checked,
                                        }),
                                    )
                                }}
                            />

                            Не хранить данные
                            {' '}
                            после запроса
                        </label>
                    </div>
                </fieldset>
            </form>
        </Modal>
    )
}

