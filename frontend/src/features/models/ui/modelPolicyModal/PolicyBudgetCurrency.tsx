// frontend/src/features/models/ui/modelPolicyModal/PolicyBudgetCurrency.tsx
// Currency selector. Switching display preserves the exact USD ceiling.
import {useEffect, useRef, useState} from 'react'
import {getRubBudgetQuote, moscowBudgetDate, rubToUsd, usdToRub} from '../../../../entities/model/api/modelApi'
import {getApiErrorMessage} from '../../../../shared/api/http'
import {parsePolicyMoney} from '../modelControlPlane/policyNumericFields'
import type {ModelPolicyModalController} from './useModelPolicyModalController'

export function PolicyBudgetCurrency({control, pending, onLoadingChange}: {control: ModelPolicyModalController; pending: boolean; onLoadingChange: (value: boolean) => void}) {
    const {draft, setDraft} = control
    const quote = draft.rubQuote
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState('')
    const active = useRef<AbortController | null>(null)
    useEffect(() => () => {active.current?.abort(); onLoadingChange(false)}, [onLoadingChange])

    const changeToRub = async () => {
        if (active.current) return
        setError('')
        const request = new AbortController()
        active.current = request
        setLoading(true)
        onLoadingChange(true)
        try {
            const fresh = await getRubBudgetQuote(request.signal)
            if (request.signal.aborted) return
            // Refreshing RUB keeps the original amounts; switching USD keeps
            // the exact existing USD ceilings or rejects an unsafe conversion.
            const max = quote ? draft.maxRequestCostUsd
                : usdToRub(parsePolicyMoney(draft.maxRequestCostUsd, 'Лимит'), fresh.rubPerUsd) ?? ''
            const monthly = quote ? draft.monthlyBudgetUsd
                : usdToRub(parsePolicyMoney(draft.monthlyBudgetUsd, 'Бюджет'), fresh.rubPerUsd) ?? ''
            setDraft(current => {
                if (current.maxRequestCostUsd !== draft.maxRequestCostUsd
                    || current.monthlyBudgetUsd !== draft.monthlyBudgetUsd
                    || current.rubQuote !== quote) return current
                return {...current, rubQuote: fresh, maxRequestCostUsd: max, monthlyBudgetUsd: monthly}
            })
        } catch (failure) {
            if (!request.signal.aborted) setError(getApiErrorMessage(failure, 'Не удалось получить курс. Попробуйте позже.'))
        } finally {
            if (!request.signal.aborted) {
                active.current = null
                setLoading(false)
                onLoadingChange(false)
            }
        }
    }

    const changeToUsd = () => {
        if (!quote) return
        setError('')
        try {
            const max = rubToUsd(parsePolicyMoney(draft.maxRequestCostUsd, 'Лимит'), quote.rubPerUsd)
            const monthly = rubToUsd(parsePolicyMoney(draft.monthlyBudgetUsd, 'Бюджет'), quote.rubPerUsd)
            setDraft(current => ({...current, rubQuote: undefined,
                maxRequestCostUsd: max ?? '', monthlyBudgetUsd: monthly ?? ''}))
        } catch (failure) {
            setError(failure instanceof Error ? failure.message : 'Проверьте суммы.')
        }
    }

    const historical = quote && quote.quoteDate !== moscowBudgetDate()
    return <div className="models-policy-fx models-policy-form__full-row">
        <div className="models-policy-fx__toolbar">
            <div>
                <strong>Валюта ввода бюджета</strong>
                <small>Расчёты и контроль расходов всегда выполняются в USD.</small>
            </div>
            <div role="group" aria-label="Валюта ввода" className="models-policy-fx__switch">
                <button type="button" aria-pressed={!quote} disabled={pending || loading || (!!quote && !!(control.numericErrors.maxRequestCostUsd || control.numericErrors.monthlyBudgetUsd))}
                        onClick={changeToUsd}>USD · Доллары</button>
                <button type="button" aria-pressed={!!quote} disabled={pending || loading || !!quote}
                        onClick={() => {void changeToRub()}}>RUB · Рубли</button>
            </div>
        </div>
        {quote && <div className="models-policy-fx__quote">
            <div>
                <span className="models-policy-fx__status">{historical ? 'Сохранённый курс' : 'Курс на сегодня'}</span>
                <strong>1 USD = {quote.rubPerUsd.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '').replace('.', ',')} RUB</strong>
                <small>Банк России · действует с {quote.effectiveDate.split('-').reverse().join('.')} · получен {quote.quoteDate.split('-').reverse().join('.')}</small>
            </div>
            <button type="button" disabled={pending || loading} onClick={() => {void changeToRub()}}>
                {loading ? 'Получаем…' : 'Получить актуальный курс'}
            </button>
            <p>При смене курса рублёвые суммы сохраняются, эквивалент USD пересчитывается.
                При сохранении фиксируются обе суммы и курс; в дальнейшем бюджет не пересчитывается автоматически.</p>
        </div>}
        {loading && <small role="status">Получаем официальный курс Банка России…</small>}
        {error && <p role="alert" className="models-policy-fx__error">{error}</p>}
    </div>
}
