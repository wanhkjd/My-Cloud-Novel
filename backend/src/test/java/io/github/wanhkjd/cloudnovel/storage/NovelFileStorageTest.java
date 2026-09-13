package io.github.wanhkjd.cloudnovel.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 所有文件操作仅落入 JUnit 临时目录，绝不读写实际书库。 */
class NovelFileStorageTest {
    @TempDir Path directory;
    private NovelFileStorage storage;
    private final String id = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() throws Exception {
        storage = new NovelFileStorage(directory.toString());
    }

    @Test
    void bytesRoundTripWithoutTranscodingAndExistingOriginalCannotBeOverwritten() throws Exception {
        byte[] original = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, (byte) 0xd6, (byte) 0xd0, 13, 10};
        storage.writeNew(id, original);
        assertThat(storage.read(id)).containsExactly(original);
        assertThatThrownBy(() -> storage.writeNew(id, new byte[] {1, 2}))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(storage.read(id)).containsExactly(original);
        try (var paths = Files.list(directory)) {
            assertThat(paths.map(file -> file.getFileName().toString()))
                    .containsExactly(id + ".txt");
        }
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
                "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
            })
    void untrustedIdentifiersAreRejectedForEveryFileOperation(String invalidId) {
        assertThatThrownBy(() -> storage.writeNew(invalidId, new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.read(invalidId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(invalidId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletingTwiceIsSafeAndReadingAMissingOriginalIsExplicit() throws Exception {
        storage.writeNew(id, new byte[] {1});
        storage.delete(id);
        storage.delete(id);
        assertThatThrownBy(() -> storage.read(id)).isInstanceOf(NoSuchFileException.class);
        assertThat(directory.resolve(id + ".txt")).doesNotExist();
    }

    @Test
    void directoryAtAnOriginalPathIsNotAcceptedAsAFile() throws Exception {
        Files.createDirectory(directory.resolve(id + ".txt"));
        assertThatThrownBy(() -> storage.read(id)).isInstanceOf(NoSuchFileException.class);
    }
}
