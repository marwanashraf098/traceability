package com.traceability.inventory;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Shopify order edits (2026-10-21): every line of the order was removed in Shopify (all quantities 0 —
 * a removed line is kept, OrderLineFreeze), so there is nothing to pack. complete() refuses instead of
 * packing an empty parcel. 409 with the usual {code, message_en, message_ar} body (ApiExceptionHandler);
 * the waybill pack session shows it as a complete failure with this code.
 */
public class AllItemsRemovedException extends ApiException {

    public static final String CODE = "ALL_ITEMS_REMOVED_IN_SHOPIFY";

    public AllItemsRemovedException() {
        super(CODE,
            "Every item on this order was removed in Shopify — there is nothing to pack.",
            "تمت إزالة كل المنتجات من هذا الطلب في Shopify — لا يوجد شيء لتغليفه.",
            HttpStatus.CONFLICT);
    }
}
