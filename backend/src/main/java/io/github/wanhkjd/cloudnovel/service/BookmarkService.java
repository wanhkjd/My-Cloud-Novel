package io.github.wanhkjd.cloudnovel.service;

import io.github.wanhkjd.cloudnovel.dto.req.BookmarkCreateRequest;
import io.github.wanhkjd.cloudnovel.dto.req.BookmarkEditRequest;
import io.github.wanhkjd.cloudnovel.dto.resp.BookmarkView;
import java.util.List;

/** 主人书签及公开感想业务接口。 */
public interface BookmarkService {

    /**
     * 读取主人全部书签，只能由已授权的主人接口调用。
     *
     * @return 全部书签
     */
    List<BookmarkView> listAll();

    /**
     * 读取书中感想；访客必须能读正文且只能看到公开条目。
     *
     * @param bookId 书籍 UUID
     * @param owner 是否为已认证的管理员
     * @return 当前身份可见的书签
     */
    List<BookmarkView> listForBook(String bookId, boolean owner);

    /**
     * 校验阅读位置后新增书签，同位置重复写入转为业务冲突。
     *
     * @param input 书签内容
     * @return 新建书签
     */
    BookmarkView create(BookmarkCreateRequest input);

    /**
     * 修改感想或公开标志，不改变原始书签位置。
     *
     * @param id 书签 UUID
     * @param edit 编辑内容
     * @return 更新后的书签
     */
    BookmarkView update(String id, BookmarkEditRequest edit);

    /**
     * 删除指定书签，不存在时返回业务错误。
     *
     * @param id 书签 UUID
     */
    void delete(String id);
}
