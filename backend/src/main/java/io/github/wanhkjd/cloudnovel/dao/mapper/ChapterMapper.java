package io.github.wanhkjd.cloudnovel.dao.mapper;

import io.github.wanhkjd.cloudnovel.dao.entity.ChapterEntity;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 章节数据访问接口；只返回实体，文本段落转换由业务层完成。 */
@Mapper
public interface ChapterMapper {

    /**
     * 查询有序目录，content 字段为空以避免加载全文。
     *
     * @param bookId 书籍 UUID
     * @return 按章节索引升序排列的目录实体
     */
    List<ChapterEntity> findSummaries(@Param("bookId") String bookId);

    /**
     * 读取指定章节的原始纯文本内容。
     *
     * @param bookId 书籍 UUID
     * @param chapterIndex 从零开始的章节索引
     * @return 章节实体；不存在时为空
     */
    Optional<ChapterEntity> findByPosition(
            @Param("bookId") String bookId, @Param("chapterIndex") int chapterIndex);

    /**
     * 批量写入一组章节，调用方需提供非空且大小受控的列表。
     *
     * @param chapters 同一书籍的一批章节
     * @return 插入行数
     */
    int insertBatch(@Param("chapters") List<ChapterEntity> chapters);
}
