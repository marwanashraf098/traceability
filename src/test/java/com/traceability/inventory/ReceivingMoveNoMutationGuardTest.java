package com.traceability.inventory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Receiving move M1 test (f): M1 contains NO Shopify mutation. The M1 classes issue no GraphQL mutation, name no
 * inventory / location write, call no ShopifyGateway write (push* / move* / adjust* / resend* / activate*), and the
 * gateway talks to Shopify only through executeGraphQLResponsePublic. The move's write (pushLocationMove) is M2 — it
 * doesn't exist yet, anywhere.
 */
class ReceivingMoveNoMutationGuardTest {

    static final Path MAIN = Path.of("src/main/java/com/traceability");
    static final List<Path> M1 = List.of(
        MAIN.resolve("inventory/ReceivingMoveService.java"),
        MAIN.resolve("inventory/ReceivingMoveController.java"),
        MAIN.resolve("inventory/ReceivingMovePlan.java"),
        MAIN.resolve("inventory/ReceivingMoveException.java"),
        MAIN.resolve("integrations/shopify/ShopifyMoveSourceGateway.java"),
        MAIN.resolve("integrations/shopify/ShopifyMoveSourceGatewayImpl.java"));
    static final List<String> WRITE_WORDS = List.of("inventoryAdjustQuantities", "inventorySetOnHandQuantities",
        "inventorySetQuantities", "inventoryMoveQuantities", "inventoryActivate", "inventoryDeactivate",
        "inventoryBulkToggleActivation", "locationEdit", "locationActivate", "locationDeactivate", "locationAdd",
        "changeFromQuantity", "@idempotent");

    @Test
    void f1_noMutation_noWriteWord() throws IOException {
        for (Path f : M1) {
            String s = Files.readString(f);
            assertThat(Pattern.compile("\\bmutation\\b").matcher(s).find()).as("%s has a mutation", f).isFalse();
            for (String w : WRITE_WORDS) assertThat(s).as("%s mentions %s", f, w).doesNotContain(w);
        }
    }

    @Test
    void f2_noShopifyWriteCall() throws IOException {
        Pattern write = Pattern.compile("\\.(push\\w+|move(?!Session|Sessions)\\w*|adjust\\w+|resend\\w+|activate\\w*|enable\\w+)\\(");
        for (Path f : M1) {
            Matcher m = write.matcher(Files.readString(f));
            Set<String> calls = new TreeSet<>();
            while (m.find()) calls.add(m.group(1));
            assertThat(calls).as("%s calls a write", f).isEmpty();
        }
    }

    @Test
    void f3_theGateway_onlyReads_throughTheGenericGraphqlPath() throws IOException {
        Matcher m = Pattern.compile("\\bshopify\\.(\\w+)\\(")
            .matcher(Files.readString(MAIN.resolve("integrations/shopify/ShopifyMoveSourceGatewayImpl.java")));
        Set<String> calls = new TreeSet<>();
        while (m.find()) calls.add(m.group(1));
        assertThat(calls).containsExactly("executeGraphQLResponsePublic");
    }

    @Test
    void f4_pushLocationMove_notBuiltYet() throws IOException {
        try (var files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                // A declaration or a call (javadoc may name the M2 write).
                assertThat(Pattern.compile("pushLocationMove\\s*\\(").matcher(Files.readString(f)).find())
                    .as("%s declares or calls pushLocationMove", f).isFalse();
            }
        }
    }
}
