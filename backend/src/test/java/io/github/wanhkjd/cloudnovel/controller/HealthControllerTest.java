package io.github.wanhkjd.cloudnovel.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 存活端点迁移后保持原 HTTP 契约，且无需初始化 MySQL、Redis 或认证服务。 */
class HealthControllerTest {
    @Test
    void livenessIsIndependentOfAuthenticationAndDatabases() throws Exception {
        MockMvcBuilders.standaloneSetup(new HealthController())
                .build()
                .perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
