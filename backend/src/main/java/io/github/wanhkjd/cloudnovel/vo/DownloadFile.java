package io.github.wanhkjd.cloudnovel.vo;

/**
 * 通过可读性校验后的原件下载结果，HTTP 头由 Controller 组装。
 *
 * @param book 经权限校验的书目
 * @param bytes 未经转码的原始字节
 */
public record DownloadFile(BookView book, byte[] bytes) {}
