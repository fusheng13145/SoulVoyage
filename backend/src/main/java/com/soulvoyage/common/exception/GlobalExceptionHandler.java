package com.soulvoyage.common.exception;

import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.api.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * S3：业务异常映射真实 HTTP 状态码（401/403/404/409/4xx/5xx），
 * body 仍返回统一 ApiResponse 结构且 code 保留原错误码——前端两套判据都可用。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> biz(BizException e) {
        return ResponseEntity.status(httpStatus(e.getErrorCode()))
                .body(ApiResponse.fail(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> valid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("参数不合法");
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.BAD_PARAMS, msg));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> denied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(ErrorCode.FORBIDDEN, null));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> auth(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(ErrorCode.UNAUTHORIZED, null));
    }

    /** 客户端中途断连（SSE 掐线等）属正常现象：吞掉，避免 ERROR 噪音与向 event-stream 写 JSON 的二次失败 */
    @ExceptionHandler(IOException.class)
    public void clientAbort(IOException e) {
        log.debug("client aborted connection: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> other(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.BAD_PARAMS, "服务内部错误"));
    }

    private static HttpStatus httpStatus(ErrorCode code) {
        return switch (code) {
            case UNAUTHORIZED, LOGIN_FAILED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, CRISIS_MODE_RESTRICTED -> HttpStatus.FORBIDDEN;
            case USERNAME_EXISTS, TASK_CONCURRENCY_LIMIT -> HttpStatus.CONFLICT;
            case NOT_FOUND, TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case LLM_RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case LLM_TIMEOUT -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;   // 20xx/30xx 其余参数与流程类
        };
    }
}
