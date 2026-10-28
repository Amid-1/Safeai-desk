package ru.safeai.gateway.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDocumentResponseJsonTest {
    private final JsonMapper mapper = JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion ->
                    inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @Test
    void pendingResponseAlwaysContainsNullSearchableVersionId() {
        var response = new KnowledgeDocumentResponse(
                UUID.randomUUID(), UUID.randomUUID(), "pending", true, 0L,
                UUID.randomUUID(), null, 1, "pending.txt", "text/plain", 12L,
                null, null, null
        );

        var json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"searchableVersionId\":null");
        assertThat(json).doesNotContain("\"createdAt\"");
        assertThat(json).doesNotContain("\"updatedAt\"");
    }

    @Test
    void publishedResponsePreservesUuid() {
        UUID versionId = UUID.randomUUID();
        var response = new KnowledgeDocumentResponse(
                UUID.randomUUID(), UUID.randomUUID(), "ready", true, 1L,
                versionId, versionId, 1, "ready.txt", "text/plain", 12L,
                null, null, null
        );

        assertThat(mapper.writeValueAsString(response))
                .contains("\"searchableVersionId\":\"" + versionId + "\"");
    }
}
