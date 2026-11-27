package com.traceability;

import com.traceability.inventory.MainReconcileService;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * Bazar U1d — for the bazar tests written before the Keep guard: Keep showing online ('leave') stays available
 * (MainReconcileService.writesFor answers true) while their contexts keep Main reconcile off / report-only.
 * Everything else on the bean is the real MainReconcileService (a Mockito spy). Use with {@code @Import}; each test
 * class already has its own context, so this builds none extra. BazarKeepGuardTest covers the real answer.
 */
@TestConfiguration
public class KeepAvailableForTests {

    @Bean
    static BeanPostProcessor keepAvailableForTestsSpy() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof MainReconcileService s)) return bean;
                MainReconcileService spied = spy(s);
                doReturn(true).when(spied).writesFor(any());
                return spied;
            }
        };
    }
}
