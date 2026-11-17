// frontend/src/features/models/ui/modelControlPlaneViews/RuntimeCard.tsx
// Назначение: сводка подключённой модели и безопасная проверка соединения.

import type {
    ModelCatalogEntry,
    RuntimeModelProbe,
    RuntimeModelStatus,
} from '../../../../entities/model/api/modelApi'
import {formatDateTime} from '../../../../shared/lib/format'
import { useRef, useState } from 'react'
import { StatusPill, InfoHint } from './viewPresentation'
import { runtimeModeLabel, runtimeHealthLabel, runtimeCapabilities, pricingStatusLabel, retentionLabel, trainingUseLabel, probeTone, probeLabel } from './viewPresentationLabels'
import { ModelIcon } from '../ModelIcon'
import { DIAGNOSTICS_TARGET_IDS } from '../modelsNavigationState'

export function RuntimeCard({
    runtime,
    effectiveCatalog,
    probe,
    probePending,
    canProbe,
    onProbe,
    onOpenCatalogVersion,
}: {
    runtime: RuntimeModelStatus
    effectiveCatalog: ModelCatalogEntry[]
    probe: RuntimeModelProbe | null
    probePending: boolean
    canProbe: boolean
    onProbe: () => void
    onOpenCatalogVersion?: (entry: ModelCatalogEntry) => void
}) {
    const detailsRef = useRef<HTMLDetailsElement>(null)
    const probeRef = useRef<HTMLDivElement>(null)
    const [detailsOpen, setDetailsOpen] = useState(false)
    const [retentionOpen, setRetentionOpen] = useState(false)
    const catalogMatches =
        effectiveCatalog.filter(
            (entry) =>
                (
                    entry.lifecycle === 'ACTIVE'
                    || entry.lifecycle === 'DEPRECATED'
                )
                && entry.provider === runtime.provider
                && entry.providerModelId === runtime.model,
        )
    const catalogMatch = catalogMatches.length === 1 ? catalogMatches[0] : null
    const healthTone = probe ? probeTone(probe.status)
        : runtime.healthStatus === 'AVAILABLE' ? 'success' : runtime.healthStatus === 'UNAVAILABLE' ? 'danger' : 'warning'

    function toggleCharacteristics() {
        const details = detailsRef.current
        if (!details) return
        const open = !details.open
        details.open = open
        setDetailsOpen(open)
        if (open) details.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
    }

    function goToProbe() {
        // Navigation only. The explicitly labeled probe button is the sole trigger.
        probeRef.current?.focus({ preventScroll: true })
        probeRef.current?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
    }

    return (
        <section className="models-card models-card--runtime models-navigation-target" id={DIAGNOSTICS_TARGET_IDS.connection}
            tabIndex={-1} aria-label="Подключённая модель">
            <div className="models-card__heading">
                <div>
                    <h2>Подключённая модель</h2>
                </div>

                <StatusPill
                    tone={runtime.enabled ? 'success' : 'danger'}
                    label={runtime.enabled ? 'Включена в настройках' : 'Отключена'}
                />
            </div>

            <dl className="models-kv-grid models-runtime-essential">
                <div className="models-runtime-action-tile">
                    <dt>
                        <span className="models-label-row">
                            <ModelIcon name="server" />Провайдер / модель
                            <span className={`models-runtime-tile-indicator${detailsOpen ? ' models-runtime-tile-indicator--expanded' : ''}`}><ModelIcon name="chevronDown" /></span>
                        </span>
                    </dt>
                    <dd>
                        {runtime.provider}
                        {' / '}
                        {runtime.model}
                    </dd>
                    <small><button type="button" className="models-runtime-tile-action" aria-controls="models-runtime-characteristics"
                        aria-expanded={detailsOpen} onClick={toggleCharacteristics}>Характеристики модели</button></small>
                </div>

                <div className={canProbe ? 'models-runtime-action-tile' : undefined}>
                    <dt><ModelIcon name="activity" />Проверка соединения
                        {canProbe && <span className="models-runtime-tile-indicator"><ModelIcon name="arrow" /></span>}</dt>
                    <dd>
                        <StatusPill tone={healthTone} label={runtimeHealthLabel(runtime, probe)} />
                    </dd>
                    <small>
                        {probe
                            ? `${formatDateTime(probe.checkedAt)} · ${probe.latencyMs.toLocaleString('ru-RU')} мс`
                            : runtime.healthStatus === 'NOT_PROBED'
                                ? 'Доступность не подтверждена'
                                : 'Статус получен от сервера'}
                    </small>
                    {canProbe && <small><button type="button" className="models-runtime-tile-action"
                        onClick={goToProbe}>Перейти к проверке</button></small>}
                </div>

                <div className={catalogMatch && onOpenCatalogVersion ? 'models-runtime-action-tile' : undefined}>
                    <dt><ModelIcon name="database" />Запись в каталоге
                        {catalogMatch && onOpenCatalogVersion && <span className="models-runtime-tile-indicator"><ModelIcon name="history" /></span>}</dt>
                    <dd>{catalogMatch ? `${catalogMatch.modelKey} · v${catalogMatch.version}`
                        : catalogMatches.length > 1 ? `Совпадений: ${catalogMatches.length}` : 'Нет действующей записи'}</dd>
                    <small>{catalogMatch && onOpenCatalogVersion ? <button type="button" className="models-runtime-tile-action"
                        aria-haspopup="dialog" onClick={() => onOpenCatalogVersion(catalogMatch)}>Открыть историю версий</button>
                        : catalogMatch ? 'Совпадение с подключением' : catalogMatches.length > 1
                        ? 'Несколько действующих записей' : 'Требует внимания'}</small>
                </div>

                <div className="models-runtime-action-tile">
                    <dt><ModelIcon name="shield" />Хранение данных <span className="models-runtime-tile-indicator"><ModelIcon name="info" /></span></dt>
                    <dd>{retentionLabel(runtime.dataRetentionStatus)}</dd>
                    <small><button type="button" className="models-runtime-tile-action" aria-expanded={retentionOpen}
                        aria-controls="models-runtime-retention" onClick={() => setRetentionOpen(open => !open)}>Условия обработки данных</button></small>
                </div>
            </dl>

            <div id="models-runtime-retention" className="models-runtime-retention" hidden={!retentionOpen}>
                <strong>Хранение и использование данных</strong>
                {runtime.dataAssurance?.source === 'LOCAL_MOCK' ? <p>Локальный тестовый провайдер: данные не отправляются внешнему AI-провайдеру.</p>
                    : runtime.dataAssurance?.source ? <>
                        <p>Подтверждение подключения: {runtime.dataAssurance.source}. Запрет обучения: {runtime.dataAssurance.noTraining ? 'подтверждён' : 'не подтверждён'}; ZDR: {runtime.dataAssurance.zeroDataRetention ? 'подтверждён' : 'не подтверждён'}.</p>
                        {runtime.dataAssurance.verifiedAt && <p>Проверено: {formatDateTime(runtime.dataAssurance.verifiedAt)}{runtime.dataAssurance.expiresAt && ` · Действует до: ${formatDateTime(runtime.dataAssurance.expiresAt)}`}</p>}
                        <p>Подтверждение внесено оператором на основании условий провайдера. Это не автоматическая проверка соблюдения договора.</p>
                    </> : <p>Нет действующего подтверждения условий для конкретного серверного подключения.</p>}
                <p>История чатов SafeAI Desk сохраняется независимо от условий хранения у провайдера.</p>
                <p>Подключённая модель: {retentionLabel(runtime.dataRetentionStatus)}. Сведения о хранении сами по себе не подтверждают запрет обучения на данных запросов.</p>
                {catalogMatch ? <p>Действующая запись каталога (v{catalogMatch.version}): хранение — {retentionLabel(catalogMatch.retentionStatus)}, обучение — {trainingUseLabel(catalogMatch.trainingUseStatus)}. Это сведения каталога, а не результат проверки провайдера.</p>
                    : <p>Однозначное соответствие действующей записи каталога не установлено. Условия обучения по каталогу не подтверждены.</p>}
                <p>Требования организации к обработке данных проверяются сервером при принятии решения по запросу.</p>
            </div>

            <details ref={detailsRef} id="models-runtime-characteristics" className="models-runtime-details models-inline-details"
                onToggle={event => setDetailsOpen(event.currentTarget.open)}>
                <summary>Характеристики модели и стоимость <span className="models-details-caption">Стоимость: {pricingStatusLabel(runtime.pricingStatus)}</span></summary>
                <p className="models-runtime-characteristics-note">Модель, физически настроенная на сервере{runtime.provider === 'mock' ? ' (тестовый провайдер)' : ''}. Правила организации не изменяют это подключение.</p>
                <dl className="models-kv-grid">
                <div>
                    <dt><ModelIcon name="network" />Режим подключения</dt>
                    <dd>{runtimeModeLabel(runtime.routingMode)}</dd>
                </div>

                <div>
                    <dt>
                        <span className="models-label-row">
                            <ModelIcon name="gauge" />Лимиты токенов
                            <InfoHint text="Максимальный вход и выход физически настроенной модели." />
                        </span>
                    </dt>
                    <dd>
                        {runtime.maxInputTokens.toLocaleString('ru-RU')}
                        {' / '}
                        {runtime.maxOutputTokens.toLocaleString('ru-RU')}
                    </dd>
                    <small>вход / выход</small>
                </div>

                <div>
                    <dt>
                        <span className="models-label-row">
                            <ModelIcon name="layers" />Поддерживаемые возможности
                            <InfoHint text="Перечислены возможности подключённой модели. Запись в каталоге сама по себе не активирует дополнительные функции." />
                        </span>
                    </dt>
                    <dd>{runtimeCapabilities(runtime)}</dd>
                </div>

                <div>
                    <dt><ModelIcon name="wallet" />Стоимость</dt>
                    <dd>
                        {pricingStatusLabel(runtime.pricingStatus)}
                    </dd>
                    <small>
                        {runtime.pricingVersion
                            ?? 'Версия стоимости не указана'}
                    </small>
                    {(runtime.inputUsdPer1mTokens !== null || runtime.outputUsdPer1mTokens !== null) && <small>
                        Вход: {runtime.inputUsdPer1mTokens ?? 'не заявлено'} · выход: {runtime.outputUsdPer1mTokens ?? 'не заявлено'} USD / 1 млн токенов
                    </small>}
                </div>
                </dl>
            </details>

            {canProbe && (
                <div className="models-runtime-probe models-navigation-target" id={DIAGNOSTICS_TARGET_IDS.probe}
                    ref={probeRef} tabIndex={-1} role="group" aria-label="Управление проверкой соединения">
                    <div>
                        <strong>Проверка соединения</strong>
                        <small>
                            Без текста запроса, истории и пользовательских данных.
                        </small>
                    </div>

                    <button
                        type="button"
                        disabled={probePending}
                        onClick={onProbe}
                    >
                        {probePending
                            ? 'Проверяем...'
                            : 'Проверить доступность'}
                    </button>
                </div>
            )}
            {!canProbe && <p className="models-diagnostics-permission models-navigation-target" role="note"
                id={DIAGNOSTICS_TARGET_IDS.probe} tabIndex={-1}>
                Проверку запускает суперадминистратор.
            </p>}

            {probe && (
                <div
                    className="models-runtime-probe-result"
                    role="status"
                >
                    <StatusPill
                        tone={probeTone(probe.status)}
                        label={probeLabel(probe.status)}
                    />
                    <span>
                        {probe.message}
                        {probe.httpStatus !== null
                            ? ` · HTTP ${probe.httpStatus}`
                            : ''}
                    </span>
                </div>
            )}
        </section>
    )
}
