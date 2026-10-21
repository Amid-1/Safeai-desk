// frontend/src/components/knowledge/knowledgeMembers/useKnowledgeMembersController.ts
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
    addKnowledgeBaseMember, getKnowledgeBaseMembers, removeKnowledgeBaseMember,
    searchKnowledgeMemberCandidates, updateKnowledgeBaseMember,
} from '../../../api/knowledgeApi'
import type { KnowledgeBase, KnowledgeBaseAccessLevel, KnowledgeBaseMember, KnowledgeMemberCandidate } from '../../../api/knowledgeApi'
import { getApiErrorMessage } from '../../../api/http'
import { useAuth } from '../../../auth/useAuth'
import { MEMBER_PAGE_SIZE, ACCESS_LEVEL_LABEL, EMPTY_MEMBER_PAGE } from './knowledgeMembersConfig'

export function useKnowledgeMembersController(knowledgeBase: KnowledgeBase, onChanged: () => void) {
    const { currentUser } = useAuth()

    const mutationRef = useRef(false)
    const [success, setSuccess] = useState('')
    const [refreshKey, setRefreshKey] = useState(0)
    const [accessTarget, setAccessTarget] = useState<{
        member: KnowledgeBaseMember
        accessLevel: KnowledgeBaseAccessLevel
    } | null>(null)

    const [
        memberPage,
        setMemberPage,
    ] = useState(
        EMPTY_MEMBER_PAGE,
    )

    const [
        currentPage,
        setCurrentPage,
    ] = useState(0)

    const [
        loading,
        setLoading,
    ] = useState(true)

    const [
        error,
        setError,
    ] = useState('')

    const [
        query,
        setQuery,
    ] = useState('')

    const [
        candidates,
        setCandidates,
    ] = useState<
        KnowledgeMemberCandidate[]
    >([])

    const [
        candidateLoading,
        setCandidateLoading,
    ] = useState(false)

    const [
        busyUserId,
        setBusyUserId,
    ] = useState<
        string | null
    >(null)

    const [
        removeTarget,
        setRemoveTarget,
    ] = useState<
        KnowledgeBaseMember | null
    >(null)

    const memberIds =
        useMemo(
            () =>
                new Set(
                    memberPage.content.map(
                        (member) =>
                            member.userId,
                    ),
                ),
            [memberPage.content],
        )

    const loadMembers =
        useCallback(
            async (
                targetPage: number,
                signal?: AbortSignal,
            ) => {
                const response =
                    await getKnowledgeBaseMembers(
                        knowledgeBase.id,
                        targetPage,
                        MEMBER_PAGE_SIZE,
                        {
                            signal,
                        },
                    )

                // Ответ отменённого запроса не должен заменять актуальные ACL.
                if (signal?.aborted) return

                if (
                    response.totalPages > 0
                    && targetPage
                        >= response.totalPages
                ) {
                    setCurrentPage(
                        response.totalPages - 1,
                    )
                    return
                }

                setMemberPage(response)
            },
            [knowledgeBase.id],
        )

    useEffect(() => {
        const controller =
            new AbortController()

        async function load() {
            setLoading(true)
            setError('')

            try {
                await loadMembers(
                    currentPage,
                    controller.signal,
                )
            } catch (loadError) {
                if (
                    !controller.signal.aborted
                ) {
                    setError(
                        getApiErrorMessage(
                            loadError,
                            'Не удалось загрузить участников.',
                        ),
                    )
                }
            } finally {
                if (
                    !controller.signal.aborted
                ) {
                    setLoading(false)
                }
            }
        }

        void load()

        return () => {
            controller.abort()
        }
    }, [
        currentPage,
        loadMembers,
        refreshKey,
    ])

    const candidateSearchVersion = useRef(0)
    useEffect(() => () => { candidateSearchVersion.current += 1 }, [knowledgeBase.id])

    async function searchCandidates() {
        const requestVersion = ++candidateSearchVersion.current
        setCandidateLoading(true)
        setError('')

        try {
            const result =
                await searchKnowledgeMemberCandidates(
                    query,
                    20,
                )

            if (requestVersion === candidateSearchVersion.current) setCandidates(result)
        } catch (searchError) {
            if (requestVersion !== candidateSearchVersion.current) return
            setError(
                getApiErrorMessage(
                    searchError,
                    'Не удалось найти пользователей.',
                ),
            )
        } finally {
            if (requestVersion === candidateSearchVersion.current) setCandidateLoading(false)
        }
    }

    function beginMutation(userId: string): boolean {
        if (mutationRef.current) {
            return false
        }
        mutationRef.current = true
        setBusyUserId(userId)
        setError('')
        setSuccess('')
        return true
    }

    function endMutation() {
        mutationRef.current = false
        setBusyUserId(null)
    }

    async function reloadAfterMutation() {
        try {
            await loadMembers(currentPage)
        } catch (refreshError) {
            setError(getApiErrorMessage(
                refreshError,
                'Изменение сохранено, но список не обновился. Нажмите «Обновить».',
            ))
        }
        onChanged()
    }

    async function addCandidate(candidate: KnowledgeMemberCandidate) {
        if (!beginMutation(candidate.userId)) return
        try {
            await addKnowledgeBaseMember(knowledgeBase.id, {
                userId: candidate.userId,
                accessLevel: 'VIEWER',
            })
            setCandidates(current => current.filter(
                item => item.userId !== candidate.userId,
            ))
            setSuccess(`Участник ${candidate.email} добавлен с правами просмотра.`)
            await reloadAfterMutation()
        } catch (saveError) {
            setError(getApiErrorMessage(saveError, 'Не удалось добавить участника.'))
        } finally {
            endMutation()
        }
    }

    async function changeAccess(
        member: KnowledgeBaseMember,
        accessLevel: KnowledgeBaseAccessLevel,
    ): Promise<boolean> {
        if (accessLevel === member.accessLevel) return true
        if (!beginMutation(member.userId)) return false
        try {
            await updateKnowledgeBaseMember(knowledgeBase.id, member.userId, {
                accessLevel,
                expectedVersion: member.version,
            })
            setSuccess(`Права ${member.email} изменены: ${ACCESS_LEVEL_LABEL[accessLevel]}.`)
            await reloadAfterMutation()
            return true
        } catch (saveError) {
            setError(getApiErrorMessage(saveError, 'Не удалось изменить доступ.'))
            return false
        } finally {
            endMutation()
        }
    }

    function requestAccessChange(
        member: KnowledgeBaseMember,
        accessLevel: KnowledgeBaseAccessLevel,
    ) {
        if (accessLevel === member.accessLevel || mutationRef.current) return

        // Дополнительное подтверждение требуется при изменении собственных прав
        // и при снятии OWNER. Роль ADMIN отдельно контролирует сервер.
        const selfChange = member.userId === currentUser?.id
        const ownerReduction = member.accessLevel === 'OWNER'
        if (selfChange || ownerReduction) {
            setAccessTarget({ member, accessLevel })
            return
        }
        void changeAccess(member, accessLevel)
    }

    async function removeMember(member: KnowledgeBaseMember): Promise<boolean> {
        if (!beginMutation(member.userId)) return false
        try {
            await removeKnowledgeBaseMember(
                knowledgeBase.id,
                member.userId,
                member.version,
            )
            setRemoveTarget(null)
            setSuccess(`Персональный доступ ${member.email} удалён.`)

            const remaining = memberPage.totalElements - 1
            if (remaining > 0 && memberPage.content.length === 1 && currentPage > 0) {
                setCurrentPage(currentPage - 1)
                onChanged()
            } else {
                await reloadAfterMutation()
            }
            return true
        } catch (removeError) {
            setError(getApiErrorMessage(removeError, 'Не удалось удалить доступ.'))
            return false
        } finally {
            endMutation()
        }
    }

    const mutating = busyUserId !== null

    return {
        currentUser,
        accessTarget,
        setAccessTarget,
        removeTarget,
        setRemoveTarget,
        memberPage,
        currentPage,
        setCurrentPage,
        loading,
        error,
        query,
        setQuery,
        candidates,
        candidateLoading,
        busyUserId,
        memberIds,
        mutating,
        success,
        setRefreshKey,
        searchCandidates,
        addCandidate,
        requestAccessChange,
        changeAccess,
        removeMember,
    }
}
export type KnowledgeMembersController = ReturnType<typeof useKnowledgeMembersController>
