package io.github.wanhkjd.cloudnovel.service;

import io.github.wanhkjd.cloudnovel.dto.PositionRequest;
import io.github.wanhkjd.cloudnovel.dto.ReadingSessionRequest;
import io.github.wanhkjd.cloudnovel.vo.ProgressView;
import io.github.wanhkjd.cloudnovel.vo.ReadingSessionView;
import io.github.wanhkjd.cloudnovel.vo.ReadingStatsView;
import java.util.List;

/** 唯一主人的阅读业务接口；访客记录不会进入此接口。 */
public interface ReadingService {

    /**
     * 读取所有书籍的最近阅读位置。
     *
     * @return 最近位置列表
     */
    List<ProgressView> listProgress();

    /**
     * 读取一本书的最近位置，保持现有未阅读时返回空响应的约定。
     *
     * @param bookId 书籍 UUID
     * @return 最近位置；书籍存在但没有进度时为 null
     */
    ProgressView getProgress(String bookId);

    /**
     * 校验并保存最新阅读位置。
     *
     * @param bookId 书籍 UUID
     * @param position 章节与段落坐标
     * @return 保存后的位置
     */
    ProgressView saveProgress(String bookId, PositionRequest position);

    /**
     * 按 UUID 幂等保存累计时长，重复或乱序请求不会重复计时。
     *
     * @param id 规范格式的会话 UUID
     * @param input 累计上报信息
     * @return 服务端最终保存的会话
     * @throws IllegalArgumentException 会话时间或位置不合法
     */
    ReadingSessionView saveSession(String id, ReadingSessionRequest input);

    /**
     * 读取最近 200 段阅读历史，不截断统计使用的总数据。
     *
     * @return 最近历史
     */
    List<ReadingSessionView> listHistory();

    /**
     * 以北京时间统计总时长、今天、近 14 天和各书时长。
     *
     * @return 完整统计结果
     */
    ReadingStatsView getStats();
}
