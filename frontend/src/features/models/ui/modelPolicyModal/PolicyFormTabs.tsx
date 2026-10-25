// frontend/src/features/models/ui/modelPolicyModal/PolicyFormTabs.tsx
// Доступная вкладочная навигация. Стрелки/Home/End и roving tabIndex по WAI-ARIA.

import {useRef} from 'react'
import type {KeyboardEvent, ReactNode} from 'react'
import {POLICY_PREVIEW_LABEL} from './policyPreviewStatus'
import type {PolicyPreviewState} from './policyPreviewStatus'

import { POLICY_TABS } from './policyFormTabsConfig'
import type { PolicyTab } from './policyFormTabsConfig'

const TAB_DETAILS: Readonly<Record<PolicyTab, {label: string; subtitle: string}>> = {
    access: {label: 'Доступ', subtitle: 'Разрешения, запреты и модель по умолчанию'},
    limits: {label: 'Лимиты', subtitle: 'Максимум входных и выходных токенов'},
    budget: {label: 'Бюджет', subtitle: 'Стоимость запроса и месячный бюджет'},
    data: {label: 'Требования к данным', subtitle: 'Стоимость, обучение и хранение данных'},
    preview: {label: 'Проверка', subtitle: 'Предварительная оценка доступности моделей для организации'},
}

type Props = {
    formId: string
    selected: PolicyTab
    onSelect: (tab: PolicyTab) => void
    panels: Readonly<Record<PolicyTab, ReactNode>>
    previewStatus?: PolicyPreviewState
}

export function PolicyFormTabs({formId, selected, onSelect, panels, previewStatus = 'idle'}: Props) {
    const tabsRef = useRef<Partial<Record<PolicyTab, HTMLButtonElement | null>>>({})

    function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>, current: PolicyTab): void {
        const index = POLICY_TABS.indexOf(current)
        let next: PolicyTab | undefined
        switch (event.key) {
            case 'ArrowRight': next = POLICY_TABS[(index + 1) % POLICY_TABS.length]; break
            case 'ArrowLeft': next = POLICY_TABS[(index + POLICY_TABS.length - 1) % POLICY_TABS.length]; break
            case 'Home': next = POLICY_TABS[0]; break
            case 'End': next = POLICY_TABS[POLICY_TABS.length - 1]; break
            default: return
        }
        event.preventDefault()
        onSelect(next)
        tabsRef.current[next]?.focus()
    }

    return (
        <div className="models-policy-tabs">
            <div className="models-policy-tabs__nav" role="tablist" aria-label="Разделы правил моделей">
                {POLICY_TABS.map((tab, index) => (
                    <button
                        ref={(element) => {tabsRef.current[tab] = element}}
                        key={tab}
                        type="button"
                        id={`${formId}-tab-${tab}`}
                        role="tab"
                        aria-selected={selected === tab}
                        aria-label={tab === 'preview' && previewStatus !== 'idle'
                            ? `Проверка — ${POLICY_PREVIEW_LABEL[previewStatus]}`
                            : undefined}
                        aria-controls={`${formId}-panel-${tab}`}
                        tabIndex={selected === tab ? 0 : -1}
                        onClick={() => onSelect(tab)}
                        onKeyDown={(event) => handleKeyDown(event, tab)}
                        className="models-policy-tabs__tab"
                    >
                        <span className="models-policy-tabs__number" aria-hidden="true">{String(index + 1).padStart(2, '0')}</span>
                        <span>{TAB_DETAILS[tab].label}</span>
                        {tab === 'preview' && previewStatus !== 'idle' && (
                            <span className={`models-policy-tabs__dot models-policy-tabs__dot--${previewStatus}`} aria-hidden="true" />
                        )}
                    </button>
                ))}
            </div>
            <div className="models-policy-tabs__content">
                {POLICY_TABS.map((tab) => (
                    <section
                        key={tab}
                        id={`${formId}-panel-${tab}`}
                        role="tabpanel"
                        aria-labelledby={`${formId}-tab-${tab}`}
                        tabIndex={0}
                        hidden={selected !== tab}
                        className="models-policy-tabs__panel"
                    >
                        <div className="models-policy-tabs__heading">
                            <h3>{TAB_DETAILS[tab].label}</h3>
                            <p>{TAB_DETAILS[tab].subtitle}</p>
                        </div>
                        {panels[tab]}
                    </section>
                ))}
            </div>
        </div>
    )
}
