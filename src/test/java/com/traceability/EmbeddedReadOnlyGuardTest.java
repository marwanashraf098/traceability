package com.traceability;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard: asserts that nothing in the com.traceability.embedded package
 * carries a mutating HTTP mapping annotation.
 *
 * Any future developer adding @PostMapping, @PutMapping, @DeleteMapping, or
 * @PatchMapping to EmbeddedController (or any class in that package) will get a
 * failing build immediately rather than a security review miss.
 *
 * No Spring context — pure reflection against the compiled classes on the classpath.
 */
class EmbeddedReadOnlyGuardTest {

    private static final String EMBEDDED_PACKAGE = "com.traceability.embedded";

    private static final List<Class<?>> MUTATING_ANNOTATIONS = List.of(
            PostMapping.class,
            PutMapping.class,
            DeleteMapping.class,
            PatchMapping.class
    );

    @Test
    void embeddedPackage_containsNoMutatingMappings() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Class<?> clazz : classesInPackage(EMBEDDED_PACKAGE)) {
            // Class-level annotation
            for (Class<?> ann : MUTATING_ANNOTATIONS) {
                if (clazz.isAnnotationPresent(ann.asSubclass(java.lang.annotation.Annotation.class))) {
                    violations.add(clazz.getSimpleName() + " has class-level @" + ann.getSimpleName());
                }
            }
            // Method-level annotations
            for (Method m : clazz.getDeclaredMethods()) {
                for (Class<?> ann : MUTATING_ANNOTATIONS) {
                    if (m.isAnnotationPresent(ann.asSubclass(java.lang.annotation.Annotation.class))) {
                        violations.add(clazz.getSimpleName() + "." + m.getName()
                                + "() has @" + ann.getSimpleName());
                    }
                }
                // Also catch @RequestMapping(method = POST/PUT/DELETE/PATCH)
                RequestMapping rm = m.getAnnotation(RequestMapping.class);
                if (rm != null) {
                    for (RequestMethod method : rm.method()) {
                        if (method == RequestMethod.POST || method == RequestMethod.PUT
                                || method == RequestMethod.DELETE || method == RequestMethod.PATCH) {
                            violations.add(clazz.getSimpleName() + "." + m.getName()
                                    + "() has @RequestMapping(method=" + method + ")");
                        }
                    }
                }
            }
        }

        assertThat(violations)
                .as("EmbeddedController package must contain NO mutating HTTP mappings. "
                        + "Violations found — read EmbeddedController docs before adding mutations.")
                .isEmpty();
    }

    /** Loads all classes under the given package from the test classpath. */
    private List<Class<?>> classesInPackage(String packageName) throws Exception {
        String path = packageName.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL resource = cl.getResource(path);
        if (resource == null) return List.of();

        File dir = new File(resource.toURI());
        if (!dir.isDirectory()) return List.of();

        List<Class<?>> classes = new ArrayList<>();
        for (File file : dir.listFiles()) {
            if (file.getName().endsWith(".class")) {
                String className = packageName + '.'
                        + file.getName().substring(0, file.getName().length() - 6);
                classes.add(Class.forName(className, false, cl));
            }
        }
        return classes;
    }
}
