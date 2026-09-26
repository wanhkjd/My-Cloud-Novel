package io.github.wanhkjd.cloudnovel.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CoverImageStorageTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 4, 5, 6};

    @TempDir Path directory;

    @Test
    void writeThenReadRoundTripsBytesAndContentType() throws IOException {
        CoverImageStorage storage = new LocalCoverImageStorage(directory.toString());
        storage.write(ID, CoverFormat.JPEG, JPEG_BYTES);
        StoredImage image = storage.read(ID).orElseThrow();
        assertThat(image.bytes()).isEqualTo(JPEG_BYTES);
        assertThat(image.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void writeOverwritesAcrossFormatsLeavingSingleFile() throws IOException {
        CoverImageStorage storage = new LocalCoverImageStorage(directory.toString());
        storage.write(ID, CoverFormat.JPEG, JPEG_BYTES);
        storage.write(ID, CoverFormat.PNG, PNG_BYTES);
        assertThat(storage.read(ID).orElseThrow().contentType()).isEqualTo("image/png");
        try (Stream<Path> files = Files.list(directory.resolve("covers"))) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith(ID))).hasSize(1);
        }
    }

    @Test
    void readReturnsEmptyWhenNoCoverAndDeleteIsIdempotent() throws IOException {
        CoverImageStorage storage = new LocalCoverImageStorage(directory.toString());
        assertThat(storage.read(ID)).isEmpty();
        storage.delete(ID); // 目录尚不存在也不抛
        storage.write(ID, CoverFormat.PNG, PNG_BYTES);
        storage.delete(ID);
        storage.delete(ID); // 幂等
        assertThat(storage.read(ID)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "",
                "../private",
                "..\\private",
                "C:\\private",
                "1-1-1-1-1",
                "123E4567-E89B-12D3-A456-426614174000"
            })
    void rejectsNonCanonicalUuid(String id) {
        CoverImageStorage storage = new LocalCoverImageStorage(directory.toString());
        assertThatThrownBy(() -> storage.write(id, CoverFormat.PNG, PNG_BYTES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.read(id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(id)).isInstanceOf(IllegalArgumentException.class);
    }
}
