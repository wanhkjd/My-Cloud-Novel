package io.github.wanhkjd.cloudnovel.service;

import io.github.wanhkjd.cloudnovel.dto.BookEditRequest;
import io.github.wanhkjd.cloudnovel.vo.BookView;
import io.github.wanhkjd.cloudnovel.vo.ChapterSummaryView;
import io.github.wanhkjd.cloudnovel.vo.ChapterView;
import io.github.wanhkjd.cloudnovel.vo.DownloadFile;
import java.io.IOException;
import java.util.List;

/** 书库业务接口；调用方传入可信身份，所有正文入口统一执行可读性校验。 */
public interface LibraryService {

    /**
     * 获取当前身份可见的书架，列表始终不返回前言。
     *
     * @param owner 是否为已认证的唯一管理员
     * @return 可见书目列表
     */
    List<BookView> listBooks(boolean owner);

    /**
     * 获取书籍详情，并根据当前身份裁剪前言。
     *
     * @param id 书籍 UUID
     * @param owner 是否为管理员
     * @return 可见的书籍详情
     */
    BookView getBook(String id, boolean owner);

    /**
     * 校验正文阅读权限；不存在与未公开使用相同错误以免泄露私有书目。
     *
     * @param id 书籍 UUID
     * @param owner 是否为管理员
     * @return 已通过正文可读性校验的书籍
     */
    BookView requireReadableBook(String id, boolean owner);

    /**
     * 获取可读书籍的章节目录。
     *
     * @param id 书籍 UUID
     * @param owner 是否为管理员
     * @return 有序目录，不含正文
     */
    List<ChapterSummaryView> listChapters(String id, boolean owner);

    /**
     * 读取一章，并转换为稳定的非空段落索引。
     *
     * @param id 书籍 UUID
     * @param index 从零开始的章节索引
     * @param owner 是否为管理员
     * @return 章节正文及元数据
     */
    ChapterView getChapter(String id, int index, boolean owner);

    /**
     * 验证主人书签或阅读记录的位置；空正文章节只允许第零段。
     *
     * @param id 书籍 UUID
     * @param chapterIndex 章节索引
     * @param paragraphIndex 段落索引
     * @throws IllegalArgumentException 段落位置超出范围
     */
    void validatePosition(String id, int chapterIndex, int paragraphIndex);

    /**
     * 解析并导入 TXT 为私有草稿；数据库提交失败时补偿删除新原件。
     *
     * @param bytes 未转码的文件字节
     * @param filename 原始文件名，仅用于校验与提取书名，不作为磁盘路径
     * @return 新建的私有书籍
     * @throws IOException 私有原件写入失败
     * @throws IllegalArgumentException 小说格式或书名不合法
     */
    BookView importNovel(byte[] bytes, String filename) throws IOException;

    /**
     * 编辑书目与公开设置；禁止在书目私有时单独公开正文。
     *
     * @param id 书籍 UUID
     * @param edit 待更新的元数据
     * @return 更新后的书籍
     */
    BookView updateBook(String id, BookEditRequest edit);

    /**
     * 事务删除书籍及关联记录，数据库提交成功后再清理原件。
     *
     * @param id 书籍 UUID
     */
    void deleteBook(String id);

    /**
     * 校验可读性后下载未经转码的原件。
     *
     * @param id 书籍 UUID
     * @param owner 是否为管理员
     * @return 下载字节与书目
     * @throws IOException 原件读取发生 I/O 故障
     */
    DownloadFile download(String id, boolean owner) throws IOException;
}
