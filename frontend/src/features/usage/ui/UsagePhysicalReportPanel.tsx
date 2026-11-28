// frontend/src/features/usage/ui/UsagePhysicalReportPanel.tsx
import { useState } from 'react'
import { usePhysicalUsageReport } from '../controller/usePhysicalUsageReport'
import { formatCount, trimDecimal } from '../lib/adminUsagePresentation'
import { serializePhysicalUsageCsv } from './physicalUsageCsv'
import { downloadCsvText } from './usageCsv'

type Props = {
    dateFrom: string
    dateTo: string
    organizationId: string | null
    userId: string
    superAdmin: boolean
    scopedFilters: boolean
    filtersDirty: boolean
}

export function UsagePhysicalReportPanel({
    dateFrom, dateTo, organizationId, userId, superAdmin, scopedFilters, filtersDirty,
}: Props) {
    const [open, setOpen] = useState(false)
    const [revision, setRevision] = useState(0)
    const [exportError, setExportError] = useState(false)
    const blocked = !userId || (!superAdmin && !organizationId) || scopedFilters || filtersDirty
    const {report, loading, error} = usePhysicalUsageReport(
        open && !blocked, dateFrom, dateTo, organizationId, superAdmin, userId, revision,
    )
    const empty = report?.physicalAttempts === '0'
    const toggle = () => {
        setExportError(false)
        if (!open) setRevision(value => value + 1)
        setOpen(value => !value)
    }
    return <section className="usage-physical-panel" aria-label="Физические вызовы и сверка расходов">
        <div className="usage-physical-heading">
            <div>
                <h2>Физические вызовы и сверка расходов</h2>
                <p>Отдельно от сохранённых ответов: включая повторы и неоднозначные вызовы провайдеров.</p>
            </div>
            <button type="button" className="secondary-button" aria-expanded={open && !blocked}
                disabled={blocked} onClick={toggle}>{open && !blocked ? 'Свернуть' : 'Показать сверку'}</button>
        </div>
        {filtersDirty ? <p role="status">Сначала примените фильтры.</p>
            : scopedFilters ? <p role="status">
                Сверка доступна по периоду и организации. Снимите фильтры модели, пользователя и бюджета.
            </p> : null}
        {loading && <p role="status">Загрузка физического отчёта…</p>}
        {error && <div role="alert" className="usage-physical-warning">
            Физический отчёт недоступен.
            <button type="button" className="secondary-button" onClick={() => setRevision(value => value + 1)}>
                Повторить
            </button>
        </div>}
        {report && <>
            {report.fullyReconciled && report.plans !== '0' && <p role="status" className="usage-reconcile-ok">
                ✓ Сверка пройдена
            </p>}
            {report.costMismatchPlans !== '0' && <p role="alert" className="usage-reconcile-error">
                Обнаружены финансовые расхождения: {formatCount(report.costMismatchPlans)}
            </p>}
            <div className="usage-physical-heading">
                <p role="status">
                    {empty ? 'Нет физических вызовов за период.' : report.costComplete && report.usageComplete
                        ? 'Токены и расчётная стоимость учтены полностью для зарегистрированных попыток.'
                        : 'Учёт неполный: известная часть не равна полному расходу.'}
                </p>
                <button type="button" className="secondary-button"
                    onClick={() => {
                        setExportError(false)
                        try {
                            downloadCsvText(serializePhysicalUsageCsv(report), 'safeai-physical-usage.csv')
                        } catch {
                            setExportError(true)
                        }
                    }}>
                    Скачать CSV сверки
                </button>
            </div>
            {exportError && <p role="alert" className="usage-physical-warning">Не удалось создать CSV сверки.</p>}
            <dl className="usage-physical-grid">
                <div><dt>Физических попыток</dt><dd>{formatCount(report.physicalAttempts)}</dd></div>
                <div><dt>Известная стоимость</dt><dd>
                    {empty || report.costUnverifiableAttempts === report.physicalAttempts
                        ? '—' : `${trimDecimal(report.knownCostUsd)} USD`}</dd></div>
                <div><dt>Известные токены (вход / выход)</dt><dd>
                    {empty || report.usageUnverifiableAttempts === report.physicalAttempts ? '—' :
                    `${formatCount(report.knownInputTokens)} / ${formatCount(report.knownOutputTokens)}`}</dd></div>
                <div><dt>В процессе / неоднозначных</dt><dd>
                    {formatCount(report.startedAttempts)} / {formatCount(report.ambiguousAttempts)}</dd></div>
                <div><dt>Исполнено / не исполнено / отклонено</dt><dd>
                    {formatCount(report.knownExecutedAttempts)} / {formatCount(report.knownNotExecutedAttempts)}
                    {' / '}{formatCount(report.knownRejectedAttempts)}</dd></div>
                <div><dt>Без полной цены / токенов</dt><dd>
                    {formatCount(report.costUnverifiableAttempts)} / {formatCount(report.usageUnverifiableAttempts)}
                </dd></div>
                <div><dt>Планы: совпало / всего</dt><dd>
                    {formatCount(report.costMatchedPlans)} / {formatCount(report.plans)}</dd></div>
                <div><dt>Расхождений / непроверяемых</dt><dd
                    className={report.costMismatchPlans !== '0' ? 'usage-reconcile-error' : undefined}>
                    {formatCount(report.costMismatchPlans)} / {formatCount(report.unverifiablePlans)}</dd></div>
            </dl>
            {report.plans !== '0' && !report.fullyReconciled && <p className="usage-physical-warning">
                Сверка планов не подтверждена полностью.
            </p>}
            <p>Период UTC по старту попыток. Сверка включает полные выбранные планы, а не только их расходы
                внутри периода. Стоимость расчётная, не счёт провайдера. Не складывайте с итогами ответов AI.</p>
        </>}
    </section>
}
