/* ============================================================
   frontend/src/components/admin/models/ModelControlPlaneViews.test.tsx
   ============================================================ */

import {render, screen} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import {
    CatalogTable,
    PolicyCard,
    RuntimeCard,
} from './ModelControlPlaneViews'
import {
    createModelCatalogEntry,
    createOrganizationModelPolicy,
    createRuntimeModelStatus,
} from './ModelControlPlaneTestFixtures'

const runtime = createRuntimeModelStatus()
const baseEntry = createModelCatalogEntry()
const policy = createOrganizationModelPolicy()

describe('ModelControlPlaneViews semantics', () => {
    it.each([
        [true, 'NOT_DECLARED', true],
        [false, 'NOT_DECLARED', false],
        [true, 'NOT_USED', false],
        [true, 'CONTRACTUAL_NO_TRAINING', false],
    ] as const)(
        'reports effective catalog training metadata (enabled=%s, status=%s)',
        (enabled, trainingUseStatus, warning) => {
            render(
                <PolicyCard
                    policy={{
                        ...policy,
                        configured: true,
                        enabled,
                        requireNoTraining: true,
                    }}
                    catalog={[createModelCatalogEntry({
                        trainingUseStatus: 'NOT_USED',
                    })]}
                    effectiveCatalog={[createModelCatalogEntry({
                        trainingUseStatus,
                    })]}
                    runtime={runtime}
                    organizationId={policy.organizationId}
                    organizationName="Demo"
                    isSuperAdmin={false}
                    organizationQuery=""
                    organizationResults={[]}
                    organizationSearchPending={false}
                    onOrganizationQueryChange={vi.fn()}
                    onOrganizationSearch={vi.fn()}
                    onOrganizationSelect={vi.fn()}
                    onEdit={vi.fn()}
                />,
            )

            expect(
                screen.queryByText(
                    'Требование запрета обучения не подтверждено',
                ) !== null,
            ).toBe(warning)
        },
    )

    it('separates runtime configuration, health and executable capabilities', () => {
        render(
            <RuntimeCard
                runtime={runtime}
                effectiveCatalog={[baseEntry]}
                probe={null}
                probePending={false}
                canProbe
                onProbe={vi.fn()}
            />,
        )

        expect(
            screen.getByText('Режим Runtime'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Один фиксированный провайдер и модель'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Исполняемые возможности'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Не выполнялась'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Не заявлено'),
        ).toBeInTheDocument()

        expect(
            screen.queryByText('Состояние конфигурации'),
        ).not.toBeInTheDocument()
    })

    it('explains empty allow-list and unconfigured policy without implying deny-all', () => {
        render(
            <PolicyCard
                policy={policy}
                catalog={[baseEntry]}
                effectiveCatalog={[baseEntry]}
                runtime={runtime}
                organizationId={policy.organizationId}
                organizationName="SafeAI Demo Organization"
                isSuperAdmin={false}
                organizationQuery=""
                organizationResults={[]}
                organizationSearchPending={false}
                onOrganizationQueryChange={vi.fn()}
                onOrganizationSearch={vi.fn()}
                onOrganizationSelect={vi.fn()}
                onEdit={vi.fn()}
            />,
        )

        expect(
            screen.getByText('SafeAI Demo Organization'),
        ).toBeInTheDocument()

        expect(
            screen.getByText(
                'Разрешённые: все допустимые · Запрещённые: 0',
            ),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Без дополнительных требований'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Не ограничены правилами'),
        ).toBeInTheDocument()
    })

    it('distinguishes latest, effective and lifecycle semantics in catalog', () => {
        render(
            <CatalogTable
                entries={[baseEntry]}
                effectiveEntries={[baseEntry]}
                runtime={runtime}
                canEdit
                onCreateVersion={vi.fn()}
                onOpenHistory={vi.fn()}
            />,
        )

        expect(
            screen.getByRole('columnheader', {
                name: 'Статус каталога',
            }),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Последняя версия'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Действующая версия'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Действует сейчас'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Активна'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Хранение: Не заявлено'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Обучение: Не заявлено'),
        ).toBeInTheDocument()
    })

    it('marks a latest non-effective snapshot as scheduled without browser-clock routing logic', () => {
        const scheduled = createModelCatalogEntry({
            id: '00000000-0000-0000-0000-000000000102',
            version: 2,
            effectiveFrom: '2099-01-01T00:00:00Z',
        })

        render(
            <CatalogTable
                entries={[scheduled]}
                effectiveEntries={[baseEntry]}
                runtime={runtime}
                canEdit={false}
                onCreateVersion={vi.fn()}
                onOpenHistory={vi.fn()}
            />,
        )

        expect(
            screen.getByText('Запланирована'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Сейчас действует версия 1'),
        ).toBeInTheDocument()
    })

    it('shows version history to read-only admin while keeping new-version mutation hidden', () => {
        render(
            <CatalogTable
                entries={[baseEntry]}
                effectiveEntries={[baseEntry]}
                runtime={runtime}
                canEdit={false}
                onCreateVersion={vi.fn()}
                onOpenHistory={vi.fn()}
            />,
        )

        expect(
            screen.getByRole('button', {
                name: 'История версий',
            }),
        ).toBeInTheDocument()

        expect(
            screen.queryByRole('button', {
                name: 'Новая версия',
            }),
        ).not.toBeInTheDocument()
    })
})
