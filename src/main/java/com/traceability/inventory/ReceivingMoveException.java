package com.traceability.inventory;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Receiving move M1 — every refusal of a "Moving from my Shopify location" session, {code, message_en, message_ar,
 * details?} (ApiExceptionHandler). details only where the screen needs more than words (the move gate's deep link).
 */
public class ReceivingMoveException extends RuntimeException {

    public enum Code {
        /** The store isn't ready for any Shopify stock update: no store, reconnect needed, scopes, main not linked,
         *  main inactive / deleted (IncrementRecoveryRules.setupProblem — the message is its fix). */
        MOVE_SHOPIFY_NOT_READY,
        /** A move session receives into the main warehouse only. */
        MOVE_MAIN_WAREHOUSE_ONLY,
        /** The chosen location isn't one of the merchant's own active Shopify locations (or is Traced's). */
        MOVE_SOURCE_NOT_ELIGIBLE,
        /** The move gate: Traced Main isn't VERIFIED Ready by a fresh read (details: readiness, link). */
        MOVE_SHIPPING_NOT_READY,
        /** A returned item can't be received in a move session. */
        MOVE_RETURN_LINE,
        /** M1 only: finishing a move session arrives with M2's write. */
        MOVE_FINALIZE_NOT_AVAILABLE,
        /** M1: a move session is print-and-stick only until count-only / factory moves are tested (2026-11-18). */
        MOVE_MODE_NOT_AVAILABLE,
        /** The session isn't a move session. */
        NOT_A_MOVE_SESSION,
        /** Shopify couldn't be read right now (codes only, never a body). */
        MOVE_SHOPIFY_READ_FAILED
    }

    private final Code code;
    private final String messageEn;
    private final String messageAr;
    private final HttpStatus status;
    private final Map<String, Object> details;

    public ReceivingMoveException(Code code, HttpStatus status, String messageEn, String messageAr,
                                  Map<String, Object> details) {
        super(code + ": " + messageEn);
        this.code = code;
        this.status = status;
        this.messageEn = messageEn;
        this.messageAr = messageAr;
        this.details = details;
    }

    public Code code() { return code; }
    public String messageEn() { return messageEn; }
    public String messageAr() { return messageAr; }
    public HttpStatus httpStatus() { return status; }
    public Map<String, Object> details() { return details; }

    static ReceivingMoveException setup(IncrementRecoveryRules.SetupProblem p) {
        return new ReceivingMoveException(Code.MOVE_SHOPIFY_NOT_READY, HttpStatus.CONFLICT,
            "Moving stock from a Shopify location needs your Shopify connection working: " + p.fixEn + ".",
            "نقل المخزون من موقع في Shopify يحتاج إلى اتصال Shopify يعمل: " + p.fixAr + ".",
            Map.of("problem", p.name()));
    }

    static ReceivingMoveException mainWarehouseOnly() {
        return new ReceivingMoveException(Code.MOVE_MAIN_WAREHOUSE_ONLY, HttpStatus.CONFLICT,
            "Stock moved from a Shopify location is received into your main warehouse only.",
            "المخزون المنقول من موقع في Shopify يُستلم في مستودعك الرئيسي فقط.", null);
    }

    static ReceivingMoveException sourceNotEligible() {
        return new ReceivingMoveException(Code.MOVE_SOURCE_NOT_ELIGIBLE, HttpStatus.CONFLICT,
            "Choose one of your own active Shopify locations to move stock from.",
            "اختر أحد مواقعك النشطة في Shopify لنقل المخزون منه.", null);
    }

    static ReceivingMoveException returnLine() {
        return new ReceivingMoveException(Code.MOVE_RETURN_LINE, HttpStatus.CONFLICT,
            "A returned item can't be received in a session that moves stock from a Shopify location. Receive it in a separate session.",
            "لا يمكن استلام منتج مرتجع في جلسة تنقل المخزون من موقع في Shopify. استلمه في جلسة منفصلة.", null);
    }

    static ReceivingMoveException finalizeNotAvailable() {
        return new ReceivingMoveException(Code.MOVE_FINALIZE_NOT_AVAILABLE, HttpStatus.CONFLICT,
            "Finishing a session that moves stock from a Shopify location isn't available yet.",
            "إنهاء جلسة تنقل المخزون من موقع في Shopify غير متاح بعد.", null);
    }

    static ReceivingMoveException modeNotAvailable() {
        return new ReceivingMoveException(Code.MOVE_MODE_NOT_AVAILABLE, HttpStatus.CONFLICT,
            "Moving stock from a Shopify location works with printed labels only for now. Choose \"Print and stick labels\".",
            "نقل المخزون من موقع في Shopify متاح حالياً مع طباعة الملصقات فقط. اختر \"طباعة الملصقات ولصقها\".", null);
    }

    static ReceivingMoveException notAMoveSession() {
        return new ReceivingMoveException(Code.NOT_A_MOVE_SESSION, HttpStatus.CONFLICT,
            "This session doesn't move stock from a Shopify location.",
            "هذه الجلسة لا تنقل المخزون من موقع في Shopify.", null);
    }

    static ReceivingMoveException readFailed() {
        return new ReceivingMoveException(Code.MOVE_SHOPIFY_READ_FAILED, HttpStatus.BAD_GATEWAY,
            "Traced couldn't read your Shopify locations right now. Try again in a moment.",
            "لم يتمكن Traced من قراءة مواقعك في Shopify الآن. حاول مرة أخرى بعد قليل.", null);
    }
}
