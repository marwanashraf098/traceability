package com.traceability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar customer details (2026-11-26): a customer's phone or name never travels in a URL — URLs end up in access
 * logs. No bazar endpoint (a controller in com.traceability.bazar, or any controller whose path mentions "bazar")
 * takes a {@code @RequestParam} whose name mentions phone or name; they come in the JSON body. Reflection, so a
 * renamed parameter ({@code @RequestParam("phone") String p}) is caught too.
 *
 * Revert check: putting {@code @RequestParam String phone} back on {@code BazarSalesController.findSales} turns this red.
 */
class BazarNoPersonalQueryParamGuardTest {

    @Test
    void noBazarEndpoint_takesAPhoneOrNameQueryParameter() throws Exception {
        Set<Class<?>> controllers = bazarControllers();
        assertThat(controllers).as("the scan finds the bazar controllers")
            .extracting(Class::getSimpleName).contains("BazarSalesController", "BazarCustomerDataController");

        List<String> offending = new ArrayList<>();
        int params = 0;
        for (Class<?> c : controllers) {
            for (Method m : c.getDeclaredMethods()) {
                for (Parameter p : m.getParameters()) {
                    RequestParam rp = p.getAnnotation(RequestParam.class);
                    if (rp == null) continue;
                    params++;
                    String name = !rp.name().isEmpty() ? rp.name() : !rp.value().isEmpty() ? rp.value() : p.getName();
                    assertThat(name).as("compiled with -parameters").doesNotMatch("arg\\d+");
                    String n = name.toLowerCase(Locale.ROOT);
                    if (n.contains("phone") || n.contains("name") || n.contains("customer")) {
                        offending.add(c.getSimpleName() + "." + m.getName() + "(" + name + ")");
                    }
                }
            }
        }
        assertThat(params).as("the scan sees the existing query parameters (lang, code, …)").isPositive();
        assertThat(offending).as("put these in the JSON body").isEmpty();
    }

    private static Set<Class<?>> bazarControllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<Class<?>> out = new LinkedHashSet<>();
        for (BeanDefinition bd : scan.findCandidateComponents("com.traceability")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (c.getPackageName().equals("com.traceability.bazar") || mentionsBazar(c)) out.add(c);
        }
        return out;
    }

    private static boolean mentionsBazar(Class<?> c) {
        List<String> paths = new ArrayList<>();
        RequestMapping cm = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
        if (cm != null) paths.addAll(Arrays.asList(cm.path()));
        for (Method m : c.getDeclaredMethods()) {
            RequestMapping mm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
            if (mm != null) paths.addAll(Arrays.asList(mm.path()));
        }
        return paths.stream().anyMatch(p -> p.toLowerCase(Locale.ROOT).contains("bazar"));
    }
}
