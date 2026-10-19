import {useState} from 'react'

export function UserExactDate({value}: {value: string | null}) {
    if (!value) return <>Ещё не входил</>
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return <>Некорректная дата</>
    const formatted = new Intl.DateTimeFormat('ru-RU', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
        timeZoneName: 'short',
    }).format(date)
    return (
        <time dateTime={value} title={`Время UTC: ${date.toISOString()}`}>
            {formatted}
        </time>
    )
}

export function UserUuidCopy({value, label}: {value: string; label: string}) {
    const [result, setResult] = useState('')
    async function copy() {
        try {
            await navigator.clipboard.writeText(value)
            setResult('Скопировано')
        } catch {
            setResult('Не удалось скопировать UUID')
        }
    }
    return (
        <span className="users-details-uuid">
            <code title={value}>{value}</code>
            <button type="button" aria-label={`Копировать ${label}`} onClick={() => void copy()}>
                Копировать
            </button>
            <span role="status">{result}</span>
        </span>
    )
}
