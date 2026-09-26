package io.github.wanhkjd.cloudnovel.dao.mapper;

import io.github.wanhkjd.cloudnovel.dao.entity.BookEntity;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 书籍数据访问接口；只定义持久化操作，公开权限由 Service 决定。 */
@Mapper
public interface BookMapper {

    /**
     * 查询书目列表，不加载前言正文。
     *
     * @param publishedOnly 是否仅查询已公开书目，由业务层传入
     * @return 按创建时间倒序排列的书籍
     */
    List<BookEntity> findAll(@Param("publishedOnly") boolean publishedOnly);

    /**
     * 按 UUID 查询完整书籍实体，不做身份判断。
     *
     * @param id 书籍 UUID
     * @return 书籍实体；不存在时为空
     */
    Optional<BookEntity> findById(@Param("id") String id);

    /**
     * 新增书籍，SHA-256 唯一约束由数据库保证。
     *
     * @param book 待保存实体
     * @return 插入行数
     */
    int insert(BookEntity book);

    /**
     * 更新可编辑元数据与两个公开标志，不修改原件或解析结果。
     *
     * @param book 包含新元数据的实体
     * @return 受影响行数
     */
    int updateMetadata(BookEntity book);

    /**
     * 仅更新封面对象键，与元数据编辑解耦，避免编辑书目时误清空封面。
     *
     * @param id 书籍 UUID
     * @param coverPath 封面对象键，或 null 表示清除封面
     * @return 受影响行数
     */
    int updateCover(@Param("id") String id, @Param("coverPath") String coverPath);

    /**
     * 删除书籍；关联数据通过外键级联删除。
     *
     * @param id 书籍 UUID
     * @return 删除行数
     */
    int deleteById(@Param("id") String id);
}
