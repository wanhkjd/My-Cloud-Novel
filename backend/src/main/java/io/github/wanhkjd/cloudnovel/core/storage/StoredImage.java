package io.github.wanhkjd.cloudnovel.core.storage;

/**
 * 从存储读回的封面图片：原始字节与其真实内容类型（由 {@link CoverFormat} 决定）。
 *
 * @param bytes 图片字节
 * @param contentType HTTP {@code Content-Type}，如 {@code image/png}
 */
public record StoredImage(byte[] bytes, String contentType) {}
