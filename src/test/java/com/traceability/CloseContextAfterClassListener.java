package com.traceability;

import org.springframework.core.Ordered;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Closes each test class's ApplicationContext at the end of the class, while its Postgres container is still up.
 * Every test class builds its own context (its own container URL via @DynamicPropertySource), so none is ever
 * reused by another class. Left to the cache, the context was closed only when the NEXT class evicted it — after
 * Testcontainers had stopped the container — and a context running JobRunr's BackgroundJobServer then waited
 * ~15 s on the dead database (JobRunr 10 s + Hikari 4 s), paid by whichever class came next.
 *
 * <p>Testcontainers stops a class's containers when the class's JUnit extension store closes, which is after every
 * afterAll callback (SpringExtension's included), so this runs first. HIGHEST_PRECEDENCE = last in the after-class
 * phase, so no other listener asks for the context after it is closed. Registered in META-INF/spring.factories.
 */
public class CloseContextAfterClassListener implements TestExecutionListener, Ordered {

    @Override
    public void afterTestClass(TestContext testContext) {
        if (testContext.hasApplicationContext()) {
            testContext.markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
