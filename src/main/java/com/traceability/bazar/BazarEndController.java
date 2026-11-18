package com.traceability.bazar;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bazar POS B5 — end the event, bring the leftovers home on its linked transfer, and the event report. Owner /
 * manager only (a worker gets 403), except scan-back and the leftovers list: any role in the event's seller_roles
 * (BazarEndService checks it). Every endpoint answers 404 BAZAR_NOT_ENABLED unless {@code tenants.bazar_enabled}.
 */
@RestController
@RequestMapping("/api/v1/bazar-events/{eventId}")
public class BazarEndController {

    private final BazarEndService end;
    private final BazarReportService report;

    public BazarEndController(BazarEndService end, BazarReportService report) {
        this.end = end;
        this.report = report;
    }

    /** live → ended (idempotent); refused while a day with money is open or changed after its close. */
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @PostMapping("/end")
    public Map<String, Object> endEvent(@PathVariable UUID eventId, @AuthenticationPrincipal CustomUserDetails p) {
        return end.end(eventId, actor(p));
    }

    /** Any role in the event's seller_roles (checked in BazarEndService), owner / manager always. */
    @GetMapping("/leftovers")
    public Map<String, Object> leftovers(@PathVariable UUID eventId, @AuthenticationPrincipal CustomUserDetails p) {
        return end.leftovers(eventId, actor(p));
    }

    /** Any role in the event's seller_roles (checked in BazarEndService), owner / manager always. */
    @PostMapping("/scan-back")
    public Map<String, Object> scanBack(@PathVariable UUID eventId, @RequestBody ScanBackRequest req,
                                        @AuthenticationPrincipal CustomUserDetails p) {
        return end.scanBack(eventId, req.code(), req.clientRequestId(), actor(p));
    }

    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @PostMapping("/mark-lost")
    public Map<String, Object> markLost(@PathVariable UUID eventId, @RequestBody MarkLostRequest req,
                                        @AuthenticationPrincipal CustomUserDetails p) {
        return end.markLost(eventId, req.pieceIds(), actor(p));
    }

    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @PostMapping("/close-transfer")
    public Map<String, Object> closeTransfer(@PathVariable UUID eventId, @AuthenticationPrincipal CustomUserDetails p) {
        return end.closeTransfer(eventId, actor(p));
    }

    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @GetMapping("/report")
    public Map<String, Object> report(@PathVariable UUID eventId, @AuthenticationPrincipal CustomUserDetails p) {
        return report.report(eventId, actor(p));
    }

    /** name, phone, pieces, total, last visit — audit-logged. */
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @GetMapping("/report/customers.csv")
    public ResponseEntity<byte[]> customersCsv(@PathVariable UUID eventId, @AuthenticationPrincipal CustomUserDetails p) {
        byte[] body = ("﻿" + report.customersCsv(eventId, actor(p))).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bazar-customers.csv\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(body);
    }

    private static BazarSaleService.Actor actor(CustomUserDetails p) {
        return new BazarSaleService.Actor(p.userId(), p.role());
    }

    public record ScanBackRequest(String code,
                                  @JsonProperty("client_request_id") @JsonAlias("clientRequestId") String clientRequestId) {}

    public record MarkLostRequest(@JsonProperty("piece_ids") @JsonAlias("pieceIds") List<String> pieceIds) {}
}
