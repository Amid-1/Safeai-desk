package ru.safeai.gateway.knowledge.generation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KnowledgeGenerationGcTest {
    @Test void unsafeRetentionAndUnboundedBatchesFailBeforeDatabaseAccess() {
        var jdbc=mock(JdbcTemplate.class);
        var gc=new KnowledgeGenerationGc(jdbc);
        assertThatThrownBy(() -> gc.collect(Duration.ZERO,2,20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gc.collect(Duration.ofDays(30),0,20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gc.collect(Duration.ofDays(30),2,101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gc.collect(null,2,20)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
}
