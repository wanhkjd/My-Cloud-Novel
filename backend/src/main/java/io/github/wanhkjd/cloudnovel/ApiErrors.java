package io.github.wanhkjd.cloudnovel;

import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason() == null ? "请求失败。" : error.getReason()));
    }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    ResponseEntity<?> invalid(Exception error) {
        String message = error instanceof IllegalArgumentException ? error.getMessage() : "提交的字段格式或长度不正确，请检查后重试。";
        return ResponseEntity.badRequest().body(Map.of("message", message == null ? "参数不正确。" : message));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge() {
        return ResponseEntity.status(413).body(Map.of("message", "文件不能超过 25 MiB。"));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> notFound() { return ResponseEntity.status(404).body(Map.of("message", "接口不存在。")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception error) {
        if (error instanceof ErrorResponse response)
            return ResponseEntity.status(response.getStatusCode()).body(Map.of("message", "请求格式不正确或接口不支持此操作。"));
        LoggerFactory.getLogger(ApiErrors.class).error("API request failed", error);
        return ResponseEntity.internalServerError().body(Map.of("message", "服务器处理失败，请稍后再试或检查服务日志。"));
    }
}
