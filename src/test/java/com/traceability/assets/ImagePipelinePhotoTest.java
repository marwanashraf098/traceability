package com.traceability.assets;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static com.traceability.assets.ImagePipelineTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Returns portal P3 — the PHOTO profile (JPEG, long edge ≤ 1600 px, q 0.80):
 *   f1 output is JPEG, long edge capped (both orientations), never upscaled, transparency flattened onto white
 *   f2 EXIF + GPS + camera make gone; orientation applied
 *   f3 type sniffing and the pixel cap apply exactly as for logos
 */
class ImagePipelinePhotoTest {

    private final ImagePipeline pipeline = new ImagePipeline();

    @Test
    void f1_jpeg_longEdge1600_noUpscale_flattened() throws Exception {
        ImagePipeline.Processed wide = pipeline.process(encode(solid(4000, 3000, Color.GRAY), "png"), ImagePipeline.Profile.PHOTO);
        assertThat(wide.contentType()).isEqualTo("image/jpeg");
        assertThat(wide.width()).isEqualTo(1600);
        assertThat(wide.height()).isEqualTo(1200);
        assertThat(wide.bytes()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(wide.bytes()[1] & 0xFF).isEqualTo(0xD8);

        ImagePipeline.Processed tall = pipeline.process(encode(solid(1500, 3000, Color.GRAY), "jpg"), ImagePipeline.Profile.PHOTO);
        assertThat(tall.height()).isEqualTo(1600);
        assertThat(tall.width()).isEqualTo(800);

        ImagePipeline.Processed small = pipeline.process(encode(solid(640, 480, Color.GRAY), "jpg"), ImagePipeline.Profile.PHOTO);
        assertThat(small.width()).isEqualTo(640);

        BufferedImage clear = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);   // fully transparent
        BufferedImage out = decode(pipeline.process(encode(clear, "png"), ImagePipeline.Profile.PHOTO).bytes());
        assertThat(out.getColorModel().hasAlpha()).isFalse();
        assertThat(out.getRGB(10, 10) & 0xFFFFFF).as("flattened onto white").isGreaterThan(0xF0F0F0);
    }

    @Test
    void f2_exifGpsStripped_orientationApplied() throws Exception {
        byte[] in = jpegWithExif(halves(40, 20), 6);
        assertThat(ImageMetadataReader.readMetadata(new ByteArrayInputStream(in)).getFirstDirectoryOfType(GpsDirectory.class)).isNotNull();

        ImagePipeline.Processed p = pipeline.process(in, ImagePipeline.Profile.PHOTO);
        String raw = new String(p.bytes(), StandardCharsets.ISO_8859_1);
        assertThat(raw).doesNotContain("LeakyCam").doesNotContain("Exif");
        Metadata after = ImageMetadataReader.readMetadata(new ByteArrayInputStream(p.bytes()));
        assertThat(after.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(after.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();
        assertThat(p.width()).isEqualTo(20);
        assertThat(p.height()).isEqualTo(40);
        BufferedImage o = decode(p.bytes());
        int top = o.getRGB(10, 3), bottom = o.getRGB(10, 36);
        assertThat(((top >> 16) & 0xFF) > (top & 0xFF) + 60).as("rotated: stored left (red) is the top").isTrue();
        assertThat((bottom & 0xFF) > ((bottom >> 16) & 0xFF) + 60).isTrue();
    }

    @Test
    void f3_typeAndPixelCap() throws Exception {
        assertThatThrownBy(() -> pipeline.process(encode(solid(20, 20, Color.RED), "gif"), ImagePipeline.Profile.PHOTO))
            .isInstanceOfSatisfying(ImagePipeline.RejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(ImagePipeline.Rejection.UNSUPPORTED_TYPE));
        assertThatThrownBy(() -> pipeline.process(pngHeaderOnly(50_000, 50_000), ImagePipeline.Profile.PHOTO))
            .isInstanceOfSatisfying(ImagePipeline.RejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(ImagePipeline.Rejection.TOO_MANY_PIXELS));
    }
}
