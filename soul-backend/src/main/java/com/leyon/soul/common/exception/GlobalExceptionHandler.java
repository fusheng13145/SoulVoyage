package com.leyon.soul.common.exception;

import com.leyon.soul.common.api.ApiResponse;
import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.api.ErrorCodeMapping;
import com.leyon.soul.orchestrator.agent.LlmUnavailableException;
import com.leyon.soul.orchestrator.agent.OutputInvalidException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
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
        return json(ErrorCodeMapping.statusOf(e.getErrorCode()))
                .body(ApiResponse.fail(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> valid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("参数不合法");
        return badRequest(msg);
    }

    /** Bean Validation 的方法级校验（@Validated + @RequestParam 等）同样收口为 2001。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> constraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(v -> v.getMessage()).findFirst().orElse("参数不合法");
        return badRequest(msg);
    }

    /** JSON 体不可解析：客户端问题，不能落进 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException e) {
        log.debug("request body unreadable: {}", e.getMessage());
        return badRequest("请求体格式不正确");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> missingParam(MissingServletRequestParameterException e) {
        return badRequest("缺少参数: " + e.getParameterName());
    }

    /** Content-Type 不被支持：415，而不是掉进 500——客户端用错编码时能直接看懂要改什么 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        log.debug("unsupported media type: {}", e.getContentType());
        return json(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.fail(ErrorCode.BAD_PARAMS, "请求体类型不支持，请用 application/json"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest("参数格式不正确: " + e.getName());
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

    /** 依赖不可达（MySQL / Redis）：503 + 5002，让前端提示稍后重试而不是“参数错误”。 */
    @ExceptionHandler({DataAccessResourceFailureException.class, RedisConnectionFailureException.class})
    public ResponseEntity<ApiResponse<Void>> dependencyDown(Exception e) {
        log.error("dependency unavailable", e);
        return json(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(ErrorCode.DEPENDENCY_UNAVAILABLE, null));
    }

    /** B-09：乐观锁冲突——同一聚合被并发修改，让客户端刷新后重试而不是 500 */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> optimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("optimistic lock conflict: {}", e.getMessage());
        return json(HttpStatus.CONFLICT).body(ApiResponse.fail(ErrorCode.CONFLICT, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> other(Exception e) {
        log.error("unhandled exception", e);
        return json(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, null));
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

    private static ResponseEntity<ApiResponse<Void>> badRequest(String msg) {
        return json(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(ErrorCode.BAD_PARAMS, msg));
    }
}
