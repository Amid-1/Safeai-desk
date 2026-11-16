// frontend/src/features/models/ui/modelControlPlaneViews/RuntimeCard.tsx
// Назначение: сводка подключённой модели и безопасная проверка соединения.

import type {
    ModelCatalogEntry,
    RuntimeModelProbe,
    RuntimeModelStatus,
} from '../../../../entities/model/api/modelApi'
import {formatDateTime} from '../../../../shared/lib/format'
import { StatusPill, InfoHint } from './viewPresentation'
import { runtimeModeLabel, runtimeHealthLabel, runtimeCapabilities, pricingStatusLabel, retentionLabel, probeTone, probeLabel } from './viewPresentationLabels'
import { ModelIcon } from '../ModelIcon'

export function RuntimeCard({
    runtime,
    effectiveCatalog,
    probe,
    probePending,
    canProbe,
    onProbe,
}: {
    runtime: RuntimeModelStatus
    effectiveCatalog: ModelCatalogEntry[]
    probe: RuntimeModelProbe | null
    probePending: boolean
    canProbe: boolean
    onProbe: () => void
}) {
    const catalogMatch =
        effectiveCatalog.find(
            (entry) =>
                (
                    entry.lifecycle === 'ACTIVE'
                    || entry.lifecycle === 'DEPRECATED'
                )
                && entry.provider === runtime.provider
                && entry.providerModelId === runtime.model,
        ) ?? null

    return (
        <section className="models-card models-card--runtime">
            <div className="models-card__heading">
                <div>
                    <span>ПОДКЛЮЧЕНИЕ</span>
                    <h2>Подключённая модель</h2>
                </div>

                <StatusPill
                    tone={runtime.enabled ? 'success' : 'danger'}
                    label={runtime.enabled ? 'Включена' : 'Отключена'}
                />
            </div>

            <dl className="models-kv-grid">
                <div>
                    <dt>
                        <span className="models-label-row">
                            <ModelIcon name="server" />Провайдер / модель
                            <InfoHint text="Модель, физически настроенная на сервере. Правила организации не изменяют это подключение." />
                        </span>
                    </dt>
                    <dd>
                        {runtime.provider}
                        {' / '}
                        {runtime.model}
                    </dd>
                    <small>Физическая конфигурация на сервере</small>
                </div>

                <div>
                    <dt>
                        <span className="models-label-row">
                            <ModelIcon name="network" />Режим подключения
                            <InfoHint text="Способ подключения модели к провайдеру. Он не зависит от правил организаций." />
                        </span>
                    </dt>
                    <dd>
                        {runtimeModeLabel(runtime.routingMode)}
                    </dd>
                    <small>
                        Динамический выбор физического провайдера и модели не используется
                    </small>
                </div>

                <div>
                    <dt><ModelIcon name="activity" />Проверка подключения</dt>
                    <dd>
                        {runtimeHealthLabel(runtime, probe)}
                    </dd>
                    <small>
                        {probe
                            ? `${formatDateTime(probe.checkedAt)} · ${probe.latencyMs.toLocaleString('ru-RU')} мс`
                            : runtime.healthStatus === 'NOT_PROBED'
                                ? 'Результат проверки отсутствует'
                                : 'Статус получен от сервера'}
                    </small>
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
                    <small>Недоступные возможности блокируются до обращения к провайдеру</small>
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
                </div>

                <div>
                    <dt><ModelIcon name="shield" />Условия хранения данных</dt>
                    <dd>
                        {retentionLabel(runtime.dataRetentionStatus)}
                    </dd>
                    <small>
                        Сведения об использовании данных для обучения здесь не предоставляются
                    </small>
                </div>

                <div>
                    <dt>
                        <span className="models-label-row">
                            <ModelIcon name="database" />Запись в каталоге
                            <InfoHint text="Действующая версия каталога, совпадающая с подключённым провайдером и моделью." />
                        </span>
                    </dt>
                    <dd>
                        {catalogMatch
                            ? `${catalogMatch.modelKey} · версия ${catalogMatch.version}`
                            : 'Действующей записи пока нет'}
                    </dd>
                </div>
            </dl>

            {canProbe && (
                <div className="models-runtime-probe">
                    <div>
                        <strong>Проверка соединения</strong>
                        <small>
                            Техническая проверка: без текста запроса, истории чата,
                            контекста базы знаний и пользовательских данных.
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
            {!canProbe && <p className="models-diagnostics-permission" role="note">
                Запуск технической проверки соединения доступен только SUPER_ADMIN. Текущая конфигурация доступна для просмотра.
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
