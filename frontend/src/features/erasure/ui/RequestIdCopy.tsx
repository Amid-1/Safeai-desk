// frontend/src/features/erasure/ui/RequestIdCopy.tsx
// Copy only the request identifier already visible in the authorized response.
import { useState } from 'react'

export default function RequestIdCopy({ requestId }: { requestId: string }) {
    const [status, setStatus] = useState<'idle' | 'pending' | 'copied' | 'failed'>('idle')
    async function copy() {
        if (status === 'pending') return
        setStatus('pending')
        try {
            await navigator.clipboard.writeText(requestId)
            setStatus('copied')
        } catch {
            setStatus('failed')
        }
    }
    return <span className="er2-id-copy">
        <span className="er2-registry-id er2-id-value">{requestId}</span>
        <button type="button" className="er2-id-copy-button" aria-label={`Скопировать номер заявки ${requestId}`}
            disabled={status === 'pending'} onClick={() => void copy()}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/></svg>
            Копировать
        </button>
        <span className="er2-id-copy-status" role="status">{status === 'copied' ? 'Номер скопирован.' : status === 'failed' ? 'Не удалось скопировать. Выделите номер и скопируйте вручную.' : status === 'pending' ? 'Копирование…' : ''}</span>
    </span>
}
