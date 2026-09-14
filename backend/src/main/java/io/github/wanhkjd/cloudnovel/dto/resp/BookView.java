package io.github.wanhkjd.cloudnovel.dto.resp;

/**
 * 经权限裁剪后的书目信息，不包含磁盘路径或文件哈希。
 *
 * @param id 书籍 UUID
 * @param title 书名
 * @param author 作者
 * @param description 主人填写的简介，不是正文
 * @param encoding 原件字符编码
 * @param chapterCount 章节总数
 * @param volumeCount 卷数
 * @param characterCount 非空白正文 Unicode 码点数
 * @param catalogPublished 书目是否公开
 * @param textPublished 正文是否公开
 * @param createdAt 创建时间，Unix 毫秒
 * @param canRead 当前调用者是否有正文访问权
 * @param preface 仅在详情且调用者可读时返回前言，否则为空
 */
public record BookView(
        String id,
        String title,
        String author,
        String description,
        String encoding,
        int chapterCount,
        int volumeCount,
        long characterCount,
        boolean catalogPublished,
        boolean textPublished,
        long createdAt,
        boolean canRead,
        String preface) {}
