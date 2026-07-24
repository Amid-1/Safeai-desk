package ru.safeai.gateway.common.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        JwtProperties.class,
        CorsProperties.class,
        ClientIpProperties.class
})
public class SecurityPropertiesConfiguration {
}
