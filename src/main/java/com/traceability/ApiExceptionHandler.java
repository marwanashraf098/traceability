package com.traceability;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.traceability.integrations.shopify.ShopifyOAuthException;
import com.traceability.integrations.shopify.ShopifySessionTokenExchangeException;
import com.traceability.integrations.shopify.ShopifyStoreNeedsReauthException;
import com.traceability.integrations.shopify.ShopifyTransientException;
import com.traceability.inventory.AwbMismatchException;
import com.traceability.inventory.LookupNotFoundException;
import com.traceability.inventory.PieceCommittedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

/**
 * Handles exceptions before any resolver can call response.sendError(), which triggers
 * a Servlet error dispatch to /error. On that dispatch, JwtAuthenticationFilter
 * (OncePerRequestFilter) does not re-run, leaving an empty security context. Spring
 * Security's anyRequest().authenticated() then overrides the original status with 401.
 *
 * Returning ResponseEntity directly bypasses sendError() entirely — no error dispatch,
 * no /error path, no status override.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    record OAuthErrorBody(
            String code,
            @JsonProperty("message_en") String messageEn,
            @JsonProperty("message_ar") String messageAr) {}

    record CommittedErrorBody(String error, String orderId, String orderNumber) {}

    record ReauthErrorBody(String error, String message, String shop) {}

    record AwbMismatchBody(
            String code,
            @JsonProperty("scannedAwb")  String scannedAwb,
            @JsonProperty("existingAwb") String existingAwb,
            @JsonProperty("message_en")  String messageEn,
            @JsonProperty("message_ar")  String messageAr) {}

    @ExceptionHandler(AwbMismatchException.class)
    ResponseEntity<AwbMismatchBody> handleAwbMismatch(AwbMismatchException ex) {
        String en = "Scanned AWB " + ex.getScannedAwb()
                  + " does not match this order's AWB " + ex.getExistingAwb() + ".";
        String ar = "رقم الشحن الممسوح " + ex.getScannedAwb()
                  + " لا يطابق رقم شحن الطلب " + ex.getExistingAwb() + ".";
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new AwbMismatchBody("AWB_MISMATCH", ex.getScannedAwb(), ex.getExistingAwb(), en, ar));
    }

    @ExceptionHandler(PieceCommittedException.class)
    ResponseEntity<CommittedErrorBody> handlePieceCommitted(PieceCommittedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new CommittedErrorBody(
                "PIECE_COMMITTED",
                ex.getOrderId() != null ? ex.getOrderId().toString() : null,
                ex.getOrderNumber()));
    }

    @ExceptionHandler(ShopifyOAuthException.class)
    ResponseEntity<OAuthErrorBody> handleShopifyOAuth(ShopifyOAuthException ex) {
        return ResponseEntity.status(ex.httpStatus())
            .body(new OAuthErrorBody(ex.code().name(), ex.messageEn(), ex.messageAr()));
    }

    // Shopify token is stale or lacks required scopes. Returning 403 with JSON so the UI can
    // display a reconnect prompt instead of a generic error screen. The message field forwards
    // the exception detail (e.g. which scope was missing or why Shopify rejected the token)
    // so the root cause is visible in the response without digging through server logs.
    @ExceptionHandler(ShopifyStoreNeedsReauthException.class)
    ResponseEntity<ReauthErrorBody> handleShopifyNeedsReauth(ShopifyStoreNeedsReauthException ex) {
        log.warn("Shopify store needs reauth: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ReauthErrorBody(
                "SHOPIFY_NEEDS_REAUTH",
                ex.getMessage(),
                ex.getShopDomain()));
    }

    // Shopify session-token exchange returned 4xx — the session token was rejected by Shopify.
    // This does NOT mean the refresh token is invalid; do NOT mark the store needs_reauth.
    @ExceptionHandler(ShopifySessionTokenExchangeException.class)
    ResponseEntity<Void> handleSessionTokenExchange(ShopifySessionTokenExchangeException ex) {
        log.warn("Session token exchange failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }

    // Shopify 5xx or timeout on any token operation — transient, no state change.
    @ExceptionHandler(ShopifyTransientException.class)
    ResponseEntity<Void> handleShopifyTransient(ShopifyTransientException ex) {
        log.warn("Shopify transient failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    // Missing traced_refresh cookie on POST /api/v1/auth/refresh → 401 (not 400/500).
    // refresh() uses required=false so this is a safety net for any future @CookieValue endpoint.
    @ExceptionHandler(MissingRequestCookieException.class)
    ResponseEntity<Void> handleMissingCookie(MissingRequestCookieException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    // Missing required @RequestHeader → 401 instead of 400; same contract as missing cookie.
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Void> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    record LookupNotFoundBody(String code, String query) {}

    // Distinguishes genuine lookup misses (PIECE_NOT_FOUND, TRACKING_NOT_FOUND) from
    // DATABASE_ERROR 500s so worker apps can tell the user to re-scan vs. call support.
    @ExceptionHandler(LookupNotFoundException.class)
    ResponseEntity<LookupNotFoundBody> handleLookupNotFound(LookupNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new LookupNotFoundBody(ex.getCode(), ex.getQuery()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Void> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).build();
    }

    record ConstraintBody(String error, String message) {}

    // DataIntegrityViolationException from a DB CHECK or UNIQUE constraint that fires before the
    // Shopify try-catch in a controller (e.g. INSERT with a short name, or ON CONFLICT path).
    // Without this handler the exception reaches handleGeneral and returns a bodyless 500.
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ConstraintBody> handleConstraintViolation(DataIntegrityViolationException ex) {
        String detail = ex.getMostSpecificCause().getMessage();
        log.warn("Data integrity violation: {}", detail);
        return ResponseEntity.badRequest()
            .body(new ConstraintBody("CONSTRAINT_VIOLATION", mapConstraintMessage(detail)));
    }

    private static String mapConstraintMessage(String detail) {
        if (detail == null) return "A data constraint was violated";
        if (detail.contains("locations_name_length"))
            return "Location name must be at least 3 characters";
        if (detail.contains("stores_cc_requires_credentials"))
            return "Store credentials are incomplete — reconnect the store via the custom-app card";
        return "A data constraint was violated";
    }

    record AccessDeniedBody(String error, String path, String authorities) {}

    // Must be caught explicitly: @PreAuthorize throws AccessDeniedException through Spring MVC,
    // which DispatcherServlet would otherwise pass to ExceptionHandlerExceptionResolver before
    // ExceptionTranslationFilter can invoke the AccessDeniedHandler.
    // Logs user + granted authorities + method + URI so role-name mismatches are immediately
    // visible without enabling Spring Security DEBUG. Returns a JSON body so the frontend can
    // distinguish a 403 from a network error and display a useful message.
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<AccessDeniedBody> handleAccessDenied(AccessDeniedException ex,
                                                        HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String user        = auth != null ? auth.getName() : "anonymous";
        String authorities = auth != null
            ? auth.getAuthorities().stream()
                  .map(GrantedAuthority::getAuthority)
                  .collect(Collectors.joining(","))
            : "none";
        log.warn("Access denied: user={} authorities=[{}] {}", user, authorities,
                 request.getMethod() + " " + request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new AccessDeniedBody("ACCESS_DENIED", request.getRequestURI(), authorities));
    }

    record DbErrorBody(String error, String detail) {}

    // Bad SQL grammar = code bug (wrong types, bad cast, typo in column name). Log at ERROR so
    // it surfaces in alerting; return 500 with a JSON body so the frontend isn't left with a blank.
    @ExceptionHandler(BadSqlGrammarException.class)
    ResponseEntity<DbErrorBody> handleBadSqlGrammar(BadSqlGrammarException ex) {
        log.error("Bad SQL grammar (type/cast bug) — fix the SQL: {}",
                  ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new DbErrorBody("DATABASE_ERROR", "SQL type error — contact support"));
    }

    // Generic DataAccessException fallback: catches any remaining Spring DAO exception
    // (connection errors, lock timeouts, etc.) that no more-specific handler claimed.
    // DataIntegrityViolationException and BadSqlGrammarException have their own handlers above.
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<DbErrorBody> handleDataAccess(DataAccessException ex) {
        log.error("Unexpected database error: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new DbErrorBody("DATABASE_ERROR", "Unexpected database error — contact support"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Void> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
