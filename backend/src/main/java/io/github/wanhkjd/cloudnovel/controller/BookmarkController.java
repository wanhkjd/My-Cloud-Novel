package io.github.wanhkjd.cloudnovel.controller;

import io.github.wanhkjd.cloudnovel.core.auth.CurrentUser;
import io.github.wanhkjd.cloudnovel.dto.req.BookmarkCreateRequest;
import io.github.wanhkjd.cloudnovel.dto.req.BookmarkEditRequest;
import io.github.wanhkjd.cloudnovel.dto.resp.BookmarkView;
import io.github.wanhkjd.cloudnovel.service.BookmarkService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 书签 HTTP 入口；公开感想与主人私有管理使用不同路径。 */
@RestController
public class BookmarkController {
    /** 书签业务接口。 */
    private final BookmarkService bookmarkService;

    /**
     * 注入书签业务接口。
     *
     * @param bookmarkService 书签业务接口
     */
    public BookmarkController(BookmarkService bookmarkService) {
        this.bookmarkService = bookmarkService;
    }

    /**
     * 获取一本书中当前身份可见的感想。
     *
     * @param book 书籍 UUID
     * @param authentication 当前认证信息
     * @return 可见感想
     */
    @GetMapping("/api/books/{book}/bookmarks")
    public List<BookmarkView> forBook(@PathVariable String book, Authentication authentication) {
        return bookmarkService.listForBook(book, CurrentUser.isOwner(authentication));
    }

    /**
     * 获取主人所有书签。
     *
     * @return 主人书签列表
     */
    @GetMapping("/api/me/bookmarks")
    public List<BookmarkView> all() {
        return bookmarkService.listAll();
    }

    /**
     * 创建书签。
     *
     * @param input 书签位置与感想
     * @return 新建书签
     */
    @PostMapping("/api/me/bookmarks")
    @ResponseStatus(HttpStatus.CREATED)
    public BookmarkView create(@Valid @RequestBody BookmarkCreateRequest input) {
        return bookmarkService.create(input);
    }

    /**
     * 编辑书签感想与公开标志。
     *
     * @param id 书签 UUID
     * @param edit 编辑内容
     * @return 更新后的书签
     */
    @PatchMapping("/api/me/bookmarks/{id}")
    public BookmarkView edit(
            @PathVariable String id, @Valid @RequestBody BookmarkEditRequest edit) {
        return bookmarkService.update(id, edit);
    }

    /**
     * 删除书签。
     *
     * @param id 书签 UUID
     */
    @DeleteMapping("/api/me/bookmarks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        bookmarkService.delete(id);
    }
}
