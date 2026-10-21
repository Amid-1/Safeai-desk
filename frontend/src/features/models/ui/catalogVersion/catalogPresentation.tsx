// frontend/src/features/models/ui/catalogVersion/catalogPresentation.tsx
// ============================================================
// frontend/src/features/models/ui/catalogVersion/catalogPresentation.tsx
// ============================================================
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

export function SectionHeading({
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
