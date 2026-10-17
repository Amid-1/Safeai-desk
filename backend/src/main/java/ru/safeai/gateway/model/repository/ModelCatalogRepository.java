package ru.safeai.gateway.model.repository;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.model.domain.*;
import ru.safeai.gateway.ai.pricing.ModelPricingScheme;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class ModelCatalogRepository {

    private static final String SELECT_COLUMNS = """
            id, model_key, version, provider, provider_model_id,
            display_name, lifecycle, max_input_tokens, max_output_tokens,
            tools_supported, vision_supported, structured_output_supported,
            text_input_supported, image_input_supported, audio_input_supported,
            text_output_supported, audio_output_supported,
            retention_status, retention_days, training_use_status,
            pricing_status, pricing_complete,
            input_usd_per_1m_tokens, cached_input_usd_per_1m_tokens,
            cache_write_input_usd_per_1m_tokens, output_usd_per_1m_tokens,
            extra_pricing_json::text as extra_pricing_json,
            pricing_version, effective_from, source, created_by_user_id, created_at,
            pricing_scheme
            """;

    private static final String FIND_LATEST_PAGE_SQL = """
            select latest.* from (
                select distinct model_key
                from model_catalog_entries
                where (?::text is null or model_key > ?::text)
                order by model_key
                limit ?
            ) keys
            cross join lateral (
                select\s""" + SELECT_COLUMNS + """
                from model_catalog_entries
                where model_key = keys.model_key
                order by version desc limit 1
            ) latest
            order by keys.model_key
            """;

    private static final String FIND_EFFECTIVE_PAGE_SQL = """
            select effective.* from (
                select distinct model_key
                from model_catalog_entries
                where effective_from <= ?
                  and (?::text is null or model_key > ?::text)
                order by model_key
                limit ?
            ) keys
            cross join lateral (
                select\s""" + SELECT_COLUMNS + """
                from model_catalog_entries
                where model_key = keys.model_key
                  and effective_from <= ?
                order by version desc limit 1
            ) effective
            order by keys.model_key
            """;

    private static final String FIND_EFFECTIVE_BY_RUNTIME_SQL =
            "select * from (select distinct on (model_key) " + SELECT_COLUMNS
                    + " from model_catalog_entries where effective_from <= ? "
                    + "order by model_key, version desc) effective "
                    + "where provider = ? and provider_model_id = ? order by model_key";

    private static final String INSERT_SQL = """
            insert into model_catalog_entries (
                id, model_key, version, provider, provider_model_id,
                display_name, lifecycle, max_input_tokens, max_output_tokens,
                tools_supported, vision_supported, structured_output_supported,
                text_input_supported, image_input_supported, audio_input_supported,
                text_output_supported, audio_output_supported,
                retention_status, retention_days, training_use_status,
                pricing_status, pricing_complete,
                input_usd_per_1m_tokens, cached_input_usd_per_1m_tokens,
                cache_write_input_usd_per_1m_tokens, output_usd_per_1m_tokens,
                extra_pricing_json, pricing_version, effective_from,
                source, created_by_user_id, created_at, pricing_scheme
            ) values (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?
            )
            """;

    private final JdbcTemplate jdbc;

    public ModelCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc не должен быть null");
    }

    /** Exact immutable catalogue version: never reselect latest. */
    public Optional<ModelCatalogEntry> findById(UUID id) {
        List<ModelCatalogEntry> rows = jdbc.query(
                "select " + SELECT_COLUMNS + " from model_catalog_entries where id = ?",
                this::map, id);
        return rows.stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockModelKey(String modelKey) {
        Objects.requireNonNull(modelKey, "modelKey не должен быть null");
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, "safeai:model-catalog:" + modelKey);
                statement.execute();
            }
            return null;
        });
    }

    /** Includes future-effective rows; used for append-only version allocation. */
    public Optional<ModelCatalogEntry> findLatest(String modelKey) {
        List<ModelCatalogEntry> rows = jdbc.query(
                "select " + SELECT_COLUMNS + " from model_catalog_entries "
                        + "where model_key = ? order by version desc limit 1",
                this::map, modelKey);
        return rows.stream().findFirst();
    }

    public List<ModelCatalogEntry> findVersions(String modelKey, Integer beforeVersion, int limit) {
        if (limit < 1 || limit > 200 || (beforeVersion != null && beforeVersion <= 0)) {
            throw new IllegalArgumentException("Invalid catalog history cursor or limit");
        }
        return jdbc.query(
                "select " + SELECT_COLUMNS + " from model_catalog_entries "
                        + "where model_key = ? and (?::integer is null or version < ?::integer) "
                        + "order by version desc limit ?",
                statement -> {
                    statement.setString(1, modelKey);
                    setNullableInteger(statement, 2, beforeVersion);
                    setNullableInteger(statement, 3, beforeVersion);
                    statement.setInt(4, limit);
                }, this::map);
    }

    public List<ModelCatalogEntry> findLatestPage(String afterModelKey, int limit) {
        validatePage(afterModelKey, limit);
        return jdbc.query(FIND_LATEST_PAGE_SQL, statement -> {
            setNullableText(statement, 1, afterModelKey);
            setNullableText(statement, 2, afterModelKey);
            statement.setInt(3, limit);
        }, this::map);
    }

    /** The version is authoritative; effective_from only gates activation. */
    public Optional<ModelCatalogEntry> findEffective(String modelKey, Instant asOf) {
        List<ModelCatalogEntry> rows = jdbc.query(
                "select " + SELECT_COLUMNS + " from model_catalog_entries "
                        + "where model_key = ? and effective_from <= ? "
                        + "order by version desc limit 1",
                this::map, modelKey, Timestamp.from(asOf));
        return rows.stream().findFirst();
    }

    public List<ModelCatalogEntry> findEffectivePage(Instant asOf, String afterModelKey, int limit) {
        Objects.requireNonNull(asOf, "asOf");
        validatePage(afterModelKey, limit);
        return jdbc.query(FIND_EFFECTIVE_PAGE_SQL, statement -> {
            statement.setTimestamp(1, Timestamp.from(asOf));
            setNullableText(statement, 2, afterModelKey);
            setNullableText(statement, 3, afterModelKey);
            statement.setInt(4, limit);
            statement.setTimestamp(5, Timestamp.from(asOf));
        }, this::map);
    }

    /** Select effective logical snapshot first, then filter physical runtime. */
    public List<ModelCatalogEntry> findEffectiveByRuntime(
            String provider, String providerModelId, Instant asOf) {
        return jdbc.query(FIND_EFFECTIVE_BY_RUNTIME_SQL, this::map,
                Timestamp.from(asOf), provider, providerModelId);
    }

    public boolean hasEffectiveHistoryByRuntime(
            String provider, String providerModelId, Instant asOf) {
        Boolean value = jdbc.queryForObject("""
                select exists (
                    select 1 from model_catalog_entries
                    where provider = ? and provider_model_id = ? and effective_from <= ?
                )
                """, Boolean.class, provider, providerModelId, Timestamp.from(asOf));
        return Boolean.TRUE.equals(value);
    }

    /**
     * Compatibility API. Never certify legacy configured rates as a verified
     * flat-token algorithm without explicit provenance from the caller.
     */
    public ModelCatalogEntry insert(ModelCatalogEntry entry) {
        Objects.requireNonNull(entry, "entry не должен быть null");
        return insert(entry, entry.pricingScheme().databaseValue());
    }

    /**
     * Explicit immutable pricing scheme. PostgreSQL V66 remains the final
     * authority for pricing, modalities and other catalog constraints.
     */
    public ModelCatalogEntry insert(ModelCatalogEntry entry, String pricingScheme) {
        Objects.requireNonNull(entry, "entry не должен быть null");
        if (pricingScheme == null || !(
                pricingScheme.equals("FLAT_TOKEN_V1")
                        || pricingScheme.equals("LEGACY_FLAT_UNVERIFIED_V1")
                        || pricingScheme.equals("UNSUPPORTED_COMPLEX_V1"))) {
            throw new IllegalArgumentException("Unsupported pricingScheme: " + pricingScheme);
        }

        if (!entry.pricingScheme().databaseValue().equals(pricingScheme)) {
            throw new IllegalArgumentException("Catalog price scheme does not match immutable entry");
        }
        int updated = jdbc.update(INSERT_SQL, statement -> {
            int index = 1;
            statement.setObject(index++, entry.id());
            statement.setString(index++, entry.modelKey());
            statement.setInt(index++, entry.version());
            statement.setString(index++, entry.provider());
            statement.setString(index++, entry.providerModelId());
            statement.setString(index++, entry.displayName());
            statement.setString(index++, entry.lifecycle().name());
            statement.setInt(index++, entry.maxInputTokens());
            statement.setInt(index++, entry.maxOutputTokens());
            statement.setBoolean(index++, entry.capabilities().contains(ModelCapability.TOOLS));
            statement.setBoolean(index++, entry.capabilities().contains(ModelCapability.VISION));
            statement.setBoolean(index++, entry.capabilities().contains(ModelCapability.STRUCTURED_OUTPUT));
            statement.setBoolean(index++, entry.inputModalities().contains(ModelModality.TEXT));
            statement.setBoolean(index++, entry.inputModalities().contains(ModelModality.IMAGE));
            statement.setBoolean(index++, entry.inputModalities().contains(ModelModality.AUDIO));
            statement.setBoolean(index++, entry.outputModalities().contains(ModelModality.TEXT));
            statement.setBoolean(index++, entry.outputModalities().contains(ModelModality.AUDIO));
            statement.setString(index++, entry.retentionStatus().name());
            setNullableInteger(statement, index++, entry.retentionDays());
            statement.setString(index++, entry.trainingUseStatus().name());
            statement.setString(index++, entry.pricingStatus().name());
            statement.setBoolean(index++, entry.pricingComplete());
            statement.setBigDecimal(index++, entry.inputUsdPer1mTokens());
            statement.setBigDecimal(index++, entry.cachedInputUsdPer1mTokens());
            statement.setBigDecimal(index++, entry.cacheWriteInputUsdPer1mTokens());
            statement.setBigDecimal(index++, entry.outputUsdPer1mTokens());
            statement.setString(index++, entry.extraPricingJson());
            statement.setString(index++, entry.pricingVersion());
            statement.setTimestamp(index++, Timestamp.from(entry.effectiveFrom()));
            statement.setString(index++, entry.source().name());
            statement.setObject(index++, entry.createdByUserId());
            statement.setTimestamp(index++, Timestamp.from(entry.createdAt()));
            statement.setString(index, pricingScheme);
        });

        if (updated != 1) {
            throw new IllegalStateException("Model catalog version insert affected " + updated + " rows");
        }
        return entry;
    }

    private static void validatePage(String afterModelKey, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("Catalog page limit must be 1..200");
        }
        if (afterModelKey != null && (afterModelKey.isBlank() || afterModelKey.length() > 200)) {
            throw new IllegalArgumentException("Invalid catalog cursor");
        }
    }

    private static void setNullableText(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void setNullableInteger(PreparedStatement statement, int index, Integer value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER);
        else statement.setInt(index, value);
    }

    private ModelCatalogEntry map(ResultSet rs, int ignoredRowNumber) throws SQLException {
        EnumSet<ModelCapability> capabilities = EnumSet.noneOf(ModelCapability.class);
        if (rs.getBoolean("tools_supported")) capabilities.add(ModelCapability.TOOLS);
        if (rs.getBoolean("vision_supported")) capabilities.add(ModelCapability.VISION);
        if (rs.getBoolean("structured_output_supported")) capabilities.add(ModelCapability.STRUCTURED_OUTPUT);

        EnumSet<ModelModality> inputModalities = EnumSet.noneOf(ModelModality.class);
        if (rs.getBoolean("text_input_supported")) inputModalities.add(ModelModality.TEXT);
        if (rs.getBoolean("image_input_supported")) inputModalities.add(ModelModality.IMAGE);
        if (rs.getBoolean("audio_input_supported")) inputModalities.add(ModelModality.AUDIO);

        EnumSet<ModelModality> outputModalities = EnumSet.noneOf(ModelModality.class);
        if (rs.getBoolean("text_output_supported")) outputModalities.add(ModelModality.TEXT);
        if (rs.getBoolean("audio_output_supported")) outputModalities.add(ModelModality.AUDIO);

        return new ModelCatalogEntry(
                rs.getObject("id", UUID.class), rs.getString("model_key"),
                rs.getInt("version"), rs.getString("provider"),
                rs.getString("provider_model_id"), rs.getString("display_name"),
                ModelLifecycle.valueOf(rs.getString("lifecycle")),
                rs.getInt("max_input_tokens"), rs.getInt("max_output_tokens"),
                capabilities, inputModalities, outputModalities,
                ModelRetentionStatus.valueOf(rs.getString("retention_status")),
                rs.getObject("retention_days", Integer.class),
                ModelTrainingUseStatus.valueOf(rs.getString("training_use_status")),
                ModelPricingStatus.valueOf(rs.getString("pricing_status")),
                rs.getBoolean("pricing_complete"),
                rs.getBigDecimal("input_usd_per_1m_tokens"),
                rs.getBigDecimal("cached_input_usd_per_1m_tokens"),
                rs.getBigDecimal("cache_write_input_usd_per_1m_tokens"),
                rs.getBigDecimal("output_usd_per_1m_tokens"),
                rs.getString("extra_pricing_json"), rs.getString("pricing_version"),
                rs.getTimestamp("effective_from").toInstant(),
                ModelCatalogSource.valueOf(rs.getString("source")),
                rs.getObject("created_by_user_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                ModelPricingScheme.fromDatabaseValue(rs.getString("pricing_scheme")));
    }
}
