import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    getUserManagementRolePolicy,
    resolveManagedUserRole,
} from '../userManagementRolePolicy'


describe('user-management role policy', () => {
    it('lets SUPER_ADMIN choose USER or ADMIN', () => {
        const policy =
            getUserManagementRolePolicy([
                'SUPER_ADMIN',
            ])

        expect(policy.canManageUsers).toBe(true)
        expect(policy.canChooseRole).toBe(true)
        expect(policy.assignableRoles).toEqual([
            'USER',
            'ADMIN',
        ])
        expect(
            resolveManagedUserRole(
                ['SUPER_ADMIN'],
                'ADMIN',
            ),
        ).toBe('ADMIN')
    })

    it('forces ADMIN to create a USER', () => {
        const policy =
            getUserManagementRolePolicy([
                'ADMIN',
            ])

        expect(policy.canManageUsers).toBe(true)
        expect(policy.canChooseRole).toBe(false)
        expect(policy.assignableRoles).toEqual([
            'USER',
        ])
        expect(
            resolveManagedUserRole(
                ['ADMIN'],
                'ADMIN',
            ),
        ).toBe('USER')
    })

    it('rejects a USER actor at the policy boundary', () => {
        const policy =
            getUserManagementRolePolicy([
                'USER',
            ])

        expect(policy.canManageUsers).toBe(false)
        expect(policy.assignableRoles).toEqual([])
        expect(() =>
            resolveManagedUserRole(
                ['USER'],
                'USER',
            )
        ).toThrow(
            'Текущая роль не даёт права управлять пользователями.',
        )
    })
})
