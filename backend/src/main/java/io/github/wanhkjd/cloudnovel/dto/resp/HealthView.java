package io.github.wanhkjd.cloudnovel.dto.resp;

/**
 * 进程健康检查响应，不泄露内部配置。
 *
 * @param status 健康状态标识
 */
public record HealthView(String status) {}
