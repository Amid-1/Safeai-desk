package ru.safeai.gateway.model.repository;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelCatalogSource;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;

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
            id,
            model_key,
            version,
            provider,
            provider_model_id,
            display_name,
            lifecycle,
            max_input_tokens,
            max_output_tokens,
            tools_supported,
            vision_supported,
            structured_output_supported,
            text_input_supported,
            image_input_supported,
            audio_input_supported,
            text_output_supported,
            audio_output_supported,
            retention_status,
            retention_days,
            training_use_status,
            pricing_status,
            pricing_complete,
            input_usd_per_1m_tokens,
            cached_input_usd_per_1m_tokens,
            cache_write_input_usd_per_1m_tokens,
            output_usd_per_1m_tokens,
            extra_pricing_json::text as extra_pricing_json,
            pricing_version,
            effective_from,
            source,
            created_by_user_id,
            created_at
            """;

    private static final String FIND_LATEST_PAGE_SQL =
            "select * from ("
                    + "select distinct on (model_key) "
                    + SELECT_COLUMNS
                    + " from model_catalog_entries "
                    + "order by model_key, version desc"
                    + ") latest "
                    + "where (?::text is null or model_key > ?::text) "
                    + "order by model_key limit ?";

    private static final String FIND_EFFECTIVE_PAGE_SQL =
            "select * from ("
                    + "select distinct on (model_key) "
                    + SELECT_COLUMNS
                    + " from model_catalog_entries "
                    + "where effective_from <= ? "
                    + "order by model_key, version desc"
                    + ") effective "
                    + "where (?::text is null or model_key > ?::text) "
                    + "order by model_key limit ?";

    private static final String FIND_EFFECTIVE_BY_RUNTIME_SQL =
            "select * from ("
                    + "select distinct on (model_key) "
                    + SELECT_COLUMNS
                    + " from model_catalog_entries "
                    + "where effective_from <= ? "
                    + "order by model_key, version desc"
                    + ") effective "
                    + "where provider = ? "
                    + "and provider_model_id = ? "
                    + "order by model_key";

    private final JdbcTemplate jdbc;

    public ModelCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(
                jdbc,
                "jdbc не должен быть null"
        );
    }

    /** Exact immutable catalogue version (NEVER latest/effective re-selection). */
    public Optional<ModelCatalogEntry> findById(UUID id) {
        List<ModelCatalogEntry> rows = jdbc.query(
                "select " + SELECT_COLUMNS + " from model_catalog_entries where id = ?",
                this::map, id);
        return rows.stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockModelKey(String modelKey) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?, 0))"
            )) {
                statement.setString(
                        1,
                        "safeai:model-catalog:" + modelKey
                );
                statement.execute();
            }
            return null;
        });
    }

    /**
     * Latest created version, regardless of effective_from. This method is for
     * append-only version allocation and administrative views. Runtime routing
     * must use {@link #findEffective(String, Instant)}. A scheduled future
     * version is intentionally visible here.
     */
    public Optional<ModelCatalogEntry> findLatest(String modelKey) {
        List<ModelCatalogEntry> rows = jdbc.query(
                "select " + SELECT_COLUMNS + " from model_catalog_entries "
                        + "where model_key = ? order by version desc limit 1",
                this::map,
                modelKey
        );
        return rows.stream().findFirst();
    }

    /**
     * Bounded append-only history page of one logical model key, newest version first.
     * Routing itself still uses a server-clock-effective immutable snapshot.
     */
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
                },
                this::map
        );
    }

    /** Bounded latest-created page, including future-effective rows. */
    public List<ModelCatalogEntry> findLatestPage(String afterModelKey, int limit) {
        validatePage(afterModelKey, limit);
        return jdbc.query(
                FIND_LATEST_PAGE_SQL,
                statement -> {
                    setNullableText(statement, 1, afterModelKey);
                    setNullableText(statement, 2, afterModelKey);
                    statement.setInt(3, limit);
                },
                this::map
        );
    }

    /**
     * Effective snapshot for one logical model key at a server-controlled time.
     * Version is the supersession order; effective_from is only the activation
     * gate. Once a later version becomes effective, its higher version number
     * supersedes every earlier version, even if its activation timestamp was
     * backdated.
     */
    public Optional<ModelCatalogEntry> findEffective(
            String modelKey,
            Instant asOf
    ) {
        List<ModelCatalogEntry> rows = jdbc.query(
                "select " + SELECT_COLUMNS + " from model_catalog_entries "
                        + "where model_key = ? and effective_from <= ? "
                        + "order by version desc limit 1",
                this::map,
                modelKey,
                Timestamp.from(asOf)
        );
        return rows.stream().findFirst();
    }

    /** Bounded effective snapshot page at the supplied instant. */
    public List<ModelCatalogEntry> findEffectivePage(
            Instant asOf,
            String afterModelKey,
            int limit
    ) {
        Objects.requireNonNull(asOf, "asOf");
        validatePage(afterModelKey, limit);
        return jdbc.query(
                FIND_EFFECTIVE_PAGE_SQL,
                statement -> {
                    statement.setTimestamp(1, Timestamp.from(asOf));
                    setNullableText(statement, 2, afterModelKey);
                    setNullableText(statement, 3, afterModelKey);
                    statement.setInt(4, limit);
                },
                this::map
        );
    }

    /**
     * Returns effective logical snapshots whose physical runtime identity
     * matches exactly. The effective snapshot is selected before provider/model
     * filtering. This prevents a stale older version of the same model_key from
     * becoming executable merely because it still matches the active runtime.
     */
    public List<ModelCatalogEntry> findEffectiveByRuntime(
            String provider,
            String providerModelId,
            Instant asOf
    ) {
        return jdbc.query(
                FIND_EFFECTIVE_BY_RUNTIME_SQL,
                this::map,
                Timestamp.from(asOf),
                provider,
                providerModelId
        );
    }

    /**
     * Returns whether this physical runtime identity has ever been governed by
     * a catalog version that was effective at or before {@code asOf}. Future-only
     * scheduled rows do not disable bootstrap compatibility early.
     */
    public boolean hasEffectiveHistoryByRuntime(
            String provider,
            String providerModelId,
            Instant asOf
    ) {
        Boolean value = jdbc.queryForObject(
                """
                select exists (
                    select 1
                    from model_catalog_entries
                    where provider = ?
                      and provider_model_id = ?
                      and effective_from <= ?
                )
                """,
                Boolean.class,
                provider,
                providerModelId,
                Timestamp.from(asOf)
        );
        return Boolean.TRUE.equals(value);
    }

    public ModelCatalogEntry insert(ModelCatalogEntry entry) {
        Objects.requireNonNull(entry, "entry не должен быть null");
        int updated = jdbc.update(
                """
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
                    source, created_by_user_id, created_at
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?
                )
                """,
                statement -> {
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
                    statement.setTimestamp(index, Timestamp.from(entry.createdAt()));
                }
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Model catalog version insert affected "
                            + updated
                            + " rows"
            );
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

    private static void setNullableText(
            PreparedStatement statement,
            int index,
            String value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableInteger(
            PreparedStatement statement,
            int index,
            Integer value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private ModelCatalogEntry map(
            ResultSet rs,
            int ignoredRowNumber
    ) throws SQLException {
        EnumSet<ModelCapability> capabilities =
                EnumSet.noneOf(ModelCapability.class);
        if (rs.getBoolean("tools_supported")) {
            capabilities.add(ModelCapability.TOOLS);
        }
        if (rs.getBoolean("vision_supported")) {
            capabilities.add(ModelCapability.VISION);
        }
        if (rs.getBoolean("structured_output_supported")) {
            capabilities.add(ModelCapability.STRUCTURED_OUTPUT);
        }

        EnumSet<ModelModality> inputModalities =
                EnumSet.noneOf(ModelModality.class);
        if (rs.getBoolean("text_input_supported")) {
            inputModalities.add(ModelModality.TEXT);
        }
        if (rs.getBoolean("image_input_supported")) {
            inputModalities.add(ModelModality.IMAGE);
        }
        if (rs.getBoolean("audio_input_supported")) {
            inputModalities.add(ModelModality.AUDIO);
        }

        EnumSet<ModelModality> outputModalities =
                EnumSet.noneOf(ModelModality.class);
        if (rs.getBoolean("text_output_supported")) {
            outputModalities.add(ModelModality.TEXT);
        }
        if (rs.getBoolean("audio_output_supported")) {
            outputModalities.add(ModelModality.AUDIO);
        }

        return new ModelCatalogEntry(
                rs.getObject("id", UUID.class),
                rs.getString("model_key"),
                rs.getInt("version"),
                rs.getString("provider"),
                rs.getString("provider_model_id"),
                rs.getString("display_name"),
                ModelLifecycle.valueOf(rs.getString("lifecycle")),
                rs.getInt("max_input_tokens"),
                rs.getInt("max_output_tokens"),
                capabilities,
                inputModalities,
                outputModalities,
                ModelRetentionStatus.valueOf(
                        rs.getString("retention_status")
                ),
                rs.getObject("retention_days", Integer.class),
                ModelTrainingUseStatus.valueOf(
                        rs.getString("training_use_status")
                ),
                ModelPricingStatus.valueOf(
                        rs.getString("pricing_status")
                ),
                rs.getBoolean("pricing_complete"),
                rs.getBigDecimal("input_usd_per_1m_tokens"),
                rs.getBigDecimal("cached_input_usd_per_1m_tokens"),
                rs.getBigDecimal("cache_write_input_usd_per_1m_tokens"),
                rs.getBigDecimal("output_usd_per_1m_tokens"),
                rs.getString("extra_pricing_json"),
                rs.getString("pricing_version"),
                rs.getTimestamp("effective_from").toInstant(),
                ModelCatalogSource.valueOf(rs.getString("source")),
                rs.getObject("created_by_user_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
