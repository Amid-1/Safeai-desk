// frontend/src/pages/ErasureReviewCenterPage.tsx
import { useEffect, useState } from 'react'
import { Link, useOutletContext } from 'react-router-dom'
import { ApiError } from '../shared/api/http'
import { loadRegistrySummary, loadRegistry, loadCapabilities, type RegistrySummary, type RegistryItem, type Capabilities } from '../features/erasure/api/workspaceApi'
import { RESOURCE_NAMES, registryState } from '../features/erasure/review/reviewLabels'
import { WorkspaceLink } from '../shared/ui/WorkspaceReturn'
import DataManagementIcon, { type DataIconName } from '../shared/ui/DataManagementIcon'
import '../features/erasure/review/ErasureReview.css'

type MetricKey = 'total' | 'pending' | 'needsInformation' | 'held' | 'registered' | 'withdrawn' | 'drafts'
type Metric = { key: MetricKey; label: string; tone: string; icon: DataIconName }
const PLATFORM_METRICS: Metric[] = [
    {key:'total',label:'Всего в реестре',tone:'blue',icon:'files'},
    {key:'pending',label:'Ожидают решения',tone:'amber',icon:'clock'},
    {key:'needsInformation',label:'Ожидают уточнения',tone:'violet',icon:'message'},
    {key:'held',label:'Заявки с удержаниями',tone:'rose',icon:'lock'},
]
const TENANT_METRICS: Metric[] = [
    {key:'total',label:'Всего в реестре',tone:'blue',icon:'files'},
    {key:'registered',label:'Зарегистрированы',tone:'amber',icon:'clock'},
    {key:'drafts',label:'Черновики',tone:'violet',icon:'message'},
    {key:'withdrawn',label:'Отозваны',tone:'rose',icon:'files'},
]
function SystemState({ cap }: { cap: Capabilities | null }) {
    return <section className="er2-card er2-system-state" aria-label="Состояние системы">
        <h2>Состояние системы</h2>
        <dl className="er2-system-grid">
            <div><dt>Приём обращений</dt><dd>{cap ? cap.requestsEnabled ? 'Включён' : 'Отключён' : 'Нет данных'}</dd></div>
            <div><dt>Рассмотрение заявок</dt><dd>{cap ? cap.approvalsEnabled ? 'Включено' : 'Отключено' : 'Нет данных'}</dd></div>
            <div><dt>Подтверждение личности</dt><dd>Не подключено</dd></div>
            <div><dt>Удаление по заявкам</dt><dd>Отключено</dd></div>
        </dl>
        <p className="er2-muted">Согласование заявки не запускает удаление данных.</p>
    </section>
}
export default function ErasureReviewCenterPage() {
    const workspace = useOutletContext<{capabilities:Capabilities|null;loading:boolean;failed:boolean}|null>()
    const [summary,setSummary]=useState<RegistrySummary|null>(null)
    const [attention,setAttention]=useState<RegistryItem[]|null>(null)
    const [cap,setCap]=useState<Capabilities|null>(null)
    const [error,setError]=useState<{status:number|null;requestId?:string}|null>(null)
    const [secondaryErrors,setSecondaryErrors]=useState<Record<string,string>>({})
    const [loading,setLoading]=useState(true)
    const [revision,setRevision]=useState(0)
    const allowed=!workspace||workspace.capabilities?.platform===true||workspace.capabilities?.tenantRegistry===true
    const expectedPlatform=workspace?.capabilities?.platform
    useEffect(()=>{
        const ac=new AbortController();setLoading(true);setSummary(null);setAttention(null);setCap(null);setError(null);setSecondaryErrors({})
        if(!allowed){setLoading(false);return ()=>ac.abort()}
        const recordError=(name:string,e:unknown)=>{if(!ac.signal.aborted)setSecondaryErrors(old=>({...old,[name]:e instanceof ApiError?`HTTP ${e.status}${e.requestId?` · ${e.requestId}`:''}`:'Нет подтверждённого ответа'}))}
        void loadRegistrySummary(ac.signal).then(v=>{if(!ac.signal.aborted)setSummary(v)})
            .catch(e=>{if(!ac.signal.aborted)setError(e instanceof ApiError?{status:e.status,requestId:e.requestId}:{status:null})}).finally(()=>{if(!ac.signal.aborted)setLoading(false)})
        void loadCapabilities(ac.signal).then(v=>{
            if(ac.signal.aborted)return
            setCap(v)
            if(v.platform){void loadRegistry(false,'ATTENTION','',0,ac.signal).then(result=>{
                if(!ac.signal.aborted)setAttention(result.items.slice(0,5))
            }).catch(e=>recordError('Реестр заявок',e))}
        }).catch(e=>recordError('Возможности пользователя',e))
        return ()=>ac.abort()
    },[revision,allowed,expectedPlatform])
    const platform = expectedPlatform ?? cap?.platform ?? true
    const visibleSummary = allowed && summary?.scope === (platform?'PLATFORM':'TENANT') ? summary : null
    const metrics = platform ? PLATFORM_METRICS : TENANT_METRICS
    const scopeMismatch = summary !== null && !visibleSummary
    const confirmedCap = workspace ? (!workspace.failed && !workspace.loading ? (allowed ? cap ?? workspace.capabilities : workspace.capabilities) : null) : cap
    if (!allowed) {
        const paused = confirmedCap?.requestsEnabled === false
        return <section className="er2-page page er2-center">
            <header className="er2-heading"><h1>Обзор заявок</h1></header>
            <div className="er2-banner er2-banner--neutral" role="status">
                <span className="er2-banner-mark" aria-hidden="true">i</span>
                <div><strong>{workspace?.loading ? 'Проверяем доступ…' : workspace?.failed ? 'Не удалось проверить доступ' : paused ? 'Приём обращений приостановлен' : 'Обзор сейчас недоступен'}</strong>
                    <p>{paused ? 'В настоящее время регистрация обращений недоступна. Новые заявки не принимаются.' : 'Статистика и реестр недоступны до подтверждения серверных полномочий.'}</p>
                </div>
            </div>
            <SystemState cap={confirmedCap} />
        </section>
    }
    return <section className="er2-page page er2-center">
        <header className="er2-heading er2-heading--row"><h1>Обзор заявок</h1><button className="er2-action er2-icon-action" type="button" disabled={loading}
            onClick={()=>setRevision(v=>v+1)}><DataManagementIcon name="refresh"/>Обновить данные</button></header>
        {(error||scopeMismatch||Object.keys(secondaryErrors).length>0)&&<div role="alert" className="er2-banner er2-banner--danger"><div>{scopeMismatch?'Область статистики изменилась. Обновите данные.':error?'Сводка недоступна. Не удалось загрузить статистику.':'Не удалось загрузить часть данных.'}<details><summary>Диагностика</summary>{error&&<p>Сводка: HTTP {error.status??'нет подтверждённого ответа'}{error.requestId&&` · ${error.requestId}`}</p>}{Object.entries(secondaryErrors).map(([name,value])=><p key={name}>{name}: {value}</p>)}</details></div></div>}
        <SystemState cap={confirmedCap} />
        <div className="er2-metrics-grid" aria-busy={loading}>{metrics.map(m=><div key={m.key} className={`er2-metric er2-metric--${m.tone}`}>
            <span className="er2-metric-label"><DataManagementIcon name={m.icon}/>{m.label}</span><strong>{loading?'…':visibleSummary?.[m.key]?.toLocaleString('ru-RU')??'—'}</strong></div>)}</div>
        {platform&&visibleSummary&&<p className="er2-muted">Зарегистрированы: {visibleSummary.registered}. Отозваны: {visibleSummary.withdrawn}. Переданы на рассмотрение: {visibleSummary.reviewed}. Удержания могут пересекаться с другими статусами.</p>}
        <div className="er2-dashboard-grid">
            <section className="er2-card"><h2>{platform?'Требуют внимания':'Заявки организации'}</h2>
                {platform?(!attention?.length?<p className="er2-attention-empty">{attention===null?((secondaryErrors['Реестр заявок']||secondaryErrors['Возможности пользователя'])?'Не удалось загрузить список.':'Загрузка списка…'):'Нет заявок, требующих внимания.'}</p>:
                    <div className="er2-table-wrap"><table className="er2-table"><thead><tr><th>Заявка / организация</th><th>Статус</th></tr></thead><tbody>{attention.map(x=><tr key={x.requestId}>
                        <td><WorkspaceLink to={`/admin/erasure/registry/${x.requestId}`}>{RESOURCE_NAMES[x.resourceType]??'Объект'}</WorkspaceLink><small>{x.organization} · {x.requestId.slice(0,8)}</small></td><td>{registryState(x.state)}{x.holdActive&&<small>Удержание</small>}</td></tr>)}</tbody></table></div>):
                    <p>Регистрация обращений вашей организации. Решения и удержания доступны только в отдельной очереди согласований при наличии полномочий.</p>}
                <div className="er2-attention-footer">{cap?.platform&&platform?<Link to="/admin/erasure/registry?state=ATTENTION">Все заявки →</Link>:cap?.tenantRegistry&&!platform?<Link to="/admin/erasure/intake">Все заявки →</Link>:<span className="er2-muted">Реестр недоступен до подтверждения доступа.</span>}</div>
            </section>
        </div>
    </section>
}
