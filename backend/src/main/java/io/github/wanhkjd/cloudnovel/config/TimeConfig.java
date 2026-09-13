package io.github.wanhkjd.cloudnovel.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为业务层提供可替换的时钟，避免测试依赖机器当前时间。 */
@Configuration
public class TimeConfig {
    /** 创建时间基础设施配置。 */
    public TimeConfig() {}

    /**
     * 创建 UTC 时钟；业务统计日期另按北京时间转换。
     *
     * @return 系统 UTC 时钟
     */
    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }
}
