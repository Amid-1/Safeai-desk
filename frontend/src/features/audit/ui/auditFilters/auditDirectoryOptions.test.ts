// frontend/src/features/audit/ui/auditFilters/auditDirectoryOptions.test.ts
// ============================================================
// frontend/src/features/audit/ui/auditFilters/auditDirectoryOptions.test.ts
// ============================================================
import { describe, expect, it } from 'vitest'
import {
    buildVisibleActors,
    buildVisibleOrganizations,
    formatActorOption,
    formatOrganizationOption,
} from './auditDirectoryOptions'

const ACTOR_ID = '11111111-1111-4111-8111-111111111111'
const ORG_ID = '22222222-2222-4222-8222-222222222222'

describe('Audit directory options', () => {
    it('не показывает варианты без UUID и сохраняет выбранный UUID после смены справочника', () => {
        expect(buildVisibleActors([
            { actorUserId: null, actorOrganizationId: null, actorEmail: 'deleted@test.ru', actorDisplayName: null },
        ], ACTOR_ID)).toEqual([{
            actorUserId: ACTOR_ID, actorOrganizationId: null, actorEmail: null, actorDisplayName: null,
        }])
    })

    it('устраняет дубли UUID, сохраняя актуальную запись', () => {
        const result = buildVisibleActors([
            { actorUserId: ACTOR_ID, actorOrganizationId: null, actorEmail: 'старый@test.ru', actorDisplayName: null },
            { actorUserId: ACTOR_ID, actorOrganizationId: null, actorEmail: 'новый@test.ru', actorDisplayName: 'Иванов' },
        ], ACTOR_ID)
        expect(result).toHaveLength(1)
        expect(formatActorOption(result[0])).toContain('новый@test.ru — Иванов')
    })

    it('сохраняет выбранную организацию и форматирует неизвестное имя', () => {
        const result = buildVisibleOrganizations([], ORG_ID)
        expect(result).toEqual([{ targetOrganizationId: ORG_ID, targetOrganizationName: null }])
        expect(formatOrganizationOption(result[0])).toContain('Название недоступно')
    })

    it('сортирует доступные варианты без потери записей', () => {
        const first = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
        const second = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
        const result = buildVisibleOrganizations([
            { targetOrganizationId: second, targetOrganizationName: 'Янтарь' },
            { targetOrganizationId: first, targetOrganizationName: 'Альфа' },
        ], '')
        expect(result.map((item) => item.targetOrganizationId)).toEqual([first, second])
    })

    it('не раскрывает пустые поля актёра вместо идентификатора', () => {
        expect(formatActorOption({
            actorUserId: ACTOR_ID, actorOrganizationId: null,
            actorEmail: null, actorDisplayName: null,
        })).toBe(`Инициатор без имени (${ACTOR_ID})`)
    })
})
