package io.github.wanhkjd.cloudnovel.dto.resp;

/**
 * 交给控制器下发的封面负载：图片字节与其内容类型，不含磁盘路径或存储细节。
 *
 * @param bytes 图片字节
 * @param contentType HTTP {@code Content-Type}，如 {@code image/png}
 */
public record CoverImage(byte[] bytes, String contentType) {}
