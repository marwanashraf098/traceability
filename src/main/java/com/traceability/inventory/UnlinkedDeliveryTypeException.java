package com.traceability.inventory;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link ShipmentLinkService#manualLink} when an unlinked Bosta delivery's type
 * (the stored payload's {@code type.code}) is not one that may become an order's forward
 * leg — see {@link ShipmentLinkService#FORWARD_LINKABLE_TYPE_CODES}. Thrown before any write.
 * Flows through the generic {@link ApiException} handler: 422 with {code, message_en, message_ar}.
 */
public class UnlinkedDeliveryTypeException extends ApiException {

    public static final String CODE = "UNLINKED_DELIVERY_TYPE_NOT_LINKABLE";

    public UnlinkedDeliveryTypeException(String trackingNumber) {
        super(CODE,
            "Bosta delivery " + trackingNumber + " isn't a regular delivery (for example a return pickup "
                + "or an exchange), so it can't be linked as this order's shipment.",
            "شحنة بوسطة " + trackingNumber + " ليست شحنة توصيل عادية (مثل استرجاع أو استبدال)، "
                + "لذلك لا يمكن ربطها كشحنة لهذا الطلب.",
            HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
