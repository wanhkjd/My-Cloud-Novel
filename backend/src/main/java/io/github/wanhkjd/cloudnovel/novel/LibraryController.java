package io.github.wanhkjd.cloudnovel.novel;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static io.github.wanhkjd.cloudnovel.security.AuthController.isOwner;

@RestController
@RequestMapping("/api/books")
public class LibraryController {
    private final Library library;
    public LibraryController(Library library) { this.library = library; }
    @GetMapping List<Library.Book> list(Authentication auth) { return library.list(isOwner(auth)); }
    @GetMapping("/{id}") Library.Book book(@PathVariable String id, Authentication auth) { return library.book(id, isOwner(auth)); }
    @GetMapping("/{id}/chapters") List<Library.ChapterSummary> chapters(@PathVariable String id, Authentication auth) { return library.chapters(id, isOwner(auth)); }
    @GetMapping("/{id}/chapters/{index}") Library.Chapter chapter(@PathVariable String id, @PathVariable int index, Authentication auth) { return library.chapter(id, index, isOwner(auth)); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) Library.Book upload(@RequestParam("file") MultipartFile file) throws IOException { return library.upload(file.getBytes(), file.getOriginalFilename()); }
    @PatchMapping("/{id}") Library.Book edit(@PathVariable String id, @Valid @RequestBody Library.Edit edit) { return library.edit(id, edit); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@PathVariable String id) { library.delete(id); }
    @GetMapping("/{id}/download") ResponseEntity<byte[]> download(@PathVariable String id, Authentication auth) throws IOException {
        var download = library.download(id, isOwner(auth));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/plain;charset=" + download.book().encoding()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(download.book().title() + ".txt", StandardCharsets.UTF_8).build().toString())
            .body(download.bytes());
    }
}
