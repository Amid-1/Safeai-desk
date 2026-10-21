// frontend/src/components/admin/models/modelControlPlaneViews/RuntimeCard.tsx
// Назначение: показ текущего Runtime и проверки доступности.

import type {
    ModelCatalogEntry,
    RuntimeModelProbe,
    RuntimeModelStatus,
} from '../../../../api/modelApi'
import {formatDateTime} from '../../../../utils/format'
import {
    StatusPill,
    InfoHint,
    runtimeModeLabel,
    runtimeHealthLabel,
    runtimeCapabilities,
    pricingStatusLabel,
    retentionLabel,
    probeTone,
    probeLabel,
} from './viewPresentation'

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
                            Провайдер / модель
                            <InfoHint text="Фактическая конфигурация Runtime на сервере. Правила организации её не переключают." />
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
                            Режим Runtime
                            <InfoHint text="Текущий способ физического исполнения моделей. Это не правила организации." />
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
                    <dt>Проверка доступности</dt>
                    <dd>
                        {runtimeHealthLabel(runtime, probe)}
                    </dd>
                    <small>
                        {probe
                            ? `${formatDateTime(probe.checkedAt)} · ${probe.latencyMs.toLocaleString('ru-RU')} мс`
                            : 'Соединение отдельно ещё не проверялось'}
                    </small>
                </div>

                <div>
                    <dt>
                        <span className="models-label-row">
                            Лимиты токенов
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
                            Исполняемые возможности
                            <InfoHint text="Показываются только возможности, которые Runtime действительно умеет выполнять. Сведения каталога сами по себе не включают возможность." />
                        </span>
                    </dt>
                    <dd>{runtimeCapabilities(runtime)}</dd>
                    <small>Недоступные возможности блокируются до обращения к провайдеру</small>
                </div>

                <div>
                    <dt>Стоимость</dt>
                    <dd>
                        {pricingStatusLabel(runtime.pricingStatus)}
                    </dd>
                    <small>
                        {runtime.pricingVersion
                            ?? 'Версия стоимости не указана'}
                    </small>
                </div>

                <div>
                    <dt>Хранение данных</dt>
                    <dd>
                        {retentionLabel(runtime.dataRetentionStatus)}
                    </dd>
                    <small>
                        Сведения об использовании данных для обучения в Runtime сейчас не предоставляются
                    </small>
                </div>

                <div>
                    <dt>
                        <span className="models-label-row">
                            Запись в каталоге
                            <InfoHint text="Действующая версия каталога, совпадающая с фактическим провайдером и моделью Runtime." />
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
