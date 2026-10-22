package com.traceability.receiving;

import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import java.io.InputStream;

/**
 * Receiving import (slice 2b) — a Shopify inventory export (CSV, or .xlsx saved from it / our template)
 * read into rows keyed by HEADER NAME, never by position. Pure parsing: no database.
 *
 * Headers are compared trimmed, case-insensitive, with a UTF-8 BOM and repeated spaces removed.
 * The columns the import reads must be present ({@link #REQUIRED}); every other Shopify column is
 * allowed and ignored. Limits: {@link #MAX_BYTES} per file, {@link #MAX_ROWS} data rows; an .xlsx
 * is unzipped into memory only up to {@link #MAX_XLSX_UNZIPPED_BYTES} (a zip bomb is refused before
 * the workbook is parsed). Any problem with the file as a whole is an {@link ImportException}; a
 * problem with one row is the caller's (per-row) business.
 */
public final class ShopifyInventoryFile {

    public static final int  MAX_BYTES = 5 * 1024 * 1024;
    public static final int  MAX_ROWS  = 20_000;
    static final long        MAX_XLSX_UNZIPPED_BYTES = 60L * 1024 * 1024;
    static final int         MAX_XLSX_ENTRIES = 500;

    /** The Shopify inventory export's columns, in Shopify's order (the template writes exactly these). */
    public static final List<String> SHOPIFY_HEADERS = List.of(
        "Handle", "Title", "Option1 Name", "Option1 Value", "Option2 Name", "Option2 Value",
        "Option3 Name", "Option3 Value", "SKU", "HS Code", "COO", "Location", "Bin name",
        "Incoming (not editable)", "Unavailable (not editable)", "Committed (not editable)",
        "Available (not editable)", "On hand (current)", "On hand (new)",
        "Variant Packed Length", "Variant Packed Width", "Variant Packed Height", "Variant Packed Dimension Unit");

    public static final String HANDLE = "handle", TITLE = "title", SKU = "sku", LOCATION = "location",
        OPT1 = "option1 value", OPT2 = "option2 value", OPT3 = "option3 value",
        ON_HAND_CURRENT = "on hand (current)", ON_HAND_NEW = "on hand (new)";
    static final List<String> REQUIRED = List.of(HANDLE, TITLE, OPT1, OPT2, OPT3, SKU, LOCATION, ON_HAND_CURRENT, ON_HAND_NEW);

    /** One data row: its 1-based line in the file (the header is row 1) and its values by normalized header. */
    public record FileRow(int rowNumber, Map<String, String> values) {
        public String get(String header) { return values.getOrDefault(header, ""); }
    }

    private ShopifyInventoryFile() {}

