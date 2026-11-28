// frontend/src/shared/ui/DataManagementIcon.tsx
// Decorative icons. Text labels supply accessible names.
const paths = {
    overview: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
    inbox: 'M4 4h16l2 10v6H2v-6zM2 14h6l2 3h4l2-3h6',
    review: 'M9 4H5v17h14V4h-4M9 2h6v5H9zM8 13l3 3 5-5',
    database: 'M4 6a8 3 0 1 0 16 0 8 3 0 1 0-16 0M4 6v12a8 3 0 0 0 16 0V6M4 12a8 3 0 0 0 16 0',
    history: 'M3 12a9 9 0 1 1 3 6.7M3 5v7h7M12 7v5l4 2',
    files: 'M8 7V3h12v14h-4M4 7h12v14H4z',
    clock: 'M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18M12 7v5l-3 3',
    message: 'M20 11a8 8 0 0 1-11 7.5L4 20l1.6-5A8 8 0 1 1 20 11z',
    lock: 'M7 10V7a5 5 0 0 1 10 0v3M5 10h14v11H5zM12 14v3',
    users: 'M10 7a3 3 0 1 0-6 0 3 3 0 1 0 6 0M2 20v-3a5 5 0 0 1 10 0v3M16 4a3 3 0 0 1 0 6M15 14a5 5 0 0 1 5 5v1',
    building: 'M3 21V3h9v18M12 8h8v13M1 21h22M6 7h3M6 11h3M6 15h3M15 12h2M15 16h2',
    chevron: 'm9 5 7 7-7 7',
    refresh: 'M20 8a8 8 0 0 0-13-3L4 8M4 3v5h5M4 16a8 8 0 0 0 13 3l3-3M20 21v-5h-5',
} as const
export type DataIconName = keyof typeof paths
export default function DataManagementIcon({name}: {name: DataIconName}) {
    return <svg aria-hidden="true" focusable="false" width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round"><path d={paths[name]}/></svg>
}
