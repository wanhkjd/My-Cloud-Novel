package io.github.wanhkjd.cloudnovel.storage;

import java.io.IOException;

/**
 * 私有原件存储契约：当前由本地目录实现，业务服务不依赖磁盘路径。
 *
 * <p>后续接入 OSS / COS / S3 时替换该实现，保留 UUID 对象标识、禁止覆盖和原字节语义； 桶必须私有，不能绕过 LibraryService
 * 的正文权限检查。当前没有配置云端存储。
 */
public interface NovelFileStorage {
    /**
     * 写入新原件；已有同标识对象必须拒绝覆盖。
     *
     * @param id 服务器生成的规范书籍 UUID
     * @param bytes 原始 TXT 字节，不转码
     * @throws IOException 写入失败
     */
    void writeNew(String id, byte[] bytes) throws IOException;

    /**
     * 读取原件；调用方必须先完成权限校验。
     *
     * @param id 书籍 UUID
     * @return 原始字节
     * @throws IOException 对象不存在或读取失败
     */
    byte[] read(String id) throws IOException;

    /**
     * 删除指定原件；不存在时也视为完成，不触及其他对象。
     *
     * @param id 书籍 UUID
     * @throws IOException 删除失败
     */
    void delete(String id) throws IOException;
}
