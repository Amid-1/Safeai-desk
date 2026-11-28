// frontend/src/features/models/ui/modelControlPlaneViews/RuntimeCard.tsx
// Назначение: сводка подключённой модели и безопасная проверка соединения.

import type {
    ModelCatalogEntry,
    RuntimeModelProbe,
    RuntimeModelStatus,
} from '../../../../entities/model/api/modelApi'
import {formatDateTime, formatUsd} from '../../../../shared/lib/format'
import { useRef, useState } from 'react'
import { StatusPill, InfoHint } from './viewPresentation'
import { runtimeModeLabel, runtimeHealthLabel, runtimeHealthTone, runtimeCapabilities, pricingStatusLabel, retentionLabel, trainingUseLabel, probeTone, probeLabel } from './viewPresentationLabels'
import { ModelIcon } from '../ModelIcon'
import { DIAGNOSTICS_TARGET_IDS } from '../modelsNavigationState'
import { runtimeDataGuarantees } from '../modelConfigurationReadiness'

export function RuntimeCard({
    runtime,
    effectiveCatalog,
    probe: receivedProbe,
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
    const probe = runtime.enabled && receivedProbe?.provider === runtime.provider && receivedProbe.model === runtime.model ? receivedProbe : null
    const healthTone = runtimeHealthTone(runtime, probe)
    const guarantees = runtimeDataGuarantees(runtime)

    function toggleCharacteristics() {
        const details = detailsRef.current
        if (!details) return
        const open = !details.open
        details.open = open
        setDetailsOpen(open)
        // Expanding inline must not scroll the toggle underneath the sticky
        // header or move it away from the pointer on mobile.
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
                    tone="neutral"
                    label={runtime.enabled ? 'Настроена' : 'Отключена'}
                />
            </div>

            <dl className="models-kv-grid models-runtime-essential">
                <div className="models-runtime-action-tile">
                    <dt>
                        <span className="models-label-row">
                            <ModelIcon name="server" />Провайдер / модель
                        </span>
                    </dt>
                    <dd>
                        {runtime.provider}
                        {' / '}
                        {runtime.model}
                    </dd>
                    <small><button type="button" className="models-runtime-tile-action" aria-controls="models-runtime-characteristics"
                        aria-expanded={detailsOpen} onClick={toggleCharacteristics}>Характеристики модели
                        <span className={`models-runtime-tile-indicator${detailsOpen ? ' models-runtime-tile-indicator--expanded' : ''}`} aria-hidden="true"><ModelIcon name="chevronDown" /></span>
                    </button></small>
                </div>

                <div className={canProbe ? 'models-runtime-action-tile' : undefined}>
                    <dt><ModelIcon name="activity" />Проверка соединения</dt>
                    <dd>
                        <StatusPill tone={healthTone} label={runtimeHealthLabel(runtime, probe)} />
                    </dd>
                    <small>
                        {!runtime.enabled ? 'Подключение отключено' : probe
                            ? `${formatDateTime(probe.checkedAt)} · ${probe.latencyMs.toLocaleString('ru-RU')} мс`
                            : runtime.healthStatus === 'NOT_PROBED'
                                ? 'Доступность не подтверждена'
                                : 'Статус получен от сервера'}
                    </small>
                    {canProbe && <small><button type="button" className="models-runtime-tile-action"
                        onClick={goToProbe}>Перейти к проверке
                        <span className="models-runtime-tile-indicator" aria-hidden="true"><ModelIcon name="arrow" /></span>
                    </button></small>}
                </div>

                <div className={catalogMatch && onOpenCatalogVersion ? 'models-runtime-action-tile' : undefined}>
                    <dt><ModelIcon name="database" />Запись в каталоге</dt>
                    <dd>{catalogMatch ? `${catalogMatch.modelKey} · v${catalogMatch.version}`
                        : catalogMatches.length > 1 ? `Совпадений: ${catalogMatches.length}` : 'Нет действующей записи'}</dd>
                    <small>{catalogMatch && onOpenCatalogVersion ? <button type="button" className="models-runtime-tile-action"
                        aria-haspopup="dialog" onClick={() => onOpenCatalogVersion(catalogMatch)}>Открыть историю версий
                        <span className="models-runtime-tile-indicator" aria-hidden="true"><ModelIcon name="history" /></span>
                    </button>
                        : catalogMatch ? 'Совпадение с подключением' : catalogMatches.length > 1
                        ? 'Несколько действующих записей' : 'Требует внимания'}</small>
                </div>

                <div className="models-runtime-action-tile">
                    <dt><ModelIcon name="shield" />Хранение данных</dt>
                    <dd>{retentionLabel(runtime.dataRetentionStatus)}</dd>
                    <small><button type="button" className="models-runtime-tile-action" aria-expanded={retentionOpen}
                        aria-controls="models-runtime-retention" onClick={() => setRetentionOpen(open => !open)}>Условия обработки данных
                        <span className="models-runtime-tile-indicator" aria-hidden="true"><ModelIcon name="info" /></span>
                    </button></small>
                </div>
            </dl>

            <div id="models-runtime-retention" className="models-runtime-retention" hidden={!retentionOpen}>
                <strong>Хранение и использование данных</strong>
                {runtime.provider === 'mock' && runtime.dataAssurance?.source === 'LOCAL_MOCK' ? <p>Локальный тестовый провайдер: данные не отправляются внешнему AI-провайдеру.</p>
                    : runtime.dataAssurance?.source ? <>
                        <p>Подтверждение подключения: {runtime.dataAssurance.source}. Запрет обучения: {guarantees.noTraining ? 'подтверждён' : 'не подтверждён'}; нулевое хранение: {guarantees.zeroDataRetention ? 'подтверждено' : 'не подтверждено'}.</p>
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
                            <ModelIcon name="gauge" /><span>Лимиты токенов</span>
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
                            <ModelIcon name="layers" /><span>Поддерживаемые возможности</span>
                            <InfoHint text="Перечислены возможности подключённой модели. Запись в каталоге сама по себе не активирует дополнительные функции." />
                        </span>
                    </dt>
                    <dd>{runtimeCapabilities(runtime)}</dd>
                </div>

                <div>
                    <dt><ModelIcon name="wallet" />Стоимость по каталогу</dt>
                    <dd>
                        {pricingStatusLabel(runtime.pricingStatus)}
                    </dd>
                    <small>
                        {runtime.pricingVersion
                            ? `Версия тарифа: ${runtime.pricingVersion}`
                            : runtime.pricingStatus === 'UNPRICED'
                                ? 'Нет единственного подтверждённого действующего тарифа'
                                : 'Тариф подтверждён записью каталога'}
                    </small>
                    {(runtime.inputUsdPer1mTokens !== null || runtime.outputUsdPer1mTokens !== null) && <small>
                        Вход: {formatUsd(runtime.inputUsdPer1mTokens)} · выход: {formatUsd(runtime.outputUsdPer1mTokens)} / 1 млн токенов
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
                        disabled={probePending || !runtime.enabled}
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
                    <span>Результат последней проверки соединения, не разрешение на AI-запрос.</span>
                    {canProbe && probe.httpStatus !== null && <details>
                        <summary>Технические сведения проверки</summary>
                        <span>HTTP {probe.httpStatus}</span>
                    </details>}
                </div>
            )}
        </section>
    )
}
