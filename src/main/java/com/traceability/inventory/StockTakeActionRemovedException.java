package com.traceability.inventory;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 400 STOCK_TAKE_ACTION_REMOVED — a stock-take resolution other than mark_damaged (2026-10-27).
 * Found it / Mark lost were taken off the review screen: a missed piece is counted by scanning it,
 * and finalize writes off what wasn't counted.
 */
public class StockTakeActionRemovedException extends ApiException {

    private StockTakeActionRemovedException(String en, String ar) {
        super("STOCK_TAKE_ACTION_REMOVED", en, ar, HttpStatus.BAD_REQUEST);
    }

    static StockTakeActionRemovedException of(String action) {
        if ("found".equals(action)) {
            return new StockTakeActionRemovedException(
                "\"Found it\" was removed — scan the piece to count it (\"Found one? Scan it\").",
                "تمت إزالة «تم العثور عليها» — امسح القطعة لعدّها («وجدت قطعة؟ امسحها»).");
        }
        if ("lost".equals(action)) {
            return new StockTakeActionRemovedException(
                "\"Mark lost\" was removed — finalize writes off every piece that wasn't counted.",
                "تمت إزالة «وضع علامة مفقود» — عند الإنجاز تُشطب كل قطعة لم تُعدّ.");
        }
        return new StockTakeActionRemovedException(
            "The only stock-take resolution is mark_damaged.",
            "الإجراء الوحيد المتاح في الجرد هو تسجيل القطعة كتالفة.");
    }
}
