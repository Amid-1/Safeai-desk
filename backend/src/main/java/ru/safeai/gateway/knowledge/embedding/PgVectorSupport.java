package ru.safeai.gateway.knowledge.embedding;

public final class PgVectorSupport {

    private PgVectorSupport() {
    }

    public static String encode(
            float[] vector
    ) {
        if (vector == null
                || vector.length == 0) {
            throw new IllegalArgumentException(
                    "Vector не должен быть пустым"
            );
        }

        StringBuilder result =
                new StringBuilder(
                        vector.length * 10
                );

        result.append('[');

        for (
                int index = 0;
                index < vector.length;
                index++
        ) {
            float value =
                    vector[index];

            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Vector содержит non-finite значение"
                );
            }

            if (index > 0) {
                result.append(',');
            }

            result.append(value);
        }

        return result
                .append(']')
                .toString();
    }
}
