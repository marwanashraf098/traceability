package com.traceability.bazar;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * Bazar POS B4 — close the day. Owner / manager only (a worker gets 403); every endpoint answers 404
 * BAZAR_NOT_ENABLED unless {@code tenants.bazar_enabled}. {@code day} is an Africa/Cairo date, yyyy-MM-dd.
 */
@RestController
@RequestMapping("/api/v1/bazar-events/{eventId}/days")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class BazarDaysController {

    private final BazarDayService days;

    public BazarDaysController(BazarDayService days) {
        this.days = days;
    }

    /** Every business day so far: money by method, status (open / closed_balanced / closed_short / changed_after_close). */
    @GetMapping
    public Map<String, Object> list(@PathVariable UUID eventId, @AuthenticationPrincipal CustomUserDetails principal) {
        return days.days(eventId, actor(principal));
    }

    /** The close calculation for what was typed so far — nothing is written. */
    @GetMapping("/{day}/close-preview")
    public Map<String, Object> preview(@PathVariable UUID eventId, @PathVariable String day,
                                       @RequestParam(required = false) BigDecimal cash,
                                       @RequestParam(required = false) BigDecimal card,
                                       @RequestParam(required = false) Boolean instapay,
                                       @RequestParam(required = false) BigDecimal kept,
                                       @RequestParam(name = "float", required = false) BigDecimal openingFloat,
                                       @AuthenticationPrincipal CustomUserDetails principal) {
        return days.preview(eventId, day(day), new BazarDayService.Entered(cash, card, instapay, kept, openingFloat),
            actor(principal));
    }

    /** Close the day (again): one new append-only close row; idempotent on client_request_id. */
    @PostMapping("/{day}/close")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> close(@PathVariable UUID eventId, @PathVariable String day,
                                     @RequestBody CloseRequest req,
                                     @AuthenticationPrincipal CustomUserDetails principal) {
        return days.close(eventId, day(day),
            new BazarDayService.Entered(req.cashCounted(), req.cardSettlement(), req.instapayReceived(),
                req.cashKept(), req.openingFloat()),
            req.note(), req.clientRequestId(), actor(principal));
    }

    private static LocalDate day(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new BazarException(BazarException.Code.BAZAR_INVALID_INPUT, "The day must be a date like 2026-12-01.",
                "يجب أن يكون اليوم تاريخًا مثل 2026-12-01.", HttpStatus.BAD_REQUEST);
        }
    }

    private static BazarSaleService.Actor actor(CustomUserDetails p) {
        return new BazarSaleService.Actor(p.userId(), p.role());
    }

    /** snake_case; camelCase accepted. */
    public record CloseRequest(
        @JsonProperty("cash_counted") @JsonAlias("cashCounted") BigDecimal cashCounted,
        @JsonProperty("card_settlement") @JsonAlias("cardSettlement") BigDecimal cardSettlement,
        @JsonProperty("instapay_received") @JsonAlias("instapayReceived") Boolean instapayReceived,
        @JsonProperty("cash_kept") @JsonAlias("cashKept") BigDecimal cashKept,
        @JsonProperty("opening_float") @JsonAlias("openingFloat") BigDecimal openingFloat,
        String note,
        @JsonProperty("client_request_id") @JsonAlias("clientRequestId") String clientRequestId) {}
}
