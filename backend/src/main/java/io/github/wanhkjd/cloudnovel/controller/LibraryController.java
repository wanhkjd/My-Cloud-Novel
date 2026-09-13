package io.github.wanhkjd.cloudnovel.controller;

import io.github.wanhkjd.cloudnovel.dto.BookEditRequest;
import io.github.wanhkjd.cloudnovel.security.CurrentUser;
import io.github.wanhkjd.cloudnovel.service.LibraryService;
import io.github.wanhkjd.cloudnovel.vo.BookView;
import io.github.wanhkjd.cloudnovel.vo.ChapterSummaryView;
import io.github.wanhkjd.cloudnovel.vo.ChapterView;
import io.github.wanhkjd.cloudnovel.vo.DownloadFile;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 书库 HTTP 入口；只处理请求、参数校验和响应，不直接访问 Mapper 或文件。 */
@RestController
@RequestMapping("/api/books")
public class LibraryController {
    /** 书库业务接口，不依赖具体实现。 */
    private final LibraryService libraryService;

    /**
     * 注入书库业务接口。
     *
     * @param libraryService 书库业务接口
     */
    public LibraryController(LibraryService libraryService) {
        this.libraryService = libraryService;
    }

    /**
     * 获取当前身份可见的书架。
     *
     * @param authentication 当前认证信息
     * @return 可见书目列表
     */
    @GetMapping
    public List<BookView> list(Authentication authentication) {
        return libraryService.listBooks(CurrentUser.isOwner(authentication));
    }

    /**
     * 获取书籍详情。
     *
     * @param id 书籍 UUID
     * @param authentication 当前认证信息
     * @return 经权限裁剪后的书籍详情
     */
    @GetMapping("/{id}")
    public BookView book(@PathVariable String id, Authentication authentication) {
        return libraryService.getBook(id, CurrentUser.isOwner(authentication));
    }

    /**
     * 获取章节目录。
     *
     * @param id 书籍 UUID
     * @param authentication 当前认证信息
     * @return 有序目录
     */
    @GetMapping("/{id}/chapters")
    public List<ChapterSummaryView> chapters(
            @PathVariable String id, Authentication authentication) {
        return libraryService.listChapters(id, CurrentUser.isOwner(authentication));
    }

    /**
     * 获取可读章节。
     *
     * @param id 书籍 UUID
     * @param index 从零开始的章节索引
     * @param authentication 当前认证信息
     * @return 按非空段落拆分的单章正文
     */
    @GetMapping("/{id}/chapters/{index}")
    public ChapterView chapter(
            @PathVariable String id, @PathVariable int index, Authentication authentication) {
        return libraryService.getChapter(id, index, CurrentUser.isOwner(authentication));
    }

    /**
     * 上传 TXT 为私有草稿，管理员权限与 CSRF 由安全过滤器校验。
     *
     * @param file 上传的 TXT 文件
     * @return 新建书籍
     * @throws IOException 上传内容或原件存储无法读写
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookView upload(@RequestParam("file") MultipartFile file) throws IOException {
        return libraryService.importNovel(file.getBytes(), file.getOriginalFilename());
    }

    /**
     * 修改书目与公开设置。
     *
     * @param id 书籍 UUID
     * @param edit 已通过字段格式校验的请求
     * @return 更新后的书籍
     */
    @PatchMapping("/{id}")
    public BookView edit(@PathVariable String id, @Valid @RequestBody BookEditRequest edit) {
        return libraryService.updateBook(id, edit);
    }

    /**
     * 删除书籍及其关联记录。
     *
     * @param id 书籍 UUID
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        libraryService.deleteBook(id);
    }

    /**
     * 返回原始 TXT 下载，响应文件名使用 UTF-8 编码而非磁盘路径。
     *
     * @param id 书籍 UUID
     * @param authentication 当前认证信息
     * @return 带下载响应头的原始字节
     * @throws IOException 原件读取失败
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id, Authentication authentication)
            throws IOException {
        DownloadFile download = libraryService.download(id, CurrentUser.isOwner(authentication));
        return ResponseEntity.ok()
                .contentType(
                        MediaType.parseMediaType(
                                "text/plain;charset=" + download.book().encoding()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.book().title() + ".txt", StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(download.bytes());
    }
}
