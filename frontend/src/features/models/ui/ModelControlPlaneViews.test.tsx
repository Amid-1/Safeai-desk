// frontend/src/features/models/ui/ModelControlPlaneViews.test.tsx
/* ============================================================
   frontend/src/features/models/ui/ModelControlPlaneViews.test.tsx
   ============================================================ */

import {fireEvent, render, screen} from '@testing-library/react'
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
} from '../../../test/fixtures/modelControlPlaneTestFixtures'

const runtime = createRuntimeModelStatus()
const baseEntry = createModelCatalogEntry()
const policy = createOrganizationModelPolicy()

describe('ModelControlPlaneViews semantics', () => {
    it('shows source, validity and local history scope in connection diagnostics', () => {
        render(<RuntimeCard runtime={createRuntimeModelStatus({
            provider: 'openai', model: 'test-model',
            dataAssurance: {noTraining: true, zeroDataRetention: false, source: 'CONTRACT-42',
                verifiedAt: '2026-11-17T12:00:00Z', expiresAt: '2026-12-17T12:00:00Z'},
        })} effectiveCatalog={[]} probe={null} probePending={false} canProbe={false} onProbe={vi.fn()} />)
        fireEvent.click(screen.getByRole('button', {name: 'Условия обработки данных'}))
        expect(screen.getByText(/Подтверждение подключения: CONTRACT-42/)).toBeVisible()
        expect(screen.getByText(/Действует до:/)).toBeVisible()
        expect(screen.getByText(/История чатов SafeAI Desk сохраняется/)).toBeVisible()
    })
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
                    organizationSearchStatus="idle"
                    organizationSearchError=""
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
            screen.getByText('Режим подключения'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Один фиксированный провайдер и модель'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Поддерживаемые возможности'),
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
                    organizationSearchStatus="idle"
                    organizationSearchError=""
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
            screen.getByText('Последняя и действующая · v1'),
        ).toBeInTheDocument()

        expect(
            screen.queryByText('Действующая · v1'),
        ).not.toBeInTheDocument()

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
            screen.getByText('Последняя версия пока не действует'),
        ).toBeInTheDocument()

        expect(
            screen.getByText(/Сейчас действует версия\s+1/),
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
