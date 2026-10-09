package com.traceability.assets;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;

/**
 * The one path every uploaded image takes (returns portal P1 logo; P3 photos reuse it):
 * <ol>
 *   <li>sniff the magic bytes — JPEG, PNG or WebP only; the file name and the declared
 *       content type are never trusted;</li>
 *   <li>read width × height from the header and refuse anything over {@link #MAX_PIXELS} or
 *       {@link #MAX_SIDE} BEFORE decoding a single pixel (decompression-bomb guard);</li>
 *   <li>decode the first frame only;</li>
 *   <li>apply the EXIF orientation, so a phone photo is upright once its metadata is gone;</li>
 *   <li>downscale to the profile's box (never upscale);</li>
 *   <li>re-encode from raw pixels — PNG keeps alpha, JPEG is flattened onto white. The output
 *       carries no metadata at all: no EXIF, no GPS, no ICC, no comments.</li>
 * </ol>
 * Never logs image content or file names.
 */
@Component
public class ImagePipeline {

    /** 40 megapixels — far above any real logo or phone photo, far below a bomb. */
    public static final long MAX_PIXELS = 40_000_000L;
    /** No side longer than this, whatever the pixel count (e.g. 1 × 100 000 strips). */
    public static final int MAX_SIDE = 12_000;

    public enum Format { PNG, JPEG }

    /** Output box and format. LOGO: PNG with alpha, at most 600 px wide. */
    public record Profile(int maxWidth, int maxHeight, Format format, float jpegQuality) {
        public static final Profile LOGO = new Profile(600, 600, Format.PNG, 0f);
    }

    public record Processed(byte[] bytes, String contentType, int width, int height, String sha256) {
        public int sizeBytes() { return bytes.length; }
    }

    public enum Rejection { UNSUPPORTED_TYPE, TOO_MANY_PIXELS, UNREADABLE }

    public static class RejectedException extends RuntimeException {
        private final Rejection reason;
        public RejectedException(Rejection reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }
        public Rejection reason() { return reason; }
    }

    private enum Sniffed {
        JPEG("jpeg"), PNG("png"), WEBP("webp");
        final String readerFormat;
        Sniffed(String readerFormat) { this.readerFormat = readerFormat; }
    }

    public ImagePipeline() {
        // In the Spring Boot fat jar the WebP / JPEG plugins (BOOT-INF/lib) are found only by a
        // scan on the application class loader — done once here, at bean creation.
        ImageIO.scanForPlugins();
        // Decode and encode in memory: no upload ever lands in a temp file.
        ImageIO.setUseCache(false);
    }

    public Processed process(byte[] input, Profile profile) {
        if (input == null || input.length == 0) throw new RejectedException(Rejection.UNREADABLE);
        Sniffed type = sniff(input);
        if (type == null) throw new RejectedException(Rejection.UNSUPPORTED_TYPE);

        int orientation = exifOrientation(input);
        // Orientations 5–8 swap width and height: scale against the box as it will be displayed.
        boolean swap = orientation >= 5;
        int boxW = swap ? profile.maxHeight() : profile.maxWidth();
        int boxH = swap ? profile.maxWidth() : profile.maxHeight();
        boolean alpha = profile.format() == Format.PNG;

        BufferedImage decoded = decodeGuarded(input, type, boxW, boxH);
        BufferedImage scaled = fit(decoded, boxW, boxH, alpha);
        BufferedImage upright = applyOrientation(scaled, orientation, alpha);
        byte[] out = encode(upright, profile);
        return new Processed(out, alpha ? "image/png" : "image/jpeg",
            upright.getWidth(), upright.getHeight(), sha256(out));
    }

    // ── 1. magic bytes ────────────────────────────────────────────────────────

