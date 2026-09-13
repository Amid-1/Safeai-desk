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

const POLICY_MODAL_RESIZE:
    ModalResizeOptions = {
        initialWidth: 1480,
        initialHeight: 900,

        minWidth: 620,
        minHeight: 440,

        scaleContent: true,
        minScale: 0.72,
        maxScale: 1.08,
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
        TRAINING_POLICY_UNSATISFIED: 'Не выполнено требование по обучению',
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
        target:
            HTMLElement | null,
    ) => {
        if (
            !target
        ) {
            return
        }

        target.focus({
            preventScroll:
                true,
        })

        if (
            typeof target
                .scrollIntoView
                === 'function'
        ) {
            target.scrollIntoView({
                behavior:
                    'smooth',
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
        <div
            className={
                'models-policy-modal__footer'
            }
        >
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
                            onClick={() => {
                                focusShortcut(
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
                            onClick={() => {
                                focusShortcut(
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
                            onClick={() => {
                                focusShortcut(
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
                            onClick={() => {
                                focusShortcut(
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
                                    + 'версий каталога с текущим Runtime.'
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
                    </div>

                    <label className="models-policy-form__setting-field models-policy-form__setting-field--budget">
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

                    <label className="models-policy-form__setting-field models-policy-form__setting-field--limits">
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

                    <label className="models-policy-form__setting-field models-policy-form__setting-field--limits">
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

                    <div className="models-policy-form__setting-field models-policy-form__setting-field--budget">
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

                    <div className="models-policy-form__setting-field models-policy-form__setting-field--budget">
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
                    </div>
                </div>

                <fieldset
                    className={
                        'models-policy-form__requirements'
                    }
                >
                    <legend>
                        Дополнительные требования
                    </legend>

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
