package io.github.wanhkjd.cloudnovel.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 基于本地磁盘的封面存储：文件落在 {@code ${app.storage-directory}/covers/{id}.{ext}}， 与原始 TXT（存储根）分离。写入为覆盖式（先删同 id
 * 其它扩展名）， 并以规范 UUID 校验阻止路径穿越与符号链接逃逸。
 */
@Component
public class LocalCoverImageStorage implements CoverImageStorage {
    private final Path directory;

    /**
     * 使用配置的存储根构造本地封面存储。
     *
     * @param storageDirectory 存储根目录；封面写入其下的 covers/ 子目录
     */
    public LocalCoverImageStorage(@Value("${app.storage-directory}") String storageDirectory) {
        this.directory = Path.of(storageDirectory).toAbsolutePath().normalize().resolve("covers");
    }

    @Override
    public void write(String id, CoverFormat format, byte[] bytes) throws IOException {
        Path target = resolve(id, format.extension());
        Files.createDirectories(directory);
        deleteAllFormats(id); // 覆盖式：换格式时不残留旧扩展名文件
        Files.write(target, bytes);
    }

    @Override
    public Optional<StoredImage> read(String id) throws IOException {
        for (CoverFormat format : CoverFormat.values()) {
            Path candidate = resolve(id, format.extension());
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(
                        new StoredImage(Files.readAllBytes(candidate), format.contentType()));
            }
        }
        return Optional.empty();
    }

    @Override
    public void delete(String id) throws IOException {
        deleteAllFormats(id);
    }

    private void deleteAllFormats(String id) throws IOException {
        for (CoverFormat format : CoverFormat.values()) {
            Files.deleteIfExists(resolve(id, format.extension()));
        }
    }

    private Path resolve(String id, String extension) {
        if (id == null || !isCanonicalUuid(id)) {
            throw new IllegalArgumentException("非法的书籍标识：" + id);
        }
        Path candidate = directory.resolve(id + "." + extension).normalize();
        if (!directory.equals(candidate.getParent())) {
            throw new IllegalArgumentException("非法的书籍标识：" + id);
        }
        return candidate;
    }

    private static boolean isCanonicalUuid(String id) {
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
