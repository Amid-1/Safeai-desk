package ru.safeai.gateway.testsupport;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.Jackson3JsonProvider;
import com.jayway.jsonpath.spi.mapper.Jackson3MappingProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonPathSecurityClasspathTest {

    @Test
    void vulnerableJsonSmartIsNotOnTestClasspath() {
        assertThatThrownBy(() -> Class.forName("net.minidev.json.JSONValue"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void jsonPathRetainsAssertionsThroughJacksonThree() {
        Configuration configuration = Configuration.defaultConfiguration();
        assertThat(configuration.jsonProvider()).isInstanceOf(Jackson3JsonProvider.class);
        assertThat(configuration.mappingProvider()).isInstanceOf(Jackson3MappingProvider.class);
        assertThat((Integer) JsonPath.read("{\"value\":42}", "$.value")).isEqualTo(42);
    }

    @Test
    void rejectsControlCharacterThatBypassedJsonSmartStrictValidation() {
        String invalid = "{\"value\":42}" + (char) 0x1A + "{garbage}";
        assertThatThrownBy(() -> JsonPath.read(invalid, "$.value"))
                .isInstanceOf(RuntimeException.class);
    }
}
