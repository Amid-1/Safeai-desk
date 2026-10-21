// ============================================================
// frontend/src/components/admin/models/ModelCatalogVersionModal.tsx
// ============================================================
import { useId, useRef, useState } from 'react'
import type {
    CreateModelCatalogVersionRequest,
    ModelCatalogEntry,
    RuntimeModelStatus,
} from '../../../api/modelApi'
import Modal from '../../Modal'
import type { ModalResizeOptions } from '../../Modal'
import { getApiErrorMessage } from '../../../api/http'
import { ErrorState } from '../../StateBlock'
import {
    buildCatalogRequest,
    createCatalogDraft,
} from './modelControlPlaneSupport'
import type { CatalogDraft } from './modelControlPlaneSupport'
import { CatalogIdentitySection } from './catalogVersion/CatalogIdentitySection'
import { CatalogLimitsSection } from './catalogVersion/CatalogLimitsSection'
import { CatalogDataSection } from './catalogVersion/CatalogDataSection'
import { CatalogCapabilitiesSection } from './catalogVersion/CatalogCapabilitiesSection'
import { CatalogPricingSection } from './catalogVersion/CatalogPricingSection'
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
        initialWidth: 1480,
        initialHeight: 920,

        minWidth: 640,
        minHeight: 480,

        scaleContent: true,
        minScale: 0.72,
        maxScale: 1.08,
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

                <div
                    className="models-catalog-form__layout"
                >
                    <CatalogIdentitySection draft={draft} setDraft={setDraft} base={base} />

                    <CatalogLimitsSection draft={draft} setDraft={setDraft} />

                    <CatalogDataSection draft={draft} setDraft={setDraft} />

                    <CatalogCapabilitiesSection draft={draft} setDraft={setDraft} />

                    <CatalogPricingSection draft={draft} setDraft={setDraft} />
                </div>
            </form>
        </Modal>
    )
}
