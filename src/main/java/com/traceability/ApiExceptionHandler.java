package com.traceability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
