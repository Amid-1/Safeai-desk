// frontend/src/features/erasure/ui/RegistryWorkspace.tsx
import { useEffect, useState } from 'react'
import { useOutletContext, useSearchParams } from 'react-router-dom'
import { WorkspaceLink } from '../../../shared/ui/WorkspaceReturn'
import { loadRegistry, type RegistryPage, type Capabilities } from '../api/workspaceApi'
import { RESOURCE_NAMES, registryState, reviewTime } from '../review/reviewLabels'
import { EmptyState, LoadingState } from '../../../shared/ui/StateBlock'
import '../review/ErasureReview.css'

const REGISTRY_FILTERS = [['ALL','Все'],['ATTENTION','Требуют внимания'],['PENDING','На рассмотрении'],
    ['NEEDS_INFORMATION','Нужно уточнение'],['HELD','Удержания'],['APPROVED_REVIEW_ONLY','Согласованы'],
    ['REJECTED','Отказы'],['WITHDRAWN','Отозваны'],['SUBMITTED','Зарегистрированы'],['DRAFT','Черновики']] as const
export default function RegistryWorkspace({ review = false }: { review?: boolean }) {
    const [params,setParams] = useSearchParams()
    const workspace = useOutletContext<{capabilities:Capabilities|null;loading:boolean;failed:boolean}|null>()
    const allowed = !workspace || (review ? workspace.capabilities?.reviewQueue===true : workspace.capabilities?.platform===true||workspace.capabilities?.tenantRegistry===true)
    const platform = workspace?.capabilities?.platform===true && !review
    const tenant = !review && workspace?.capabilities?.tenantRegistry===true && !platform
    const filters = REGISTRY_FILTERS.filter(([k])=>tenant ? ['ALL','DRAFT','SUBMITTED','WITHDRAWN'].includes(k) : k!=='DRAFT' && (!review||k!=='SUBMITTED'))
    const state = filters.some(([k]) => k === params.get('state')) ? params.get('state')! : 'ALL'
    const query = (params.get('query') ?? '').slice(0,160)
    const rawPage = Number(params.get('page') ?? '0')
    const page = Number.isSafeInteger(rawPage) && rawPage >= 0 && rawPage <= 100000 ? rawPage : 0
    const [draft,setDraft] = useState(query)
    useEffect(() => setDraft(query), [query])
    const [result,setResult] = useState<RegistryPage|null>(null)
    const [error,setError] = useState(false)
    const [loading,setLoading] = useState(true)
    const [revision,setRevision] = useState(0)
    useEffect(() => {
        const ac = new AbortController(); setResult(null); setError(false); setLoading(true)
        if (!allowed) { setLoading(false); return () => ac.abort() }
        void loadRegistry(review,state,query,page,ac.signal).then(data => {
            if (!ac.signal.aborted) setResult(data)
        }).catch(() => { if (!ac.signal.aborted) setError(true) })
            .finally(() => { if (!ac.signal.aborted) setLoading(false) })
        return () => ac.abort()
    }, [review,state,query,page,revision,allowed])
    function update(nextState:string,nextQuery:string,nextPage:number) {
        if(nextState===state&&nextQuery===query&&nextPage===page){setRevision(v=>v+1);return}
        setResult(null); setLoading(true); setParams({state:nextState,query:nextQuery,page:String(nextPage)})
    }
    if(!allowed) return <section className="er2-page page"><h1>{review?'Очередь согласований':'Заявки'}</h1>
        <p role="status">{workspace?.loading?'Проверяем доступ…':workspace?.failed?'Не удалось проверить доступ.':review?'Для рассмотрения требуются отдельные полномочия.':'Реестр сейчас недоступен.'}</p></section>
    return <section className="er2-page page">
        <header className="er2-heading er2-heading--row"><h1>{review?'Очередь согласований':'Заявки'}</h1>
            <button className="er2-action" type="button" disabled={loading} onClick={()=>setRevision(v=>v+1)}>Обновить</button></header>
        {review && <p className="er2-muted">Согласование не запускает удаление данных.</p>}
        <div className="er2-card er2-queue-card">
            <form className="er2-panel-heading er2-search-form" onSubmit={e=>{e.preventDefault();update(state,draft.trim(),0)}}>
                <label className="er2-filter">Поиск <input type="search" maxLength={160} value={draft}
                    placeholder={platform?"Номер заявки или организация":"Номер заявки, имя или email"} onChange={e=>setDraft(e.target.value)}/></label>
                <button className="er2-action" type="submit">Найти</button>
            </form>
            <div className="er2-filter-tabs" aria-label="Фильтр по состоянию">{[
                {label:'Общие',codes:['ALL','ATTENTION','HELD']},
                {label:'Регистрация',codes:['DRAFT','SUBMITTED','WITHDRAWN']},
                {label:'Рассмотрение',codes:['PENDING','NEEDS_INFORMATION','APPROVED_REVIEW_ONLY','REJECTED']},
            ].map(group=>{
                const entries=filters.filter(([code])=>group.codes.includes(code))
                return entries.length>0&&<div key={group.label} className="er2-filter-group" role="group" aria-label={group.label}>
                    {entries.map(([code,label])=><button type="button" key={code} aria-pressed={state===code} className={state===code?'er2-tab er2-tab--active':'er2-tab'}
                        onClick={()=>update(code,query,0)}>{code==='SUBMITTED'&&platform?'Без рассмотрения':label}</button>)}
                </div>
            })}</div>
            {loading?<LoadingState message="Загружаем заявки…"/>:error?<div role="alert"><p>Не удалось загрузить заявки. Проверьте доступ или повторите попытку.</p>
                <button className="er2-action" type="button" onClick={()=>setRevision(v=>v+1)}>Повторить</button></div>:result&&<>
                {result.items.length===0?<EmptyState message="Заявок не найдено."/>:<div className="er2-table-wrap"><table className="er2-table">
                    <thead><tr><th>Заявка</th><th>Организация</th>{!platform&&<th>Заявитель</th>}<th>Регистрация</th>{!tenant&&<><th>Рассмотрение</th><th>Удержание</th></>}<th>Обновлена</th><th>Действие</th></tr></thead>
                    <tbody>{result.items.map(item=><tr key={item.requestId}><td><strong>{RESOURCE_NAMES[item.resourceType]??'Объект'}</strong>
                        <details><summary>Номер {item.requestId.slice(0,8)}</summary><small>{item.requestId}</small></details></td>
                        <td>{item.organization}</td>{!platform&&<td>{item.requester??'Не указан'}</td>}<td>{registryState(item.intakeState)}</td>{!tenant&&<td>{item.reviewState?registryState(item.reviewState):item.reviewVisible?'Не начато':'Недоступно'}</td>}
                        {!tenant&&<td>{item.holdActive?'Активно':'Нет'}</td>}<td>{reviewTime(item.updatedAt)}</td>
                        <td><WorkspaceLink to={review?`/admin/erasure/requests/${item.requestId}`:`/admin/erasure/registry/${item.requestId}`}>Открыть карточку</WorkspaceLink></td></tr>)}</tbody>
                </table></div>}
                {(page>0||result.hasNext)&&<div className="er2-panel-heading"><button className="er2-action" type="button" disabled={page===0} onClick={()=>update(state,query,page-1)}>Назад</button>
                    <span>Страница {page+1}</span><button className="er2-action" type="button" disabled={!result.hasNext} onClick={()=>update(state,query,page+1)}>Вперёд</button></div>}
            </>}
        </div>
    </section>
}
