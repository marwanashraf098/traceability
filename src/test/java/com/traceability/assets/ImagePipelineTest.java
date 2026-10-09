package com.traceability.assets;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Returns portal P1 — the shared upload pipeline (ImagePipeline), no Spring:
 *   i1 only JPEG / PNG / WebP by magic bytes (GIF, BMP, text refused, whatever they're called)
 *   i2 pixel cap read from the header BEFORE decoding (a 50 000 × 50 000 header, a 20 000-px strip)
 *   i3 EXIF (incl. GPS and camera make) is gone from the output
 *   i4 EXIF orientation is applied (6, 8, 3)
 *   i5 PNG alpha is preserved (transparent, half-transparent and opaque pixels)
 *   i6 logos are scaled down to 600 px wide, never up
 *   i7 WebP decodes; a truncated PNG is UNREADABLE
 */
public class ImagePipelineTest {

    private final ImagePipeline pipeline = new ImagePipeline();

    // ── i1 ───────────────────────────────────────────────────────────────────

    @Test
    void i1_onlyJpegPngWebp_byMagicBytes() throws Exception {
        assertRejected(encode(solid(20, 20, Color.RED), "gif"), ImagePipeline.Rejection.UNSUPPORTED_TYPE);
        assertRejected(encode(solid(20, 20, Color.RED), "bmp"), ImagePipeline.Rejection.UNSUPPORTED_TYPE);
        assertRejected("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8),
            ImagePipeline.Rejection.UNSUPPORTED_TYPE);
        assertRejected("hello".getBytes(StandardCharsets.UTF_8), ImagePipeline.Rejection.UNSUPPORTED_TYPE);
        // The three accepted types go through.
        assertThat(pipeline.process(encode(solid(20, 20, Color.RED), "png"), ImagePipeline.Profile.LOGO).contentType())
            .isEqualTo("image/png");
        assertThat(pipeline.process(encode(solid(20, 20, Color.RED), "jpg"), ImagePipeline.Profile.LOGO).width())
            .isEqualTo(20);
    }

    // ── i2 ───────────────────────────────────────────────────────────────────

    @Test
    void i2_pixelCap_fromHeader_beforeDecode() {
        // A PNG whose header claims 50 000 × 50 000 (2.5 gigapixels) and carries no pixel data at all:
        // refused as TOO_MANY_PIXELS from the header alone (decoding would be UNREADABLE — no IDAT).
        assertRejected(pngHeaderOnly(50_000, 50_000), ImagePipeline.Rejection.TOO_MANY_PIXELS);
        // Few pixels but an absurd side.
        assertRejected(pngHeaderOnly(20_000, 10), ImagePipeline.Rejection.TOO_MANY_PIXELS);
        // Just under both caps is not refused as too many pixels (it's unreadable: no pixel data).
        assertRejected(pngHeaderOnly(6_000, 6_000), ImagePipeline.Rejection.UNREADABLE);
    }

    // ── i3 ───────────────────────────────────────────────────────────────────

    @Test
    void i3_exifAndGps_strippedFromOutput() throws Exception {
        byte[] in = jpegWithExif(solid(40, 20, Color.GREEN), 1);
        // The input really carries them (otherwise this test proves nothing).
        Metadata before = ImageMetadataReader.readMetadata(new ByteArrayInputStream(in));
        assertThat(before.getFirstDirectoryOfType(GpsDirectory.class)).isNotNull();
        assertThat(new String(in, StandardCharsets.ISO_8859_1)).contains("LeakyCam").contains("Exif");

        byte[] out = pipeline.process(in, ImagePipeline.Profile.LOGO).bytes();
        String raw = new String(out, StandardCharsets.ISO_8859_1);
        assertThat(raw).doesNotContain("LeakyCam").doesNotContain("Exif");
        Metadata after = ImageMetadataReader.readMetadata(new ByteArrayInputStream(out));
        assertThat(after.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(after.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();
        // And no PNG text / EXIF chunks either.
        assertThat(raw).doesNotContain("eXIf").doesNotContain("tEXt").doesNotContain("iTXt").doesNotContain("zTXt");
    }

    // ── i4 ───────────────────────────────────────────────────────────────────

    @Test
    void i4_exifOrientation_applied() throws Exception {
        // Stored 40 × 20: left half red, right half blue.
        BufferedImage stored = halves(40, 20);

        ImagePipeline.Processed six = pipeline.process(jpegWithExif(stored, 6), ImagePipeline.Profile.LOGO);
        BufferedImage o6 = decode(six.bytes());
        assertThat(o6.getWidth()).isEqualTo(20);
        assertThat(o6.getHeight()).isEqualTo(40);
        assertThat(dominant(o6.getRGB(10, 3))).as("rotate 90 CW: the stored left (red) is now the top").isEqualTo('r');
        assertThat(dominant(o6.getRGB(10, 36))).isEqualTo('b');

        BufferedImage o8 = decode(pipeline.process(jpegWithExif(stored, 8), ImagePipeline.Profile.LOGO).bytes());
        assertThat(o8.getWidth()).isEqualTo(20);
        assertThat(dominant(o8.getRGB(10, 3))).as("rotate 270 CW: the stored right (blue) is now the top").isEqualTo('b');

        BufferedImage o3 = decode(pipeline.process(jpegWithExif(stored, 3), ImagePipeline.Profile.LOGO).bytes());
        assertThat(o3.getWidth()).isEqualTo(40);
        assertThat(dominant(o3.getRGB(3, 10))).as("rotate 180: the stored right (blue) is now the left").isEqualTo('b');

        BufferedImage o1 = decode(pipeline.process(jpegWithExif(stored, 1), ImagePipeline.Profile.LOGO).bytes());
        assertThat(dominant(o1.getRGB(3, 10))).as("orientation 1: as stored").isEqualTo('r');
    }

    // ── i5 ───────────────────────────────────────────────────────────────────

    @Test
    void i5_pngAlpha_preserved() throws Exception {
        BufferedImage src = new BufferedImage(30, 30, BufferedImage.TYPE_INT_ARGB);   // fully transparent
        for (int y = 10; y < 20; y++) for (int x = 10; x < 20; x++) src.setRGB(x, y, 0xFF0F766E);   // opaque
        for (int y = 0; y < 5; y++) for (int x = 25; x < 30; x++) src.setRGB(x, y, 0x80FF0000);     // half

        ImagePipeline.Processed p = pipeline.process(encode(src, "png"), ImagePipeline.Profile.LOGO);
        assertThat(p.contentType()).isEqualTo("image/png");
        BufferedImage out = decode(p.bytes());
        assertThat(out.getColorModel().hasAlpha()).isTrue();
        assertThat(alpha(out.getRGB(2, 28))).as("transparent corner").isZero();
        assertThat(alpha(out.getRGB(15, 15))).as("opaque centre").isEqualTo(255);
        assertThat(alpha(out.getRGB(27, 2))).as("half-transparent").isBetween(120, 136);
    }

    // ── i6 ───────────────────────────────────────────────────────────────────

    @Test
    void i6_logo_scaledTo600Wide_neverUp() throws Exception {
        ImagePipeline.Processed big = pipeline.process(encode(solid(1800, 450, Color.BLUE), "png"), ImagePipeline.Profile.LOGO);
        assertThat(big.width()).isEqualTo(600);
        assertThat(big.height()).isEqualTo(150);
        assertThat(decode(big.bytes()).getWidth()).isEqualTo(600);
        assertThat(big.sha256()).matches("^[0-9a-f]{64}$");
        assertThat(big.sizeBytes()).isEqualTo(big.bytes().length);

        ImagePipeline.Processed small = pipeline.process(encode(solid(120, 40, Color.BLUE), "png"), ImagePipeline.Profile.LOGO);
        assertThat(small.width()).isEqualTo(120);
        assertThat(small.height()).isEqualTo(40);
    }

    // ── i7 ───────────────────────────────────────────────────────────────────

    /** A 1 × 1 lossless WebP. */
    static final String WEBP_1X1 = "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

    @Test
    void i7_webpDecodes_truncatedPngUnreadable() throws Exception {
        ImagePipeline.Processed w = pipeline.process(Base64.getDecoder().decode(WEBP_1X1), ImagePipeline.Profile.LOGO);
        assertThat(w.contentType()).isEqualTo("image/png");
        assertThat(w.width()).isEqualTo(1);
        assertThat(decode(w.bytes())).isNotNull();

        byte[] png = encode(solid(50, 50, Color.RED), "png");
        byte[] truncated = java.util.Arrays.copyOf(png, 40);
        assertRejected(truncated, ImagePipeline.Rejection.UNREADABLE);
        assertRejected(new byte[0], ImagePipeline.Rejection.UNREADABLE);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void assertRejected(byte[] in, ImagePipeline.Rejection reason) {
        assertThatThrownBy(() -> pipeline.process(in, ImagePipeline.Profile.LOGO))
            .isInstanceOfSatisfying(ImagePipeline.RejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    static BufferedImage solid(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    static BufferedImage halves(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(Color.BLUE);
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        return img;
    }

    static byte[] encode(BufferedImage img, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, format, out)).as("writer for " + format).isTrue();
        return out.toByteArray();
    }

    static BufferedImage decode(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static char dominant(int rgb) {
        int r = (rgb >> 16) & 0xFF, b = rgb & 0xFF;
        return r > b + 60 ? 'r' : b > r + 60 ? 'b' : '?';
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    /**
     * A JPEG with an APP1 EXIF segment: IFD0 = Make "LeakyCam", Orientation, GPS pointer; GPS IFD =
     * GPSLatitudeRef "N". Little-endian TIFF, built by hand.
     */
    static byte[] jpegWithExif(BufferedImage img, int orientation) throws Exception {
        byte[] jpeg = encode(img, "jpg");
        ByteBuffer t = ByteBuffer.allocate(77).order(ByteOrder.LITTLE_ENDIAN);
        t.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        // IFD0 @8: 3 entries (12 bytes each) + next-IFD offset → ends at 50
        t.putShort((short) 3);
        t.putShort((short) 0x010F).putShort((short) 2).putInt(9).putInt(68);          // Make → @68
        t.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        t.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(50);          // GPS IFD → @50
        t.putInt(0);
        // GPS IFD @50: 1 entry → ends at 68
        t.putShort((short) 1);
        t.putShort((short) 0x0001).putShort((short) 2).putInt(2).put((byte) 'N').put((byte) 0).putShort((short) 0);
        t.putInt(0);
        // @68: "LeakyCam\0"
        t.put("LeakyCam".getBytes(StandardCharsets.US_ASCII)).put((byte) 0);
        byte[] tiff = t.array();

        byte[] header = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
        int len = 2 + header.length + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                                   // SOI
        out.write(0xFF); out.write(0xE1); out.write(len >> 8); out.write(len & 0xFF);
        out.write(header);
        out.write(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** PNG signature + IHDR (8-bit RGBA) + IEND — a header that claims w × h, with no pixel data. */
    public static byte[] pngHeaderOnly(int w, int h) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 6)
            .put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
