package com.traceability;

import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway must never take its transactional advisory lock: CREATE INDEX CONCURRENTLY (V175) waits on the open
 * transaction that lock lives in, and the migration hangs forever (app startup in prod, the suite in tests).
 */
class FlywayLockGuardTest {

    @Test
    void appConfigUsesTheSessionLock() throws Exception {
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        Object value = sources.stream()
            .map(s -> s.getProperty("spring.flyway.postgresql.transactional-lock"))
            .filter(v -> v != null).findFirst().orElse(null);
        assertThat(String.valueOf(value)).as("spring.flyway.postgresql.transactional-lock in application.yml")
            .isEqualTo("false");
    }

    @Test
    void testHelperUsesTheSessionLock() {
        assertThat(FlywayTestSupport.configure().getPluginRegister()
            .getPlugin(PostgreSQLConfigurationExtension.class).isTransactionalLock()).isFalse();
    }

    @Test
    void noTestCallsFlywayConfigureDirectly() throws Exception {
        Path root = Path.of("src/test/java");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.getFileName().toString().equals("FlywayTestSupport.java"))
                .filter(p -> !p.getFileName().toString().equals("FlywayLockGuardTest.java"))
                .filter(p -> {
                    try {
                        return Files.readString(p).replaceAll("\\s+", "").contains("Flyway.configure(");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .map(p -> root.relativize(p).toString())
                .toList();
        }
        assertThat(offenders).as("use FlywayTestSupport.configure() instead of Flyway.configure()").isEmpty();
    }
}
