package io.github.wanhkjd.cloudnovel.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;

/** 前端读取上传上限的公开端点；只回传字节数，随 multipart 配置变化，无需数据库或认证。 */
class ConfigControllerTest {
    @Test
    void publishesConfiguredUploadCeilingInBytesWithoutAuthenticationOrDatabase() throws Exception {
        MockMvcBuilders.standaloneSetup(new ConfigController(DataSize.ofMegabytes(30)))
                .build()
                .perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxUploadBytes").value(31457280));
    }

    @Test
    void reflectsAReconfiguredLimitRatherThanAHardcodedValue() throws Exception {
        MockMvcBuilders.standaloneSetup(new ConfigController(DataSize.ofMegabytes(40)))
                .build()
                .perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxUploadBytes").value(41943040));
    }
}
