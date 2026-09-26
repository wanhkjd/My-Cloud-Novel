package io.github.wanhkjd.cloudnovel.controller;

import io.github.wanhkjd.cloudnovel.dto.resp.ConfigView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公开配置入口；把 multipart 上限下发给前端，使体积提示与预检随外部配置变化。 */
@RestController
public class ConfigController {
    /** 单个 TXT 上传的字节上限，来源于 {@code spring.servlet.multipart.max-file-size}。 */
    private final long maxUploadBytes;

    /**
     * 从 multipart 配置读取单文件上限，缓存为字节数。
     *
     * @param maxFileSize 框架解析后的单文件上限
     */
    public ConfigController(
            @Value("${spring.servlet.multipart.max-file-size}") DataSize maxFileSize) {
        this.maxUploadBytes = maxFileSize.toBytes();
    }

    /**
     * 返回前端渲染上传约束所需的公开配置。
     *
     * @return 仅含上传字节上限的配置视图
     */
    @GetMapping("/api/config")
    public ConfigView config() {
        return new ConfigView(maxUploadBytes);
    }
}
