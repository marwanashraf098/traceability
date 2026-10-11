package com.traceability.integrations.shopify;

import java.util.List;

/** A GraphQL response with errors and no usable data; {@code codes} are the errors' extensions.code values. */
public class ShopifyGraphQLErrorException extends ShopifyException {
    private final List<String> codes;

    public ShopifyGraphQLErrorException(String message, List<String> codes) {
        super(message + " " + codes);
        this.codes = List.copyOf(codes);
    }

    public List<String> codes() { return codes; }
}
