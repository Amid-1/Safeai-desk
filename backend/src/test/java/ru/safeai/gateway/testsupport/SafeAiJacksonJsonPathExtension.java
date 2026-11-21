package ru.safeai.gateway.testsupport;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.Jackson3JsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.Jackson3MappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

/**
 * Replaces JsonPath's json-smart defaults for ALL JUnit Jupiter tests, including
 * individual IDE executions. This is test-only; it does not modify production JSON.
 */
public final class SafeAiJacksonJsonPathExtension implements BeforeAllCallback {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final JsonProvider JSON_PROVIDER = new Jackson3JsonProvider(MAPPER);
    private static final MappingProvider MAPPING_PROVIDER = new Jackson3MappingProvider(MAPPER);

    private static final Configuration.Defaults DEFAULTS = new Configuration.Defaults() {
        @Override
        public JsonProvider jsonProvider() {
            return JSON_PROVIDER;
        }

        @Override
        public MappingProvider mappingProvider() {
            return MAPPING_PROVIDER;
        }

        @Override
        public Set<Option> options() {
            return Set.of();
        }
    };

    @Override
    public void beforeAll(ExtensionContext context) {
        Configuration.setDefaults(DEFAULTS);
    }
}
