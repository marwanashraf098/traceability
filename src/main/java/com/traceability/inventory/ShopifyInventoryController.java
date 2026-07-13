package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shopify-inventory")
@PreAuthorize("isAuthenticated()")
public class ShopifyInventoryController {

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;

    public ShopifyInventoryController(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txm);
    }

    @GetMapping("/adjustments")
    public Map<String, Object> listAdjustments(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {

        UUID tenantId = TenantContext.require();

        StringBuilder where = new StringBuilder("WHERE a.tenant_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (status != null && !status.isBlank()) {
            where.append(" AND a.status = ?"); params.add(status);
        }
        if (triggerType != null && !triggerType.isBlank()) {
            where.append(" AND a.trigger_type = ?"); params.add(triggerType);
        }
        if (from != null && !from.isBlank()) {
            where.append(" AND a.created_at >= ?::timestamptz"); params.add(from);
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND a.created_at <= ?::timestamptz"); params.add(to);
        }

        String sql = """
                SELECT a.id, a.batch_id, a.variant_id, a.location_id,
                       a.shopify_inventory_item_id, a.shopify_location_id,
                       a.delta, a.trigger_type, a.trigger_id,
                       a.status, a.error, a.created_at, a.applied_at,
                       v.sku, v.title AS variant_title,
                       p.title AS product_title,
                       l.name  AS location_name
                FROM shopify_inventory_adjustments a
                JOIN variants  v ON v.id = a.variant_id
                JOIN products  p ON p.id = v.product_id
                JOIN locations l ON l.id = a.location_id
                """ + where + " ORDER BY a.created_at DESC LIMIT ? OFFSET ?";

        params.add(size);
        params.add((long) page * size);

        List<Map<String, Object>> rows = tx.execute(s ->
            jdbc.queryForList(sql, params.toArray()));

        String countSql = "SELECT COUNT(*) FROM shopify_inventory_adjustments a " + where;
        Long total = tx.execute(s ->
            jdbc.queryForObject(countSql, Long.class, params.subList(0, params.size() - 2).toArray()));

        return Map.of("rows", rows, "total", total != null ? total : 0L,
                      "page", page, "size", size);
    }

    @GetMapping("/adjustments/export.csv")
    public ResponseEntity<String> exportCsv(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        UUID tenantId = TenantContext.require();

        StringBuilder where = new StringBuilder("WHERE a.tenant_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (status != null && !status.isBlank()) {
            where.append(" AND a.status = ?"); params.add(status);
        }
        if (triggerType != null && !triggerType.isBlank()) {
            where.append(" AND a.trigger_type = ?"); params.add(triggerType);
        }
        if (from != null && !from.isBlank()) {
            where.append(" AND a.created_at >= ?::timestamptz"); params.add(from);
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND a.created_at <= ?::timestamptz"); params.add(to);
        }

        String sql = """
                SELECT a.id, a.batch_id, a.trigger_type, a.trigger_id,
                       v.sku, v.title AS variant_title, p.title AS product_title,
                       l.name AS location_name,
                       a.delta, a.status, a.error, a.created_at, a.applied_at
                FROM shopify_inventory_adjustments a
                JOIN variants  v ON v.id = a.variant_id
                JOIN products  p ON p.id = v.product_id
                JOIN locations l ON l.id = a.location_id
                """ + where + " ORDER BY a.created_at DESC";

        List<Map<String, Object>> rows = tx.execute(s ->
            jdbc.queryForList(sql, params.toArray()));

        StringBuilder csv = new StringBuilder();
        csv.append("id,batch_id,trigger_type,trigger_id,sku,variant,product,location,delta,status,error,created_at,applied_at\n");
        for (Map<String, Object> row : rows) {
            csv.append(csvField(row.get("id"))).append(',')
               .append(csvField(row.get("batch_id"))).append(',')
               .append(csvField(row.get("trigger_type"))).append(',')
               .append(csvField(row.get("trigger_id"))).append(',')
               .append(csvField(row.get("sku"))).append(',')
               .append(csvField(row.get("variant_title"))).append(',')
               .append(csvField(row.get("product_title"))).append(',')
               .append(csvField(row.get("location_name"))).append(',')
               .append(csvField(row.get("delta"))).append(',')
               .append(csvField(row.get("status"))).append(',')
               .append(csvField(row.get("error"))).append(',')
               .append(csvField(row.get("created_at"))).append(',')
               .append(csvField(row.get("applied_at"))).append('\n');
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"shopify-inventory-adjustments.csv\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(csv.toString());
    }

    private static String csvField(Object val) {
        if (val == null) return "";
        String s = val.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
