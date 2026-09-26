package io.github.wanhkjd.cloudnovel.core.storage;

import java.io.IOException;
import java.util.Optional;

/** 封面图片的持久化：与原始 TXT 存储分离，落在存储根下的 covers/ 子目录。 */
public interface CoverImageStorage {
    /**
     * 覆盖式写入封面；写入前会清除该 id 其它扩展名的旧封面，保证每本书至多一个封面文件。
     *
     * @param id 书籍 UUID
     * @param format 由魔数判定的真实格式，决定落盘扩展名
     * @param bytes 图片字节
     * @throws IOException 写入失败
     */
    void write(String id, CoverFormat format, byte[] bytes) throws IOException;

    /**
     * 读回封面；不存在时返回空而非抛异常，便于上层回退到 404。
     *
     * @param id 书籍 UUID
     * @return 图片字节与内容类型，或 {@link Optional#empty()}
     * @throws IOException 读取失败（非"不存在"）
     */
    Optional<StoredImage> read(String id) throws IOException;

    /**
     * 幂等删除该 id 的全部封面文件；不存在视为成功。
     *
     * @param id 书籍 UUID
     * @throws IOException 删除失败
     */
    void delete(String id) throws IOException;
}
