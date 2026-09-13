package io.github.wanhkjd.cloudnovel.vo;

import java.util.List;

/**
 * 主人阅读统计；服务端会补全近 14 天没有阅读的日期。
 *
 * @param totalSeconds 全部已保存会话的累计秒数
 * @param todaySeconds 今天开始的会话累计秒数
 * @param sessionCount 会话总数
 * @param days 按日期升序排列的近 14 天统计
 * @param books 按累计时长降序排列的各书统计
 */
public record ReadingStatsView(
        long totalSeconds,
        long todaySeconds,
        long sessionCount,
        List<DayReadingView> days,
        List<BookTimeView> books) {}
