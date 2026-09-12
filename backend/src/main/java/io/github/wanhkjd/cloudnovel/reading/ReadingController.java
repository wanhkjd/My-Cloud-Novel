package io.github.wanhkjd.cloudnovel.reading;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/me")
public class ReadingController {
    private final ReadingLog log;
    public ReadingController(ReadingLog log) { this.log = log; }
    @GetMapping("/progress") List<ReadingLog.Progress> progress() { return log.progress(); }
    @GetMapping("/progress/{book}") ReadingLog.Progress progress(@PathVariable String book) { return log.progress(book); }
    @PutMapping("/progress/{book}") ReadingLog.Progress save(@PathVariable String book, @Valid @RequestBody ReadingLog.Position position) { return log.saveProgress(book, position); }
    @PutMapping("/sessions/{id}") ReadingLog.Session session(@PathVariable String id, @Valid @RequestBody ReadingLog.SessionInput input) { return log.saveSession(id, input); }
    @GetMapping("/history") List<ReadingLog.Session> history() { return log.history(); }
    @GetMapping("/stats") ReadingLog.Stats stats() { return log.stats(); }
}
