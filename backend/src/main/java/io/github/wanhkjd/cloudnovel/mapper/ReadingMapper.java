package io.github.wanhkjd.cloudnovel.mapper;

import io.github.wanhkjd.cloudnovel.entity.ProgressEntity;
import io.github.wanhkjd.cloudnovel.entity.ReadingSessionEntity;
import io.github.wanhkjd.cloudnovel.vo.BookTimeView;
import io.github.wanhkjd.cloudnovel.vo.DayReadingView;
import io.github.wanhkjd.cloudnovel.vo.ProgressView;
import io.github.wanhkjd.cloudnovel.vo.ReadingSessionView;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 主人阅读记录的数据访问接口；时间合法性与幂等策略由 Service 管理。 */
@Mapper
public interface ReadingMapper {

    /**
     * 查询所有书籍的最近阅读位置。
     *
     * @return 按更新时间倒序排列的位置
     */
    List<ProgressView> findProgress();

    /**
     * 查询一本书的最近阅读位置。
     *
     * @param bookId 书籍 UUID
     * @return 位置投影；未阅读时为空
     */
    Optional<ProgressView> findProgressByBook(@Param("bookId") String bookId);

    /**
     * 插入最近阅读位置。
     *
     * @param progress 位置实体
     * @return 插入行数
     */
    int insertProgress(ProgressEntity progress);

    /**
     * 更新已有的最近阅读位置。
     *
     * @param progress 位置实体
     * @return 受影响行数
     */
    int updateProgress(ProgressEntity progress);

    /**
     * 读取原始会话，用于业务层核对书籍、开始时间和累计秒数。
     *
     * @param id 会话 UUID
     * @return 会话实体；不存在时为空
     */
    Optional<ReadingSessionEntity> findSession(@Param("id") String id);

    /**
     * 读取带书名和章节标题的会话。
     *
     * @param id 会话 UUID
     * @return 会话投影；不存在时为空
     */
    Optional<ReadingSessionView> findSessionView(@Param("id") String id);

    /**
     * 保存新会话，不执行累加计算。
     *
     * @param session 已校验的会话实体
     * @return 插入行数
     */
    int insertSession(ReadingSessionEntity session);

    /**
     * 仅当新累计秒数更大时更新会话，防止旧请求回退计时。
     *
     * @param session 包含新累计秒数和最新位置的会话
     * @return 受影响行数；没有增长时为零
     */
    int advanceSession(ReadingSessionEntity session);

    /**
     * 查询最近的阅读会话。
     *
     * @param limit 业务层限定的最大记录数
     * @return 按更新时间倒序排列的会话
     */
    List<ReadingSessionView> findHistory(@Param("limit") int limit);

    /**
     * 汇总全部会话的有效秒数。
     *
     * @return 总秒数，无记录时为零
     */
    long sumSeconds();

    /**
     * 汇总指定开始日期的有效秒数。
     *
     * @param date 北京时间日期，格式 yyyy-MM-dd
     * @return 当日秒数，无记录时为零
     */
    long sumSecondsOnDay(@Param("date") String date);

    /**
     * 统计全部会话数量。
     *
     * @return 会话数量
     */
    long countSessions();

    /**
     * 按日期聚合实际有记录的阅读时长，不负责补零。
     *
     * @param date 包含在内的最早日期
     * @return 每日聚合投影
     */
    List<DayReadingView> findDailySecondsSince(@Param("date") String date);

    /**
     * 按书籍汇总阅读时长。
     *
     * @return 按时长降序排列的书籍统计
     */
    List<BookTimeView> findBookTimes();
}
