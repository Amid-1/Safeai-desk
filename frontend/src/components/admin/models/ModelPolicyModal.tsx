import {
    useRef,
    useState,
} from 'react'
import type {
    BudgetEnforcement,
    CreateOrganizationModelPolicyVersionRequest,
    ModelCatalogEntry,
    OrganizationModelPolicy,
} from '../../../api/modelApi'
import { BUDGET_ENFORCEMENTS } from '../../../api/modelApi'
import Modal from '../../Modal'
import type { ModalResizeOptions } from '../../Modal'
import { getApiErrorMessage } from '../../../api/http'
import { ErrorState } from '../../StateBlock'
import { DecimalInput } from './ModelFormControls'
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
import type { PolicyDraft } from './modelControlPlaneSupport'

const POLICY_MODAL_RESIZE: ModalResizeOptions = {
    initialWidth: 1140,
    initialHeight: 760,
    minWidth: 820,
    minHeight: 600,
    maxWidth: 1480,
    maxHeight: 980,
    scaleContent: true,
}

type ModelPolicyModalProps = {
    policy: OrganizationModelPolicy
    catalog: ModelCatalogEntry[]
    organizationId: string
    pending: boolean
    onClose: () => void
    onSubmit: (
        request: CreateOrganizationModelPolicyVersionRequest,
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
    value: BudgetEnforcement,
) {
    switch (value) {
        case 'SOFT':
            return 'Мягкий — предупреждать'
        case 'HARD':
            return 'Жёсткий — блокировать'
    }
}

export function ModelPolicyModal({
    policy,
    catalog,
    organizationId,
    pending,
    onClose,
    onSubmit,
}: ModelPolicyModalProps) {
    const [draft, setDraft] =
        useState<PolicyDraft>(() =>
            createPolicyDraft(policy),
        )

    const [formError, setFormError] =
        useState('')
    const [allowSelectorState, setAllowSelectorState] =
        useState<ModelKeySelectorInteractionState>({
            hasPendingInput: false,
            hasError: false,
        })
    const [denySelectorState, setDenySelectorState] =
        useState<ModelKeySelectorInteractionState>({
            hasPendingInput: false,
            hasError: false,
        })

    const accessControlRef =
        useRef<HTMLInputElement | null>(null)
    const limitsControlRef =
        useRef<HTMLInputElement | null>(null)
    const budgetControlRef =
        useRef<HTMLSelectElement | null>(null)
    const dataControlRef =
        useRef<HTMLInputElement | null>(null)

    const focusShortcut = (
        target: HTMLElement | null,
    ) => {
        if (!target) {
            return
        }

        target.focus({
            preventScroll: true,
        })

        if (
            typeof target.scrollIntoView
                === 'function'
        ) {
            target.scrollIntoView({
                behavior: 'smooth',
                block: 'center',
                inline: 'nearest',
            })
        }
    }

    const handleSubmit = async () => {
        setFormError('')

        if (
            allowSelectorState.hasError
            || denySelectorState.hasError
        ) {
            setFormError(
                'Исправьте ошибки в списках моделей перед сохранением.',
            )
            return
        }

        if (
            allowSelectorState.hasPendingInput
            || denySelectorState.hasPendingInput
        ) {
            setFormError(
                'Завершите добавление модели: выберите её из списка, нажмите Enter для ручного ключа или очистите строку поиска.',
            )
            return
        }

        try {
            await onSubmit(
                buildPolicyRequest(
                    draft,
                    policy.version,
                ),
            )
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
            ? `Сейчас действует версия ${policy.version}. После сохранения появится версия ${policy.version + 1}; предыдущая останется в истории.`
            : 'Это первая настройка правил для организации. После сохранения появится версия 1.'

    const rulesStatusLabel =
        draft.enabled
            ? 'Правила включены'
            : 'Правила выключены'

    const rulesStatusHint =
        draft.enabled
            ? 'Ограничения и лимиты применяются к запросам этой организации.'
            : 'Ограничения временно не применяются. Организация будет работать без этих правил, пока вы не включите их снова.'

    return (
        <Modal
            title="Правила использования моделей"
            onClose={onClose}
            closeDisabled={pending}
            size="lg"
            className="models-policy-modal"
            resize={POLICY_MODAL_RESIZE}
        >
            <form
                className="models-form models-policy-form"
                onSubmit={(event) => {
                    event.preventDefault()
                    void handleSubmit()
                }}
            >
                <div className="models-policy-form__intro">
                    <div>
                        <span className="models-policy-form__meta-label">
                            Организация
                        </span>
                        <code className="models-form__code">
                            {organizationId}
                        </code>
                    </div>

                    <p className="models-form__hint">
                        {versionMessage}
                    </p>
                </div>

                {formError && (
                    <ErrorState
                        message={formError}
                        variant="inline"
                    />
                )}

                <div className="models-policy-form__scope">
                    <div className="models-policy-form__scope-copy">
                        <strong>Что настраивает это окно</strong>
                        <p className="models-form__hint">
                            Здесь настраивается только блок «Доступ и ограничения» на странице моделей.
                            Блок «Подключённая модель» слева заполняется автоматически по фактическому
                            подключению сервера и не меняется в этом окне.
                        </p>
                    </div>

                    <nav
                        className="models-policy-form__scope-tags"
                        aria-label="Быстрый переход по настройкам"
                    >
                        <button
                            type="button"
                            className="models-policy-form__scope-tag"
                            title="Перейти к настройкам доступа"
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
                            className="models-policy-form__scope-tag"
                            title="Перейти к лимитам токенов"
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
                            className="models-policy-form__scope-tag"
                            title="Перейти к настройкам бюджета"
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
                            className="models-policy-form__scope-tag"
                            title="Перейти к требованиям к данным"
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

                <div className="models-policy-form__toggle">
                    <label className="models-policy-form__switch-card">
                        <span className="models-policy-form__switch">
                            <input
                                type="checkbox"
                                checked={draft.enabled}
                                onChange={(event) => {
                                    setDraft((current) => ({
                                        ...current,
                                        enabled:
                                            event.target.checked,
                                    }))
                                }}
                            />
                            <span className="models-policy-form__switch-track">
                                <span className="models-policy-form__switch-thumb" />
                            </span>
                        </span>

                        <span className="models-policy-form__switch-copy">
                            <strong>{rulesStatusLabel}</strong>
                            <small>{rulesStatusHint}</small>
                        </span>
                    </label>

                    <p className="models-policy-form__toggle-note">
                        {draft.enabled
                            ? 'Сейчас ограничения этой организации включены.'
                            : 'Сейчас ограничения этой организации отключены.'}
                    </p>
                </div>

                <div className="models-policy-form__access-grid">
                    <ModelKeySelector
                        label="Разрешённые модели"
                        hint="Можно выбрать модель из каталога или добавить корректный ключ вручную. Если список пуст, разрешены все модели, кроме явно запрещённых."
                        kind="allow"
                        catalog={catalog}
                        value={draft.allowModelKeys}
                        conflictingValue={draft.denyModelKeys}
                        disabled={pending}
                        inputRef={accessControlRef}
                        onInteractionStateChange={setAllowSelectorState}
                        onChange={(value) => {
                            setDraft((current) => ({
                                ...current,
                                allowModelKeys: value,
                            }))
                        }}
                    />

                    <ModelKeySelector
                        label="Запрещённые модели"
                        hint="Можно выбрать модель из каталога или добавить ключ вручную. Модель не может одновременно находиться в разрешённом и запрещённом списке."
                        kind="deny"
                        catalog={catalog}
                        value={draft.denyModelKeys}
                        conflictingValue={draft.allowModelKeys}
                        disabled={pending}
                        onInteractionStateChange={setDenySelectorState}
                        onChange={(value) => {
                            setDraft((current) => ({
                                ...current,
                                denyModelKeys: value,
                            }))
                        }}
                    />
                </div>

                <div className="models-policy-form__settings-grid">
                    <div className="models-policy-form__setting-field">
                        <span className="models-label-row">
                            Модель по умолчанию
                            <InfoHint text="Используется, если запрос не выбрал модель явно. Список учитывает разрешённые и запрещённые модели." />
                        </span>

                        <DefaultModelSelector
                            catalog={catalog}
                            allowModelKeys={draft.allowModelKeys}
                            denyModelKeys={draft.denyModelKeys}
                            value={draft.defaultModelKey}
                            disabled={pending}
                            onChange={(value) => {
                                setDraft((current) => ({
                                    ...current,
                                    defaultModelKey: value,
                                }))
                            }}
                        />
                    </div>

                    <label>
                        <span className="models-label-row">
                            Контроль бюджета
                            <InfoHint text="Мягкий режим показывает превышение, но не останавливает запросы. Жёсткий режим блокирует запрос при превышении бюджета." />
                        </span>

                        <select
                            ref={budgetControlRef}
                            value={
                                draft.budgetEnforcement
                            }
                            onChange={(event) => {
                                setDraft((current) => ({
                                    ...current,
                                    budgetEnforcement:
                                        event.target.value as BudgetEnforcement,
                                }))
                            }}
                        >
                            {BUDGET_ENFORCEMENTS.map(
                                (value) => (
                                    <option
                                        key={value}
                                        value={value}
                                    >
                                        {budgetEnforcementLabel(
                                            value,
                                        )}
                                    </option>
                                ),
                            )}
                        </select>
                    </label>

                    <label>
                        Входные токены, максимум
                        <input
                            ref={limitsControlRef}
                            inputMode="numeric"
                            placeholder="Например: 32000"
                            value={
                                draft.maxInputTokens
                            }
                            onChange={(event) => {
                                setDraft((current) => ({
                                    ...current,
                                    maxInputTokens:
                                        event.target.value,
                                }))
                            }}
                        />
                    </label>

                    <label>
                        Выходные токены, максимум
                        <input
                            inputMode="numeric"
                            placeholder="Например: 4096"
                            value={
                                draft.maxOutputTokens
                            }
                            onChange={(event) => {
                                setDraft((current) => ({
                                    ...current,
                                    maxOutputTokens:
                                        event.target.value,
                                }))
                            }}
                        />
                    </label>

                    <DecimalInput
                        label="Стоимость запроса, USD"
                        placeholder="Без лимита"
                        value={
                            draft.maxRequestCostUsd
                        }
                        onChange={(value) => {
                            setDraft((current) => ({
                                ...current,
                                maxRequestCostUsd:
                                    value,
                            }))
                        }}
                    />

                    <DecimalInput
                        label="Бюджет на месяц, USD"
                        placeholder="Не задан"
                        value={draft.monthlyBudgetUsd}
                        onChange={(value) => {
                            setDraft((current) => ({
                                ...current,
                                monthlyBudgetUsd:
                                    value,
                            }))
                        }}
                    />
                </div>

                <fieldset className="models-policy-form__requirements">
                    <legend>
                        Дополнительные требования
                    </legend>

                    <div className="models-form__checks">
                        <label>
                            <input
                                ref={dataControlRef}
                                type="checkbox"
                                checked={
                                    draft.requireCompletePricing
                                }
                                onChange={(event) => {
                                    setDraft((current) => ({
                                        ...current,
                                        requireCompletePricing:
                                            event.target.checked,
                                    }))
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
                                onChange={(event) => {
                                    setDraft((current) => ({
                                        ...current,
                                        requireNoTraining:
                                            event.target.checked,
                                    }))
                                }}
                            />
                            Не использовать данные для обучения
                        </label>

                        <label>
                            <input
                                type="checkbox"
                                checked={
                                    draft.requireZeroDataRetention
                                }
                                onChange={(event) => {
                                    setDraft((current) => ({
                                        ...current,
                                        requireZeroDataRetention:
                                            event.target.checked,
                                    }))
                                }}
                            />
                            Не хранить данные после запроса
                        </label>
                    </div>
                </fieldset>

                <div className="models-form__actions">
                    <button
                        type="button"
                        disabled={pending}
                        onClick={onClose}
                    >
                        Отмена
                    </button>

                    <button
                        type="submit"
                        className="btn-primary"
                        disabled={pending}
                    >
                        {pending
                            ? 'Сохраняем...'
                            : 'Сохранить правила'}
                    </button>
                </div>
            </form>
        </Modal>
    )
}
