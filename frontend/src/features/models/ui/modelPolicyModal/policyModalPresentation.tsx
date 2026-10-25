// frontend/src/features/models/ui/modelPolicyModal/policyModalPresentation.tsx
// Пояснения и подписи редактирования правил моделей.


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
