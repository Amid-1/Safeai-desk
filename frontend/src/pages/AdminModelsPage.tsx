import {
    useEffect,
    useState,
} from 'react'
import {
    getRuntimeModelStatus,
} from '../api/modelApi'
import type {
    RuntimeModelStatus,
} from '../api/modelApi'
import {
    getApiErrorMessage,
} from '../api/http'
import {
    ErrorState,
    LoadingState,
} from '../components/StateBlock'
import ResizableScrollRegion
    from '../components/ResizableScrollRegion'

function AdminModelsPage() {
    const [status, setStatus] = useState<RuntimeModelStatus | null>(null)
    const [error, setError] = useState('')

    useEffect(() => {
        const controller = new AbortController()

        void getRuntimeModelStatus(controller.signal)
            .then(setStatus)
            .catch((failure) => {
                if (!controller.signal.aborted) {
                    setError(getApiErrorMessage(failure, 'Не удалось загрузить runtime-модель.'))
                }
            })

        return () => controller.abort()
    }, [])

    if (error) {
        return <div className="page"><ErrorState message={error} /></div>
    }

    if (!status) {
        return <div className="page"><LoadingState message="Загрузка конфигурации модели..." /></div>
    }

    return (
        <div className="page">
            <h1>Модели и маршрутизация</h1>
            <p className="page-description">
                Фактическая конфигурация активного runtime. Секреты, ключи и URL провайдера здесь не отображаются.
            </p>
            <ResizableScrollRegion
                storageKey="safeai:models-runtime-height"
                label="сведения об активной модели"
                viewportClassName="table-wrapper"
                defaultHeight={360}
                minHeight={220}
                maxHeight={620}
            >
                <table>
                    <tbody>
                        <tr><th>Провайдер</th><td>{status.provider}</td></tr>
                        <tr><th>Модель</th><td>{status.model}</td></tr>
                        <tr><th>Режим</th><td>{status.routingMode}</td></tr>
                        <tr><th>Контекст / ответ</th><td>{status.maxInputTokens.toLocaleString()} / {status.maxOutputTokens.toLocaleString()} токенов</td></tr>
                        <tr><th>Pricing</th><td>{status.pricingStatus}{status.pricingVersion ? ` (${status.pricingVersion})` : ''}</td></tr>
                        <tr><th>Provider health</th><td>{status.healthStatus} — live probe ещё не реализован</td></tr>
                        <tr><th>Data retention</th><td>{status.dataRetentionStatus} — требуется явная policy-полность</td></tr>
                        <tr><th>Tools / Vision / Structured output</th><td>{String(status.toolsSupported)} / {String(status.visionSupported)} / {String(status.structuredOutputSupported)}</td></tr>
                    </tbody>
                </table>
            </ResizableScrollRegion>
            <div className="pagination pagination--single">
                <div className="pagination__summary">
                    <strong>Активная runtime-модель</strong>
                    <span>{status.provider} · {status.model} · {status.routingMode}</span>
                </div>
            </div>
            <p className="page-description">
                Следующий безопасный шаг: versioned catalog и tenant model policy, затем multiplexer/routing. До этого SafeAI не заявляет поддержку multi-model routing.
            </p>
        </div>
    )
}

export default AdminModelsPage
