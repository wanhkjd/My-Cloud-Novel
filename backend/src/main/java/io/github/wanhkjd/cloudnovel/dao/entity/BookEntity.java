package io.github.wanhkjd.cloudnovel.dao.entity;

/**
 * 书籍持久化实体，对应 books 表；不得直接作为 HTTP 响应。
 *
 * @param id 书籍 UUID
 * @param title 书名
 * @param author 作者
 * @param description 主人填写的简介，不是正文
 * @param encoding 原件字符编码
 * @param chapterCount 章节总数
 * @param volumeCount 卷数
 * @param characterCount 非空白正文 Unicode 码点数
 * @param preface 前言正文，公开性由业务层判断
 * @param sha256 原始字节的 SHA-256，用于重复导入检测
 * @param catalogPublished 书目是否公开
 * @param textPublished 正文是否公开
 * @param createdAt 创建时间，Unix 毫秒
 * @param timelineDate 自定义时间轴日期，可空；为空时前端回退 createdAt
 * @param coverPath 封面对象键（如 covers/{id}.jpg），仅存储用，绝不下发前端
 */
public record BookEntity(
        String id,
        String title,
        String author,
        String description,
        String encoding,
        int chapterCount,
        int volumeCount,
        long characterCount,
        String preface,
        String sha256,
        boolean catalogPublished,
        boolean textPublished,
        long createdAt,
        java.time.LocalDate timelineDate,
        String coverPath) {}
