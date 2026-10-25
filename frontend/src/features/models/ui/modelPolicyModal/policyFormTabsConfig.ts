// frontend/src/features/models/ui/modelPolicyModal/policyFormTabsConfig.ts
export const POLICY_TABS = ['access', 'limits', 'budget', 'data', 'preview'] as const
export type PolicyTab = typeof POLICY_TABS[number]
