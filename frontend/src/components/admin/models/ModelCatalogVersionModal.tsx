// ============================================================
// frontend/src/components/admin/models/ModelCatalogVersionModal.tsx
// ============================================================

import {
    useId,
    useState,
} from 'react'
import type {
    CreateModelCatalogVersionRequest,
    ModelCatalogEntry,
    RuntimeModelStatus,
    ModelLifecycle,
    ModelRetentionStatus,
    ModelTrainingUseStatus,
    ModelPricingStatus,
    ModelCapability,
    ModelModality,
} from '../../../api/modelApi'
import {
    MODEL_CAPABILITIES,
    MODEL_LIFECYCLES,
    MODEL_MODALITIES,
    MODEL_PRICING_STATUSES,
    MODEL_RETENTION_STATUSES,
    MODEL_TRAINING_USE_STATUSES,
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
    CheckboxGroup,
    DecimalInput,
} from './ModelFormControls'
import {
    buildCatalogRequest,
    createCatalogDraft,
    normalizeDraftForPricingStatus,
} from './modelControlPlaneSupport'
import type {
    CatalogDraft,
} from './modelControlPlaneSupport'
import './ModelCatalogVersionModal.css'

type ModelCatalogVersionModalProps = {
    base: ModelCatalogEntry | null
    runtime: RuntimeModelStatus
    catalogByKey: Map<string, ModelCatalogEntry>
    pending: boolean
    onClose: () => void
    onSubmit: (
        request: CreateModelCatalogVersionRequest,
    ) => Promise<void>
}

const CATALOG_MODAL_RESIZE:
    ModalResizeOptions = {
        initialWidth: 1120,
        initialHeight: 790,

        minWidth: 700,
        minHeight: 520,

        maxWidth: 1480,
        maxHeight: 980,

        scaleContent: true,
        minScale: 0.80,
        maxScale: 1.18,
    }

const OUTPUT_MODALITIES =
    MODEL_MODALITIES.filter(
        (value) => value !== 'IMAGE',
    )

const LIFECYCLE_LABELS: Record<
    ModelLifecycle,
    string
> = {
    ACTIVE: 'Активна',
    DEPRECATED: 'Устаревает',
    DISABLED: 'Отключена',
    RETIRED: 'Выведена из эксплуатации',
}

const CAPABILITY_LABELS: Record<
    ModelCapability,
    string
> = {
    TOOLS: 'Инструменты',
    VISION: 'Работа с изображениями',
    STRUCTURED_OUTPUT:
        'Структурированный ответ',
}

const MODALITY_LABELS: Record<
    ModelModality,
    string
> = {
    TEXT: 'Текст',
    IMAGE: 'Изображения',
    AUDIO: 'Аудио',
}

const RETENTION_LABELS: Record<
    ModelRetentionStatus,
    string
> = {
    NOT_DECLARED: 'Не указано',
    STANDARD: 'Стандартное хранение',
    ZERO_DATA_RETENTION:
        'Данные не сохраняются',
    CUSTOM: 'Особые условия',
}

const TRAINING_LABELS: Record<
    ModelTrainingUseStatus,
    string
> = {
    NOT_DECLARED: 'Не указано',
    NOT_USED: 'Не используется для обучения',
    MAY_BE_USED: 'Может использоваться для обучения',
    CONTRACTUAL_NO_TRAINING:
        'Обучение запрещено договором',
}

const PRICING_LABELS: Record<
    ModelPricingStatus,
    string
