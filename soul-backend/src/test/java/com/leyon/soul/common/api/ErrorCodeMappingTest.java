package com.leyon.soul.common.api;

import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-19 错误码契约：码值只增不改、每个错误码有唯一 HTTP 状态映射，
 * 未知异常与依赖不可达分别收敛为 500/5000、503/5002（A-12）。
 */
class ErrorCodeMappingTest {

    /** 全量冻结：新增错误码必须同步登记在此，避免映射表悄悄漂移。 */
    private static final Map<ErrorCode, HttpStatus> EXPECTED = Map.ofEntries(
            Map.entry(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED),
            Map.entry(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCode.LOGIN_FAILED, HttpStatus.UNAUTHORIZED),
            Map.entry(ErrorCode.USERNAME_EXISTS, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.WX_LOGIN_FAILED, HttpStatus.BAD_GATEWAY),
            Map.entry(ErrorCode.WX_BIND_EXPIRED, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.BAD_PARAMS, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.CONFLICT, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.TASK_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.PIPELINE_NOT_ALLOWED, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.TASK_CONCURRENCY_LIMIT, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.LLM_TIMEOUT, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(ErrorCode.LLM_RATE_LIMIT, HttpStatus.TOO_MANY_REQUESTS),
            Map.entry(ErrorCode.LLM_OUTPUT_INVALID, HttpStatus.BAD_GATEWAY),
            Map.entry(ErrorCode.ASR_FAILED, HttpStatus.BAD_GATEWAY),
            Map.entry(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(ErrorCode.CRISIS_MODE_RESTRICTED, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCode.DEPENDENCY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS));

    private static final Map<ErrorCode, Integer> PINNED_VALUES = Map.ofEntries(
            Map.entry(ErrorCode.UNAUTHORIZED, 1001),
            Map.entry(ErrorCode.FORBIDDEN, 1002),
            Map.entry(ErrorCode.LOGIN_FAILED, 1003),
            Map.entry(ErrorCode.USERNAME_EXISTS, 1004),
            Map.entry(ErrorCode.WX_LOGIN_FAILED, 1005),
            Map.entry(ErrorCode.WX_BIND_EXPIRED, 1006),
            Map.entry(ErrorCode.BAD_PARAMS, 2001),
            Map.entry(ErrorCode.NOT_FOUND, 2002),
            Map.entry(ErrorCode.CONFLICT, 2003),
            Map.entry(ErrorCode.TASK_NOT_FOUND, 3001),
            Map.entry(ErrorCode.PIPELINE_NOT_ALLOWED, 3002),
            Map.entry(ErrorCode.TASK_CONCURRENCY_LIMIT, 3003),
            Map.entry(ErrorCode.LLM_TIMEOUT, 4001),
            Map.entry(ErrorCode.LLM_RATE_LIMIT, 4002),
            Map.entry(ErrorCode.LLM_OUTPUT_INVALID, 4003),
            Map.entry(ErrorCode.ASR_FAILED, 4004),
            Map.entry(ErrorCode.INTERNAL_ERROR, 5000),
            Map.entry(ErrorCode.CRISIS_MODE_RESTRICTED, 5001),
            Map.entry(ErrorCode.DEPENDENCY_UNAVAILABLE, 5002),
            Map.entry(ErrorCode.RATE_LIMITED, 1007));

    @Test
    @DisplayName("错误码只增不改：码值与 HTTP 映射全量冻结")
    void everyCodeHasPinnedValueAndStatus() {
        assertEquals(ErrorCode.values().length, EXPECTED.size(), "映射表必须覆盖全部错误码");
        assertEquals(ErrorCode.values().length, PINNED_VALUES.size(), "码值表必须覆盖全部错误码");
        for (ErrorCode code : ErrorCode.values()) {
            assertEquals(PINNED_VALUES.get(code), code.code(), code.name() + " 码值不得漂移");
            assertEquals(EXPECTED.get(code), ErrorCodeMapping.statusOf(code), code.name() + " HTTP 映射不得漂移");
            assertNotNull(code.defaultMsg(), code.name() + " 必须有默认话术");
        }
    }

    @Test
    @DisplayName("未知异常：HTTP 500 + 5000（不再冒充 2001）")
    void unknownExceptionBecomesInternalError() {
        var res = new GlobalExceptionHandler().other(new IllegalStateException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
        assertEquals(ErrorCode.INTERNAL_ERROR.code(), res.getBody().getCode());
        assertEquals(ErrorCode.INTERNAL_ERROR.defaultMsg(), res.getBody().getMsg());
        assertEquals(MediaType.APPLICATION_JSON, res.getHeaders().getContentType());
    }

    @Test
    @DisplayName("依赖不可达：HTTP 503 + 5002")
    void dependencyFailureBecomesServiceUnavailable() {
        var res = new GlobalExceptionHandler().dependencyDown(new DataAccessResourceFailureException("db down"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE.code(), res.getBody().getCode());
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE.defaultMsg(), res.getBody().getMsg());
    }

    @Test
    @DisplayName("业务异常：保留原码，并按映射表给出 HTTP 状态")
    void bizExceptionKeepsCode() {
        var res = new GlobalExceptionHandler().biz(new BizException(ErrorCode.NOT_FOUND));

        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertEquals(2002, res.getBody().getCode());
        assertEquals(ErrorCode.NOT_FOUND.defaultMsg(), res.getBody().getMsg());
    }

    @Test
    @DisplayName("缺参/不可解析请求：统一 400 + 2001")
    void parameterProblemsStayBadRequest() {
        var missing = new GlobalExceptionHandler()
                .missingParam(new MissingServletRequestParameterException("month", "String"));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertEquals(ErrorCode.BAD_PARAMS.code(), missing.getBody().getCode());
        assertTrue(missing.getBody().getMsg().contains("month"));
    }

    @Test
    @DisplayName("B-09 乐观锁冲突：HTTP 409 + 2003，不落 500")
    void optimisticLockConflictBecomes409() {
        var res = new GlobalExceptionHandler()
                .optimisticLock(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                        "UserPreferencesEntity", 1L));

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
        assertEquals(ErrorCode.CONFLICT.code(), res.getBody().getCode());
        assertEquals(ErrorCode.CONFLICT.defaultMsg(), res.getBody().getMsg());
    }

    @Test
    @DisplayName("Content-Type 不支持：HTTP 415 + 2001，不落 500")
    void unsupportedMediaTypeBecomes415() {
        var res = new GlobalExceptionHandler()
                .unsupportedMediaType(new org.springframework.web.HttpMediaTypeNotSupportedException(
                        MediaType.APPLICATION_FORM_URLENCODED, java.util.List.of(MediaType.APPLICATION_JSON)));

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, res.getStatusCode());
        assertEquals(ErrorCode.BAD_PARAMS.code(), res.getBody().getCode());
        assertTrue(res.getBody().getMsg().contains("application/json"));
    }

    @Test
    @DisplayName("上传超限：仍是 400 + 业务话术，不落 500")
    void oversizedUploadStaysBadRequest() {
        var res = new GlobalExceptionHandler().tooLarge(new MaxUploadSizeExceededException(6_000_000));

        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals(ErrorCode.BAD_PARAMS.code(), res.getBody().getCode());
        assertTrue(res.getBody().getMsg().contains("录音太大"));
    }
}
