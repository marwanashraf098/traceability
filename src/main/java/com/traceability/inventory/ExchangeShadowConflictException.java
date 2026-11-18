package com.traceability.inventory;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 409 EXCHANGE_SHADOW_CONFLICT (B0, 2026-11-18) — the Bosta exchange belongs to a Shopify Return whose live
 * Shopify-exchange shadow order already reserves its replacement (ShopifyShadowOrders.shadowForReturnReference), and the
 * mapping chosen isn't that shadow's own item (a different variant, or more than one line). Mapping it anyway would create
 * an internal:exchange: order next to the shadow and reserve / ship the replacement twice (prod overdue, 2026-11-18).
 */
public class ExchangeShadowConflictException extends ApiException {

    public static final String CODE = "EXCHANGE_SHADOW_CONFLICT";
    static final String MESSAGE_EN = "This exchange was made in Shopify, and Shopify's exchange already reserves a "
        + "different item for it. Release the Shopify exchange reservation (Pick & Pack → Waiting for release) or fix "
        + "the exchange in Shopify first, then map it again.";
    static final String MESSAGE_AR = "تم إنشاء هذا الاستبدال في Shopify، واستبدال Shopify يحجز له منتجًا مختلفًا بالفعل. "
        + "ألغِ حجز استبدال Shopify (التجهيز والتغليف ← بانتظار الإتاحة) أو صحّح الاستبدال في Shopify أولًا، ثم اربطه مرة أخرى.";

    public ExchangeShadowConflictException() {
        super(CODE, MESSAGE_EN, MESSAGE_AR, HttpStatus.CONFLICT);
    }
}
