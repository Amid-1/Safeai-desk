// frontend/src/features/models/ui/modelPolicyModal/useModelPolicyModalController.ts
// Контроллер черновика, проверки на сервере и безопасного сохранения.

import {useCallback, useEffect, useId, useMemo, useRef, useState} from 'react'
import type {Dispatch, SetStateAction} from 'react'
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
    // Исходная версия фиксируется на весь сеанс редактирования. Нельзя
    // сохранять старый черновик под новой optimistic-lock версией после
    // фонового обновления политики другой административной операцией.
    const openedPolicyVersionRef = useRef(policy.version)
    const openedPolicyIdRef = useRef(policy.id)
    const policyChangedExternally =
        openedPolicyVersionRef.current !== policy.version
        || openedPolicyIdRef.current !== policy.id

    // Защищаем запросы от повторных действий до следующего обновления React.
    const previewInFlightRef = useRef(false)
    const submitInFlightRef = useRef(false)

    const formId =
        useId()

    // Every real edit advances a monotonic revision, including A -> B -> A.
    // Comparing JSON alone would incorrectly revive an obsolete server preview.
    const [draftState, setDraftState] = useState(() => ({
        value: createPolicyDraft(policy),
        revision: 0,
    }))
    const draft = draftState.value
    const draftRevision = draftState.revision
    const setDraft: Dispatch<SetStateAction<PolicyDraft>> = useCallback((update) => {
        setDraftState((previous) => {
            const next = typeof update === 'function' ? update(previous.value) : update
            return JSON.stringify(next) === JSON.stringify(previous.value)
                ? previous
                : {value: next, revision: previous.revision + 1}
        })
    }, [])

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
    const [verifiedDraftRevision, setVerifiedDraftRevision] = useState<number | null>(null)

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
        useState<'access' | 'limits' | 'budget' | 'data' | 'preview'>('access')

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

    // A server preview is valid only for the exact runtime and effective catalog
    // used to request it. Reordering catalog entries must not invalidate it.
    const previewContextFingerprint = JSON.stringify({
        runtime,
        effectiveCatalog: [...effectiveCatalog].sort((a, b) =>
            a.modelKey.localeCompare(b.modelKey) || a.version - b.version,
        ),
    })
    const [verifiedContextFingerprint, setVerifiedContextFingerprint] = useState('')
    const contextGenerationRef = useRef(0)

    // Повторная версия политики делает прежний preview недействительным,
    // даже если текст черновика не изменился.
    const currentPreview =
        previewFingerprint === draftFingerprint
        && verifiedDraftRevision === draftRevision
        && verifiedContextFingerprint === previewContextFingerprint
        && !policyChangedExternally
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
    }, [draftRevision, previewContextFingerprint])

    // A changed runtime/catalog invalidates the issued preview permanently,
    // even if those props later happen to return to their previous values.
    // The exact fingerprint check above closes the render-to-effect gap.
    useEffect(() => {
        contextGenerationRef.current += 1
        // Keep the previous response as a visible *stale* result, never current.
        setVerifiedDraftRevision(null)
        setVerifiedContextFingerprint('')
    }, [previewContextFingerprint])

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

    const buildCurrentRequest =
        (): CreateOrganizationModelPolicyVersionRequest | null => {
            setFormError('')

            if (policyChangedExternally) {
                setActiveSection('preview')
                setFormError(
                    'Правила организации изменились после открытия окна. '
                    + 'Чтобы не перезаписать чужие изменения, закройте окно '
                    + 'и откройте актуальную версию правил заново.',
                )
                return null
            }

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
                setActiveSection('access')
                setFormError(
                    'Исправьте ошибки в списках моделей перед сохранением.',
                )
                return null
            }

            if (
                allowSelectorState.hasPendingInput
                || denySelectorState.hasPendingInput
            ) {
                setActiveSection('access')
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
        const revision = draftRevision
        const contextFingerprint = previewContextFingerprint
        const contextGeneration = contextGenerationRef.current
        setActiveSection('preview')
        previewInFlightRef.current = true
        setPreviewPending(true)
        // Hide the previous decision while a new server evaluation is in flight.
        setPolicyPreview(null)
        setPreviewFingerprint('')
        setVerifiedDraftRevision(null)
        setVerifiedContextFingerprint('')
        setPreviewError('')
        setLockoutAcknowledged(false)

        // Единый путь отказа: неверный ответ сервера и сбой запроса
        // одинаково аннулируют preview, не разрешая сохранение по нему.
        const invalidatePreview = (message: string): void => {
            setPolicyPreview(null)
            setPreviewFingerprint('')
            setVerifiedDraftRevision(null)
            setVerifiedContextFingerprint('')
            setPreviewError(message)
        }

        try {
            const result = await onPreview(request)

            // Не допускаем использования проверки другой организации,
            // версии правил или состояния включения для текущего черновика.
            if (contextGenerationRef.current !== contextGeneration) {
                invalidatePreview('Подключение или каталог изменились во время проверки. Запустите проверку заново.')
                return
            }

            if (
                result.organizationId !== policy.organizationId
                || result.basePolicyVersion !== policy.version
                || result.enabled !== request.enabled
                || result.runtimeProvider !== runtime.provider
                || result.runtimeModel !== runtime.model
            ) {
                // Это ошибка контракта ответа, а не исключение HTTP-запроса.
                // Не бросаем исключение, которое тут же поймает этот catch.
                invalidatePreview('Сервер вернул проверку для другого состояния правил.')
                return
            }

            setPolicyPreview(result)
            setPreviewFingerprint(fingerprint)
            setVerifiedDraftRevision(revision)
            setVerifiedContextFingerprint(contextFingerprint)
        } catch (failure) {
            invalidatePreview(
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
            setActiveSection('preview')
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
            setActiveSection('preview')
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
                `Действующие правила организации: версия ${policy.version}. `
                + `При успешном сохранении будет создана версия ${policy.version + 1}. `
                + 'Предыдущая версия сохранится в истории.'
            )
            : (
                'Это первая настройка правил. '
                + 'При успешном сохранении будет создана версия 1.'
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
        formId, draft, setDraft, formError, pending,
        policyPreview, currentPreview, previewPending, previewError,
        lockoutAcknowledged, setLockoutAcknowledged,
        allowSelectorState, setAllowSelectorState,
        denySelectorState, setDenySelectorState,
        accessControlRef, limitsControlRef, budgetControlRef, dataControlRef,
        activeSection, setActiveSection, policyChangedExternally,
        undeclaredTrainingModels, activationWarning,
        serverConfirmedTrainingLockout, serverTrainingRejectedModelKeys,
        isDirty, requestClose, handlePreview, handleSubmit,
        versionMessage, rulesStatusLabel, rulesStatusHint,
    }
}

export type ModelPolicyModalController = ReturnType<typeof useModelPolicyModalController>
