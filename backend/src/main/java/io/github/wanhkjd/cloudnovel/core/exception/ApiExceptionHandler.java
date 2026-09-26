package io.github.wanhkjd.cloudnovel.core.exception;

import io.github.wanhkjd.cloudnovel.dto.resp.ErrorView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Web 层统一异常转换；前端只得到安全说明，内部堆栈只进入服务器日志。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    /** 未预期故障的服务器日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 单个 TXT 上传的字节上限，用于生成与配置一致的 413 文案。 */
    private final long maxUploadBytes;

    /**
     * 记录当前的 multipart 单文件上限，使超限文案随外部配置变化。
     *
     * @param maxFileSize 框架解析后的单文件上限
     */
    public ApiExceptionHandler(
            @Value("${spring.servlet.multipart.max-file-size}") DataSize maxFileSize) {
        this.maxUploadBytes = maxFileSize.toBytes();
    }

    /**
     * 把与协议无关的业务错误映射为 HTTP 状态。
     *
     * @param error 可预期的业务错误
     * @return 404 或 409 错误响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorView> business(BusinessException error) {
        int status =
                switch (error.getKind()) {
                    case NOT_FOUND -> 404;
                    case CONFLICT -> 409;
                };
        return ResponseEntity.status(status).body(new ErrorView(error.getMessage()));
    }

    /**
     * 保留框架显式抛出的 HTTP 错误状态。
     *
     * @param error 框架状态异常
     * @return 带原状态码的安全响应
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorView> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(new ErrorView(error.getReason() == null ? "请求失败。" : error.getReason()));
    }

    /**
     * 处理参数类型转换、领域校验、Bean Validation 与 JSON 解析失败。
     *
     * @param error 输入错误
     * @return 400 响应，不回传无法解析的原始请求体
     */
    @ExceptionHandler({
        IllegalArgumentException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class,
        TypeMismatchException.class
    })
    public ResponseEntity<ErrorView> invalid(Exception error) {
        String message =
                error instanceof IllegalArgumentException
                        ? error.getMessage()
                        : "提交的字段格式或长度不正确，请检查后重试。";
        return ResponseEntity.badRequest()
                .body(new ErrorView(message == null ? "参数不正确。" : message));
    }

    /**
     * 处理框架拒绝的大文件。
     *
     * @return 413 响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorView> tooLarge() {
        return ResponseEntity.status(413)
                .body(new ErrorView("文件不能超过 " + formatMebibytes(maxUploadBytes) + " MiB。"));
    }

    /**
     * 把字节上限渲染为便于阅读的 MiB 数：整除时取整数，否则保留原始小数。
     *
     * @param bytes 字节上限
     * @return 用于文案的 MiB 表示
     */
    private static String formatMebibytes(long bytes) {
        double mebibytes = bytes / (1024.0 * 1024.0);
        return mebibytes == Math.floor(mebibytes)
                ? String.valueOf((long) mebibytes)
                : String.valueOf(mebibytes);
    }

    /**
     * 处理不存在的 HTTP 路径。
     *
     * @return 404 响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorView> notFound() {
        return ResponseEntity.status(404).body(new ErrorView("接口不存在。"));
    }

    /**
     * 处理其余框架错误与未预期故障，禁止向客户端输出数据库或文件系统细节。
     *
     * @param error 未被其他处理器捕获的异常
     * @return 框架错误状态或通用 500 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorView> unexpected(Exception error) {
        if (error instanceof ErrorResponse response) {
            return ResponseEntity.status(response.getStatusCode())
                    .body(new ErrorView("请求格式不正确或接口不支持此操作。"));
        }
        LOG.error("API request failed", error);
        return ResponseEntity.internalServerError().body(new ErrorView("服务器处理失败，请稍后再试或检查服务日志。"));
    }
}
