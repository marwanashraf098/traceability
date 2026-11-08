package com.traceability.portal;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Shopify-native returns S5a — a refused "Try again" on the request drawer's Shopify row, as
 * {@code {code, message_en, message_ar}} (ApiExceptionHandler) so the drawer shows WHY nothing started instead of
 * silently doing nothing (prod 2026-11-08, RR-ZMCAUG: a Try again left no trace).
 */
public class ShopifyReturnSyncException extends ApiException {

    private ShopifyReturnSyncException(String code, String en, String ar) {
        super(code, en, ar, HttpStatus.CONFLICT);
    }

    static ShopifyReturnSyncException switchedOff() {
        return new ShopifyReturnSyncException("SHOPIFY_SYNC_OFF",
            "Creating returns in Shopify is switched off (Settings › Returns portal), so nothing was started.",
            "إنشاء المرتجعات في شوبيفاي متوقف (الإعدادات › بوابة المرتجعات)، لذلك لم يبدأ شيء.");
    }

    static ShopifyReturnSyncException notApplicable() {
        return new ShopifyReturnSyncException("SHOPIFY_SYNC_NOT_APPLICABLE",
            "This request isn't created in Shopify (it was decided before the switch was turned on, or it isn't approved).",
            "هذا الطلب لا يُنشأ في شوبيفاي (تم البت فيه قبل تشغيل الخيار، أو لم تتم الموافقة عليه).");
    }

    static ShopifyReturnSyncException inProgress() {
        return new ShopifyReturnSyncException("SHOPIFY_SYNC_IN_PROGRESS",
            "Traced is creating this return in Shopify right now. Check again in a few minutes.",
            "يقوم Traced بإنشاء هذا المرتجع في شوبيفاي الآن. تحقق مرة أخرى بعد بضع دقائق.");
    }

    static ShopifyReturnSyncException alreadyInShopify() {
        return new ShopifyReturnSyncException("SHOPIFY_SYNC_ALREADY_CREATED",
            "This return is already in Shopify — there's nothing to try again.",
            "هذا المرتجع موجود بالفعل في شوبيفاي — لا يوجد ما يمكن إعادة محاولته.");
    }

    static ShopifyReturnSyncException changedMeanwhile() {
        return new ShopifyReturnSyncException("SHOPIFY_SYNC_CHANGED",
            "This request's Shopify state changed just now. Refresh and try again.",
            "تغيرت حالة هذا الطلب في شوبيفاي للتو. حدّث الصفحة وحاول مرة أخرى.");
    }
}
