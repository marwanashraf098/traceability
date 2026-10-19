package com.traceability;

import com.traceability.inventory.ReturnSessionService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * returns-expected-back review (2026-10-19): the ONLY way to record a returns-session disposition
 * is the checked {@code ReturnSessionService.disposition(..., actorRole)} — the one that refuses a
 * mismatch below manager (403 NEEDS_MANAGER). The unchecked body, {@code dispositionInternal}, must
 * stay private and must never be named by a controller.
 *
 *   g1 reflection: dispositionInternal is private; every non-private disposition* method takes the
 *      caller's role as its last (7th) String parameter
 *   g2 source scan: no @RestController / @Controller under src/main/java names dispositionInternal,
 *      and every controller call to .disposition( passes 7 arguments
 *
 * No Spring context — reflection on the compiled class plus a scan of src/main/java.
 */
class ReturnsDispositionEntryGuardTest {

    @Test
    void g1_uncheckedBodyIsPrivate_everyCallableEntryTakesTheRole() {
        List<Method> methods = Arrays.stream(ReturnSessionService.class.getDeclaredMethods())
            .filter(m -> m.getName().startsWith("disposition")).toList();

        Method internal = methods.stream().filter(m -> m.getName().equals("dispositionInternal")).findFirst().orElseThrow();
        assertThat(Modifier.isPrivate(internal.getModifiers())).as("dispositionInternal skips the role check").isTrue();

        List<Method> callable = methods.stream().filter(m -> !Modifier.isPrivate(m.getModifiers())).toList();
        assertThat(callable).isNotEmpty();
        assertThat(callable).allSatisfy(m -> {
            Class<?>[] p = m.getParameterTypes();
            assertThat(p).as(m + " must take the caller's role").hasSize(7);
            assertThat(p[6]).isEqualTo(String.class);
        });
    }

    @Test
    void g2_noControllerCallsTheUncheckedBody() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                if (!src.contains("@RestController") && !src.contains("@Controller")) continue;
                if (src.contains("dispositionInternal")) violations.add(f + " names dispositionInternal");
                int i = 0;
                while ((i = src.indexOf(".disposition(", i)) >= 0) {
                    int start = i + ".disposition(".length(), depth = 1, p = start, commas = 0;
                    while (depth > 0) {
                        char c = src.charAt(p++);
                        if (c == '(') depth++;
                        else if (c == ')') depth--;
                        else if (c == ',' && depth == 1) commas++;
                    }
                    if (commas != 6) violations.add(f + " calls .disposition( with " + (commas + 1) + " arguments");
                    i = p;
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
