// ============================================================
// frontend/src/hooks/useAutoClearMessage.ts
// ============================================================
import { useEffect } from 'react'
import type { Dispatch, SetStateAction } from 'react'

export function useAutoClearMessage(
    message: string,
    setMessage: Dispatch<SetStateAction<string>>,
    timeoutMs: number,
): void {
    useEffect(() => {
        if (!message) {
            return
        }

        const timeoutId = window.setTimeout(() => {
            setMessage('')
        }, timeoutMs)

        return () => {
            window.clearTimeout(timeoutId)
        }
    }, [message, setMessage, timeoutMs])
}