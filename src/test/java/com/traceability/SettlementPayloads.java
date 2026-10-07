package com.traceability;

/**
 * Bosta delivery payload shapes for the settlement tests — modelled on real prod payloads
 * (values as Bosta sends them: cashCycle numbers often as strings, payout dates mostly only in
 * the transaction id), with no customer data.
 */
final class SettlementPayloads {

    private SettlementPayloads() {}

    /** Delivered COD Send, settled and paid (WEDCOD09SEP26). */
    static final String DELIVERED_PAID = """
        {"type":{"code":10,"value":"Send"},"state":{"code":45},"cod":990,"shipmentFees":92,
         "dropOffAddress":{"city":{"_id":"FceDyHXwpSYYF9zGW","name":"Cairo"}},
         "wallet":{"compensation":null,
           "cashCycle":{"_id":76483748,"deposited_at":"2026-09-01T08:08:44.000Z","deposited_amt":885.12,
                        "cod":"990.00","total_amount":990,"bosta_fees":"104.88","shipping_fees":"85.00",
                        "vat":"12.88","opening_package_fees":"7.00","collection_fees":"0.00",
                        "insurance_fees":"0.00","flex_ship_fees":"0.00","promotion_discount_amount":"0.00"},
           "cashout":{"transaction_id":"WEDCOD09SEP26"}}}
        """;

    /** Return to Origin (refused): Bosta deducts the fee — a NEGATIVE deposit — not paid out yet. */
    static final String RTO_DEPOSITED = """
        {"type":{"code":20,"value":"Return to Origin"},"state":{"code":46},"cod":null,"shipmentFees":68,
         "wallet":{"compensation":null,
           "cashCycle":{"_id":83387281,"deposited_at":"2026-09-01T08:08:44.000Z","deposited_amt":-77.52,
                        "cod":"0.00","total_amount":0,"bosta_fees":"77.52","shipping_fees":"68.00","vat":"9.52",
                        "opening_package_fees":"0.00","collection_fees":"0.00","insurance_fees":"0.00",
                        "flex_ship_fees":"0.00","promotion_discount_amount":"0.00"},
           "cashout":{"next_cashout_date":"2026-09-09T00:00:00.000Z"}}}
        """;

    /** Customer Return Pickup (type 25): fee-only negative deposit, paid in a batch with a stored total. */
    static final String CRP_PAID_WITH_BATCH = """
        {"type":{"code":25,"value":"Customer Return Pickup"},"state":{"code":46},"cod":0,"shipmentFees":93,
         "wallet":{"cashCycle":{"_id":81505826,"deposited_at":"2026-08-17T07:06:54.000Z","deposited_amt":-106.02,
                                "cod":"0.00","bosta_fees":"106.02","shipping_fees":"93.00","vat":"13.02",
                                "opening_package_fees":"0.00","promotion_discount_amount":"0.00"},
                   "cashout":{"amount":"67854.59","transaction_id":"MONCOD24AUG26",
                              "transaction_date":"2026-08-24T00:00:00.000Z"}}}
        """;

    /** Exchange (type 30) covered by a promotion: fees discounted to zero. */
    static final String EXCHANGE_PROMO = """
        {"type":{"code":30,"value":"Exchange"},"state":{"code":45},"cod":0,"shipmentFees":0,
         "wallet":{"cashCycle":{"_id":82189235,"deposited_at":"2026-08-23T07:03:58.000Z","deposited_amt":0,
                                "cod":"0.00","bosta_fees":"0.00","shipping_fees":"94.00","vat":"0.00",
                                "promotion_discount_amount":"94.00"},
                   "cashout":{"next_cashout_date":"2026-09-02T00:00:00.000Z"}}}
        """;

    /** A v2 search item: wallet present but empty — must never clear stored values. */
    static final String V2_ITEM_NULL_WALLET = """
        {"_tracedRawShape":"v2-list","type":{"code":10,"value":"Send"},"state":{"code":45},"cod":840,
         "shipmentFees":59,"pricing":{},
         "wallet":{"cashout":{"next_cashout_date":null},"cashCycle":null,"compensation":null}}
        """;

    /** Delivered, not settled yet: only the quote. */
    static final String DELIVERED_UNSETTLED = """
        {"type":{"code":10,"value":"Send"},"state":{"code":45},"cod":500,"shipmentFees":50,
         "wallet":{"cashout":{"next_cashout_date":null},"cashCycle":null}}
        """;
}
