// frontend/src/features/models/ui/modelPolicyModal/useModelPolicyModalController.ts
// Контроллер черновика, проверки на сервере и безопасного сохранения.

import {useEffect, useId, useMemo, useRef, useState} from 'react'
import type {
    CreateOrganizationModelPolicyVersionRequest,
    ModelPolicyPreview,
} from '../../../../entities/model/api/modelApi'
import {getApiErrorMessage} from '../../../../shared/api/http'
import {buildPolicyRequest, createPolicyDraft} from '../modelControlPlaneSupport'
import type {PolicyDraft} from '../modelControlPlaneSupport'
import type {ModelKeySelectorInteractionState} from '../ModelPolicyModelSelectors'
import type {ModelPolicyModalProps} from './policyModalTypes'

type ModelPolicyModalControllerInput = Pick<ModelPolicyModalProps,
    'policy' | 'organizationId' | 'effectiveCatalog' | 'runtime' | 'pending' | 'onClose' | 'onPreview' | 'onSubmit'
>

export function useModelPolicyModalController({
    policy,
    organizationId,
    effectiveCatalog,
    runtime,
    pending,
    onClose,
    onPreview,
    onSubmit,
}: ModelPolicyModalControllerInput) {
    // Защищаем запросы от повторных действий до следующего обновления React.
    const previewInFlightRef = useRef(false)
    const submitInFlightRef = useRef(false)

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

    // Локально поясняем только метаданные каталога: решения о маршрутизации и блокировке принимает сервер.
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

    // Повторная версия политики делает прежний preview недействительным,
    // даже если текст черновика не изменился.
    const currentPreview =
        previewFingerprint === draftFingerprint
        && policyPreview?.organizationId === policy.organizationId
        && policyPreview.basePolicyVersion === policy.version
        && policyPreview.enabled === draft.enabled
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

            // Не отправляем правила, если отображаемая организация и загруженная
            // политика относятся к разным tenant.
            if (organizationId !== policy.organizationId) {
                setFormError('Организация формы не совпадает с загруженными правилами. Обновите данные.')
                return null
            }

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
        if (pending || previewInFlightRef.current || submitInFlightRef.current) {
            return
        }
        const request = buildCurrentRequest()
        if (!request) {
            return
        }

        const fingerprint = draftFingerprint
        previewInFlightRef.current = true
        setPreviewPending(true)
        setPreviewError('')
        setLockoutAcknowledged(false)

        try {
            const result = await onPreview(request)

            // Не допускаем использования проверки другой организации,
            // версии правил или состояния включения для текущего черновика.
            if (
                result.organizationId !== policy.organizationId
                || result.basePolicyVersion !== policy.version
                || result.enabled !== request.enabled
            ) {
                throw new Error('Сервер вернул проверку для другого состояния правил.')
            }

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
            previewInFlightRef.current = false
            setPreviewPending(false)
        }
    }

    const handleSubmit = async () => {
        if (pending || previewInFlightRef.current || submitInFlightRef.current) {
            return
        }
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

        submitInFlightRef.current = true
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
        } finally {
            submitInFlightRef.current = false
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

    return {
        formId, draft, setDraft, formError,
        policyPreview, currentPreview, previewPending, previewError,
        lockoutAcknowledged, setLockoutAcknowledged,
        allowSelectorState, setAllowSelectorState,
        denySelectorState, setDenySelectorState,
        accessControlRef, limitsControlRef, budgetControlRef, dataControlRef,
        activeSection, setActiveSection, focusShortcut,
        undeclaredTrainingModels, activationWarning,
        serverConfirmedTrainingLockout, serverTrainingRejectedModelKeys,
        isDirty, requestClose, handlePreview, handleSubmit,
        versionMessage, rulesStatusLabel, rulesStatusHint,
    }
}

export type ModelPolicyModalController = ReturnType<typeof useModelPolicyModalController>
