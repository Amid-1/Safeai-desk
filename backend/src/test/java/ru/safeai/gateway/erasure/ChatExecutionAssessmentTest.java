// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/erasure/ChatExecutionAssessmentTest.java
package ru.safeai.gateway.erasure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.safeai.gateway.erasure.ChatExecutionAssessment.Blocker.*;

class ChatExecutionAssessmentTest {
    private ResultSet valid() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("state")).thenReturn("SUBMITTED");
        when(row.getString("review_state")).thenReturn("APPROVED_REVIEW_ONLY");
        when(row.getString("erasure_state")).thenReturn("AVAILABLE");
        when(row.getString("request_digest")).thenReturn("a".repeat(64));
        for (String name : new String[]{"reviewer_current", "archived", "chat_allowed"}) {
            when(row.getObject(name, Boolean.class)).thenReturn(true);
        }
        for (String name : new String[]{"held_v86", "held_v105", "active_ai", "oversized", "existing_operation"}) {
            when(row.getObject(name, Boolean.class)).thenReturn(false);
        }
        for (String name : new String[]{"expected_resource_version", "chat_version", "case_version",
                "review_version", "hold_revision", "reviewer_token_version", "owner_token_version",
                "org_auth_version", "policy_version"}) {
            when(row.getObject(name, Long.class)).thenReturn(1L);
        }
        for (String name : new String[]{"reviewer_id", "id", "organization_id", "requested_by_user_id", "resource_id"}) {
            when(row.getObject(name, UUID.class)).thenReturn(UUID.randomUUID());
        }
        return row;
    }

    @Test void completeObservationStillCannotIssuePermit() throws SQLException {
        var assessment = ChatExecutionAssessment.map(valid());
        assertThat(assessment.blockers()).containsExactly(VERIFIED_IDENTITY_REQUIRED,
                INDEPENDENT_EXECUTION_AUTHORITY_REQUIRED, FROZEN_MANIFEST_REQUIRED, UNIFIED_DB_EXECUTION_GATE_REQUIRED);
        assertThat(assessment.binding()).isPresent();
        assertThat(assessment.executionAvailable()).isFalse();
        assertThat(assessment.permitIssued()).isFalse();
    }

    static Stream<String> evidenceColumns() {
        return Stream.of("reviewer_current", "archived", "chat_allowed", "held_v86", "held_v105",
                "active_ai", "oversized", "existing_operation");
    }

    @ParameterizedTest @MethodSource("evidenceColumns")
    void missingBooleanEvidenceNeverProducesBinding(String column) throws SQLException {
        ResultSet row = valid();
        when(row.getObject(column, Boolean.class)).thenReturn(null);
        var assessment = ChatExecutionAssessment.map(row);
        assertThat(assessment.blockers()).contains(UNKNOWN_EVIDENCE);
        assertThat(assessment.binding()).isEmpty();
        assertThat(assessment.permitIssued()).isFalse();
    }

    @Test void revokedGrantAndBothHoldsAndActiveOperationAreIndependentBlockers() throws SQLException {
        ResultSet row = valid();
        when(row.getObject("reviewer_current", Boolean.class)).thenReturn(false);
        when(row.getObject("held_v86", Boolean.class)).thenReturn(true);
        when(row.getObject("held_v105", Boolean.class)).thenReturn(true);
        when(row.getObject("active_ai", Boolean.class)).thenReturn(true);
        when(row.getObject("oversized", Boolean.class)).thenReturn(true);
        when(row.getObject("existing_operation", Boolean.class)).thenReturn(true);
        when(row.getObject("chat_allowed", Boolean.class)).thenReturn(false);
        var assessment = ChatExecutionAssessment.map(row);
        assertThat(assessment.blockers()).contains(APPROVER_NOT_CURRENTLY_AUTHORIZED, LEGAL_HOLD_V86,
                LEGAL_HOLD_V105, ACTIVE_AI_OPERATION, ATOMIC_LIMIT_EXCEEDED, EXISTING_V86_OPERATION,
                POLICY_NOT_ALLOWED);
        assertThat(assessment.binding()).isEmpty();
    }

    @Test void changedResourceAndWithdrawnCaseCannotProduceBinding() throws SQLException {
        ResultSet row = valid();
        when(row.getString("state")).thenReturn("WITHDRAWN");
        when(row.getObject("chat_version", Long.class)).thenReturn(2L);
        var assessment = ChatExecutionAssessment.map(row);
        assertThat(assessment.blockers()).contains(CASE_NOT_SUBMITTED, RESOURCE_CHANGED);
        assertThat(assessment.binding()).isEmpty();
    }

    @Test void malformedBindingDigestIsNotEvidence() throws SQLException {
        ResultSet row = valid();
        when(row.getString("request_digest")).thenReturn("not-a-digest");
        var assessment = ChatExecutionAssessment.map(row);
        assertThat(assessment.blockers()).contains(UNKNOWN_EVIDENCE);
        assertThat(assessment.binding()).isEmpty();
    }
}
