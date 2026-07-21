package com.traceability.inventory;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class LookupNotFoundException extends ResponseStatusException {

    private final String code;
    private final String query;

    public LookupNotFoundException(String code, String query) {
        super(HttpStatus.NOT_FOUND);
        this.code  = code;
        this.query = query;
    }

    public String getCode()  { return code; }
    public String getQuery() { return query; }
}
