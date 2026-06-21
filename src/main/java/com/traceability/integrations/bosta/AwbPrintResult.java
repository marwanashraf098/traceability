package com.traceability.integrations.bosta;

/**
 * Result of a single Bosta mass-awb API call.
 *
 * Inline path (≤50 AWBs per call): pdfBytes is set, emailMessage is null.
 * Email path (Bosta went async): pdfBytes is null, emailMessage is set.
 */
public record AwbPrintResult(byte[] pdfBytes, String emailMessage) {
    public boolean isInline() { return pdfBytes != null; }
}
