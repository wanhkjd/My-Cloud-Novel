package io.github.wanhkjd.cloudnovel.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CoverFormatTest {
    private static final byte[] JPEG = {
        (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0
    };
    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A, 0, 0, 0, 0
    };
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P'};

    @Test
    void detectsRealImageMagicBytes() {
        assertThat(CoverFormat.detect(JPEG)).contains(CoverFormat.JPEG);
        assertThat(CoverFormat.detect(PNG)).contains(CoverFormat.PNG);
        assertThat(CoverFormat.detect(WEBP)).contains(CoverFormat.WEBP);
    }

    @Test
    void rejectsDisallowedOrMalformedContent() {
        assertThat(CoverFormat.detect("GIF89a-----".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(CoverFormat.detect("<svg xmlns=".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(CoverFormat.detect("<!DOCTYPE h".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(CoverFormat.detect("RIFF____XXXX".getBytes(StandardCharsets.US_ASCII)))
                .isEmpty();
        assertThat(CoverFormat.detect(new byte[] {(byte) 0xFF, (byte) 0xD8})).isEmpty(); // 太短
        assertThat(CoverFormat.detect(new byte[0])).isEmpty();
        assertThat(CoverFormat.detect(null)).isEmpty();
    }

    @Test
    void exposesExtensionAndContentType() {
        assertThat(CoverFormat.JPEG.extension()).isEqualTo("jpg");
        assertThat(CoverFormat.JPEG.contentType()).isEqualTo("image/jpeg");
        assertThat(CoverFormat.PNG.extension()).isEqualTo("png");
        assertThat(CoverFormat.PNG.contentType()).isEqualTo("image/png");
        assertThat(CoverFormat.WEBP.extension()).isEqualTo("webp");
        assertThat(CoverFormat.WEBP.contentType()).isEqualTo("image/webp");
    }

    @Test
    void resolvesFormatFromStoredExtension() {
        assertThat(CoverFormat.fromExtension("jpg")).contains(CoverFormat.JPEG);
        assertThat(CoverFormat.fromExtension("png")).contains(CoverFormat.PNG);
        assertThat(CoverFormat.fromExtension("webp")).contains(CoverFormat.WEBP);
        assertThat(CoverFormat.fromExtension("gif")).isEmpty();
        assertThat(CoverFormat.fromExtension(null)).isEmpty();
    }
}
