// frontend/src/features/models/ui/ModelIcon.tsx
// Общие контурные значки раздела; подписи остаются доступными без SVG.
const paths = {
    overview: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
    layers: 'm12 3 9 5-9 5-9-5 9-5Z M3 12l9 5 9-5 M3 16l9 5 9-5',
    shield: 'M12 3 3 7v5c0 5 5 8 9 10 4-2 9-5 9-10V7l-9-4Z M8 12l3 3 5-6',
    activity: 'M2 12h5l3-8 4 16 3-8h5',
    server: 'M3 4h18v6H3z M3 14h18v6H3z M6 7h.01 M6 17h.01 M11 7h7 M11 17h7',
    cpu: 'M6 6h12v12H6z M9 9h6v6H9z M9 3v3 M15 3v3 M9 18v3 M15 18v3 M3 9h3 M3 15h3 M18 9h3 M18 15h3',
    search: 'M15.5 15.5 21 21 M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z',
    history: 'M3 12a9 9 0 1 0 3-7 M3 4v5h5 M12 7v5l3 2',
    text: 'M4 5h16 M12 5v14 M8 19h8',
    image: 'M3 4h18v16H3z M3 16l5-5 4 4 3-3 6 6 M15 8h.01',
    tools: 'M14 6a5 5 0 0 0-6 6L3 17l4 4 5-5a5 5 0 0 0 6-6l-3 3-4-4 3-3Z',
    code: 'm8 6-6 6 6 6 M16 6l6 6-6 6 M14 4l-4 16',
    audio: 'M12 3v18 M8 7v10 M16 7v10 M4 10v4 M20 10v4',
    wallet: 'M3 6h16v14H3z M19 10h3v6h-6v-6z M3 6l13-3',
    budget: 'M12 3v9h9 A9 9 0 1 1 12 3Z M15 3a9 9 0 0 1 6 6h-6z',
    gauge: 'M4 18a9 9 0 1 1 16 0 M12 14l5-6 M12 14h.01',
    list: 'M9 6h12 M9 12h12 M9 18h12 M3 6l1 1 2-2 M3 12l1 1 2-2 M3 18l1 1 2-2',
    building: 'M4 21V3h16v18 M2 21h20 M8 7h2 M14 7h2 M8 11h2 M14 11h2 M10 21v-6h4v6',
    copy: 'M8 8h13v13H8z M16 8V3H3v13h5',
    network: 'M9 3h6v6H9z M3 15h6v6H3z M15 15h6v6h-6z M12 9v3 M6 15v-3h12v3',
    database: 'M3 6a9 3 0 0 0 18 0 9 3 0 0 0-18 0Z M3 6v12a9 3 0 0 0 18 0V6 M3 12a9 3 0 0 0 18 0',
    check: 'm5 12 4 4L19 6',
    alert: 'M12 3 2 21h20L12 3Z M12 9v5 M12 17h.01',
    arrow: 'M4 12h15 M13 6l6 6-6 6',
} as const

export type ModelIconName = keyof typeof paths

export function ModelIcon({ name }: { name: ModelIconName }) {
    return <svg className="models-icon" aria-hidden="true" focusable="false"
        viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"
        strokeLinecap="round" strokeLinejoin="round"><path d={paths[name]} /></svg>
}
