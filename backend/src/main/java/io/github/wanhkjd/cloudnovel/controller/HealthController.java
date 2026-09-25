package io.github.wanhkjd.cloudnovel.controller;

import io.github.wanhkjd.cloudnovel.dto.resp.HealthView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 进程存活检查入口；依赖就绪状态由 Actuator 的 /api/ready 单独提供。 */
@RestController
public class HealthController {
    /** 创建无外部依赖的存活检查控制器。 */
    public HealthController() {}

    /**
     * 检查服务进程是否能响应请求，不触发数据库连接。
     *
     * @return 固定存活标识
     */
    @GetMapping("/api/health")
    public HealthView health() {
        return new HealthView("ok");
    }
}
