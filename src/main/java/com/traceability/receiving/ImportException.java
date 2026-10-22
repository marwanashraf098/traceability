package com.traceability.receiving;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Receiving import (slice 2b) — a file refused as a whole. {code, message_en, message_ar} via
 * ApiExceptionHandler's ApiException handler. Problems with single rows are never an exception:
 * they come back as unmatched rows in the preview.
 */
public class ImportException extends ApiException {

    private ImportException(String code, String en, String ar, HttpStatus status) {
        super(code, en, ar, status);
    }

    private static ImportException bad(String code, String en, String ar) {
        return new ImportException(code, en, ar, HttpStatus.BAD_REQUEST);
    }

    public static ImportException emptyFile() {
        return bad("IMPORT_EMPTY_FILE", "The file is empty.", "الملف فارغ.");
    }

    public static ImportException tooLarge() {
        return bad("IMPORT_FILE_TOO_LARGE", "The file is too large — the limit is 5 MB.",
            "الملف كبير جدًا — الحد الأقصى 5 ميجابايت.");
    }

    public static ImportException tooManyRows() {
        return bad("IMPORT_TOO_MANY_ROWS", "The file has more than " + ShopifyInventoryFile.MAX_ROWS + " rows. Split it into smaller files.",
            "الملف به أكثر من " + ShopifyInventoryFile.MAX_ROWS + " صف. قسّمه إلى ملفات أصغر.");
    }

    public static ImportException unreadable() {
        return bad("IMPORT_UNREADABLE", "Traced couldn't read this file. Upload a .csv or .xlsx file.",
            "تعذّر على Traced قراءة هذا الملف. ارفع ملف ‎.csv‎ أو ‎.xlsx‎.");
    }

    public static ImportException oldExcel() {
        return bad("IMPORT_OLD_EXCEL", "This is an old Excel (.xls) file. Save it as .xlsx or .csv and upload it again.",
            "هذا ملف Excel قديم (‎.xls‎). احفظه بصيغة ‎.xlsx‎ أو ‎.csv‎ وارفعه مرة أخرى.");
    }

    public static ImportException notUtf8() {
        return bad("IMPORT_NOT_UTF8", "The file isn't saved as UTF-8 text. Export it again from Shopify, or save it as CSV UTF-8.",
            "الملف غير محفوظ كنص UTF-8. صدّره مرة أخرى من Shopify، أو احفظه بصيغة CSV UTF-8.");
    }

    public static ImportException notAnExport(List<String> missing) {
        return bad("IMPORT_NOT_SHOPIFY_EXPORT",
            "This doesn't look like a Shopify inventory export — missing column" + (missing.size() == 1 ? ": " : "s: ")
                + String.join(", ", missing.stream().map(ImportException::display).toList()) + ".",
            "لا يبدو هذا ملف تصدير مخزون من Shopify — أعمدة ناقصة: "
                + String.join("، ", missing.stream().map(ImportException::display).toList()) + ".");
    }

    public static ImportException severalLocations() {
        return bad("IMPORT_SEVERAL_LOCATIONS", "This file has several locations. Export one location only from Shopify.",
            "هذا الملف به عدة مواقع. صدّر موقعًا واحدًا فقط من Shopify.");
    }

    public static ImportException nothingToAdd() {
        return bad("IMPORT_NOTHING_TO_ADD", "Choose at least one row to add.", "اختر صفًا واحدًا على الأقل لإضافته.");
    }

    public static ImportException unknownVariant() {
        return bad("IMPORT_UNKNOWN_VARIANT", "A row no longer matches a product in Traced. Upload the file again.",
            "صف لم يعد يطابق منتجًا في Traced. ارفع الملف مرة أخرى.");
    }

    public static ImportException badQuantity() {
        return bad("IMPORT_BAD_QUANTITY", "Every quantity must be a whole number from 1 to " + ReceivingImportService.MAX_QUANTITY + ".",
            "يجب أن تكون كل كمية رقمًا صحيحًا من 1 إلى " + ReceivingImportService.MAX_QUANTITY + ".");
    }

    /** The header as Shopify writes it ("On hand (new)"), not our normalized key. */
    private static String display(String normalized) {
        return ShopifyInventoryFile.SHOPIFY_HEADERS.stream()
            .filter(h -> ShopifyInventoryFile.normalizeHeader(h).equals(normalized)).findFirst().orElse(normalized);
    }
}
