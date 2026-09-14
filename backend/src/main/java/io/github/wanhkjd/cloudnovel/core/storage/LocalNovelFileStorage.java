package io.github.wanhkjd.cloudnovel.core.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 私有 TXT 原件存储；只接受服务器生成的 UUID，不接受上传文件名作为路径。 */
@Component
public class LocalNovelFileStorage implements NovelFileStorage {
    /** 标准化后的私有存储根目录，不作为静态资源暴露。 */
    private final Path directory;

    /**
     * 准备原件目录。
     *
     * @param directory 配置中的私有原件目录
     * @throws IOException 目录不可创建或访问
     */
    public LocalNovelFileStorage(@Value("${app.storage-directory}") String directory)
            throws IOException {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
    }

    /**
     * 创建新的私有原件，不覆盖已有 UUID 文件。
     *
     * @param id 规范格式的书籍 UUID
     * @param bytes 原始文件字节
     * @throws IOException 文件创建或写入失败
     */
    @Override
    public void writeNew(String id, byte[] bytes) throws IOException {
        Path target = resolve(id);
        // 打开失败时不能删除已有文件；只有成功创建后发生的写入失败才允许补偿。
        OutputStream output =
                Files.newOutputStream(
                        target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (output) {
            output.write(bytes);
        } catch (IOException error) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    /**
     * 读取原始字节；调用方必须先做可读性校验。
     *
     * @param id 书籍 UUID
     * @return 未转码的 TXT 字节
     * @throws IOException 文件不存在、为符号链接或无法读取
     */
    @Override
    public byte[] read(String id) throws IOException {
        Path file = resolve(id);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(file.toString());
        }
        return Files.readAllBytes(file);
    }

    /**
     * 清理指定原件；重复删除是安全的。
     *
     * @param id 书籍 UUID
     * @throws IOException 文件删除失败
     */
    @Override
    public void delete(String id) throws IOException {
        Files.deleteIfExists(resolve(id));
    }

    private Path resolve(String id) {
        try {
            if (!UUID.fromString(id).toString().equals(id)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IllegalArgumentException("书籍编号不正确。");
        }
        Path target = directory.resolve(id + ".txt").normalize();
        if (!target.getParent().equals(directory)) {
            throw new IllegalArgumentException("原件路径不正确。");
        }
        return target;
    }
}
