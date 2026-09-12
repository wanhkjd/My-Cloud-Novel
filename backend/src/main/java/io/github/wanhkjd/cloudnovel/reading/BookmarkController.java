package io.github.wanhkjd.cloudnovel.reading;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static io.github.wanhkjd.cloudnovel.security.AuthController.isOwner;

@RestController
public class BookmarkController {
    private final Bookmarks bookmarks;
    public BookmarkController(Bookmarks bookmarks) { this.bookmarks = bookmarks; }
    @GetMapping("/api/books/{book}/bookmarks") List<Bookmarks.Bookmark> forBook(@PathVariable String book, Authentication auth) { return bookmarks.forBook(book, isOwner(auth)); }
    @GetMapping("/api/me/bookmarks") List<Bookmarks.Bookmark> all() { return bookmarks.all(); }
    @PostMapping("/api/me/bookmarks") @ResponseStatus(HttpStatus.CREATED) Bookmarks.Bookmark create(@Valid @RequestBody Bookmarks.Input input) { return bookmarks.create(input); }
    @PatchMapping("/api/me/bookmarks/{id}") Bookmarks.Bookmark edit(@PathVariable String id, @Valid @RequestBody Bookmarks.Edit edit) { return bookmarks.edit(id, edit); }
    @DeleteMapping("/api/me/bookmarks/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@PathVariable String id) { bookmarks.delete(id); }
}
