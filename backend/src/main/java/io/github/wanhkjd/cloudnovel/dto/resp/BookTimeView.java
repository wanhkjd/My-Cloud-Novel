package io.github.wanhkjd.cloudnovel.dto.resp;

/**
 * 单本书的累计阅读时长。
 *
 * @param bookId 书籍 UUID
 * @param title 书名
 * @param seconds 累计有效秒数
 */
public record BookTimeView(String bookId, String title, long seconds) {}
