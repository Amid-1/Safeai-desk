package ru.safeai.gateway.model.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("performance")
@Testcontainers
@Timeout(600)
class ModelCommitmentLedgerExplainPerformanceIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");
    private static final Pattern EXECUTION_TIME = Pattern.compile("Execution Time: ([0-9.]+) ms");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path DIRECTORY = Path.of("target", "model-explain");
    private static final int SAMPLES = 3;

    private ModelCommitmentLedgerFixture fixture;

    @BeforeEach
    void prepare() throws IOException, SQLException {
        fixture = new ModelCommitmentLedgerFixture(POSTGRES);
        fixture.reset();
        fixture.installV62();
        Files.createDirectories(DIRECTORY);
    }

    @Test
    void selectiveTenantAndGlobalLedgerPlansBeforeAndAfterMigration() throws IOException {
        compare(false, 100_000);
    }

    @Test
    void denseActivePlansAndSkewedTenantPreserveResultsAndBoundRegression() throws IOException {
        int plans = Boolean.getBoolean("safeai.model.performance.large") ? 500_000 : 100_000;
        compare(true, plans);
    }

    private void compare(boolean dense, int plans) throws IOException {
        fixture.addBackground(plans, dense);
        long attempts = Objects.requireNonNull(fixture.jdbc.queryForObject(
                "SELECT count(*) FROM public.model_execution_attempts", Long.class));
        assertThat(attempts).isEqualTo(2L * plans + 9);
        if (dense) {
            assertThat(fixture.jdbc.queryForObject("""
                    SELECT count(*) FROM public.model_execution_plans WHERE organization_id = ?
                    """, Long.class, fixture.hotTenant())).isEqualTo(plans / 2L);
            assertThat(fixture.jdbc.queryForObject("""
                    SELECT count(*) FROM public.chat_turns WHERE state = 'PROCESSING'
                    """, Long.class)).isGreaterThan(plans / 4L);
        }
        var repository = new ModelRouteDecisionRepository(fixture.jdbc);
        var before = repository.loadCommittedMonthlyCostSnapshot(
                fixture.tenant(), ModelCommitmentLedgerFixture.FROM, ModelCommitmentLedgerFixture.TO);
        ModelCommitmentLedgerV101IntegrationTest.assertFinancialCases(before);
        var globalBefore = fixture.globalResults();
        assertThat(globalBefore).hasSize(dense ? 1002 : 1001);
        var hotBefore = repository.loadCommittedMonthlyCostSnapshot(
                fixture.hotTenant(), ModelCommitmentLedgerFixture.FROM, ModelCommitmentLedgerFixture.TO);

        String prefix = dense ? "dense-" : "";
        List<Scope> scopes = new ArrayList<>();
        scopes.add(new Scope("tenant", fixture::explainTenant, () -> fixture.explainTenantJson(fixture.tenant())));
        if (dense) {
            scopes.add(new Scope("hot", () -> fixture.explainTenant(fixture.hotTenant()),
                    () -> fixture.explainTenantJson(fixture.hotTenant())));
        }
        scopes.add(new Scope("global", fixture::explainGlobal, fixture::explainGlobalJson));
        List<Measurement> old = collect(prefix + "v62-", scopes, false);
        fixture.upgradeToV101();
        List<Measurement> next = collect(prefix + "v101-", scopes, true);

        assertThat(repository.loadCommittedMonthlyCostSnapshot(
                fixture.tenant(), ModelCommitmentLedgerFixture.FROM, ModelCommitmentLedgerFixture.TO))
                .isEqualTo(before);
        assertThat(repository.loadCommittedMonthlyCostSnapshot(
                fixture.hotTenant(), ModelCommitmentLedgerFixture.FROM, ModelCommitmentLedgerFixture.TO))
                .isEqualTo(hotBefore);
        assertThat(fixture.globalResults()).containsExactlyElementsOf(globalBefore);
        long sla = Long.parseLong(System.getProperty("safeai.model.explain-sla-ms", "5000"));
        long largeSla = Long.parseLong(System.getProperty("safeai.model.large-explain-sla-ms", "30000"));
        assertThat(sla).isPositive();
        assertThat(largeSla).isPositive();
        StringBuilder report = new StringBuilder(
                "version,scope,attempts,median_ms,max_ms,median_shared_blocks,median_temp_blocks,median_attempt_scan_rows\n");
        for (int i = 0; i < scopes.size(); i++) {
            String scope = scopes.get(i).name();
            Measurement baseline = old.get(i);
            Measurement revised = next.get(i);
            report.append(baseline.csv("v62", scope, attempts));
            report.append(revised.csv("v101", scope, attempts));
            long bound = plans > 100_000 && !"tenant".equals(scope) ? largeSla : sla;
            assertThat(revised.maxMs()).as("V101 %s absolute SQL SLA", scope).isLessThanOrEqualTo(bound);
            // Broad, noise-tolerant relative regression gate; not a claimed speedup.
            assertThat(revised.medianMs()).as("V101 %s relative SQL regression", scope)
                    .isLessThanOrEqualTo(2 * baseline.medianMs() + 100);
            if ("tenant".equals(scope)) {
                assertThat(revised.attemptRows()).as("Selective query must avoid global attempt scan")
                        .isLessThan(baseline.attemptRows());
            }
        }
        Files.writeString(DIRECTORY.resolve(prefix + "comparison.csv"), report);
    }

    private static List<Measurement> collect(String prefix, List<Scope> scopes, boolean revised) throws IOException {
        List<Measurement> measurements = new ArrayList<>();
        for (Scope scope : scopes) {
            scope.text().get(); // warm up separately for each version and scope
            String plan = scope.text().get();
            Files.writeString(DIRECTORY.resolve(prefix + scope.name() + ".txt"), plan);
            assertThat(plan).contains("Buffers:", "Planning Time:", "Execution Time:");
            var time = EXECUTION_TIME.matcher(plan);
            assertThat(time.find()).isTrue();
            double textMillis = Double.parseDouble(time.group(1));
            if (revised && "tenant".equals(scope.name())) {
                assertThat(plan).contains("Limit", "uq_model_execution_attempt_number_v50");
            }
            List<Sample> samples = new ArrayList<>();
            for (int i = 1; i <= SAMPLES; i++) {
                String json = scope.json().get();
                Files.writeString(DIRECTORY.resolve(prefix + scope.name() + "-" + i + ".json"), json);
                JsonNode root = Objects.requireNonNull(JSON.readTree(json).get(0), "Missing JSON EXPLAIN result");
                JsonNode node = root.path("Plan");
                assertThat(node.isObject()).isTrue();
                assertThat(root.path("Execution Time").isNumber()).isTrue();
                samples.add(new Sample(root.path("Execution Time").asDouble(),
                        blocks(node, "Shared Hit Blocks", "Shared Read Blocks"),
                        blocks(node, "Temp Read Blocks", "Temp Written Blocks"),
                        attemptScanRows(node)));
            }
            measurements.add(Measurement.of(samples, textMillis));
        }
        return measurements;
    }

    private static long blocks(JsonNode node, String first, String second) {
        return node.path(first).asLong(0L) + node.path(second).asLong(0L);
    }

    // EXPLAIN reports averages per loop, rounded by PostgreSQL. This is a
    // candidate-row estimate, not an exact count of physical tuple reads.
    private static long attemptScanRows(JsonNode node) {
        long count = 0;
        if ("model_execution_attempts".equals(node.path("Relation Name").asString(""))) {
            count = node.path("Actual Loops").asLong()
                    * (node.path("Actual Rows").asLong() + node.path("Rows Removed by Filter").asLong(0L)
                    + node.path("Rows Removed by Index Recheck").asLong(0L));
        }
        JsonNode children = node.path("Plans");
        if (children.isArray()) {
            for (JsonNode child : children) {
                count += attemptScanRows(child);
            }
        }
        return count;
    }

    private record Scope(String name, Supplier<String> text, Supplier<String> json) {}

    private record Sample(double millis, long shared, long temp, long attempts) {}

    private record Measurement(double medianMs, double maxMs, long shared, long temp, long attemptRows) {
        static Measurement of(List<Sample> samples, double textMillis) {
            return new Measurement(
                    samples.stream().mapToDouble(Sample::millis).sorted().toArray()[SAMPLES / 2],
                    Math.max(textMillis, samples.stream().mapToDouble(Sample::millis).max().orElseThrow()),
                    samples.stream().mapToLong(Sample::shared).sorted().toArray()[SAMPLES / 2],
                    samples.stream().mapToLong(Sample::temp).sorted().toArray()[SAMPLES / 2],
                    samples.stream().mapToLong(Sample::attempts).sorted().toArray()[SAMPLES / 2]);
        }

        String csv(String version, String scope, long attempts) {
            return String.format(Locale.ROOT, "%s,%s,%d,%.3f,%.3f,%d,%d,%d%n",
                    version, scope, attempts, medianMs, maxMs, shared, temp, attemptRows);
        }
    }
}
