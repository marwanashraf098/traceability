package com.traceability;

import com.traceability.inventory.ShopifyShadowOrders;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The S3c switch ({@link ShopifyShadowOrders#S3C_FLAG}) is set in prod by ONE env var,
 * TRACED_SHOPIFY_EXCHANGE_SHIPPED_ALERT_ENABLED (application.yml maps it; default true). f1 unset → true; f2 "false" →
 * false — the same placeholder resolution @Value("${traced.shopify-exchange.shipped-alert-enabled:true}") goes through.
 */
class ShopifyExchangeFlagEnvTest {

    static String resolve(Map<String, Object> env) throws Exception {
        StandardEnvironment e = new StandardEnvironment();
        e.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        e.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        e.getPropertySources().addFirst(new SystemEnvironmentPropertySource("env", env));
        List<PropertySource<?>> yml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        yml.forEach(e.getPropertySources()::addLast);
        return e.resolvePlaceholders("${" + ShopifyShadowOrders.S3C_FLAG + ":true}");
    }

    @Test
    void f1_unset_defaultsOn() throws Exception {
        assertThat(resolve(Map.of())).isEqualTo("true");
    }

    @Test
    void f2_envVarFalse_turnsItOff() throws Exception {
        assertThat(resolve(Map.of("TRACED_SHOPIFY_EXCHANGE_SHIPPED_ALERT_ENABLED", "false"))).isEqualTo("false");
    }
}
