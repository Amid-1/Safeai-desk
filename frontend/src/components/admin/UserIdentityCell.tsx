// ============================================================
// frontend/src/components/admin/UserIdentityCell.tsx
// ============================================================
type UserIdentityCellProps = {
    fullName: string | null
    email: string
    roles: readonly string[]
}

function getInitials(
    fullName: string | null,
    email: string,
): string {
    const source = fullName?.trim() || email.trim()
    const parts = source.split(/\s+/).filter(Boolean)

    if (parts.length >= 2) {
        return `${parts[0][0]}${parts[1][0]}`.toUpperCase()
    }

    return source.slice(0, 2).toUpperCase()
}

function getTone(roles: readonly string[]): 'user' | 'admin' | 'super-admin' {
    if (roles.includes('SUPER_ADMIN')) {
        return 'super-admin'
    }

    if (roles.includes('ADMIN')) {
        return 'admin'
    }

    return 'user'
}

function UserIdentityCell({
                              fullName,
                              email,
                              roles,
                          }: UserIdentityCellProps) {
    const tone = getTone(roles)

    return (
        <div className={`user-identity user-identity--${tone}`}>
            <div
                className={`user-avatar user-avatar--${tone}`}
                aria-hidden="true"
            >
                {getInitials(fullName, email)}
            </div>

            <div className="user-identity__text">
                <strong className="user-identity__name">
                    {fullName?.trim() || 'Без имени'}
                </strong>

                <span className="user-identity__email">
                    {email}
                </span>
            </div>
        </div>
    )
}

export default UserIdentityCell
