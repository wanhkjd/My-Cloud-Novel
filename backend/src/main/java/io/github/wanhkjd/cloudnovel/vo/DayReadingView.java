package io.github.wanhkjd.cloudnovel.vo;

/**
 * 按北京时间开始日期聚合的阅读时长。
 *
 * @param date 日期，格式 yyyy-MM-dd
 * @param seconds 累计有效秒数
 */
public record DayReadingView(String date, long seconds) {}
