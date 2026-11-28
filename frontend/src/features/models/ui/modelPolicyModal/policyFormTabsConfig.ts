// frontend/src/features/models/ui/modelPolicyModal/policyFormTabsConfig.ts
export const POLICY_TABS = ['access', 'limits', 'budget', 'data', 'preview'] as const
export type PolicyTab = typeof POLICY_TABS[number]

// Each shortcut selects the editor tab and its actual field/group.
export const POLICY_EDITOR_TARGETS = {
    access: {tab: 'access', idSuffix: 'access'},
    default: {tab: 'access', idSuffix: 'default'},
    limits: {tab: 'limits', idSuffix: 'limits'},
    requestCost: {tab: 'budget', idSuffix: 'request-cost'},
    monthlyBudget: {tab: 'budget', idSuffix: 'monthly-budget'},
    data: {tab: 'data', idSuffix: 'data'},
    preview: {tab: 'preview', idSuffix: 'panel-preview'},
} as const satisfies Record<string, {tab: PolicyTab; idSuffix: string}>
export type PolicyEditorTarget = keyof typeof POLICY_EDITOR_TARGETS
