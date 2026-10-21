// ============================================================
// frontend/src/components/admin/audit/auditFilters/auditFilterControls.tsx
// ============================================================
type PresetButtonProps = {
    label: string
    disabled: boolean
    active: boolean
    onClick: () => void
}

export function PresetButton({
    label,
    disabled,
    active,
    onClick,
}: PresetButtonProps) {
    return (
        <button
            type="button"
            className={active ? 'secondary-button audit-date-preset--active' : 'secondary-button'}
            aria-pressed={active}
            disabled={disabled}
            onClick={onClick}
        >
            {label}
        </button>
    )
}

export function DirectoryWarning({
    message,
}: {
    message: string
}) {
    return message ? (
        <div
            className={
                'audit-directory-warning'
            }
            role="status"
            aria-live="polite"
        >
            {message}
        </div>
    ) : null
}
