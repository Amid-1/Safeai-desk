// frontend/src/pages/ErasureHistoryPage.tsx
import { useEffect, useState } from 'react'
import { useOutletContext, useSearchParams } from 'react-router-dom'
import { useAuth } from '../features/auth/state/useAuth'
import { loadWorkspaceHistory, type HistoryItem, type Capabilities } from '../features/erasure/api/workspaceApi'
import { EVENT_LABELS, reviewTime } from '../features/erasure/review/reviewLabels'
import { WorkspaceLink, WorkspaceReturn } from '../shared/ui/WorkspaceReturn'
import '../features/erasure/review/ErasureReview.css'
export default function ErasureHistoryPage() {
    const {currentUser}=useAuth()
    const platform=currentUser?.roles.includes('SUPER_ADMIN')===true
    const [params,setParams]=useSearchParams()
    const workspace=useOutletContext<{capabilities:Capabilities|null;loading:boolean;failed:boolean}|null>()
    const allowed=!workspace||workspace.capabilities?.platform===true||workspace.capabilities?.reviewQueue===true
    const query=(params.get('query')??'').slice(0,160)
    const raw=Number(params.get('page')??'0')
    const page=Number.isSafeInteger(raw)&&raw>=0&&raw<=100000?raw:0
    const [draft,setDraft]=useState(query)
    useEffect(()=>setDraft(query),[query])
    const [data,setData]=useState<{items:HistoryItem[];hasNext:boolean}|null>(null)
    const [error,setError]=useState(false)
    const [revision,setRevision]=useState(0)
    useEffect(()=>{
        const ac=new AbortController();setData(null);setError(false)
        if(!allowed)return ()=>ac.abort()
        void loadWorkspaceHistory(query,page,ac.signal).then(v=>{if(!ac.signal.aborted)setData(v)}).catch(()=>{if(!ac.signal.aborted)setError(true)})
        return()=>ac.abort()
    },[query,page,revision,allowed])
    function update(q:string,p:number){if(q===query&&p===page){setRevision(v=>v+1);return}setParams({query:q,page:String(p)})}
    if(!allowed)return <section className="er2-page page"><h1>История изменений</h1><p role="status">{workspace?.loading?'Проверяем доступ…':'История недоступна.'}</p></section>
    return <section className="er2-page page">{workspace?.capabilities?.platform === true && <WorkspaceReturn/>}<header className="er2-heading er2-heading--row"><h1>История изменений</h1>
        {platform&&<WorkspaceLink className="er2-action" to="/admin/audit?eventType=RETENTION_POLICY_CHANGED">История политики хранения</WorkspaceLink>}</header>
        <div className="er2-card"><h2>События регистрации и рассмотрения</h2><form className="er2-panel-heading er2-search-form" onSubmit={e=>{e.preventDefault();update(draft.trim(),0)}}>
            <label className="er2-filter">Номер заявки<input type="search" value={draft} maxLength={160} onChange={e=>setDraft(e.target.value)}/></label>
            <button className="er2-action" type="submit">Найти</button></form>
            {error?<div role="alert">Не удалось загрузить историю. <button type="button" onClick={()=>setRevision(v=>v+1)}>Повторить</button></div>:!data?<p role="status">Загрузка…</p>:<>
                {data.items.length===0?<p>Событий не найдено.</p>:<div className="er2-table-wrap"><table className="er2-table"><thead><tr><th>Дата</th><th>Организация</th><th>Заявка</th><th>Этап</th><th>Событие</th></tr></thead><tbody>
                    {data.items.map(x=><tr key={`${x.source}-${x.eventId}`}><td>{reviewTime(x.createdAt)}</td><td>{x.organization}</td>
                        <td><details><summary>{x.requestId.slice(0,8)}</summary><small>{x.requestId}</small></details></td><td>{x.source==='INTAKE'?'Регистрация':'Рассмотрение'}</td><td>{x.source === 'REVIEW' && x.eventCode === 'SUBMITTED' ? 'Создана запись рассмотрения' : EVENT_LABELS[x.eventCode] ?? 'Событие заявки'}</td></tr>)}</tbody></table></div>}
                {(page>0||data.hasNext)&&<div className="er2-panel-heading"><button className="er2-action" disabled={page===0} onClick={()=>update(query,page-1)}>Назад</button><span>Страница {page+1}</span><button className="er2-action" disabled={!data.hasNext} onClick={()=>update(query,page+1)}>Вперёд</button></div>}</>}
        </div></section>
}
