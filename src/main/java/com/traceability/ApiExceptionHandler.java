package com.traceability;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.traceability.integrations.shopify.ShopifyOAuthException;
import com.traceability.integrations.shopify.ShopifySessionTokenExchangeException;
import com.traceability.integrations.shopify.ShopifyStoreNeedsReauthException;
import com.traceability.integrations.shopify.ShopifyTransientException;
import com.traceability.inventory.PieceCommittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

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
    // display a reconnect prompt instead of a generic error screen.
    @ExceptionHandler(ShopifyStoreNeedsReauthException.class)
    ResponseEntity<ReauthErrorBody> handleShopifyNeedsReauth(ShopifyStoreNeedsReauthException ex) {
        log.warn("Shopify store needs reauth: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ReauthErrorBody(
                "SHOPIFY_NEEDS_REAUTH",
                "Shopify token is stale or lacks required scopes — reconnect the store in Settings",
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

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Void> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).build();
    }

    // Must be caught explicitly: @PreAuthorize throws AccessDeniedException through Spring MVC,
    // which DispatcherServlet would otherwise pass to ExceptionHandlerExceptionResolver before
    // ExceptionTranslationFilter can invoke the AccessDeniedHandler.
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Void> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Void> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
