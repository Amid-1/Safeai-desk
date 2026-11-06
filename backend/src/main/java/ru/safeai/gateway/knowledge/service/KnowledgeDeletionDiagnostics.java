
package ru.safeai.gateway.knowledge.service;

/**
 * Общая диагностика ошибок удаления документов и баз знаний.
 */
final class KnowledgeDeletionDiagnostics {

    private KnowledgeDeletionDiagnostics() {
    }

    /**
     * Формирует ограниченную цепочку исходных исключений.
     */
    static String diagnostic(Throwable error) {
        StringBuilder result = new StringBuilder();

        Throwable current = error;
        int depth = 0;

        while (current != null && depth++ < 4) {
            if (!result.isEmpty()) {
                result.append(" <- ");
            }

            result.append(current.getClass().getSimpleName());

            String message = current.getMessage();

            if (message != null && !message.isBlank()) {
                String safeMessage = message
                        .replaceAll("[\\r\\n\\t]+", " ")
                        .replaceAll("\\s+", " ")
                        .trim();

                if (safeMessage.length() > 240) {
                    safeMessage = safeMessage.substring(0, 240) + "…";
                }

                result.append(": ").append(safeMessage);
            }

            current = current.getCause();
        }

        return result.toString();
    }
}
