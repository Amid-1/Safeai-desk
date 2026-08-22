package ru.safeai.gateway.knowledge.embedding;

public final class PgVectorSupport {

    private PgVectorSupport() {
    }

    public static String encode(float[] vector) {
        StringBuilder result = new StringBuilder(vector.length * 10);
        result.append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(vector[index]);
        }
        return result.append(']').toString();
    }
}
