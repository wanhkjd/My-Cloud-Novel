package io.github.wanhkjd.cloudnovel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 个人云书库后端入口；组件扫描覆盖 Controller、Service、Mapper 和基础设施配置。 */
@SpringBootApplication
public class CloudNovelApplication {
    /** 创建 Spring Boot 应用配置。 */
    public CloudNovelApplication() {}

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 命令行配置参数，不应传入会被进程列表暴露的密码
     */
    public static void main(String[] args) {
        SpringApplication.run(CloudNovelApplication.class, args);
    }
}
