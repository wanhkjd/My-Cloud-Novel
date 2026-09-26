package io.github.wanhkjd.cloudnovel.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.wanhkjd.cloudnovel.dto.resp.ErrorView;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** 大文件 413 文案应随配置的 multipart 上限变化，而非写死某个数值。 */
class ApiExceptionHandlerTest {
    @Test
    void tooLargeMessageStatesTheConfiguredMebibyteCeiling() {
        var response = new ApiExceptionHandler(DataSize.ofMegabytes(30)).tooLarge();
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).extracting(ErrorView::message).isEqualTo("文件不能超过 30 MiB。");
    }

    @Test
    void tooLargeMessageTracksAReconfiguredCeiling() {
        var response = new ApiExceptionHandler(DataSize.ofMegabytes(50)).tooLarge();
        assertThat(response.getBody()).extracting(ErrorView::message).isEqualTo("文件不能超过 50 MiB。");
    }
}
