// frontend/src/components/admin/models/modelPolicyModal/policyModalPresentation.tsx
// Пояснения и подписи редактирования правил моделей.

import type {BudgetEnforcement, ModelRouteReason} from '../../../../api/modelApi'

export function InfoHint({
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

export function budgetEnforcementLabel(
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


export function previewReasonLabel(
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
