package com.traceability;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Handles ResponseStatusException before ResponseStatusExceptionResolver can call
 * response.sendError(), which would trigger an error dispatch to /error and allow
 * Spring Security to override the status code (e.g. 423 → 401 when the error
 * dispatch runs without an authenticated security context).
 *
 * Returns the status directly as a ResponseEntity — no error dispatch, no /error path.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Void> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).build();
    }
}
