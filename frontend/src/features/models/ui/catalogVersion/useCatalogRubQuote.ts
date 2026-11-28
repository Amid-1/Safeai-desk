// frontend/src/features/models/ui/catalogVersion/useCatalogRubQuote.ts
import {useEffect, useState} from 'react'
import {getRubBudgetQuote, moscowBudgetDate} from '../../../../entities/model/api/modelApi'
import type {RubBudgetQuote} from '../../../../entities/model/api/modelApi'

type QuoteState =
    | {status: 'loading'}
    | {status: 'ready'; quote: RubBudgetQuote}
    | {status: 'error'}

export function useCatalogRubQuote(active: boolean) {
    const [state, setState] = useState<QuoteState>({status: 'loading'})
    const [revision, setRevision] = useState(0)

    useEffect(() => {
        if (!active) return
        const request = new AbortController()
        void (async () => {
            try {
                const quote = await getRubBudgetQuote(request.signal)
                if (!request.signal.aborted) setState({status: 'ready', quote})
            } catch {
                if (!request.signal.aborted) setState({status: 'error'})
            }
        })()
        return () => request.abort()
    }, [active, revision])

    const quote = state.status === 'ready' && state.quote.quoteDate === moscowBudgetDate()
        ? state.quote : null
    return {
        quote,
        loading: state.status === 'loading',
        unavailable: state.status !== 'loading' && !quote,
        refresh: () => { setState({status: 'loading'}); setRevision(value => value + 1) },
    }
}
