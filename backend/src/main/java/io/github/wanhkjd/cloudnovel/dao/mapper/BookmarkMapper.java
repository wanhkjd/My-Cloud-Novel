package io.github.wanhkjd.cloudnovel.dao.mapper;

import io.github.wanhkjd.cloudnovel.dao.entity.BookmarkEntity;
import io.github.wanhkjd.cloudnovel.dto.resp.BookmarkView;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 段落书签数据访问接口；不负责书籍可读性或公开规则。 */
@Mapper
public interface BookmarkMapper {

    /**
     * 查询主人的全部书签。
     *
     * @return 按更新时间倒序排列的书签
     */
    List<BookmarkView> findAll();

    /**
     * 查询指定书籍的书签。
     *
     * @param bookId 书籍 UUID
     * @param publishedOnly 是否只查公开感想，由 Service 决定
     * @return 按章节与段落升序排列的书签
     */
    List<BookmarkView> findByBook(
            @Param("bookId") String bookId, @Param("publishedOnly") boolean publishedOnly);

    /**
     * 读取一条书签。
     *
     * @param id 书签 UUID
     * @return 书签投影；不存在时为空
     */
    Optional<BookmarkView> findById(@Param("id") String id);

    /**
     * 新增书签，书籍与段落位置的唯一约束由数据库保证。
     *
     * @param bookmark 书签实体
     * @return 插入行数
     */
    int insert(BookmarkEntity bookmark);

    /**
     * 更新感想、公开标志和更新时间，不移动书签位置。
     *
     * @param bookmark 包含新感想的实体
     * @return 受影响行数
     */
    int update(BookmarkEntity bookmark);

    /**
     * 删除指定书签。
     *
     * @param id 书签 UUID
     * @return 删除行数
     */
    int deleteById(@Param("id") String id);
}