    private static Sniffed sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Sniffed.JPEG;
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return Sniffed.PNG;
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return Sniffed.WEBP;
        }
        return null;
    }

    // ── 2 + 3. header check, then decode ──────────────────────────────────────

    private static BufferedImage decodeGuarded(byte[] input, Sniffed type, int boxW, int boxH) {
        ImageReader reader = null;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(type.readerFormat);
            if (!readers.hasNext()) throw new RejectedException(Rejection.UNSUPPORTED_TYPE);
            reader = readers.next();
            reader.setInput(in, true, true);   // ignore metadata while decoding
            int w = reader.getWidth(0);
            int h = reader.getHeight(0);
            if (w <= 0 || h <= 0) throw new RejectedException(Rejection.UNREADABLE);
            if (w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS) {
                throw new RejectedException(Rejection.TOO_MANY_PIXELS);
            }
            ImageReadParam param = reader.getDefaultReadParam();
            // Decode a much larger image at reduced resolution (still >= 2x the box, which the
            // bicubic halving in fit() then brings down) — a 40 MP photo never sits in memory whole.
            int step = (int) Math.max(1, Math.floor(Math.min((double) w / (2.0 * boxW), (double) h / (2.0 * boxH))));
            if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
            BufferedImage img = reader.read(0, param);
            if (img == null) throw new RejectedException(Rejection.UNREADABLE);
            return img;
        } catch (RejectedException e) {
            throw e;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            throw new RejectedException(Rejection.UNREADABLE);
        } finally {
            if (reader != null) reader.dispose();
        }
    }

    // ── 4. EXIF orientation ───────────────────────────────────────────────────

    /** 1..8; 1 (as stored) when there is no readable orientation tag. */
    static int exifOrientation(byte[] input) {
        try {
            Metadata m = ImageMetadataReader.readMetadata(new ByteArrayInputStream(input), input.length);
            for (ExifIFD0Directory d : m.getDirectoriesOfType(ExifIFD0Directory.class)) {
                if (d.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                    int o = d.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                    return o >= 1 && o <= 8 ? o : 1;
                }
            }
        } catch (Exception ignored) {
            // No or unreadable metadata — the pixels are shown as stored.
        }
        return 1;
    }

    /**
     * The EXIF orientation as an explicit pixel mapping (stored → displayed), applied to the
     * already-downscaled image. out(x, y) = src(...):
     * 2 mirror (w-1-x, y) · 3 rotate 180 (w-1-x, h-1-y) · 4 flip (x, h-1-y) · 5 transpose (y, x) ·
     * 6 rotate 90 CW (y, h-1-x) · 7 transverse (w-1-y, h-1-x) · 8 rotate 270 CW (w-1-y, x).
     */
    static BufferedImage applyOrientation(BufferedImage src, int orientation, boolean alpha) {
        if (orientation <= 1 || orientation > 8) return src;
        int w = src.getWidth(), h = src.getHeight();
        boolean swap = orientation >= 5;
        int ow = swap ? h : w, oh = swap ? w : h;
        BufferedImage out = new BufferedImage(ow, oh, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                int sx, sy;
                switch (orientation) {
                    case 2 -> { sx = w - 1 - x; sy = y; }
                    case 3 -> { sx = w - 1 - x; sy = h - 1 - y; }
                    case 4 -> { sx = x; sy = h - 1 - y; }
                    case 5 -> { sx = y; sy = x; }
                    case 6 -> { sx = y; sy = h - 1 - x; }
                    case 7 -> { sx = w - 1 - y; sy = h - 1 - x; }
                    default -> { sx = w - 1 - y; sy = x; }   // 8
                }
                out.setRGB(x, y, src.getRGB(sx, sy));
            }
        }
        return out;
    }

    // ── 5. downscale ──────────────────────────────────────────────────────────

    private static BufferedImage fit(BufferedImage src, int maxW, int maxH, boolean alpha) {
        int w = src.getWidth(), h = src.getHeight();
        double scale = Math.min(1.0, Math.min((double) maxW / w, (double) maxH / h));
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage cur = toType(src, type, alpha);
        // Halve step by step (good quality without a third-party scaler), then the last step.
        while (cur.getWidth() / 2 >= tw && cur.getHeight() / 2 >= th) {
            cur = draw(cur, cur.getWidth() / 2, cur.getHeight() / 2, type, alpha);
        }
        if (cur.getWidth() != tw || cur.getHeight() != th) cur = draw(cur, tw, th, type, alpha);
        return cur;
    }

    /** A fresh image of {@code type}: drops whatever colour model / metadata the decoder produced. */
    private static BufferedImage toType(BufferedImage src, int type, boolean alpha) {
        return draw(src, src.getWidth(), src.getHeight(), type, alpha);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h, int type, boolean alpha) {
        BufferedImage out = new BufferedImage(w, h, type);
        Graphics2D g = out.createGraphics();
        try {
            if (!alpha) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, w, h);
            } else {
                g.setComposite(AlphaComposite.Src);
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (!alpha) g.setComposite(AlphaComposite.SrcOver);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    // ── 6. re-encode ──────────────────────────────────────────────────────────

    private static byte[] encode(BufferedImage img, Profile profile) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (profile.format() == Format.PNG) {
                if (!ImageIO.write(img, "png", bos)) throw new IllegalStateException("No PNG writer");
            } else {
                Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
                if (!writers.hasNext()) throw new IllegalStateException("No JPEG writer");
                ImageWriter writer = writers.next();
                try (ImageOutputStream out = ImageIO.createImageOutputStream(bos)) {
                    writer.setOutput(out);
                    ImageWriteParam p = writer.getDefaultWriteParam();
                    p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    p.setCompressionQuality(profile.jpegQuality());
                    writer.write(null, new IIOImage(img, null, null), p);   // null metadata: none written
                } finally {
                    writer.dispose();
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Image encoding failed", e);
        }
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
