package ru.safeai.gateway.knowledge.embedding;

import java.util.List;

public interface KnowledgeEmbeddingProvider {

    int dimensions();

    String model();

    float[] embed(String text);

    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
