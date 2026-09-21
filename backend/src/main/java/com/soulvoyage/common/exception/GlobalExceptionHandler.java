package com.soulvoyage.common.exception;

import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.orchestrator.agent.LlmUnavailableException;
import com.soulvoyage.orchestrator.agent.OutputInvalidException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;

/**
 * S3：业务异常映射真实 HTTP 状态码（401/403/404/409/4xx/5xx），
 * body 仍返回统一 ApiResponse 结构且 code 保留原错误码——前端两套判据都可用。
 *
 * 所有错误响应显式声明 Content-Type: application/json：SSE 端点（任务流/逐轮流）的
 * Accept 只有 text/event-stream，靠内容协商会让异常处理器二次失败并退化成 500。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> biz(BizException e) {
        return json(httpStatus(e.getErrorCode())).body(ApiResponse.fail(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> valid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("参数不合法");
        return json(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(ErrorCode.BAD_PARAMS, msg));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> denied(AccessDeniedException e) {
        return json(HttpStatus.FORBIDDEN).body(ApiResponse.fail(ErrorCode.FORBIDDEN, null));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> auth(AuthenticationException e) {
        return json(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(ErrorCode.UNAUTHORIZED, null));
    }

    /** 客户端中途断连（SSE 掐线等）属正常现象：吞掉，避免 ERROR 噪音与向 event-stream 写 JSON 的二次失败 */
    @ExceptionHandler(IOException.class)
    public void clientAbort(IOException e) {
        log.debug("client aborted connection: {}", e.getMessage());
    }

    /** 4001：模型侧不可用（超时/上游 5xx/排队放弃）——同步调用方（内容预览试跑等）走这里 */
    @ExceptionHandler(LlmUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> llmDown(LlmUnavailableException e) {
        log.warn("llm unavailable: {}", e.getMessage());
        return json(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(ErrorCode.LLM_TIMEOUT, e.getMessage()));
    }

    /** 4003：模型输出没过 Schema/越界词校验——异步链路里表现为步骤 DEGRADED，同步链路里走这里 */
    @ExceptionHandler(OutputInvalidException.class)
    public ResponseEntity<ApiResponse<Void>> llmOutputInvalid(OutputInvalidException e) {
        log.warn("llm output rejected: {}", e.getMessage());
        return json(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail(ErrorCode.LLM_OUTPUT_INVALID, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> other(Exception e) {
        log.error("unhandled exception", e);
        return json(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.BAD_PARAMS, "服务内部错误"));
    }

    /** 上传体积超过容器上限时 Spring 先于业务抛：换成 2001 的业务话术，别把 500 甩给录音的用户 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooLarge(MaxUploadSizeExceededException e) {
        return json(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ErrorCode.BAD_PARAMS, "这段录音太大了，换一个短一点的吧"));
    }

    private static BodyBuilder json(HttpStatus status) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
    }

    private static HttpStatus httpStatus(ErrorCode code) {
        return switch (code) {
            case UNAUTHORIZED, LOGIN_FAILED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, CRISIS_MODE_RESTRICTED -> HttpStatus.FORBIDDEN;
            case USERNAME_EXISTS, TASK_CONCURRENCY_LIMIT -> HttpStatus.CONFLICT;
            case NOT_FOUND, TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case LLM_RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case LLM_TIMEOUT -> HttpStatus.SERVICE_UNAVAILABLE;
            case ASR_FAILED, WX_LOGIN_FAILED -> HttpStatus.BAD_GATEWAY;   // 第三方没答上来，不是客户端的错
            default -> HttpStatus.BAD_REQUEST;   // 20xx/30xx 其余参数与流程类
        };
    }
}
