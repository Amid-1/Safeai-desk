// frontend/src/features/knowledge/ui/details/knowledgeDocumentFormat.ts
export function formatBytes(value: number): string {
    if (value < 1024) {
        return `${value} Б`
    }

    if (value < 1024 * 1024) {
        return `${(
            value / 1024
        ).toFixed(1)} КБ`
    }

    return `${(
        value / 1024 / 1024
    ).toFixed(1)} МБ`
}