    public static List<FileRow> parse(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw ImportException.emptyFile();
        if (bytes.length > MAX_BYTES) throw ImportException.tooLarge();
        if (isZip(bytes)) return fromTable(readXlsx(bytes));
        if (isOle(bytes)) throw ImportException.oldExcel();
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) throw ImportException.unreadable();
        return fromTable(readCsv(bytes));
    }

    static String normalizeHeader(String h) {
        if (h == null) return "";
        return h.replace("﻿", "").strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static List<FileRow> fromTable(List<List<String>> table) {
        if (table.isEmpty()) throw ImportException.emptyFile();
        List<String> headers = table.get(0).stream().map(ShopifyInventoryFile::normalizeHeader).toList();
        List<String> missing = REQUIRED.stream().filter(r -> !headers.contains(r)).toList();
        if (!missing.isEmpty()) throw ImportException.notAnExport(missing);
        if (table.size() - 1 > MAX_ROWS) throw ImportException.tooManyRows();
        List<FileRow> rows = new ArrayList<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> cells = table.get(i);
            if (cells.stream().allMatch(c -> c == null || c.isBlank())) continue;   // blank line
            Map<String, String> values = new HashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String h = headers.get(c);
                if (h.isEmpty() || values.containsKey(h)) continue;                   // first column of a name wins
                values.put(h, c < cells.size() && cells.get(c) != null ? cells.get(c).strip() : "");
            }
            rows.add(new FileRow(i + 1, values));
        }
        return rows;
    }

    // ── CSV (RFC 4180: quotes, doubled quotes, separators and line breaks inside quotes) ──────────

    static List<List<String>> readCsv(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw ImportException.notUtf8();
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        List<List<String>> table = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') { cell.append('"'); i += 2; continue; }
                    quoted = false; i++; continue;
                }
                cell.append(c); i++; continue;
            }
            if (c == '"' && cell.isEmpty()) { quoted = true; i++; continue; }
            if (c == ',') { row.add(cell.toString()); cell.setLength(0); i++; continue; }
            if (c == '\r' || c == '\n') {
                row.add(cell.toString()); cell.setLength(0);
                table.add(row); row = new ArrayList<>();
                if (table.size() > MAX_ROWS + 1) throw ImportException.tooManyRows();
                i += (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') ? 2 : 1;
                continue;
            }
            cell.append(c); i++;
        }
        if (quoted) throw ImportException.unreadable();
        if (!cell.isEmpty() || !row.isEmpty()) { row.add(cell.toString()); table.add(row); }
        return table;
    }

    // ── XLSX ─────────────────────────────────────────────────────────────────────────────────────

    static boolean isZip(byte[] b) {
        return b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
    }

    /** The old binary .xls (OLE2 compound file). */
    static boolean isOle(byte[] b) {
        return b.length >= 8 && (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF && (b[2] & 0xFF) == 0x11 && (b[3] & 0xFF) == 0xE0;
    }

    static List<List<String>> readXlsx(byte[] bytes) {
        refuseZipBomb(bytes);
        List<List<String>> table = new ArrayList<>();
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(bytes));
             Stream<Row> rows = wb.getFirstSheet().openStream()) {
            Iterator<Row> it = rows.iterator();
            while (it.hasNext()) {
                Row r = it.next();
                if (table.size() > MAX_ROWS + 1) throw ImportException.tooManyRows();
                // Pad skipped rows so row numbers match what the merchant sees in Excel.
                while (table.size() < r.getRowNum() - 1) table.add(List.of());
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < r.getCellCount(); c++) cells.add(text(r.getCell(c)));
                table.add(cells);
            }
        } catch (ImportException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw ImportException.unreadable();
        }
        return table;
    }

    private static String text(Cell cell) {
        if (cell == null) return "";
        if (cell.getType() == CellType.NUMBER) {
            String raw = cell.getRawValue();
            return raw == null ? "" : raw;
        }
        String t = cell.getText();
        return t == null ? "" : t;
    }

    /**
     * Unpacks every entry into a counting sink only (through the zip's central directory, so any layout
     * Excel or another writer produces is read): too many entries or too many unpacked bytes → refused
     * before the workbook is parsed. The real bytes are counted — never the sizes the zip declares.
     */
    static void refuseZipBomb(byte[] bytes) {
        long total = 0;
        int entries = 0;
        byte[] buf = new byte[64 * 1024];
        try (org.apache.commons.compress.archivers.zip.ZipFile zip = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                .setSeekableByteChannel(new org.apache.commons.compress.utils.SeekableInMemoryByteChannel(bytes)).get()) {
            var it = zip.getEntries();
            while (it.hasMoreElements()) {
                var e = it.nextElement();
                if (++entries > MAX_XLSX_ENTRIES) throw ImportException.unreadable();
                try (InputStream in = zip.getInputStream(e)) {
                    int k;
                    while ((k = in.read(buf)) > 0) {
                        total += k;
                        if (total > MAX_XLSX_UNZIPPED_BYTES) throw ImportException.tooLarge();
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ImportException ie) throw ie;
            throw ImportException.unreadable();
        }
        if (entries == 0) throw ImportException.unreadable();
    }

    // ── Writing (the template) ───────────────────────────────────────────────────────────────────

    /** CSV with a UTF-8 BOM (so Excel shows Arabic titles), every cell quoted where needed, CRLF lines. */
    public static byte[] writeCsv(List<List<String>> table) {
        StringBuilder sb = new StringBuilder("﻿");
        for (List<String> row : table) {
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append(',');
                String v = row.get(c) == null ? "" : row.get(c);
                if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
                    sb.append('"').append(v.replace("\"", "\"\"")).append('"');
                } else {
                    sb.append(v);
                }
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** One sheet, every cell a string except numeric "On hand (current)" values (written as numbers). */
    public static byte[] writeXlsx(List<List<String>> table, Set<Integer> numericColumns) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            org.dhatim.fastexcel.Workbook wb = new org.dhatim.fastexcel.Workbook(out, "Traced", "1.0");
            org.dhatim.fastexcel.Worksheet ws = wb.newWorksheet("Inventory");
            for (int r = 0; r < table.size(); r++) {
                List<String> row = table.get(r);
                for (int c = 0; c < row.size(); c++) {
                    String v = row.get(c);
                    if (v == null || v.isEmpty()) continue;
                    if (r > 0 && numericColumns.contains(c) && v.matches("-?\\d{1,9}")) ws.value(r, c, Integer.parseInt(v));
                    else ws.value(r, c, v);
                }
            }
            wb.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the template", e);
        }
        return out.toByteArray();
    }
}
