// frontend/src/features/models/ui/modelPolicyModal/policyPreviewStatus.ts
// UI status is derived from the server result; it is not a routing authorization.
import type {ModelPolicyPreview} from '../../../../entities/model/api/modelApi'

export type PolicyPreviewState = 'idle' | 'pending' | 'stale' | 'error' | 'available' | 'restricted' | 'blocked'

export function policyPreviewState(input: {
    currentPreview: ModelPolicyPreview | null
    policyPreview: ModelPolicyPreview | null
    previewPending: boolean
    previewError: string
}): PolicyPreviewState {
    if (input.previewPending) return 'pending'
    if (input.previewError) return 'error'
    const result = input.currentPreview
    if (!result) return input.policyPreview ? 'stale' : 'idle'
    if (result.wouldLockOutOrganization || (result.enabled && result.executableModelCount === 0)) return 'blocked'
    if (result.automaticOutcome !== 'ALLOWED' || result.models.some(model => model.outcome !== 'ALLOWED')) return 'restricted'
    return 'available'
}

export const POLICY_PREVIEW_LABEL: Readonly<Record<PolicyPreviewState, string>> = {
    idle: 'Не проверено',
    pending: 'Проверяем',
    stale: 'Требуется повторная проверка',
    error: 'Ошибка проверки',
    available: 'Доступно по текущим правилам',
    restricted: 'Есть ограничения',
    blocked: 'Доступ заблокирован',
}
