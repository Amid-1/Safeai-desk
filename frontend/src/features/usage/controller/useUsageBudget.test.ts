// frontend/src/features/usage/controller/useUsageBudget.test.ts
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { getOrganizationModelPolicy } from '../../../entities/model/api/modelApi'
import type { OrganizationModelPolicy } from '../../../entities/model/api/modelApi'
import { useUsageBudget } from './useUsageBudget'

vi.mock('../../../entities/model/api/modelApi', () => ({getOrganizationModelPolicy: vi.fn()}))
const read = vi.mocked(getOrganizationModelPolicy)
const policy = { organizationId: 'one', monthlyBudgetUsd: '0', version: 2 } as OrganizationModelPolicy
beforeEach(() => read.mockReset())

it('does not request a platform-wide budget or substitute zero for missing policy', async () => {
    const {result, rerender} = renderHook(({org}) => useUsageBudget('admin', org, 0), {initialProps: {org: ''}})
    expect(result.current.scoped).toBe(false)
    expect(read).not.toHaveBeenCalled()
    read.mockRejectedValueOnce(new Error('denied'))
    rerender({org: 'one'})
    await waitFor(() => expect(result.current.error).toBe(true))
    expect(result.current.policy).toBeNull()
})

it('discards stale results, accepts an exact zero budget, and rejects a mismatched tenant', async () => {
    let resolve!: (value: OrganizationModelPolicy) => void
    read.mockImplementationOnce(() => new Promise(r => {resolve = r}))
    const {result, rerender} = renderHook(({org}) => useUsageBudget('admin', org, 0), {initialProps: {org: 'one'}})
    const signal = read.mock.calls[0][1]?.signal
    read.mockResolvedValueOnce({...policy, organizationId: 'two'})
    rerender({org: 'two'})
    expect(signal?.aborted).toBe(true)
    await waitFor(() => expect(result.current.policy?.organizationId).toBe('two'))
    expect(result.current.policy?.monthlyBudgetUsd).toBe('0')
    await act(async () => resolve(policy))
    expect(result.current.policy?.organizationId).toBe('two')
    read.mockResolvedValueOnce(policy)
    rerender({org: 'three'})
    expect(result.current.policy).toBeNull()
    await waitFor(() => expect(result.current.error).toBe(true))
})