> = {
    UNPRICED: 'Стоимость не указана',
    FREE: 'Бесплатно',
    CONFIGURED: 'Стоимость настроена',
    INCOMPLETE: 'Стоимость заполнена не полностью',
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

function SectionHeading({
    title,
    hint,
    tone = 'primary',
}: {
    title: string
    hint: string
    tone?:
        | 'primary'
        | 'success'
        | 'neutral'
        | 'pricing'
}) {
    return (
        <div
            className={[
                'models-catalog-form__section-heading',
                `models-catalog-form__section-heading--${tone}`,
            ].join(' ')}
        >
            <span
                className="models-catalog-form__section-icon"
                aria-hidden="true"
            />

            <div>
                <h3>
                    {title}
                </h3>

                <p>
                    {hint}
                </p>
            </div>
        </div>
    )
}

export function ModelCatalogVersionModal({
    base,
    runtime,
    catalogByKey,
    pending,
    onClose,
    onSubmit,
}: ModelCatalogVersionModalProps) {
    const formId =
        useId()

    const [draft, setDraft] =
        useState<CatalogDraft>(() =>
            createCatalogDraft(
                base,
                runtime,
            ),
        )

    const [formError, setFormError] =
        useState('')

    const expectedPreviousVersion =
        catalogByKey.get(
            draft.modelKey
                .trim()
                .toLowerCase(),
        )?.version ?? 0

    const handleSubmit = async () => {
        setFormError('')

        try {
            const request =
                buildCatalogRequest(
                    draft,
                    expectedPreviousVersion,
                )

            await onSubmit(request)
        } catch (failure) {
            setFormError(
                failure instanceof Error
                    ? getApiErrorMessage(
                        failure,
                        failure.message,
                    )
                    : 'Не удалось сохранить модель.',
            )
        }
    }

    const modalFooter = (
        <div
            className="models-catalog-modal__footer"
        >
            <button
                type="button"
                disabled={pending}
                onClick={onClose}
            >
                Отмена
            </button>

            <button
                type="submit"
                form={formId}
                className="btn-primary"
                disabled={pending}
            >
                {pending
                    ? 'Сохраняем...'
                    : 'Сохранить версию'}
            </button>
        </div>
    )

    return (
        <Modal
            title={
                base
                    ? `Новая версия: ${base.displayName}`
                    : 'Добавление модели в каталог'
            }
            footer={modalFooter}
            onClose={onClose}
            closeDisabled={pending}

            /**
             * Каталожная форма содержит много полей.
             * Случайный click по backdrop не должен уничтожать draft.
             */
            closeOnBackdrop={false}

            /**
             * Поведение совпадает с policy modal:
             * закрытие — крестик / Отмена / успешный Save.
             */
            closeOnEscape={false}

            size="lg"
            className="models-catalog-modal"
            resize={CATALOG_MODAL_RESIZE}
        >
            <form
                id={formId}
                className="models-form models-catalog-form"
                onSubmit={(event) => {
                    event.preventDefault()
                    void handleSubmit()
                }}
            >
                <div
                    className="models-catalog-form__intro"
                >
                    <span
                        className="models-catalog-form__intro-badge"
                        aria-hidden="true"
                    />

                    <div>
                        <strong>
                            {base
                                ? 'Новая immutable-версия модели'
                                : 'Новая модель в Model Catalog'}
                        </strong>

                        <p className="models-form__hint">
                            {base
                                ? `После сохранения появится версия ${expectedPreviousVersion + 1}. Предыдущая версия останется в истории.`
                                : 'После сохранения появится первая версия модели в каталоге.'}
                        </p>
                    </div>
                </div>

                {formError && (
                    <ErrorState
                        message={formError}
                        variant="inline"
                    />
                )}

                <div
                    className="models-catalog-form__layout"
                >
                    <section
                        className={[
                            'models-catalog-form__panel',
                            'models-catalog-form__panel--identity',
                        ].join(' ')}
                    >
                        <SectionHeading
                            title="Основные данные"
                            hint="Идентификаторы модели и момент вступления версии в силу."
                        />

                        <div className="models-form__grid">
                            <label>
                                <span className="models-label-row">
                                    Ключ модели
                                    <InfoHint text="Уникальное техническое имя модели в каталоге, например openai:gpt-5." />
                                </span>

                                <input
                                    required
                                    maxLength={160}
                                    value={draft.modelKey}
                                    disabled={base !== null}
                                    placeholder="openai:gpt-5"
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            modelKey:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>

                            <label>
                                Название
                                <input
                                    required
                                    maxLength={255}
                                    value={draft.displayName}
                                    placeholder="Название для администраторов"
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            displayName:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>

                            <label>
                                Провайдер
                                <input
                                    required
                                    maxLength={32}
                                    value={draft.provider}
                                    placeholder="openai"
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            provider:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>

                            <label>
                                ID модели у провайдера
                                <input
                                    required
                                    maxLength={100}
                                    value={draft.providerModelId}
                                    placeholder="gpt-5"
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            providerModelId:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>

                            <label>
                                Статус модели
                                <select
                                    value={draft.lifecycle}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            lifecycle:
                                                (
                                                    event.target.value as ModelLifecycle
                                                ),
                                        }))
                                    }}
                                >
                                    {MODEL_LIFECYCLES.map(
                                        (value) => (
                                            <option
                                                key={value}
                                                value={value}
                                            >
                                                {LIFECYCLE_LABELS[value]}
                                            </option>
                                        ),
                                    )}
                                </select>
                            </label>

                            <label>
                                Начать использовать с
                                <input
                                    type="datetime-local"
                                    value={draft.effectiveFrom}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            effectiveFrom:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>
                        </div>
                    </section>

                    <section
                        className={[
                            'models-catalog-form__panel',
                            'models-catalog-form__panel--limits',
                        ].join(' ')}
                    >
                        <SectionHeading
                            title="Лимиты"
                            hint="Ограничения на объём входа и ответа модели."
                            tone="neutral"
                        />

                        <div className="models-form__grid">
                            <label>
                                Входные токены, максимум
                                <input
                                    required
                                    inputMode="numeric"
                                    value={draft.maxInputTokens}
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
                                    required
                                    inputMode="numeric"
                                    value={draft.maxOutputTokens}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            maxOutputTokens:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>
                        </div>
                    </section>

                    <section
                        className={[
                            'models-catalog-form__panel',
                            'models-catalog-form__panel--data',
                        ].join(' ')}
                    >
                        <SectionHeading
                            title="Данные и использование"
                            hint="Retention, обучение и семантика стоимости."
                            tone="success"
                        />

                        <div className="models-form__grid">
                            <label>
                                Хранение данных
                                <select
                                    value={draft.retentionStatus}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            retentionStatus:
                                                (
                                                    event.target.value as ModelRetentionStatus
                                                ),
                                        }))
                                    }}
                                >
                                    {MODEL_RETENTION_STATUSES.map(
                                        (value) => (
                                            <option
                                                key={value}
                                                value={value}
                                            >
                                                {RETENTION_LABELS[value]}
                                            </option>
                                        ),
                                    )}
                                </select>
                            </label>

                            <label>
                                Срок хранения, дней
                                <input
                                    inputMode="numeric"
                                    placeholder="Если применимо"
                                    value={draft.retentionDays}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            retentionDays:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>

                            <label>
                                Использование для обучения
                                <select
                                    value={draft.trainingUseStatus}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            trainingUseStatus:
                                                (
                                                    event.target.value as ModelTrainingUseStatus
                                                ),
                                        }))
                                    }}
                                >
                                    {MODEL_TRAINING_USE_STATUSES.map(
                                        (value) => (
                                            <option
                                                key={value}
                                                value={value}
                                            >
                                                {TRAINING_LABELS[value]}
                                            </option>
                                        ),
                                    )}
                                </select>
                            </label>

                            <label>
                                Статус стоимости
                                <select
                                    value={draft.pricingStatus}
                                    onChange={(event) => {
                                        const pricingStatus =
                                            (
                                                event.target.value as ModelPricingStatus
                                            )

                                        setDraft((current) =>
                                            normalizeDraftForPricingStatus(
                                                {
                                                    ...current,
                                                    pricingStatus,
                                                },
                                            ),
                                        )
                                    }}
                                >
                                    {MODEL_PRICING_STATUSES.map(
                                        (value) => (
                                            <option
                                                key={value}
                                                value={value}
                                            >
                                                {PRICING_LABELS[value]}
                                            </option>
                                        ),
                                    )}
                                </select>
                            </label>
                        </div>
                    </section>

                    <section
                        className={[
                            'models-catalog-form__panel',
                            'models-catalog-form__panel--capabilities',
                        ].join(' ')}
                    >
                        <SectionHeading
                            title="Возможности и типы данных"
                            hint="Что модель умеет и с какими входами/выходами работает."
                            tone="primary"
                        />

                        <fieldset>
                            <legend>
                                Дополнительные возможности
                            </legend>

                            <CheckboxGroup
                                values={MODEL_CAPABILITIES}
                                selected={draft.capabilities}
                                getLabel={(value) =>
                                    CAPABILITY_LABELS[value]}
                                onChange={(capabilities) => {
                                    setDraft((current) => ({
                                        ...current,
                                        capabilities,
                                    }))
                                }}
                            />
                        </fieldset>

                        <div className="models-form__two-columns">
                            <fieldset>
                                <legend>
                                    Входные данные
                                </legend>

                                <CheckboxGroup
                                    values={MODEL_MODALITIES}
                                    selected={draft.inputModalities}
                                    getLabel={(value) =>
                                        MODALITY_LABELS[value]}
                                    onChange={(inputModalities) => {
                                        setDraft((current) => ({
                                            ...current,
                                            inputModalities,
                                        }))
                                    }}
                                />
                            </fieldset>

                            <fieldset>
                                <legend>
                                    Выходные данные
                                </legend>

                                <CheckboxGroup
                                    values={OUTPUT_MODALITIES}
                                    selected={draft.outputModalities.filter(
                                        (value) =>
                                            value !== 'IMAGE',
                                    )}
                                    getLabel={(value) =>
                                        MODALITY_LABELS[value]}
                                    onChange={(outputModalities) => {
                                        setDraft((current) => ({
                                            ...current,
                                            outputModalities,
                                        }))
                                    }}
                                />
                            </fieldset>
                        </div>
                    </section>

                    <section
                        className={[
                            'models-catalog-form__panel',
                            'models-catalog-form__panel--pricing',
                        ].join(' ')}
                    >
                        <SectionHeading
                            title="Тарификация"
                            hint="Версионированные price dimensions. Неизвестная стоимость не превращается в ноль."
                            tone="pricing"
                        />

                        <label
                            className={[
                                'models-check-row',
                                'models-check-row--strong',
                                'models-catalog-form__pricing-complete',
                            ].join(' ')}
                        >
                            <input
                                type="checkbox"
                                checked={draft.pricingComplete}
                                disabled={
                                    draft.pricingStatus
                                        === 'FREE'
                                    || draft.pricingStatus
                                        === 'UNPRICED'
                                    || draft.pricingStatus
                                        === 'INCOMPLETE'
                                }
                                onChange={(event) => {
                                    setDraft((current) => ({
                                        ...current,
                                        pricingComplete:
                                            event.target.checked,
                                    }))
                                }}
                            />

                            Все варианты тарификации известны
                        </label>

                        <div
                            className={[
                                'models-form__grid',
                                'models-form__pricing-grid',
                                'models-catalog-form__pricing-grid',
                            ].join(' ')}
                        >
                            <DecimalInput
                                label="Вход, USD за 1 млн токенов"
                                value={draft.inputUsdPer1mTokens}
                                onChange={(value) => {
                                    setDraft((current) => ({
                                        ...current,
                                        inputUsdPer1mTokens:
                                            value,
                                    }))
                                }}
                            />

                            <DecimalInput
                                label="Кэшированный вход, USD за 1 млн"
                                value={draft.cachedInputUsdPer1mTokens}
                                onChange={(value) => {
                                    setDraft((current) => ({
                                        ...current,
                                        cachedInputUsdPer1mTokens:
                                            value,
                                    }))
                                }}
                            />

                            <DecimalInput
                                label="Запись кэша, USD за 1 млн"
                                value={draft.cacheWriteInputUsdPer1mTokens}
                                onChange={(value) => {
                                    setDraft((current) => ({
                                        ...current,
                                        cacheWriteInputUsdPer1mTokens:
                                            value,
                                    }))
                                }}
                            />

                            <DecimalInput
                                label="Выход, USD за 1 млн токенов"
                                value={draft.outputUsdPer1mTokens}
                                onChange={(value) => {
                                    setDraft((current) => ({
                                        ...current,
                                        outputUsdPer1mTokens:
                                            value,
                                    }))
                                }}
                            />

                            <label>
                                Версия тарифа
                                <input
                                    maxLength={64}
                                    value={draft.pricingVersion}
                                    placeholder="Например: 2026-08"
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            pricingVersion:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>
                        </div>

                        <details className="models-form__advanced">
                            <summary>
                                Дополнительные параметры тарификации
                            </summary>

                            <label>
                                JSON-параметры
                                <textarea
                                    rows={3}
                                    maxLength={16_000}
                                    value={draft.extraPricingJson}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            extraPricingJson:
                                                event.target.value,
                                        }))
                                    }}
                                />

                                <small>
                                    Для обычной полной тарификации
                                    оставьте {'{}'}.
                                </small>
                            </label>
                        </details>
                    </section>
                </div>
            </form>
        </Modal>
    )
}
