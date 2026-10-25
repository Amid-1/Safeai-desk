// frontend/src/features/models/ui/modelControlPlaneViews/viewPresentation.tsx
// JSX components only.
import type { StatusTone } from './viewPresentationLabels'

export function StatusPill({
    tone,
    label,
}: {
    tone: StatusTone
    label: string
}) {
    return (
        <span
            className={`models-status models-status--${tone}`}
        >
            {label}
        </span>
    )
}

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
