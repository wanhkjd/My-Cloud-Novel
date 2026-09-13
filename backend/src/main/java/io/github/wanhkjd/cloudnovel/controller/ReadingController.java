package io.github.wanhkjd.cloudnovel.controller;

import io.github.wanhkjd.cloudnovel.dto.PositionRequest;
import io.github.wanhkjd.cloudnovel.dto.ReadingSessionRequest;
import io.github.wanhkjd.cloudnovel.service.ReadingService;
import io.github.wanhkjd.cloudnovel.vo.ProgressView;
import io.github.wanhkjd.cloudnovel.vo.ReadingSessionView;
import io.github.wanhkjd.cloudnovel.vo.ReadingStatsView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 主人阅读记录 HTTP 入口，整个 /api/me 路径由安全层强制要求 ADMIN 角色。 */
@RestController
@RequestMapping("/api/me")
public class ReadingController {
    /** 主人阅读业务接口。 */
    private final ReadingService readingService;

    /**
     * 注入阅读业务接口。
     *
     * @param readingService 阅读业务接口
     */
    public ReadingController(ReadingService readingService) {
        this.readingService = readingService;
    }

    /**
     * 获取所有最近阅读位置。
     *
     * @return 主人的位置列表
     */
    @GetMapping("/progress")
    public List<ProgressView> progress() {
        return readingService.listProgress();
    }

    /**
     * 获取单书阅读位置。
     *
     * @param book 书籍 UUID
     * @return 保存的位置；尚未阅读时为空响应
     */
    @GetMapping("/progress/{book}")
    public ProgressView progress(@PathVariable String book) {
        return readingService.getProgress(book);
    }

    /**
     * 保存最新位置。
     *
     * @param book 书籍 UUID
     * @param position 章节与段落坐标
     * @return 保存后的位置
     */
    @PutMapping("/progress/{book}")
    public ProgressView save(
            @PathVariable String book, @Valid @RequestBody PositionRequest position) {
        return readingService.saveProgress(book, position);
    }

    /**
     * 幂等上报阅读片段的累计秒数。
     *
     * @param id 会话 UUID
     * @param input 累计阅读片段
     * @return 服务器最终保存的会话
     */
    @PutMapping("/sessions/{id}")
    public ReadingSessionView session(
            @PathVariable String id, @Valid @RequestBody ReadingSessionRequest input) {
        return readingService.saveSession(id, input);
    }

    /**
     * 获取最近阅读历史。
     *
     * @return 最近 200 段会话
     */
    @GetMapping("/history")
    public List<ReadingSessionView> history() {
        return readingService.listHistory();
    }

    /**
     * 获取主人阅读统计。
     *
     * @return 以北京时间计算的统计数据
     */
    @GetMapping("/stats")
    public ReadingStatsView stats() {
        return readingService.getStats();
    }
}
