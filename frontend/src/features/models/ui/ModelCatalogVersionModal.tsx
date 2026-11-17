// frontend/src/features/models/ui/ModelCatalogVersionModal.tsx
// ============================================================
// frontend/src/features/models/ui/ModelCatalogVersionModal.tsx
// ============================================================
import { useId, useRef, useState } from 'react'
import type {
    CreateModelCatalogVersionRequest,
    ModelCatalogEntry,
    RuntimeModelStatus,
} from '../../../entities/model/api/modelApi'
import Modal from '../../../shared/ui/Modal'
import type { ModalResizeOptions } from '../../../shared/ui/Modal'
import { getApiErrorMessage } from '../../../shared/api/http'
import { ErrorState } from '../../../shared/ui/StateBlock'
import {
    buildCatalogRequest,
    CatalogDraftValidationError,
    createCatalogDraft,
} from './modelControlPlaneSupport'
import type { CatalogDraft, CatalogSection } from './modelControlPlaneSupport'
import { CatalogIdentitySection } from './catalogVersion/CatalogIdentitySection'
import { CatalogLimitsSection } from './catalogVersion/CatalogLimitsSection'
import { CatalogDataSection } from './catalogVersion/CatalogDataSection'
import { CatalogCapabilitiesSection } from './catalogVersion/CatalogCapabilitiesSection'
import { CatalogPricingSection } from './catalogVersion/CatalogPricingSection'
import { CatalogFormTabs } from './catalogVersion/CatalogFormTabs'
import './ModelControlPlaneModalShell.css'
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
        initialHeight: 770,

        minWidth: 360,
        minHeight: 400,

        scaleContent: false,
        maximizable: true,
    }

export function ModelCatalogVersionModal({
    base,
    runtime,
    catalogByKey,
    pending,
    onClose,
    onSubmit,
}: ModelCatalogVersionModalProps) {
    const submittingRef = useRef(false)
    const [submitting, setSubmitting] = useState(false)

    const busy = pending || submitting

    const formId =
        useId()

    const [draft, setDraft] =
        useState<CatalogDraft>(() =>
            createCatalogDraft(
                base,
                runtime,
            ),
        )

    const [initialDraftFingerprint] =
        useState(() => JSON.stringify(draft))

    const [formError, setFormError] =
        useState('')
    const [section, setSection] = useState<CatalogSection>('identity')
    const [advancedPricingOpen, setAdvancedPricingOpen] = useState(false)

    const isDirty =
        JSON.stringify(draft) !== initialDraftFingerprint

    const requestClose = () => {
        if (busy || submittingRef.current) {
            return
        }

        if (
            isDirty
            && !window.confirm(
                'Отменить несохранённые изменения версии каталога?',
            )
        ) {
            return
        }

        onClose()
    }

    const expectedPreviousVersion =
        catalogByKey.get(
            draft.modelKey
                .trim()
                .toLowerCase(),
        )?.version ?? 0

    const handleSubmit = async () => {
        // Блокировка синхронна: два submit до следующего рендера не создают два POST.
        if (busy || submittingRef.current) {
            return
        }

        submittingRef.current = true
        setSubmitting(true)
        setFormError('')

        try {
            const request =
                buildCatalogRequest(
                    draft,
                    expectedPreviousVersion,
                )

            await onSubmit(request)
        } catch (failure) {
            if (failure instanceof CatalogDraftValidationError) {
                setSection(failure.section)
                if (failure.section === 'pricing' && failure.message.includes('JSON')) {
                    setAdvancedPricingOpen(true)
                }
            }
            setFormError(
                failure instanceof Error
                    ? getApiErrorMessage(
                        failure,
                        failure.message,
                    )
                    : 'Не удалось сохранить модель.',
            )
        } finally {
            submittingRef.current = false
            setSubmitting(false)
        }
    }

    const modalFooter = (
        <div
            className="models-catalog-modal__footer"
        >
            <span className="models-catalog-modal__draft" role="status">
                {isDirty ? 'Есть несохранённые изменения' : 'Изменений нет'}
            </span>
            <button
                type="button"
                disabled={busy}
                onClick={requestClose}
            >
                Отмена
            </button>

            <button
                type="submit"
                form={formId}
                className="btn-primary"
                disabled={busy}
            >
                {busy
                    ? 'Сохраняем...'
                    : 'Сохранить версию'}
            </button>
        </div>
    )

    return (
        <Modal
            title={
                expectedPreviousVersion > 0
                    ? `Новая версия: ${draft.displayName.trim() || draft.modelKey.trim()}`
                    : 'Добавление модели в каталог'
            }
            footer={modalFooter}
            onClose={requestClose}
            closeDisabled={busy}

            /**
             * Форма содержит много полей. Закрытие по фону отключено,
             * чтобы не потерять несохранённые изменения.
             */
            closeOnBackdrop={false}

            /**
             * Escape и кнопка закрытия вызывают подтверждение, если
             * в черновике остались несохранённые изменения.
             */
            closeOnEscape={true}

            size="lg"
            className="models-catalog-modal"
            resize={CATALOG_MODAL_RESIZE}
            dragEnabled
        >
            <form
                id={formId}
                className="models-form models-catalog-form models-catalog-form--tabs"
                noValidate
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
                            {expectedPreviousVersion > 0
                                ? 'Новая неизменяемая версия модели'
                                : 'Новая модель в каталоге управления'}
                        </strong>

                        <p className="models-form__hint">
                            {expectedPreviousVersion > 0
                                ? `После сохранения появится версия ${expectedPreviousVersion + 1}. Предыдущая версия останется неизменной и доступной в истории.`
                                : 'После сохранения появится первая версия модели в каталоге. Физическое подключение провайдера выполняется отдельно в Runtime.'}
                        </p>
                    </div>
                </div>

                {formError && (
                    <ErrorState
                        message={formError}
                        variant="inline"
                    />
                )}

                <CatalogFormTabs formId={formId} selected={section} onSelect={setSection}
                    panels={{
                        identity: <CatalogIdentitySection draft={draft} setDraft={setDraft} base={base} />,
                        limits: <CatalogLimitsSection draft={draft} setDraft={setDraft} />,
                        pricing: <CatalogPricingSection draft={draft} setDraft={setDraft} active={section === 'pricing'}
                            advancedOpen={advancedPricingOpen} setAdvancedOpen={setAdvancedPricingOpen} />,
                        data: <CatalogDataSection draft={draft} setDraft={setDraft} />,
                        capabilities: <CatalogCapabilitiesSection draft={draft} setDraft={setDraft} />,
                    }} />
            </form>
        </Modal>
    )
}
